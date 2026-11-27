import { chargeCloudRequest, httpRateCost, cloudRateResponse } from './cloud-rate.mjs';
import { cloudReviewInbox } from './cloud-approval-view.mjs';
import { cloudContinuitySettings } from './cloud-continuity.mjs';
import { cloudAccessAdmin } from './cloud-admin.mjs';
import { cloudActions } from './cloud-actions-view.mjs';
import { cloudArchiveView } from './cloud-lifecycle.mjs';
import { cloudSpellbook } from './cloud-spellbook.mjs';
import { handleCloudMedia } from './cloud-media.mjs';
import { cloudRecordView } from './cloud-records-query.mjs';
import { BridgeError } from './core.mjs';
import { cloudFail, validCloudId } from './cloud-validation.mjs';
import { CloudPlan } from './cloud-plan.mjs';
import { cloudContext, cloudChanges, cloudRelationships, cloudPoolHistory } from './cloud-query.mjs';
import { mechanicActorSheet, mechanicFormPreview } from './cloud-mechanics.mjs';

export async function cloudBody(request) {
  if(request.headers.get('content-type')?.split(';')[0].trim()!=='application/json')cloudFail(415,'JSON_REQUIRED');
  const reader=request.body?.getReader();if(!reader)cloudFail(400,'BODY_REQUIRED');
  const chunks=[];let size=0;
  for(;;){const item=await reader.read();if(item.done)break;size+=item.value.length;if(size>65536){await reader.cancel();cloudFail(413,'REQUEST_TOO_LARGE');}chunks.push(item.value);}
  const data=new Uint8Array(size);let offset=0;
  for(const chunk of chunks){data.set(chunk,offset);offset+=chunk.length;}
  try{return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(data));}catch{cloudFail(400,'INVALID_JSON');}
}
export async function cloudSheet(service,principal,id,actorId,formId=null) {
  const opened=await service.access(principal,id);
  const ctx=new CloudPlan(service,service.key(id),opened.grant,{commandId:'preview-actor-sheet',operation:'inspect_actor'},service.now());
  const view=formId===null ? {sheet:await mechanicActorSheet(ctx,actorId)} : {preview:await mechanicFormPreview(ctx,actorId,formId)};
  const again=await service.access(principal,id);
  if(again.continuity.epoch!==opened.continuity.epoch||again.continuity.revision!==opened.continuity.revision||again.grant.revision!==opened.grant.revision)cloudFail(409,'SNAPSHOT_CHANGED');
  return {epoch:opened.continuity.epoch,revision:opened.continuity.revision,grantRevision:opened.grant.revision,...view};
}
export function cloudResponse(value,status=200) {
  return Response.json(value,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'}});
}
export async function cloudHttp(request,bridge,service,ownerPrincipal) {
  const url=new URL(request.url);
  try {
    const parts=url.pathname.split('/').slice(3).map(decodeURIComponent);
    const [id,action,item,decision]=parts,method=request.method;
    if((action!=='media' && parts.length>4)||!validCloudId(id))cloudFail(404,'UNKNOWN_ROUTE');
    const origin=request.headers.get('Origin');
    if(origin && origin!==url.origin)cloudFail(403,'CROSS_ORIGIN_DENIED');
    let principal;
    if(request.headers.has('authorization')||request.headers.has('X-Astravar-Credential')){
      const bearer=request.headers.get('Authorization')||'';
      if(!bearer.startsWith('Bearer '))cloudFail(401,'AUTHENTICATION_REQUIRED');
      principal=await bridge.authenticate(request.headers.get('X-Astravar-Credential')||'',bearer.slice(7));
    }else{
      if(!ownerPrincipal)cloudFail(401,'AUTHENTICATION_REQUIRED');
      if(method!=='GET' && origin!==url.origin)cloudFail(403,'SAME_ORIGIN_REQUIRED');
      principal=ownerPrincipal;
    }
    await chargeCloudRequest(service,principal,id,httpRateCost(method,action),action==='isolated' && method==='POST' && !item);
    if(action==='settings' && !item && method==='GET')return cloudResponse(await cloudContinuitySettings(service,principal,id));
    if(action==='media')return await handleCloudMedia(request,service,principal,id,parts.slice(1).join('/'));
    if(action==='review-inbox'){
      if(method!=='GET'||decision)cloudFail(404,'UNKNOWN_ROUTE');
      const pairs=[...url.searchParams], allowed=item?['digest']:['limit','after'];
      if(pairs.some(([k])=>!allowed.includes(k)) || new Set(pairs.map(([k])=>k)).size!==pairs.length)cloudFail(400,'INVALID_REVIEW_VIEW');
      if(item)return cloudResponse(await cloudReviewInbox(service,principal,id,'detail',{commandId:item,digest:url.searchParams.get('digest')}));
      const limit=url.searchParams.get('limit');
      if(limit!==null && !/^[1-9][0-9]?$/.test(limit))cloudFail(400,'INVALID_PAGE');
      return cloudResponse(await cloudReviewInbox(service,principal,id,'list',{limit:limit===null?20:Number(limit),after:url.searchParams.get('after')??''}));
    }
    const options={};
    for(const [key,value] of url.searchParams){
      if(Object.hasOwn(options,key))cloudFail(400,'DUPLICATE_PARAMETER');
      if(['limit','atRevision','atEpoch','grantRevision','sinceRevision'].includes(key)){
        if(!/^(0|[1-9][0-9]{0,15})$/.test(value)||!Number.isSafeInteger(Number(value)))cloudFail(400,'INVALID_PAGE');
        options[key]=Number(value);
      }else if(['kind','id','after','actorId','query'].includes(key))options[key]=value;
      else if(key==='activeOnly' && ['true','false'].includes(value))options[key]=value==='true';
      else cloudFail(400,'UNKNOWN_PARAMETER');
    }
    let result;
    if(method==='POST'&&action==='isolated'&&!item){
      const body=await cloudBody(request);if(!body||Array.isArray(body)||Object.keys(body).length)cloudFail(400,'INVALID_REQUEST');
      result=await service.createIsolated(principal,id);
    }else if(method==='POST'&&action==='grants'&&item&&!decision)result=await service.setGrant(principal,id,item,await cloudBody(request));
    else if(method==='GET'&&action==='access'&&!item)result=await cloudAccessAdmin(service,principal,id);
    else if(method==='GET'&&action==='capabilities'&&!item)result=await service.capabilities(principal,id);
    else if(method==='GET'&&action==='state'&&!item)result=await service.state(principal,id,options);
    else if(method==='GET'&&action==='records'&&item&&!decision)result=await cloudRecordView(service,principal,id,item,options);
    else if(method==='GET'&&action==='archive'&&!item)result=await cloudArchiveView(service,principal,id,options);
    else if(method==='GET'&&action==='changes'&&!item)result=await cloudChanges(service,principal,id,options);
    else if(method==='GET'&&action==='pool-history'&&item&&!decision)result=await cloudPoolHistory(service,principal,id,item,options);
    else if(method==='GET'&&action==='context'&&!item)result=await cloudContext(service,principal,id,options);
    else if(method==='GET'&&action==='spellbook'&&item&&!decision)result=await cloudSpellbook(service,principal,id,item,options);
    else if(method==='GET'&&action==='actions'&&item&&!decision)result=await cloudActions(service,principal,id,item,options);
    else if(method==='GET'&&action==='forms'&&validCloudId(item)&&validCloudId(decision))result=await cloudSheet(service,principal,id,item,decision);
    else if(method==='GET'&&action==='sheet'&&item&&!decision)result=await cloudSheet(service,principal,id,item);
    else if(method==='GET'&&action==='links'&&item&&!decision)result=await cloudRelationships(service,principal,id,item,options);
    else if(method==='POST'&&action==='commands'&&!item)result=await service.submit(principal,id,await cloudBody(request));
    else if(method==='GET'&&action==='receipts'&&item&&!decision)result=await service.receipt(principal,id,item);
    else if(method==='GET'&&action==='review-status'&&!item)result=await service.reviewStatus(principal,id);
    else if(method==='GET'&&action==='approvals'&&!item)result=await service.approvals(principal,id,options);
    else if(method==='POST'&&action==='approvals'&&item&&decision){
      const body=await cloudBody(request);
      if(!['verify','refuse'].includes(decision)||!body||Object.keys(body).length!==1||!Object.hasOwn(body,'digest'))cloudFail(400,'EXACT_REVIEW_REQUIRED');
      result=await service.review(principal,id,item,body.digest,decision);
    }else cloudFail(404,'UNKNOWN_ROUTE');
    return cloudResponse(result,result.status==='PENDING_APPROVAL'?202:200);
  }catch(e){
    const limited=cloudRateResponse(e);
    if(limited){
      const response=cloudResponse({status:'UNVERIFIED',error:limited},429);
      response.headers.set('Retry-After',String(limited.retryAfterSeconds));
      return response;
    }
    return cloudResponse({status:e instanceof BridgeError&&e.status===409?'CONFLICT':'UNVERIFIED',error:{code:e instanceof BridgeError?e.code:'SERVICE_UNAVAILABLE'},...(e instanceof BridgeError && e.conflict?{conflict:e.conflict}:{})},e instanceof BridgeError?e.status:503);}
}
