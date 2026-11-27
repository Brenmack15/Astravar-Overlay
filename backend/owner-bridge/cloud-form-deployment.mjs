import { BridgeError } from './core.mjs';
import { actor, owned, insert, save, must, op, identifier, fields, named, option, boolean, gearEligible, useTiming, addClock } from './cloud-mechanics-core.mjs';

const roundSeconds=6;
const incapacitating=new Set(['incapacitated','unconscious','stunned','petrified','paralyzed']);

export async function eligibleForm(ctx,a,form,willing){
  const d=form.data;
  must(!['disabled','dormant'].includes(a.data.activityState) && !a.data.conditions.some(k=>incapacitating.has(k)) &&
    (!d.requiresWilling||willing===true),'FORM_TRANSFORMATION_INELIGIBLE');
  must(await gearEligible(ctx,d.sourceItemId,a.id,d.requiresAttunement,d.requiresOperating),'FORM_SOURCE_INELIGIBLE');
  if(d.integrityPoolId){
    const p=await owned(ctx,'resource_pool',d.integrityPoolId,a.id);
    must(p.data.current!=null&&p.data.current>0,'FORM_INTEGRITY_UNAVAILABLE');
  }
}

async function actorEncounter(ctx,a){
  if(!a.data.encounterId)return null;
  ctx.require('combat',a.id,false);
  // Return only this actor's membership and the public turn clock. A scoped grant need
  // not read other participants' initiatives, action budgets or encounter notes.
  const e=await ctx.current(a.data.encounterId);
  must(e?.kind==='encounter' && e.life==='active' && e.data.active && e.data.participants.includes(a.id),'DEPLOYMENT_ENCOUNTER_UNAVAILABLE');
  return e;
}
function segment(a,enc,gameSeconds=a.data.gameSeconds){
  return enc?{encounterId:enc.id,round:enc.data.round,index:enc.data.index,gameSeconds}:
    {encounterId:null,gameSeconds};
}
function elapsedSegment(s,enc,a){
  if(!s.encounterId){
    if(s.gameSeconds==null||a.data.gameSeconds==null)return 0;
    must(a.data.gameSeconds>=s.gameSeconds,'DEPLOYMENT_CLOCK_UNVERIFIED');
    return a.data.gameSeconds-s.gameSeconds;
  }
  must(enc?.id===s.encounterId,'DEPLOYMENT_ENCOUNTER_CHANGED');
  const rounds=enc.data.round-s.round-(enc.data.index<s.index?1:0);
  must(rounds>=0,'DEPLOYMENT_CLOCK_UNVERIFIED');
  return rounds*roundSeconds;
}
function progress(d,enc,a){
  const segmentSeconds=elapsedSegment(d.segment,enc,a),elapsedSeconds=d.elapsedBeforeSegment+segmentSeconds;
  return {elapsedSeconds,remainingSeconds:Math.max(0,d.requiredSeconds-elapsedSeconds),segmentSeconds,
    basis:enc?'encounter_rounds':'actor_game_clock',round:enc?.data.round??null};
}
function synchronizedClock(d,elapsed,a){
  if(!d.segment.encounterId)return a.data.gameSeconds;
  if(d.segment.gameSeconds==null)return a.data.gameSeconds;
  return Math.max(a.data.gameSeconds??0,d.segment.gameSeconds+elapsed);
}

async function settleActorClock(ctx,a,clock){
  if(clock==null||a.data.gameSeconds==null||clock===a.data.gameSeconds)return a.data;
  const next=await addClock(ctx,a,clock-a.data.gameSeconds);
  if(a.data.activeFormId && !next.activeFormId)await ctx.unlink(a.id,'active_form',a.data.activeFormId);
  return next;
}

