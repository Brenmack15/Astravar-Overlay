import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { test } from 'node:test';
import { SqliteAdapter } from './sqlite-adapter.mjs';

const schema = await readFile(new URL('./schema.sql', import.meta.url), 'utf8');
const migrationRoot = new URL('./drizzle/', import.meta.url);
const journal = JSON.parse(await readFile(new URL('meta/_journal.json', migrationRoot)));
const owner = 'schema-fixture-owner';
const continuity = 'first-fixture';
const digest = 'a'.repeat(64);

function database(t) {
  const db = new SqliteAdapter();
  t.after(() => db.close());
  db.exec(schema);
  return db;
}

function enroll(db, id = continuity, account = owner, authority = 'cloud') {
  return db.db.prepare('INSERT INTO cloud_continuities(owner_id,continuity_id,authority) VALUES(?,?,?)')
    .run(account, id, authority);
}

function entityStatement(db, id, kind, actorId, tenant = continuity, account = owner, revision = 1) {
  return db.prepare(`INSERT INTO cloud_entities
    (owner_id,continuity_id,id,kind,actor_id,title,data_json,life,revision) VALUES(?,?,?,?,?,?,'{}','active',?)`)
    .bind(account, tenant, id, kind, actorId, 'Synthetic ' + kind, revision);
}

function submit(db, id, status) {
  return db.prepare(`INSERT INTO cloud_requests
    (owner_id,continuity_id,command_id,subject,digest,request_json,access_json,status,submitted_at)
    VALUES(?,?,?,'synthetic-dm',?,'{}','[]',?,1)`).bind(owner, continuity, id, digest, status);
}

function receipt(db, id, status, transactionId = 'tx-' + id) {
  return db.prepare(`INSERT INTO cloud_receipts
    (owner_id,continuity_id,command_id,transaction_id,status,epoch,revision,result_json,access_json,created_at)
    VALUES(?,?,?,?,?,1,1,'{}','[]',2)`).bind(owner, continuity, id, transactionId, status);
}

function guardRevision(db, expected) {
  return db.prepare(`INSERT INTO cloud_guards(ok) SELECT EXISTS(
    SELECT 1 FROM cloud_continuities WHERE owner_id=? AND continuity_id=? AND authority='cloud' AND epoch=1 AND revision=?
  )`).bind(owner, continuity, expected);
}

test('the appended migration preserves existing rows and enrolls no old campaign', async t => {
  const db = new SqliteAdapter();
  t.after(() => db.close());
  async function apply(entry) {
    const sql = await readFile(new URL(entry.tag + '.sql', migrationRoot), 'utf8');
    for (const statement of sql.split(/^--> statement-breakpoint\s*$/m).filter(s => s.trim())) {
      await db.prepare(statement).bind().run();
    }
  }
  const ascensionIndex = journal.entries.findIndex(entry => entry.tag === '0008_cloud_authority');
  assert.equal(ascensionIndex, 8);
  for (const entry of journal.entries.slice(0, ascensionIndex)) await apply(entry);
  db.db.prepare('INSERT INTO bridge_profiles VALUES(?,?,?,?)').run(owner, continuity, 'original', '{"example":"original"}');
  const old = db.db.prepare('SELECT * FROM bridge_profiles').get();
  await apply(journal.entries[ascensionIndex]);
  assert.deepEqual(db.db.prepare('SELECT * FROM bridge_profiles').get(), old);
  assert.equal(db.db.prepare('SELECT COUNT(*) AS n FROM cloud_continuities').get().n, 0);
  assert.deepEqual(db.db.prepare('PRAGMA foreign_key_check').all(), []);
  assert.equal(db.db.prepare('PRAGMA integrity_check').get().integrity_check, 'ok');
});

test('tenant-qualified actor and edge references are typed, stable and enforced at commit', async t => {
  const db = database(t);
  enroll(db);
  enroll(db, 'other-fixture');
  enroll(db, continuity, 'other-owner');
  await db.batch([
    entityStatement(db, 'w-1', 'wallet', 'a-1'),
    entityStatement(db, 'a-1', 'actor', 'a-1'),
    entityStatement(db, 'a-1', 'actor', 'a-1', 'other-fixture'),
    entityStatement(db, 'a-1', 'actor', 'a-1', continuity, 'other-owner'),
    entityStatement(db, 'place', 'world_entity', null),
  ]);
  await assert.rejects(db.batch([entityStatement(db, 'bad', 'wallet', 'place')]), /FOREIGN KEY/);
  await assert.rejects(db.batch([entityStatement(db, 'bad', 'wallet', 'missing')]), /FOREIGN KEY/);
  await assert.rejects(db.batch([entityStatement(db, 'bad', 'actor', null)]), /CHECK/);
  const link = (id, target, scope = continuity) => db.prepare('INSERT INTO cloud_links VALUES(?,?,?,?,?)')
    .bind(owner, scope, id, 'held_by', target);
  await db.batch([link('w-1', 'a-1')]);
  await assert.rejects(db.batch([link('w-1', 'a-1', 'other-fixture')]), /FOREIGN KEY/);
  await assert.rejects(db.batch([link('a-1', 'place', 'other-fixture')]), /FOREIGN KEY/);
  await assert.rejects(db.batch([entityStatement(db, 'w-1', 'item_definition', null, continuity, owner, 2)]),
    /CLOUD_ENTITY_REVISION/);
  assert.equal(db.db.prepare('SELECT COUNT(*) AS n FROM cloud_links').get().n, 1);
});

