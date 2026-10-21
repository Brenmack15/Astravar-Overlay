import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handleMcp, TOOLS } from './mcp.mjs';

const ownerId = 'hp-adapter-test-owner';
const campaignId = 'hp-adapter-test-campaign';
const profileId = 'hp-adapter-test-profile';
const commandId = '43c25c15-a10c-4615-a92a-568fd2768a9a';
const rpc = (name, args = {}) => ({ jsonrpc: '2.0', id: 'hp-adapter-test', method: 'tools/call', params: { name, arguments: args } });
const request = (value, headers = {}) => new Request('https://hp-adapter.example/mcp', { method: 'POST',
  headers: { 'Content-Type': 'application/json', 'oai-authenticated-user-id': ownerId, ...headers }, body: JSON.stringify(value) });
const proposal = () => ({ campaignId, profileId, commandId, expectedRevision: 7, expectedCurrentHp: 31, adjustment: -4, source: 'Adapter test player request' });
function fixture() {
  const calls = [];
  const liveHp = Object.fromEntries(['getConnection', 'getCharacterHp', 'proposeHpUpdate', 'getPendingUpdates', 'getCommandReceipt'].map(name => [name, async (...args) => {
    calls.push({ name, args });
    return name === 'getConnection' ? { mode: 'ASTRAVAR_OWNER_HP_V1', connections: [] } : { mode: 'ASTRAVAR_OWNER_HP_V1', campaignId, pending: name === 'proposeHpUpdate' };
  }]));
  return { bridge: {}, liveHp, calls };
}

test('HP MCP operations use only server-constructed connector HP scopes and never carry approval authority', async () => {
  const { bridge, liveHp, calls } = fixture();
  for (const [name, args, method] of [
    ['get_owner_connection', {}, 'getConnection'],
    ['get_character_hp', { campaignId, profileId }, 'getCharacterHp'],
    ['propose_hp_adjustment', proposal(), 'proposeHpUpdate'],
    ['get_hp_pending_updates', { campaignId }, 'getPendingUpdates'],
    ['get_hp_command_receipt', { campaignId, commandId }, 'getCommandReceipt'],
  ]) {
    const response = await (await handleMcp(request(rpc(name, args)), bridge, { ownerId, liveHp })).json();
    assert.equal(response.result.isError, false);
    const saved = calls.at(-1);
    assert.equal(saved.name, method);
    assert.deepEqual(saved.args[0], { ownerId, role: 'connector', credentialId: 'sites-mcp', scopes: ['hp:read', 'hp:propose', 'hp:receipt'] });
  }
  const staged = calls.find(value => value.name === 'proposeHpUpdate').args[2];
  assert.deepEqual(staged, { commandId, profileId, expectedRevision: 7, expectedCurrentHp: 31, adjustment: -4, source: 'Adapter test player request' });
  for (const field of ['approved', 'confirmed', 'fields', 'currentHp', 'maximumHp', 'temporaryHp', 'recordId', 'principal', 'deviceCredentialId']) assert.equal(Object.hasOwn(staged, field), false);
  const ownerConnection = await handleMcp(request({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'get_owner_connection' } }), bridge, { ownerId, liveHp });
  assert.equal((await ownerConnection.json()).result.isError, false);
});

test('HP schema rejects extra fields, direct HP assignment, unknown rolls, zero/fractional/unbounded deltas, invalid IDs and fabricated authority', async () => {
  const { bridge, liveHp, calls } = fixture();
  const cases = [
    ['get_owner_connection', { includeDatabase: true }],
    ['get_owner_connection', null],
    ['get_character_hp', { campaignId, profileId, includeParty: true }],
    ['get_character_hp', { campaignId: '../foreign', profileId }],
    ['get_character_hp', { campaignId, profileId: '' }],
    ['get_hp_pending_updates', { campaignId, profileId }],
    ['get_hp_command_receipt', { campaignId, commandId, profileId }],
    ['propose_hp_adjustment', { ...proposal(), adjustment: 0 }],
    ['propose_hp_adjustment', { ...proposal(), adjustment: 0.5 }],
    ['propose_hp_adjustment', { ...proposal(), adjustment: -1_000_001 }],
    ['propose_hp_adjustment', { ...proposal(), adjustment: 1_000_001 }],
    ['propose_hp_adjustment', { ...proposal(), adjustment: '-4' }],
    ['propose_hp_adjustment', { ...proposal(), expectedCurrentHp: null }],
    ['propose_hp_adjustment', { ...proposal(), expectedCurrentHp: -1 }],
    ['propose_hp_adjustment', { ...proposal(), expectedCurrentHp: 1_000_001 }],
    ['propose_hp_adjustment', { ...proposal(), expectedRevision: 0 }],
    ['propose_hp_adjustment', { ...proposal(), fields: { currentHp: 27 } }],
    ['propose_hp_adjustment', { ...proposal(), currentHp: 27 }],
    ['propose_hp_adjustment', { ...proposal(), maximumHp: 99 }],
    ['propose_hp_adjustment', { ...proposal(), temporaryHp: 99 }],
    ['propose_hp_adjustment', { ...proposal(), roll: 4 }],
    ['propose_hp_adjustment', { ...proposal(), confirmed: true }],
    ['propose_hp_adjustment', { ...proposal(), approved: true }],
    ['propose_hp_adjustment', { ...proposal(), principal: { role: 'device' } }],
    ['propose_hp_adjustment', { ...proposal(), source: ' ' }],
    ['propose_hp_adjustment', { ...proposal(), source: 'x'.repeat(1901) }],
    ['approve_hp_adjustment', proposal()],
    ['publish_character_hp', proposal()],
    ['revoke_hp_device', { campaignId }],
  ];
  for (const [name, args] of cases) {
    const response = await (await handleMcp(request(rpc(name, args)), bridge, { ownerId, liveHp })).json();
    assert.equal(response.error.code, -32602, name + ' must reject its malformed arguments');
  }
  assert.equal(calls.length, 0);
});

