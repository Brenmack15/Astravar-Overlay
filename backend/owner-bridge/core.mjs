/** Owner-scoped fictional character bridge. No campaign import or combat-engine adapter. */
export const MODE = 'ASTRAVAR_OWNER_BRIDGE_V1';
export const CAMPAIGN = 'synthetic-owner-acceptance';
export const PROFILE = 'owner-sync-fixture';
export const RECORD = 'owner-sync-fixture-int';
export const DEVICE_SCOPES = ['character:read','character:publish','proposal:decide'];
const SAFE_INT = Number.MAX_SAFE_INTEGER;
const ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export class BridgeError extends Error {
 constructor(status, code) { super(code); this.status=status; this.code=code; }
}
function fail(status,code) { throw new BridgeError(status,code); }
function exact(v,required,optional=[]) {
 if (!v || typeof v!=='object' || Array.isArray(v) || required.some(k=>!(k in v)) ||
  Object.keys(v).some(k=>!required.includes(k)&&!optional.includes(k))) fail(400,'INVALID_REQUEST');
}
function integer(v,min=0,max=SAFE_INT-1) { if(!Number.isSafeInteger(v)||v<min||v>max) fail(400,'INVALID_INTEGER'); return v; }
function text(v,max=2000) { if(typeof v!=='string'||!v.trim()||v.length>max) fail(400,'INVALID_TEXT'); return v; }
function command(v) { if(typeof v!=='string'||!UUID.test(v)) fail(400,'INVALID_COMMAND_ID'); return v; }
export function canonical(v) {
 if(v===null || typeof v==='boolean' || typeof v==='string') return JSON.stringify(v);
 if(typeof v==='number') { if(!Number.isSafeInteger(v)) fail(400,'INTEGER_REQUIRED'); return String(v); }
 if(Array.isArray(v)) return '['+v.map(canonical).join(',')+']';
 if(v&&typeof v==='object') return '{'+Object.keys(v).sort().map(k=>JSON.stringify(k)+':'+canonical(v[k])).join(',')+'}';
 fail(400,'INVALID_JSON');
}
export async function sha256(value) {
 return [...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value)))].map(b=>b.toString(16).padStart(2,'0')).join('');
}
function random(bytes=32) {
 const values=crypto.getRandomValues(new Uint8Array(bytes));
 return btoa(String.fromCharCode(...values)).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
}
function constantEqual(a,b) { if(typeof a!=='string'||typeof b!=='string'||a.length!==b.length) return false; let d=0; for(let i=0;i<a.length;i++) d|=a.charCodeAt(i)^b.charCodeAt(i); return d===0; }
function typedAbility(value) {
 exact(value,['recordId','kind','revision','fields']);
 if(value.recordId!==RECORD||value.kind!=='character_ability') fail(403,'FICTIONAL_FIXTURE_ONLY');
 integer(value.revision,1);
 exact(value.fields,['profileId','key','score','magicModifier','notes','source','updatedAt']);
 const f=value.fields;
 if(f.profileId!==PROFILE||f.key!=='INT') fail(403,'FICTIONAL_FIXTURE_ONLY');
 if(f.score!==null) integer(f.score,1,30);
 integer(f.magicModifier,-100,100); integer(f.updatedAt,0,SAFE_INT);
 if(typeof f.notes!=='string'||f.notes.length>2000) fail(400,'INVALID_NOTES');
 text(f.source); return value;
}
function typedProfile(value) {
 exact(value,['id','name','revision','source'],['currentHp','maximumHp','temporaryHp','armorClass','level','updatedAt']);
 if(value.id!==PROFILE) fail(403,'FICTIONAL_FIXTURE_ONLY');
 text(value.name,120); text(value.source); integer(value.revision,1);
 for(const k of ['currentHp','maximumHp','temporaryHp','armorClass','level','updatedAt']) if(value[k]!==undefined&&value[k]!==null) integer(value[k]);
 return value;
}

