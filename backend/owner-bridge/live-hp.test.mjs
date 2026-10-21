import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile,mkdtemp,rm,copyFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {OwnerBridge,sha256,canonical,MODE} from './core.mjs';
import {LiveHpBridge,HP_MODE,HP_FRESHNESS_MS} from './live-hp.mjs';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {handleWorker} from './worker.mjs';
const ownerId='hp-fixture-owner',campaign='hp-integration-fixture',profileId='hp-player-fixture';
const connector={ownerId,role:'connector',scopes:['hp:read','hp:propose','hp:receipt']};
const owner={ownerId,role:'owner',scopes:[]};
const uuid=()=>crypto.randomUUID();
const baseline=()=>({name:'Synthetic HP Tester',currentHp:24,maximumHp:40,temporaryHp:2,source:'Fictional HP acceptance',updatedAt:1_800_000_000_000});
async function setup(t,path=':memory:'){
 const db=new SqliteAdapter(path);db.exec(await readFile(new URL('./schema.sql',import.meta.url),'utf8'));let time=1_800_000_000_000;
 const now=()=>time,bridge=new OwnerBridge(db,{ownerId,now}),hp=new LiveHpBridge(db,{ownerId,now});
 async function enroll(profile=profileId,camp=campaign){const secret='x'+uuid().replaceAll('-','')+'q'.repeat(20);const pairing=await bridge.requestPairing({campaignId:camp,profileId:profile,deviceName:'Fictional test device',deviceSecretHash:await sha256(secret)});assert.equal((await bridge.pollPairing({deviceCode:pairing.deviceCode,deviceSecret:secret})).status,'PENDING');const summary=await bridge.pairingSummary(owner,{userCode:pairing.userCode});assert.equal(summary.profileId,profile);assert.deepEqual(summary.scopes,['hp:read','hp:publish','hp:decide']);await bridge.approvePairing(owner,{userCode:pairing.userCode});const token=await bridge.pollPairing({deviceCode:pairing.deviceCode,deviceSecret:secret});assert.equal(token.mode,MODE);assert.equal(token.profileId,profile);return{principal:await bridge.authenticate(token.credentialId,secret),secret,token};}
 const enrolled=await enroll();t.after(()=>db.close());
 const publication=(f=baseline(),revision=7,expectedRevision=0)=>({commandId:uuid(),expectedRevision,profileId,revision,fields:f});
 const proposal=(revision=7,current=24,adjustment=-3)=>({commandId:uuid(),profileId,expectedRevision:revision,expectedCurrentHp:current,adjustment,source:'Fictional authorized HP adjustment'});
 return{db,bridge,hp,now,setTime:v=>time=v,enroll,...enrolled,publication,proposal};
}
function approved(p,updatedAt){return{commandId:p.commandId,approved:true,localAppliedRevision:p.expectedRevision+1,localReceiptId:uuid(),proposalFingerprint:p.fingerprint,localFields:{...p.currentFields,currentHp:p.fields.currentHp,source:`Player-approved HP connection test ${p.commandId} · ${p.source}`,updatedAt}};}
function rejected(p){return{commandId:p.commandId,approved:false,localAppliedRevision:null,localReceiptId:null,proposalFingerprint:p.fingerprint,localFields:null};}
async function error(p,code){await assert.rejects(p,e=>e.code===code);}

test('narrow HP publication -> MCP-capable read -> proposal only -> explicit saved approval -> independent receipt and readback',async t=>{
 const f=await setup(t),publish=f.publication();const published=await f.hp.publishCharacter(f.principal,campaign,publish);assert.equal(published.mode,HP_MODE);assert.equal(published.receipt.resultingRevision,7);
 const first=await f.hp.getCharacterHp(connector,campaign,profileId);assert.deepEqual(first.fields,publish.fields);assert.equal(first.stale,false);assert.equal(first.lastDeviceSyncAt,f.now());
 const {proposal:p}=await f.hp.proposeHpUpdate(connector,campaign,f.proposal());assert.deepEqual(p.differences,[{field:'currentHp',current:24,proposed:21}]);assert.equal(p.fingerprint,await sha256(canonical({campaignId:campaign,commandId:p.commandId,recordId:profileId,expectedRevision:7,fields:{currentHp:21},source:p.source,adjustment:-3})));
 assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).fields.currentHp,24);await error(f.hp.getCommandReceipt(connector,campaign,p.commandId),'RECEIPT_NOT_READY');
 assert.equal((await f.hp.getPendingUpdates(f.principal,campaign)).proposals.length,1);const body=approved(p,f.now()+1),result=await f.hp.decide(f.principal,campaign,body);assert.equal(result.receipt.status,'APPLIED');assert.equal(result.receipt.resultingRevision,8);
 assert.deepEqual((await f.hp.getCommandReceipt(connector,campaign,p.commandId)).receipt,result.receipt);const after=await f.hp.getCharacterHp(connector,campaign,profileId);assert.deepEqual(after.fields,body.localFields);assert.equal(after.revision,8);assert.deepEqual((await f.hp.getPendingUpdates(connector,campaign)).proposals,[]);
 assert.deepEqual(Object.keys(after.fields).sort(),['currentHp','maximumHp','name','source','temporaryHp','updatedAt'].sort());
});