test('grant scope shapes enforce write, read and explicit auto-permit relationships', t => {
  const db = database(t);
  enroll(db);
  const insert = db.db.prepare(`INSERT INTO cloud_grants
    (owner_id,continuity_id,subject,scopes_json,actors_json,auto_json,expires_at) VALUES(?,?,?,?,?,?,1000)`);
  function grant(scopes, actors, auto) {
    insert.run(owner, continuity, 'synthetic-dm', JSON.stringify(scopes), JSON.stringify(actors), JSON.stringify(auto));
  }
  assert.throws(() => grant(['inventory.write'], ['actor'], []), /INVALID_CLOUD_GRANT/);
  assert.throws(() => grant(['inventory.read'], ['actor'], ['inventory.write']), /INVALID_CLOUD_GRANT/);
  assert.throws(() => grant(['inventory.read'], ['*', 'actor'], []), /INVALID_CLOUD_GRANT/);
  assert.throws(() => grant(['inventory.read', 'inventory.read'], ['actor'], []), /INVALID_CLOUD_GRANT/);
  assert.throws(() => grant([1], ['actor'], []), /INVALID_CLOUD_GRANT/);
  grant(['inventory.read', 'inventory.write'], ['actor'], ['inventory.write']);
  assert.throws(() => db.exec("UPDATE cloud_grants SET revoked=1"), /CLOUD_GRANT_REVISION/);
  db.exec("UPDATE cloud_grants SET revoked=1, revision=revision+1");
  assert.equal(db.db.prepare('SELECT revoked FROM cloud_grants').get().revoked, 1);
});

test('pending decisions persist without a receipt and terminal decisions require exactly one matching receipt', async t => {
  const db = database(t);
  enroll(db);
  await submit(db, 'review', 'PENDING_APPROVAL').run();
  assert.equal(db.db.prepare('SELECT count(*) AS n FROM cloud_receipts').get().n, 0);
  assert.throws(() => db.exec("UPDATE cloud_requests SET request_json='{\"changed\":true}', status='COMMITTED'"), /IMMUTABLE_CLOUD_REQUEST/);
  const decision = db.prepare("UPDATE cloud_requests SET status='REFUSED' WHERE command_id='review'").bind();
  await assert.rejects(db.batch([decision]), /FOREIGN KEY/);
  await assert.rejects(db.batch([decision, receipt(db, 'review', 'COMMITTED')]), /FOREIGN KEY/);
  assert.equal(db.db.prepare('SELECT status FROM cloud_requests').get().status, 'PENDING_APPROVAL');
  await db.batch([decision, receipt(db, 'review', 'REFUSED')]);
  assert.equal(db.db.prepare('SELECT status FROM cloud_receipts').get().status, 'REFUSED');
  assert.throws(() => db.exec("UPDATE cloud_requests SET status='COMMITTED'"), /IMMUTABLE_CLOUD_REQUEST/);
  assert.throws(() => db.exec("UPDATE cloud_receipts SET result_json='{}'"), /IMMUTABLE_CLOUD_RECEIPT/);
  assert.throws(() => db.exec('DELETE FROM cloud_receipts'), /IMMUTABLE_CLOUD_RECEIPT/);
  assert.throws(() => db.exec('INSERT OR REPLACE INTO cloud_receipts SELECT * FROM cloud_receipts'), /IMMUTABLE_CLOUD_RECEIPT/);
  await db.batch([submit(db, 'direct', 'COMMITTED'), receipt(db, 'direct', 'COMMITTED')]);
  await assert.rejects(db.batch([submit(db, 'duplicate-tx', 'COMMITTED'), receipt(db, 'duplicate-tx', 'COMMITTED', 'tx-direct')]),
    /IMMUTABLE_CLOUD_RECEIPT/);
  assert.equal(db.db.prepare('SELECT COUNT(*) AS n FROM cloud_requests').get().n, 2);
});

