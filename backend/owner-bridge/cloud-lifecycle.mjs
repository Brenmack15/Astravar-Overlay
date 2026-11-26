import { CLOUD_ID, CLOUD_REVISION, cloudObject, cloudFail, validCloudId } from './cloud-validation.mjs';
import { CloudPlan, cloudAllowed } from './cloud-plan.mjs';
import { purgeCloudMediaObjects } from './cloud-media.mjs';
import { eraseEntityEvidence } from './cloud-evidence.mjs';

const selection=cloudObject({id:CLOUD_ID,kind:CLOUD_ID,revision:{...CLOUD_REVISION,minimum:1}});
const records={type:'array',items:selection,minItems:1,maxItems:24};
const batch=cloudObject({records});
const permanent=cloudObject({records,confirmation:{const:'PERMANENT_DELETE'},acknowledgeExternalBackups:{const:true},
  acknowledgeLegacyReceipts:{type:'boolean'}},['records','confirmation','acknowledgeExternalBackups']);
const mechanicalRelations=new Set(['active_form','concentrates_on','concentration','current_turn','equipped','attuned','active_encounter']);
function isInUse(row) {
  const d=row.data;
  if(row.kind==='actor')return Boolean(d.activeFormId||d.concentrationCastId||d.encounterId);
  if(row.kind==='item_instance')return Boolean(d.attuned||d.operating||d.equipment&&d.equipment!=='packed');
  if(row.kind==='encounter')return d.active===true || ['active','in_progress'].includes(d.status);
  if(row.kind==='effect')return d.enabled===true;
  return false;
}
async function dependencies(ctx,ids) {
  const selected=JSON.stringify(ids),b=ctx.binding;
  const links=(await ctx.service.s(`SELECT from_id,relation,to_id FROM cloud_links WHERE owner_id=? AND continuity_id=?
    AND (from_id IN(SELECT value FROM json_each(?)) OR to_id IN(SELECT value FROM json_each(?))) ORDER BY from_id,relation,to_id LIMIT 257`,
  ...b,selected,selected).all()).results;
  const references=(await ctx.service.s(`SELECT e.id,e.kind,e.title,e.actor_id,e.life,e.data_json FROM cloud_entities e WHERE e.owner_id=? AND e.continuity_id=?
    AND e.id NOT IN(SELECT value FROM json_each(?))
    AND (e.actor_id IN(SELECT value FROM json_each(?)) OR EXISTS(
      SELECT 1 FROM json_tree(e.data_json) j WHERE j.type='text' AND j.atom IN(SELECT value FROM json_each(?))
    )) ORDER BY e.id LIMIT 257`,...b,selected,selected,selected).all()).results;
  if(links.length>256||references.length>256)cloudFail(409,'DEPENDENCY_REVIEW_LIMIT');
  for(const row of references)ctx.require(ctx.service.kinds[row.kind],row.actor_id,false);
  const names=new Map(),endpoints=new Set(links.flatMap(link=>[link.from_id,link.to_id]));
  for(const id of endpoints){
    const end=await ctx.current(id);
    if(!end)cloudFail(409,'INCOMPLETE_DEPENDENCY');
    ctx.require(ctx.service.kinds[end.kind],end.actorId,false);names.set(id,end.title);
  }
  const protectedFields=['activeFormId','concentrationCastId','encounterId','resourcePoolId','chargePoolId'];
  const selectedIds=new Set(ids);
  return {links:links.map(l=>({...l,fromTitle:names.get(l.from_id),toTitle:names.get(l.to_id)})),references:references.map(r=>{
    const d=JSON.parse(r.data_json);
    const gameplayUse=r.life==='active' && (protectedFields.some(f=>selectedIds.has(d[f])) ||
      (r.kind==='actor' && [d.poolIds??[],d.effectIds??[]].some(values=>values.some(id=>selectedIds.has(id)))) ||
      (r.kind==='item_instance' && (selectedIds.has(d.ownerId)||selectedIds.has(d.custodianId))&&(d.attuned||d.operating||d.equipment!=='packed')) ||
      (r.kind==='encounter' && d.active && (d.participants??[]).some(id=>selectedIds.has(id))));
    return {id:r.id,kind:r.kind,title:r.title,life:r.life,actorId:r.actor_id,gameplayUse};
  })};
}
async function legacyCount(ctx,ids) {
  const selected=JSON.stringify(ids), b=ctx.binding;
  const row=await ctx.service.s(`SELECT count(*) AS n FROM cloud_requests q LEFT JOIN cloud_receipts r
    USING(owner_id,continuity_id,command_id) WHERE q.owner_id=? AND q.continuity_id=?
    AND ((json_extract(q.request_json,'$.evidenceVersion') IS NULL AND EXISTS(SELECT 1 FROM json_tree(q.request_json)
      WHERE type='text' AND atom IN(SELECT value FROM json_each(?))))
    OR (r.result_json IS NOT NULL AND json_extract(r.result_json,'$.evidenceVersion') IS NULL AND EXISTS(
      SELECT 1 FROM json_tree(r.result_json) WHERE type='text' AND atom IN(SELECT value FROM json_each(?)))))
  `,...b,selected,selected).first();
  return row.n;
}
async function selectedRows(ctx,args) {
  if(new Set(args.records.map(r=>r.id)).size!==args.records.length)cloudFail(400,'DUPLICATE_SELECTION');
  const rows=[];
  for(const requested of args.records){
    const row=await ctx.get(requested.kind,requested.id);
    if(!row||row.revision!==requested.revision)cloudFail(409,'LIFECYCLE_SELECTION_CHANGED');
    ctx.require('lifecycle',row.actorId);ctx.require(ctx.service.kinds[row.kind],row.actorId);
    rows.push(row);
  }
  return rows;
}
async function archive(ctx,args,restore) {
  const rows=await selectedRows(ctx,args),deps=await dependencies(ctx,rows.map(r=>r.id));
  if(!restore && (rows.some(isInUse)||deps.references.some(r=>r.gameplayUse)||deps.links.some(l=>mechanicalRelations.has(l.relation))))cloudFail(409,'GAMEPLAY_DEPENDENCY_ACTIVE');
  const selected=new Set(rows.map(r=>r.id)),actorIds=new Set(rows.filter(r=>r.kind==='actor').map(r=>r.id));
  if(!restore && deps.references.some(r=>r.life==='active' && actorIds.has(r.actorId)))cloudFail(409,'INCLUDE_ACTIVE_ACTOR_RECORDS');
  for(const row of rows){
    if(restore && row.actorId && row.actorId!==row.id && !selected.has(row.actorId) && (await ctx.get('actor',row.actorId))?.life!=='active')cloudFail(409,'RESTORE_ACTOR_FIRST');
    if(row.life!==(restore?'archived':'active'))cloudFail(409,'LIFECYCLE_SELECTION_CHANGED');
    await ctx.put({...row,life:restore?'active':'archived'});
  }
  return {records:rows.map(r=>({id:r.id,kind:r.kind,previousLife:r.life,life:restore?'active':'archived'})),
    preservedRelationships:deps.links,dependentRecords:deps.references};
}
async function mediaForDeletion(ctx,rows) {
  const ids=JSON.stringify(rows.map(r=>r.id));
  const uploads=JSON.stringify(rows.filter(r=>r.kind==='media_asset').map(r=>r.data.uploadId));
  const reservations=(await ctx.service.s(`SELECT u.upload_id,u.digest,u.state,
    EXISTS(SELECT 1 FROM cloud_entities a WHERE a.owner_id=u.owner_id AND a.continuity_id=u.continuity_id AND a.kind='media_asset'
      AND json_extract(a.data_json,'$.uploadId')=u.upload_id AND a.id NOT IN(SELECT value FROM json_each(?))) AS surviving,
    EXISTS(SELECT 1 FROM cloud_entities a WHERE a.owner_id=u.owner_id AND a.continuity_id=u.continuity_id AND a.kind='media_asset'
      AND json_extract(a.data_json,'$.uploadId')=u.upload_id AND a.id NOT IN(SELECT value FROM json_each(?))
      AND NOT EXISTS(SELECT 1 FROM cloud_links l WHERE l.owner_id=a.owner_id AND l.continuity_id=a.continuity_id AND l.to_id=a.id
        AND l.relation='primary_image' AND l.from_id NOT IN(SELECT value FROM json_each(?)))) AS exclusive
    FROM cloud_media_uploads u WHERE u.owner_id=? AND u.continuity_id=? AND (
      u.target_id IN(SELECT value FROM json_each(?)) OR u.upload_id IN(SELECT value FROM json_each(?)))
    ORDER BY u.upload_id LIMIT 257`,ids,ids,ids,...ctx.binding,ids,uploads).all()).results;
  if(reservations.length>256)cloudFail(409,'DEPENDENCY_REVIEW_LIMIT');
  if(reservations.some(u=>u.state!=='ready'))cloudFail(409,'IMAGE_STORAGE_CHANGE_IN_PROGRESS');
  if(reservations.some(u=>u.exclusive))cloudFail(409,'INCLUDE_EXCLUSIVE_IMAGE_IN_DELETE');
  return {reviewed:reservations.map(u=>u.upload_id),remove:reservations.filter(u=>!u.surviving).map(u=>({id:u.upload_id,digest:u.digest}))};
}
async function permanentDelete(ctx,args) {
  const rows=await selectedRows(ctx,args),ids=rows.map(r=>r.id), selected=new Set(ids), deps=await dependencies(ctx,ids);
  if(rows.some(isInUse)||deps.links.some(e=>mechanicalRelations.has(e.relation)))cloudFail(409,'GAMEPLAY_DEPENDENCY_ACTIVE');
  if(deps.references.length) cloudFail(409,'DEPENDENT_RECORDS_RETAINED');
  const legacy=await legacyCount(ctx,ids);
  if(legacy && !args.acknowledgeLegacyReceipts)cloudFail(409,'LEGACY_IMMUTABLE_RECEIPTS_REQUIRE_REVIEW');
  // A second pending command has a frozen proposal over this identity. Resolve
  // it first so the owner can inspect/refuse its exact payload unambiguously.
  const pending=await ctx.service.s(`SELECT q.command_id FROM cloud_requests q WHERE q.owner_id=? AND q.continuity_id=?
    AND q.status='PENDING_APPROVAL' AND q.command_id<>? AND (
      EXISTS(SELECT 1 FROM cloud_evidence_subjects s WHERE s.owner_id=q.owner_id AND s.continuity_id=q.continuity_id
        AND s.command_id=q.command_id AND s.entity_id IN(SELECT value FROM json_each(?)))
      OR (json_extract(q.request_json,'$.evidenceVersion') IS NULL AND EXISTS(SELECT 1 FROM json_tree(q.request_json)
        WHERE type='text' AND atom IN(SELECT value FROM json_each(?))))) LIMIT 1`,
    ...ctx.binding,ctx.commandId,JSON.stringify(ids),JSON.stringify(ids)).first();
  if(pending)cloudFail(409,'PENDING_DEPENDENCY_REVIEW');
  const media=await mediaForDeletion(ctx,rows);
  for(const link of deps.links){
    if(!selected.has(link.from_id) && selected.has(link.to_id))cloudFail(409,'DEPENDENT_RECORDS_RETAINED');
    if(link.relation==='primary_image' && !selected.has(link.to_id)){
      const surviving=await ctx.service.s(`SELECT 1 AS n FROM cloud_links WHERE owner_id=? AND continuity_id=?
        AND to_id=? AND relation='primary_image' AND from_id NOT IN(SELECT value FROM json_each(?)) LIMIT 1`,
      ...ctx.binding,link.to_id,JSON.stringify(ids)).first();
      if(!surviving)cloudFail(409,'INCLUDE_EXCLUSIVE_IMAGE_IN_DELETE');
    }
    await ctx.unlink(link.from_id,link.relation,link.to_id);
  }
  for(const row of rows)ctx.changes.set(row.id,{before:row,after:null});
  return {permanentDeletion:true,records:rows.map(r=>({id:r.id,kind:r.kind,revision:r.revision})),
    relationshipsRemoved:deps.links.length,media,legacyImmutableReceipts:legacy,externalBackupsMayRetainData:true};
}
export const CLOUD_LIFECYCLE_OPERATIONS=Object.freeze({
  archive_records:{scope:'lifecycle',ownerReview:true,schema:batch,plan:(ctx,args)=>archive(ctx,args,false)},
  restore_records:{scope:'lifecycle',ownerReview:true,schema:batch,plan:(ctx,args)=>archive(ctx,args,true)},
  permanently_delete_records:{scope:'lifecycle',ownerReview:true,schema:permanent,plan:permanentDelete},
});