test('rejection, duplicate retry, altered retry and immutable receipts never apply a second revision',async t=>{
 const f=await setup(t);await f.hp.publishCharacter(f.principal,campaign,f.publication());const request=f.proposal(),{proposal:p}=await f.hp.proposeHpUpdate(connector,campaign,request);const body=rejected(p),first=await f.hp.decide(f.principal,campaign,body);
 assert.equal(first.receipt.status,'REJECTED');assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).revision,7);assert.deepEqual(await f.hp.decide(f.principal,campaign,body),first);
 await error(f.hp.decide(f.principal,campaign,approved(p,f.now())),'COMMAND_ID_REUSED');await error(f.hp.proposeHpUpdate(connector,campaign,{...request,adjustment:-4}),'COMMAND_ID_REUSED');
 assert.throws(()=>f.db.exec("UPDATE bridge_hp_receipts SET status='APPLIED'"),/IMMUTABLE_RECEIPT/);assert.throws(()=>f.db.exec('DELETE FROM bridge_hp_proposals'),/IMMUTABLE_PROPOSAL/);
});

test('same-snapshot publication changes no revision and a replay cannot pretend old phone data is current',async t=>{
 const f=await setup(t);const request=f.publication();const receipt=await f.hp.publishCharacter(f.principal,campaign,request);f.setTime(f.now()+HP_FRESHNESS_MS+1);
 assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).stale,true);await error(f.hp.proposeHpUpdate(connector,campaign,f.proposal()),'DEVICE_SYNC_STALE');
 assert.deepEqual(await f.hp.publishCharacter(f.principal,campaign,request),receipt);assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).stale,true);
 await f.hp.publishCharacter(f.principal,campaign,f.publication(request.fields,7,7));const fresh=await f.hp.getCharacterHp(connector,campaign,profileId);assert.equal(fresh.stale,false);assert.equal(fresh.revision,7);
 await error(f.hp.publishCharacter(f.principal,campaign,f.publication({...request.fields,currentHp:23},7,7)),'REVISION_CONFLICT');
});

test('stale revisions, simultaneous local edits and duplicate device decisions preserve the newer saved record',async t=>{
 const f=await setup(t);await f.hp.publishCharacter(f.principal,campaign,f.publication());const {proposal:p}=await f.hp.proposeHpUpdate(connector,campaign,f.proposal());
 await f.hp.publishCharacter(f.principal,campaign,f.publication({...baseline(),currentHp:25,source:'Fictional local player correction'},10,7));
 const conflict=await f.hp.decide(f.principal,campaign,approved(p,f.now()));assert.equal(conflict.receipt.status,'CONFLICT');assert.equal(conflict.receipt.resultingRevision,10);assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).fields.currentHp,25);
 await error(f.hp.proposeHpUpdate(connector,campaign,f.proposal()),'REVISION_CONFLICT');
 const {proposal:p2}=await f.hp.proposeHpUpdate(connector,campaign,f.proposal(10,25,-2)),decision=approved(p2,f.now());const results=await Promise.all([f.hp.decide(f.principal,campaign,decision),f.hp.decide(f.principal,campaign,decision)]);assert.deepEqual(results[0],results[1]);assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).revision,11);
});

