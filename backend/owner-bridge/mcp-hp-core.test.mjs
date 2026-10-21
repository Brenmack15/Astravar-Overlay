import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { OwnerBridge, canonical, sha256, CAMPAIGN } from './core.mjs';
import { LiveHpBridge, HP_FRESHNESS_MS } from './live-hp.mjs';
import { SqliteAdapter } from './sqlite-adapter.mjs';
import { handleMcp } from './mcp.mjs';

const ownerId = 'mcp-hp-contract-owner';
const campaignId = 'mcp-hp-contract-campaign';
const profileId = 'mcp-hp-existing-profile-id';
function fixture() {
  const db = new SqliteAdapter();
  db.exec(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'));
  let clock = 1_800_000_000_000;
  const now = () => clock;
  const bridge = new OwnerBridge(db, { ownerId, now });
  const liveHp = new LiveHpBridge(db, { ownerId, now });
  bridge.liveHp = liveHp;
  const owner = { ownerId, role: 'owner', scopes: [] };
  const invoke = async (name, args = {}, options = {}) => {
    const request = new Request('https://mcp-hp-contract.example/mcp', { method: 'POST', headers: {
      'Content-Type': 'application/json', 'oai-authenticated-user-id': options.visitorId ?? ownerId,
    }, body: JSON.stringify({ jsonrpc: '2.0', id: 'mcp-hp-contract', method: 'tools/call', params: { name, arguments: args } }) });
    return (await (await handleMcp(request, bridge, { ownerId, liveHp, ...options })).json());
  };
  const enroll = async (campaign = campaignId, profile = profileId) => {
    const secret = Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('base64url');
    const pairing = await bridge.requestPairing({ campaignId: campaign, deviceName: 'Protocol fixture device', deviceSecretHash: await sha256(secret), ...(campaign !== CAMPAIGN ? { profileId: profile } : {}) });
    await bridge.approvePairing(owner, { userCode: pairing.userCode });
    const enrolled = await bridge.pollPairing({ deviceCode: pairing.deviceCode, deviceSecret: secret });
    return bridge.authenticate(enrolled.credentialId, secret);
  };
  const publish = async (device, fields = {}, expectedRevision = 0, revision = 7) => {
    const value = { name: 'HP protocol fixture', currentHp: 31, maximumHp: 40, temporaryHp: 3, source: 'Fictional protocol fixture source', updatedAt: now(), ...fields };
    await liveHp.publishCharacter(device, campaignId, { commandId: crypto.randomUUID(), expectedRevision, profileId, revision, fields: value });
    return value;
  };
  return { db, bridge, liveHp, owner, invoke, enroll, publish, tick: amount => { clock += amount; }, now };
}
const code = response => JSON.parse(response.result.content[0].text).error;

test('real SQLite HP MCP keeps the proposal separate, binds its canonical fingerprint, then returns independent immutable receipt and saved readback', async () => {
  const f = fixture();
  try {
    assert.deepEqual((await f.invoke('get_owner_connection')).result.structuredContent.connections, []);
    const device = await f.enroll();
    const enrolled = (await f.invoke('get_owner_connection')).result.structuredContent.connections;
    assert.equal(enrolled.length, 1);
    assert.equal(enrolled[0].profileId, profileId);
    assert.equal(enrolled[0].published, false);
    assert.equal(Object.hasOwn(enrolled[0], 'fields'), false);
    const originalFields = await f.publish(device);
    const original = (await f.invoke('get_character_hp', { campaignId, profileId })).result.structuredContent;
    assert.equal(original.recordId, profileId);
    assert.equal(original.revision, 7);
    assert.equal(original.fields.currentHp, 31);
    const commandId = crypto.randomUUID(), source = 'Fictional requested current HP adjustment';
    const body = { campaignId, profileId, commandId, expectedRevision: 7, expectedCurrentHp: 31, adjustment: -4, source };
    const staged = (await f.invoke('propose_hp_adjustment', body)).result.structuredContent.proposal;
    assert.deepEqual(staged.differences, [{ field: 'currentHp', current: 31, proposed: 27 }]);
    assert.equal(staged.fingerprint, await sha256(canonical({ campaignId, commandId, recordId: profileId, expectedRevision: 7, fields: { currentHp: 27 }, source, adjustment: -4 })));
    const unchanged = (await f.invoke('get_character_hp', { campaignId, profileId })).result.structuredContent;
    assert.deepEqual(unchanged.fields, originalFields);
    assert.equal(unchanged.revision, 7);
    assert.equal(code(await f.invoke('get_hp_command_receipt', { campaignId, commandId })), 'RECEIPT_NOT_READY');
    const pending = (await f.invoke('get_hp_pending_updates', { campaignId })).result.structuredContent.proposals;
    assert.equal(pending.length, 1);
    assert.equal(pending[0].fingerprint, staged.fingerprint);
    f.tick(1);
    const localFields = { ...originalFields, currentHp: 27, source: `Player-approved HP connection test ${commandId} · ${source}`, updatedAt: f.now() };
    const decision = { commandId, approved: true, localAppliedRevision: 8, localReceiptId: 'fixture-local-hp-receipt', proposalFingerprint: staged.fingerprint, localFields };
    const native = await f.liveHp.decide(device, campaignId, decision);
    const receipt = (await f.invoke('get_hp_command_receipt', { campaignId, commandId })).result.structuredContent.receipt;
    assert.deepEqual(receipt, native.receipt);
    assert.equal(receipt.status, 'APPLIED');
    assert.equal(receipt.resultingRevision, 8);
    assert.equal(receipt.localRecordFingerprint, await sha256(canonical(localFields)));
    const saved = (await f.invoke('get_character_hp', { campaignId, profileId })).result.structuredContent;
    assert.deepEqual(saved.fields, localFields);
    assert.equal(saved.revision, 8);
    assert.equal(saved.fields.maximumHp, 40);
    assert.equal(saved.fields.temporaryHp, 3);
    assert.deepEqual((await f.liveHp.decide(device, campaignId, decision)).receipt, receipt);
    await f.invoke('propose_hp_adjustment', body);
    assert.equal((await f.invoke('get_character_hp', { campaignId, profileId })).result.structuredContent.revision, 8);
    assert.deepEqual((await f.invoke('get_hp_pending_updates', { campaignId })).result.structuredContent.proposals, []);
  } finally { f.db.close(); }
});

test('actual HP bridge denies foreign/unapproved bindings and fictional credentials while metadata never exports profile fields or credentials', async () => {
  const f = fixture();
  try {
    const device = await f.enroll();
    await f.publish(device);
    for (const [name, args] of [
      ['get_character_hp', { campaignId: 'unapproved-campaign', profileId }],
      ['get_character_hp', { campaignId, profileId: 'unapproved-profile' }],
      ['get_hp_pending_updates', { campaignId: 'unapproved-campaign' }],
      ['get_hp_command_receipt', { campaignId: 'unapproved-campaign', commandId: crypto.randomUUID() }],
      ['propose_hp_adjustment', { campaignId, profileId: 'unapproved-profile', commandId: crypto.randomUUID(), expectedRevision: 7, expectedCurrentHp: 31, adjustment: -1, source: 'Fictional request' }],
    ]) {
      const response = await f.invoke(name, args);
      assert.equal(response.result.isError, true);
      assert.equal(code(response), 'HP_CONNECTION_NOT_AUTHORIZED');
    }
    const fictional = await f.enroll(CAMPAIGN);
    await assert.rejects(() => f.liveHp.getCharacterHp(fictional, campaignId, profileId), error => error.code === 'SCOPE_DENIED');
    await assert.rejects(() => f.liveHp.proposeHpUpdate(fictional, campaignId, { commandId: crypto.randomUUID(), profileId, expectedRevision: 7, expectedCurrentHp: 31, adjustment: -1, source: 'Fictional request' }), error => error.code === 'ROLE_DENIED');
    const metadata = (await f.invoke('get_owner_connection')).result.structuredContent;
    assert.deepEqual(Object.keys(metadata).sort(), ['connections', 'mode']);
    assert.equal(metadata.connections.length, 1);
    const entry = metadata.connections[0];
    assert.deepEqual(Object.keys(entry).sort(), ['campaignId', 'deviceName', 'lastDeviceSyncAt', 'profileId', 'published', 'revision', 'scopes', 'stale', 'supersededCount']);
    assert.equal(Number.isSafeInteger(entry.supersededCount), true);
    assert.equal(entry.supersededCount >= 0, true);
    assert.equal(entry.supersededCount, 0);
    for (const key of ['name', 'currentHp', 'maximumHp', 'temporaryHp', 'source', 'fields', 'credentialId', 'bearer', 'deviceSecret']) assert.equal(Object.hasOwn(entry, key), false);
    for (const [name, args] of [['get_owner_connection', {}], ['get_character_hp', { campaignId, profileId }], ['get_hp_pending_updates', { campaignId }], ['get_hp_command_receipt', { campaignId, commandId: crypto.randomUUID() }]]) {
      const denied = await f.invoke(name, args, { hpConnectorScopes: [] });
      assert.equal(denied.result.isError, true);
      assert.equal(code(denied), 'SCOPE_DENIED');
    }
  } finally { f.db.close(); }
});

test('real HP MCP fails closed on unknown values, stale synchronization, stale expected values and out-of-range arithmetic without clamping', async () => {
  for (const unknown of [{ currentHp: null }, { maximumHp: null }]) {
    const f = fixture();
    try {
      const device = await f.enroll(); await f.publish(device, unknown);
      const response = await f.invoke('propose_hp_adjustment', { campaignId, profileId, commandId: crypto.randomUUID(), expectedRevision: 7, expectedCurrentHp: 31, adjustment: -1, source: 'Fictional request' });
      assert.equal(code(response), 'HP_UNKNOWN');
    } finally { f.db.close(); }
  }
  const f = fixture();
  try {
    const device = await f.enroll(); await f.publish(device);
    const body = { campaignId, profileId, commandId: crypto.randomUUID(), expectedRevision: 7, expectedCurrentHp: 31, adjustment: -1, source: 'Fictional request' };
    assert.equal(code(await f.invoke('propose_hp_adjustment', { ...body, expectedRevision: 6 })), 'REVISION_CONFLICT');
    assert.equal(code(await f.invoke('propose_hp_adjustment', { ...body, expectedCurrentHp: 30 })), 'REVISION_CONFLICT');
    assert.equal(code(await f.invoke('propose_hp_adjustment', { ...body, adjustment: -32 })), 'INVALID_HP_RANGE');
    assert.equal(code(await f.invoke('propose_hp_adjustment', { ...body, adjustment: 10 })), 'INVALID_HP_RANGE');
    f.tick(HP_FRESHNESS_MS + 1);
    const stale = (await f.invoke('get_character_hp', { campaignId, profileId })).result.structuredContent;
    assert.equal(stale.stale, true);
    assert.equal(code(await f.invoke('propose_hp_adjustment', body)), 'DEVICE_SYNC_STALE');
    assert.equal(stale.fields.currentHp, 31);
    assert.equal(stale.revision, 7);
  } finally { f.db.close(); }
});