// This hook runs AFTER the terminal receipt/evidence statements in the same
// SQL batch, and only on a committed, reviewed permanent deletion.
export function deletionTail(service,b,commandId,revision,plan) {
  const rows=plan.changes.filter(c=>!c.after).map(c=>c.before);
  if(!rows.length)return [];
  const selection=JSON.stringify(rows.map(r=>r.id));
  const writes=[service.guard(`NOT EXISTS(SELECT 1 FROM cloud_requests q WHERE q.owner_id=? AND q.continuity_id=?
    AND q.status='PENDING_APPROVAL' AND q.command_id<>? AND (
      EXISTS(SELECT 1 FROM cloud_evidence_subjects s WHERE s.owner_id=q.owner_id AND s.continuity_id=q.continuity_id
        AND s.command_id=q.command_id AND s.entity_id IN(SELECT value FROM json_each(?)))
      OR (json_extract(q.request_json,'$.evidenceVersion') IS NULL AND EXISTS(SELECT 1 FROM json_tree(q.request_json)
        WHERE type='text' AND atom IN(SELECT value FROM json_each(?))))))`,...b,commandId,selection,selection)];
  const media=plan.result.media;
  const selectedUploads=JSON.stringify(rows.filter(r=>r.kind==='media_asset').map(r=>r.data.uploadId));
  // Upload staging leaves the gameplay revision untouched. Recheck this part
  // of the reviewed dependency set inside the commit that creates tombstones.
  writes.push(service.guard(`NOT EXISTS(SELECT 1 FROM cloud_media_uploads WHERE owner_id=? AND continuity_id=?
    AND (target_id IN(SELECT value FROM json_each(?)) OR upload_id IN(SELECT value FROM json_each(?)))
    AND (state<>'ready' OR upload_id NOT IN(SELECT value FROM json_each(?)))) AND
    (SELECT count(*) FROM cloud_media_uploads WHERE owner_id=? AND continuity_id=? AND upload_id IN(SELECT value FROM json_each(?)))=?`,
    ...b,selection,selectedUploads,JSON.stringify(media.reviewed),...b,JSON.stringify(media.reviewed),media.reviewed.length));
  for(const row of rows)writes.push(eraseEntityEvidence(service,b,row.id));
  for(const {id:u,digest} of media.remove){
    const unused=`NOT EXISTS(SELECT 1 FROM cloud_entities WHERE owner_id=? AND continuity_id=?
      AND kind='media_asset' AND json_extract(data_json,'$.uploadId')=?)`;
    writes.push(service.s('INSERT INTO cloud_media_gc(owner_id,continuity_id,command_id,upload_id,digest,state) SELECT ?,?,?,?,?,\'pending\' WHERE '+unused+
      ' ON CONFLICT(owner_id,continuity_id,command_id,upload_id) DO NOTHING',...b,commandId,u,digest,...b,u));
    writes.push(service.s("UPDATE cloud_media_uploads SET state='cleaning' WHERE owner_id=? AND continuity_id=? AND upload_id=? AND "+unused,...b,u,...b,u));
  }
  return writes;
}