test('HP tools cannot be invoked by a missing/foreign Sites owner, an ordinary device bearer, or unconfigured service', async () => {
  const { bridge, liveHp, calls } = fixture();
  for (const name of ['get_owner_connection', 'get_character_hp', 'propose_hp_adjustment', 'get_hp_pending_updates', 'get_hp_command_receipt']) {
    const args = name === 'get_owner_connection' ? {} : name === 'get_character_hp' ? { campaignId, profileId } : name === 'propose_hp_adjustment' ? proposal() : name === 'get_hp_pending_updates' ? { campaignId } : { campaignId, commandId };
    const foreign = await handleMcp(request(rpc(name, args), { 'oai-authenticated-user-id': 'different-owner' }), bridge, { ownerId, liveHp, allowPublicDiscovery: true });
    assert.equal(foreign.status, 401);
    const missing = new Request('https://hp-adapter.example/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer device-credential-is-not-sites-owner' }, body: JSON.stringify(rpc(name, args)) });
    assert.equal((await handleMcp(missing, bridge, { ownerId, liveHp, allowPublicDiscovery: true })).status, 401);
  }
  const unconfigured = await (await handleMcp(request(rpc('get_owner_connection')), bridge, { ownerId })).json();
  assert.equal(unconfigured.result.isError, true);
  assert.equal(JSON.parse(unconfigured.result.content[0].text).error, 'HP_CONNECTION_NOT_CONFIGURED');
  assert.equal((await handleMcp(request(rpc('get_owner_connection')), null, { ownerId, liveHp })).status, 503);
  assert.equal(calls.length, 0);
});

test('HP errors remain unverified and sanitize service data while tool definitions expose no decision endpoint', async () => {
  const { bridge, liveHp } = fixture();
  liveHp.getCommandReceipt = async () => { throw Object.assign(new Error('private token must not escape'), { code: 'RECEIPT_NOT_READY' }); };
  const response = await (await handleMcp(request(rpc('get_hp_command_receipt', { campaignId, commandId })), bridge, { ownerId, liveHp })).json();
  assert.equal(response.result.isError, true);
  assert.deepEqual(JSON.parse(response.result.content[0].text), { error: 'RECEIPT_NOT_READY', confirmation: 'UNVERIFIED' });
  assert.equal(JSON.stringify(response).includes('private token'), false);
  assert.equal(TOOLS.some(value => /approve|reject|decide|publish|enroll|revoke/.test(value.name)), false);
});

test('HP schema keeps exact numeric/provenance boundaries and request identity intact without supplying a new HP value', async () => {
  const { bridge, liveHp, calls } = fixture();
  for (const adjustment of [-1_000_000, -1, 1, 1_000_000]) {
    const body = { ...proposal(), adjustment, expectedCurrentHp: adjustment > 0 ? 0 : 1_000_000, source: 'x'.repeat(1900) };
    const response = await (await handleMcp(request(rpc('propose_hp_adjustment', body)), bridge, { ownerId, liveHp })).json();
    assert.equal(response.result.isError, false);
    assert.equal(calls.at(-1).args[2].adjustment, adjustment);
    assert.equal(calls.at(-1).args[2].source.length, 1900);
    assert.equal(Object.hasOwn(calls.at(-1).args[2], 'fields'), false);
  }
});
