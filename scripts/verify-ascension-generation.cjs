// Exercise the offline kit on local, unpublished workerd bindings. No account or deployment endpoint.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const recovery = require('../tools/ascension_provider.cjs');
const root = path.resolve(__dirname, '..');
const digest = data => crypto.createHash('sha256').update(data).digest('hex');
const read = file => JSON.parse(fs.readFileSync(file));
const cli = (tool, ...argv) => JSON.parse(execFileSync('python3', [path.join(root, 'tools', tool), ...argv], {
  encoding: 'utf8', maxBuffer: 32 << 20, stdio: ['ignore', 'pipe', 'pipe']
}));
const generation = (...argv) => cli('ascension_migrate_generation.py', ...argv);
const bridge = (...argv) => cli('ascension_provider_package.py', ...argv);
function write(file, data) { fs.writeFileSync(file, JSON.stringify(data), {flag: 'wx', mode: 0o600}); }
function validateTarget(plan, target) {
  for (const key of ['generation', 'd1DatabaseId', 'privateBucket']) assert.equal(target[key], plan[key], 'Binding identity differs');
  assert.equal(target.routed, false);
  assert.equal(target.private, true);
  assert.equal(plan.requiredFinalAuthority, 'staged');
}
async function listObjects(bucket) {
  const keys = [];
  let cursor;
  do {
    const page = await bucket.list({limit: 1000, ...(cursor ? {cursor} : {})});
    keys.push(...page.objects.map(o => o.key));
    assert.ok(!page.truncated || (page.cursor && page.cursor !== cursor), 'Invalid bucket cursor');
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  return keys.sort();
}
async function census(db, plan) {
  const schema = (await db.prepare("SELECT type,name,sql FROM sqlite_master WHERE (tbl_name GLOB 'cloud_*' OR tbl_name GLOB 'bridge_*') AND sql IS NOT NULL ORDER BY name").all()).results;
  assert.deepEqual(schema.map(row => [row.type, row.name, row.sql.trim().replace(/\s+/g, ' ')]), plan.schemaObjects, 'Schema differs');
  const names = (await db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*' AND name<>'_cf_METADATA' ORDER BY name").all()).results.map(x => x.name);
  assert.ok(names.every(n => /^(cloud|bridge)_[a-z_]+$/.test(n)), 'Unclassified storage');
  const result = await db.batch(names.map(n => db.prepare('SELECT COUNT(*) AS n FROM ' + n)));
  return Object.fromEntries(names.map((n, i) => [n, result[i].results[0].n]));
}
async function applyOffline(runtime, target, folder, fingerprint) {
  const manifest = fs.readFileSync(path.join(folder, 'generation.json'));
  assert.equal(digest(manifest), fingerprint);
  const plan = JSON.parse(manifest);
  validateTarget(plan, target);
  assert.ok(Object.values(await census(runtime.db, plan)).every(n => n === 0), 'D1 generation is not empty');
  assert.deepEqual(await listObjects(runtime.bucket), [], 'R2 generation is not empty');
  const raw = fs.readFileSync(path.join(folder, 'd1-batch.json'));
  assert.equal(digest(raw), plan.d1BatchSha256);
  const stmts = JSON.parse(raw);
  assert.equal(stmts.length, plan.statementCount);
  const objects = plan.objects.map(expected => {
    const directory = fs.realpathSync(path.join(folder, 'objects'));
    const file = fs.realpathSync(path.join(directory, expected.key));
    assert.ok(file.startsWith(directory + path.sep), 'Object escaped reviewed folder');
    const bytes = fs.readFileSync(file);
    assert.equal(bytes.length, expected.bytes);
    assert.equal(digest(bytes), expected.sha256);
    return {bytes, expected};
  });
  for (const {bytes, expected} of objects) {
    await runtime.bucket.put(expected.key, bytes, {sha256: expected.sha256, httpMetadata: {contentType: 'image/png'}});
  }
  await runtime.db.batch(stmts.map(s => runtime.db.prepare(s.sql).bind(...s.params)));
}
async function readBack(runtime, target, folder, destination) {
  const plan = read(path.join(folder, 'generation.json'));
  validateTarget(plan, target);
  const counts = await census(runtime.db, plan);
  const keys = await listObjects(runtime.bucket);
  const files = path.join(destination, 'capture');
  fs.mkdirSync(files, {mode: 0o700});
  fs.mkdirSync(path.join(files, 'objects'), {mode: 0o700});
  const state = await runtime.db.prepare('SELECT authority,epoch,revision,schema_version AS schema FROM cloud_continuities WHERE owner_id=? AND continuity_id=?').bind(...plan.tenant).first();
  assert.ok(state);
  const tables = {};
  let unpackedForeignRows = 0;
  for (const t of bridge('layout')) {
    const sql = 'SELECT ' + t.columns.join(',') + ' FROM ' + t.name + ' WHERE owner_id=? AND continuity_id=? ORDER BY ' + t.order.join(',');
    tables[t.name] = (await runtime.db.prepare(sql).bind(...plan.tenant).all()).results;
    unpackedForeignRows += counts[t.name] - tables[t.name].length;
  }
  for (const [name, count] of Object.entries(counts)) {
    if (!Object.hasOwn(tables, name) && name !== 'cloud_continuities') unpackedForeignRows += count;
  }
  for (const key of keys) {
    const file = path.resolve(files, 'objects', key);
    assert.ok(file.startsWith(path.join(files, 'objects') + path.sep));
    const object = await runtime.bucket.get(key);
    assert.ok(object);
    const bytes = Buffer.from(await object.arrayBuffer());
    fs.mkdirSync(path.dirname(file), {recursive: true, mode: 0o700});
    fs.writeFileSync(file, bytes, {flag: 'wx', mode: 0o600});
  }
  write(path.join(files, 'snapshot.json'), {owner: plan.tenant[0], continuity: plan.tenant[1], state, tables});
  const archive = path.join(destination, 'independent-reexport.zip');
  bridge('pack', files, archive);
  const inventory = path.join(destination, 'inventory.json');
  write(inventory, {...target, tenantCount: counts.cloud_continuities, liveGrants: counts.cloud_grants, credentials: counts.bridge_credentials,
    objectKeys: keys, unpackedForeignRows, schemaSqlSha256: digest(fs.readFileSync(path.join(root, 'backend/owner-bridge/schema.sql')))});
  return {archive, inventory};
}
async function rehearse(tmp, zip, mod, purpose = 'production-candidate') {
  const storage = path.join(tmp, 'generation-fixture'), folder = path.join(tmp, 'offline');
  await recovery.create(storage, 'fixture-maintenance-owner', 'qa-unrouted-generation', mod);
  const target = await recovery.withOfflineBindings(storage, (_, t) => t, mod);
  const args = ['--generation', target.generation, '--database', target.d1DatabaseId, '--bucket', target.privateBucket, '--package-sha', digest(fs.readFileSync(zip))];
  if (purpose === 'isolated') {
    assert.throws(() => generation('export', zip, path.join(tmp, 'production-blocked'), '--purpose', 'production-candidate', ...args), /Review holds prevent production candidate export/);
  }
  const result = generation('export', zip, folder, '--purpose', purpose, ...args);
  const sha = result.generationManifestSha256;
  await recovery.withOfflineBindings(storage, async (r, t) => {
    await assert.rejects(applyOffline(r, {...t, d1DatabaseId: crypto.randomUUID()}, folder, sha), /Binding identity differs/);
    await r.bucket.put('unrelated', 'fixture');
    await assert.rejects(applyOffline(r, t, folder, sha), /R2 generation is not empty/);
    await r.bucket.delete('unrelated');
    await applyOffline(r, t, folder, sha);
    await assert.rejects(applyOffline(r, t, folder, sha), /D1 generation is not empty/);
  }, mod);
  // A fresh workerd process re-reads the actual D1 rows and complete R2 inventory.
  const capture = await recovery.withOfflineBindings(storage, (r, t) => readBack(r, t, folder, tmp), mod);
  return generation('verify-readback', folder, capture.archive, capture.inventory, '--manifest-sha', sha);
}
module.exports = {rehearse};