test('unknown HP, unapproved fields, over-range deltas and forged local snapshots fail closed',async t=>{
 const f=await setup(t);await f.hp.publishCharacter(f.principal,campaign,f.publication({...baseline(),currentHp:null}));await error(f.hp.proposeHpUpdate(connector,campaign,f.proposal()),'HP_UNKNOWN');
 await f.hp.publishCharacter(f.principal,campaign,f.publication(baseline(),8,7));for(const adjustment of [-25,17])await error(f.hp.proposeHpUpdate(connector,campaign,f.proposal(8,24,adjustment)),'INVALID_HP_RANGE');
 await error(f.hp.proposeHpUpdate(connector,campaign,{...f.proposal(8),adjustment:0}),'NO_FIELD_CHANGE');await error(f.hp.proposeHpUpdate(connector,campaign,{...f.proposal(8),fields:{maximumHp:50}}),'INVALID_REQUEST');
 const {proposal:p}=await f.hp.proposeHpUpdate(connector,campaign,f.proposal(8));const body=approved(p,f.now());for(const patch of [{maximumHp:41},{temporaryHp:0},{name:'Altered'},{source:'Unbound source'},{currentHp:22}])await error(f.hp.decide(f.principal,campaign,{...body,localFields:{...body.localFields,...patch}}),'LOCAL_FIELDS_MISMATCH');
 assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).revision,8);
});

test('pairing binds exact existing profile; replacement writer revokes old device and preserves records',async t=>{
 const f=await setup(t);await f.hp.publishCharacter(f.principal,campaign,f.publication());const {proposal:p}=await f.hp.proposeHpUpdate(connector,campaign,f.proposal());
 const second=await f.enroll();await error(f.bridge.authenticate(f.token.credentialId,f.secret),'CREDENTIAL_REVOKED_OR_EXPIRED');await error(f.hp.decide(f.principal,campaign,rejected(p)),'HP_CONNECTION_NOT_AUTHORIZED');
 assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).stale,true);await f.hp.publishCharacter(second.principal,campaign,f.publication(baseline(),7,7));assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).stale,false);assert.deepEqual((await f.hp.getPendingUpdates(second.principal,campaign)).proposals,[]);
 await error(f.hp.getCharacterHp({...second.principal,profileId:'other'},campaign,profileId),'SCOPE_DENIED');
 const list=await f.bridge.listDevices(owner);assert.equal(list.devices.find(d=>d.credentialId===f.token.credentialId).revoked,true);
});

test('live HTTP owner revocation, wrong authorization and removed device grant are real 401 boundaries',async t=>{
 const f=await setup(t);const env={DB:f.db,OWNER_SITE_USER_ID:ownerId};const headers={'Content-Type':'application/json',Authorization:'Bearer '+f.secret,'X-Astravar-Credential':f.token.credentialId};
 // Worker clock is real; establish a fresh credential using a real-clock bridge for this fixture.
 f.setTime(Date.now());const fresh=await f.enroll();headers.Authorization='Bearer '+fresh.secret;headers['X-Astravar-Credential']=fresh.token.credentialId;
 const url='https://fixture-owner.example';const req=(path,method='GET',body,h=headers)=>new Request(url+path,{method,headers:h,...(body===undefined?{}:{body:JSON.stringify(body)})});
 const pub=await handleWorker(req('/v1/hp/campaigns/'+campaign+'/characters','POST',f.publication()),env);assert.equal(pub.status,200);assert.equal((await pub.json()).mode,HP_MODE);
 assert.equal((await handleWorker(req('/v1/hp/campaigns/'+campaign+'/characters/'+profileId,'GET',undefined,{...headers,Authorization:'Bearer '+'a'.repeat(43)}),env)).status,401);
 const revoked=await handleWorker(req('/v1/owner/devices/revoke','POST',{credentialId:fresh.token.credentialId},{'Content-Type':'application/json',Origin:url,'oai-authenticated-user-id':ownerId}),env);assert.equal(revoked.status,200);assert.equal((await revoked.json()).revoked,true);
 assert.equal((await handleWorker(req('/v1/hp/campaigns/'+campaign+'/characters/'+profileId),env)).status,401);
 assert.equal((await handleWorker(req('/v1/owner/devices/revoke','POST',{credentialId:'unknown'},{'Content-Type':'application/json','oai-authenticated-user-id':ownerId}),env)).status,404);
});

