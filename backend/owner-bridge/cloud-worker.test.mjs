import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {handleWorker} from './worker.mjs';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {sha256} from './core.mjs';

const schema=readFileSync(new URL('schema.sql',import.meta.url),'utf8');
const origin='https://isolated-worker.example', ownerId='fixture-site-owner', continuity='qa-offline-lab';
const path='/v3/continuities/'+continuity;
const gp=amount=>({cp:0,sp:0,ep:0,gp:amount,pp:0});
const player={actorId:'inventor',name:'Fixture Inventor',role:'player',rulesProfile:'legacy_2014',level:4,abilities:{INT:14}};
const baseScopes=['character','economy','inventory','resources','chronicle'].flatMap(s=>[s+'.read',s+'.write']);
async function lab(t) {
  const dir=mkdtempSync(join(tmpdir(),'astravar-cloud-')),file=join(dir,'fixture.db');
  let db=new SqliteAdapter(file);db.exec(schema);
  const env={DB:db,OWNER_SITE_USER_ID:ownerId,ASTRAVAR_CLOUD_ENABLED:'1',ASTRAVAR_CLOUD_ENV:'isolated'};
  t.after(()=>{db.close();rmSync(dir,{recursive:true,force:true});});
  const bearer=crypto.randomUUID().replaceAll('-','')+crypto.randomUUID().replaceAll('-','');
  const request=async(route,{method='GET',body,as='owner',headers={}}={})=>{
    const common={'Content-Type':'application/json',...headers};
    if(as==='owner'){common['oai-authenticated-user-id']=ownerId;if(method!=='GET')common.Origin=origin;}
    if(as==='device'){common.Authorization='Bearer '+bearer;common['X-Astravar-Credential']='paired-fixture';}
    const r=await handleWorker(new Request(origin+route,{method,headers:common,...(body===undefined?{}:{body:JSON.stringify(body)})}),env);
    const value=await r.json();return {status:r.status,value};
  };
  const post=(route,body,as='owner')=>request(route,{method:'POST',body,as});
  const rpc=async(name,args)=>{
    const answer=await post('/mcp',{jsonrpc:'2.0',id:11,method:'tools/call',params:{name,arguments:{continuityId:continuity,...args}}});
    const result=answer.value.result;
    return result?.isError ? JSON.parse(result.content[0].text) : result?.structuredContent;
  };
  const enroll=await post(path+'/isolated',{});assert.equal(enroll.status,200);
  const expiresAt=Date.now()+86400000;
  const grant=async(subject,scopes=baseScopes,auto=scopes.filter(s=>s.endsWith('.write')),actors=['*'],expectedGrantRevision=0,revoked=false)=>{
    const res=await post(path+'/grants/'+subject,{scopes,auto,actors,expiresAt,expectedGrantRevision,revoked});
    assert.equal(res.status,200,JSON.stringify(res));return res.value;
  };
  await grant('sites-mcp');
  async function connect(){
    await db.prepare("INSERT INTO bridge_credentials(id,owner_id,campaign_id,token_hash,role,scopes_json,device_name,expires_at) VALUES(?,?,?,?,'device','[]','Fixture client',?)")
      .bind('paired-fixture',ownerId,continuity,await sha256(bearer),expiresAt).run();
    await grant('device:paired-fixture');
  }
  function restart(){db.close();db=new SqliteAdapter(file);env.DB=db;}
  return {request,post,rpc,connect,grant,restart,db,env};
}
const command=(expectedRevision,operation,args)=>({version:1,epoch:1,commandId:crypto.randomUUID(),expectedRevision,operation,args});

test('MCP commits actor, exact atomic purchase, expenditure and Chronicle while no device exists; reconnect reads each once',async t=>{
  const l=await lab(t);let revision=0;
  async function commit(operation,args){
    const cmd=command(revision,operation,args);
    const ret=await l.rpc('submit_cloud_command',{command:cmd});assert.equal(ret.status,'COMMITTED',JSON.stringify(ret));revision++;
    assert.equal(ret.revision,revision);
    const proof=await l.rpc('get_cloud_receipt',{commandId:cmd.commandId});assert.deepEqual(proof,ret);return {cmd,ret};
  }
  await commit('define_actor',player);
  await commit('set_actor_ability',{actorId:'inventor',ability:'INT',score:15});
  await commit('define_wallet',{walletId:'purse',title:'Fixture purse',holderId:'inventor',shared:false,coins:gp(16),source:'test grant'});
  await commit('define_item_definition',{definitionId:'lens-type',name:'Fixture Lens',category:'tool',description:'Polished lens',rarity:null,stackable:false,wearable:false,handheld:true,attunement:'none',source:'fixture catalog'});
  const purchase=await commit('purchase_item',{itemId:'lens-1',definitionId:'lens-type',ownerId:'inventor',quantity:1,source:'resolved fixture purchase',walletId:'purse',price:gp(6),sellerId:null});
  await commit('define_resource',{actorId:'inventor',poolId:'spark',name:'Spark charge',type:'charge',maximum:3,current:3,recovery:'none',source:'fixture'});
  await commit('spend_resource',{actorId:'inventor',poolId:'spark',amount:1});
  await commit('record_chronicle_event',{eventId:'visit',title:'Workshop visit',narrative:'The visitor returned the measured drawing.',category:'conversation',status:'confirmed',worldTime:null,subjects:[{kind:'actor',id:'inventor'}],correctsId:null,source:'fixture'});
  const replay=await l.rpc('submit_cloud_command',{command:purchase.cmd});assert.deepEqual(replay,purchase.ret);
  assert.equal(l.db.db.prepare('SELECT count(*) AS n FROM bridge_credentials').get().n,0);
  l.restart();await l.connect();
  let after=null,records=[];
  do{
    const cursor=after?'&after='+after+'&atRevision='+revision+'&atEpoch=1&grantRevision=1':'';
    const r=await l.request(path+'/state?limit=3'+cursor,{as:'device'});assert.equal(r.status,200,JSON.stringify(r));
    records.push(...r.value.records);after=r.value.nextAfter;
  }while(after);
  assert.equal(records.length,new Set(records.map(r=>r.id)).size);
  assert.equal(records.find(r=>r.id==='inventor').data.abilities.INT,15);assert.equal(records.find(r=>r.id==='purse').data.coins.gp,10);
  assert.equal(records.find(r=>r.id==='spark').data.current,2);assert.equal(records.filter(r=>r.id==='lens-1').length,1);assert.equal(records.filter(r=>r.id==='visit').length,1);
  const updated=await l.post(path+'/commands',command(revision,'set_actor_ability',{actorId:'inventor',ability:'INT',score:16}),'device');
  assert.equal(updated.value.status,'COMMITTED');
  const independent=await l.rpc('get_cloud_state',{id:'inventor'});assert.equal(independent.records[0].data.abilities.INT,16);
  const oldPage=await l.request(path+'/state?limit=3&after=inventor&atRevision='+revision+'&atEpoch=1&grantRevision=1',{as:'device'});
  assert.equal(oldPage.status,409);
});

