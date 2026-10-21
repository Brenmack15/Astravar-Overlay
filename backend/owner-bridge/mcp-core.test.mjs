import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { OwnerBridge, sha256, CAMPAIGN, PROFILE, RECORD } from './core.mjs';
import { SqliteAdapter } from './sqlite-adapter.mjs';
import { handleMcp } from './mcp.mjs';

test('real SQLite core through MCP stages proposal, requires device decision, then returns immutable receipt and independent readback', async () => {
  const db = new SqliteAdapter();
  db.exec(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'));
  const ownerId = 'fictional-site-owner';
  const bridge = new OwnerBridge(db, { ownerId });
  const owner = { ownerId, role: 'owner', scopes: [] };
  const secret = 'n'.repeat(43);
  try {
    const pairing = await bridge.requestPairing({ campaignId: CAMPAIGN, deviceName: 'Fictional acceptance phone', deviceSecretHash: await sha256(secret) });
    await bridge.approvePairing(owner, { userCode: pairing.userCode });
    const enrolled = await bridge.pollPairing({ deviceCode: pairing.deviceCode, deviceSecret: secret });
    const device = await bridge.authenticate(enrolled.credentialId, secret);
    const source = 'Fictional native fixture';
    await bridge.publishCharacter(device, CAMPAIGN, {
      commandId: 'e8fa8060-e280-4b55-b142-ddab94c81b88', expectedRevision: 0,
      profile: { id: PROFILE, name: 'Fictional Acceptance Artificer', revision: 1, source },
      ability: { recordId: RECORD, kind: 'character_ability', revision: 1,
        fields: { profileId: PROFILE, key: 'INT', score: 18, magicModifier: 0, notes: '', source, updatedAt: 1 } },
    });
    const invoke = async (name, args) => {
      const request = new Request('https://fictional-owner.example/mcp', { method: 'POST', headers: {
        'Content-Type': 'application/json', 'oai-authenticated-user-id': ownerId,
      }, body: JSON.stringify({ jsonrpc: '2.0', id: 'fictional-trace', method: 'tools/call', params: { name, arguments: args } }) });
      return (await (await handleMcp(request, bridge, { ownerId })).json()).result;
    };
    const original = await invoke('get_character_abilities', { campaignId: CAMPAIGN, profileId: PROFILE });
    assert.equal(original.isError, false);
    assert.equal(original.structuredContent.abilities[0].fields.score, 18);
    const commandId = 'f862465c-9386-4859-9ba7-768196ae5f8e';
    const staged = await invoke('propose_record_update', { campaignId: CAMPAIGN, commandId, recordId: RECORD, expectedRevision: 1, fields: { score: 20 }, source: 'Fictional ChatGPT request' });
    assert.equal(staged.isError, false);
    assert.deepEqual(staged.structuredContent.proposal.differences, [{ field: 'score', current: 18, proposed: 20 }]);
    const stillOriginal = await invoke('get_character_abilities', { campaignId: CAMPAIGN, profileId: PROFILE });
    assert.equal(stillOriginal.structuredContent.abilities[0].fields.score, 18);
    const premature = await invoke('get_command_receipt', { campaignId: CAMPAIGN, commandId });
    assert.equal(premature.isError, true);
    assert.equal(JSON.parse(premature.content[0].text).error, 'RECEIPT_NOT_READY');
    const pending = await bridge.getPendingUpdates(device, CAMPAIGN);
    assert.equal(pending.proposals.length, 1);
    const decision = { commandId, approved: true, localAppliedRevision: 2, localReceiptId: 'native-fixture-local-receipt', proposalFingerprint: pending.proposals[0].fingerprint,
      localFields: { ...pending.proposals[0].currentFields, score: 20, source: `Player-approved owner proposal ${commandId} · ${pending.proposals[0].source}`, updatedAt: 2 } };
    const committed = await bridge.decide(device, CAMPAIGN, decision);
    assert.equal(committed.receipt.status, 'APPLIED');
    const receipt = await invoke('get_command_receipt', { campaignId: CAMPAIGN, commandId });
    assert.equal(receipt.structuredContent.receipt.resultingRevision, 2);
    const duplicate = await bridge.decide(device, CAMPAIGN, decision);
    assert.deepEqual(duplicate.receipt, committed.receipt);
    const readback = await invoke('get_character_abilities', { campaignId: CAMPAIGN, profileId: PROFILE });
    assert.equal(readback.structuredContent.abilities[0].fields.score, 20);
    assert.equal(readback.structuredContent.abilities[0].revision, 2);
  } finally { db.close(); }
});
