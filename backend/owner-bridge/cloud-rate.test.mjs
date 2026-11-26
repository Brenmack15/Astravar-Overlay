import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { chargeCloudRequest, cloudRateResponse, httpRateCost, mcpRateCost } from './cloud-rate.mjs';
import { CloudService } from './cloud-service.mjs';
import { SqliteAdapter } from './sqlite-adapter.mjs';

const sql=await readFile(new URL('schema.sql',import.meta.url),'utf8');
const ownerId='rate-fixture',a='qa-first',b='qa-second';
const owner={ownerId,role:'owner',credentialId:'sites-owner'},dm={ownerId,role:'connector',credentialId:'sites-mcp'};
async function fixture(t){
  const db=new SqliteAdapter();t.after(()=>db.close());db.exec(sql);
  let clock=1_000_000;
  const service=new CloudService(db,{ownerId,isolated:true,kinds:{},operations:{},now:()=>clock});
  service.rateCapacity='16';service.rateRefillMs='100';
  for(const id of [a,b]){
    await service.createIsolated(owner,id);
    await service.setGrant(owner,id,'sites-mcp',{actors:['*'],scopes:['character.read'],auto:[],revoked:false,expiresAt:99_000_000,expectedGrantRevision:0});
  }
  return {db,service,charge:(cost,id=a,principal=dm)=>chargeCloudRequest(service,principal,id,cost),advance:ms=>{clock+=ms;}};
}

test('shared weights and integer refill are exact at the boundary, preserve debt across reads and cap idle burst',async t=>{
  const {db,charge,advance}=await fixture(t);
  for(const [method,route,tool] of [['GET','state','get_cloud_state'],['GET','receipts','get_cloud_receipt'],['GET','forms','preview_cloud_form'],
    ['GET','capabilities','get_cloud_capabilities'],['GET','records','get_cloud_record_view'],['POST','commands','submit_cloud_command'],['PUT','media','stage_cloud_image']])
    assert.equal(httpRateCost(method,route),mcpRateCost(tool));
  assert.equal(httpRateCost('GET','media'),4);
  await charge(mcpRateCost('stage_cloud_image'));
  const stored=db.db.prepare('SELECT next_ms FROM cloud_rate_buckets').get().next_ms;
  for(const [elapsed,retry] of [[0,400],[99,301],[300,1]]){
    advance(elapsed);
    await assert.rejects(charge(httpRateCost('POST','commands')),e=>{
      assert.deepEqual(cloudRateResponse(e),{code:'CLOUD_RATE_LIMITED',retryAfterMs:retry,retryAfterSeconds:1,submission:'NOT_EXECUTED'});
      return e.status===429;
    });
  }
  assert.equal(db.db.prepare('SELECT next_ms FROM cloud_rate_buckets').get().next_ms,stored);
  advance(1);await charge(4);
  advance(-10);await assert.rejects(charge(1),{retryAfterMs:110});
  advance(30_000);
  const accepted=await Promise.allSettled(Array.from({length:17},()=>charge(1)));
  assert.equal(accepted.filter(s=>s.status==='fulfilled').length,16);
  assert.equal(accepted.at(-1).reason.status,429);
});

test('a spent scope leaves other continuities and identities available and cannot allocate unauthorized traffic state',async t=>{
  const {charge,db,service}=await fixture(t);await charge(16);
  await assert.rejects(charge(1),{status:429});
  await charge(16,b);await charge(16,a,owner);
  await assert.rejects(charge(1,'qa-unknown'),{code:'CLOUD_GRANT_DENIED'});
  await assert.rejects(charge(1,a,{...dm,ownerId:'other-fixture'}),{code:'CLOUD_GRANT_DENIED'});
  await assert.rejects(charge(1,b,{ownerId,role:'device',campaignId:a,credentialId:'not-paired'}),{code:'CLOUD_GRANT_DENIED'});
  await service.setGrant(owner,a,'sites-mcp',{actors:['*'],scopes:['character.read'],auto:[],revoked:true,expiresAt:99_000_000,expectedGrantRevision:1});
  await assert.rejects(charge(1),{code:'CLOUD_GRANT_DENIED'});
  assert.equal(db.db.prepare('SELECT count(*) AS n FROM cloud_rate_buckets').get().n,3);
  const gameplay=db.db.prepare('SELECT count(*) AS n FROM cloud_requests').get().n;
  assert.equal(gameplay,0);
});

