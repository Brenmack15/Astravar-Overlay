import {matchesForm,reactionRollWindow} from './cloud-mechanics-core.mjs';
import { interruptIfPending } from './cloud-form-deployment.mjs';
import { arsenalDefinitions, loadChoice, useArsenal, validateArsenal } from './cloud-mechanics-arsenal.mjs';
import { snapshotComponents } from './cloud-mechanics-combat.mjs';
import { linkedTypes, linkedDefinitions, linkedArguments, validateLinkedAction, executeLinkedAction } from './cloud-mechanics-linked.mjs';
import { stats, identifier, named, whole, nullable, arrayOf, option, boolean, fields, actionCost, costSchema, must, op, actor, owned, insert, save, transferableSource, gearEligible, sheet, spend, useTiming } from './cloud-mechanics-core.mjs';

const sizeOrder=['tiny','small','medium','large','huge','gargantuan'];
const actionSchema=fields({
  ...linkedDefinitions,...arsenalDefinitions,actorId:identifier,actionId:identifier,name:named(120),source:named(300),
  type:option('use','teleport','frighten','roll_bonus','multiattack','fixed_attack_die','store_payload','prepare_strike',...linkedTypes),fixedDieValue:whole(1,20),attackSequence:arrayOf(identifier,12),substitutions:arrayOf(fields({attackId:identifier,replaces:arrayOf(identifier,12),maximum:whole(1,12)},['attackId','replaces','maximum']),12),timing:actionCost,formId:identifier,excludedForms:arrayOf(identifier,16),sourceItemId:identifier,transfersWithItem:boolean,
  requiresOperating:boolean,requiresAttunement:boolean,cost:costSchema,range:whole(0,1200),saveDc:whole(1,100),
  saveAbility:option(...stats),durationSeconds:whole(1,86400),bonusAbility:option(...stats),fixedBonus:whole(-100,100)
},['actorId','actionId','name','source','type','timing']);
async function define(ctx,x){
  const a=await actor(ctx,x.actorId);
  await validateLinkedAction(ctx,x);await validateArsenal(ctx,x);
  must((!x.requiresOperating&&!x.requiresAttunement)||x.sourceItemId,'ACTION_SOURCE_REQUIRED');
  if(x.sourceItemId)must(await ctx.get('item_instance',x.sourceItemId),'ACTION_SOURCE_MISSING');
  await transferableSource(ctx,x);
  if(x.formId)await owned(ctx,'form',x.formId,x.actorId);
  must(!x.formId||!x.excludedForms?.includes(x.formId),'INVALID_FORM_REQUIREMENT');
  for(const id of new Set(x.excludedForms??[]))await owned(ctx,'form',id,x.actorId);
  if(x.cost)await owned(ctx,'resource_pool',x.cost.poolId,x.actorId);
  must((x.type==='multiattack')===(x.attackSequence!==undefined),'MULTIATTACK_SEQUENCE_REQUIRED');
  if(x.type==='multiattack'){
    must(x.timing==='action' && x.attackSequence.length>=2 && !x.transfersWithItem,'MULTIATTACK_CONFIGURATION_INVALID');
    const alternatives=x.substitutions??[];
    must(new Set(alternatives.map(s=>s.attackId)).size===alternatives.length && alternatives.every(s=>!x.attackSequence.includes(s.attackId) && s.replaces.length && s.replaces.every(id=>x.attackSequence.includes(id)) && s.maximum<=x.attackSequence.length),'MULTIATTACK_SUBSTITUTIONS_INVALID');
    for(const id of new Set([...x.attackSequence,...alternatives.map(s=>s.attackId)])){
      const attack=await owned(ctx,'attack',id,a.id);
      must(attack.data.resolution==='attack' && ['attack','action'].includes(attack.data.timing) &&
        (!attack.data.formId||attack.data.formId===x.formId),'MULTIATTACK_COMPONENT_UNAVAILABLE');
    }
  }
  must((x.fixedDieValue!==undefined)===(x.type==='fixed_attack_die'),'FIXED_DIE_CONFIGURATION_INVALID');
  if(x.type==='fixed_attack_die')must(x.cost && x.sourceItemId && x.timing==='other','FIXED_DIE_ITEM_AND_COST_REQUIRED');
  if(x.type==='teleport')must(x.range!=null,'TELEPORT_RANGE_REQUIRED');
  if(x.type==='frighten')must(x.range!=null&&x.durationSeconds!=null&&x.saveDc!=null&&x.saveAbility,'SAVE_CONFIGURATION_REQUIRED');
  if(x.type==='roll_bonus')must(x.timing==='reaction'&&x.range!=null&&(x.bonusAbility!=null)!==(x.fixedBonus!=null),'REACTION_RULE_REQUIRED');
  const {actorId,actionId,name,...data}=x;
  await insert(ctx,'action_definition',actionId,actorId,name,data);
  for(const v of x.payloadVariants??[])await ctx.link(actionId,'permits_payload',v.spellId);
  if(x.formId)await ctx.link(actionId,'requires_form',x.formId);
  for(const id of new Set(x.excludedForms??[]))await ctx.link(actionId,'excludes_form',id);
  if(x.sourceItemId)await ctx.link(actionId,'requires_item',x.sourceItemId);
  if(x.cost)await ctx.link(actionId,'uses_pool',x.cost.poolId);
  must(!x.substitutions||x.type==='multiattack','SUBSTITUTIONS_REQUIRE_MULTIATTACK');
  for(const id of new Set([...(x.attackSequence??[]),...(x.substitutions??[]).map(s=>s.attackId)]))await ctx.link(actionId,'includes_attack',id);
  return {actionId};
}
export async function applyCondition(ctx,actorId,key,enabled,sourceId,expiresAt=null){
  const a=await actor(ctx,actorId),p=await sheet(ctx,actorId);
  let sources=[...(a.data.conditionSources??[])];
  if(enabled){
    must(!p.effects.some(r=>r.data.target==='condition_immunity' && r.data.key===key),'CONDITION_IMMUNE');
    if(!sources.some(s=>s.key===key&&s.sourceId===sourceId))sources.push({key,sourceId,expiresAt});
  }else sources=sources.filter(s=>!(s.key===key&&s.sourceId===sourceId));
  const preexisting=a.data.conditions.filter(c=>!(a.data.conditionSources??[]).some(s=>s.key===c));
  const conditions=[...new Set([...preexisting,...sources.map(s=>s.key)])];
  const ends=conditions.some(c=>['incapacitated','unconscious','stunned','paralyzed','petrified'].includes(c));
  if(ends&&a.data.concentrationCastId)await ctx.unlink(a.id,'concentrates_on',a.data.concentrationCastId);
  const next=enabled && ['incapacitated','unconscious','stunned','paralyzed','petrified'].includes(key)?await interruptIfPending(ctx,a,'actor_incapacitated'):a.data;
  await save(ctx,a,{...next,conditionSources:sources,conditions,concentrationCastId:ends?null:next.concentrationCastId});
  return {actorId,key,conditions};
}
async function setExhaustion(ctx,x){
  const a=await actor(ctx,x.actorId),p=await sheet(ctx,a.id);
  must(!x.enabled || !p.effects.some(e=>e.data.target==='condition_immunity' && e.data.key==='exhaustion'),'CONDITION_IMMUNE');
  const level=x.level??(x.enabled?null:0);
  must(level!==null && x.enabled===(level>0),'EXHAUSTION_LEVEL_REQUIRED');
  must(!x.customExhaustionRule || a.data.rulesProfile==='custom'&&x.adjudicationSource,'PINNED_EXHAUSTION_RULE_REQUIRED');
  must(!x.adjudicationSource || x.customExhaustionRule,'CUSTOM_EXHAUSTION_RULE_REQUIRED');
  const source='manual:'+x.sourceId,conditions=a.data.conditions.filter(k=>k!=='exhaustion');
  const conditionSources=(a.data.conditionSources??[]).filter(s=>s.key!=='exhaustion');
  if(level>0){conditions.push('exhaustion');conditionSources.push({key:'exhaustion',sourceId:source,expiresAt:null});}
  await save(ctx,a,{...a.data,conditions,conditionSources,exhaustionLevel:level,exhaustionSource:source,
    ...(x.customExhaustionRule?{customExhaustionRule:x.customExhaustionRule,exhaustionAdjudicationSource:x.adjudicationSource}:{})});
  return {actorId:a.id,key:'exhaustion',before:p.exhaustion,after:(await sheet(ctx,a.id)).exhaustion,conditions};
}
const destination=fields({label:named(200),distanceFeet:whole(0,1200),visible:boolean,unoccupied:boolean,grounded:boolean,
  availableSize:option(...sizeOrder),passengers:whole(0,100),permittedByBarriers:boolean},['label','distanceFeet','visible','unoccupied','grounded','availableSize','passengers','permittedByBarriers']);
