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

test('stale event receives a conflict disposition; scoped replacement binds original intent and requires a fresh owner decision',async t=>{
  const l=await lab(t);
  await l.rpc('submit_cloud_command',{command:command(0,'define_actor',player)});
  await l.grant('sites-mcp',baseScopes,[],['*'],1);
  const args={actorId:'inventor',ability:'INT',score:17},old=command(1,'set_actor_ability',args);
  const proposal=await l.rpc('submit_cloud_command',{command:old});assert.equal(proposal.status,'PENDING_APPROVAL');
  const other=await l.post(path+'/commands',command(1,'set_actor_ability',{...args,ability:'DEX'}));assert.equal(other.value.status,'COMMITTED');
  const conflict=await l.post(path+'/approvals/'+old.commandId+'/verify',{digest:proposal.digest});assert.equal(conflict.value.status,'CONFLICT');
  const replacement={...command(2,old.operation,args),replacesCommandId:old.commandId,replacesDigest:proposal.digest};
  const altered=await l.rpc('submit_cloud_command',{command:{...replacement,commandId:crypto.randomUUID(),args:{...args,score:20}}});
  assert.equal(altered.error,'MATERIAL_INTENT_CHANGED');
  const next=await l.rpc('submit_cloud_command',{command:replacement});assert.equal(next.status,'PENDING_APPROVAL');
  const duplicate=await l.rpc('submit_cloud_command',{command:{...replacement,commandId:crypto.randomUUID()}});assert.equal(duplicate.error,'TRANSACTION_PRECONDITION_FAILED');
  const outcome=await l.post(path+'/approvals/'+replacement.commandId+'/verify',{digest:next.digest});assert.equal(outcome.value.status,'COMMITTED');
  assert.equal((await l.rpc('get_cloud_receipt',{commandId:old.commandId})).status,'CONFLICT');
  assert.equal((await l.rpc('get_cloud_state',{id:'inventor'})).records[0].data.abilities.INT,17);
});

test('spellbook inspection and level previews calculate scaling without casting or changing slots',async t=>{
  const l=await lab(t);let rev=0;
  async function seed(operation,args){
    const r=await l.post(path+'/commands',command(rev++,operation,args));assert.equal(r.value.status,'COMMITTED',JSON.stringify(r));
  }
  await seed('define_actor',player);
  for(const level of [1,2]){
    await seed('define_resource',{actorId:'inventor',poolId:'slot-'+level,name:'Slot '+level,type:'spell_slot',maximum:2,current:2,recovery:'long_rest',slotLevel:level,source:'fixture'});
  }
  await seed('define_spell',{actorId:'inventor',spellId:'test-light',name:'Fixture Light',source:'fixture',baseLevel:1,concentration:false,timing:'action',ritual:false,resolution:'automatic',components:[{id:'light',name:'Light',type:'radiant',dice:1,sides:6,flat:0,critical:false,extraDicePerLevel:1}]});
  await seed('grant_spell',{actorId:'inventor',spellId:'test-light',grantId:'light-grant',source:'fixture',route:'slot',levels:[1,2],preparation:'prepared'});
  const r=await l.request(path+'/spellbook/inventor');
  assert.equal(r.status,200,JSON.stringify(r));assert.deepEqual(r.value.rows.map(row=>[row.level,row.components[0].dice,row.hit,row.dc,row.spellId]),[[1,1,null,null,'test-light'],[2,2,null,null,'test-light']]);
  assert.deepEqual(r.value.rows[1].eligibleSlotIds,['slot-2']);
  assert.equal((await l.request(path+'/state')).value.revision,rev);
  assert.deepEqual(r.value.slots.map(s=>s.current),[2,2]);
});