export async function completeMediaCleanup(service,b,commandId) {
  const jobs=(await service.s('SELECT upload_id,digest,state FROM cloud_media_gc WHERE owner_id=? AND continuity_id=? AND command_id=?',...b,commandId).all()).results;
  for(const job of jobs){
    if(job.state==='removed')continue;
    const unused=await service.s(`SELECT NOT EXISTS(SELECT 1 FROM cloud_entities WHERE owner_id=? AND continuity_id=?
      AND kind='media_asset' AND json_extract(data_json,'$.uploadId')=?) AS ok`,...b,job.upload_id).first();
    if(!unused.ok)continue;
    try {
      await purgeCloudMediaObjects(service,b,job.upload_id,job.digest);
      await service.transact([service.s("DELETE FROM cloud_media_uploads WHERE owner_id=? AND continuity_id=? AND upload_id=? AND state='cleaning'",...b,job.upload_id),
        service.s("UPDATE cloud_media_gc SET state='removed' WHERE owner_id=? AND continuity_id=? AND command_id=? AND upload_id=?",...b,commandId,job.upload_id)]);
      job.state='removed';
    }catch { /* A receipt retry resumes the durable cleanup intent. */ }
  }
  return jobs.length?{status:jobs.every(j=>j.state==='removed')?'COMPLETE':'PENDING',assets:jobs.length}:null;
}

