import { canonical, sha256 } from './core.mjs';
import { cloudFail, validCloudId, cloudObject, CLOUD_ID } from './cloud-validation.mjs';
import { cloudAllowed } from './cloud-plan.mjs';
import { MEDIA_MAX_BYTES, boundedMediaBody, inspectMediaPng, mediaDigest, mediaHex } from './cloud-media-codec.mjs';

export const CLOUD_MEDIA_KINDS = Object.freeze({media_asset:'media'});
const targetKinds = new Set(['actor','form','world_entity','item_definition','item_instance','invention_project','attack','attack_definition','blueprint_version']);
const shaPattern = /^[a-f0-9]{64}$/;
const ageLimit = 7 * 24 * 60 * 60 * 1000;
const headers = {
  'Cache-Control':'private, no-store',
  'Content-Security-Policy':"default-src 'none'; sandbox",
  'X-Content-Type-Options':'nosniff',
  'Referrer-Policy':'no-referrer',
};
function unavailable() { cloudFail(404,'IMAGE_UNAVAILABLE'); }

function storage(service) {
  if (!service.mediaBucket) cloudFail(503,'PRIVATE_IMAGE_STORAGE_UNAVAILABLE');
  return service.mediaBucket;
}
function requireTarget(service, grant, target, write) {
  if (!target || !targetKinds.has(target.kind) || target.life!=='active' ||
      !cloudAllowed(grant,service.kinds[target.kind],target.actor_id,false) ||
      !cloudAllowed(grant,'media',target.actor_id,false) ||
      (write && (!cloudAllowed(grant,service.kinds[target.kind],target.actor_id,true) ||
          !cloudAllowed(grant,'media',target.actor_id,true)))) unavailable();
}
async function targetById(service, b, id) {
  return service.s('SELECT id,kind,actor_id,revision,life FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id=?',...b,id).first();
}
async function uploadById(service, b, id) {
  return service.s('SELECT * FROM cloud_media_uploads WHERE owner_id=? AND continuity_id=? AND upload_id=?',...b,id).first();
}
async function keys(binding, uploadId, digest) {
  // No caller or external URL selects a bucket key. Each reservation has a
  // private namespace. Reusing its reviewed asset shares the same object pair.
  const root='ascension/media/'+await sha256(canonical([...binding,uploadId,digest]));
  return {original:root+'/original.png',thumbnail:root+'/thumbnail.png'};
}
function candidatePaths(id,uploadId) {
  const prefix='/v3/continuities/'+encodeURIComponent(id)+'/media/uploads/'+encodeURIComponent(uploadId);
  return {original:prefix+'/original',thumbnail:prefix+'/thumbnail'};
}
function assetInfo(meta,variant) {
  return variant==='thumbnail' ? [meta.thumbnailSha256,meta.thumbnailByteLength] : [meta.sha256,meta.byteLength];
}
async function checkObject(bucket,key,hash,bytes) {
  const object=await bucket.head(key);
  if (!object || object.size!==bytes) cloudFail(503,'IMAGE_OBJECT_UNAVAILABLE');
  if (object.checksums?.sha256 && mediaHex(object.checksums.sha256)===hash) return;
  // Some restored object stores omit upload-time checksums. Verify bounded bytes.
  const content=await bucket.get(key);
  if (!content || content.size!==bytes ||
      await mediaDigest(await boundedMediaBody(content.body,bytes))!==hash) cloudFail(503,'IMAGE_OBJECT_INTEGRITY');
}
async function readObject(service,b,uploadId,meta,variant) {
  const key=(await keys(b,uploadId,meta.sha256))[variant], [hash,size]=assetInfo(meta,variant);
  const object=await storage(service).get(key);
  if(!object || object.size!==size)cloudFail(503,'IMAGE_OBJECT_UNAVAILABLE');
  const bytes=await boundedMediaBody(object.body,size);
  if(bytes.length!==size || await mediaDigest(bytes)!==hash)cloudFail(503,'IMAGE_OBJECT_INTEGRITY');
  return new Response(bytes,{headers:{...headers,'Content-Type':'image/png','Content-Length':String(bytes.length),
    'X-Astravar-Content-SHA256':hash,'Content-Disposition':'inline'}});
}
function uploadResult(continuityId,uploadId,meta,target) {
  return {status:'CANDIDATE_READY',uploadId,sha256:meta.sha256,byteLength:meta.byteLength,mime:meta.mime,
    width:meta.width,height:meta.height,targetId:target.id,targetKind:target.kind,preview:candidatePaths(continuityId,uploadId)};
}
async function putUpload(request,service,principal,id,uploadId) {
  const bucket=storage(service),url=new URL(request.url),kind=url.searchParams.get('targetKind'),targetId=url.searchParams.get('targetId');
  if(!targetKinds.has(kind)||!validCloudId(targetId))cloudFail(400,'INVALID_IMAGE_TARGET');
  const {grant,continuity}=await service.access(principal,id),b=service.key(id);
  const target=await targetById(service,b,targetId);
  requireTarget(service,grant,target,true);
  if(target.kind!==kind)unavailable();
  if(request.headers.get('content-type')?.split(';')[0].trim().toLowerCase()!=='image/png')cloudFail(415,'PNG_IMAGE_REQUIRED');
  const expected=request.headers.get('x-astravar-content-sha256');
  if(!shaPattern.test(expected??''))cloudFail(400,'IMAGE_DIGEST_REQUIRED');
  const length=request.headers.get('content-length');
  if(length && (!/^\d+$/.test(length)||Number(length)>MEDIA_MAX_BYTES))cloudFail(413,'IMAGE_SIZE_LIMIT');
  let prior=await uploadById(service,b,uploadId);
  function match(row) {
    if(!row || row.subject!==grant.subject || row.target_id!==targetId || row.target_kind!==kind ||
        row.target_revision!==target.revision || row.epoch!==continuity.epoch || row.actor_id!==target.actor_id ||
        row.digest!==expected || row.state==='cleaning' || row.expires_at<=service.now())cloudFail(409,'IMAGE_UPLOAD_CONFLICT');
  }
  if(prior)match(prior);
  const bytes=await boundedMediaBody(request.body,MEDIA_MAX_BYTES);
  if(length && Number(length)!==bytes.length)cloudFail(400,'IMAGE_LENGTH_MISMATCH');
  if(await mediaDigest(bytes)!==expected)cloudFail(422,'IMAGE_DIGEST_MISMATCH');
  const {meta,thumbnail}=await inspectMediaPng(bytes), metadata=canonical(meta);
  let createdHere=false;
  if(!prior){
    const now=service.now();
    try {
      await service.transact([...service.batchGuards(id,continuity,grant),
        service.s(`INSERT INTO cloud_media_uploads
          (owner_id,continuity_id,upload_id,subject,epoch,target_id,target_kind,target_revision,actor_id,digest,metadata_json,state,created_at,expires_at)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,'uploading',?,?)`,
          ...b,uploadId,grant.subject,continuity.epoch,targetId,kind,target.revision,target.actor_id,expected,metadata,now,now+ageLimit)]);
      createdHere=true;
    } catch(error) {
      if(!await uploadById(service,b,uploadId))throw error;
    }
    prior=await uploadById(service,b,uploadId);match(prior);
  }
  if(prior.metadata_json!==metadata)cloudFail(409,'IMAGE_METADATA_CONFLICT');
  const objectKeys=await keys(b,uploadId,expected);
  try {
    // Only the request that won the SQL reservation writes objects. A retry
    // can verify and publish a fully uploaded pair after a lost response.
    if(createdHere){
      await bucket.put(objectKeys.original,bytes,{sha256:expected,httpMetadata:{contentType:'image/png'}});
      await bucket.put(objectKeys.thumbnail,thumbnail,{sha256:meta.thumbnailSha256,httpMetadata:{contentType:'image/png'}});
    } else if(prior.state==='uploading' && (!await bucket.head(objectKeys.original)||!await bucket.head(objectKeys.thumbnail))){
      cloudFail(409,'IMAGE_UPLOAD_IN_PROGRESS');
    }
    await checkObject(bucket,objectKeys.original,expected,meta.byteLength);
    await checkObject(bucket,objectKeys.thumbnail,meta.thumbnailSha256,meta.thumbnailByteLength);
    if(prior.state!=='ready')await service.transact([...service.batchGuards(id,continuity,grant),
      service.guard("EXISTS(SELECT 1 FROM cloud_media_uploads WHERE owner_id=? AND continuity_id=? AND upload_id=? AND state IN('ready','uploading') AND digest=?)",...b,uploadId,expected),
      service.s("UPDATE cloud_media_uploads SET state='ready' WHERE owner_id=? AND continuity_id=? AND upload_id=?",...b,uploadId)]);
  }catch(error){
    if(createdHere){
      try{
        await service.transact([service.guard("EXISTS(SELECT 1 FROM cloud_media_uploads WHERE owner_id=? AND continuity_id=? AND upload_id=? AND state='uploading')",...b,uploadId),
          service.s("UPDATE cloud_media_uploads SET state='cleaning' WHERE owner_id=? AND continuity_id=? AND upload_id=?",...b,uploadId)]);
        await purgeCloudMediaObjects(service,b,uploadId,expected);
        await service.s("DELETE FROM cloud_media_uploads WHERE owner_id=? AND continuity_id=? AND upload_id=? AND state='cleaning'",...b,uploadId).run();
      }catch{ /* Owner cleanup can resume a retained reservation after an outage. */ }
    }
    throw error;
  }
  return Response.json(uploadResult(id,uploadId,meta,target),{headers});
}

