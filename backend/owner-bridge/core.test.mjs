import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,mkdtempSync,rmSync,copyFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {OwnerBridge,CAMPAIGN,PROFILE,RECORD,sha256,canonical} from './core.mjs';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {handleOwnerApi} from './http.mjs';
const schema=readFileSync(new URL('./schema.sql',import.meta.url),'utf8');
const owner={ownerId:'verified-owner',role:'owner'};
const connector={ownerId:owner.ownerId,role:'connector',scopes:['character:read','proposal:create','proposal:read']};
const secret='A'.repeat(43);
async function fixture(path=':memory:') {
 const db=new SqliteAdapter(path);db.exec(schema);let time=1_800_000_000_000;
 const bridge=new OwnerBridge(db,{ownerId:owner.ownerId,now:()=>time});
 const pair=await bridge.requestPairing({campaignId:CAMPAIGN,deviceName:'Fictional Android',deviceSecretHash:await sha256(secret)});
 assert.equal((await bridge.pollPairing({deviceCode:pair.deviceCode,deviceSecret:secret})).status,'PENDING');
 await bridge.approvePairing(owner,{userCode:pair.userCode});
 const paired=await bridge.pollPairing({deviceCode:pair.deviceCode,deviceSecret:secret});
 const device=await bridge.authenticate(paired.credentialId,secret);
 const profile={id:PROFILE,name:'Fictional Test Inventor',revision:1,source:'Fictional acceptance fixture'};
 const ability={recordId:RECORD,kind:'character_ability',revision:1,fields:{profileId:PROFILE,key:'INT',score:16,magicModifier:0,notes:'Fictional connectivity fixture',source:'Fictional acceptance fixture',updatedAt:time}};
 const publication={commandId:crypto.randomUUID(),profile,ability,expectedRevision:0};
 await bridge.publishCharacter(device,CAMPAIGN,publication);
 return {db,bridge,device,paired,pair,profile,ability,publication,advance:n=>time+=n};
}
function proposal(score=20,revision=1) { return {commandId:crypto.randomUUID(),recordId:RECORD,expectedRevision:revision,fields:{score},source:'Fictional player-approved test request'}; }
async function proposed(f,score=20,revision=1){return (await f.bridge.proposeRecordUpdate(connector,CAMPAIGN,proposal(score,revision))).proposal;}
function decision(p,approved=true){return {commandId:p.commandId,approved,localAppliedRevision:approved?p.expectedRevision+1:null,localReceiptId:approved?crypto.randomUUID():null,proposalFingerprint:p.fingerprint,localFields:approved?{...p.currentFields,score:p.fields.score,source:`Player-approved owner proposal ${p.commandId} · ${p.source}`,updatedAt:1_800_000_000_000}:null};}
async function rejectsCode(action,code){await assert.rejects(action,e=>e.code===code);}

