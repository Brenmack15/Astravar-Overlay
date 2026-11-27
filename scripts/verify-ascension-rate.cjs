/* Both authenticated transports through the actual bundle, private local D1 and R2, and a cold Worker restart. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {createHash,randomBytes} = require('node:crypto');
const {setTimeout:delay} = require('node:timers/promises');
const provider = require('../tools/ascension_provider.cjs');
const mod = process.argv[2] ? path.resolve(process.argv[2]) : undefined;
const origin='https://isolated.invalid',owner='traffic-fixture',a='qa-traffic-a',b='qa-traffic-b';
const rate={capacity:16,refillMs:500};
const tokens=Object.fromEntries(['paired','parallel'].map(k=>[k,randomBytes(32).toString('hex')]));
const command={version:1,commandId:'one-event',epoch:1,expectedRevision:0,operation:'define_actor',
  args:{actorId:'synthetic',name:'Traffic fixture',role:'player',rulesProfile:'legacy_2014',level:1,abilities:{INT:14}}};
function url(id,path){return '/v3/continuities/'+id+'/'+path;}
async function wire(r,route,method='GET',body,as='owner',type='application/json'){
  const identity=as==='owner'?{'oai-authenticated-user-id':owner}:{'X-Astravar-Credential':as,Authorization:'Bearer '+tokens[as]};
  const response=await r.dispatch(origin+route,{method,headers:{...identity,Origin:origin,'Content-Type':type},
    ...(body===undefined?{}:{body:typeof body==='string'?body:JSON.stringify(body)})});
  return {status:response.status,retry:response.headers.get('Retry-After'),value:await response.json()};
}
const rpc=(r,id,name,args={})=>wire(r,'/mcp','POST',{jsonrpc:'2.0',id:7,method:'tools/call',params:{name,arguments:{continuityId:id,...args}}});
function limited(reply,mcp=false){
  assert.equal(reply.status,429,JSON.stringify(reply.value));
  const data=mcp?reply.value.error.data:reply.value.error;
  if(mcp)assert.equal(reply.value.error.code,-32029);
  assert.equal(data.code,'CLOUD_RATE_LIMITED');assert.equal(data.submission,'NOT_EXECUTED');
  assert.ok(Number.isSafeInteger(data.retryAfterMs) && data.retryAfterMs>0);
  assert.equal(Number(reply.retry),Math.ceil(data.retryAfterMs/1000));
  assert.equal(data.retryAfterSeconds,Number(reply.retry));
  return data.retryAfterMs;
}
async function full(r,id,subject){
  // Seed a full debt to make the limited paths deterministic without changing the Worker clock.
  await r.db.prepare('INSERT INTO cloud_rate_buckets VALUES(?,?,?,?) ON CONFLICT(owner_id,continuity_id,subject) DO UPDATE SET next_ms=excluded.next_ms')
    .bind(owner,id,subject,Date.now()+rate.capacity*rate.refillMs).run();
}
async function proof(){
  const previous=process.cwd(),tmp=fs.mkdtempSync(path.join(os.tmpdir(),'ascension-rate-'));process.chdir(tmp);
  const store=path.join(tmp,'generation');
  const grant={scopes:['character.read','character.write','media.read','media.write'],auto:['character.write'],actors:['*'],
    revoked:false,expiresAt:Date.now()+86_400_000,expectedGrantRevision:0};
  let previousReceipt,storedDebt,givenRetry,acceptedBurst,deniedBurst;
  try{
    await provider.create(store,owner,a,mod);
    await provider.withGameplay(store,async r=>{
      for(const id of [a,b]){
        assert.equal((await wire(r,url(id,'isolated'),'POST',{})).status,200);
        assert.equal((await wire(r,url(id,'grants/sites-mcp'),'POST',grant)).status,200);
      }
      for(const [credential,token] of Object.entries(tokens)){
        await r.db.prepare("INSERT INTO bridge_credentials(id,owner_id,campaign_id,token_hash,role,scopes_json,device_name,expires_at) VALUES(?,?,?,?,'device','[]','Traffic fixture',?)")
          .bind(credential,owner,a,createHash('sha256').update(token).digest('hex'),grant.expiresAt).run();
        assert.equal((await wire(r,url(a,'grants/device:'+credential),'POST',grant)).status,200);
      }
      await full(r,a,'sites-mcp');
      // The cloud method and continuity are identifiable from the bounded RPC envelope.
      // Full image argument validation and decode occur after admission.
      limited(await rpc(r,a,'stage_cloud_image',{bytesBase64:'invalid'}),true);
      givenRetry=limited(await rpc(r,a,'submit_cloud_command',{command}),true);
      assert.equal((await r.db.prepare('SELECT count(*) AS n FROM cloud_requests').first()).n,0);
      assert.equal((await rpc(r,b,'get_cloud_state')).value.result.structuredContent.records.length,0);
      assert.equal((await wire(r,url(a,'state'),'GET',undefined,'paired')).status,200);
      assert.equal((await wire(r,url(b,'state'),'GET',undefined,'paired')).status,403);
      await full(r,a,'device:paired');
      limited(await wire(r,url(a,'media/uploads/not-staged'),'PUT','unread bytes','paired','image/png'));
      assert.equal((await r.bucket.list()).objects.length,0);
      assert.equal((await r.db.prepare('SELECT count(*) AS n FROM cloud_media_uploads').first()).n,0);
      await delay(givenRetry+60);
      const committed=await rpc(r,a,'submit_cloud_command',{command});
      assert.equal(committed.status,200,JSON.stringify(committed.value));
      previousReceipt=committed.value.result.structuredContent;
      assert.equal(previousReceipt.status,'COMMITTED');
      assert.equal(previousReceipt.revision,1);
      await full(r,a,'sites-mcp');
      storedDebt=(await r.db.prepare("SELECT next_ms FROM cloud_rate_buckets WHERE owner_id=? AND continuity_id=? AND subject='sites-mcp'").bind(owner,a).first()).next_ms;
    },mod,rate);
    await provider.withGameplay(store,async r=>{
      // Cooldown survives reopening the runtime. Exact resubmission recovers the one receipt.
      assert.equal((await r.db.prepare("SELECT next_ms FROM cloud_rate_buckets WHERE owner_id=? AND continuity_id=? AND subject='sites-mcp'").bind(owner,a).first()).next_ms,storedDebt);
      let retry=await rpc(r,a,'submit_cloud_command',{command});
      if(retry.status===429){
        await delay(limited(retry,true)+60);
        retry=await rpc(r,a,'submit_cloud_command',{command});
      }
      assert.equal(retry.status,200,JSON.stringify(retry.value));
      assert.deepEqual(retry.value.result?.structuredContent,previousReceipt);
      const read=await wire(r,url(a,'receipts/one-event'));
      assert.equal(read.value.receipt.transactionId,previousReceipt.receipt.transactionId);
      assert.equal((await r.db.prepare('SELECT count(*) AS n FROM cloud_receipts').first()).n,1);
      const start=Date.now();
      const batch=await Promise.all(Array.from({length:32},()=>wire(r,url(a,'state'),'GET',undefined,'parallel')));
      const accepted=batch.filter(x=>x.status===200).length;
      assert.ok(accepted>=rate.capacity && accepted<=rate.capacity+Math.floor((Date.now()-start)/rate.refillMs));
      const denied=batch.filter(x=>x.status===429);assert.ok(denied.length>0);denied.forEach(x=>limited(x));
      assert.equal(accepted+denied.length,batch.length);
      acceptedBurst=accepted;deniedBurst=denied.length;
    },mod,rate);
    await provider.withGameplay(store,async r=>{
      // A large first sync uses the Android client's real page size and frozen cursor contract.
      await r.db.prepare(`WITH RECURSIVE fixture(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM fixture WHERE n<2461)
        INSERT INTO cloud_entities(owner_id,continuity_id,id,kind,actor_id,title,data_json,revision)
        SELECT ?,?,printf('page-%04d',n),'actor',printf('page-%04d',n),'Paging fixture','{"role":"companion"}',1 FROM fixture`).bind(owner,a).run();
      const seen=new Set();let next=null,pages=0,waits=0;
      do{
        const params=pages?'&after='+next+'&atEpoch=1&atRevision=1&grantRevision=1':'';
        const route=url(a,'state?limit=20'+params);
        if(pages===115){
          // The same authenticated installation also checks this continuity from another view.
          for(let i=0;i<130;i++)if((await wire(r,url(a,'state?limit=1'),'GET',undefined,'parallel')).status===429)break;
        }
        let part=await wire(r,route,'GET',undefined,'parallel');
        for(let attempt=0;part.status===429 && attempt<4;attempt++){
          await delay(limited(part)+30);waits++;
          part=await wire(r,route,'GET',undefined,'parallel');
        }
        assert.equal(part.status,200,JSON.stringify(part.value));
        assert.deepEqual([part.value.revision,part.value.epoch,part.value.grantRevision],[1,1,1]);
        for(const row of part.value.records){assert.ok(!seen.has(row.id));seen.add(row.id);}
        next=part.value.nextAfter;pages++;
      }while(next);
      assert.equal(seen.size,2462);assert.ok(pages>120 && waits>0);
      console.log(JSON.stringify({status:'VERIFIED',runtime:'workerd',http429:true,mcp429:true,independentPrincipals:true,
        independentContinuities:true,mediaObjectWrites:0,boundedParallelAccepted:acceptedBurst,throttledParallel:deniedBurst,
        recoveredReceipts:1,productionBudgetPages:pages,productionBudgetWaits:waits,uniquePagedRecords:seen.size,production:false}));
    },mod,{capacity:120,refillMs:500});
  }finally{process.chdir(previous);fs.rmSync(tmp,{force:true,recursive:true});}
}
proof().catch(error=>{console.error(error.stack);process.exitCode=1;});
