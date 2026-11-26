/** Verify a source-backed native attack from a reviewed synthetic rehearsal and its portable restore. */
import assert from 'node:assert/strict';
import {SqliteAdapter} from '../../backend/owner-bridge/sqlite-adapter.mjs';
import {handleWorker} from '../../backend/owner-bridge/worker.mjs';
import {cloudParity} from '../../backend/owner-bridge/fixtures/cloud-parity.mjs';

const [file,mode]=process.argv.slice(2);
assert.ok(['edit','restored'].includes(mode));
const owner='fixture-owner',continuityId='fixture-continuity',actorId='fixture-actor',attackId='fixture-ray';
const base='/v3/continuities/'+continuityId,origin='https://synthetic-source.example';
const db=new SqliteAdapter(file),env={DB:db,OWNER_SITE_USER_ID:owner,ASTRAVAR_CLOUD_ENABLED:'1',ASTRAVAR_CLOUD_ENV:'isolated'};
async function post(path,body) {
  const r=await handleWorker(new Request(origin+path,{method:'POST',headers:{Origin:origin,'oai-authenticated-user-id':owner,'Content-Type':'application/json'},body:JSON.stringify(body)}),env);
  const content=await r.json();assert.equal(r.status,200,JSON.stringify(content));return content;
}
async function rpc(name,params={}) {
  const {result}=await post('/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId,...params}}});
  assert.equal(result.isError??false,false,JSON.stringify(result));return result.structuredContent;
}
const read=async id=>(await rpc('get_cloud_state',{id})).records[0];
try {
  const scopes=['character','combat','inventory','resources','rolls'].flatMap(v=>[v+'.read',v+'.write']);
  await post(base+'/grants/sites-mcp',{scopes,auto:scopes.filter(s=>s.endsWith('.write')),actors:['*'],revoked:false,expiresAt:Date.now()+86400000,expectedGrantRevision:0});
  const parity=cloudParity(()=>db,owner,continuityId);
  async function submit(commandId,operation,args){
    const {epoch,revision}=await rpc('get_cloud_state',{limit:1});
    return parity({version:1,epoch,expectedRevision:revision,commandId,operation,args});
  }
  const before=await read(attackId),{recordedSource,selections,...rules}=before.data;
  assert.equal(recordedSource.profileId,actorId);assert.equal(recordedSource.attackId,attackId);assert.equal(recordedSource.sheetRevision,8);
  assert.deepEqual(selections,[{actorId,componentId:'element',type:'cold',formId:null}]);
  assert.deepEqual(rules.cost,{poolId:'fixture-pool',amount:1});assert.equal(rules.sourceItemId,'fixture-gear');
  assert.deepEqual(rules.components.map(c=>c.id),['ray-base','element']);assert.deepEqual(rules.components[1].variants,['fire','cold']);
  assert.equal((await read('fixture-gear')).data.equipment,'held');
  if(mode==='edit'){
    assert.equal(before.title,'Tuning ray');assert.equal((await read('fixture-pool')).data.current,1);
    const preview=await rpc('get_cloud_actions',{id:actorId});
    const listed=preview.rows.find(row=>row.id===attackId);
    assert.deepEqual(listed.components.map(c=>c.type),['force','cold']);
    assert.equal((await rpc('get_cloud_state',{kind:'attack_attempt'})).records.length,0);
    const edit=await submit('fixture-rename','update_attack',{actorId,attackId,...rules,name:'Reviewed tuning ray',expectedAttackRevision:before.revision});
    assert.equal(edit.status,'COMMITTED');assert.equal((await read('fixture-pool')).data.current,1);
    const updated=await read(attackId);assert.deepEqual(updated.data,before.data);assert.equal(updated.revision,before.revision+1);
    const hit=await submit('fixture-fire','resolve_attack',{actorId,attackId,attemptId:'ray-shot',mode:'normal',hit:{origin:'player_physical',faces:[16]},
      includeDamage:true,damage:[{id:'ray-base',evidence:{origin:'player_physical',faces:[3]}},{id:'element',evidence:{origin:'player_physical',faces:[5]}}]});
    assert.equal(hit.status,'COMMITTED');
  }
  assert.equal((await read(attackId)).title,'Reviewed tuning ray');assert.equal((await read('fixture-pool')).data.current,0);
  const attempt=await read('ray-shot');assert.equal(attempt.data.attackId,attackId);assert.equal(attempt.data.sourceItemId,'fixture-gear');
  assert.deepEqual(attempt.data.damage.map(p=>[p.id,p.type,p.total]),[['ray-base','force',4],['element','cold',7]]);
  assert.equal(attempt.data.outcome,'pending');
  const edges=(await rpc('get_cloud_record_links',{id:attackId})).links;
  assert.ok(edges.some(e=>e.relation==='requires_item'&&e.to_id==='fixture-gear'));
  assert.ok(edges.some(e=>e.relation==='uses_pool'&&e.to_id==='fixture-pool'));
  const editReceipt=await rpc('get_cloud_receipt',{commandId:'fixture-rename'}),shotReceipt=await rpc('get_cloud_receipt',{commandId:'fixture-fire'});
  const original=editReceipt.receipt.changes.find(c=>c.before?.id===attackId)?.before;
  assert.ok(original);assert.equal(original.title,'Tuning ray');assert.deepEqual(original.data.recordedSource,recordedSource);
  console.log(JSON.stringify({originalSource:recordedSource,editReceipt,shotReceipt,shot:attempt,attack:await read(attackId)}));
} finally {db.close();}
