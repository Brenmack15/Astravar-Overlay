import {formCollectionOperations} from './cloud-form-collection.mjs';
import { checkDiceOwner, diceCategories, stats, damageTypes, standardSkills, identifier, boolean, whole, nullable, named, option, fields, actorSchema, resourceSchema, formSchema, effectSchema, restSchema, must, op, owned, actor, insert, save, transferableSource, gearEligible, activeForm, sheet, healedHp, spend, useTiming, addClock } from './cloud-mechanics-core.mjs';
import { deploymentOperations, eligibleForm, planCompletion, deploymentView, interruptIfPending } from './cloud-form-deployment.mjs';
import { careOperations } from './cloud-mechanics-care.mjs';
import { settleEffectCosts } from './cloud-effect-costs.mjs';
import { requirePlayableExhaustion } from './cloud-mechanics-core.mjs';
import { profileOperations } from './cloud-mechanics-profile.mjs';
import { rollOperations } from './cloud-mechanics-rolls.mjs';
import { combatOperations } from './cloud-mechanics-combat.mjs';
import { actionOperations } from './cloud-mechanics-actions.mjs';
import { spellOperations } from './cloud-mechanics-spells.mjs';

export const MECHANIC_KINDS=Object.freeze({
  actor:'character',care_protocol:'character',care_event:'resources',feature:'character',progression_event:'character',form_deployment:'character', form:'character', effect:'character', resource_pool:'resources', rest_report:'resources',recovery_event:'resources',
  roll:'rolls',roll_batch:'rolls',roll_preset:'rolls',attack:'combat',attack_attempt:'combat',encounter:'combat',
  action_definition:'combat',action_use:'combat',concentration_check:'spells',spell:'spells',spell_grant:'spells',cast_instance:'spells'
});
export async function mechanicActorSheet(ctx,actorId){
  const {effects,...projection}=await sheet(ctx,actorId);
  if(projection.contextTags.includes('pocket-workshop')){
    const context=await sheet(ctx,actorId,['pocket-workshop']);
    projection.rollContexts={'pocket-workshop':{rollModes:context.rollModes,rollRestrictions:context.rollRestrictions,skills:context.skills,checks:context.checks,saves:context.saves,initiative:context.initiative}};
  }
  return projection;
}

export async function mechanicFormPreview(ctx,actorId,formId){
  const a=await actor(ctx,actorId),f=await owned(ctx,'form',formId,actorId),d=f.data;
  const {effects,...projection}=await sheet(ctx,actorId,[],formId);
  const pools=[];
  for(const id of [d.deploymentPoolId,d.integrityPoolId,d.resetPoolId].filter(Boolean)){
    const pool=await owned(ctx,'resource_pool',id,actorId);pools.push({id,current:pool.data.current,maximum:pool.data.maximum,role:pool.data.type});
  }
  return {status:'preview',trackedEncounter:Boolean(a.data.encounterId),deployment:await deploymentView(ctx,a,formId),activeFormId:a.data.activeFormId,formId,projection,pools,activationSeconds:d.activationSeconds,
    durationSeconds:d.durationSeconds??null,requiresWilling:d.requiresWilling??false,
    sourceEligible:await gearEligible(ctx,d.sourceItemId,actorId,d.requiresAttunement,d.requiresOperating)};
}
async function defineActor(ctx,x){
  const {actorId,name,source,role,rulesProfile,level}=x;
  must(!(x.exhaustionLevel>0)||source,'EXHAUSTION_SOURCE_REQUIRED');
  must(x.currentHp==null||x.maxHp==null||x.currentHp<=x.maxHp,'HP_EXCEEDS_CAPACITY');
  must(new Set(x.saveProficiencies??[]).size===(x.saveProficiencies??[]).length,'DUPLICATE_SAVE_PROFICIENCY');
  if(x.initiativeAfterActorId){
    const anchor=await actor(ctx,x.initiativeAfterActorId);
    must(role==='companion' && !anchor.data.initiativeAfterActorId,'INITIATIVE_ANCHOR_INVALID');
  }
  const data={role,rulesProfile,level,initiativeAfterActorId:x.initiativeAfterActorId??null,proficiencyMethod:x.proficiency===undefined && role==='player' && rulesProfile!=='custom'?'level':x.proficiency!=null?'reviewed':'unknown',source:source??null,proficiency:x.proficiency===undefined?
    (role==='player' && rulesProfile!=='custom'?2+Math.floor((level-1)/4):null):x.proficiency,
    abilities:Object.fromEntries(stats.map(stat=>[stat,x.abilities?.[stat]??null])),
    baseAc:x.baseAc??null,maxHp:x.maxHp??null,baselineConstitutionModifier:x.baselineConstitutionModifier??null,currentHp:x.currentHp??null,temporaryHp:x.temporaryHp??null,
    speeds:x.speeds??{},senses:x.senses??{},size:x.size??null,creatureType:x.creatureType??null,gameSeconds:x.gameSeconds??null,saveProficiencies:x.saveProficiencies??[],
    attacksPerAction:x.attacksPerAction??1,attunementCapacity:x.attunementCapacity??null,conditions:[],conditionSources:[],activeFormId:null,formExpiresAt:null,concentrationCastId:null,encounterId:null,
    poolIds:[],effectIds:[],skills:{}};
  data.exhaustionLevel=x.exhaustionLevel===undefined?0:x.exhaustionLevel;
  if(data.exhaustionLevel>0){data.conditions.push('exhaustion');data.exhaustionSource=source;data.conditionSources.push({key:'exhaustion',sourceId:'initial',expiresAt:null});}
  await insert(ctx,'actor',actorId,actorId,name,data);
  if(x.initiativeAfterActorId)await ctx.link(actorId,'initiative_after',x.initiativeAfterActorId);
  return {actorId};
}
function validateResourceRules(data) {
  must(data.maximum==null||data.current==null||data.current<=data.maximum,'POOL_EXCEEDS_MAXIMUM');
  must((data.type==='hit_die')===(data.dieSides!=null) && (data.type==='spell_slot')===(data.slotLevel!=null),'POOL_SHAPE_INVALID');
  must(data.recovery==='recharge_roll'?data.rechargeSides!=null && data.rechargeMinimum!=null && data.rechargeMinimum<=data.rechargeSides && data.maximum===1:data.rechargeSides===undefined && data.rechargeMinimum===undefined,'INVALID_RECHARGE_DIE');
  must(data.type!=='lifetime'||data.recovery==='none','LIFETIME_POOL_CANNOT_RECOVER');
  must(data.recovery!=='edition_hit_dice'||data.type==='hit_die','HIT_DICE_PROFILE_REQUIRED');
  must(data.type!=='integrity'||data.recovery!=='deployment','INTEGRITY_DEPLOYMENT_REFILL_UNSUPPORTED');
}
async function defineResource(ctx,x){
  const a=await actor(ctx,x.actorId);
  validateResourceRules(x);
  must(a.data.poolIds.length<128,'ACTOR_POOL_LIMIT');
  if(x.recoveryActorId && x.recoveryActorId!==a.id){
    must(['short_rest','long_rest','either_rest'].includes(x.recovery) && !['hit_die','spell_slot'].includes(x.type),'EXTERNAL_RECOVERY_TRIGGER_INVALID');
    const trigger=await actor(ctx,x.recoveryActorId),ids=trigger.data.recoveryPoolIds??[];
    must(ids.length<96,'EXTERNAL_RECOVERY_LIMIT');await save(ctx,trigger,{...trigger.data,recoveryPoolIds:[...ids,x.poolId]});
  }
  const {poolId,actorId,name,...config}=x;
  await insert(ctx,'resource_pool',poolId,actorId,name,{...config,dieSides:x.dieSides??null,slotLevel:x.slotLevel??null,recoveryAmount:x.recoveryAmount??null});
  await save(ctx,a,{...a.data,poolIds:[...a.data.poolIds,poolId]});
  if(x.recoveryActorId&&x.recoveryActorId!==a.id)await ctx.link(poolId,'recovers_on_actor_rest',x.recoveryActorId);
  return {poolId,current:x.current,maximum:x.maximum};
}
const resourceRevisionSchema = fields({
  actorId:identifier, poolId:identifier, name:named(160), maximum:nullable(whole()),
  recovery:resourceSchema.properties.recovery, recoveryAmount:nullable(whole(1)),
  recoveryActorId:nullable(identifier), rechargeSides:whole(2,100), rechargeMinimum:whole(1,100), source:named(300)
}, ['actorId','poolId','source']);