test('tombstones prevent resurrection and deletion retains dependency and change history integrity', async t => {
  const db = database(t);
  enroll(db);
  await db.batch([entityStatement(db, 'owner-actor', 'actor', 'owner-actor'),
    entityStatement(db, 'item', 'item_instance', 'owner-actor')]);
  db.db.prepare('INSERT INTO cloud_links VALUES(?,?,?,?,?)').run(owner, continuity, 'owner-actor', 'carried_item', 'item');
  assert.throws(() => db.exec("DELETE FROM cloud_entities WHERE id='item'"), /CLOUD_TOMBSTONE_REQUIRED/);
  const mark = db.prepare('INSERT INTO cloud_tombstones VALUES(?,?,?,?,2)').bind(owner, continuity, 'item', 'item_instance');
  const remove = db.prepare("DELETE FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id='item'").bind(owner, continuity);
  const unlink = db.prepare("DELETE FROM cloud_links WHERE owner_id=? AND continuity_id=? AND relation='carried_item'").bind(owner, continuity);
  const change = db.prepare(`INSERT INTO cloud_changes
    (owner_id,continuity_id,revision,ordinal,entity_id,kind,actor_id,change_type)
    VALUES(?,?,2,0,'item','item_instance','owner-actor','delete')`).bind(owner, continuity);
  await assert.rejects(db.batch([mark, remove, change]), /FOREIGN KEY/);
  assert.equal(db.db.prepare('SELECT count(*) AS n FROM cloud_tombstones').get().n, 0);
  await db.batch([unlink, mark, remove, change]);
  await assert.rejects(entityStatement(db, 'item', 'item_instance', 'owner-actor').run(), /DELETED_CLOUD_IDENTITY/);
  assert.throws(() => db.exec('DELETE FROM cloud_tombstones'), /IMMUTABLE_CLOUD_TOMBSTONE/);
  assert.throws(() => db.exec('DELETE FROM cloud_changes'), /IMMUTABLE_CLOUD_CHANGE/);
  assert.equal(db.db.prepare('SELECT count(*) AS n FROM cloud_entities').get().n, 1);
  assert.deepEqual(db.db.prepare('PRAGMA foreign_key_check').all(), []);
});

test('concurrent revision claims serialize, roll back all losing work and survive a database restart', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'astravar-cloud-schema-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const path = join(directory, 'test.sqlite');
  const a = new SqliteAdapter(path);
  const b = new SqliteAdapter(path);
  try {
    a.exec(schema);
    enroll(a);
    function compete(db, id) {
      return db.batch([
        guardRevision(db, 0), entityStatement(db, id, 'actor', id),
        db.prepare('UPDATE cloud_continuities SET revision=1 WHERE owner_id=? AND continuity_id=?').bind(owner, continuity),
        submit(db, id, 'COMMITTED'), receipt(db, id, 'COMMITTED'), db.prepare('DELETE FROM cloud_guards').bind(),
      ]);
    }
    const results = await Promise.allSettled([compete(a, 'actor-a'), compete(b, 'actor-b')]);
    assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
    assert.match(String(results.find(result => result.status === 'rejected').reason), /CHECK/);
  } finally { a.close(); b.close(); }
  const restored = new SqliteAdapter(path);
  try {
    for (const name of ['cloud_entities', 'cloud_receipts', 'cloud_requests']) {
      assert.equal(restored.db.prepare('SELECT COUNT(*) AS n FROM ' + name).get().n, 1);
    }
    assert.equal(restored.db.prepare('SELECT revision FROM cloud_continuities').get().revision, 1);
    assert.equal(restored.db.prepare('SELECT COUNT(*) AS n FROM cloud_guards').get().n, 0);
    assert.deepEqual(restored.db.prepare('PRAGMA foreign_key_check').all(), []);
  } finally { restored.close(); }
});

test('the authority fence preserves legacy evidence, blocks old commands and leaves other continuities untouched', t => {
  const db = database(t);
  const add = db.db.prepare("INSERT INTO bridge_proposals VALUES(?,?,?,'marker',0,'synthetic','{}')");
  add.run(owner, 'legacy-with-pending', 'old');
  enroll(db, 'legacy-with-pending', owner, 'staged');
  assert.throws(() => db.exec("UPDATE cloud_continuities SET authority='cloud',epoch=2"), /LEGACY_COMMAND_PENDING/);
  assert.equal(db.db.prepare('SELECT authority FROM cloud_continuities').get().authority, 'staged');
  enroll(db);
  assert.throws(() => add.run(owner, continuity, 'late'), /CLOUD_AUTHORITY_REQUIRED/);
  add.run(owner, 'unbound', 'untouched');
  add.run('other-owner', continuity, 'untouched');
  db.db.prepare('INSERT INTO bridge_records VALUES(?,?,?,?,?,?,?)').run(owner, 'existing', 'stat', 'pc', 'character_ability', 1, '{}');
  assert.throws(() => enroll(db, 'existing'), /STAGED_MIGRATION_REQUIRED/);
  enroll(db, 'existing', owner, 'staged');
  assert.throws(() => db.exec("UPDATE cloud_continuities SET authority='cloud' WHERE continuity_id='existing'"), /CLOUD_AUTHORITY_EPOCH/);
  db.exec("UPDATE cloud_continuities SET authority='cloud',epoch=2 WHERE continuity_id='existing'");
  assert.equal(db.db.prepare("SELECT revision FROM bridge_records WHERE campaign_id='existing'").get().revision, 1);
  assert.throws(() => db.exec("DELETE FROM cloud_continuities WHERE continuity_id='existing'"), /CLOUD_AUTHORITY_REQUIRED/);
});