test('permission history is owner-only and the badge counts only proposals still eligible for approval',async t=>{
  const l=await lab(t);
  await l.connect();
  assert.equal((await l.request(path+'/access',{as:'device'})).status,403);
  assert.equal((await l.post(path+'/grants/sites-mcp',{scopes:[]},'device')).status,403);
  assert.equal((await l.request(path+'/review-status',{as:'device'})).status,403);
  await l.grant('device:paired-fixture',[...baseScopes,'approval.read'],[],['*'],1);
  await l.grant('sites-mcp',baseScopes,[],['*'],1);
  const proposal=command(0,'define_actor',player);
  const queued=await l.rpc('submit_cloud_command',{command:proposal});
  assert.equal(queued.status,'PENDING_APPROVAL');
  const badge=async()=> (await l.request(path+'/review-status',{as:'device'})).value.actionableCount;
  assert.equal(await badge(),1);
  await l.grant('sites-mcp',baseScopes,[],['*'],2,true);
  assert.equal(await badge(),0);
  const queue=(await l.request(path+'/approvals')).value;
  assert.deepEqual([queue.pendingCount,queue.actionableCount,queue.requests[0].readiness],[1,0,'SUBMITTER_GRANT_INACTIVE']);
  const terminal=await l.post(path+'/approvals/'+proposal.commandId+'/verify',{digest:queued.digest});
  assert.equal(terminal.value.status,'REFUSED');
  assert.equal((await l.request(path+'/state')).value.records.length,0);
  const admin=(await l.request(path+'/access')).value;
  assert.equal(admin.grants.find(g=>g.subject==='sites-mcp').revoked,true);
  assert.deepEqual(admin.audit.filter(e=>e.subject==='sites-mcp').map(e=>e.grant_revision).sort(),[1,2,3]);
  assert.throws(()=>l.db.db.exec('DELETE FROM cloud_grant_audit'),/IMMUTABLE_GRANT_AUDIT/);
  assert.equal((await l.request('/v3/continuities/qa-other/access',{as:'device'})).status,403);
});

test('a fresh DM pages earlier changes and links with a scoped stable cursor',async t=>{
  const l=await lab(t);
  const operations=[['define_actor',player]];
  for(const tag of ['a','b','c']){
    operations.push(['record_chronicle_event',{eventId:'discovery-'+tag,title:'Fixture discovery '+tag,narrative:'Measured marks on a fixture.',category:'discovery',status:'confirmed',worldTime:null,subjects:[{id:player.actorId,kind:'actor'}],correctsId:null,source:'engineering fixture'}]);
  }
  for(const [i,[op,args]] of operations.entries()){
    const r=await l.post(path+'/commands',command(i,op,args));
    assert.equal(r.value.status,'COMMITTED',JSON.stringify(r.value));
  }
  const page=await l.rpc('get_cloud_changes',{limit:2,sinceRevision:0});
  assert.equal(page.changes.length,2);
  const anchor={grantRevision:page.grantRevision,atEpoch:page.epoch,atRevision:page.revision};
  const tail=await l.rpc('get_cloud_changes',{limit:2,sinceRevision:0,after:page.nextAfter,...anchor});
  assert.deepEqual([...page.changes,...tail.changes].map(x=>x.revision),[4,3,2,1]);
  assert.equal(tail.nextAfter,null);
  const first=await l.rpc('get_cloud_record_links',{id:player.actorId,limit:2});
  const second=await l.rpc('get_cloud_record_links',{id:player.actorId,limit:2,after:first.nextAfter,...anchor});
  assert.deepEqual([first.links.length,second.links.length,second.nextAfter],[2,1,null]);
  await l.grant('sites-mcp',baseScopes,[],['another-actor'],1);
  assert.equal((await l.rpc('get_cloud_record_links',{id:player.actorId,limit:2,after:first.nextAfter,...anchor})).error,'SNAPSHOT_CHANGED');
  assert.equal((await l.rpc('get_cloud_changes',{actorId:player.actorId})).error,'CONTEXT_SCOPE_DENIED');
  assert.deepEqual((await l.rpc('get_cloud_changes',{})).changes,[]);
});

