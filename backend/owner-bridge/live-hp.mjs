/** Explicitly enrolled, narrow current-HP mirror. Never infers unknown HP. */
import {BridgeError,canonical,sha256,CAMPAIGN,PROFILE,RECORD} from './core.mjs';
export const HP_MODE='ASTRAVAR_OWNER_HP_V1';
export const HP_FRESHNESS_MS=5*60_000;
const ID=/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
function fail(status,code){throw new BridgeError(status,code);}
function exact(v,keys){if(!v||typeof v!=='object'||Array.isArray(v)||Object.keys(v).length!==keys.length||keys.some(k=>!(k in v)))fail(400,'INVALID_REQUEST');}
function integer(v,min=0,max=Number.MAX_SAFE_INTEGER-1){if(!Number.isSafeInteger(v)||v<min||v>max)fail(400,'INVALID_INTEGER');return v;}
function text(v,max=2000){if(typeof v!=='string'||!v.trim()||v.length>max)fail(400,'INVALID_TEXT');return v;}
function command(v){if(typeof v!=='string'||!UUID.test(v))fail(400,'INVALID_COMMAND_ID');return v;}
function safeId(v){if(typeof v!=='string'||!ID.test(v))fail(400,'INVALID_SCOPE_ID');return v;}
function fields(v){exact(v,['name','currentHp','maximumHp','temporaryHp','source','updatedAt']);text(v.name,120);text(v.source);integer(v.updatedAt);for(const k of ['currentHp','maximumHp','temporaryHp'])if(v[k]!==null)integer(v[k],0,1_000_000);if(v.currentHp!==null&&v.maximumHp!==null&&v.currentHp>v.maximumHp)fail(400,'INVALID_HP_RANGE');return v;}
export class LiveHpBridge{
 constructor(db,{ownerId,now=Date.now}={}){if(!ownerId||typeof ownerId!=='string')throw Error('Verified owner required');this.db=db;this.ownerId=ownerId;this.now=now;}
 s(sql,...v){return this.db.prepare(sql).bind(...v);}
 envelope(campaignId,body){return{mode:HP_MODE,campaignId,...body};}
 async authorize(p,campaignId,profileId,permission,roles=['device','connector']){
  safeId(campaignId);if(campaignId===CAMPAIGN)fail(403,'LIVE_SCOPE_REQUIRED');if(profileId!==undefined){safeId(profileId);if([PROFILE,RECORD].includes(profileId))fail(403,'LIVE_SCOPE_REQUIRED');}
  if(!p||p.ownerId!==this.ownerId)fail(403,'OWNER_SCOPE_DENIED');
  if(!roles.includes(p.role))fail(403,'ROLE_DENIED');if(p.inactive)fail(401,'CREDENTIAL_REVOKED_OR_EXPIRED');
  if(!p.scopes?.includes(permission))fail(403,'SCOPE_DENIED');
  if(p.role==='device'&&(p.campaignId!==campaignId||(profileId!==undefined&&p.profileId!==profileId)))fail(403,'SCOPE_DENIED');
  const rows=await this.s('SELECT b.profile_id,b.credential_id,c.device_name,c.expires_at FROM bridge_hp_bindings b JOIN bridge_credentials c ON c.id=b.credential_id WHERE b.owner_id=? AND b.campaign_id=? AND c.revoked=0 AND c.expires_at>?',this.ownerId,campaignId,this.now()).all();
  const bindings=rows.results.filter(b=>(profileId===undefined||b.profile_id===profileId)&&(p.role!=='device'||b.credential_id===p.credentialId));
  if(!bindings.length)fail(403,'HP_CONNECTION_NOT_AUTHORIZED');return bindings;
 }
 async getConnection(p){
  if(!p||p.ownerId!==this.ownerId||p.role!=='connector')fail(403,'ROLE_DENIED');if(!p.scopes?.includes('hp:read'))fail(403,'SCOPE_DENIED');
  const rows=await this.s('SELECT b.campaign_id,b.profile_id,b.credential_id,c.device_name,r.revision,r.last_device_sync_at,(SELECT COUNT(*) FROM bridge_hp_proposals p LEFT JOIN bridge_hp_receipts x ON x.owner_id=p.owner_id AND x.campaign_id=p.campaign_id AND x.command_id=p.command_id WHERE p.owner_id=b.owner_id AND p.campaign_id=b.campaign_id AND p.profile_id=b.profile_id AND p.device_credential_id<>b.credential_id AND x.command_id IS NULL) AS superseded_count FROM bridge_hp_bindings b JOIN bridge_credentials c ON c.id=b.credential_id LEFT JOIN bridge_hp_records r ON r.owner_id=b.owner_id AND r.campaign_id=b.campaign_id AND r.profile_id=b.profile_id AND r.device_credential_id=b.credential_id WHERE b.owner_id=? AND c.revoked=0 AND c.expires_at>? ORDER BY b.campaign_id,b.profile_id',this.ownerId,this.now()).all();
  return{mode:HP_MODE,connections:rows.results.map(r=>({campaignId:r.campaign_id,profileId:r.profile_id,deviceName:r.device_name,supersededCount:r.superseded_count,scopes:['hp:read','hp:publish','hp:decide'],published:r.revision!==null,revision:r.revision??null,lastDeviceSyncAt:r.last_device_sync_at??null,stale:r.last_device_sync_at===null||this.now()-r.last_device_sync_at>HP_FRESHNESS_MS}))};
 }
 async record(campaign,id){return this.s('SELECT * FROM bridge_hp_records WHERE owner_id=? AND campaign_id=? AND profile_id=?',this.ownerId,campaign,id).first();}
 async receipt(campaign,id){return this.s('SELECT * FROM bridge_hp_receipts WHERE owner_id=? AND campaign_id=? AND command_id=?',this.ownerId,campaign,id).first();}
 async proposal(campaign,id){return this.s('SELECT * FROM bridge_hp_proposals WHERE owner_id=? AND campaign_id=? AND command_id=?',this.ownerId,campaign,id).first();}
 async getCharacterHp(p,campaign,id){
  const binding=(await this.authorize(p,campaign,id,'hp:read'))[0],r=await this.record(campaign,id);if(!r)fail(404,'CHARACTER_NOT_PUBLISHED');
  const stale=r.device_credential_id!==binding.credential_id||this.now()-r.last_device_sync_at>HP_FRESHNESS_MS;
  return this.envelope(campaign,{profileId:id,recordId:id,revision:r.revision,fields:JSON.parse(r.fields_json),verifiedAt:this.now(),publishedAt:r.published_at,deviceId:r.device_credential_id,lastDeviceSyncAt:r.last_device_sync_at,stale,dataClass:'LIVE_HP_ONLY'});
 }
 async heartbeat(p,campaign,body){
  exact(body,['profileId','revision','fieldsFingerprint','sentAt','commandId']);command(body.commandId);integer(body.revision,1);integer(body.sentAt);
  if(typeof body.fieldsFingerprint!=='string'||!/^[a-f0-9]{64}$/.test(body.fieldsFingerprint))fail(400,'INVALID_FINGERPRINT');
  await this.authorize(p,campaign,body.profileId,'hp:publish',['device']);
  const r=await this.record(campaign,body.profileId);if(!r)fail(404,'CHARACTER_NOT_PUBLISHED');
  if(r.device_credential_id!==p.credentialId)fail(409,'WRITER_CLAIM_REQUIRED');
  if(r.revision!==body.revision||await sha256(canonical(JSON.parse(r.fields_json)))!==body.fieldsFingerprint)fail(409,'REVISION_CONFLICT');
  const fp=await sha256(canonical({campaignId:campaign,deviceCredentialId:p.credentialId,...body}));
  const acknowledgement=at=>this.envelope(campaign,{profileId:body.profileId,revision:body.revision,fieldsFingerprint:body.fieldsFingerprint,commandId:body.commandId,lastDeviceSyncAt:at});
  if(r.last_heartbeat_id===body.commandId){if(r.last_heartbeat_fingerprint!==fp)fail(409,'COMMAND_ID_REUSED');return acknowledgement(r.last_heartbeat_at);}
  const acceptedAt=this.now();
  if(Math.abs(acceptedAt-body.sentAt)>60_000)fail(409,'HEARTBEAT_TIMESTAMP_EXPIRED');
  if(r.last_heartbeat_sent_at!==null&&body.sentAt<=r.last_heartbeat_sent_at)fail(409,'HEARTBEAT_TIMESTAMP_REPLAYED');
  const changed=await this.s('UPDATE bridge_hp_records SET last_device_sync_at=?,last_heartbeat_id=?,last_heartbeat_fingerprint=?,last_heartbeat_sent_at=?,last_heartbeat_at=? WHERE owner_id=? AND campaign_id=? AND profile_id=? AND revision=? AND fields_json=? AND device_credential_id=? AND COALESCE(last_heartbeat_sent_at,-1)=? AND EXISTS(SELECT 1 FROM bridge_credentials c WHERE c.id=? AND c.revoked=0 AND c.expires_at>?)',acceptedAt,body.commandId,fp,body.sentAt,acceptedAt,this.ownerId,campaign,body.profileId,body.revision,r.fields_json,p.credentialId,r.last_heartbeat_sent_at??-1,p.credentialId,acceptedAt).run();
  if(changed.meta?.changes!==1){const current=await this.record(campaign,body.profileId);if(current?.last_heartbeat_id===body.commandId&&current.last_heartbeat_fingerprint===fp)return acknowledgement(current.last_heartbeat_at);fail(409,'REVISION_CONFLICT');}
  return acknowledgement(acceptedAt);
 }
 async publishCharacter(p,campaign,body){
  exact(body,['commandId','expectedRevision','profileId','revision','fields']);command(body.commandId);integer(body.expectedRevision);integer(body.revision,1);fields(body.fields);
  await this.authorize(p,campaign,body.profileId,'hp:publish',['device']);
  const fp=await sha256(canonical(body)),old=await this.receipt(campaign,body.commandId);
  if(old){if(old.request_fingerprint!==fp||old.device_credential_id!==p.credentialId)fail(409,'COMMAND_ID_REUSED');return this.envelope(campaign,{receipt:JSON.parse(old.receipt_json)});}
  if(await this.proposal(campaign,body.commandId))fail(409,'COMMAND_ID_REUSED');
  const r=await this.record(campaign,body.profileId);if((r?.revision??0)!==body.expectedRevision)fail(409,'REVISION_CONFLICT');
  if(body.revision<body.expectedRevision||(body.revision===body.expectedRevision&&(!r||canonical(JSON.parse(r.fields_json))!==canonical(body.fields))))fail(409,'REVISION_CONFLICT');
  const receipt={commandId:body.commandId,recordId:body.profileId,status:'PUBLISHED',priorRevision:body.expectedRevision,resultingRevision:body.revision,fingerprint:fp,localReceiptId:body.commandId,localRecordFingerprint:await sha256(canonical(body.fields)),recordedAt:this.now(),eventId:crypto.randomUUID(),deviceCredentialId:p.credentialId};
  try{await this.receiptStatement(campaign,receipt,fp,canonical(body.fields)).run();}catch(e){return this.recover(campaign,body.commandId,fp,p,e);}
  return this.envelope(campaign,{receipt});
 }
 receiptStatement(campaign,r,requestFp,fieldsJson=null){return this.s('INSERT INTO bridge_hp_receipts(owner_id,campaign_id,command_id,profile_id,fingerprint,request_fingerprint,status,prior_revision,resulting_revision,receipt_json,fields_json,device_credential_id,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',this.ownerId,campaign,r.commandId,r.recordId,r.fingerprint,requestFp,r.status,r.priorRevision,r.resultingRevision,canonical(r),fieldsJson,r.deviceCredentialId,r.recordedAt);}
 async recover(campaign,id,fp,p,e){const old=await this.receipt(campaign,id);if(old){if(old.request_fingerprint!==fp||old.device_credential_id!==p.credentialId)fail(409,'COMMAND_ID_REUSED');return this.envelope(campaign,{receipt:JSON.parse(old.receipt_json)});}for(const code of ['REVISION_CONFLICT','COMMAND_ID_REUSED','PROPOSAL_MISMATCH','HP_CONNECTION_NOT_AUTHORIZED','DEVICE_SYNC_STALE'])if(String(e).includes(code))fail(code==='HP_CONNECTION_NOT_AUTHORIZED'?403:409,code);throw e;}
 async proposeHpUpdate(p,campaign,body){
  exact(body,['commandId','profileId','expectedRevision','expectedCurrentHp','adjustment','source']);command(body.commandId);integer(body.expectedRevision,1);integer(body.expectedCurrentHp,0,1_000_000);integer(body.adjustment,-1_000_000,1_000_000);if(body.adjustment===0)fail(400,'NO_FIELD_CHANGE');text(body.source,1900);
  const binding=(await this.authorize(p,campaign,body.profileId,'hp:propose',['connector']))[0];
  // Request identity is checked before current state so transport retries remain idempotent.
  const requestFp=await sha256(canonical({campaignId:campaign,...body})),old=await this.proposal(campaign,body.commandId);
  if(old){if(old.request_fingerprint!==requestFp)fail(409,'COMMAND_ID_REUSED');return this.envelope(campaign,{proposal:JSON.parse(old.proposal_json)});}
  if(await this.receipt(campaign,body.commandId))fail(409,'COMMAND_ID_REUSED');
  const r=await this.record(campaign,body.profileId);if(!r)fail(404,'CHARACTER_NOT_PUBLISHED');if(r.device_credential_id!==binding.credential_id||this.now()-r.last_device_sync_at>HP_FRESHNESS_MS)fail(409,'DEVICE_SYNC_STALE');
  const before=JSON.parse(r.fields_json);if(before.currentHp===null||before.maximumHp===null)fail(409,'HP_UNKNOWN');
  if(r.revision!==body.expectedRevision||before.currentHp!==body.expectedCurrentHp)fail(409,'REVISION_CONFLICT');
  const hp=before.currentHp+body.adjustment;if(hp<0||hp>before.maximumHp)fail(400,'INVALID_HP_RANGE');
  const value={commandId:body.commandId,recordId:body.profileId,expectedRevision:body.expectedRevision,fields:{currentHp:hp},source:body.source,adjustment:body.adjustment};
  const fp=await sha256(canonical({campaignId:campaign,...value}));
  const proposal={...value,profileId:body.profileId,fingerprint:fp,currentFields:before,status:'PENDING',createdAt:this.now(),differences:[{field:'currentHp',current:before.currentHp,proposed:hp}]};
  try{await this.s('INSERT INTO bridge_hp_proposals(owner_id,campaign_id,command_id,profile_id,expected_revision,fingerprint,request_fingerprint,proposal_json,device_credential_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)',this.ownerId,campaign,body.commandId,body.profileId,body.expectedRevision,fp,requestFp,canonical(proposal),binding.credential_id,this.now()).run();}
  catch(e){if(String(e).includes('PENDING_LIMIT'))fail(429,'PENDING_LIMIT');if(String(e).includes('HISTORY_LIMIT'))fail(429,'HISTORY_LIMIT');if(String(e).includes('DEVICE_SYNC_STALE'))fail(409,'DEVICE_SYNC_STALE');if(String(e).includes('REVISION_CONFLICT'))fail(409,'REVISION_CONFLICT');const existing=await this.proposal(campaign,body.commandId);if(!existing||existing.request_fingerprint!==requestFp)fail(409,'COMMAND_ID_REUSED');return this.envelope(campaign,{proposal:JSON.parse(existing.proposal_json)});}
  return this.envelope(campaign,{proposal});
 }
 async getPendingUpdates(p,campaign,profileId){
  const bindings=await this.authorize(p,campaign,profileId,'hp:read');
  const selectedProfile=p.role==='device'?bindings[0].profile_id:(profileId??null);
  const where='p.owner_id=? AND p.campaign_id=? AND (? IS NULL OR p.profile_id=?) AND r.command_id IS NULL';
  const values=[this.ownerId,campaign,selectedProfile,selectedProfile];
  const authorized='EXISTS(SELECT 1 FROM bridge_hp_bindings b JOIN bridge_credentials c ON c.id=b.credential_id WHERE b.owner_id=p.owner_id AND b.campaign_id=p.campaign_id AND b.profile_id=p.profile_id AND c.revoked=0 AND c.expires_at>?)';
  const isWriter='EXISTS(SELECT 1 FROM bridge_hp_bindings b JOIN bridge_credentials c ON c.id=b.credential_id WHERE b.owner_id=p.owner_id AND b.campaign_id=p.campaign_id AND b.profile_id=p.profile_id AND b.credential_id=p.device_credential_id AND c.revoked=0 AND c.expires_at>?)';
  const from=' FROM bridge_hp_proposals p LEFT JOIN bridge_hp_receipts r ON r.owner_id=p.owner_id AND r.campaign_id=p.campaign_id AND r.command_id=p.command_id WHERE '+where;
  const active=await this.s('SELECT p.proposal_json'+from+' AND '+isWriter+' ORDER BY p.command_id LIMIT 50',...values,this.now()).all();
  const supersededWhere=from+' AND '+authorized+' AND NOT '+isWriter;
  const supersededValues=[...values,this.now(),this.now()];
  const count=await this.s('SELECT COUNT(*) AS count'+supersededWhere,...supersededValues).first();
  const old=await this.s("SELECT p.command_id,p.profile_id,p.expected_revision,json_extract(p.proposal_json,'$.source') AS source"+supersededWhere+' ORDER BY p.created_at DESC,p.command_id LIMIT 50',...supersededValues).all();
  return this.envelope(campaign,{proposals:active.results.map(r=>JSON.parse(r.proposal_json)),supersededCount:count.count,superseded:old.results.map(r=>({commandId:r.command_id,recordId:r.profile_id,expectedRevision:r.expected_revision,status:'SUPERSEDED_BY_REPAIR',source:r.source}))});
 }
 async decide(p,campaign,body){
  exact(body,['commandId','approved','localAppliedRevision','localReceiptId','proposalFingerprint','localFields']);command(body.commandId);if(typeof body.approved!=='boolean')fail(400,'INVALID_APPROVAL');if(!/^[a-f0-9]{64}$/.test(body.proposalFingerprint||''))fail(400,'INVALID_FINGERPRINT');
  await this.authorize(p,campaign,p?.profileId,'hp:decide',['device']);
  const fp=await sha256(canonical(body)),old=await this.receipt(campaign,body.commandId);if(old){if(old.request_fingerprint!==fp||old.device_credential_id!==p.credentialId)fail(409,'COMMAND_ID_REUSED');return this.envelope(campaign,{receipt:JSON.parse(old.receipt_json)});}
  const row=await this.proposal(campaign,body.commandId);if(!row||row.fingerprint!==body.proposalFingerprint||row.profile_id!==p.profileId||row.device_credential_id!==p.credentialId)fail(409,'PROPOSAL_MISMATCH');
  const proposal=JSON.parse(row.proposal_json),r=await this.record(campaign,row.profile_id);if(!r)fail(404,'CHARACTER_NOT_PUBLISHED');
  if(body.approved){integer(body.localAppliedRevision,1);text(body.localReceiptId,200);fields(body.localFields);const before=proposal.currentFields,after=body.localFields;
   if(body.localAppliedRevision!==proposal.expectedRevision+1)fail(400,'LOCAL_COMMIT_REQUIRED');
   if(['name','maximumHp','temporaryHp'].some(k=>canonical(before[k])!==canonical(after[k]))||after.currentHp!==proposal.fields.currentHp||after.source!==`Player-approved HP connection test ${body.commandId} · ${proposal.source}`)fail(409,'LOCAL_FIELDS_MISMATCH');
  }else if(body.localAppliedRevision!==null||body.localReceiptId!==null||body.localFields!==null)fail(400,'REJECTION_MUST_NOT_APPLY');
  const status=body.approved?(r.revision===proposal.expectedRevision&&r.device_credential_id===p.credentialId?'APPLIED':'CONFLICT'):'REJECTED';
  const receipt={commandId:body.commandId,recordId:row.profile_id,status,priorRevision:r.revision,resultingRevision:status==='APPLIED'?r.revision+1:r.revision,fingerprint:row.fingerprint,localReceiptId:body.localReceiptId,localRecordFingerprint:body.localFields?await sha256(canonical(body.localFields)):null,recordedAt:this.now(),eventId:crypto.randomUUID(),deviceCredentialId:p.credentialId};
  try{await this.receiptStatement(campaign,receipt,fp,status==='APPLIED'?canonical(body.localFields):null).run();}catch(e){return this.recover(campaign,body.commandId,fp,p,e);}
  return this.envelope(campaign,{receipt});
 }
 async getCommandReceipt(p,campaign,id,profileId){command(id);const bindings=await this.authorize(p,campaign,profileId,p?.role==='connector'?'hp:receipt':'hp:read');const r=await this.receipt(campaign,id);if(!r)fail(404,'RECEIPT_NOT_READY');if(!bindings.some(b=>b.profile_id===r.profile_id&&(p.role!=='device'||b.credential_id===r.device_credential_id)))fail(403,'SCOPE_DENIED');return this.envelope(campaign,{receipt:JSON.parse(r.receipt_json)});}
 async history(p,campaign,after=0){integer(after);const bindings=await this.authorize(p,campaign,p?.profileId,'hp:read',['device']);const rows=await this.s('SELECT sequence,receipt_json FROM bridge_hp_receipts WHERE owner_id=? AND campaign_id=? AND profile_id=? AND device_credential_id=? AND sequence>? ORDER BY sequence LIMIT 50',this.ownerId,campaign,bindings[0].profile_id,p.credentialId,after).all();return this.envelope(campaign,{receipts:rows.results.map(r=>({...JSON.parse(r.receipt_json),cursor:r.sequence})),nextCursor:rows.results.at(-1)?.sequence??after});}
}
