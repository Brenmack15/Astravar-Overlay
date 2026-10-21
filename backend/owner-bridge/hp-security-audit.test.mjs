/** Independent adversarial checks. Every profile/value/token below is generated
 * for an isolated SQLite fixture; this never contacts or changes a live device.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { SqliteAdapter } from './sqlite-adapter.mjs';
import { OwnerBridge, BridgeError, CAMPAIGN, PROFILE, canonical, sha256 } from './core.mjs';
import { LiveHpBridge, HP_MODE, HP_FRESHNESS_MS } from './live-hp.mjs';
import { handleOwnerApi } from './http.mjs';

const SCHEMA = readFileSync(new URL('./schema.sql', import.meta.url), 'utf8');
const campaign = 'hp-security-fixture';
const profileId = 'player-security-fixture';
const owner = { ownerId: 'isolated-security-owner', role: 'owner', credentialId: 'sites-owner', scopes: [] };
const connector = { ownerId: owner.ownerId, role: 'connector', credentialId: 'sites-mcp', scopes: ['hp:read', 'hp:propose', 'hp:receipt'] };
const denied = (operation, ...codes) => assert.rejects(operation, error => error instanceof BridgeError && codes.includes(error.code));

async function fixture(initialFields = {}) {
  const db = new SqliteAdapter(); db.exec(SCHEMA);
  let clock = 1_000_000;
  const bridge = new OwnerBridge(db, { ownerId: owner.ownerId, now: () => clock });
  const hp = new LiveHpBridge(db, { ownerId: owner.ownerId, now: () => clock });
  const fields = { name: 'Isolated HP audit fixture', currentHp: 40, maximumHp: 66, temporaryHp: 7,
    source: 'Generated security fixture; no campaign data', updatedAt: clock, ...initialFields };
  async function enroll(id = profileId, targetCampaign = campaign) {
    const secret = Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('base64url');
    const request = { campaignId: targetCampaign, deviceName: 'Isolated automated audit device', deviceSecretHash: await sha256(secret),
      ...(targetCampaign === CAMPAIGN ? {} : { profileId: id }) };
    const pair = await bridge.requestPairing(request);
    assert.equal((await bridge.pollPairing({ deviceCode: pair.deviceCode, deviceSecret: secret })).status, 'PENDING');
    await bridge.approvePairing(owner, { userCode: pair.userCode });
    const token = await bridge.pollPairing({ deviceCode: pair.deviceCode, deviceSecret: secret });
    return { principal: await bridge.authenticate(token.credentialId, secret), token, secret };
  }
  const device = await enroll();
  const publication = { commandId: crypto.randomUUID(), expectedRevision: 0, profileId, revision: 7, fields };
  await hp.publishCharacter(device.principal, campaign, publication);
  const propose = async (overrides = {}) => {
    const body = { commandId: crypto.randomUUID(), profileId, expectedRevision: 7, expectedCurrentHp: 40,
      adjustment: -5, source: 'Isolated automatic security check', ...overrides };
    return { body, proposal: (await hp.proposeHpUpdate(connector, campaign, body)).proposal };
  };
  const decision = proposal => ({ commandId: proposal.commandId, approved: true,
    localAppliedRevision: proposal.expectedRevision + 1, localReceiptId: crypto.randomUUID(),
    proposalFingerprint: proposal.fingerprint, localFields: { ...proposal.currentFields,
      currentHp: proposal.fields.currentHp,
      source: `Player-approved HP connection test ${proposal.commandId} · ${proposal.source}`, updatedAt: clock } });
  return { db, bridge, hp, fields, publication, device, enroll, propose, decision,
    advance: amount => { clock += amount; }, close: () => db.close() };
}

test('HP audit: owner approval creates only the selected profile grant; legacy fictional authority is insufficient', async () => {
  const f = await fixture(); try {
    assert.deepEqual(f.device.principal.scopes.sort(), ['hp:read', 'hp:publish', 'hp:decide'].sort());
    assert.equal(f.device.principal.profileId, profileId);
    assert.equal(f.device.principal.campaignId, campaign);
    const legacy = await f.enroll(PROFILE, CAMPAIGN);
    await denied(() => f.hp.getCharacterHp(legacy.principal, campaign, profileId), 'SCOPE_DENIED');
    await denied(() => f.hp.getCharacterHp({ ...connector, ownerId: 'foreign-owner' }, campaign, profileId), 'OWNER_SCOPE_DENIED');
    await denied(() => f.hp.getCharacterHp({ ...connector, scopes: [] }, campaign, profileId), 'SCOPE_DENIED');
    const credentials = f.db.db.prepare('SELECT token_hash FROM bridge_credentials').all();
    assert.equal(JSON.stringify(credentials).includes(f.device.secret), false);
  } finally { f.close(); }
});

test('HP audit: another profile or campaign cannot borrow an enrolled device or its receipt/history rights', async () => {
  const f = await fixture(); try {
    const other = await f.enroll('second-isolated-profile');
    await f.hp.publishCharacter(other.principal, campaign, { commandId: crypto.randomUUID(), expectedRevision: 0,
      profileId: other.principal.profileId, revision: 1, fields: { ...f.fields, name: 'Second isolated fixture', currentHp: 12 } });
    await denied(() => f.hp.getCharacterHp(f.device.principal, campaign, other.principal.profileId), 'SCOPE_DENIED');
    await denied(() => f.hp.getCharacterHp({ ...f.device.principal, profileId: other.principal.profileId }, campaign, other.principal.profileId), 'HP_CONNECTION_NOT_AUTHORIZED');
    await denied(() => f.hp.getCharacterHp(f.device.principal, 'foreign-campaign', profileId), 'SCOPE_DENIED');
    await denied(() => f.hp.getCommandReceipt(other.principal, campaign, f.publication.commandId), 'SCOPE_DENIED');
    const result = await f.hp.history(other.principal, campaign);
    assert.equal(result.receipts.some(row => row.recordId === profileId), false);
  } finally { f.close(); }
});

test('HP audit: connector proposals never grant publication, device approval, enrollment, or unrelated field writes', async () => {
  const f = await fixture(); try {
    const { proposal } = await f.propose();
    await denied(() => f.hp.decide(connector, campaign, f.decision(proposal)), 'ROLE_DENIED');
    await denied(() => f.hp.publishCharacter(connector, campaign, { ...f.publication, commandId: crypto.randomUUID() }), 'ROLE_DENIED');
    await denied(() => f.bridge.approvePairing(connector, { userCode: 'not-an-owner-code' }), 'ROLE_DENIED');
    await denied(() => f.hp.proposeHpUpdate(f.device.principal, campaign, { commandId: crypto.randomUUID(), profileId,
      expectedRevision: 7, expectedCurrentHp: 40, adjustment: -5, source: 'Fixture' }), 'ROLE_DENIED');
    await denied(() => f.hp.publishCharacter(f.device.principal, campaign, { ...f.publication, commandId: crypto.randomUUID(),
      fields: { ...f.fields, armorClass: 99 } }), 'INVALID_REQUEST');
    for (const key of ['maximumHp', 'temporaryHp', 'name']) {
      const body = f.decision(proposal);
      body.localFields[key] = key === 'name' ? 'Unauthorized rename' : 999;
      await denied(() => f.hp.decide(f.device.principal, campaign, body), 'LOCAL_FIELDS_MISMATCH');
    }
    const unchanged = await f.hp.getCharacterHp(connector, campaign, profileId);
    assert.equal(unchanged.revision, 7); assert.deepEqual(unchanged.fields, f.fields);
    await denied(() => f.hp.getCommandReceipt(connector, campaign, proposal.commandId), 'RECEIPT_NOT_READY');
  } finally { f.close(); }
});

test('HP audit: signed adjustment is fingerprint-bound; exact replay after approval cannot apply again', async () => {
  const f = await fixture(); try {
    const { body, proposal } = await f.propose();
    const expected = await sha256(canonical({ campaignId: campaign, commandId: body.commandId, recordId: profileId,
      expectedRevision: 7, fields: { currentHp: 35 }, source: body.source, adjustment: -5 }));
    assert.equal(proposal.fingerprint, expected);
    assert.equal((await f.hp.getCharacterHp(connector, campaign, profileId)).fields.currentHp, 40);
    const decision = f.decision(proposal);
    const first = (await f.hp.decide(f.device.principal, campaign, decision)).receipt;
    assert.equal(first.status, 'APPLIED'); assert.equal(first.resultingRevision, 8);
    assert.deepEqual((await f.hp.decide(f.device.principal, campaign, decision)).receipt, first);
    assert.deepEqual((await f.hp.proposeHpUpdate(connector, campaign, body)).proposal, proposal);
    for (const changed of [{ adjustment: -4 }, { expectedCurrentHp: 39 }, { expectedRevision: 8 }, { source: body.source + ' changed' }]) {
      await denied(() => f.hp.proposeHpUpdate(connector, campaign, { ...body, ...changed }), 'COMMAND_ID_REUSED');
    }
    const after = await f.hp.getCharacterHp(connector, campaign, profileId);
    assert.equal(after.revision, 8); assert.equal(after.fields.currentHp, 35);
    assert.equal(after.fields.maximumHp, 66); assert.equal(after.fields.temporaryHp, 7);
    assert.equal(after.fields.name, f.fields.name);
    assert.equal((await f.hp.getPendingUpdates(connector, campaign)).proposals.length, 0);
    assert.equal(f.db.db.prepare('SELECT COUNT(*) AS n FROM bridge_hp_receipts WHERE command_id=?').get(body.commandId).n, 1);
    assert.throws(() => f.db.db.exec('DELETE FROM bridge_hp_receipts'), /IMMUTABLE_RECEIPT/);
  } finally { f.close(); }
});

test('HP audit: unknown/stale HP is not guessed; read requests cannot renew device freshness', async () => {
  for (const fields of [{ currentHp: null }, { maximumHp: null }]) {
    const f = await fixture(fields); try { await denied(() => f.propose(), 'HP_UNKNOWN'); } finally { f.close(); }
  }
  const f = await fixture(); try {
    const first = await f.hp.getCharacterHp(connector, campaign, profileId);
    f.advance(HP_FRESHNESS_MS + 1);
    const stale = await f.hp.getCharacterHp(connector, campaign, profileId);
    assert.equal(stale.stale, true); assert.equal(stale.lastDeviceSyncAt, first.lastDeviceSyncAt);
    await denied(() => f.propose(), 'DEVICE_SYNC_STALE');
    const freshCommand = { ...f.publication, commandId: crypto.randomUUID(), expectedRevision: 7 };
    await f.hp.publishCharacter(f.device.principal, campaign, freshCommand);
    const current = await f.hp.getCharacterHp(connector, campaign, profileId);
    assert.equal(current.revision, 7); assert.equal(current.stale, false);
    assert.ok(current.lastDeviceSyncAt > first.lastDeviceSyncAt);
  } finally { f.close(); }
});

test('HP audit: revocation or expiry stops previously authenticated principals and profile replacement revokes its old writer', async () => {
  const f = await fixture(); try {
    const { proposal } = await f.propose();
    const replacement = await f.enroll();
    await denied(() => f.bridge.authenticate(f.device.token.credentialId, f.device.secret), 'CREDENTIAL_REVOKED_OR_EXPIRED');
    await denied(() => f.hp.decide(f.device.principal, campaign, f.decision(proposal)), 'HP_CONNECTION_NOT_AUTHORIZED');
    assert.equal((await f.hp.getPendingUpdates(replacement.principal, campaign)).proposals.length, 0);
    await f.hp.publishCharacter(replacement.principal, campaign, { ...f.publication, commandId: crypto.randomUUID(), expectedRevision: 7 });
    f.advance(31 * 24 * 60 * 60_000);
    await denied(() => f.bridge.authenticate(replacement.token.credentialId, replacement.secret), 'CREDENTIAL_REVOKED_OR_EXPIRED');
    await denied(() => f.hp.getCharacterHp(replacement.principal, campaign, profileId), 'HP_CONNECTION_NOT_AUTHORIZED');
    await denied(() => f.hp.getCharacterHp(connector, campaign, profileId), 'HP_CONNECTION_NOT_AUTHORIZED');
  } finally { f.close(); }
});

test('HP audit: rejection is final, and unsafe ranges or invented approval authority leave HP untouched', async () => {
  const f = await fixture(); try {
    for (const invalid of [{ adjustment: 0 }, { adjustment: -41 }, { adjustment: 27 }, { adjustment: 0.5 },
      { adjustment: '5' }, { expectedCurrentHp: '40' }, { source: 'S'.repeat(1901) }, { approved: true }, { fields: { currentHp: 2 } }]) {
      await assert.rejects(() => f.propose(invalid));
    }
    const { proposal } = await f.propose();
    const reject = { commandId: proposal.commandId, approved: false, localAppliedRevision: null, localReceiptId: null,
      proposalFingerprint: proposal.fingerprint, localFields: null };
    const receipt = (await f.hp.decide(f.device.principal, campaign, reject)).receipt;
    assert.equal(receipt.status, 'REJECTED'); assert.equal(receipt.resultingRevision, 7);
    await denied(() => f.hp.decide(f.device.principal, campaign, f.decision(proposal)), 'COMMAND_ID_REUSED');
    assert.equal((await f.hp.getCharacterHp(connector, campaign, profileId)).fields.currentHp, 40);
  } finally { f.close(); }
});

test('HP audit: owner HTTP revocation actually disables the selected live campaign credential', async () => {
  const f = await fixture(); try {
    const response = await handleOwnerApi(new Request('https://owner.example/v1/owner/devices/revoke', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Origin: 'https://owner.example' },
      body: JSON.stringify({ credentialId: f.device.token.credentialId }),
    }), f.bridge, { ownerPrincipal: owner });
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.equal(result.revoked, true);
    await denied(() => f.bridge.authenticate(f.device.token.credentialId, f.device.secret), 'CREDENTIAL_REVOKED_OR_EXPIRED');
    await denied(() => f.hp.getCharacterHp(f.device.principal, campaign, profileId), 'HP_CONNECTION_NOT_AUTHORIZED');
  } finally { f.close(); }
});

test('HP audit: pairing scope IDs stay strictly typed and cannot request broader credentials or fixed fictional profiles', async () => {
  const f = await fixture(); try {
    const before = f.db.db.prepare('SELECT COUNT(*) AS n FROM bridge_hp_bindings').get().n;
    const base = { campaignId: campaign, profileId, deviceName: 'Malformed-scope fixture', deviceSecretHash: 'a'.repeat(64) };
    for (const invalid of [{ campaignId: 123 }, { profileId: 456 }, { campaignId: [campaign] }, { profileId: [profileId] },
      { campaignId: 'scope/escape' }, { profileId: PROFILE }, { campaignId: CAMPAIGN }, { scopes: ['hp:publish', '*'] }, { ownerId: owner.ownerId }]) {
      await assert.rejects(() => f.bridge.requestPairing({ ...base, ...invalid }));
    }
    assert.equal(f.db.db.prepare('SELECT COUNT(*) AS n FROM bridge_hp_bindings').get().n, before);
    const unchanged = await f.hp.getCharacterHp(connector, campaign, profileId);
    assert.equal(unchanged.fields.currentHp, 40); assert.equal(unchanged.revision, 7);
  } finally { f.close(); }
});

test('HP audit: full pending quota rejects more active work but cannot permanently lock out an owner-approved replacement', async () => {
  const f = await fixture(); try {
    for (let i = 0; i < 50; i++) await f.propose();
    assert.equal((await f.hp.getPendingUpdates(f.device.principal, campaign)).proposals.length, 50);
    await denied(() => f.propose(), 'PENDING_LIMIT');
    const replacement = await f.enroll();
    await f.hp.publishCharacter(replacement.principal, campaign, { ...f.publication, commandId: crypto.randomUUID(), expectedRevision: 7 });
    assert.equal((await f.hp.getPendingUpdates(replacement.principal, campaign)).proposals.length, 0);
    await f.propose();
    assert.equal((await f.hp.getPendingUpdates(replacement.principal, campaign)).proposals.length, 1);
    assert.equal(f.db.db.prepare('SELECT COUNT(*) AS n FROM bridge_hp_proposals').get().n, 51);
    const record = await f.hp.getCharacterHp(connector, campaign, profileId);
    assert.equal(record.fields.currentHp, 40); assert.equal(record.revision, 7);
  } finally { f.close(); }
});