test('enrollment quotas have one owner key and invalid rate configuration fails closed',async t=>{
  const {db,service}=await fixture(t);
  const createBudget=id=>chargeCloudRequest(service,owner,id,4,true);
  for(const id of ['qa-c','qa-d','qa-e','qa-f'])await createBudget(id);
  await assert.rejects(createBudget('qa-g'),{status:429});
  await assert.rejects(createBudget('real-campaign'),{code:'ISOLATED_CONTINUITY_REQUIRED'});
  assert.deepEqual({...db.db.prepare('SELECT subject,continuity_id FROM cloud_rate_buckets').get()},{subject:'sites-owner',continuity_id:'*'});
  for(const bad of ['0','99999999','1.2','Infinity','100x']){
    service.rateCapacity=bad;await assert.rejects(createBudget('qa-h'),{code:'CLOUD_RATE_CONFIGURATION_INVALID'});
  }
  service.rateCapacity='16';service.rateRefillMs='0';
  await assert.rejects(createBudget('qa-h'),{code:'CLOUD_RATE_CONFIGURATION_INVALID'});
});

test('production defaults admit a 120-credit burst and replenish one credit every 500 ms',async t=>{
  const {service,charge,advance}=await fixture(t);
  service.isolated=false;delete service.rateCapacity;delete service.rateRefillMs;
  for(let i=0;i<30;i++)await charge(4);
  await assert.rejects(charge(1),{retryAfterMs:500});
  advance(499);await assert.rejects(charge(1),{retryAfterMs:1});
  advance(1);await charge(1);
});

test('two authenticated accounts sharing a physical database retain independent budgets for the same continuity and role',async t=>{
  const {db,charge}=await fixture(t), secondOwner='another-owner';
  const other=new CloudService(db,{ownerId:secondOwner,isolated:true,kinds:{},operations:{},now:()=>1_000_000});
  other.rateCapacity='16';other.rateRefillMs='100';
  const principal={...owner,ownerId:secondOwner};
  await other.createIsolated(principal,a);
  await charge(16,a,owner);
  await assert.rejects(charge(1,a,owner),{status:429});
  await chargeCloudRequest(other,principal,a,16);
  assert.equal(db.db.prepare('SELECT count(*) AS n FROM cloud_rate_buckets WHERE continuity_id=? AND subject=?')
    .get(a,'sites-owner').n,2);
});

test('the 120th and 121st eligible commands commit, and exhaustion refuses the 122nd ahead of all gameplay writes',async t=>{
  const {handleWorker}=await import('./worker.mjs');
  const db=new SqliteAdapter();t.after(()=>db.close());db.exec(sql);
  const origin='https://throttle.example.test',id='qa-command-capacity';
  const env={DB:db,OWNER_SITE_USER_ID:ownerId,ASTRAVAR_CLOUD_ENABLED:'1',ASTRAVAR_CLOUD_ENV:'isolated',
    ASTRAVAR_CLOUD_RATE_CAPACITY:'484',ASTRAVAR_CLOUD_RATE_REFILL_MS:'60000'};
  const post=async(path,body)=>handleWorker(new Request(origin+path,{method:'POST',headers:{
    Origin:origin,'oai-authenticated-user-id':ownerId,'Content-Type':'application/json'},body:JSON.stringify(body)}),env);
  const prefix='/v3/continuities/'+id;
  assert.equal((await post(prefix+'/isolated',{})).status,200);
  assert.equal((await post(prefix+'/grants/sites-mcp',{scopes:['character.read','character.write'],auto:['character.write'],actors:['*'],
    revoked:false,expectedGrantRevision:0,expiresAt:Date.now()+3600000})).status,200);
  for(let n=1;n<=122;n++){
    const actorId='actor-'+n,command={version:1,epoch:1,expectedRevision:n-1,commandId:'create-'+n,operation:'define_actor',
      args:{actorId,name:'Synthetic actor',role:'companion',rulesProfile:'legacy_2014',level:1,abilities:{INT:10}}};
    const response=await post('/mcp',{jsonrpc:'2.0',id:n,method:'tools/call',params:{name:'submit_cloud_command',arguments:{continuityId:id,command}}});
    const value=await response.json();
    if(n<=121){assert.equal(response.status,200);assert.equal(value.result.structuredContent?.revision,n);}
    else {assert.equal(response.status,429);assert.equal(value.error.data.submission,'NOT_EXECUTED');assert.ok(+response.headers.get('Retry-After')>0);}
  }
  for(const table of ['cloud_requests','cloud_receipts','cloud_entities'])assert.equal(db.db.prepare('SELECT count(*) AS n FROM '+table).get().n,121);
  assert.equal(db.db.prepare('SELECT revision FROM cloud_continuities').get().revision,121);
});
