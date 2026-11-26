/** Independently open a newly migrated temporary database using the real private client contracts. */
import assert from 'node:assert/strict';
import {SqliteAdapter} from '../../backend/owner-bridge/sqlite-adapter.mjs';
import {handleWorker} from '../../backend/owner-bridge/worker.mjs';

const file = process.argv[2];
const owner = 'fixture-owner', continuityId = 'fixture-continuity';
const domain = 'https://offline-rehearsal.example';
const db = new SqliteAdapter(file);
const env = {DB:db, OWNER_SITE_USER_ID:owner, ASTRAVAR_CLOUD_ENABLED:'1', ASTRAVAR_CLOUD_ENV:'isolated'};
async function call(path, body) {
  const response = await handleWorker(new Request(domain+path, {method:'POST', body:JSON.stringify(body),
    headers:{'Content-Type':'application/json', Origin:domain, 'oai-authenticated-user-id':owner}}), env);
  assert.equal(response.ok,true);
  return response.json();
}
async function read(name, args) {
  const result=await call('/mcp',{jsonrpc:'2.0', id:1, method:'tools/call',params:{name,arguments:{continuityId,...args}}});
  assert.ok(!result.result.isError,JSON.stringify(result.result));
  return result.result.structuredContent ?? JSON.parse(result.result.content[0].text);
}
try {
  await call('/v3/continuities/'+continuityId+'/grants/sites-mcp', {
    scopes:['character.read','resources.read','inventory.read','economy.read'], auto:[],actors:['*'],
    expectedGrantRevision:0, revoked:false,expiresAt:Date.now()+86400000,
  });
  const sheet=await read('get_cloud_actor_sheet',{id:'fixture-actor'});
  const state=await read('get_cloud_state',{limit:20});
  const wallets=await read('get_cloud_record_view',{view:'wallets'});
  const gear=await read('get_cloud_record_view',{view:'inventory',id:'fixture-gear'});
  const find=id=>state.records.find(row=>row.id===id);
  assert.equal(sheet.revision,0);
  assert.equal(sheet.epoch,2);
  assert.equal(find('fixture-actor').data.currentHp,13);
  assert.equal(find('fixture-pool').data.current,1);
  assert.equal(wallets.wallets[0].data.coins.gp,14);
  assert.equal(wallets.wallets[0].data.coins.sp,null);
  assert.equal(gear.holding.data.quantity,3);
  assert.equal(gear.holding.data.ownerId,'fixture-actor');
  assert.equal(gear.definition.id,'fixture-definition');
  console.log(JSON.stringify({epoch:state.epoch,revision:state.revision,ordinaryHp:13,pool:1,items:3,
    coins:wallets.wallets[0].data.coins,definitionId:gear.definition.id}));
} finally {
  db.close();
}
