import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {handleWorker} from './worker.mjs';
import {CloudService} from './cloud-service.mjs';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {cloudReviewInbox} from './cloud-approval-view.mjs';
import {sha256} from './core.mjs';

const schema=readFileSync(new URL('schema.sql',import.meta.url),'utf8'),owner='fixture-owner-inbox',id='qa-approvals',base='/v3/continuities/'+id;
const origin='https://private-owner.example',p='person',dm={role:'connector',ownerId:owner,credentialId:'sites-mcp'};
async function fixture(t){
  const db=new SqliteAdapter();t.after(()=>db.close());db.exec(schema);
  const env={DB:db,OWNER_SITE_USER_ID:owner,ASTRAVAR_CLOUD_ENABLED:'1',ASTRAVAR_CLOUD_ENV:'isolated'};
  const token=crypto.randomUUID().repeat(2),expiresAt=Date.now()+86400000;
  const wire=async(path,{method='GET',body,as='owner'}={})=>{
    const headers={'Content-Type':'application/json',Origin:origin};
    if(as==='owner')headers['oai-authenticated-user-id']=owner;
    if(as==='device'){headers.Authorization='Bearer '+token;headers['X-Astravar-Credential']='fixture-device';}
    const r=await handleWorker(new Request(origin+path,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)})}),env);
    return {status:r.status,value:await r.json()};
  };
  const get=async(path,as='device')=>wire(base+path,{as});
  const post=async(path,body,as='owner')=>wire(base+path,{method:'POST',body,as});
  const grant=async(sub,scopes,rev,actors=['*'],revoked=false)=>{
    const r=await post('/grants/'+sub,{scopes,actors,auto:[],expiresAt,expectedGrantRevision:rev,revoked});
    assert.equal(r.status,200,JSON.stringify(r.value));
  };
  const cmd=(commandId,expectedRevision,operation,args)=>({version:1,epoch:1,commandId,expectedRevision,operation,args});
  const rpc=async command=>{
    const r=await wire('/mcp',{method:'POST',body:{jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'submit_cloud_command',arguments:{continuityId:id,command}}}});
    assert.equal(r.value.result.isError,false,JSON.stringify(r.value));return r.value.result.structuredContent;
  };
  assert.equal((await post('/isolated',{})).status,200);
  db.db.prepare("INSERT INTO bridge_credentials(id,owner_id,campaign_id,token_hash,role,scopes_json,device_name,expires_at) VALUES(?,?,?,?,'device','[]','Fixture',?)")
    .run('fixture-device',owner,id,await sha256(token),expiresAt);
  await grant('sites-mcp',['character.read','character.write','resources.read','resources.write'],0);
  const actor=await post('/commands',cmd('inbox-fixture-create',0,'define_actor',{actorId:p,name:'Fixture',role:'player',rulesProfile:'legacy_2014',level:2,abilities:{INT:14}}));
  assert.equal(actor.value.status,'COMMITTED');
  const single=await rpc(cmd('inbox-first-proposal',1,'set_actor_ability',{actorId:p,ability:'INT',score:16}));
  const mixed=await rpc(cmd('inbox-second-proposal',1,'define_resource',{actorId:p,poolId:'typed-pool',name:'Private resource fixture',source:'review fixture',
    type:'charge',maximum:2,current:1,recovery:'none'}));
  assert.equal(single.status,'PENDING_APPROVAL');assert.equal(mixed.status,'PENDING_APPROVAL');
  return {db,env,wire,get,post,cmd,single,mixed,grant,detail:proposal=>'/review-inbox/'+proposal.commandId+'?digest='+proposal.digest};
}