const targets=arrayOf(fields({actorId:identifier,distanceFeet:whole(0,1200),hears:boolean},['actorId','distanceFeet','hears']),24);
const reaction=fields({rollId:identifier,targetActorId:identifier,distanceFeet:whole(0,1200),visible:boolean,windowOpen:{const:true}},
  ['rollId','targetActorId','distanceFeet','visible','windowOpen']);
async function execute(ctx,x){
  const a=await actor(ctx,x.actorId),action=await owned(ctx,'action_definition',x.actionId,a.id),d=action.data,p=await sheet(ctx,a.id);
  must(!['disabled','dormant'].includes(a.data.activityState),'ACTOR_INACTIVE');
  must(!p.conditions.some(c=>['incapacitated','unconscious','stunned','paralyzed','petrified'].includes(c)),'ACTOR_CANNOT_ACT');
  must(d.type!=='fixed_attack_die','DECLARE_SUBSTITUTION_WITH_ATTACK');
  must(matchesForm(d,p.ruleFormIds),'ACTION_FORM_INELIGIBLE');
  must(await gearEligible(ctx,d.sourceItemId,a.id,d.requiresAttunement,d.requiresOperating),'ACTION_ITEM_INELIGIBLE');
  must((x.destination!=null)===(d.type==='teleport') && (x.targets!=null)===(d.type==='frighten') && (x.reaction!=null)===(d.type==='roll_bonus'),'ACTION_ARGUMENT_MISMATCH');
  const data={actionId:action.id,actorId:a.id,formId:p.formId,type:d.type,timing:d.timing,sourceItemId:d.sourceItemId??null,
    recordedAt:ctx.now,commandId:ctx.commandId,costs:[]};
  if(d.type==='multiattack'){
    const encounter=a.data.encounterId?await ctx.get('encounter',a.data.encounterId):null;
    must(encounter||a.data.gameSeconds!=null,'GAME_CLOCK_UNKNOWN');
    data.remainingAttacks=[...d.attackSequence];data.substitutions=(d.substitutions??[]).map(s=>({...s,remaining:s.maximum}));data.attemptIds=[];data.encounterId=encounter?.id??null;
    data.turn=encounter?[encounter.data.round,encounter.data.index]:null;data.startedAt=a.data.gameSeconds;
    await save(ctx,a,{...a.data,activeMultiattackId:x.useId});
  }else if(d.type==='teleport'){
    const dest=x.destination;
    const form=p.formId?await owned(ctx,'form',p.formId,a.id):null;
    must(dest.visible&&dest.unoccupied&&dest.permittedByBarriers&&dest.passengers===0&&dest.distanceFeet<=d.range,'TELEPORT_DESTINATION_INVALID');
    must(!form?.data.grounded||dest.grounded,'GROUNDED_DESTINATION_REQUIRED');
    if(form?.data.size)must(sizeOrder.indexOf(dest.availableSize)>=sizeOrder.indexOf(form.data.size),'DESTINATION_TOO_SMALL');
    data.from=a.data.position??null;data.destination=dest;
    await save(ctx,a,{...a.data,position:dest.label});
  }else if(d.type==='frighten'){
    must(x.targets.length>0&&new Set(x.targets.map(t=>t.actorId)).size===x.targets.length,'ACTION_TARGETS_INVALID');
    const chosen=[];
    for(const t of x.targets){
      const target=await actor(ctx,t.actorId);
      must(t.hears && t.distanceFeet<=d.range && target.id!==a.id,'ACTION_TARGET_INELIGIBLE');
      must(target.data.gameSeconds!=null,'TARGET_CLOCK_UNKNOWN');
      chosen.push({actorId:t.actorId,status:'pending_save',clockAtUse:target.data.gameSeconds,expiresAt:target.data.gameSeconds+d.durationSeconds,rollIds:[]});
    }
    data.targets=chosen;data.save={ability:d.saveAbility,dc:d.saveDc};
  }else if(d.type==='roll_bonus'){
    const r=x.reaction,roll=await owned(ctx,'roll',r.rollId,r.targetActorId);
    must(['skill','ability','save'].includes(roll.data.kind) && roll.data.status==='resolved' &&
      !roll.data.appliedUseId && !roll.data.adjudicated && r.visible && r.distanceFeet<=d.range,'ROLL_REACTION_INELIGIBLE');
    const target=await actor(ctx,r.targetActorId);
    must((a.data.encounterId??null)===(target.data.encounterId??null) &&
      (roll.data.reactionWindow??null)===await reactionRollWindow(ctx,target),'ROLL_REACTION_WINDOW_CLOSED');
    const bonus=d.bonusAbility?p.abilities[d.bonusAbility].modifier:d.fixedBonus;
    must(bonus!=null,'REACTION_BONUS_UNKNOWN');
    const before=roll.data.total,after=before+bonus;
    await save(ctx,roll,{...roll.data,originalTotal:before,total:after,appliedUseId:x.useId,
      text:roll.data.text+'; '+action.title+' '+(bonus>=0?'+':'')+bonus+' = '+after});
    data.rollId=roll.id;data.targetActorId=r.targetActorId;data.modifier=bonus;data.originalTotal=before;data.finalTotal=after;
  }
  await executeLinkedAction(ctx,a,action,x,data);
  await useArsenal(ctx,a,action,x,data,p,snapshotComponents);
  await useTiming(ctx,a,d.timing);
  if(d.cost)data.costs.push(await spend(ctx,a.id,d.cost.poolId,d.cost.amount));
  await insert(ctx,'action_use',x.useId,a.id,action.title,data);
  await ctx.link(x.useId,'used_action',action.id);
  if(data.castId)await ctx.link(x.useId,'delivered_cast',data.castId);
  if(data.deflection)await ctx.link(x.useId,'deflected_attempt',data.deflection.attemptId);
  if(data.rollId)await ctx.link(x.useId,'adjusted_roll',data.rollId);
  for(const t of data.targets??[])await ctx.link(x.useId,'target',t.actorId);
  return {useId:x.useId,...data};
}
async function saveAgainst(ctx,x){
  const use=await owned(ctx,'action_use',x.useId,x.actorId);
  must(use.data.type==='frighten','ACTION_HAS_NO_SAVE');
  const target=await actor(ctx,x.targetActorId);
  const detail=use.data.targets.find(t=>t.actorId===target.id);
  must(detail && ['pending_save','frightened'].includes(detail.status),'NO_PENDING_SAVE');
  const time=target.data.gameSeconds;
  must(time!=null&&time<detail.expiresAt,'ACTION_EFFECT_EXPIRED');
  const roll=await owned(ctx,'roll',x.rollId,target.id);
  must(roll.data.kind==='save' && roll.data.key===use.data.save.ability && !roll.data.adjudicated &&
    roll.data.status==='resolved' && roll.data.forUseId===use.id && !detail.rollIds.includes(x.rollId),'SAVE_EVIDENCE_REQUIRED');
  let turnMarker=null;
  if(detail.status==='frightened'){
    const enc=target.data.encounterId?await ctx.get('encounter',target.data.encounterId):null;
    must(x.endOfTurnConfirmed && enc?.data.active && enc.data.participants[enc.data.index]===target.id,'END_OF_TARGET_TURN_REQUIRED');
    turnMarker=enc.id+':'+enc.data.round+':'+enc.data.index;
    must(detail.lastRepeatTurn!==turnMarker,'SAVE_ALREADY_RESOLVED_THIS_TURN');
  }
  const saved=roll.data.total>=use.data.save.dc,immune=(await sheet(ctx,target.id)).effects.some(r=>r.data.target==='condition_immunity'&&r.data.key==='frightened');
  const status=immune?'immune':saved?'saved':'frightened';
  await applyCondition(ctx,target.id,'frightened',status==='frightened',use.id,detail.expiresAt);
  await save(ctx,roll,{...roll.data,adjudicated:true});
  await save(ctx,use,{...use.data,targets:use.data.targets.map(t=>t.actorId!==target.id?t:{...t,status,rollIds:[...t.rollIds,roll.id],lastRepeatTurn:turnMarker})});
  await ctx.link(use.id,'resolved_save',roll.id);
  return {useId:use.id,targetActorId:target.id,status,rollId:roll.id,total:roll.data.total,dc:use.data.save.dc};
}
export const actionOperations=Object.freeze({
  define_action:op('combat',actionSchema,define),
  use_action:op('combat',fields({...linkedArguments,load:loadChoice,actorId:identifier,actionId:identifier,useId:identifier,declared:{const:true},destination,targets,reaction},['actorId','actionId','useId','declared']),execute),
  resolve_action_save:op('combat',fields({actorId:identifier,useId:identifier,targetActorId:identifier,rollId:identifier,endOfTurnConfirmed:boolean},['actorId','useId','targetActorId','rollId']),saveAgainst),
  resolve_attack_rider:op('combat',fields({actorId:identifier,attemptId:identifier,targetActorId:identifier,rollId:identifier,feet:whole(0,200),destination:named(160),source:named(300)},['actorId','attemptId','targetActorId','rollId','feet','source']),async(ctx,x)=>{
    const attempt=await owned(ctx,'attack_attempt',x.attemptId,x.actorId),d=attempt.data,push=d.preparedPush;
    const target=await actor(ctx,x.targetActorId),earlier=d.targetResults.find(r=>r.targetActorId===target.id);
    must(push && earlier?.decision==='hit' && !earlier.pushResult,'NO_PENDING_ATTACK_RIDER');
    const roll=await owned(ctx,'roll',x.rollId,target.id);
    must(roll.data.forRiderAttemptId===attempt.id && roll.data.status==='resolved' && !roll.data.adjudicated,'SAVE_EVIDENCE_REQUIRED');
    const saved=roll.data.total>=push.dc;
    must(x.feet<=(saved?0:push.feet) && Boolean(x.destination)===(x.feet>0),'INVALID_RESOLVED_MOVEMENT');
    const current=await sheet(ctx,target.id),immune=current.effects.some(e=>e.data.target==='condition_immunity' && e.data.key==='prone');
    if(x.feet)await save(ctx,target,{...target.data,position:x.destination});
    const prone=!saved && push.prone && !immune;
    if(prone)await applyCondition(ctx,target.id,'prone',true,attempt.id,null);
    const result={saved,dc:push.dc,acceptedFeet:x.feet,destination:x.destination??null,prone,rollId:roll.id,source:x.source};
    await save(ctx,roll,{...roll.data,adjudicated:true});
    await save(ctx,attempt,{...d,targetResults:d.targetResults.map(r=>r.targetActorId===target.id?{...r,riderStatus:'resolved',pushResult:result}:r)});
    await ctx.link(attempt.id,'resolved_rider',roll.id);return result;
  }),
  set_condition:op('combat',fields({actorId:identifier,key:identifier,enabled:boolean,sourceId:identifier,level:whole(0,6),
    customExhaustionRule:option('legacy_2014','revised_2024','manual'),adjudicationSource:named(300)},['actorId','key','enabled','sourceId']),async(ctx,x)=>{
    ctx.require('combat',x.actorId);
    if(x.key.toLowerCase()==='exhaustion')return setExhaustion(ctx,x);
    must(x.level===undefined && !x.customExhaustionRule && !x.adjudicationSource,'EXHAUSTION_FIELDS_REQUIRE_EXHAUSTION');
    // Manually entered declarations carry an independent namespace from effect-generated conditions.
    return applyCondition(ctx,x.actorId,x.key.toLowerCase(),x.enabled,'manual:'+x.sourceId);
  })
});