test('SQLite restart and backup retain narrow saved HP, independent receipts, and interrupted response deduplication',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'astravar-hp-restart-'));t.after(()=>rm(dir,{recursive:true,force:true}));const path=join(dir,'db.sqlite'),f=await setup(t,path);await f.hp.publishCharacter(f.principal,campaign,f.publication());const {proposal:p}=await f.hp.proposeHpUpdate(connector,campaign,f.proposal());const body=approved(p,f.now()),first=await f.hp.decide(f.principal,campaign,body);f.db.db.exec('PRAGMA wal_checkpoint(FULL)');await copyFile(path,join(dir,'backup.sqlite'));
 const restored=new SqliteAdapter(join(dir,'backup.sqlite'));t.after(()=>restored.close());const hp=new LiveHpBridge(restored,{ownerId,now:f.now});assert.deepEqual((await hp.getCommandReceipt(connector,campaign,p.commandId)).receipt,first.receipt);assert.deepEqual(await hp.decide(f.principal,campaign,body),first);assert.equal((await hp.getCharacterHp(connector,campaign,profileId)).revision,8);
});

test('two independent SQLite connections serialize simultaneous approval CAS and command-ID collisions',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'astravar-hp-race-'));t.after(()=>rm(dir,{recursive:true,force:true}));const path=join(dir,'db.sqlite'),f=await setup(t,path);await f.hp.publishCharacter(f.principal,campaign,f.publication());
 const otherDb=new SqliteAdapter(path);t.after(()=>otherDb.close());const other=new LiveHpBridge(otherDb,{ownerId,now:f.now});const p1=(await f.hp.proposeHpUpdate(connector,campaign,f.proposal())).proposal,p2=(await other.proposeHpUpdate(connector,campaign,f.proposal(7,24,-4))).proposal;
 const outcomes=await Promise.allSettled([f.hp.decide(f.principal,campaign,approved(p1,f.now())),other.decide(f.principal,campaign,approved(p2,f.now()))]);
 assert.equal(outcomes.filter(r=>r.status==='fulfilled'&&r.value.receipt.status==='APPLIED').length,1);assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).revision,8);
 // Failed race remains pending; a retry records its honest conflict against revision8.
 for(let i=0;i<outcomes.length;i++)if(outcomes[i].status==='rejected'){assert.equal(outcomes[i].reason.code,'REVISION_CONFLICT');const result=await f.hp.decide(f.principal,campaign,approved(i===0?p1:p2,f.now()));assert.equal(result.receipt.status,'CONFLICT');}
 const id=uuid(),current=await f.hp.getCharacterHp(connector,campaign,profileId);const pair=await Promise.allSettled([f.hp.publishCharacter(f.principal,campaign,{...f.publication(current.fields,8,8),commandId:id}),other.proposeHpUpdate(connector,campaign,{...f.proposal(8,current.fields.currentHp,-1),commandId:id})]);
 assert.equal(pair.filter(r=>r.status==='fulfilled').length,1);assert.equal(pair.filter(r=>r.status==='rejected')[0].reason.code,'COMMAND_ID_REUSED');
});

test('owner repair exposes superseded metadata without transferring approvals or starving active pending capacity',async t=>{
 const f=await setup(t);await f.hp.publishCharacter(f.principal,campaign,f.publication());const original=[];for(let i=0;i<50;i++)original.push((await f.hp.proposeHpUpdate(connector,campaign,f.proposal())).proposal);
 await error(f.hp.proposeHpUpdate(connector,campaign,f.proposal()),'PENDING_LIMIT');const replacement=await f.enroll();await f.hp.publishCharacter(replacement.principal,campaign,f.publication({...baseline(),currentHp:21,source:'Fictional restored accepted local receipt'},8,7));
 const pending=await f.hp.getPendingUpdates(replacement.principal,campaign);assert.equal(pending.proposals.length,0);assert.equal(pending.supersededCount,50);assert.equal(pending.superseded[0].status,'SUPERSEDED_BY_REPAIR');assert.equal(Object.hasOwn(pending.superseded[0],'currentFields'),false);assert.equal(Object.hasOwn(pending.superseded[0],'fields'),false);
 await error(f.hp.decide(replacement.principal,campaign,approved(original[0],f.now())),'PROPOSAL_MISMATCH');await error(f.hp.getCommandReceipt(connector,campaign,original[0].commandId),'RECEIPT_NOT_READY');
 const fresh=await f.hp.proposeHpUpdate(connector,campaign,f.proposal(8,21,-1));assert.equal(fresh.proposal.status,'PENDING');assert.equal((await f.hp.getConnection(connector)).connections[0].supersededCount,50);assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).revision,8);
});

