/** Dependency-free deterministic Worker bundler for the small tracked ESM set.
 * It intentionally rejects syntax outside that explicit subset. No runtime
 * credentials or campaign content are read by this build.
 */
import { readFile, mkdir, writeFile, copyFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const output = resolve(process.argv[2] || resolve(root, 'build'));
const modules = [
  ['core.mjs', 'CORE'], ['native-source.mjs', 'NATIVE_SOURCE'], ['campaign.mjs', 'CAMPAIGN_SYNC'], ['campaign-push.mjs', 'CAMPAIGN_PUSH'], ['campaign-tools.mjs','CAMPAIGN_TOOLS_MODULE'], ['live-hp.mjs', 'LIVE_HP'], ['http.mjs', 'HTTP'],
  ['cloud-validation.mjs','CLOUD_VALIDATION'], ['cloud-plan.mjs','CLOUD_PLAN'], ['cloud-continuity.mjs','CLOUD_CONTINUITY'], ['cloud-query.mjs','CLOUD_QUERY'], ['cloud-records-query.mjs','CLOUD_RECORDS_QUERY'],
  ['cloud-records-contract.mjs','CLOUD_RECORDS_CONTRACT'], ['cloud-holdings.mjs','CLOUD_HOLDINGS'],
  ['cloud-crafting.mjs','CLOUD_CRAFTING'], ['cloud-world.mjs','CLOUD_WORLD'], ['cloud-records.mjs','CLOUD_RECORDS'],
  ['cloud-mechanics-core.mjs','CLOUD_MECHANICS_CORE'], ['cloud-mechanics-profile.mjs','CLOUD_PROFILE'], ['cloud-mechanics-rolls.mjs','CLOUD_ROLLS'],
  ['cloud-mechanics-care.mjs','CLOUD_CARE'], ['cloud-mechanics-linked.mjs','CLOUD_LINKED'], ['cloud-mechanics-combat.mjs','CLOUD_COMBAT'], ['cloud-mechanics-spells.mjs','CLOUD_SPELLS'],
  ['cloud-mechanics-actions.mjs','CLOUD_ACTIONS'], ['cloud-mechanics.mjs','CLOUD_MECHANICS'],
  ['cloud-media-codec.mjs','CLOUD_MEDIA_CODEC'], ['cloud-media.mjs','CLOUD_MEDIA'], ['cloud-evidence.mjs','CLOUD_EVIDENCE'], ['cloud-lifecycle.mjs','CLOUD_LIFECYCLE'], ['cloud-approval-view.mjs','CLOUD_APPROVAL_VIEW'], ['cloud-service.mjs','CLOUD_SERVICE'], ['cloud-actions-view.mjs','CLOUD_ACTIONS_VIEW'], ['cloud-spellbook.mjs','CLOUD_SPELLBOOK'], ['cloud-admin.mjs','CLOUD_ADMIN'], ['cloud-inbox.mjs','CLOUD_INBOX'], ['cloud-http.mjs','CLOUD_HTTP'], ['cloud-mcp.mjs','CLOUD_MCP'], ['mcp.mjs', 'MCP'],
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
const normalizeSql = value => value.replace(/^--> statement-breakpoint\s*$/gm, '').replace(/\s+/g, ' ').trim();
const journal = JSON.parse(await readFile(resolve(root, 'drizzle/meta/_journal.json'), 'utf8'));
if (journal.dialect !== 'sqlite' || !Array.isArray(journal.entries) || !journal.entries.length) throw Error('Invalid migration journal.');
const migrationFiles = ['meta/_journal.json'];
const migrationSql = [];
let previousSnapshotId = '00000000-0000-0000-0000-000000000000';
for (const [index, entry] of journal.entries.entries()) {
  const prefix = String(index).padStart(4, '0');
  if (entry.idx !== index || !new RegExp('^' + prefix + '_[A-Za-z0-9_-]+$').test(entry.tag) || entry.version !== '6' || entry.breakpoints !== true) throw Error('Invalid migration journal entry.');
  const snapshotFile = 'meta/' + prefix + '_snapshot.json';
  const snapshot = JSON.parse(await readFile(resolve(root, 'drizzle', snapshotFile), 'utf8'));
  if (snapshot.dialect !== 'sqlite' || snapshot.prevId !== previousSnapshotId || typeof snapshot.id !== 'string') throw Error('Invalid migration snapshot chain.');
  previousSnapshotId = snapshot.id;
  migrationFiles.push(entry.tag + '.sql', snapshotFile);
  migrationSql.push(await readFile(resolve(root, 'drizzle', entry.tag + '.sql'), 'utf8'));
}
if (normalizeSql(migrationSql.join('\n')) !== normalizeSql(await readFile(resolve(root, 'schema.sql'), 'utf8'))) throw Error('Reviewed migration chain does not match the schema. Append migrations; never rewrite applied SQL.');
// Sites discovers hosted migrations beneath the artifact's .openai directory.
// A sibling output/drizzle directory is not consumed by the deployment migrator.
await mkdir(resolve(output, '.openai'), { recursive: true });
for (const file of migrationFiles) {
  const destination = resolve(output, '.openai', 'drizzle', file);
  await mkdir(dirname(destination), { recursive: true });
  await copyFile(resolve(root, 'drizzle', file), destination);
}
let hostingConfigured = false;
try {
  const hosting = JSON.parse(await readFile(resolve(root, '.openai/hosting.json'), 'utf8'));
  if (!hosting.project_id || hosting.d1 !== 'DB' || !hosting.capabilities?.includes('mcp')) throw Error('New Site hosting configuration is incomplete');
  await mkdir(resolve(output, '.openai'), { recursive: true });
  await copyFile(resolve(root, '.openai/hosting.json'), resolve(output, '.openai/hosting.json'));
  hostingConfigured = true;
} catch (error) { if (error.code !== 'ENOENT') throw error; }
console.log(JSON.stringify({ worker: resolve(output, 'server/index.js'), migration: resolve(output, '.openai/drizzle/0000_owner_bridge.sql'), migrations: journal.entries.map(entry => resolve(output, '.openai/drizzle', entry.tag + '.sql')), hostingConfigured, deployed: false }));
