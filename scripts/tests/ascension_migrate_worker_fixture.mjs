/** Exercise the same in-process authenticated Worker surface as the native and DM clients on a rehearsal SQLite DB. */
import assert from 'node:assert/strict';
import { SqliteAdapter } from '../../backend/owner-bridge/sqlite-adapter.mjs';
import { handleWorker } from '../../backend/owner-bridge/worker.mjs';

const [file, action, epochText] = process.argv.slice(2);
const epoch = Number(epochText);
const owner = 'fixture-owner', continuityId = 'fixture-campaign';
const origin = 'https://offline-rehearsal.example';
const db = new SqliteAdapter(file);
const env = { DB: db, OWNER_SITE_USER_ID: owner, ASTRAVAR_CLOUD_ENABLED: '1', ASTRAVAR_CLOUD_ENV: 'isolated' };
const coins = gp => ({cp:0,sp:0,ep:0,gp,pp:0});
const scopes = ['character','economy','inventory','resources','world','chronicle'].flatMap(s => [s+'.read', s+'.write']);
const source = 'offline recovery fixture';
async function post(path, body) {
  const request = new Request(origin + path, { method: 'POST', body: JSON.stringify(body),
    headers: { 'Content-Type':'application/json', Origin:origin, 'oai-authenticated-user-id':owner } });
  return (await handleWorker(request, env)).json();
}
async function rpc(name, args) {
  const envelope = await post('/mcp', {jsonrpc:'2.0', id:1, method:'tools/call', params:{name, arguments:{continuityId, ...args}}});
  return envelope.result?.structuredContent ?? JSON.parse(envelope.result.content[0].text);
}
try {
  const beforeGrant = await rpc('get_cloud_state', {});
  assert.ok(!beforeGrant.records, 'expected a new installation with no inherited DM grants');
  const grant = await post('/v3/continuities/' + continuityId + '/grants/sites-mcp', {
    scopes, auto:scopes.filter(s=>s.endsWith('.write')), actors:['*'],
    expiresAt:Date.now()+86400000, expectedGrantRevision:0, revoked:false
  });
  assert.equal(grant.revoked, false, JSON.stringify(grant));
  const operations = [
    ['define_actor', {actorId:'archivist', name:'Fixture archivist', role:'player', rulesProfile:'legacy_2014', level:4, abilities:{INT:14}}],
    ['define_wallet', {walletId:'personal', title:'Personal purse', holderId:'archivist', shared:false, coins:coins(19), source}],
    ['define_item_definition', {definitionId:'book-type', name:'Fixture volume', category:'book', description:'A blank volume', rarity:null,
      stackable:false, wearable:false, handheld:true, attunement:'none', source}],
    ['purchase_item', {itemId:'volume', definitionId:'book-type', ownerId:'archivist', quantity:1, walletId:'personal', price:coins(4), sellerId:null, source}],
    ['define_resource', {actorId:'archivist', poolId:'tokens', name:'Tokens', type:'ordinary', maximum:5, current:4, recovery:'none', source}],
    ['spend_resource', {actorId:'archivist', poolId:'tokens', amount:2}],
    ['define_connection', {entityId:'archive', name:'Archive', category:'place', actorId:null, knownDescription:'A known room.', source}],
    ['record_chronicle_event', {eventId:'visit', title:'Visit', narrative:'The archivist counted the shelves.', category:'travel', status:'confirmed',
      worldTime:null, subjects:[{kind:'actor',id:'archivist'},{kind:'world_entity',id:'archive'}], correctsId:null, source}],
    ['save_checkpoint', {checkpointId:'reading', title:'Reading room', scene:'At the shelves', locationId:'archive', worldTime:null, lastEventId:'visit',
      unresolved:'Choose a book.', pendingRollId:null, encounterId:null, objectiveIds:[], actorIds:['archivist'], source}],
  ];
  assert.ok((action === 'seed' && epoch === 2) || (action === 'read' && epoch > 2));
  const receipts = [];
  for (const [index, [operation, args]] of operations.entries()) {
    const commandId = 'fixture-command-' + index;
    if (action === 'seed') {
      const command = {version:1, epoch, expectedRevision:index, commandId, operation, args};
      const wrote = await rpc('submit_cloud_command', {command});
      assert.equal(wrote.status, 'COMMITTED', JSON.stringify(wrote));
    }
    const proof = await rpc('get_cloud_receipt', {commandId});
    assert.equal(proof.status, 'COMMITTED');
    receipts.push(proof.receipt);
  }
  const state = await rpc('get_cloud_state', {limit:100});
  assert.equal(state.epoch, epoch);
  assert.equal(state.revision, operations.length);
  const find = id => state.records.find(r=>r.id === id);
  assert.equal(find('archivist').data.abilities.INT, 14);
  assert.equal(find('personal').data.coins.gp, 15);
  assert.equal(find('volume').data.quantity, 1);
  assert.equal(find('tokens').data.current, 2);
  assert.equal(find('reading').data.unresolved, 'Choose a book.');
  console.log(JSON.stringify({receipts, records:state.records}));
} finally { db.close(); }