export class OwnerBridge {
 constructor(db,{ownerId,now=Date.now}={}) {
  if(!ownerId||typeof ownerId!=='string'||ownerId.length>200) throw Error('Verified owner binding required');
  this.db=db; this.ownerId=ownerId; this.now=now;
 }
 s(sql,...values) { return this.db.prepare(sql).bind(...values); }
 envelope(campaignId,body) { return {mode:MODE,campaignId,...body}; }
 scope(principal,campaign,role,permission,allowInactive=false) {
  if(campaign!==CAMPAIGN) fail(403,'FICTIONAL_SCOPE_ONLY');
  if(!principal||principal.ownerId!==this.ownerId) fail(403,'OWNER_SCOPE_DENIED');
  if(principal.inactive&&!allowInactive) fail(401,'CREDENTIAL_REVOKED_OR_EXPIRED');
  if(principal.role==='device'&&principal.campaignId!==campaign) fail(403,'SCOPE_DENIED');
  if(role && !role.includes(principal.role)) fail(403,'ROLE_DENIED');
  if(principal.role!=='owner'&&permission&&!principal.scopes?.includes(permission)) fail(403,'SCOPE_DENIED');
 }
 async authenticate(credentialId,bearer,{forSelfRevocation=false}={}) {
  if(!ID.test(credentialId||'')||typeof bearer!=='string'||!/^[A-Za-z0-9_-]{43,256}$/.test(bearer)) fail(401,'AUTHENTICATION_REQUIRED');
  const r=await this.s('SELECT * FROM bridge_credentials WHERE id=?',credentialId).first();
  if(!r||r.owner_id!==this.ownerId||!constantEqual(r.token_hash,await sha256(bearer))) fail(401,'CREDENTIAL_REVOKED_OR_EXPIRED');
  const inactive=!!r.revoked||r.expires_at<=this.now();
  if(inactive&&!forSelfRevocation) fail(401,'CREDENTIAL_REVOKED_OR_EXPIRED');
  return {ownerId:r.owner_id,role:r.role,credentialId:r.id,campaignId:r.campaign_id,scopes:JSON.parse(r.scopes_json),deviceName:r.device_name,inactive};
 }
 async requestPairing(body) {
  exact(body,['campaignId','deviceName','deviceSecretHash']);
  if(body.campaignId!==CAMPAIGN) fail(403,'FICTIONAL_SCOPE_ONLY');
  text(body.deviceName,120);
  if(!/^[0-9a-f]{64}$/.test(body.deviceSecretHash)) fail(400,'INVALID_DEVICE_PROOF');
  const deviceCode=random(), userCode=random(12), id=crypto.randomUUID(), expiresAt=this.now()+10*60_000;
  try { await this.db.batch([
   this.s('DELETE FROM bridge_pairings WHERE owner_id=? AND expires_at<=?',this.ownerId,this.now()),
   this.s('INSERT INTO bridge_pairings(id,owner_id,campaign_id,device_code_hash,user_code_hash,secret_hash,device_name,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?)',id,this.ownerId,CAMPAIGN,await sha256(deviceCode),await sha256(userCode),body.deviceSecretHash,body.deviceName,expiresAt,this.now())
  ]); } catch(error) { if(String(error).includes('PAIRING_LIMIT')) fail(429,'PAIRING_LIMIT');throw error; }
  return this.envelope(CAMPAIGN,{deviceCode,userCode,expiresAt,pollIntervalSeconds:5});
 }
 async approvePairing(principal,body) {
  this.scope(principal,CAMPAIGN,['owner']); exact(body,['userCode']); text(body.userCode,100);
  const p=await this.s('SELECT * FROM bridge_pairings WHERE owner_id=? AND user_code_hash=?',this.ownerId,await sha256(body.userCode)).first();
  if(!p||p.expires_at<=this.now()) fail(404,'PAIRING_EXPIRED_OR_UNKNOWN');
  if(p.credential_id) return this.envelope(CAMPAIGN,{status:'APPROVED',credentialId:p.credential_id});
  const credentialId=crypto.randomUUID(),expiresAt=this.now()+30*24*60*60_000;
  try {
   await this.db.batch([
    this.s('INSERT INTO bridge_credentials(id,owner_id,campaign_id,token_hash,role,scopes_json,device_name,expires_at) VALUES(?,?,?,?,?,?,?,?)',credentialId,this.ownerId,CAMPAIGN,p.secret_hash,'device',canonical(DEVICE_SCOPES),p.device_name,expiresAt),
    this.s('UPDATE bridge_pairings SET credential_id=? WHERE id=? AND credential_id IS NULL',credentialId,p.id)
   ]);
  } catch(error) {
   const existing=await this.s('SELECT credential_id FROM bridge_pairings WHERE id=?',p.id).first();
   if(existing?.credential_id) return this.envelope(CAMPAIGN,{status:'APPROVED',credentialId:existing.credential_id});
   if(String(error).includes('DEVICE_LIMIT')) fail(429,'DEVICE_LIMIT');
   throw error;
  }
  return this.envelope(CAMPAIGN,{status:'APPROVED',credentialId});
 }
 async pollPairing(body) {
  exact(body,['deviceCode','deviceSecret']); text(body.deviceCode,100);
  if(!/^[A-Za-z0-9_-]{43,256}$/.test(body.deviceSecret||'')) fail(401,'INVALID_DEVICE_PROOF');
  const p=await this.s('SELECT * FROM bridge_pairings WHERE owner_id=? AND device_code_hash=?',this.ownerId,await sha256(body.deviceCode)).first();
  if(!p||p.expires_at<=this.now()||!constantEqual(p.secret_hash,await sha256(body.deviceSecret))) fail(401,'PAIRING_EXPIRED_OR_UNKNOWN');
  if(!p.credential_id) return this.envelope(CAMPAIGN,{status:'PENDING'});
  const c=await this.s('SELECT * FROM bridge_credentials WHERE id=?',p.credential_id).first();
  if(!c||c.revoked||c.expires_at<=this.now()) fail(401,'CREDENTIAL_REVOKED_OR_EXPIRED');
  return this.envelope(CAMPAIGN,{status:'PAIRED',credentialId:c.id,expiresAt:c.expires_at,deviceName:c.device_name,ownerId:this.ownerId,ownerLabel:'Owner '+this.ownerId,scopes:JSON.parse(c.scopes_json)});
 }
 async revokeDevice(principal,campaign,credentialId) {
  this.scope(principal,campaign,['owner','device'],null,true);
  if(principal.role==='device'&&principal.credentialId!==credentialId) fail(403,'ROLE_DENIED');
  await this.s('UPDATE bridge_credentials SET revoked=1 WHERE id=? AND owner_id=? AND campaign_id=?',credentialId,this.ownerId,campaign).run();
  return this.envelope(campaign,{status:'REVOKED',revoked:true,credentialId});
 }
 async listDevices(principal) {
  this.scope(principal,CAMPAIGN,['owner']);
  const rows=await this.s('SELECT id,device_name,expires_at,revoked,scopes_json FROM bridge_credentials WHERE owner_id=? AND campaign_id=? ORDER BY id',this.ownerId,CAMPAIGN).all();
  return this.envelope(CAMPAIGN,{devices:rows.results.map(r=>({credentialId:r.id,deviceName:r.device_name,expiresAt:r.expires_at,revoked:!!r.revoked,scopes:JSON.parse(r.scopes_json)}))});
 }
 async record(campaign,id) { return this.s('SELECT * FROM bridge_records WHERE owner_id=? AND campaign_id=? AND record_id=?',this.ownerId,campaign,id).first(); }
 async receipt(campaign,id) { return this.s('SELECT * FROM bridge_receipts WHERE owner_id=? AND campaign_id=? AND command_id=?',this.ownerId,campaign,id).first(); }
 async proposal(campaign,id) { return this.s('SELECT * FROM bridge_proposals WHERE owner_id=? AND campaign_id=? AND command_id=?',this.ownerId,campaign,id).first(); }
 async getCharacter(principal,campaign,profileId=PROFILE) {
  this.scope(principal,campaign,['device','connector','owner'],'character:read');
  if(profileId!==PROFILE) fail(403,'FICTIONAL_FIXTURE_ONLY');
  const profile=await this.s('SELECT profile_json FROM bridge_profiles WHERE owner_id=? AND campaign_id=? AND profile_id=?',this.ownerId,campaign,profileId).first();
  if(!profile) fail(404,'CHARACTER_NOT_PUBLISHED');
  const rows=await this.s('SELECT * FROM bridge_records WHERE owner_id=? AND campaign_id=? AND profile_id=? ORDER BY record_id',this.ownerId,campaign,profileId).all();
  return this.envelope(campaign,{profile:JSON.parse(profile.profile_json),abilities:rows.results.map(r=>({recordId:r.record_id,kind:r.kind,revision:r.revision,fields:JSON.parse(r.fields_json)})),verifiedAt:this.now(),dataClass:'FICTIONAL_ONLY'});
 }
 async getCharacterAbilities(principal,campaign,profileId=PROFILE) {
  const value=await this.getCharacter(principal,campaign,profileId);
  return this.envelope(campaign,{profileId,abilities:value.abilities,verifiedAt:value.verifiedAt,dataClass:'FICTIONAL_ONLY'});
 }
 async publishCharacter(principal,campaign,body) {
  this.scope(principal,campaign,['device'],'character:publish');
  exact(body,['commandId','profile','ability','expectedRevision']); command(body.commandId);
  typedProfile(body.profile); typedAbility(body.ability); integer(body.expectedRevision);
  if(body.ability.revision!==body.expectedRevision+1) fail(400,'INVALID_REVISION');
  const fp=await sha256(canonical(body)),previous=await this.receipt(campaign,body.commandId);
  if(previous) { if(previous.request_fingerprint!==fp) fail(409,'COMMAND_ID_REUSED'); return this.envelope(campaign,{receipt:JSON.parse(previous.receipt_json)}); }
  if(await this.proposal(campaign,body.commandId)) fail(409,'COMMAND_ID_REUSED');
  const existing=await this.record(campaign,body.ability.recordId);
  if((existing?.revision??0)!==body.expectedRevision) fail(409,'REVISION_CONFLICT');
  const profile=await this.s('SELECT profile_json FROM bridge_profiles WHERE owner_id=? AND campaign_id=? AND profile_id=?',this.ownerId,campaign,PROFILE).first();
  if(profile && canonical(JSON.parse(profile.profile_json))!==canonical(body.profile)) fail(409,'PROFILE_CONFLICT');
  const receipt={commandId:body.commandId,recordId:body.ability.recordId,status:'PUBLISHED',priorRevision:body.expectedRevision,resultingRevision:body.ability.revision,fingerprint:fp,localReceiptId:body.commandId,recordedAt:this.now(),eventId:crypto.randomUUID()};
  try {
   await this.db.batch([
    this.s('INSERT OR IGNORE INTO bridge_profiles VALUES(?,?,?,?)',this.ownerId,campaign,PROFILE,canonical(body.profile)),
    this.receiptStatement(campaign,receipt,fp,canonical(body.ability.fields))
   ]);
  } catch(error) { return this.recoverWrite(campaign,body.commandId,fp,error); }
  return this.envelope(campaign,{receipt});
 }
 receiptStatement(campaign,r,requestFp,fieldsJson=null) {
  return this.s('INSERT INTO bridge_receipts(owner_id,campaign_id,command_id,record_id,fingerprint,request_fingerprint,status,prior_revision,resulting_revision,receipt_json,fields_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)',this.ownerId,campaign,r.commandId,r.recordId,r.fingerprint,requestFp,r.status,r.priorRevision,r.resultingRevision,canonical(r),fieldsJson);
 }
 async recoverWrite(campaign,id,requestFp,error) {
  const previous=await this.receipt(campaign,id);
  if(previous) { if(previous.request_fingerprint!==requestFp) fail(409,'COMMAND_ID_REUSED'); return this.envelope(campaign,{receipt:JSON.parse(previous.receipt_json)}); }
  if(String(error).includes('REVISION_CONFLICT')) fail(409,'REVISION_CONFLICT');
  if(String(error).includes('COMMAND_ID_REUSED')) fail(409,'COMMAND_ID_REUSED');
  throw error;
 }
 async proposeRecordUpdate(principal,campaign,body) {
  this.scope(principal,campaign,['connector'],'proposal:create');
  exact(body,['commandId','recordId','expectedRevision','fields','source']); command(body.commandId); text(body.source,1900);
  if(body.recordId!==RECORD) fail(403,'FICTIONAL_FIXTURE_ONLY'); integer(body.expectedRevision,1);
  exact(body.fields,['score']); integer(body.fields.score,1,30);
  const fp=await sha256(canonical({campaignId:campaign,...body})),previous=await this.proposal(campaign,body.commandId);
  if(previous) { if(previous.fingerprint!==fp) fail(409,'COMMAND_ID_REUSED'); return this.envelope(campaign,{proposal:JSON.parse(previous.proposal_json)}); }
  if(await this.receipt(campaign,body.commandId)) fail(409,'COMMAND_ID_REUSED');
  const record=await this.record(campaign,body.recordId);
  if(!record) fail(404,'RECORD_NOT_PUBLISHED');
  if(record.revision!==body.expectedRevision) fail(409,'REVISION_CONFLICT');
  const currentFields=JSON.parse(record.fields_json);
  if(currentFields.score===body.fields.score) fail(400,'NO_FIELD_CHANGE');
  const proposal={...body,fingerprint:fp,currentFields,status:'PENDING',createdAt:this.now(),differences:[{field:'score',current:currentFields.score,proposed:body.fields.score}]};
  try { await this.s('INSERT INTO bridge_proposals VALUES(?,?,?,?,?,?,?)',this.ownerId,campaign,body.commandId,body.recordId,body.expectedRevision,fp,canonical(proposal)).run(); }
  catch(error) { if(String(error).includes('PENDING_LIMIT')) fail(429,'PENDING_LIMIT'); const row=await this.proposal(campaign,body.commandId); if(!row||row.fingerprint!==fp) fail(409,'COMMAND_ID_REUSED'); return this.envelope(campaign,{proposal:JSON.parse(row.proposal_json)}); }
  return this.envelope(campaign,{proposal});
 }
 async getPendingUpdates(principal,campaign) {
  this.scope(principal,campaign,['connector','device','owner'],principal?.role==='connector'?'proposal:read':null);
  const rows=await this.s('SELECT p.proposal_json FROM bridge_proposals p LEFT JOIN bridge_receipts r ON r.owner_id=p.owner_id AND r.campaign_id=p.campaign_id AND r.command_id=p.command_id WHERE p.owner_id=? AND p.campaign_id=? AND r.command_id IS NULL ORDER BY p.command_id LIMIT 50',this.ownerId,campaign).all();
  return this.envelope(campaign,{proposals:rows.results.map(r=>JSON.parse(r.proposal_json))});
 }
 async decide(principal,campaign,body) {
  this.scope(principal,campaign,['device'],'proposal:decide');
  exact(body,['commandId','approved','localAppliedRevision','localReceiptId','proposalFingerprint','localFields']); command(body.commandId);
  if(typeof body.approved!=='boolean') fail(400,'INVALID_APPROVAL');
  if(body.localAppliedRevision!==null) integer(body.localAppliedRevision,1);
  if(body.localReceiptId!==null) text(body.localReceiptId,200);
  if(!/^[0-9a-f]{64}$/.test(body.proposalFingerprint)) fail(400,'INVALID_FINGERPRINT');
  const requestFp=await sha256(canonical(body)),previous=await this.receipt(campaign,body.commandId);
  if(previous) { if(previous.request_fingerprint!==requestFp) fail(409,'COMMAND_ID_REUSED'); return this.envelope(campaign,{receipt:JSON.parse(previous.receipt_json)}); }
  const row=await this.proposal(campaign,body.commandId);
  if(!row||row.fingerprint!==body.proposalFingerprint) fail(409,'PROPOSAL_MISMATCH');
  const proposal=JSON.parse(row.proposal_json),record=await this.record(campaign,proposal.recordId);
  if(!record) fail(404,'RECORD_NOT_PUBLISHED');
  let status=body.approved?'APPLIED':'REJECTED';
  if(body.approved && record.revision!==proposal.expectedRevision) status='CONFLICT';
  if(body.approved && (body.localAppliedRevision!==proposal.expectedRevision+1||!body.localReceiptId)) fail(400,'LOCAL_COMMIT_REQUIRED');
  if(!body.approved && (body.localAppliedRevision!==null||body.localReceiptId!==null||body.localFields!==null)) fail(400,'REJECTION_MUST_NOT_APPLY');
  if(body.approved) {
   typedAbility({recordId:proposal.recordId,kind:'character_ability',revision:body.localAppliedRevision,fields:body.localFields});
   const before=proposal.currentFields,after=body.localFields;
   if(['profileId','key','magicModifier','notes'].some(k=>canonical(before[k])!==canonical(after[k]))||
    after.score!==proposal.fields.score||after.source!==`Player-approved owner proposal ${body.commandId} · ${proposal.source}`) fail(409,'LOCAL_FIELDS_MISMATCH');
  }
  const priorRevision=record.revision,resultingRevision=status==='APPLIED'?priorRevision+1:priorRevision;
  const receipt={commandId:body.commandId,recordId:proposal.recordId,status,priorRevision,resultingRevision,fingerprint:row.fingerprint,localReceiptId:body.localReceiptId,localRecordFingerprint:body.localFields?await sha256(canonical(body.localFields)):null,recordedAt:this.now(),eventId:crypto.randomUUID(),deviceCredentialId:principal.credentialId};
  const fields=status==='APPLIED'?body.localFields:null;
  try { await this.receiptStatement(campaign,receipt,requestFp,fields&&canonical(fields)).run(); }
  catch(error) { return this.recoverWrite(campaign,body.commandId,requestFp,error); }
  return this.envelope(campaign,{receipt});
 }
 async getCommandReceipt(principal,campaign,id) {
  this.scope(principal,campaign,['connector','device','owner'],principal?.role==='connector'?'proposal:read':null); command(id);
  const receipt=await this.receipt(campaign,id);
  if(!receipt) fail(404,'RECEIPT_NOT_READY');
  return this.envelope(campaign,{receipt:JSON.parse(receipt.receipt_json)});
 }
 async history(principal,campaign,after=0) {
  this.scope(principal,campaign,['device','owner'],'character:read'); integer(after);
  const rows=await this.s('SELECT sequence,receipt_json FROM bridge_receipts WHERE owner_id=? AND campaign_id=? AND sequence>? ORDER BY sequence LIMIT 50',this.ownerId,campaign,after).all();
  return this.envelope(campaign,{receipts:rows.results.map(r=>({...JSON.parse(r.receipt_json),cursor:r.sequence})),nextCursor:rows.results.at(-1)?.sequence??after});
 }
}
