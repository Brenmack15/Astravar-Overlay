import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { execFile } from 'node:child_process';
import { handleWorker } from './worker.mjs';
import { SqliteAdapter } from './sqlite-adapter.mjs';
const execute = promisify(execFile);
const ownerId = 'fixture-site-owner';
const ownerRequest = (path, options = {}) => new Request('https://fixture-owner.example' + path, {
  ...options, headers: { 'oai-authenticated-user-id': ownerId, ...(options.headers || {}) },
});

test('generated custom migration statements apply independently without splitting trigger bodies', async () => {
  const journal = JSON.parse(await readFile(new URL('./drizzle/meta/_journal.json', import.meta.url), 'utf8'));
  assert.equal(journal.dialect, 'sqlite');
  assert.equal(journal.entries[0].tag, '0000_owner_bridge');
  const sql = await readFile(new URL('./drizzle/0000_owner_bridge.sql', import.meta.url), 'utf8');
  const statements = sql.split(/^--> statement-breakpoint\s*$/m).map(value => value.trim()).filter(Boolean);
  const db = new SqliteAdapter();
  try {
    for (const statement of statements) await db.prepare(statement).bind().run();
    assert.equal((await db.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type='table' AND name LIKE 'bridge_%'").bind().first()).count, 6);
    assert.equal((await db.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type='trigger' AND name LIKE 'bridge_%'").bind().first()).count, 10);
  } finally { db.close(); }
});

test('Sites Worker fails closed without DB or pinned owner and rejects unmatched owner identity', async () => {
  assert.equal((await handleWorker(ownerRequest('/owner'), {})).status, 503);
  const env = { DB: {}, OWNER_SITE_USER_ID: ownerId };
  assert.equal((await handleWorker(new Request('https://fixture-owner.example/owner'), env)).status, 401);
  assert.equal((await handleWorker(new Request('http://fixture-owner.example/owner'), env)).status, 400);
  const page = await handleWorker(ownerRequest('/owner'), env);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-security-policy'), /script-src 'nonce-/);
  assert.equal((await page.text()).includes('Trusted DM Auto-Sync'), true);
});

test('new private Site can discover schemas and its verified identity before configuration without exposing or changing records', async () => {
  const setup = await handleWorker(ownerRequest('/owner/setup/identity'), {});
  assert.equal((await setup.json()).siteScopedUserId, ownerId);
  assert.equal((await handleWorker(new Request('https://fixture-owner.example/owner/setup/identity'), {})).status, 401);
  for (const method of ['server/discover', 'tools/list']) {
    const request = new Request('https://fixture-owner.example/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method }) });
    const response = await handleWorker(request, {});
    assert.equal(response.status, 200);
    assert.equal(Object.hasOwn(await response.json(), 'result'), true);
  }
  const data = new Request('https://fixture-owner.example/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json', 'oai-authenticated-user-id': ownerId }, body: JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'get_character', arguments: { campaignId: 'synthetic-owner-acceptance', profileId: 'owner-sync-fixture' } } }) });
  assert.equal((await handleWorker(data, {})).status, 503);
  assert.equal((await handleWorker(ownerRequest('/v1/owner/devices'), {})).status, 503);
});

test('Sites Worker routes authenticated owner pairing, device listing, revocation and MCP discovery', async () => {
  const db = new SqliteAdapter();
  db.exec(await readFile(new URL('./schema.sql', import.meta.url), 'utf8'));
  const env = { DB: db, OWNER_SITE_USER_ID: ownerId };
  try {
    const list = await handleWorker(ownerRequest('/v1/owner/devices'), env);
    assert.equal(list.status, 200);
    assert.deepEqual((await list.json()).devices, []);
    const unauthorized = await handleWorker(new Request('https://fixture-owner.example/v1/owner/devices'), env);
    assert.equal(unauthorized.status, 401);
    const pairing = await handleWorker(ownerRequest('/v1/owner/pairings/approve', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Origin: 'https://evil.example' }, body: JSON.stringify({ userCode: 'fictional-code' }),
    }), env);
    assert.equal(pairing.status, 403);
    const discovery = await handleWorker(ownerRequest('/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'server/discover' }) }), env);
    assert.equal((await discovery.json()).result.supportedVersions[0], '2026-07-28');
    assert.equal((await handleWorker(ownerRequest('/owner/bootstrap'), env)).status, 200);
  } finally { db.close(); }
});

test('deterministic bundled Worker imports and serves the same authenticated MCP discovery', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'astravar-worker-test-'));
  try {
    await execute(process.execPath, [fileURLToPath(new URL('./build-worker.mjs', import.meta.url)), directory]);
    const first = await readFile(join(directory, 'server/index.js'), 'utf8');
    await execute(process.execPath, [fileURLToPath(new URL('./build-worker.mjs', import.meta.url)), directory]);
    assert.equal(await readFile(join(directory, 'server/index.js'), 'utf8'), first);
    const bundled = await import(pathToFileURL(join(directory, 'server/index.js')));
    const response = await bundled.default.fetch(ownerRequest('/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list' }) }), { DB: {}, OWNER_SITE_USER_ID: ownerId });
    assert.equal((await response.json()).result.tools.length, 29);
    assert.equal((await readFile(join(directory, '.openai/drizzle/0000_owner_bridge.sql'), 'utf8')).includes('CREATE TABLE'), true);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('Sites artifact migration layout initializes the hosted-style database before an authenticated pending read', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'astravar-migration-layout-'));
  const db = new SqliteAdapter();
  try {
    const { stdout } = await execute(process.execPath, [fileURLToPath(new URL('./build-worker.mjs', import.meta.url)), directory]);
    assert.equal(JSON.parse(stdout).migration, join(directory, '.openai/drizzle/0000_owner_bridge.sql'));
    const migrationRoot = join(directory, '.openai/drizzle');
    for (const file of ['0000_owner_bridge.sql', '0001_bootstrap_downloads.sql', 'meta/_journal.json', 'meta/0000_snapshot.json', 'meta/0001_snapshot.json', '0002_live_hp.sql', 'meta/0002_snapshot.json', '0003_campaign_sync.sql', 'meta/0003_snapshot.json']) {
      assert.equal(await readFile(join(migrationRoot, file), 'utf8'), await readFile(new URL('./drizzle/' + file, import.meta.url), 'utf8'));
    }
    await assert.rejects(readFile(join(directory, 'drizzle/0000_owner_bridge.sql')), { code: 'ENOENT' });
    const journal = JSON.parse(await readFile(join(migrationRoot, 'meta/_journal.json'), 'utf8'));
    for (const entry of journal.entries) {
      const sql = await readFile(join(migrationRoot, entry.tag + '.sql'), 'utf8');
      for (const statement of sql.split(/^--> statement-breakpoint\s*$/m).map(value => value.trim()).filter(Boolean)) {
        await db.prepare(statement).bind().run();
      }
    }
    assert.equal((await db.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type='table' AND name LIKE 'bridge_%'").bind().first()).count, 18);
    const bundled = await import(pathToFileURL(join(directory, 'server/index.js')));
    const response = await bundled.default.fetch(ownerRequest('/mcp', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: {
        name: 'get_pending_updates', arguments: { campaignId: 'synthetic-owner-acceptance' },
      } }),
    }), { DB: db, OWNER_SITE_USER_ID: ownerId });
    const result = (await response.json()).result;
    assert.equal(result.isError, false);
    assert.deepEqual(result.structuredContent.proposals, []);
  } finally { db.close(); await rm(directory, { recursive: true, force: true }); }
});
