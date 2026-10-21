import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handleMcp, MCP_VERSION, TOOLS } from './mcp.mjs';

const campaignId = 'synthetic-owner-acceptance';
const profileId = 'owner-sync-fixture';
const recordId = 'owner-sync-fixture-int';
const commandId = 'de890f8b-d33d-4a17-92ab-16ed3138a30c';
const ownerId = 'test-site-owner';
const request = (body, headers = {}) => new Request('https://owner.example/mcp', {
  method: 'POST', headers: { 'Content-Type': 'application/json', 'oai-authenticated-user-id': ownerId, ...headers }, body: JSON.stringify(body),
});
const rpc = (method, params) => ({ jsonrpc: '2.0', id: 7, method, ...(params ? { params } : {}) });
const call = (name, args) => rpc('tools/call', { name, arguments: args });
const proposal = () => ({ campaignId, commandId, recordId, expectedRevision: 1, fields: { score: 20 }, source: 'Fictional player request; native approval still required.' });
function fixture() {
  const calls = [];
  const bridge = Object.fromEntries(['getCharacter', 'getCharacterAbilities', 'proposeRecordUpdate', 'getPendingUpdates', 'getCommandReceipt'].map(name => [name,
    async (...args) => { calls.push({ name, args }); return { mode: 'ASTRAVAR_OWNER_BRIDGE_V1', campaignId, pending: name === 'proposeRecordUpdate' }; }]));
  return { calls, bridge };
}

test('MCP preserves five fictional tools and adds HP and scoped campaign tools without an approval operation', async () => {
  const { bridge } = fixture();
  const discovery = await (await handleMcp(request(rpc('server/discover')), bridge, { ownerId })).json();
  assert.deepEqual(discovery.result.supportedVersions, [MCP_VERSION]);
  const listed = await (await handleMcp(request(rpc('tools/list')), bridge, { ownerId })).json();
  assert.equal(listed.result.tools.length, 29);
  assert.deepEqual(listed.result.tools.slice(19, 24).map(tool => tool.name), ['get_character', 'get_character_abilities', 'propose_record_update', 'get_pending_updates', 'get_command_receipt']);
  assert.deepEqual(listed.result.tools.map(tool => tool.name), TOOLS.map(tool => tool.name));
  assert.equal(listed.result.tools.some(tool => /approve|commit|revoke|enroll/.test(tool.name)), false);
});

test('MCP owner binding fails closed; token and caller-selected userId cannot replace identity', async () => {
  const { bridge, calls } = fixture();
  const body = call('get_character', { campaignId, profileId });
  const missing = new Request('https://owner.example/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer fictional-device-token' }, body: JSON.stringify(body) });
  assert.equal((await handleMcp(missing, bridge, { ownerId })).status, 401);
  assert.equal((await handleMcp(request(body, { 'oai-authenticated-user-id': 'different-owner' }), bridge, { ownerId })).status, 401);
  assert.equal((await handleMcp(request(body), bridge)).status, 503);
  assert.equal(calls.length, 0);
});

test('MCP only passes a server-constructed connector principal to scoped core operations', async () => {
  const { bridge, calls } = fixture();
  const actions = [
    ['get_character', { campaignId, profileId }, 'getCharacter'],
    ['get_character_abilities', { campaignId, profileId }, 'getCharacterAbilities'],
    ['propose_record_update', proposal(), 'proposeRecordUpdate'],
    ['get_pending_updates', { campaignId }, 'getPendingUpdates'],
    ['get_command_receipt', { campaignId, commandId }, 'getCommandReceipt'],
  ];
  for (const [name, args, method] of actions) {
    const response = await (await handleMcp(request(call(name, args)), bridge, { ownerId })).json();
    assert.equal(response.result.isError, false);
    const saved = calls.at(-1);
    assert.equal(saved.name, method);
    assert.equal(saved.args[0].role, 'connector');
    assert.equal(saved.args[0].ownerId, ownerId);
    assert.equal(saved.args[0].scopes.some(scope => /approve|commit|device/.test(scope)), false);
  }
  const saved = calls.find(value => value.name === 'proposeRecordUpdate').args[2];
  assert.equal(Object.hasOwn(saved, 'confirmed'), false);
  assert.equal(Object.hasOwn(saved, 'decision'), false);
  assert.deepEqual(saved.fields, { score: 20 });
});

