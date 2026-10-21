/** Dependency-free deterministic Worker bundler for the small tracked ESM set.
 * It intentionally rejects syntax outside that explicit subset. No runtime
 * credentials or campaign content are read by this build.
 */
import { readFile, mkdir, writeFile, copyFile, cp } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const output = resolve(process.argv[2] || resolve(root, 'build'));
const modules = [
  ['core.mjs', 'CORE'], ['http.mjs', 'HTTP'], ['mcp.mjs', 'MCP'],
  ['bootstrap.mjs', 'BOOTSTRAP'], ['worker.mjs', 'ENTRY'],
];
const names = new Map(modules.map(([file, name]) => [`./${file}`, name]));
let bundle = '// Generated from tracked Astravar owner-bridge modules. Do not edit.\n';
for (const [file, name] of modules) {
  let source = await readFile(resolve(root, file), 'utf8');
  const exported = [...source.matchAll(/\bexport\s+(?:async\s+)?(?:class|function|const|let)\s+([A-Za-z_$][\w$]*)/g)].map(match => match[1]);
  source = source.replace(/^import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?\s*$/gm, (_, bindings, dependency) => {
    if (!names.has(dependency)) throw Error(`Unsupported Worker import in ${file}`);
    return `const {${bindings}} = ${names.get(dependency)};`;
  });
  let hasDefault = false;
  source = source.replace(/\bexport\s+default\s+\{\s*fetch:\s*handleWorker\s*\};?/g, () => { hasDefault = true; return 'const __default = { fetch: handleWorker };'; });
  source = source.replace(/\bexport\s+(?=(?:async\s+)?(?:class|function|const|let)\b)/g, '');
  if (/^\s*(?:import|export)\s/m.test(source)) throw Error(`Unsupported module syntax in ${file}`);
  const entries = [...exported, ...(hasDefault ? ['default: __default'] : [])];
  bundle += `const ${name} = (() => {\n${source}\nreturn {${entries.join(',')}};\n})();\n`;
}
bundle += '\nexport default ENTRY.default;\n';
await mkdir(resolve(output, 'server'), { recursive: true });
await writeFile(resolve(output, 'server/index.js'), bundle);
const migrationPath = resolve(root, 'drizzle/0000_owner_bridge.sql');
const normalizeSql = value => value.replace(/^--> statement-breakpoint\s*$/gm, '').replace(/\s+/g, ' ').trim();
if (normalizeSql(await readFile(migrationPath, 'utf8')) !== normalizeSql(await readFile(resolve(root, 'schema.sql'), 'utf8'))) throw Error('Reviewed migration does not match the initial schema. Append a new migration after deployment; never rewrite applied SQL.');
// Sites discovers hosted migrations beneath the artifact's .openai directory.
// A sibling output/drizzle directory is not consumed by the deployment migrator.
await mkdir(resolve(output, '.openai'), { recursive: true });
await cp(resolve(root, 'drizzle'), resolve(output, '.openai', 'drizzle'), { recursive: true });
let hostingConfigured = false;
try {
  const hosting = JSON.parse(await readFile(resolve(root, '.openai/hosting.json'), 'utf8'));
  if (!hosting.project_id || hosting.d1 !== 'DB' || !hosting.capabilities?.includes('mcp')) throw Error('New Site hosting configuration is incomplete');
  await mkdir(resolve(output, '.openai'), { recursive: true });
  await copyFile(resolve(root, '.openai/hosting.json'), resolve(output, '.openai/hosting.json'));
  hostingConfigured = true;
} catch (error) { if (error.code !== 'ENOENT') throw error; }
console.log(JSON.stringify({ worker: resolve(output, 'server/index.js'), migration: resolve(output, '.openai/drizzle/0000_owner_bridge.sql'), hostingConfigured, deployed: false }));