export async function cloudArchiveView(service,principal,id,opts={}) {
  const {grant,continuity:c}=await service.access(principal,id),b=service.key(id);
  if(!grant.scopes.includes('lifecycle.read'))cloudFail(403,'CLOUD_GRANT_DENIED');
  if(opts.id){
    if(!validCloudId(opts.id))cloudFail(400,'INVALID_ENTITY_ID');
    const ctx=new CloudPlan(service,b,grant,{commandId:'inspect-dependencies',operation:'inspect_dependencies'},service.now());
    const row=await ctx.current(opts.id);
    if(!row||!cloudAllowed(grant,'lifecycle',row.actorId)||!cloudAllowed(grant,service.kinds[row.kind],row.actorId))cloudFail(404,'RECORD_UNAVAILABLE');
    const deps=await dependencies(ctx,[row.id]),legacy=await legacyCount(ctx,[row.id]);
    const again=await service.access(principal,id);
    if(again.grant.revision!==grant.revision||again.continuity.revision!==c.revision||again.continuity.epoch!==c.epoch)cloudFail(409,'SNAPSHOT_CHANGED');
    return {epoch:c.epoch,revision:c.revision,grantRevision:grant.revision,record:row,dependencies:deps,legacyImmutableReceipts:legacy,
      inUse:isInUse(row)||deps.references.some(r=>r.gameplayUse)||deps.links.some(l=>mechanicalRelations.has(l.relation))};
  }
  const limit=opts.limit??25,after=opts.after??'',query=opts.query??'';
  if(!Number.isSafeInteger(limit)||limit<1||limit>100||(after&&!validCloudId(after))||typeof query!=='string'||query.length>120)cloudFail(400,'INVALID_PAGE');
  if(after && (opts.atEpoch!==c.epoch||opts.atRevision!==c.revision||opts.grantRevision!==grant.revision))cloudFail(409,'SNAPSHOT_CHANGED');
  const kinds=Object.keys(service.kinds).filter(k=>grant.scopes.includes(service.kinds[k]+'.read')&&(!opts.kind||opts.kind===k));
  const rows=(await service.s(`SELECT id,kind,actor_id,title,revision,life FROM cloud_entities WHERE owner_id=? AND continuity_id=?
    AND life='archived' AND kind IN(SELECT value FROM json_each(?)) AND (?=1 OR actor_id IN(SELECT value FROM json_each(?)))
    AND id>? AND instr(lower(title),lower(?))>0 ORDER BY id LIMIT ?`,...b,JSON.stringify(kinds),Number(grant.actors.includes('*')),JSON.stringify(grant.actors),after,query,limit+1).all()).results;
  const again=await service.access(principal,id);
  if(again.grant.revision!==grant.revision||again.continuity.revision!==c.revision||again.continuity.epoch!==c.epoch)cloudFail(409,'SNAPSHOT_CHANGED');
  return {epoch:c.epoch,revision:c.revision,grantRevision:grant.revision,records:rows.slice(0,limit),nextAfter:rows.length>limit?rows[limit-1].id:null};
}