test('MCP rejects extra fields, fabricated authority, invalid values and approval calls', async () => {
  const { bridge, calls } = fixture();
  const invalid = [
    ['get_character', { campaignId, profileId, includeDatabase: true }],
    ['propose_record_update', { ...proposal(), confirmed: true }],
    ['propose_record_update', { ...proposal(), principal: { role: 'device' } }],
    ['propose_record_update', { ...proposal(), fields: { score: 20, currentHp: 66 } }],
    ['propose_record_update', { ...proposal(), fields: { score: 20.5 } }],
    ['propose_record_update', { ...proposal(), fields: { score: 31 } }],
    ['propose_record_update', { ...proposal(), expectedRevision: 0 }],
    ['propose_record_update', { ...proposal(), commandId: 'not-a-uuid' }],
    ['propose_record_update', { ...proposal(), source: ' ' }],
    ['propose_record_update', { ...proposal(), source: 'x'.repeat(1901) }],
    ['approve_record_update', proposal()],
  ];
  for (const [name, args] of invalid) {
    const response = await (await handleMcp(request(call(name, args)), bridge, { ownerId })).json();
    assert.equal(response.error.code, -32602);
  }
  assert.equal(calls.length, 0);
  const live=await (await handleMcp(request(call('get_character',{campaignId:'live-unauthorized',profileId:'local-player'})),bridge,{ownerId})).json();
  assert.equal(live.result.isError,true);
});

test('MCP accepts the exact sourced provenance limit without truncation', async () => {
  const { bridge, calls } = fixture();
  const source = 'x'.repeat(1900);
  const response = await (await handleMcp(request(call('propose_record_update', { ...proposal(), source })), bridge, { ownerId })).json();
  assert.equal(response.result.isError, false);
  assert.equal(calls[0].args[2].source, source);
});

test('MCP sanitizes core errors and never returns transport credentials', async () => {
  const { bridge } = fixture();
  bridge.getCharacter = async () => { throw new Error('Secret bearer abc should not escape'); };
  const response = await (await handleMcp(request(call('get_character', { campaignId, profileId })), bridge, { ownerId })).json();
  assert.equal(response.result.isError, true);
  assert.equal(response.result.content[0].text.includes('abc'), false);
  assert.equal(JSON.parse(response.result.content[0].text).error, 'OPERATION_FAILED');
  bridge.getCharacter = async () => { throw Object.assign(new Error('Details hidden'), { code: 'REVISION_CONFLICT' }); };
  const conflict = await (await handleMcp(request(call('get_character', { campaignId, profileId })), bridge, { ownerId })).json();
  assert.equal(JSON.parse(conflict.result.content[0].text).error, 'REVISION_CONFLICT');
});

test('MCP rejects oversized and malformed requests before running backend code', async () => {
  const { bridge, calls } = fixture();
  assert.equal((await handleMcp(request({ ...rpc('tools/list'), padding: 'x'.repeat(65_536) }), bridge, { ownerId })).status, 413);
  for (const body of [[rpc('tools/list')], { ...rpc('tools/list'), jsonrpc: '1.0' }, { ...rpc('tools/list'), id: {} }, { ...rpc('tools/list'), role: 'owner' }]) {
    assert.equal((await handleMcp(request(body), bridge, { ownerId })).status, 400);
  }
  const notJson = new Request('https://owner.example/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json', 'oai-authenticated-user-id': ownerId }, body: '{bad' });
  assert.equal((await handleMcp(notJson, bridge, { ownerId })).status, 400);
  assert.equal(calls.length, 0);
});