test('owner review persists across restart; refusal, stale grant, and independent authorized readback',async t=>{
  const l=await lab(t);await l.grant('sites-mcp',baseScopes,[],['*'],1);
  const cmd=command(0,'define_actor',player);
  const queued=await l.rpc('submit_cloud_command',{command:cmd});assert.equal(queued.status,'PENDING_APPROVAL',JSON.stringify(queued));
  l.restart();
  assert.equal((await l.rpc('get_cloud_state',{})).records.length,0);
  const inbox=(await l.request(path+'/approvals')).value;assert.equal(inbox.actionableCount,1);assert.equal(inbox.requests[0].plan.changes[0].before,null);
  const invalid=await l.post(path+'/approvals/'+cmd.commandId+'/verify',{digest:'0'.repeat(64)});assert.equal(invalid.status,409);
  const approved=await l.post(path+'/approvals/'+cmd.commandId+'/verify',{digest:queued.digest});assert.equal(approved.value.status,'COMMITTED');
  const repeat=await l.post(path+'/approvals/'+cmd.commandId+'/verify',{digest:queued.digest});assert.deepEqual(repeat,approved);
  const pause=command(1,'set_actor_ability',{actorId:'inventor',ability:'INT',score:17});
  const pending=await l.rpc('submit_cloud_command',{command:pause});
  const refused=await l.post(path+'/approvals/'+pause.commandId+'/refuse',{digest:pending.digest});assert.equal(refused.value.status,'REFUSED');
  const second=command(1,'set_actor_ability',{actorId:'inventor',ability:'INT',score:18});
  const next=await l.rpc('submit_cloud_command',{command:second});
  await l.grant('sites-mcp',baseScopes,[],['*'],2,true);
  const revoked=await l.post(path+'/approvals/'+second.commandId+'/verify',{digest:next.digest});assert.equal(revoked.value.status,'REFUSED');
  assert.equal((await l.request(path+'/state?id=inventor')).value.records[0].data.abilities.INT,14);
  assert.equal((await l.rpc('get_cloud_receipt',{commandId:cmd.commandId})).error,'CLOUD_GRANT_DENIED');
});

test('same-revision competing commands admit one winner, stale and changed retries never partially apply',async t=>{
  const l=await lab(t),seed=command(0,'define_actor',player);
  assert.equal((await l.rpc('submit_cloud_command',{command:seed})).status,'COMMITTED');
  assert.equal((await l.rpc('submit_cloud_command',{command:{...seed,args:{...player,level:5}}})).error,'COMMAND_ID_CONFLICT');
  const change=score=>command(1,'set_actor_ability',{actorId:'inventor',ability:'INT',score});
  const results=await Promise.all([change(13),change(15)].map(c=>l.rpc('submit_cloud_command',{command:c})));
  assert.equal(results.filter(r=>r.status==='COMMITTED').length,1);
  assert.equal((await l.rpc('get_cloud_state',{})).revision,2);
  assert.equal(l.db.db.prepare('SELECT count(*) AS n FROM cloud_receipts').get().n,2);
  await l.connect();const legacy=await l.request('/v2/campaigns/'+continuity+'/pending',{as:'device'});
  assert.deepEqual([legacy.status,legacy.value.error.code],[409,'CLOUD_AUTHORITY_REQUIRED']);
});

test('an equivalent absolute ability request is superseded with separate proof and no second mutation',async t=>{
  const l=await lab(t);
  assert.equal((await l.rpc('submit_cloud_command',{command:command(0,'define_actor',player)})).status,'COMMITTED');
  await l.grant('sites-mcp',baseScopes,[],['*'],1);
  const args={actorId:'inventor',ability:'INT',score:18};
  const p=command(1,'set_actor_ability',args), pending=await l.rpc('submit_cloud_command',{command:p});
  assert.equal(pending.status,'PENDING_APPROVAL');
  const ownerEvent=await l.post(path+'/commands',command(1,'set_actor_ability',args));assert.equal(ownerEvent.value.status,'COMMITTED');
  const proof=await l.rpc('get_cloud_receipt',{commandId:p.commandId});
  assert.equal(proof.status,'SUPERSEDED');assert.equal(proof.receipt.evidence.transactionId,ownerEvent.value.receipt.transactionId);
  assert.equal((await l.rpc('get_cloud_state',{})).revision,2);
  assert.equal((await l.request(path+'/approvals')).value.actionableCount,0);
});