async function checkUploadObjects(service,id,command,plan) {
  const {args}=command;
  const b=service.key(id),row=await uploadById(service,b,args.uploadId);
  if(!row || row.state!=='ready' || row.expires_at<=service.now() || row.digest!==args.sha256 || row.target_id!==args.targetId ||
     row.target_kind!==args.targetKind || row.epoch!==command.epoch)cloudFail(409,'IMAGE_CANDIDATE_CHANGED');
  const reviewed=await service.s(`SELECT requested_at FROM cloud_media_previews WHERE owner_id=? AND continuity_id=?
    AND upload_id=? AND digest=? AND target_id=? AND target_kind=? AND target_revision=?`,...b,args.uploadId,
    args.sha256,args.targetId,args.targetKind,row.target_revision).first();
  if(!reviewed)cloudFail(409,'OWNER_IMAGE_PREVIEW_REQUIRED');
  const meta=JSON.parse(row.metadata_json),record=plan.changes.find(change=>change.after?.id===args.assetId)?.after;
  if(!record || record.data.sha256!==row.digest || record.data.thumbnailSha256!==meta.thumbnailSha256)cloudFail(409,'IMAGE_CANDIDATE_CHANGED');
  const objectKeys=await keys(b,args.uploadId,meta.sha256),bucket=storage(service);
  await checkObject(bucket,objectKeys.original,meta.sha256,meta.byteLength);
  await checkObject(bucket,objectKeys.thumbnail,meta.thumbnailSha256,meta.thumbnailByteLength);
}
async function primaryEdge(ctx,target,asset) {
  const prior=await ctx.service.s("SELECT to_id FROM cloud_links WHERE owner_id=? AND continuity_id=? AND from_id=? AND relation='primary_image'",...ctx.binding,target.id).first();
  if(prior?.to_id && prior.to_id!==asset.id)await ctx.unlink(target.id,'primary_image',prior.to_id);
  await ctx.link(target.id,'primary_image',asset.id);
  return {targetId:target.id,targetKind:target.kind,assetId:asset.id,sha256:asset.data.sha256,previousAssetId:prior?.to_id??null};
}
async function assignImage(ctx,args) {
  const target=await ctx.get(args.targetKind,args.targetId);
  if(!target||target.life!=='active')unavailable();
  ctx.require('media',target.actorId);
  const row=await uploadById(ctx.service,ctx.binding,args.uploadId),id=ctx.binding[1];
  if(!row || row.state!=='ready' || row.expires_at<=ctx.now || row.digest!==args.sha256 ||
      row.target_id!==target.id || row.target_kind!==target.kind || row.target_revision!==target.revision ||
      row.actor_id!==target.actorId || (ctx.grant.subject!=='sites-owner'&&row.subject!==ctx.grant.subject))cloudFail(409,'IMAGE_CANDIDATE_CHANGED');
  if(await ctx.get('media_asset',args.assetId))cloudFail(409,'IMAGE_ASSET_ID_IN_USE');
  const meta=JSON.parse(row.metadata_json);
  const asset=await ctx.put({id:args.assetId,kind:'media_asset',actorId:target.actorId,title:'Primary image',
    data:{...meta,uploadId:args.uploadId},life:'active'});
  const change=await primaryEdge(ctx,target,asset);
  return {...change,candidate: candidatePaths(id,args.uploadId)};
}