test('dedicated heartbeat updates bounded freshness only, retries the exact acknowledgement, and rejects forged, expired or older timestamps',async t=>{
 const f=await setup(t);await f.hp.publishCharacter(f.principal,campaign,f.publication());const receiptsBefore=(await f.db.prepare('SELECT COUNT(*) AS n FROM bridge_hp_receipts').bind().first()).n;
 const heartbeat=async(sentAt=f.now())=>({commandId:uuid(),profileId,revision:7,fieldsFingerprint:await sha256(canonical(baseline())),sentAt});
 const body=await heartbeat();const ack=await f.hp.heartbeat(f.principal,campaign,body);assert.equal(ack.commandId,body.commandId);assert.equal(ack.fieldsFingerprint,body.fieldsFingerprint);assert.equal(ack.lastDeviceSyncAt,f.now());
 f.setTime(f.now()+HP_FRESHNESS_MS+1);assert.deepEqual(await f.hp.heartbeat(f.principal,campaign,body),ack);assert.equal((await f.hp.getCharacterHp(connector,campaign,profileId)).stale,true);
 await error(f.hp.heartbeat(f.principal,campaign,{...body,sentAt:f.now()}),'COMMAND_ID_REUSED');await error(f.hp.heartbeat(f.principal,campaign,await heartbeat(f.now()-60_001)),'HEARTBEAT_TIMESTAMP_EXPIRED');await error(f.hp.heartbeat(f.principal,campaign,await heartbeat(f.now()+60_001)),'HEARTBEAT_TIMESTAMP_EXPIRED');
 await error(f.hp.heartbeat(f.principal,campaign,{...await heartbeat(),fieldsFingerprint:'a'.repeat(64)}),'REVISION_CONFLICT');await error(f.hp.heartbeat(f.principal,campaign,{...await heartbeat(),revision:8}),'REVISION_CONFLICT');await error(f.hp.heartbeat(connector,campaign,await heartbeat()),'ROLE_DENIED');
 const next=await heartbeat();const fresh=await f.hp.heartbeat(f.principal,campaign,next);assert.equal(fresh.lastDeviceSyncAt,f.now());await error(f.hp.heartbeat(f.principal,campaign,{...await heartbeat(),sentAt:next.sentAt}),'HEARTBEAT_TIMESTAMP_REPLAYED');
 const r=await f.hp.getCharacterHp(connector,campaign,profileId);assert.equal(r.stale,false);assert.equal(r.revision,7);assert.deepEqual(r.fields,baseline());assert.equal((await f.db.prepare('SELECT COUNT(*) AS n FROM bridge_hp_receipts').bind().first()).n,receiptsBefore);await error(f.hp.getCommandReceipt(connector,campaign,next.commandId),'RECEIPT_NOT_READY');
});

test('heartbeat compare-and-swap across independent connections cannot freshen a changed or re-paired record',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'astravar-hp-heartbeat-'));t.after(()=>rm(dir,{recursive:true,force:true}));const f=await setup(t,join(dir,'db.sqlite'));await f.hp.publishCharacter(f.principal,campaign,f.publication());const db2=new SqliteAdapter(join(dir,'db.sqlite'));t.after(()=>db2.close());const hp2=new LiveHpBridge(db2,{ownerId,now:f.now});const body={commandId:uuid(),profileId,revision:7,fieldsFingerprint:await sha256(canonical(baseline())),sentAt:f.now()};
 const duplicate=await Promise.all([f.hp.heartbeat(f.principal,campaign,body),hp2.heartbeat(f.principal,campaign,body)]);assert.deepEqual(duplicate[0],duplicate[1]);
 await hp2.publishCharacter(f.principal,campaign,f.publication({...baseline(),currentHp:22},8,7));await error(f.hp.heartbeat(f.principal,campaign,body),'REVISION_CONFLICT');
 const replacement=await f.enroll();await error(hp2.heartbeat(f.principal,campaign,{...body,revision:8}),'HP_CONNECTION_NOT_AUTHORIZED');
 await error(f.hp.heartbeat(replacement.principal,campaign,{...body,revision:8,fieldsFingerprint:await sha256(canonical({...baseline(),currentHp:22}))}),'WRITER_CLAIM_REQUIRED');
});