export async function currentDeployment(ctx,a){
  return a.data.activeDeploymentId?owned(ctx,'form_deployment',a.data.activeDeploymentId,a.id):null;
}
export async function deploymentView(ctx,a,formId=null){
  const record=await currentDeployment(ctx,a);
  if(!record)return null;
  const enc=await actorEncounter(ctx,a),d=record.data,p=progress(d,enc,a);
  const form=await ctx.get('form',d.formId);
  let blockedReason=null;
  if(!form||form.life!=='active'||(form.data.configurationRevision??form.revision)!==d.formRevision||a.data.activeFormId!==d.previousFormId)blockedReason='DEPLOYMENT_FORM_CHANGED';
  else {
    try {
      await eligibleForm(ctx,a,form,true);
      if(form.data.deploymentPoolId){
        const pool=await owned(ctx,'resource_pool',form.data.deploymentPoolId,a.id);
        if(pool.data.current==null||pool.data.current<1)blockedReason='RESOURCE_EMPTY_OR_UNKNOWN';
      }
    }catch(e){if(!(e instanceof BridgeError)||e.status===403)throw e;blockedReason=e.code;}
  }
  return {deploymentId:record.id,formId:d.formId,formTitle:record.title,requiredSeconds:d.requiredSeconds,startedAt:d.startedAt,
    status:d.status,elapsedSeconds:p.elapsedSeconds,remainingSeconds:p.remainingSeconds,basis:p.basis,round:p.round,
    ready:p.remainingSeconds===0 && !blockedReason,blockedReason,matchesForm:formId===d.formId,sourceRevision:d.formRevision};
}
async function begin(ctx,x){
  const a=await actor(ctx,x.actorId),form=await owned(ctx,'form',x.formId,a.id),d=form.data;
  must(!a.data.activeDeploymentId,'DEPLOYMENT_ALREADY_PENDING');
  must(a.data.activeFormId!==form.id && d.activationSeconds>roundSeconds,'TRACKED_DEPLOYMENT_INELIGIBLE');
  await eligibleForm(ctx,a,form,x.willing);
  const enc=await actorEncounter(ctx,a);
  must(enc||a.data.gameSeconds!=null,'GAME_CLOCK_UNKNOWN');
  must(!d.durationSeconds||a.data.gameSeconds!=null,'GAME_CLOCK_UNKNOWN');
  if(enc)must(enc.data.participants[enc.data.index]===a.id || d.timing==='reaction','TURN_UNAVAILABLE');
  if(d.deploymentPoolId){
    const p=await owned(ctx,'resource_pool',d.deploymentPoolId,a.id);
    must(p.data.current!=null && p.data.current>0,'RESOURCE_EMPTY_OR_UNKNOWN');
  }
  // The action of starting work happens now. A successful deployment spends its
  // configured finite use and initializes eligible per-deployment resources later.
  await useTiming(ctx,a,d.timing);
  const data={status:'deploying',formId:form.id,formRevision:form.data.configurationRevision??form.revision,previousFormId:a.data.activeFormId,
    requiredSeconds:d.activationSeconds,startedAt:ctx.now,startedByCommand:ctx.commandId,sourceItemId:d.sourceItemId??null,
    requiresOperating:d.requiresOperating??false,requiresAttunement:d.requiresAttunement??false,startedTiming:d.timing,
    elapsedBeforeSegment:0,segment:segment(a,enc),willing:x.willing??null};
  await insert(ctx,'form_deployment',x.deploymentId,a.id,form.title,data);
  await ctx.link(x.deploymentId,'deployment_form',form.id);await ctx.link(a.id,'deploying',x.deploymentId);
  await save(ctx,a,{...a.data,activeDeploymentId:x.deploymentId});
  return {deploymentId:x.deploymentId,formId:form.id,status:data.status,elapsedSeconds:0,requiredSeconds:d.activationSeconds,costs:[]};
}

export async function closeDeployment(ctx,a,record,status,reason){
  const d=record.data;
  must(d.status==='deploying' && a.data.activeDeploymentId===record.id,'DEPLOYMENT_NOT_PENDING');
  const enc=await actorEncounter(ctx,a),p=progress(d,enc,a);
  const clock=synchronizedClock(d,p.segmentSeconds,a);
  await save(ctx,record,{...d,status,elapsedSeconds:p.elapsedSeconds,completionBasis:p.basis,finishedAt:ctx.now,
    finishedByCommand:ctx.commandId,reason,endingRound:p.round,finishedGameSeconds:clock});
  await ctx.unlink(a.id,'deploying',record.id);
  return {...await settleActorClock(ctx,a,clock),activeDeploymentId:null};
}
export async function interruptIfPending(ctx,a,reason){
  const record=await currentDeployment(ctx,a);
  return record?closeDeployment(ctx,a,record,'interrupted',reason):a.data;
}
export async function planCompletion(ctx,a,form,x){
  if(!x.deploymentId){must(!a.data.activeDeploymentId,'FINISH_OR_INTERRUPT_PENDING_DEPLOYMENT');return null;}
  const record=await owned(ctx,'form_deployment',x.deploymentId,a.id),d=record.data;
  must(record.id===a.data.activeDeploymentId && d.status==='deploying' && d.formId===form?.id,'DEPLOYMENT_NOT_PENDING');
  must(a.data.activeFormId===d.previousFormId && (form.data.configurationRevision??form.revision)===d.formRevision,'DEPLOYMENT_FORM_CHANGED');
  must(x.elapsedSeconds===0 && x.uninterrupted===true,'RECORDED_DEPLOYMENT_TIME_REQUIRED');
  await eligibleForm(ctx,a,form,x.willing);
  const enc=await actorEncounter(ctx,a),p=progress(d,enc,a);
  must(p.remainingSeconds===0,'DEPLOYMENT_TIME_REMAINING');
  return {next:await closeDeployment(ctx,a,record,'completed','confirmed_uninterrupted'),elapsedSeconds:p.elapsedSeconds};
}

// Bridge the clock when initiative starts or ends. An encounter ending partway
// through a round contributes only fully observed rounds since this segment began.
export async function changeDeploymentClock(ctx,a,oldEncounter,nextEncounter){
  const record=await currentDeployment(ctx,a);
  if(!record)return a.data;
  const d=record.data,p=progress(d,oldEncounter,a),clock=synchronizedClock(d,p.segmentSeconds,a);
  await save(ctx,record,{...d,elapsedBeforeSegment:p.elapsedSeconds,segment:segment(a,nextEncounter,clock)});
  return settleActorClock(ctx,a,clock);
}
export const deploymentOperations=Object.freeze({
  begin_form_deployment:op('character',fields({actorId:identifier,formId:identifier,deploymentId:identifier,willing:boolean,declared:{const:true}},
    ['actorId','formId','deploymentId','declared']),begin),
  interrupt_form_deployment:op('character',fields({actorId:identifier,deploymentId:identifier,status:option('cancelled','interrupted'),reason:named(300)},
    ['actorId','deploymentId','status','reason']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId),record=await owned(ctx,'form_deployment',x.deploymentId,a.id);
    await save(ctx,a,await closeDeployment(ctx,a,record,x.status,x.reason));
    const ended=await owned(ctx,'form_deployment',record.id,a.id);
    return {deploymentId:record.id,status:ended.data.status,elapsedSeconds:ended.data.elapsedSeconds,reason:x.reason,costs:[]};
  })
});
