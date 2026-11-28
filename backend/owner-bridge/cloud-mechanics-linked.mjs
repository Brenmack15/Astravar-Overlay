import {assessHandling,handlingLimits,handlingRequest} from './cloud-object-handling.mjs';
import {acquireTargetLock,lockTarget} from './cloud-target-lock.mjs';
import { translateMessage } from './cloud-companion-traits.mjs';
import { identifier, whole, named, option, boolean, arrayOf, fields, evidence, component, actor, owned, must, save, gearEligible, sheet, checkDiceOwner } from './cloud-mechanics-core.mjs';
import { observed } from './cloud-mechanics-rolls.mjs';

export const linkedTypes=['assess_handling','translate','thermal_scan','gate','touch_delivery','deflection','timed_attack_mode','weapon_target_lock','damage_reprisal'];
export const linkedDefinitions={handlingLimits,reactionAttacks:arrayOf(identifier,24),lockRangeFeet:whole(1,10000),communicationFeatureId:identifier,bondedActorId:identifier,thermal:fields({normal:whole(1,10000),faint:whole(1,10000),skillId:identifier,tag:identifier},['normal','faint','skillId','tag']),
  recognizedAllyInterception:{const:true},reduction:fields({dice:whole(1,20),sides:whole(2,100),flat:whole(0,100)},['dice','sides']),modeAttacks:arrayOf(identifier,16),modeReach:whole(1,200),modeComponent:component};
export const linkedArguments={handling:handlingRequest,lockTarget,translation:fields({inputLanguage:named(80),outputLanguage:named(80),recipientId:identifier,modality:option('speech','writing'),sourcePerceived:boolean,outputHeard:boolean},['inputLanguage','outputLanguage','recipientId','modality','sourcePerceived','outputHeard']),thermalReading:fields({distanceFeet:whole(0,10000),faint:boolean,obstacle:option('none','solid','deep_snow','magical_interference','unverified')},['distanceFeet','faint','obstacle']),
  gate:fields({open:boolean,location:named(160),permittedHere:boolean,teleportAllowed:boolean},['open','location','permittedHere','teleportAllowed']),
  delivery:fields({castId:identifier,targetActorId:identifier,distanceToCaster:whole(0,10000),targetWithinReach:{const:true}},['castId','targetActorId','distanceToCaster','targetWithinReach']),
  deflection:fields({attemptId:identifier,targetActorId:identifier,distanceFeet:whole(0,10000),visible:{const:true},dice:evidence,interception:fields({recognized:boolean,possible:boolean,source:named(300)},['recognized','possible','source']),
    allocation:arrayOf(fields({type:identifier,amount:whole()},['type','amount']),16)},['attemptId','targetActorId','distanceFeet','visible','dice'])};