async function reviseResource(ctx,x) {
  const p = await owned(ctx,'resource_pool',x.poolId,x.actorId), d = p.data;
  must(Object.keys(x).some(k => !['actorId','poolId','source'].includes(k)), 'RESOURCE_REVISION_EMPTY');
  const next = {...d, originalSource:d.originalSource ?? d.source, source:x.source};
  for (const key of ['maximum','recovery','recoveryAmount','recoveryActorId','rechargeSides','rechargeMinimum'])
    if (Object.hasOwn(x,key)) next[key]=x[key];
  if(next.recovery!=='recharge_roll') {
    must(!Object.hasOwn(x,'rechargeSides') && !Object.hasOwn(x,'rechargeMinimum'),'INVALID_RECHARGE_DIE');
    delete next.rechargeSides; delete next.rechargeMinimum;
  }
  validateResourceRules(next);
  const priorTrigger = d.recoveryActorId && d.recoveryActorId!==p.actorId ? d.recoveryActorId : null;
  const nextTrigger = next.recoveryActorId && next.recoveryActorId!==p.actorId ? next.recoveryActorId : null;
  if(nextTrigger) must(['short_rest','long_rest','either_rest'].includes(next.recovery) && !['hit_die','spell_slot'].includes(next.type),'EXTERNAL_RECOVERY_TRIGGER_INVALID');
  if(priorTrigger!==nextTrigger) {
    if(priorTrigger) {
      const previous=await actor(ctx,priorTrigger);
      await save(ctx,previous,{...previous.data,recoveryPoolIds:(previous.data.recoveryPoolIds??[]).filter(id=>id!==p.id)});
      await ctx.unlink(p.id,'recovers_on_actor_rest',priorTrigger);
    }
    if(nextTrigger) {
      const trigger=await actor(ctx,nextTrigger), ids=trigger.data.recoveryPoolIds??[];
      must(ids.length<96 && !ids.includes(p.id),'EXTERNAL_RECOVERY_LIMIT');
      await save(ctx,trigger,{...trigger.data,recoveryPoolIds:[...ids,p.id]});
      await ctx.link(p.id,'recovers_on_actor_rest',nextTrigger);
    }
  }
  await ctx.put({...p,title:x.name??p.title,data:next});
  return {poolId:p.id,current:next.current,maximum:next.maximum,recovery:next.recovery,source:x.source};
}
async function defineForm(ctx,x,edit=false){
  const a=await actor(ctx,x.actorId);
  const previous=edit?await owned(ctx,'form',x.formId,a.id):null;
  if(previous)must(a.data.activeFormId!==previous.id,'ACTIVE_FORM_EDIT_REQUIRES_REVERSION');
  const suppress=x.suppressedItemIds??[];
  must(new Set(suppress).size===suppress.length && !suppress.includes(x.sourceItemId),'FORM_SUPPRESSION_INVALID');
  for(const id of suppress)must((await ctx.get('item_instance',id))?.life==='active','SUPPRESSED_ITEM_UNAVAILABLE');
  if(x.sourceItemId)must(await ctx.get('item_instance',x.sourceItemId),'FORM_SOURCE_MISSING');
  must((!x.requiresAttunement&&!x.requiresOperating)||x.sourceItemId,'FORM_SOURCE_REQUIRED');
  const poolIds=[x.deploymentPoolId,x.integrityPoolId,x.resetPoolId].filter(Boolean);
  must(new Set(poolIds).size===poolIds.length,'FORM_POOLS_MUST_BE_DISTINCT');
  for(const p of poolIds){
    const pool=await owned(ctx,'resource_pool',p,a.id);
    if(p===x.integrityPoolId)must(pool.data.type==='integrity','INTEGRITY_POOL_REQUIRED');
    if(p===x.resetPoolId)must(pool.data.recovery==='deployment'&&pool.data.maximum!=null,'DEPLOYMENT_POOL_REQUIRED');
  }
  const {actorId,formId,name,...config}=x;
  const data={...config,abilityOverrides:x.abilityOverrides??{},speeds:x.speeds??{},grounded:x.grounded??false};
  if(previous){
    for(const key of ['deploymentPoolId','integrityPoolId','resetPoolId'])if(previous.data[key] && !poolIds.includes(previous.data[key]))await ctx.unlink(formId,'uses_pool',previous.data[key]);
    if(previous.data.sourceItemId && previous.data.sourceItemId!==x.sourceItemId)await ctx.unlink(formId,'requires_item',previous.data.sourceItemId);
    for(const id of previous.data.suppressedItemIds??[])if(!suppress.includes(id))await ctx.unlink(formId,'suppresses_item',id);
    if(previous.data.orderKey!==undefined)data.orderKey=previous.data.orderKey;
    if(previous.data.inheritedRuleFormIds)data.inheritedRuleFormIds=previous.data.inheritedRuleFormIds;
    await ctx.put({...previous,title:name,data});
  }else await insert(ctx,'form',formId,actorId,name,data);
  for(const id of suppress)await ctx.link(formId,'suppresses_item',id);
  for(const id of poolIds)await ctx.link(formId,'uses_pool',id);
  if(x.sourceItemId)await ctx.link(formId,'requires_item',x.sourceItemId);
  return {formId};
}
async function changeForm(ctx,x){
  const a=await actor(ctx,x.actorId), old=a.data.activeFormId;
  must(x.formId!==old,'FORM_ALREADY_SELECTED');
  const form=x.formId?await owned(ctx,'form',x.formId,a.id):null;
  const elapsed=x.elapsedSeconds, config=form?.data;
  const tracked=await planCompletion(ctx,a,form,x);
  if(config && !tracked){
    await eligibleForm(ctx,a,form,x.willing);
    if(config.activationSeconds>6)must(elapsed>=config.activationSeconds && x.uninterrupted===true && !a.data.encounterId,'UNINTERRUPTED_DEPLOYMENT_REQUIRED');
  }
  if(a.data.encounterId && !tracked)must(elapsed<=6,'TRACKED_ENCOUNTER_CLOCK_REQUIRED');
  const next=tracked?.next??await addClock(ctx,a,elapsed);
  must(!config?.durationSeconds||next.gameSeconds!=null,'GAME_CLOCK_UNKNOWN');
  const costs=[];
  if(config?.deploymentPoolId)costs.push(await spend(ctx,a.id,config.deploymentPoolId,1));
  if(config?.resetPoolId){
    const p=await owned(ctx,'resource_pool',config.resetPoolId,a.id);
    await save(ctx,p,{...p.data,current:p.data.maximum});
  }
  if(!tracked)await useTiming(ctx,a,config?.timing??'action');
  const expires=config?.durationSeconds?next.gameSeconds+config.durationSeconds:null;
  await save(ctx,a,{...next,activeFormId:x.formId,formExpiresAt:expires});
  if(old)await ctx.unlink(a.id,'active_form',old);
  if(form)await ctx.link(a.id,'active_form',form.id);
  return {actorId:a.id,previousFormId:old,formId:x.formId,expires,costs,...(tracked?{deploymentId:x.deploymentId,elapsedSeconds:tracked.elapsedSeconds}:{})};
}
async function effect(ctx,x,edit=false){
  const a=await actor(ctx,x.actorId), old=edit?await owned(ctx,'effect',x.effectId,a.id):null;
  must(old||a.data.effectIds.length<128,'ACTOR_EFFECT_LIMIT');
  const numeric=['ability_score','ability_check','skill','save','passive','initiative','ac','max_hp','attack','damage','spell_attack','spell_dc','speed','sense'];
  if(x.charge){
    must(['attack','spell_attack','damage'].includes(x.target) && (x.charge.trigger==='attempt'||x.target==='damage'),'EFFECT_CHARGE_TRIGGER_INVALID');
    const pool=await owned(ctx,'resource_pool',x.charge.poolId,a.id);
    must(['charge','ordinary','lifetime'].includes(pool.data.type),'EFFECT_CHARGE_POOL_INVALID');
    if(x.target==='spell_attack' && x.key!=='all')await owned(ctx,'spell_grant',x.key,a.id);
  }
  if(x.target!=='damage')must([x.damageRoute,x.damageComponentId,x.damageType,x.eachMatchingComponent].every(v=>v===undefined),'DAMAGE_SELECTOR_ON_OTHER_EFFECT');
  if(numeric.includes(x.target)){
    must(['add','set','minimum','maximum'].includes(x.operation)&&x.amount!=null,'NUMERIC_EFFECT_INVALID');
    if(['ability_score','ability_check','save'].includes(x.target))must(x.key==='all'||stats.includes(x.key),'INVALID_ABILITY');
    if(x.target==='speed')must(['all','walk','fly','hover','swim','climb','burrow'].includes(x.key),'INVALID_MOVEMENT');
    if(x.target==='sense')must(['all','darkvision','blindsight','tremorsense','truesight'].includes(x.key),'INVALID_SENSE');
    if(['ac','initiative','max_hp'].includes(x.target))must(x.key==='all','INVALID_CALCULATION_KEY');
    if(x.target==='attack' && x.key!=='all'){
      const attack=await owned(ctx,'attack',x.key,a.id);
      if(x.transfersWithItem)must(attack.data.transfersWithItem && attack.data.sourceItemId===x.sourceItemId,'TRANSFERABLE_ATTACK_TARGET_REQUIRED');
    }
    if(x.target==='damage'){
      must(!x.eachMatchingComponent || x.damageType,'TYPED_COMPONENT_SELECTOR_REQUIRED');
      must(!x.damageComponentId || x.key!=='all','NAMED_DAMAGE_SOURCE_REQUIRED');
      if(x.key!=='all'){
        const route=x.damageRoute??'attack';
        must(route!=='both','NAMED_DAMAGE_ROUTE_REQUIRED');
        const source=await owned(ctx,route,x.key,a.id);
        if(x.transfersWithItem)must(source.data.transfersWithItem && source.data.sourceItemId===x.sourceItemId,'TRANSFERABLE_DAMAGE_TARGET_REQUIRED');
        if(x.damageComponentId)must(source.data.components.some(c=>c.id===x.damageComponentId && c.type!=='healing' &&
          (!x.damageType||c.type===x.damageType||c.variants?.includes(x.damageType))),'DAMAGE_COMPONENT_TARGET_UNAVAILABLE');
      }
    }
  }else if(x.target==='damage_defense'){
    must(damageTypes.includes(x.key)&&['immunity','resistance','vulnerability'].includes(x.operation)&&x.amount===undefined,'DEFENSE_INVALID');
  }else if(x.target==='save_mitigation'){
    must(x.operation==='evade' && stats.includes(x.key) && x.amount===undefined,'SAVE_MITIGATION_INVALID');
  }else if(x.target==='spell_damage_immunity'){
    must(x.operation==='immunity' && x.amount===undefined && (await ctx.get('spell',x.key))?.life==='active','SPELL_IMMUNITY_DEFINITION_REQUIRED');
  }else if(x.target==='condition_immunity'){
    must(x.key!=='all' && x.operation==='immunity' && x.amount===undefined,'CONDITION_IMMUNITY_INVALID');
  }else{
    must(['advantage','disadvantage','keep_highest_three'].includes(x.operation)&&x.amount===undefined,'ROLL_EDGE_INVALID');
  }
  if(x.formId)await owned(ctx,'form',x.formId,a.id);
  if(x.sourceItemId)must(await ctx.get('item_instance',x.sourceItemId),'EFFECT_SOURCE_MISSING');
  await transferableSource(ctx,x);
  must((!x.requiresAttunement&&!x.requiresOperating)||x.sourceItemId,'EFFECT_SOURCE_REQUIRED');
  const {actorId,effectId,name,...config}=x;
  const data={...config,enabled:x.enabled??old?.data.enabled??true,priority:x.priority??0};
  if(old){
    if(old.data.charge && old.data.charge.poolId!==x.charge?.poolId)await ctx.unlink(effectId,'uses_pool',old.data.charge.poolId);
    if(old.data.target==='attack' && old.data.key!=='all' && (x.target!=='attack'||old.data.key!==x.key))await ctx.unlink(effectId,'modifies_attack',old.data.key);
    if(old.data.target==='damage' && old.data.key!=='all' && (x.target!=='damage'||old.data.key!==x.key))await ctx.unlink(effectId,'modifies_damage',old.data.key);
    if(old.data.sourceItemId&&old.data.sourceItemId!==x.sourceItemId)await ctx.unlink(effectId,'requires_item',old.data.sourceItemId);
    if(old.data.formId&&old.data.formId!==x.formId)await ctx.unlink(effectId,'requires_form',old.data.formId);
    if(old.data.target==='spell_damage_immunity' && (x.target!=='spell_damage_immunity'||old.data.key!==x.key))await ctx.unlink(effectId,'applies_to_spell',old.data.key);
    await ctx.put({...old,title:name,data});
  }else{
    await insert(ctx,'effect',effectId,actorId,name,data);
    await save(ctx,a,{...a.data,effectIds:[...a.data.effectIds,effectId]});
  }
  if(x.charge)await ctx.link(effectId,'uses_pool',x.charge.poolId);
  if(x.target==='spell_damage_immunity')await ctx.link(effectId,'applies_to_spell',x.key);
  if(x.target==='attack' && x.key!=='all')await ctx.link(effectId,'modifies_attack',x.key);
  if(x.target==='damage' && x.key!=='all')await ctx.link(effectId,'modifies_damage',x.key);
  if(x.formId)await ctx.link(effectId,'requires_form',x.formId);
  if(x.sourceItemId)await ctx.link(effectId,'requires_item',x.sourceItemId);
  return {effectId};
}
async function hp(ctx,x){
  ctx.require('combat',x.actorId);
  const a=await actor(ctx,x.actorId), before=a.data;
  const form=await activeForm(ctx,a), poolId=form?.data.integrityPoolId;
  let amount=x.amount, after={...before}, absorbed=0, shell=0;
  if(x.kind==='damage'){
    if(poolId && amount){
      const p=await owned(ctx,'resource_pool',poolId,a.id);
      must(p.data.current!=null,'INTEGRITY_UNKNOWN');
      shell=Math.min(amount,p.data.current); amount-=shell;
      await save(ctx,p,{...p.data,current:p.data.current-shell});
      if(p.data.current===shell){after.activeFormId=null;after.formExpiresAt=null;await ctx.unlink(a.id,'active_form',form.id);}
    }
    if(amount){
      must(before.temporaryHp!=null&&before.currentHp!=null,'HP_UNKNOWN');
      absorbed=Math.min(amount,before.temporaryHp);
      after.temporaryHp-=absorbed;after.currentHp=Math.max(0,before.currentHp-(amount-absorbed));
    }
  }else if(x.kind==='heal'){
    must(before.activityState!=='disabled','DISABLED_ACTOR_REQUIRES_RECONSTRUCTION');
    const projection=await sheet(ctx,a.id);
    must(projection.exhaustion.status!=='fatal','FATAL_EXHAUSTION_REQUIRES_RESOLUTION');
    const cap=projection.maxHp;
    must(before.currentHp!=null&&cap!=null,'HP_UNKNOWN');
    after.currentHp=healedHp(before.currentHp,cap,amount);
  }else{
    must(before.temporaryHp===0||x.replaceTemporary===true,'TEMPORARY_HP_DECISION_REQUIRED');
    after.temporaryHp=x.amount;
  }
  if(before.recoveryProtocolId && x.kind==='damage' && after.currentHp===0){
    if(after.activeFormId)await ctx.unlink(a.id,'active_form',after.activeFormId);
    after={...after,activityState:'disabled',activeFormId:null,formExpiresAt:null};
  }
  if(before.activeDeploymentId && before.activityState!=='disabled' && after.activityState==='disabled')
    after=await interruptIfPending(ctx,{...a,data:after},'actor_disabled');
  await save(ctx,a,after);
  let concentrationCheckId=null;
  const qualifyingDamage=x.kind==='damage'?x.amount-shell:0;
  if(qualifyingDamage>0 && before.concentrationCastId){
    const cast=await owned(ctx,'cast_instance',before.concentrationCastId,a.id);
    // Revised core caps this DC at 30; the legacy rule has no such cap.
    const dc=a.data.rulesProfile==='custom'?null:Math.min(a.data.rulesProfile==='revised_2024'?30:Infinity,Math.max(10,Math.floor(qualifyingDamage/2)));
    concentrationCheckId=ctx.commandId;
    await insert(ctx,'concentration_check',concentrationCheckId,a.id,'Concentration saving throw',{
      castId:cast.id,dc,damage:qualifyingDamage,status:'pending',source:ctx.commandId});
    await ctx.link(concentrationCheckId,'for_cast',cast.id);
  }
  return {actorId:a.id,kind:x.kind,concentrationCheckId,beforeHp:before.currentHp,afterHp:after.currentHp,temporaryHp:after.temporaryHp,absorbedByTemporaryHp:absorbed,absorbedByIntegrity:shell,activeFormId:after.activeFormId};
}
async function applyAttemptTarget(ctx,x,isCast=false){
  const attempt=await owned(ctx,isCast?'cast_instance':'attack_attempt',isCast?x.castId:x.attemptId,x.actorId), d=attempt.data;
  const prior=d.targetResults??[];
  must(!d.chargeRelease,'ATTEMPT_CLOSED');
  must(!isCast||!d.deliveryActorId||d.deliveryUseId,'SPELL_AWAITS_DELIVERY_REACTION');
  must(d.targets?.includes(x.targetActorId) && !prior.some(r=>r.targetActorId===x.targetActorId),'TARGET_UNDECLARED_OR_ALREADY_RESOLVED');
  const target=await actor(ctx,x.targetActorId), p=await sheet(ctx,target.id,target.data.contextTags??[]);
  let decision,half=false,applies=true,evasion=null;
  if(d.resolution==='attack'){
    must(d.hit && !x.saveRollId && (d.hit.natural===1||d.hit.natural===20||p.ac.value!=null),'TARGET_AC_UNKNOWN');
    applies=d.hit.natural!==1 && (d.hit.natural===20 || d.hit.total>=p.ac.value);
    decision=applies?'hit':'miss';
  }else if(d.resolution==='save'){
    must(d.save?.success,'SAVE_OUTCOME_CONFIGURATION_REQUIRED');
    const forced=p.rollRestrictions['save:'+d.save.ability]==='AUTOMATIC_SAVE_FAILURE';
    let success=false;
    if(forced)must(!x.saveRollId,'AUTOMATIC_SAVE_REQUIRES_NO_DIE');
    else{
      must(x.saveRollId,'SAVE_ROLL_UNAVAILABLE');
      const roll=await owned(ctx,'roll',x.saveRollId,target.id);
      must(roll.data[isCast?'forCastId':'forAttemptId']===attempt.id && roll.data.kind==='save' && roll.data.status==='resolved' &&
        roll.data.key===d.save.ability && !roll.data.adjudicated,'SAVE_ROLL_UNAVAILABLE');
      success=roll.data.total>=d.save.dc;
      await save(ctx,roll,{...roll.data,adjudicated:true});
    }
    applies=!success || d.save.success!=='none';half=success && d.save.success==='half';
    if(d.save.success==='half' && !p.conditions.some(c=>['incapacitated','unconscious','stunned','petrified','paralyzed'].includes(c))){
      const passive=p.effects.find(r=>r.data.target==='save_mitigation' && r.data.key===d.save.ability && r.data.operation==='evade');
      if(passive){applies=!success;half=!success;evasion=passive.id;}
    }
    decision=forced?'automatic_save_failure':success?'save_succeeded':'save_failed';
  }else{
    must(d.resolution==='automatic'&&!x.saveRollId,'UNSUPPORTED_TARGET_RESOLUTION');decision='automatic';
  }
  must(!applies||!d.components.length||Array.isArray(d.damage),'ATTEMPT_DAMAGE_REQUIRED');
  const byType=new Map();
  for(const c of d.damage??[])byType.set(c.type,(byType.get(c.type)??0)+Math.max(0,c.total));
  if(half && [...byType.values()].filter(value=>value%2!==0).length>1)must(false,'MIXED_DAMAGE_ROUNDING_REQUIRES_ADJUDICATION');
  const components=[];
  for(const [type,rolled] of byType){
    const defenses=p.defenses.filter(r=>r.type===type);
    if(isCast && type!=='healing')for(const r of p.effects)if(r.data.target==='spell_damage_immunity' && r.data.key===d.spellId)defenses.push({type,kind:'immunity',source:r.id});
    const keys=new Set(defenses.map(r=>r.kind));
    const reduction=(d.deflections??[]).filter(r=>r.targetActorId===target.id).flatMap(r=>r.allocation).filter(r=>r.type===type).reduce((n,r)=>n+r.amount,0);
    const reduced=Math.max(0,rolled-reduction);let amount=applies?(half?Math.floor(reduced/2):reduced):0;
    if(keys.has('immunity'))amount=0;
    else { if(keys.has('resistance'))amount=Math.floor(amount/2);if(keys.has('vulnerability'))amount*=2; }
    components.push({type,rolled,reduction,applied:amount,defenses});
  }
  const total=components.filter(c=>c.type!=='healing').reduce((n,c)=>n+c.applied,0);
  const healing=components.filter(c=>c.type==='healing').reduce((n,c)=>n+c.applied,0);
  must(!total || !healing,'MIXED_HEALING_AND_DAMAGE_REQUIRES_ADJUDICATION');
  const applied=total||healing?await hp(ctx,{actorId:target.id,kind:healing?'heal':'damage',amount:healing||total}):null;
  const result={targetActorId:target.id,decision,evasion,ac:d.resolution==='attack'?p.ac.value:null,saveRollId:x.saveRollId??null,
    amount:total,healing,actualHealing:healing?applied.afterHp-applied.beforeHp:0,components,hp:applied,source:x.source,commandId:ctx.commandId};
  const targetResults=[...prior,result], complete=targetResults.length===d.targets.length;
  const settled=await settleEffectCosts(ctx,attempt.actorId,attempt.id,d.effectCharges,decision==='hit'?'confirmed_hit':complete?'miss':'pending');
  if(settled.costs.length)result.effectCosts=settled.costs;
  await save(ctx,attempt,{...d,...(d.effectCharges?.length?{effectCharges:settled.charges,costs:[...d.costs,...settled.costs]}:{}),
    targetResults,outcome:complete?'resolved':'pending',appliedDamage:targetResults.reduce((n,r)=>n+r.amount,0)});
  if(x.saveRollId)await ctx.link(attempt.id,'resolved_save',x.saveRollId);
  if(applied?.concentrationCheckId)await ctx.link(applied.concentrationCheckId,isCast?'triggering_cast':'triggering_attack',attempt.id);
  return {...(isCast?{castId:attempt.id}:{attemptId:attempt.id}),...result,complete};
}
async function completeRest(ctx,x){
  const a=await actor(ctx,x.actorId), long=x.kind==='long_rest';
  must(!['disabled','dormant'].includes(a.data.activityState),'INACTIVE_ACTOR_CANNOT_REST');
  must(x.confirmed && x.elapsedSeconds>=(long?28800:3600) && !a.data.encounterId,'REST_INELIGIBLE');
  must(!long || x.hitDice.length===0,'LONG_REST_CANNOT_SPEND_HIT_DICE');
  must(long || !x.restoreHitDice?.length,'SHORT_REST_CANNOT_RESTORE_HIT_DICE');
  must(!long || a.data.rulesProfile!=='custom','CUSTOM_LONG_REST_REQUIRES_ADJUDICATION');
  const exhaustion=(await sheet(ctx,a.id)).exhaustion;
  requirePlayableExhaustion(exhaustion);
  must(x.exhaustionRecoveryEligible===undefined || long && exhaustion.level>0,'UNEXPECTED_EXHAUSTION_RECOVERY');
  const pools=new Map();
  for(const id of a.data.poolIds)pools.set(id,await owned(ctx,'resource_pool',id,a.id));
  const next=await addClock(ctx,a,x.elapsedSeconds);
  const recovery=long && exhaustion.level>0 && (x.exhaustionRecoveryEligible===true ||
    x.exhaustionRecoveryEligible===undefined && exhaustion.rulesProfile==='revised_2024');
  if(recovery){
    next.exhaustionLevel=exhaustion.level-1;next.exhaustionSource=ctx.commandId;
    if(!next.exhaustionLevel){next.conditions=next.conditions.filter(k=>k!=='exhaustion');next.conditionSources=next.conditionSources.filter(s=>s.key!=='exhaustion');}
  }
  if(a.data.activeFormId && !next.activeFormId)await ctx.unlink(a.id,'active_form',a.data.activeFormId);
  await save(ctx,a,next);
  const stats=await sheet(ctx,a.id),startHp=a.data.currentHp,max=stats.maxHp;
  const con=stats.abilities.CON.modifier;
  let hp=startHp;const dice=[],deltas=[],unresolved=[];
  if(long && exhaustion.level>0 && exhaustion.rulesProfile==='legacy_2014' && x.exhaustionRecoveryEligible===undefined)
    unresolved.push({reason:'legacy_exhaustion_food_and_drink_unconfirmed'});
  const selected=new Set();
  for(const entry of x.hitDice){
    const p=pools.get(entry.poolId);
    checkDiceOwner(ctx,a,'hit_dice',entry.origin);
    must(!selected.has(entry.poolId)&&p?.data.type==='hit_die'&&p.data.dieSides!=null,'HIT_DIE_SELECTION_INVALID');
    selected.add(entry.poolId);
    must(entry.faces.length>0&&p.data.current!=null&&p.data.current>=entry.faces.length&&con!=null&&hp!=null&&max!=null,'HIT_DICE_OR_HP_UNKNOWN');
    for(const face of entry.faces){
      must(face>=1 && face<=p.data.dieSides,'INVALID_HIT_DIE_FACE');
      const healing=Math.max(0,face+con),actual=healedHp(hp,max,healing)-hp; hp+=actual;
      dice.push({poolId:p.id,origin:entry.origin,sides:p.data.dieSides,face,modifier:con,healing,actual});
    }
    const result=await spend(ctx,a.id,p.id,entry.faces.length);
    deltas.push({...result,reason:'hit_dice_spent'});
  }
  const legacyDice=[...pools.values()].filter(p=>p.data.recovery==='edition_hit_dice');
  const legacyRestore=new Map();
  if(long && a.data.rulesProfile==='legacy_2014' && legacyDice.length){
    if(legacyDice.every(p=>p.data.maximum!=null && p.data.current!=null)){
      const allowance=Math.max(1,Math.floor(legacyDice.reduce((sum,p)=>sum+p.data.maximum,0)/2));
      const available=legacyDice.reduce((sum,p)=>sum+p.data.maximum-p.data.current,0);
      const target=Math.min(allowance,available);
      if(legacyDice.length===1 && !x.restoreHitDice?.length)legacyRestore.set(legacyDice[0].id,target);
      else{
        let total=0;
        for(const c of x.restoreHitDice??[]){
          const p=legacyDice.find(r=>r.id===c.poolId);
          must(p && !legacyRestore.has(p.id) && c.amount<=p.data.maximum-p.data.current,'INVALID_HIT_DICE_RESTORATION');
          legacyRestore.set(p.id,c.amount); total+=c.amount;
        }
        must(total===target,'CHOOSE_HIT_DICE_RESTORATION');
      }
    }else{
      must(!x.restoreHitDice?.length,'HIT_DICE_CAPACITY_UNKNOWN');
      unresolved.push({reason:'unknown_hit_dice_allocation',poolIds:legacyDice.map(p=>p.id)});
    }
  }else must(!x.restoreHitDice?.length,'UNEXPECTED_HIT_DICE_RESTORATION');
  for(const [id] of pools){
    const p=await owned(ctx,'resource_pool',id,a.id),d=p.data;
    if(d.recoveryActorId && d.recoveryActorId!==a.id)continue;
    if(!long && d.type==='hit_die')continue;
    const ordinary=d.recovery===x.kind || d.recovery==='either_rest' || (long&&d.recovery==='short_rest');
    const edition=long&&d.recovery==='edition_hit_dice';
    if(!ordinary&&!edition)continue;
    if(d.current==null||d.maximum==null){unresolved.push({poolId:id,reason:'unknown_resource_balance'});continue;}
    const gain=edition&&a.data.rulesProfile==='legacy_2014'?(legacyRestore.get(id)??0):d.recoveryAmount??d.maximum;
    const after=Math.min(d.maximum,d.current+gain);
    if(after!==d.current){
      await save(ctx,p,{...d,current:after});
      deltas.push({poolId:id,before:d.current,after,amount:after-d.current,reason:edition?'hit_dice_recovered':x.kind});
    }
  }
  for(const id of a.data.recoveryPoolIds??[]){
    const p=await ctx.get('resource_pool',id),d=p?.data;
    if(p?.life!=='active'||d.recoveryActorId!==a.id||!(d.recovery===x.kind||d.recovery==='either_rest'||long&&d.recovery==='short_rest'))continue;
    if(d.current==null||d.maximum==null){unresolved.push({poolId:id,reason:'unknown_resource_balance'});continue;}
    const after=Math.min(d.maximum,d.current+(d.recoveryAmount??d.maximum));
    if(after!==d.current){await save(ctx,p,{...d,current:after});deltas.push({poolId:id,before:d.current,after,amount:after-d.current,reason:'linked_actor_rest'});}
  }
  if(long && hp!=null && max!=null)hp=Math.max(hp,max);
  if(long && (hp==null||max==null))unresolved.push({reason:'unknown_hit_points'});
  if(long && a.data.concentrationCastId)await ctx.unlink(a.id,'concentrates_on',a.data.concentrationCastId);
  await save(ctx,a,{...next,currentHp:hp,temporaryHp:long&&next.temporaryHp!=null?0:next.temporaryHp,concentrationCastId:long?null:next.concentrationCastId});
  const reportId=ctx.commandId;
  const report={actorId:a.id,kind:x.kind,status:'completed',elapsedSeconds:x.elapsedSeconds,beforeHp:startHp,afterHp:hp,maxHp:max,
    ...(exhaustion.level>0?{exhaustion:{before:exhaustion.level,after:next.exhaustionLevel,recovered:Boolean(recovery),eligible:x.exhaustionRecoveryEligible??null}}:{}),
    actualHealing:hp==null||startHp==null?null:hp-startHp,hitDice:dice,deltas,unresolved,
    remainingHitDice:await Promise.all([...pools.values()].filter(p=>p.data.type==='hit_die').map(async p=>({poolId:p.id,remaining:(await owned(ctx,'resource_pool',p.id,a.id)).data.current}))),
    commandId:ctx.commandId,recordedAt:ctx.now};
  await insert(ctx,'rest_report',reportId,a.id,long?'Long rest':'Short rest',report);
  for(const id of new Set(deltas.map(d=>d.poolId)))await ctx.link(reportId,'affected_pool',id);
  return {reportId,...report};
}
export const MECHANIC_OPERATIONS=Object.freeze({
  retire_unresolved_effect_charges:{...op('resources',fields({actorId:identifier,useId:identifier,kind:option('attack_attempt','cast_instance'),source:named(300)},
    ['actorId','useId','kind','source']),async(ctx,x)=>{
    const use=await owned(ctx,x.kind,x.useId,x.actorId);
    must(!use.data.chargeRelease && use.data.effectCharges?.some(c=>c.status==='reserved'),'NO_PENDING_EFFECT_CHARGE');
    const settled=await settleEffectCosts(ctx,x.actorId,use.id,use.data.effectCharges,'miss');
    const chargeRelease={source:x.source,commandId:ctx.commandId};
    await save(ctx,use,{...use.data,chargeRelease,outcome:'closed_unresolved',effectCharges:settled.charges});
    return {useId:use.id,chargeRelease,effectCharges:settled.charges};
  }),ownerReview:true},
  set_dice_delegation:{...op('character',fields({actorId:identifier,category:option(...diceCategories),enabled:boolean,source:named(300)},['actorId','category','enabled','source']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId), categories=new Set(a.data.diceDelegations??[]);
    if(x.enabled)categories.add(x.category);else categories.delete(x.category);
    await save(ctx,a,{...a.data,diceDelegations:[...categories].sort()});
    return {actorId:a.id,category:x.category,enabled:x.enabled,source:x.source};
  }),ownerReview:true},
  define_actor:op('character',actorSchema,defineActor),
  set_actor_ability:op('character',fields({actorId:identifier,ability:option(...stats),score:nullable(whole(0,100))},['actorId','ability','score']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId);const before=a.data.abilities[x.ability];
    await save(ctx,a,{...a.data,abilities:{...a.data.abilities,[x.ability]:x.score}});
    return {actorId:a.id,ability:x.ability,before,after:x.score};
  }),
  set_skill_pin:op('character',fields({actorId:identifier,skillId:identifier,pinned:boolean},['actorId','skillId','pinned']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId);
    must(Object.hasOwn(standardSkills,x.skillId)||Object.hasOwn(a.data.skills,x.skillId),'SKILL_UNKNOWN');
    const pins=new Set(a.data.pinnedSkillIds??[]);
    if(x.pinned)pins.add(x.skillId);else pins.delete(x.skillId);
    await save(ctx,a,{...a.data,pinnedSkillIds:[...pins].sort()});
    return {actorId:a.id,skillId:x.skillId,pinned:x.pinned};
  }),
  remove_custom_skill:op('character',fields({actorId:identifier,skillId:identifier},['actorId','skillId']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId);
    must(!Object.hasOwn(standardSkills,x.skillId)&&Object.hasOwn(a.data.skills,x.skillId),'ACTIVE_CUSTOM_SKILL_REQUIRED');
    const skills={...a.data.skills};const prior=skills[x.skillId];delete skills[x.skillId];
    await save(ctx,a,{...a.data,skills,pinnedSkillIds:(a.data.pinnedSkillIds??[]).filter(key=>key!==x.skillId)});
    return {actorId:a.id,skillId:x.skillId,previous:prior};
  }),
  set_skill_training:op('character',fields({actorId:identifier,skillId:identifier,name:named(120),ability:option(...stats),rank:whole(0,2)},['actorId','skillId','name','ability','rank']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId);
    must(!['__proto__','constructor','prototype'].includes(x.skillId) && (!Object.hasOwn(standardSkills,x.skillId)||standardSkills[x.skillId]===x.ability),'SKILL_IDENTITY_CONFLICT');
    must(Object.hasOwn(a.data.skills,x.skillId)||Object.keys(a.data.skills).length<128,'SKILL_LIMIT');
    const train={name:x.name,ability:x.ability,rank:x.rank};await save(ctx,a,{...a.data,skills:{...a.data.skills,[x.skillId]:train}});
    return {skillId:x.skillId,...train};
  }),
  define_resource:op('resources',resourceSchema,defineResource),
  revise_resource:op('resources',resourceRevisionSchema,reviseResource),
  correct_resource_balance:{...op('resources',fields({actorId:identifier,poolId:identifier,before:nullable(whole()),after:nullable(whole()),source:named(300)},['actorId','poolId','before','after','source']),async(ctx,x)=>{
    const p=await owned(ctx,'resource_pool',x.poolId,x.actorId);
    must(p.data.current===x.before && (p.data.maximum==null||x.after==null||x.after<=p.data.maximum),'RESOURCE_CORRECTION_BASELINE_CHANGED');
    if(p.data.effectHolds?.length)must(x.after!=null && x.after>=p.data.effectHolds.reduce((n,h)=>n+h.amount,0),'RESOURCE_HAS_PENDING_EFFECT_CHARGES');
    await save(ctx,p,{...p.data,current:x.after});return {poolId:p.id,before:x.before,after:x.after,source:x.source};
  }),ownerReview:true},
  spend_resource:op('resources',fields({actorId:identifier,poolId:identifier,amount:whole(1)},['actorId','poolId','amount']),async(ctx,x)=>spend(ctx,x.actorId,x.poolId,x.amount)),
  resolve_pool_recharge:op('resources',fields({actorId:identifier,poolId:identifier,face:whole(1,100),origin:option('dm','delegated','app','player_physical','player_digital'),atStartOfTurn:{const:true}},['actorId','poolId','face','origin','atStartOfTurn']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId),p=await owned(ctx,'resource_pool',x.poolId,a.id),d=p.data;
    const enc=a.data.encounterId?await ctx.get('encounter',a.data.encounterId):null;
    must(enc?.data.active && !enc.data.actedThisTurn && enc.data.participants[enc.data.index]===a.id && d.current===0 && d.recovery==='recharge_roll' && x.face<=d.rechargeSides,'RECHARGE_WINDOW_UNAVAILABLE');
    checkDiceOwner(ctx,a,'custom',x.origin);
    const window=enc.id+':'+enc.data.round+':'+enc.data.index;must(d.lastRechargeWindow!==window,'RECHARGE_ALREADY_CHECKED');
    const after=x.face>=d.rechargeMinimum?1:0;
    await save(ctx,p,{...d,current:after,lastRechargeWindow:window});return {poolId:p.id,window,face:x.face,sides:d.rechargeSides,source:x.origin,before:0,after};
  }),
  complete_dawn_recovery:op('resources',fields({actorId:identifier,eventId:identifier,gameSeconds:whole(0,100000000000),confirmed:{const:true},source:named(300)},['actorId','eventId','gameSeconds','confirmed','source']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId);
    must(a.data.gameSeconds===x.gameSeconds && (a.data.lastDawnAt==null||a.data.lastDawnAt<x.gameSeconds),'DAWN_TIME_UNCONFIRMED_OR_RECORDED');
    const deltas=[],unresolved=[];
    for(const id of a.data.poolIds){
      const p=await owned(ctx,'resource_pool',id,a.id),d=p.data;
      if(d.recovery!=='dawn')continue;
      if(d.current==null||d.maximum==null){unresolved.push(id);continue;}
      const after=Math.min(d.maximum,d.current+(d.recoveryAmount??d.maximum));
      if(after!==d.current){await save(ctx,p,{...d,current:after});deltas.push({id,before:d.current,after});}
    }
    await save(ctx,a,{...a.data,lastDawnAt:x.gameSeconds});
    const report={actorId:a.id,trigger:'dawn',gameSeconds:x.gameSeconds,source:x.source,deltas,unresolved,commandId:ctx.commandId};
    await insert(ctx,'recovery_event',x.eventId,a.id,'Dawn recovery',report);
    for(const d of deltas)await ctx.link(x.eventId,'affected_pool',d.id);
    return {eventId:x.eventId,...report};
  }),
  complete_rest:op('resources',restSchema,completeRest),
  ...formCollectionOperations(defineForm),
  define_form:op('character',formSchema,(ctx,x)=>defineForm(ctx,x,false)),
  revise_form:op('character',formSchema,(ctx,x)=>defineForm(ctx,x,true)),
  change_form:op('character',fields({actorId:identifier,formId:nullable(identifier),deploymentId:identifier,elapsedSeconds:whole(0,86400),uninterrupted:boolean,willing:boolean},['actorId','formId','elapsedSeconds']),changeForm),
  advance_actor_time:op('character',fields({actorId:identifier,elapsedSeconds:whole(1,31536000),confirmed:{const:true}},['actorId','elapsedSeconds','confirmed']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId);const next=await addClock(ctx,a,x.elapsedSeconds);
    if(a.data.activeFormId && !next.activeFormId)await ctx.unlink(a.id,'active_form',a.data.activeFormId);
    await save(ctx,a,next);return {actorId:a.id,gameSeconds:next.gameSeconds,formId:next.activeFormId};
  }),
  define_effect:op('character',effectSchema,(ctx,x)=>effect(ctx,x,false)),
  revise_effect:op('character',effectSchema,(ctx,x)=>effect(ctx,x,true)),
  enable_effect:op('character',fields({actorId:identifier,effectId:identifier,enabled:boolean},['actorId','effectId','enabled']),async(ctx,x)=>{
    const e=await owned(ctx,'effect',x.effectId,x.actorId);await save(ctx,e,{...e.data,enabled:x.enabled});return {effectId:e.id,enabled:x.enabled};
  }),
  resolve_concentration_check:op('spells',fields({actorId:identifier,checkId:identifier,rollId:identifier},['actorId','checkId','rollId']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId),pending=await owned(ctx,'concentration_check',x.checkId,a.id),r=await owned(ctx,'roll',x.rollId,a.id);
    must(pending.data.status==='pending' && pending.data.dc!=null && pending.data.castId===a.data.concentrationCastId,'CONCENTRATION_CHECK_UNAVAILABLE');
    must(r.data.forConcentrationId===pending.id && r.data.status==='resolved' && r.data.kind==='save' && r.data.key==='CON' && !r.data.adjudicated,'CONCENTRATION_SAVE_REQUIRED');
    const success=r.data.total>=pending.data.dc;
    if(!success){await ctx.unlink(a.id,'concentrates_on',pending.data.castId);await save(ctx,a,{...a.data,concentrationCastId:null});}
    const status=success?'maintained':'ended';
    await save(ctx,r,{...r.data,adjudicated:true});await save(ctx,pending,{...pending.data,status,rollId:r.id});
    await ctx.link(pending.id,'resolved_save',r.id);return {actorId:a.id,checkId:pending.id,castId:pending.data.castId,status,dc:pending.data.dc,total:r.data.total};
  }),
  resolve_attack_target:op('combat',fields({actorId:identifier,attemptId:identifier,targetActorId:identifier,saveRollId:identifier,source:named(300)},['actorId','attemptId','targetActorId','source']),(ctx,x)=>applyAttemptTarget(ctx,x)),
  resolve_cast_target:op('spells',fields({actorId:identifier,castId:identifier,targetActorId:identifier,saveRollId:identifier,source:named(300)},['actorId','castId','targetActorId','source']),(ctx,x)=>applyAttemptTarget(ctx,x,true)),
  apply_hp_change:op('combat',fields({actorId:identifier,kind:option('damage','heal','temporary'),amount:whole(),replaceTemporary:boolean},['actorId','kind','amount']),hp),
  ...deploymentOperations,...careOperations,...profileOperations,...rollOperations,...combatOperations,...spellOperations,...actionOperations
});
