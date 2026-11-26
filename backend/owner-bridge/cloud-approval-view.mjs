import { cloudFail, validCloudId } from './cloud-validation.mjs';
import { cloudAllowed, cloudEntity } from './cloud-plan.mjs';
import { readRequestEvidence, readResultEvidence } from './cloud-evidence.mjs';

function inboxAccess(principal,grant){
  if(!['device','owner'].includes(principal.role) || !grant.scopes.includes('approval.read') || !grant.actors.includes('*'))
    cloudFail(403,'OWNER_INBOX_DENIED');
}
function visibleProposal(service,grant){
  const kinds=Object.keys(service.kinds).filter(k=>grant.scopes.includes(service.kinds[k]+'.read'));
  const hidden="(e.kind NOT IN(SELECT value FROM json_each(?)) OR (e.life='archived' AND ?=0))";
  const reads=JSON.stringify(grant.scopes),visible=JSON.stringify(kinds),archive=Number(grant.scopes.includes('lifecycle.read'));
  return {
    sql:`json_array_length(q.access_json)>0 AND NOT EXISTS(SELECT 1 FROM json_each(q.access_json) a
      WHERE a.type<>'object' OR json_type(a.value,'$.scope') IS NOT 'text' OR
        json_extract(a.value,'$.scope')||'.read' NOT IN(SELECT value FROM json_each(?)))
      AND NOT EXISTS(SELECT 1 FROM cloud_evidence_subjects s JOIN cloud_entities e
        ON e.owner_id=s.owner_id AND e.continuity_id=s.continuity_id AND e.id=s.entity_id
        WHERE s.owner_id=q.owner_id AND s.continuity_id=q.continuity_id AND s.command_id=q.command_id AND s.part='request' AND `+hidden+`)
      AND NOT EXISTS(SELECT 1 FROM json_each(q.request_json,'$.plan.changes') prior JOIN cloud_entities e
        ON e.owner_id=q.owner_id AND e.continuity_id=q.continuity_id AND
          e.id=coalesce(json_extract(prior.value,'$.after.id'),json_extract(prior.value,'$.before.id'))
        WHERE `+hidden+')',
    values:[reads,visible,archive,visible,archive],
  };
}
function summary(r){
  return {commandId:r.command_id,digest:r.digest,operation:r.operation,subject:r.subject,submittedAt:r.submitted_at,readiness:r.readiness};
}
function epochStamp(c,grant){return {epoch:c.epoch,revision:c.revision,grantRevision:grant.revision};}
async function confirm(service,principal,id,grant,c){
  const current=await service.access(principal,id);inboxAccess(principal,current.grant);
  if(current.grant.revision!==grant.revision || current.continuity.epoch!==c.epoch || current.continuity.revision!==c.revision)
    cloudFail(409,'SNAPSHOT_CHANGED');
}
export async function cloudReviewInbox(service,principal,id,mode,opts={}){
  const {grant,continuity:c}=await service.access(principal,id),b=service.key(id);
  inboxAccess(principal,grant);
  if(!['count','list','detail'].includes(mode))cloudFail(400,'INVALID_REVIEW_VIEW');
  const vis=visibleProposal(service,grant),stamp=epochStamp(c,grant);
  const now=service.now(),base=' FROM ('+service.pendingRequests()+') q WHERE '+vis.sql,bindings=[now,now,...b,...vis.values];
  if(mode==='count'||mode==='list'){
    const limit=opts.limit??20,after=opts.after??'';
    if(!Number.isSafeInteger(limit)||limit<1||limit>50||(after&&!validCloudId(after)))cloudFail(400,'INVALID_PAGE');
    const countSql="count(*) AS pendingCount,count(CASE WHEN readiness='READY' THEN 1 END) AS actionableCount";
    if(mode==='count'){
      const total=await service.s('SELECT '+countSql+base,...bindings).first();
      await confirm(service,principal,id,grant,c);
      return {...stamp,...total};
    }
    // Count and page share one D1 statement, including pending changes that
    // do not advance the campaign revision.
    const total=await service.s('WITH permitted AS (SELECT q.*'+base+
      ') SELECT '+countSql+`,(SELECT json_group_array(json_object('command_id',command_id,'digest',digest,'subject',subject,
        'submitted_at',submitted_at,'readiness',readiness,'operation',json_extract(request_json,'$.command.operation')))
       FROM (SELECT * FROM permitted WHERE command_id>? ORDER BY command_id LIMIT ?)) AS page FROM permitted`,
      ...bindings,after,limit+1).first();
    const rows=JSON.parse(total.page);
    await confirm(service,principal,id,grant,c);
    return {...stamp,actionableCount:total.actionableCount,pendingCount:total.pendingCount,requests:rows.slice(0,limit).map(summary),
      nextAfter:rows.length>limit?rows[limit-1].command_id:null};
  }
  if(!validCloudId(opts.commandId)||typeof opts.digest!=='string'||!/^[0-9a-f]{64}$/.test(opts.digest))cloudFail(400,'EXACT_REVIEW_REQUIRED');
  // The same visibility predicate protects counts, summaries and exact lookup.
  const row=await service.s('SELECT q.* FROM cloud_requests q WHERE q.owner_id=? AND q.continuity_id=? AND q.command_id=? AND q.digest=? AND '+vis.sql,
    ...b,opts.commandId,opts.digest,...vis.values).first();
  if(!row)cloudFail(404,'REVIEW_UNAVAILABLE');
  const parsed=JSON.parse(row.request_json),proposal=await readRequestEvidence(service,b,row);
  if(row.status!=='PENDING_APPROVAL'){
    if(!parsed.reviewRequired && !proposal.plan && !['REFUSED','CONFLICT','SUPERSEDED'].includes(row.status))cloudFail(404,'REVIEW_UNAVAILABLE');
    const terminal=await service.s('SELECT * FROM cloud_receipts WHERE owner_id=? AND continuity_id=? AND command_id=?',...b,row.command_id).first();
    if(!terminal)cloudFail(503,'CLOUD_EVIDENCE_INCOMPLETE');
    const result=await readResultEvidence(service,b,terminal);
    await confirm(service,principal,id,grant,c);
    return {...stamp,commandId:row.command_id,digest:row.digest,operation:parsed.command.operation,status:terminal.status,reason:result.reason??null,
      receipt:{transactionId:terminal.transaction_id,epoch:terminal.epoch,revision:terminal.revision}};
  }
  const pending=await service.s("SELECT q.command_id,q.digest,q.subject,q.submitted_at,q.readiness,json_extract(q.request_json,'$.command.operation') AS operation"+base+
    ' AND q.command_id=? AND q.digest=?',...bindings,row.command_id,row.digest).first();
  if(!pending)cloudFail(409,'SNAPSHOT_CHANGED');
  const plan=proposal.plan;
  if(!plan)cloudFail(409,'REVIEW_EVIDENCE_UNAVAILABLE');
  const ids=plan.changes.map(change=>(change.after??change.before).id);
  const actual=(await service.s('SELECT * FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id IN(SELECT value FROM json_each(?))',...b,JSON.stringify(ids)).all()).results;
  const current={};
  for(const item of actual){
    if(!cloudAllowed(grant,service.kinds[item.kind],item.actor_id,false) || item.life==='archived'&&!cloudAllowed(grant,'lifecycle',item.actor_id,false))
      cloudFail(404,'REVIEW_UNAVAILABLE');
    // IDs are data and may coincide with JavaScript inherited property names.
    Object.defineProperty(current,item.id,{value:cloudEntity(item),enumerable:true});
  }
  await confirm(service,principal,id,grant,c);
  const last=await service.s('SELECT status FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND command_id=?',...b,row.command_id).first();
  if(last?.status!=='PENDING_APPROVAL')cloudFail(409,'SNAPSHOT_CHANGED');
  const reason=service.operations[proposal.command.operation]?.ownerReview?'OWNER_CONFIRMATION_REQUIRED':'AUTO_SYNC_REVIEW_REQUIRED';
  return {...stamp,...summary(pending),status:'PENDING_APPROVAL',reviewReason:reason,command:proposal.command,
    plan:{changes:plan.changes,edges:plan.edges,result:plan.result,access:plan.access},current};
}