test('equipment review is safely superseded, whereas an independent same-price purchase receives a conflict',async t=>{
  const l=await lab(t);let revision=0;
  async function owner(op,args){
    const r=await l.post(path+'/commands',command(revision,op,args));
    assert.equal(r.value.status,'COMMITTED',JSON.stringify(r));revision++;return r.value;
  }
  await owner('define_actor',player);
  await owner('define_wallet',{walletId:'purse',title:'Fixture purse',holderId:'inventor',shared:false,coins:gp(30),source:'test grant'});
  await owner('define_item_definition',{definitionId:'clip-type',name:'Fixture Clip',category:'tool',description:'Fixture clip',rarity:null,stackable:false,wearable:true,handheld:true,attunement:'none',source:'fixture catalog'});
  const purchase=itemId=>({itemId,definitionId:'clip-type',ownerId:'inventor',quantity:1,source:'resolved fixture purchase',walletId:'purse',price:gp(5),sellerId:null});
  await owner('purchase_item',purchase('fixture-clip-one'));
  await l.grant('sites-mcp',baseScopes,[],['*'],1);
  const equip={itemId:'fixture-clip-one',equipment:'worn',source:'fixture fit confirmed'};
  const old=command(revision,'equip_item',equip);
  assert.equal((await l.rpc('submit_cloud_command',{command:old})).status,'PENDING_APPROVAL');
  const edit=await owner('equip_item',equip);
  const disposition=await l.rpc('get_cloud_receipt',{commandId:old.commandId});
  assert.equal(disposition.status,'SUPERSEDED');
  assert.equal(disposition.receipt.evidence.transactionId,edit.receipt.transactionId);
  const pending=command(revision,'purchase_item',purchase('fixture-clip-two'));
  const queued=await l.rpc('submit_cloud_command',{command:pending});
  await owner('purchase_item',purchase('fixture-clip-three'));
  const refused=await l.post(path+'/approvals/'+pending.commandId+'/verify',{digest:queued.digest});
  assert.equal(refused.value.status,'CONFLICT');
  assert.equal((await l.request(path+'/state?id=purse')).value.records[0].data.coins.gp,20);
  assert.deepEqual((await l.request(path+'/state?id=fixture-clip-two')).value.records,[]);
});

test('pool ledger preserves exact scoped balances, receipt confidentiality and a fixed page revision', async t => {
  const l = await lab(t);
  await l.connect();
  for (const [i,[name,args]] of [
    ['define_actor',player],
    ['define_resource',{actorId:player.actorId,poolId:'relay',name:'Relay',type:'charge',maximum:3,current:3,recovery:'none',source:'fixture'}],
    ['spend_resource',{actorId:player.actorId,poolId:'relay',amount:2}],
  ].entries()) assert.equal((await l.post(path+'/commands',command(i,name,args))).value.status,'COMMITTED');
  const page = await l.rpc('get_cloud_pool_history',{id:'relay',limit:1});
  assert.deepEqual(page.events.map(e=>[e.before,e.after,e.operation,e.evidenceAvailable]),[[3,1,'spend_resource',true]]);
  const independently = await l.request(path+'/pool-history/relay?limit=1',{as:'device'});
  assert.deepEqual(independently.value,page);
  assert.equal((await l.rpc('get_cloud_receipt',{commandId:page.events[0].commandId})).receipt.transactionId,page.events[0].transactionId);
  const next = {id:'relay',limit:1,after:page.nextAfter,atRevision:page.revision,atEpoch:page.epoch,grantRevision:page.grantRevision};
  const earlier = await l.rpc('get_cloud_pool_history',next);
  assert.deepEqual(earlier.events.map(e=>[e.before,e.after,e.operation]),[[null,3,'define_resource']]);
  assert.equal(earlier.nextAfter,null);
  await l.grant('sites-mcp',['resources.read'],[],[player.actorId],1);
  assert.equal((await l.rpc('get_cloud_pool_history',next)).error,'SNAPSHOT_CHANGED');
  const limited = await l.rpc('get_cloud_pool_history',{id:'relay'});
  assert.equal(limited.events.length,2);
  assert.deepEqual(limited.events.map(e=>e.after),[1,3]);
  assert.equal(Object.hasOwn(limited.events[1],'commandId'),false);
  await l.grant('sites-mcp',['resources.read'],[],['other-fixture'],2);
  assert.equal((await l.rpc('get_cloud_pool_history',{id:'relay'})).error,'POOL_UNAVAILABLE');
});
