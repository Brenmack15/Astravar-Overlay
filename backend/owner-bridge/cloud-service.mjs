import { canonical, sha256, BridgeError } from './core.mjs';
import { CLOUD_SCOPES, CLOUD_ID, cloudObject, cloudMatches, validCloudId, cloudFail, validateCloudCommand } from './cloud-validation.mjs';
import { CloudPlan, cloudAllowed, cloudEntity } from './cloud-plan.mjs';
import { requestEvidence, resultEvidence, readRequestEvidence, readResultEvidence } from './cloud-evidence.mjs';
import { deletionTail, completeMediaCleanup } from './cloud-lifecycle.mjs';

const utcMillis = "CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)";
const grantScope = { type:'string', enum:[...CLOUD_SCOPES.flatMap(s=>[s+'.read', s+'.write']),'approval.read'] };
const grantSpec = cloudObject({
  scopes:{type:'array',items:grantScope,uniqueItems:true,maxItems:25},
  actors:{type:'array',items:{anyOf:[CLOUD_ID,{const:'*'}]},uniqueItems:true,minItems:1,maxItems:100},
  auto:{type:'array',items:grantScope,uniqueItems:true,maxItems:12},
  expiresAt:{type:'integer',minimum:1},revoked:{type:'boolean'},expectedGrantRevision:{type:'integer',minimum:0},
});
function subjectFor(principal, ownerId, id) {
  if(!principal||principal.ownerId!==ownerId||principal.inactive)cloudFail(403,'CLOUD_GRANT_DENIED');
  if(principal.role==='owner' && principal.credentialId==='sites-owner')return 'sites-owner';
  if(principal.role==='connector' && principal.credentialId==='sites-mcp')return 'sites-mcp';
  if(principal.role==='device' && principal.campaignId===id && validCloudId(principal.credentialId))return 'device:'+principal.credentialId;
  cloudFail(403,'CLOUD_GRANT_DENIED');
}
function unpackGrant(row) {
  return {subject:row.subject,scopes:JSON.parse(row.scopes_json),actors:JSON.parse(row.actors_json),auto:JSON.parse(row.auto_json),expiresAt:row.expires_at,revision:row.revision};
}
function assertAccess(grant, access, write=false) {
  if(!access.length || !access.every(e=>cloudAllowed(grant,e.scope,e.actorId,write && e.write)))cloudFail(403,'CLOUD_GRANT_DENIED');
}
function receiptOf(row,evidence) {
  return {commandId:row.command_id,transactionId:row.transaction_id,status:row.status,epoch:row.epoch,revision:row.revision,createdAt:row.created_at,...evidence};
}
export class CloudService {
  constructor(db,{ownerId,operations,kinds,now=Date.now,isolated=false}) {
    this.db=db; this.ownerId=ownerId; this.operations=operations; this.kinds=kinds; this.now=now; this.isolated=isolated;
  }
  s(sql,...values){return this.db.prepare(sql).bind(...values);}
  key(id){if(!validCloudId(id))cloudFail(400,'INVALID_CONTINUITY');return [this.ownerId,id];}
  async continuity(id) {
    const row=await this.s('SELECT * FROM cloud_continuities WHERE owner_id=? AND continuity_id=?',...this.key(id)).first();
    if(!row||row.authority!=='cloud')cloudFail(404,'CLOUD_AUTHORITY_UNAVAILABLE');
    return row;
  }
  owner(principal,id){if(subjectFor(principal,this.ownerId,id)!=='sites-owner')cloudFail(403,'OWNER_REQUIRED');}
  async createIsolated(principal,id) {
    this.owner(principal,id);
    if(!this.isolated || !id.startsWith('qa-'))cloudFail(403,'ISOLATED_CONTINUITY_REQUIRED');
    const binding=this.key(id);
    try { await this.s("INSERT INTO cloud_continuities(owner_id,continuity_id,authority,epoch,revision,schema_version) VALUES(?,?,'cloud',1,0,1)",...binding).run(); }
    catch{cloudFail(409,'CONTINUITY_ALREADY_EXISTS_OR_LEGACY');}
    return {continuityId:id,authority:'cloud',epoch:1,revision:0};
  }
  async setGrant(principal,id,subject,grant) {
    this.owner(principal,id);
    await this.continuity(id);
    if(!(subject==='sites-mcp'||(subject.startsWith('device:')&&validCloudId(subject.slice(7)))) || !cloudMatches(grant,grantSpec))cloudFail(400,'INVALID_GRANT');
    if(!grant.revoked && (grant.expiresAt<=this.now()||grant.expiresAt>this.now()+366*86400000) ||
      grant.scopes.some(s=>s.endsWith('.write')&&!grant.scopes.includes(s.replace('.write','.read'))) ||
      grant.auto.some(s=>!s.endsWith('.write')||!grant.scopes.includes(s)) ||
      grant.actors.includes('*')&&grant.actors.length!==1)cloudFail(400,'INVALID_GRANT');
    const b=this.key(id),v=[JSON.stringify(grant.scopes),JSON.stringify(grant.actors),JSON.stringify(grant.auto),grant.expiresAt,Number(grant.revoked)];
    if(subject.startsWith('device:') && !await this.s('SELECT 1 FROM bridge_credentials WHERE owner_id=? AND campaign_id=? AND id=?',...b,subject.slice(7)).first())cloudFail(404,'DEVICE_CREDENTIAL_REQUIRED');
    let changed;
    if(grant.expectedGrantRevision===0){
      changed=await this.s("INSERT INTO cloud_grants(owner_id,continuity_id,subject,scopes_json,actors_json,auto_json,expires_at,revoked,revision) VALUES(?,?,?,?,?,?,?,?,1) ON CONFLICT DO NOTHING",...b,subject,...v).run();
    }else{
      changed=await this.s('UPDATE cloud_grants SET scopes_json=?,actors_json=?,auto_json=?,expires_at=?,revoked=?,revision=revision+1 WHERE owner_id=? AND continuity_id=? AND subject=? AND revision=?',...v,...b,subject,grant.expectedGrantRevision).run();
    }
    if(changed.meta.changes!==1)cloudFail(409,'GRANT_REVISION_CONFLICT');
    return {continuityId:id,subject,revision:grant.expectedGrantRevision+1,revoked:grant.revoked};
  }
  async grantForSubject(id,subject) {
    if(subject==='sites-owner')return {subject,scopes:grantScope.enum,actors:['*'],auto:grantScope.enum,revision:0,expiresAt:Number.MAX_SAFE_INTEGER};
    const b=this.key(id);
    const row=await this.s('SELECT * FROM cloud_grants WHERE owner_id=? AND continuity_id=? AND subject=? AND revoked=0 AND expires_at>?',...b,subject,this.now()).first();
    if(!row)cloudFail(403,'CLOUD_GRANT_DENIED');
    if(subject.startsWith('device:')){
      const credential=await this.s('SELECT expires_at FROM bridge_credentials WHERE owner_id=? AND campaign_id=? AND id=? AND revoked=0 AND expires_at>?',...b,subject.slice(7),this.now()).first();
      if(!credential)cloudFail(401,'CREDENTIAL_REVOKED_OR_EXPIRED');
      row.expires_at=Math.min(row.expires_at,credential.expires_at);
    }
    return unpackGrant(row);
  }
  async access(principal,id){
    const subject=subjectFor(principal,this.ownerId,id);
    // Scope-check ordinary callers before exposing continuity membership.
    const grant=await this.grantForSubject(id,subject);
    return {grant,continuity:await this.continuity(id)};
  }
  async capabilities(principal,id) {
    const {grant,continuity:c}=await this.access(principal,id);
    return {version:1,continuityId:id,authority:c.authority,epoch:c.epoch,revision:c.revision,subject:grant.subject,grantRevision:grant.revision,
      scopes:grant.scopes,actors:grant.actors,automatic:grant.auto,
      kindScopes:Object.fromEntries(Object.entries(this.kinds).filter(([,scope])=>grant.scopes.includes(scope+'.read'))),
      kinds:Object.keys(this.kinds).filter(k=>grant.scopes.includes(this.kinds[k]+'.read')),
      operations:Object.entries(this.operations).filter(([,d])=>grant.scopes.includes(d.scope+'.write')).map(([name,d])=>({name,scope:d.scope,schema:d.schema,eligibleForAutoSync:!d.ownerReview&&grant.auto.includes(d.scope+'.write')}))};
  }
  async state(principal,id,opts={}) {
    const {grant,continuity:c}=await this.access(principal,id), b=this.key(id),after=opts.after??'',limit=opts.limit??50;
    if(after!==''&&!validCloudId(after)||!Number.isSafeInteger(limit)||limit<1||limit>100)cloudFail(400,'INVALID_PAGE');
    if(after && [opts.atRevision,opts.atEpoch,opts.grantRevision].some(n=>!Number.isSafeInteger(n)))cloudFail(400,'PAGE_BASELINE_REQUIRED');
    if(opts.atRevision!==undefined&&opts.atRevision!==c.revision||opts.atEpoch!==undefined&&opts.atEpoch!==c.epoch||opts.grantRevision!==undefined&&opts.grantRevision!==grant.revision)cloudFail(409,'SNAPSHOT_CHANGED');
    let kinds=Object.keys(this.kinds).filter(k=>grant.scopes.includes(this.kinds[k]+'.read'));
    if(opts.kind){if(!kinds.includes(opts.kind))cloudFail(403,'CLOUD_GRANT_DENIED');kinds=[opts.kind];}
    if(opts.id&&!validCloudId(opts.id))cloudFail(400,'INVALID_ENTITY_ID');
    const predicates=['owner_id=?','continuity_id=?','id>?','kind IN(SELECT value FROM json_each(?))'],values=[...b,after,JSON.stringify(kinds)];
    if(!grant.actors.includes('*')){predicates.push('actor_id IN(SELECT value FROM json_each(?))');values.push(JSON.stringify(grant.actors));}
    if(opts.id){predicates.push('id=?');values.push(opts.id);}
    if(opts.activeOnly!==false || !grant.scopes.includes('lifecycle.read'))predicates.push("life='active'");
    if(opts.query!==undefined){
      if(typeof opts.query!=='string'||opts.query.length>120)cloudFail(400,'INVALID_QUERY');
      predicates.push('instr(lower(title),lower(?))>0');values.push(opts.query);
    }
    const rows=(await this.s('SELECT * FROM cloud_entities WHERE '+predicates.join(' AND ')+' ORDER BY id LIMIT ?',...values,limit+1).all()).results;
    const records=rows.slice(0,limit).map(cloudEntity);
    const again=await this.access(principal,id);
    if(again.continuity.epoch!==c.epoch||again.continuity.revision!==c.revision||again.grant.revision!==grant.revision)cloudFail(409,'SNAPSHOT_CHANGED');
    return {continuityId:id,authority:'cloud',epoch:c.epoch,revision:c.revision,subject:grant.subject,grantRevision:grant.revision,grantExpiresAt:grant.expiresAt,records,nextAfter:rows.length>limit?records.at(-1).id:null};
  }
  async receipt(principal,id,commandId) {
    if(!validCloudId(commandId))cloudFail(400,'INVALID_COMMAND_ID');
    const {grant}=await this.access(principal,id);
    const b=this.key(id),row=await this.s('SELECT * FROM cloud_receipts WHERE owner_id=? AND continuity_id=? AND command_id=?',...b,commandId).first();
    if(row){
      assertAccess(grant,JSON.parse(row.access_json));
      const evidence=await readResultEvidence(this,b,row);
      const cleanup=evidence.deletedIds?await completeMediaCleanup(this,b,commandId):null;
      if(cleanup)evidence.assetCleanup=cleanup;
      return {status:row.status,commandId,epoch:row.epoch,revision:row.revision,receipt:receiptOf(row,evidence)};
    }
    const pending=await this.s('SELECT * FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND command_id=?',...b,commandId).first();
    if(!pending)cloudFail(404,'COMMAND_UNKNOWN');
    assertAccess(grant,JSON.parse(pending.access_json));
    const {command}=JSON.parse(pending.request_json);
    return {status:pending.status,commandId,epoch:command.epoch,revision:command.expectedRevision,digest:pending.digest};
  }
  guard(predicate,...values){return this.s('INSERT INTO cloud_guards(ok) SELECT CASE WHEN ('+predicate+') THEN 1 ELSE 0 END',...values);}
  batchGuards(id,c,grant) {
    const b=this.key(id),checks=[this.guard("EXISTS(SELECT 1 FROM cloud_continuities WHERE owner_id=? AND continuity_id=? AND authority='cloud' AND epoch=? AND revision=?)",...b,c.epoch,c.revision)];
    if(grant.subject!=='sites-owner'){
      checks.push(this.guard('EXISTS(SELECT 1 FROM cloud_grants WHERE owner_id=? AND continuity_id=? AND subject=? AND revision=? AND revoked=0 AND expires_at>'+utcMillis+')',...b,grant.subject,grant.revision));
      if(grant.subject.startsWith('device:'))checks.push(this.guard('EXISTS(SELECT 1 FROM bridge_credentials WHERE owner_id=? AND campaign_id=? AND id=? AND revoked=0 AND expires_at>'+utcMillis+')',...b,grant.subject.slice(7)));
    }
    return checks;
  }
  async transact(stmts) {
    try { await this.db.batch([...stmts,this.s('DELETE FROM cloud_guards')]); }
    catch(error) {
      if(/CLOUD_|constraint|CHECK|SQLITE_CONSTRAINT|UNIQUE|FOREIGN KEY/i.test(String(error?.cause?.message||error?.message)))cloudFail(409,'TRANSACTION_PRECONDITION_FAILED');
      throw error;
    }
  }
  stageChanges(b,revision,plan) {
    const statements=[];
    for(const [i,change] of plan.changes.entries()){
      const a=change.after,old=change.before;
      if(!a){
        if(!old || !plan.result?.permanentDeletion)cloudFail(400,'UNSUPPORTED_DELETION');
        statements.push(this.guard('EXISTS(SELECT 1 FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id=? AND revision=?)',...b,old.id,old.revision),
          this.s('INSERT INTO cloud_tombstones(owner_id,continuity_id,id,kind,deleted_revision) VALUES(?,?,?,?,?)',...b,old.id,old.kind,revision),
          this.s('DELETE FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id=?',...b,old.id),
          this.s("INSERT INTO cloud_changes(owner_id,continuity_id,revision,ordinal,entity_id,kind,actor_id,change_type) VALUES(?,?,?,?,?,?,?,'delete')",...b,revision,i,old.id,old.kind,old.actorId));
        continue;
      }
      if(old) {
        statements.push(this.guard('EXISTS(SELECT 1 FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id=? AND revision=?)',...b,a.id,old.revision));
        statements.push(this.s('UPDATE cloud_entities SET actor_id=?,title=?,data_json=?,life=?,revision=? WHERE owner_id=? AND continuity_id=? AND id=?',a.actorId,a.title,canonical(a.data),a.life,a.revision,...b,a.id));
      }else statements.push(this.s('INSERT INTO cloud_entities(owner_id,continuity_id,id,kind,actor_id,title,data_json,life,revision) VALUES(?,?,?,?,?,?,?,?,?)',...b,a.id,a.kind,a.actorId,a.title,canonical(a.data),a.life,a.revision));
      statements.push(this.s("INSERT INTO cloud_changes(owner_id,continuity_id,revision,ordinal,entity_id,kind,actor_id,change_type) VALUES(?,?,?,?,?,?,?,'upsert')",...b,revision,i,a.id,a.kind,a.actorId));
    }
    for(const edge of plan.edges){
      if(edge.remove)statements.push(this.s('DELETE FROM cloud_links WHERE owner_id=? AND continuity_id=? AND from_id=? AND relation=? AND to_id=?',...b,edge.fromId,edge.relation,edge.toId));
      else statements.push(this.s('INSERT INTO cloud_links(owner_id,continuity_id,from_id,relation,to_id) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING',...b,edge.fromId,edge.relation,edge.toId));
    }
    statements.push(this.s('UPDATE cloud_continuities SET revision=? WHERE owner_id=? AND continuity_id=?',revision,...b));
    return statements;
  }
  async terminal(b,commandId,status,c,result,access,now,plan) {
    const evidence=await resultEvidence(this,b,commandId,result,access,plan);
    return [this.s('INSERT INTO cloud_receipts(owner_id,continuity_id,command_id,transaction_id,status,epoch,revision,result_json,access_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)',
      ...b,commandId,crypto.randomUUID(),status,c.epoch,c.revision,evidence.stored,canonical(access),now),...evidence.writes];
  }
  async absoluteEvidence(id,command,continuity,grant) {
    if(command.operation!=='set_actor_ability' || command.epoch!==continuity.epoch)return null;
    const {actorId,ability,score}=command.args;
    if(!cloudAllowed(grant,'character',actorId,true))return null;
    const b=this.key(id),actor=await this.s("SELECT data_json FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id=? AND kind='actor' AND life='active'",...b,actorId).first();
    if(!actor || JSON.parse(actor.data_json).abilities?.[ability]!==score)return null;
    const fingerprint=await sha256(canonical(command.args));
    const row=await this.s(`SELECT r.command_id,r.revision,r.transaction_id,q.request_json,r.access_json FROM cloud_receipts r
      JOIN cloud_requests q USING(owner_id,continuity_id,command_id)
      WHERE r.owner_id=? AND r.continuity_id=? AND r.status='COMMITTED' AND r.epoch=? AND r.revision>?
        AND json_extract(q.request_json,'$.command.operation')='set_actor_ability'
        AND (json_extract(q.request_json,'$.argsHash')=? OR (json_extract(q.request_json,'$.evidenceVersion') IS NULL
          AND json_extract(q.request_json,'$.command.args.actorId')=? AND json_extract(q.request_json,'$.command.args.ability')=?
          AND json_extract(q.request_json,'$.command.args.score') IS ?)) ORDER BY r.revision DESC LIMIT 1`,
        ...b,continuity.epoch,command.expectedRevision,fingerprint,actorId,ability,score).first();
    if(!row)return null;
    const stored=JSON.parse(row.request_json);
    if(stored.evidenceVersion===1?stored.argsHash!==fingerprint:canonical(stored.command.args)!==canonical(command.args))return null;
    assertAccess(grant,JSON.parse(row.access_json));
    return {commandId:row.command_id,transactionId:row.transaction_id,revision:row.revision};
  }
  async retireEquivalent(id,accepted) {
    if(accepted.operation!=='set_actor_ability')return;
    const b=this.key(id),fingerprint=await sha256(canonical(accepted.args));
    const pending=(await this.s(`SELECT command_id,digest,request_json FROM cloud_requests WHERE owner_id=? AND continuity_id=?
      AND status='PENDING_APPROVAL' AND json_extract(request_json,'$.command.operation')='set_actor_ability'
      AND (json_extract(request_json,'$.argsHash')=? OR (json_extract(request_json,'$.evidenceVersion') IS NULL
        AND json_extract(request_json,'$.command.args.actorId')=? AND json_extract(request_json,'$.command.args.ability')=?
        AND json_extract(request_json,'$.command.args.score') IS ?)) ORDER BY submitted_at LIMIT 32`,
      ...b,fingerprint,accepted.args.actorId,accepted.args.ability,accepted.args.score).all()).results;
    for(const row of pending){
      const old=JSON.parse(row.request_json);
      if(old.evidenceVersion===1?old.argsHash!==fingerprint:canonical(old.command.args)!==canonical(accepted.args))continue;
      try{await this.review({role:'owner',ownerId:this.ownerId,credentialId:'sites-owner'},id,row.command_id,row.digest,'reconcile');}
      catch(e){if(!(e instanceof BridgeError))throw e;}
    }
  }
  async submit(principal,id,command) {
    const {grant,continuity:c}=await this.access(principal,id),b=this.key(id);
    const def=validateCloudCommand(command,this.operations),digest=await sha256(canonical(command));
    if(!grant.scopes.includes(def.scope+'.write'))cloudFail(403,'CLOUD_GRANT_DENIED');
    const replay=async()=>{
      const existing=await this.s('SELECT digest,subject FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND command_id=?',...b,command.commandId).first();
      if(!existing)return null;
      if(existing.digest!==digest||existing.subject!==grant.subject)cloudFail(409,'COMMAND_ID_CONFLICT');
      return this.receipt(principal,id,command.commandId);
    };
    const previous=await replay(); if(previous)return previous;
    if(c.epoch!==command.epoch||c.revision!==command.expectedRevision){
      const error=new BridgeError(409,'EXPECTED_REVISION_CONFLICT');
      error.conflict={commandId:command.commandId,epoch:c.epoch,expectedEpoch:command.epoch,actualRevision:c.revision,expectedRevision:command.expectedRevision};
      throw error;
    }
    if(command.replacesCommandId){
      const prior=await this.s('SELECT * FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND command_id=?',...b,command.replacesCommandId).first();
      if(!prior || prior.status!=='CONFLICT'||prior.digest!==command.replacesDigest || (prior.subject!==grant.subject && grant.subject!=='sites-owner'))cloudFail(409,'REPLACEMENT_EVIDENCE_REQUIRED');
      assertAccess(grant,JSON.parse(prior.access_json),true);
      const evidence=await readRequestEvidence(this,b,prior);
      if(evidence.evidenceErased||evidence.command.operation!==command.operation||canonical(evidence.command.args)!==canonical(command.args))cloudFail(409,'MATERIAL_INTENT_CHANGED');
    }
    const now=this.now(),ctx=new CloudPlan(this,b,grant,command,now);
    const plan=ctx.freeze(await def.plan(ctx,command.args));
    if(!plan.access.some(e=>e.write && e.scope===def.scope))cloudFail(403,'COMMAND_SCOPE_UNBOUND');
    const direct=plan.access.filter(e=>e.write).every(e=>grant.auto.includes(e.scope+'.write')) && !def.ownerReview && !command.replacesCommandId;
    const status=direct?'COMMITTED':'PENDING_APPROVAL',access=canonical(plan.access), next=c.revision+1;
    const evidence=await requestEvidence(this,b,command,grant.revision,plan,direct);
    const statements=this.batchGuards(id,c,grant);
    if(command.replacesCommandId){
      statements.push(this.guard("NOT EXISTS(SELECT 1 FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND json_extract(request_json,'$.command.replacesCommandId')=? AND status IN('PENDING_APPROVAL','COMMITTED','SUPERSEDED'))",...b,command.replacesCommandId));
    }
    const windowStart=this.now()-60000;
    statements.push(this.guard('((SELECT count(*) FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND subject=? AND submitted_at>?)<120)',...b,grant.subject,windowStart));
    statements.push(this.s('INSERT INTO cloud_requests(owner_id,continuity_id,command_id,subject,digest,request_json,access_json,status,submitted_at) VALUES(?,?,?,?,?,?,?,?,?)',...b,command.commandId,grant.subject,digest,evidence.stored,access,status,now),...evidence.writes);
    if(direct){
      if(def.beforeCommit)await def.beforeCommit(this,id,command,plan);
      statements.push(...this.stageChanges(b,next,plan),...await this.terminal(b,command.commandId,status,{epoch:c.epoch,revision:next},this.outcome(plan),plan.access,now,plan),...deletionTail(this,b,command.commandId,next,plan));
    }
    try{await this.transact(statements);}catch(e){const concurrent=await replay();if(concurrent)return concurrent;throw e;}
    if(direct)await this.retireEquivalent(id,command);
    return this.receipt(principal,id,command.commandId);
  }
  outcome(plan){return {outcome:plan.result,changes:plan.changes,relationships:plan.edges};}
  async reviewStatus(principal,id) {
    const {grant}=await this.access(principal,id);
    if(principal.role==='connector'||!grant.scopes.includes('approval.read')||!grant.actors.includes('*'))cloudFail(403,'OWNER_INBOX_DENIED');
    const total=await this.s("SELECT count(*) AS n FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND status='PENDING_APPROVAL'",...this.key(id)).first();
    return {actionableCount:total.n};
  }
  async approvals(principal,id,opts={}) {
    this.owner(principal,id);await this.continuity(id);
    const b=this.key(id),limit=opts.limit??30,after=opts.after??'';
    if(!Number.isInteger(limit)||limit<1||limit>50||after&&!validCloudId(after))cloudFail(400,'INVALID_PAGE');
    const rows=(await this.s("SELECT * FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND status='PENDING_APPROVAL' AND command_id>? ORDER BY command_id LIMIT ?",...b,after,limit+1).all()).results;
    const total=await this.s("SELECT count(*) AS n FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND status='PENDING_APPROVAL'",...b).first();
    const requests=await Promise.all(rows.slice(0,limit).map(async r=>({commandId:r.command_id,digest:r.digest,subject:r.subject,
      submittedAt:r.submitted_at,...await readRequestEvidence(this,b,r)})));
    return {actionableCount:total.n,requests,nextAfter:rows.length>limit?rows[limit-1].command_id:null};
  }
  async review(principal,id,commandId,digest,action) {
    this.owner(principal,id);
    if(!['verify','refuse','reconcile'].includes(action)||!validCloudId(commandId)||typeof digest!=='string'||!/^[a-f0-9]{64}$/.test(digest))cloudFail(400,'INVALID_DECISION');
    const b=this.key(id),c=await this.continuity(id);
    const req=await this.s('SELECT * FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND command_id=?',...b,commandId).first();
    if(!req||req.digest!==digest)cloudFail(409,'REVIEW_DIGEST_CONFLICT');
    if(req.status!=='PENDING_APPROVAL')return this.receipt(principal,id,commandId);
    const proposal=await readRequestEvidence(this,b,req);
    if(proposal.evidenceErased)cloudFail(409,'REVIEW_EVIDENCE_UNAVAILABLE');
    const {command,plan,grantRevision}=proposal;
    const access=JSON.parse(req.access_json);let status='REFUSED',reason='OWNER_REFUSED',grant=null,evidence=null;
    if(action!=='refuse'){
      try {grant=await this.grantForSubject(id,req.subject);assertAccess(grant,access,true);}
      catch(e){if(!(e instanceof BridgeError))throw e;reason='SUBMITTER_GRANT_INACTIVE';}
      if(grant){
        if(grant.revision!==grantRevision){status='CONFLICT';reason='SUBMITTER_GRANT_CHANGED';}
        else if(command.epoch!==c.epoch||command.expectedRevision!==c.revision){
          evidence=await this.absoluteEvidence(id,command,c,grant);
          status=evidence?'SUPERSEDED':'CONFLICT';reason=evidence?'ALREADY_SATISFIED':'REVIEW_BASELINE_CHANGED';
        }
        else {status='COMMITTED';reason=null;}
      }
    }
    if(action==='reconcile' && status!=='SUPERSEDED')return this.receipt(principal,id,commandId);
    const checks=this.batchGuards(id,c,(status==='COMMITTED'||status==='SUPERSEDED')?grant:{subject:'sites-owner'});
    checks.push(this.guard("EXISTS(SELECT 1 FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND command_id=? AND digest=? AND status='PENDING_APPROVAL')",...b,commandId,digest));
    const newRevision=c.revision+Number(status==='COMMITTED');
    if(status==='COMMITTED'){
      const validate=this.operations[command.operation]?.beforeCommit;
      if(validate)await validate(this,id,command,plan);
      checks.push(...this.stageChanges(b,newRevision,plan));
    }
    checks.push(this.s('UPDATE cloud_requests SET status=? WHERE owner_id=? AND continuity_id=? AND command_id=?',status,...b,commandId),
      ...await this.terminal(b,commandId,status,{epoch:c.epoch,revision:newRevision},status==='COMMITTED'?this.outcome(plan):{reason,expectedRevision:command.expectedRevision,actualRevision:c.revision,...(evidence?{evidence}:{})},access,this.now(),plan));
    if(status==='COMMITTED')checks.push(...deletionTail(this,b,commandId,newRevision,plan));
    try{await this.transact(checks);}catch(e){const read=await this.receipt(principal,id,commandId);if(read.status!=='PENDING_APPROVAL')return read;throw e;}
    if(status==='COMMITTED')await this.retireEquivalent(id,command);
    return this.receipt(principal,id,commandId);
  }
}