export const CLOUD_MEDIA_OPERATIONS=Object.freeze({
  assign_primary_image:{scope:'media',ownerReview:true,beforeCommit:checkUploadObjects,
    schema:cloudObject({uploadId:CLOUD_ID,assetId:CLOUD_ID,targetId:CLOUD_ID,
      targetKind:{type:'string',enum:[...targetKinds]},sha256:{type:'string',pattern:'^[a-f0-9]{64}$'}}),
    plan:assignImage},
});

async function shownCandidate(service,principal,id,uploadId,variant) {
  const b=service.key(id),{grant,continuity}=await service.access(principal,id),row=await uploadById(service,b,uploadId);
  if(!row || row.state!=='ready' || (grant.subject!=='sites-owner'&&grant.subject!==row.subject))unavailable();
  const target=await targetById(service,b,row.target_id);
  requireTarget(service,grant,target,false);
  if(target.kind!==row.target_kind||target.actor_id!==row.actor_id)unavailable();
  const response=await readObject(service,b,uploadId,JSON.parse(row.metadata_json),variant);
  const again=await service.access(principal,id);
  if(again.grant.revision!==grant.revision||again.continuity.epoch!==continuity.epoch||again.continuity.revision!==continuity.revision)cloudFail(409,'SNAPSHOT_CHANGED');
  if(grant.subject==='sites-owner' && variant==='original'){
    // Receipt of a preview is recorded separately from the explicit owner decision.
    await service.s(`INSERT INTO cloud_media_previews
      (owner_id,continuity_id,upload_id,digest,target_id,target_kind,target_revision,requested_at)
      VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(owner_id,continuity_id,upload_id) DO UPDATE SET requested_at=excluded.requested_at`,
      ...b,uploadId,row.digest,row.target_id,row.target_kind,row.target_revision,service.now()).run();
  }
  return response;
}
async function primaryImage(service,principal,id,targetId,variant) {
  const b=service.key(id),{grant,continuity}=await service.access(principal,id),target=await targetById(service,b,targetId);
  requireTarget(service,grant,target,false);
  const asset=await service.s(`SELECT e.actor_id,e.data_json FROM cloud_links l JOIN cloud_entities e
    ON e.owner_id=l.owner_id AND e.continuity_id=l.continuity_id AND e.id=l.to_id
    WHERE l.owner_id=? AND l.continuity_id=? AND l.from_id=? AND l.relation='primary_image' AND e.kind='media_asset' AND e.life='active'`,...b,targetId).first();
  if(!asset || !cloudAllowed(grant,'media',asset.actor_id,false))unavailable();
  const meta=JSON.parse(asset.data_json);
  const result=await readObject(service,b,meta.uploadId,meta,variant);
  const again=await service.access(principal,id);
  if(again.grant.revision!==grant.revision||again.continuity.epoch!==continuity.epoch||again.continuity.revision!==continuity.revision)cloudFail(409,'SNAPSHOT_CHANGED');
  return result;
}
async function cleanup(service,principal,id,uploadId) {
  service.owner(principal,id);
  const b=service.key(id),{grant,continuity}=await service.access(principal,id),row=await uploadById(service,b,uploadId);
  if(!row)unavailable();
  if(row.state==='uploading' && row.expires_at>service.now())cloudFail(409,'IMAGE_UPLOAD_IN_PROGRESS');
  const deps=`NOT EXISTS(SELECT 1 FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND kind='media_asset'
    AND json_extract(data_json,'$.uploadId')=?)
    AND NOT EXISTS(SELECT 1 FROM cloud_requests WHERE owner_id=? AND continuity_id=? AND status='PENDING_APPROVAL'
    AND json_extract(request_json,'$.command.operation')='assign_primary_image' AND json_extract(request_json,'$.command.args.uploadId')=?)`;
  await service.transact([...service.batchGuards(id,continuity,grant),service.guard(deps,...b,uploadId,...b,uploadId),
    service.s("UPDATE cloud_media_uploads SET state='cleaning' WHERE owner_id=? AND continuity_id=? AND upload_id=?",...b,uploadId)]);
  const objectKeys=await keys(b,uploadId,row.digest);
  await storage(service).delete([objectKeys.original,objectKeys.thumbnail]);
  await service.transact([service.guard(deps,...b,uploadId,...b,uploadId),
    service.s("DELETE FROM cloud_media_uploads WHERE owner_id=? AND continuity_id=? AND upload_id=? AND state='cleaning'",...b,uploadId)]);
  return Response.json({status:'REMOVED',uploadId},{headers});
}