test('badge, queue pages and details enforce the whole device grant and every affected read scope',async t=>{
  const {db,wire,get,post,grant,single,mixed,detail}=await fixture(t),device='device:fixture-device';
  assert.equal((await get('/review-inbox')).status,403);
  await grant(device,['approval.read','character.read'],0);
  assert.equal((await get('/review-status')).value.actionableCount,1);
  const first=(await get('/review-inbox?limit=1')).value;
  assert.deepEqual(first.requests.map(r=>r.commandId),[single.commandId]);assert.equal(first.nextAfter,null);
  const permitted=await get(detail(single));assert.equal(permitted.status,200);
  assert.equal(permitted.value.current[p].data.abilities.INT,14);
  assert.equal(permitted.value.plan.changes[0].after.data.abilities.INT,16);
  assert.equal(permitted.value.reviewReason,'AUTO_SYNC_REVIEW_REQUIRED');
  assert.equal((await get(detail(mixed))).status,404);
  assert.equal((await get('/approvals')).status,403);
  assert.equal((await post('/approvals/'+single.commandId+'/refuse',{digest:single.digest},'device')).status,403);
  assert.equal((await get(detail(single).replace(single.digest,'0'.repeat(64)))).status,404);
  await grant(device,['approval.read','resources.read'],1);
  assert.equal((await get('/review-status')).value.actionableCount,0);
  assert.equal((await get(detail(mixed))).status,404);
  await grant(device,['approval.read','resources.read','character.read'],2,[p]);
  assert.equal((await get('/review-inbox')).status,403);
  await grant(device,['approval.read','resources.read','character.read'],3);
  const expanded=(await get('/review-inbox?limit=1')).value;
  assert.equal(expanded.actionableCount,2);assert.equal(expanded.pendingCount,2);
  assert.deepEqual((await get('/review-inbox?limit=1&after='+expanded.nextAfter)).value.requests.map(r=>r.commandId),[mixed.commandId]);
  assert.equal((await get(detail(mixed))).value.plan.changes.find(c=>c.after?.id==='typed-pool').after.title,'Private resource fixture');
  assert.equal((await wire('/v3/continuities/qa-other/review-inbox',{as:'device'})).status,403);
  await grant(device,['approval.read','resources.read','character.read'],4,['*'],true);
  assert.equal((await get('/review-inbox')).status,403);
  const service=new CloudService(db,{ownerId:owner,kinds:{actor:'character'},operations:{},isolated:true});
  await assert.rejects(cloudReviewInbox(service,dm,id,'list'),{code:'OWNER_INBOX_DENIED'});
});

test('current values survive stale proposals and owner refusal binds the exact digest with one terminal receipt',async t=>{
  const {db,get,post,cmd,grant,single,mixed,detail}=await fixture(t);
  await grant('device:fixture-device',['character.read','resources.read','approval.read'],0);
  const changed=cmd('different-owner-change',1,'set_actor_ability',{actorId:p,ability:'INT',score:15});
  assert.equal((await post('/commands',changed)).value.status,'COMMITTED');
  const stale=(await get(detail(single))).value;
  assert.equal(stale.readiness,'REVIEW_BASELINE_CHANGED');
  assert.deepEqual([stale.plan.changes[0].before.data.abilities.INT,stale.current[p].data.abilities.INT,stale.plan.changes[0].after.data.abilities.INT],[14,15,16]);
  await grant('sites-mcp',['character.read','character.write','resources.read','resources.write'],1);
  assert.equal((await get(detail(mixed))).value.readiness,'SUBMITTER_GRANT_CHANGED');
  assert.deepEqual([(await get('/review-inbox')).value.actionableCount,(await get('/review-inbox')).value.pendingCount],[0,2]);
  const path='/approvals/'+single.commandId;
  assert.equal((await post(path+'/refuse',{digest:mixed.digest})).status,409);
  const refused=await post(path+'/refuse',{digest:single.digest});
  assert.equal(refused.value.status,'REFUSED');
  assert.deepEqual((await post(path+'/verify',{digest:single.digest})).value,refused.value);
  const result=(await get(detail(single))).value;
  assert.equal(result.status,'REFUSED');assert.equal(result.reason,'OWNER_REFUSED');
  assert.equal(result.receipt.transactionId,refused.value.receipt.transactionId);
  assert.equal((await get('/review-inbox')).value.requests.length,1);
  assert.equal(db.db.prepare('SELECT count(*) AS n FROM cloud_receipts WHERE command_id=?').get(single.commandId).n,1);
  const directDigest=await sha256((await import('./core.mjs')).canonical(changed));
  assert.equal((await get('/review-inbox/'+changed.commandId+'?digest='+directDigest)).status,404);
});