test('fictional device publication -> connector read -> proposed difference -> device decision -> independent read and receipt',async()=>{
 const f=await fixture();try{
  const before=await f.bridge.getCharacter(connector,CAMPAIGN,PROFILE);assert.equal(before.abilities[0].fields.score,16);
  const p=await proposed(f);assert.deepEqual(p.differences,[{field:'score',current:16,proposed:20}]);
  assert.equal((await f.bridge.getCharacter(connector,CAMPAIGN,PROFILE)).abilities[0].revision,1);
  const pending=await f.bridge.getPendingUpdates(f.device,CAMPAIGN);assert.equal(pending.proposals.length,1);
  const d=decision(p);const receipt=(await f.bridge.decide(f.device,CAMPAIGN,d)).receipt;
  assert.equal(receipt.status,'APPLIED');assert.equal(receipt.resultingRevision,2);
  const after=await f.bridge.getCharacter(connector,CAMPAIGN,PROFILE);assert.equal(after.abilities[0].fields.score,20);assert.equal(after.abilities[0].revision,2);
  assert.equal(after.abilities[0].fields.magicModifier,0);
  assert.deepEqual((await f.bridge.getCommandReceipt(connector,CAMPAIGN,p.commandId)).receipt,receipt);
  assert.equal((await f.bridge.getPendingUpdates(connector,CAMPAIGN)).proposals.length,0);
 }finally{f.db.close();}
});
test('rejection creates immutable receipt without mutation and cannot later become approval',async()=>{
 const f=await fixture();try{
  const p=await proposed(f),d=decision(p,false),r=await f.bridge.decide(f.device,CAMPAIGN,d);
  assert.equal(r.receipt.status,'REJECTED');assert.equal(r.receipt.resultingRevision,1);
  assert.equal((await f.bridge.getCharacter(connector,CAMPAIGN)).abilities[0].fields.score,16);
  assert.deepEqual(await f.bridge.decide(f.device,CAMPAIGN,d),r);
  await rejectsCode(()=>f.bridge.decide(f.device,CAMPAIGN,decision(p)), 'COMMAND_ID_REUSED');
  assert.throws(()=>f.db.db.exec('DELETE FROM bridge_receipts'),/IMMUTABLE_RECEIPT/);
  assert.throws(()=>f.db.db.exec("UPDATE bridge_receipts SET status='APPLIED'"),/IMMUTABLE_RECEIPT/);
 }finally{f.db.close();}
});
test('network-response loss retry and concurrent duplicate approval commit exactly once',async()=>{
 const f=await fixture();try{
  const p=await proposed(f),d=decision(p);
  const results=await Promise.all([f.bridge.decide(f.device,CAMPAIGN,d),f.bridge.decide(f.device,CAMPAIGN,d)]);
  assert.deepEqual(results[0],results[1]);
  assert.equal((await f.bridge.getCharacter(connector,CAMPAIGN)).abilities[0].revision,2);
  assert.equal(f.db.db.prepare('SELECT COUNT(*) AS n FROM bridge_receipts WHERE command_id=?').get(p.commandId).n,1);
  assert.deepEqual(await f.bridge.decide(f.device,CAMPAIGN,d),results[0]);
 }finally{f.db.close();}
});
test('stale proposal and simultaneous edits produce conflict without overwriting the newer revision',async()=>{
 const f=await fixture();try{
  const p=await proposed(f),q=await proposed(f,18);
  await f.bridge.decide(f.device,CAMPAIGN,decision(p));
  const conflict=(await f.bridge.decide(f.device,CAMPAIGN,decision(q))).receipt;
  assert.equal(conflict.status,'CONFLICT');assert.equal(conflict.resultingRevision,2);
  assert.equal((await f.bridge.getCharacter(connector,CAMPAIGN)).abilities[0].fields.score,20);
  await rejectsCode(()=>f.bridge.proposeRecordUpdate(connector,CAMPAIGN,proposal(19)), 'REVISION_CONFLICT');
 }finally{f.db.close();}
});
test('device cannot propose, connector cannot approve/publish/pair, foreign owner and missing scopes denied',async()=>{
 const f=await fixture();try{
  const p=await proposed(f);
  await rejectsCode(()=>f.bridge.decide(connector,CAMPAIGN,decision(p)),'ROLE_DENIED');
  await rejectsCode(()=>f.bridge.publishCharacter(connector,CAMPAIGN,f.publication),'ROLE_DENIED');
  await rejectsCode(()=>f.bridge.approvePairing(connector,{userCode:f.pair.userCode}),'ROLE_DENIED');
  await rejectsCode(()=>f.bridge.proposeRecordUpdate(f.device,CAMPAIGN,proposal()),'ROLE_DENIED');
  await rejectsCode(()=>f.bridge.getCharacter({...connector,ownerId:'other-owner'},CAMPAIGN),'OWNER_SCOPE_DENIED');
  await rejectsCode(()=>f.bridge.getCharacter({...connector,scopes:[]},CAMPAIGN),'SCOPE_DENIED');
  await rejectsCode(()=>f.bridge.getCharacter(connector,'real-zerg-campaign'),'FICTIONAL_SCOPE_ONLY');
 }finally{f.db.close();}
});
test('unknown HP is absent, update whitelist blocks other mechanics, real profile and malformed fields',async()=>{
 const f=await fixture();try{
  const c=await f.bridge.getCharacter(connector,CAMPAIGN);assert.equal('currentHp' in c.profile,false);
  for(const fields of [{currentHp:66},{score:31},{score:20,magicModifier:3},{score:20,spellSlots:9}])
   await assert.rejects(()=>f.bridge.proposeRecordUpdate(connector,CAMPAIGN,{...proposal(),fields}));
  await rejectsCode(()=>f.bridge.publishCharacter(f.device,CAMPAIGN,{...f.publication,commandId:crypto.randomUUID(),profile:{...f.profile,id:'local-player'}}),'FICTIONAL_FIXTURE_ONLY');
  await rejectsCode(()=>f.bridge.proposeRecordUpdate(connector,CAMPAIGN,{...proposal(),confirmed:true}),'INVALID_REQUEST');
 }finally{f.db.close();}
});
test('idempotency binds full proposal and decisions to canonical campaign-aware bytes',async()=>{
 const f=await fixture();try{
  const body=proposal(),p=(await f.bridge.proposeRecordUpdate(connector,CAMPAIGN,body)).proposal;
  assert.equal(p.fingerprint,await sha256(canonical({campaignId:CAMPAIGN,...body})));
  assert.deepEqual((await f.bridge.proposeRecordUpdate(connector,CAMPAIGN,body)).proposal,p);
  await rejectsCode(()=>f.bridge.proposeRecordUpdate(connector,CAMPAIGN,{...body,fields:{score:19}}),'COMMAND_ID_REUSED');
  await rejectsCode(()=>f.bridge.decide(f.device,CAMPAIGN,{...decision(p),proposalFingerprint:'0'.repeat(64)}),'PROPOSAL_MISMATCH');
 }finally{f.db.close();}
});
test('invalid credentials, expiry and server revocation fail closed while no raw secret stored',async()=>{
 const f=await fixture();try{
  await rejectsCode(()=>f.bridge.authenticate(f.paired.credentialId,'B'.repeat(43)),'CREDENTIAL_REVOKED_OR_EXPIRED');
  const row=f.db.db.prepare('SELECT * FROM bridge_credentials').get();assert.equal(row.token_hash,await sha256(secret));assert.equal(JSON.stringify(row).includes(secret),false);
  await f.bridge.revokeDevice(f.device,CAMPAIGN,f.paired.credentialId);
  await rejectsCode(()=>f.bridge.authenticate(f.paired.credentialId,secret),'CREDENTIAL_REVOKED_OR_EXPIRED');
  f.advance(31*24*60*60_000);
  await rejectsCode(()=>f.bridge.pollPairing({deviceCode:f.pair.deviceCode,deviceSecret:secret}),'PAIRING_EXPIRED_OR_UNKNOWN');
 }finally{f.db.close();}
});
test('pairing requires separate verified owner approval and wrong device proof cannot poll',async()=>{
 const db=new SqliteAdapter();db.exec(schema);const b=new OwnerBridge(db,{ownerId:owner.ownerId});try{
  const p=await b.requestPairing({campaignId:CAMPAIGN,deviceName:'Fictional phone',deviceSecretHash:await sha256(secret)});
  await rejectsCode(()=>b.approvePairing({ownerId:'foreign',role:'owner'},{userCode:p.userCode}),'OWNER_SCOPE_DENIED');
  await rejectsCode(()=>b.pollPairing({deviceCode:p.deviceCode,deviceSecret:'B'.repeat(43)}),'PAIRING_EXPIRED_OR_UNKNOWN');
  assert.equal((await b.pollPairing({deviceCode:p.deviceCode,deviceSecret:secret})).status,'PENDING');
 }finally{db.close();}
});
test('service restart and SQLite backup/restore retain confirmed record and receipts',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'astravar-owner-test-')),path=join(dir,'bridge.db'),backup=join(dir,'backup.db');
 let f;
 try {
  f=await fixture(path);const p=await proposed(f);const r=(await f.bridge.decide(f.device,CAMPAIGN,decision(p))).receipt;
  f.db.db.exec('PRAGMA wal_checkpoint(TRUNCATE)');f.db.close();copyFileSync(path,backup);
  for(const file of [path,backup]){
   const db=new SqliteAdapter(file);const b=new OwnerBridge(db,{ownerId:owner.ownerId});
   const result=await b.getCharacter(connector,CAMPAIGN);assert.equal(result.abilities[0].fields.score,20);assert.equal(result.abilities[0].revision,2);
   assert.deepEqual((await b.getCommandReceipt(connector,CAMPAIGN,p.commandId)).receipt,r);
   assert.equal(db.db.prepare('PRAGMA integrity_check').get().integrity_check,'ok');db.close();
  }
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('HTTP adapter enforces authentication, bounds, same-origin, and proper device self-revocation',async()=>{
 const f=await fixture();try{
  const base='https://private.example/v1/campaigns/'+CAMPAIGN;
  let response=await handleOwnerApi(new Request(base+'/characters/'+PROFILE),f.bridge);assert.equal(response.status,401);
  const headers={Authorization:'Bearer '+secret,'X-Astravar-Credential':f.paired.credentialId};
  response=await handleOwnerApi(new Request(base+'/characters/'+PROFILE,{headers}),f.bridge);assert.equal(response.status,200);
  response=await handleOwnerApi(new Request(base+'/characters/'+PROFILE,{headers:{...headers,Origin:'https://evil.example'}}),f.bridge);assert.equal(response.status,403);
  response=await handleOwnerApi(new Request(base+'/devices/revoke',{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:'{}'}),f.bridge);assert.equal(response.status,200);assert.equal((await response.json()).status,'REVOKED');
  response=await handleOwnerApi(new Request(base+'/devices/revoke',{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:'{}'}),f.bridge);assert.equal(response.status,200);assert.equal((await response.json()).status,'REVOKED');
  const inactive=await f.bridge.authenticate(f.paired.credentialId,secret,{forSelfRevocation:true});
  await rejectsCode(()=>f.bridge.getPendingUpdates(inactive,CAMPAIGN),'CREDENTIAL_REVOKED_OR_EXPIRED');
  response=await handleOwnerApi(new Request(base+'/characters/'+PROFILE,{headers}),f.bridge);assert.equal(response.status,401);
 }finally{f.db.close();}
});
test('full saved provenance is mirrored and unapproved magical modifier or notes changes are rejected',async()=>{
 const f=await fixture();try{
  const p=await proposed(f),d=decision(p);
  for(const localFields of [{...d.localFields,magicModifier:4},{...d.localFields,notes:'changed'},{...d.localFields,source:p.source},{...d.localFields,score:18}])
   await rejectsCode(()=>f.bridge.decide(f.device,CAMPAIGN,{...d,localFields}),'LOCAL_FIELDS_MISMATCH');
  const r=(await f.bridge.decide(f.device,CAMPAIGN,d)).receipt;
  assert.equal(r.localRecordFingerprint,await sha256(canonical(d.localFields)));
  assert.deepEqual((await f.bridge.getCharacter(connector,CAMPAIGN)).abilities[0].fields,d.localFields);
 }finally{f.db.close();}
});
test('explicit fictional local publish uses CAS, preserves fields, and retries without another revision',async()=>{
 const f=await fixture();try{
  const body={...f.publication,commandId:crypto.randomUUID(),expectedRevision:1,ability:{...f.ability,revision:2,fields:{...f.ability.fields,score:17,source:'Explicit local player edit',updatedAt:1_800_000_000_002}}};
  const first=await f.bridge.publishCharacter(f.device,CAMPAIGN,body);
  assert.equal(first.receipt.resultingRevision,2);assert.deepEqual(await f.bridge.publishCharacter(f.device,CAMPAIGN,body),first);
  assert.deepEqual((await f.bridge.getCharacter(connector,CAMPAIGN)).abilities[0],body.ability);
  await rejectsCode(()=>f.bridge.publishCharacter(f.device,CAMPAIGN,{...body,commandId:crypto.randomUUID()}),'REVISION_CONFLICT');
 }finally{f.db.close();}
});
test('two independent SQLite connections cannot collide command namespaces or overwrite stale edits',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'astravar-race-')),path=join(dir,'bridge.db');let first,second;
 try{
  first=await fixture(path);const db2=new SqliteAdapter(path);second={db:db2,bridge:new OwnerBridge(db2,{ownerId:owner.ownerId,now:()=>1_800_000_000_000})};
  const id=crypto.randomUUID(),local={...first.publication,commandId:id,expectedRevision:1,ability:{...first.ability,revision:2,fields:{...first.ability.fields,score:17}}};
  const results=await Promise.allSettled([
   first.bridge.publishCharacter(first.device,CAMPAIGN,local),
   second.bridge.proposeRecordUpdate(connector,CAMPAIGN,{...proposal(),commandId:id})
  ]);
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  const row=first.db.db.prepare('SELECT (SELECT COUNT(*) FROM bridge_proposals WHERE command_id=?) + (SELECT COUNT(*) FROM bridge_receipts WHERE command_id=?) AS n').get(id,id);assert.equal(row.n,1);
  if(results[0].status==='fulfilled'){
   await rejectsCode(()=>second.bridge.publishCharacter(first.device,CAMPAIGN,{...local,commandId:crypto.randomUUID()}),'REVISION_CONFLICT');
  }else{
   const p=(await first.bridge.getPendingUpdates(first.device,CAMPAIGN)).proposals[0];
   const q=(await second.bridge.proposeRecordUpdate(connector,CAMPAIGN,proposal(18))).proposal;
   const d=decision(p),e=decision(q);
   const attempts=await Promise.allSettled([first.bridge.decide(first.device,CAMPAIGN,d),second.bridge.decide(first.device,CAMPAIGN,e)]);
   assert.equal((await first.bridge.getCharacter(connector,CAMPAIGN)).abilities[0].revision,2);
   assert.equal(attempts.filter(r=>r.status==='fulfilled'&&r.value.receipt.status==='APPLIED').length,1);
  }
 }finally{first?.db.close();second?.db.close();rmSync(dir,{recursive:true,force:true});}
});
test('pending proposal and pairing quotas prevent hidden requests and unlimited outstanding enrollments',async()=>{
 const f=await fixture();try{
  for(let i=0;i<50;i++) await f.bridge.proposeRecordUpdate(connector,CAMPAIGN,proposal());
  assert.equal((await f.bridge.getPendingUpdates(f.device,CAMPAIGN)).proposals.length,50);
  await rejectsCode(()=>f.bridge.proposeRecordUpdate(connector,CAMPAIGN,proposal()),'PENDING_LIMIT');
  for(let i=1;i<20;i++) await f.bridge.requestPairing({campaignId:CAMPAIGN,deviceName:'Fictional pending '+i,deviceSecretHash:await sha256('fixture-secret-'+i)});
  await rejectsCode(async()=>f.bridge.requestPairing({campaignId:CAMPAIGN,deviceName:'Beyond quota',deviceSecretHash:await sha256('extra')}),'PAIRING_LIMIT');
  f.advance(10*60_000+1);
  assert.ok((await f.bridge.requestPairing({campaignId:CAMPAIGN,deviceName:'Expired cleanup',deviceSecretHash:await sha256('new')})).userCode);
 }finally{f.db.close();}
});
test('proposal provenance boundary leaves room for the complete native approval source',async()=>{
 const f=await fixture();try{
  const body={...proposal(),source:'S'.repeat(1900)};
  const p=(await f.bridge.proposeRecordUpdate(connector,CAMPAIGN,body)).proposal;
  const d=decision(p);assert.ok(d.localFields.source.length<=2000);
  await f.bridge.decide(f.device,CAMPAIGN,d);
  assert.equal((await f.bridge.getCharacter(connector,CAMPAIGN)).abilities[0].fields.source,d.localFields.source);
  await rejectsCode(()=>f.bridge.proposeRecordUpdate(connector,CAMPAIGN,{...proposal(18,2),source:'S'.repeat(1901)}),'INVALID_TEXT');
 }finally{f.db.close();}
});