/** Called only after normal owner/device/MCP authentication and owner CSRF checks. */
export async function handleCloudMedia(request,service,principal,continuityId,action) {
  const parts=action.split('/');
  if(parts[0]!=='media'||!validCloudId(parts[2]))unavailable();
  const [,route,id,variant]=parts;
  if(parts.length===3&&route==='uploads'){
    if(request.method==='PUT')return putUpload(request,service,principal,continuityId,id);
    if(request.method==='DELETE')return cleanup(service,principal,continuityId,id);
  }
  if(parts.length===4&&request.method==='GET'&&['original','thumbnail'].includes(variant)){
    if(route==='uploads')return shownCandidate(service,principal,continuityId,id,variant);
    if(route==='primary')return primaryImage(service,principal,continuityId,id,variant);
  }
  unavailable();
}

// Dedicated, bounded bytes transport for a connector which has an approved
// source image. Larger uploads stream through the authenticated binary route.
export const CLOUD_MEDIA_MCP_TOOL=Object.freeze({
  name:'stage_cloud_image',
  description:'Stage actual PNG bytes (maximum 36 KiB) for one existing entity. Returns an owner preview and digest. The separate assign_primary_image operation requires owner review. Larger PNGs use the authenticated binary upload endpoint. Only use bytes available in this conversation or an owner-authorized asset source.',
  inputSchema:cloudObject({continuityId:CLOUD_ID,uploadId:CLOUD_ID,targetId:CLOUD_ID,
    targetKind:{type:'string',enum:[...targetKinds]},sha256:{type:'string',pattern:'^[a-f0-9]{64}$'},
    bytesBase64:{type:'string',minLength:4,maxLength:49152,pattern:'^[A-Za-z0-9+/]*={0,2}$'}}),
  annotations:{readOnlyHint:false,destructiveHint:false,idempotentHint:true,openWorldHint:false},
});
export async function stageCloudMcpImage(service,principal,args) {
  if(principal?.role!=='connector')cloudFail(403,'CLOUD_GRANT_DENIED');
  if(typeof args.bytesBase64!=='string'||args.bytesBase64.length>49152||args.bytesBase64.length%4)cloudFail(400,'INVALID_MEDIA_ENCODING');
  let decoded;
  try { decoded=atob(args.bytesBase64); }
  catch { cloudFail(400,'INVALID_MEDIA_ENCODING'); }
  if(btoa(decoded)!==args.bytesBase64)cloudFail(400,'INVALID_MEDIA_ENCODING');
  if(!validCloudId(args.uploadId)||!validCloudId(args.continuityId)||!validCloudId(args.targetId)||!targetKinds.has(args.targetKind))
    cloudFail(400,'INVALID_IMAGE_TARGET');
  const path='media/uploads/'+encodeURIComponent(args.uploadId);
  const url=new URL('https://private-media.invalid/'+path);
  url.searchParams.set('targetId',args.targetId);url.searchParams.set('targetKind',args.targetKind);
  // This local Request adapts the bytes to the shared validator. It performs no fetch.
  const request=new Request(url,{method:'PUT',headers:{'Content-Type':'image/png','X-Astravar-Content-SHA256':args.sha256},
    body:Uint8Array.from(decoded,char=>char.charCodeAt(0))});
  return (await handleCloudMedia(request,service,principal,args.continuityId,path)).json();
}

/** The lifecycle worker calls this only for durable, owner-approved GC rows. */
export async function purgeCloudMediaObjects(service,binding,uploadId,digest) {
  const keysToPurge=await keys(binding,uploadId,digest);
  const bucket=storage(service);
  await bucket.delete([keysToPurge.original,keysToPurge.thumbnail]);
  if(await bucket.head(keysToPurge.original)||await bucket.head(keysToPurge.thumbnail))cloudFail(503,'IMAGE_CLEANUP_PENDING');
}