export async function validateLinkedAction(ctx,x){
  must((x.type==='damage_reprisal')===(x.reactionAttacks!==undefined),'UNEXPECTED_REACTION_ATTACKS');
  if(x.type==='damage_reprisal'){
    must(x.timing==='reaction'&&x.range&&x.reactionAttacks.length&&new Set(x.reactionAttacks).size===x.reactionAttacks.length,'REACTION_ATTACK_LIST_REQUIRED');
    for(const id of x.reactionAttacks){
      const attack=await owned(ctx,'attack',id,x.actorId);
      must(attack.data.delivery==='melee'&&attack.data.resolution==='attack','REACTION_ATTACK_INELIGIBLE');
    }
  }
  must((x.type==='weapon_target_lock')===(x.lockRangeFeet!==undefined),'UNEXPECTED_LOCK_RANGE');
  if(x.type==='weapon_target_lock'){
    must(x.timing==='bonus_action'&&x.sourceItemId&&x.requiresOperating&&x.cost?.amount===1,'TARGET_LOCK_CONFIGURATION_REQUIRED');
    const pool=await owned(ctx,'resource_pool',x.cost.poolId,x.actorId);
    must(pool.data.recovery==='short_rest'&&pool.data.maximum===1,'TARGET_LOCK_POOL_REQUIRED');
  }
  must((x.type==='translate')===Boolean(x.communicationFeatureId),'TRANSLATOR_SOURCE_REQUIRED');
  if(x.communicationFeatureId){
    const f=await owned(ctx,'feature',x.communicationFeatureId,x.actorId);
    must(f.data.speech?.translation===true && x.timing==='other' && f.data.sourceItemId===x.sourceItemId,'TRANSLATOR_CONFIGURATION_REQUIRED');
  }
  if(x.bondedActorId){must(x.bondedActorId!==x.actorId,'BONDED_ACTOR_INVALID');await actor(ctx,x.bondedActorId);}
  must((x.type==='assess_handling')===Boolean(x.handlingLimits),'HANDLING_CONFIGURATION_INVALID');
  if(x.handlingLimits)must(x.timing==='other'&&x.handlingLimits.pushDragLiftPounds>=x.handlingLimits.carryPounds,'HANDLING_CONFIGURATION_INVALID');
  if(x.type==='thermal_scan')must(x.thermal && x.thermal.faint<=x.thermal.normal && x.sourceItemId,'SENSOR_CONFIGURATION_REQUIRED');
  must(!x.recognizedAllyInterception||x.type==='deflection','UNEXPECTED_INTERCEPTION_RULE');
  if(x.type==='deflection')must(x.timing==='reaction'&&Boolean(x.bondedActorId)!==Boolean(x.recognizedAllyInterception)&&x.reduction&&x.range!=null,'DEFLECTION_CONFIGURATION_REQUIRED');
  if(x.type==='touch_delivery')must(x.timing==='reaction'&&x.bondedActorId&&x.range!=null,'DELIVERY_CONFIGURATION_REQUIRED');
  if(x.type==='gate')must(x.timing==='action','GATE_ACTION_REQUIRED');
  if(x.type==='timed_attack_mode'){
    must(x.sourceItemId && x.cost && x.durationSeconds && x.modeReach && x.modeComponent && x.modeAttacks?.length &&
      new Set(x.modeAttacks).size===x.modeAttacks.length && !x.modeComponent.variants && !x.modeComponent.abilityBonus && !x.modeComponent.extraDicePerLevel,'ATTACK_MODE_CONFIGURATION_REQUIRED');
    for(const id of x.modeAttacks){const a=await owned(ctx,'attack',id,x.actorId);must(a.data.delivery==='melee'&&a.data.resolution==='attack'&&!a.data.components.some(c=>c.id===x.modeComponent.id),'ATTACK_MODE_SOURCE_INVALID');}
  }
  must((x.thermal!==undefined)===(x.type==='thermal_scan') && (x.reduction!==undefined)===(x.type==='deflection') &&
    (!x.modeAttacks&&!x.modeReach&&!x.modeComponent||x.type==='timed_attack_mode'),'UNEXPECTED_LINKED_CONFIGURATION');
}
export async function executeLinkedAction(ctx,a,action,x,data){
  const d=action.data;
  must((d.type==='assess_handling')===Boolean(x.handling),'HANDLING_ARGUMENT_MISMATCH');
  if(x.handling)data.handling=assessHandling(d.handlingLimits,x.handling);
  must(d.type!=='damage_reprisal','RESOLVE_TRIGGERED_ATTACK_REQUIRED');
  must((d.type==='weapon_target_lock')===Boolean(x.lockTarget),'LOCK_ARGUMENT_MISMATCH');
  if(d.type==='weapon_target_lock')await acquireTargetLock(ctx,a,action,x,data);
  if(d.type==='translate') {must(x.translation,'TRANSLATION_REQUIRED');data.translation=await translateMessage(ctx,a,x,await sheet(ctx,a.id),d.communicationFeatureId);}
  for(const [key,type] of [['translation','translate'],['thermalReading','thermal_scan'],['gate','gate'],['delivery','touch_delivery'],['deflection','deflection']])must((x[key]!==undefined)===(d.type===type),'ACTION_ARGUMENT_MISMATCH');
  if(d.type==='thermal_scan'){
    const read=x.thermalReading, limit=read.faint?d.thermal.faint:d.thermal.normal;
    const eligible=read.obstacle==='none' && read.distanceFeet<=limit;
    data.sensor={...read,limit,eligible,skillId:d.thermal.skillId,tag:d.thermal.tag,status:eligible?'pending_check':read.obstacle==='unverified'?'unverified':'blocked'};
  }else if(d.type==='gate'){
    const g=x.gate,prior=a.data.openGate??null;
    must(g.open?!prior && g.permittedHere && g.teleportAllowed:prior?.actionId===action.id && prior.location===g.location,'GATE_STATE_OR_LOCATION_INELIGIBLE');
    const state=g.open?{actionId:action.id,location:g.location,timeRatio:{inside:1,outside:1},openedAt:a.data.gameSeconds}:null;
    await save(ctx,a,{...a.data,openGate:state});data.gate={previous:prior,current:state};
  }else if(d.type==='touch_delivery'){
    const r=x.delivery,cast=await owned(ctx,'cast_instance',r.castId,d.bondedActorId),c=cast.data;
    must(c.rangeKind==='touch' && c.deliveryActorId===a.id && !c.deliveryUseId && c.targets.includes(r.targetActorId) && c.outcome==='pending' && r.distanceToCaster<=d.range,'TOUCH_DELIVERY_INELIGIBLE');
    await actor(ctx,r.targetActorId);await save(ctx,cast,{...c,deliveryUseId:x.useId});data.castId=cast.id;data.delivery=r;
  }else if(d.type==='deflection'){
    const r=x.deflection,attempt=await ctx.get('attack_attempt',r.attemptId),t=await sheet(ctx,r.targetActorId),hit=attempt?.data.hit;
    const eligible=d.recognizedAllyInterception?r.targetActorId!==a.id&&r.interception?.recognized===true&&r.interception.possible===true:r.targetActorId===d.bondedActorId&&!r.interception;
    must(eligible && r.distanceFeet<=d.range && hit && hit.natural!==1 && (hit.natural===20||t.ac.value!=null&&hit.total>=t.ac.value) &&
      attempt.data.targets.includes(r.targetActorId) && Array.isArray(attempt.data.damage) && !attempt.data.targetResults?.some(k=>k.targetActorId===r.targetActorId) &&
      !attempt.data.deflections?.some(k=>k.targetActorId===r.targetActorId),'DEFLECTION_WINDOW_UNAVAILABLE');
    checkDiceOwner(ctx,a,'damage',r.dice.origin);
    const roll=observed(action.title,d.reduction.dice,d.reduction.sides,d.reduction.flat??0,'sum',r.dice),amounts=new Map();
    for(const part of attempt.data.damage)amounts.set(part.type,(amounts.get(part.type)??0)+Math.max(0,part.total));
    const maximum=Math.min(roll.total,[...amounts.values()].reduce((n,v)=>n+v,0));
    const allocation=r.allocation??(amounts.size===1?[{type:[...amounts.keys()][0],amount:maximum}]:null);
    must(allocation && new Set(allocation.map(k=>k.type)).size===allocation.length && allocation.every(k=>amounts.has(k.type)&&k.amount<=amounts.get(k.type)) && allocation.reduce((n,k)=>n+k.amount,0)===maximum,'DAMAGE_ALLOCATION_REQUIRED');
    data.deflection={attemptId:attempt.id,targetActorId:r.targetActorId,roll,allocation,...(r.interception?{interception:r.interception}:{})};
    await save(ctx,attempt,{...attempt.data,deflections:[...(attempt.data.deflections??[]),{useId:x.useId,...data.deflection}]});
  }else if(d.type==='timed_attack_mode'){
    must(a.data.gameSeconds!=null,'GAME_CLOCK_UNKNOWN');
    const current=a.data.attackModes??[], active=current.filter(m=>m.expiresAt>a.data.gameSeconds);
    must(!active.some(m=>m.actionId===action.id) && active.length<16,'ATTACK_MODE_ALREADY_ACTIVE');
    data.mode={actionId:action.id,useId:x.useId,sourceItemId:d.sourceItemId,requiresAttunement:d.requiresAttunement??false,
      requiresOperating:d.requiresOperating??false,expiresAt:a.data.gameSeconds+d.durationSeconds,attackIds:d.modeAttacks,reach:d.modeReach,component:d.modeComponent};
    await save(ctx,a,{...a.data,attackModes:[...active,data.mode]});
  }
}
export async function attackModeFor(ctx,a,attack){
  const modes=[];
  for(const m of a.data.attackModes??[])if(a.data.gameSeconds!=null && m.expiresAt>a.data.gameSeconds && m.attackIds.includes(attack.id) && await gearEligible(ctx,m.sourceItemId,a.id,m.requiresAttunement,m.requiresOperating))modes.push(m);
  must(new Set(modes.map(m=>m.component.id)).size===modes.length && modes.every(m=>!attack.data.components.some(c=>c.id===m.component.id)),'ATTACK_MODE_COMPONENT_CONFLICT');
  return {components:modes.map(m=>m.component),reach:modes.reduce((v,m)=>Math.max(v??0,m.reach),attack.data.reach??null),useIds:modes.map(m=>m.useId)};
}
