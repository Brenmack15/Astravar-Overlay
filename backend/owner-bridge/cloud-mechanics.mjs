import { stats, damageTypes, standardSkills, identifier, boolean, whole, nullable, named, option, fields, actorSchema, resourceSchema, formSchema, effectSchema, restSchema, must, op, owned, actor, insert, save, transferableSource, gearEligible, activeForm, sheet, spend, useTiming, addClock } from './cloud-mechanics-core.mjs';
import { rollOperations } from './cloud-mechanics-rolls.mjs';
import { combatOperations } from './cloud-mechanics-combat.mjs';
import { actionOperations } from './cloud-mechanics-actions.mjs';
import { spellOperations } from './cloud-mechanics-spells.mjs';

export const MECHANIC_KINDS=Object.freeze({
  actor:'character', form:'character', effect:'character', resource_pool:'resources', rest_report:'resources',
  roll:'rolls',roll_batch:'rolls',roll_preset:'rolls',attack:'combat',attack_attempt:'combat',encounter:'combat',
  action_definition:'combat',action_use:'combat',spell:'spells',spell_grant:'spells',cast_instance:'spells'
});
export async function mechanicActorSheet(ctx,actorId){
  const {effects,...projection}=await sheet(ctx,actorId);
  if(projection.contextTags.includes('pocket-workshop')){
    const context=await sheet(ctx,actorId,['pocket-workshop']);
    projection.rollContexts={'pocket-workshop':{rollModes:context.rollModes,skills:context.skills,checks:context.checks,saves:context.saves,initiative:context.initiative}};
  }
  return projection;
}

async function defineActor(ctx,x){
  const {actorId,name,source,role,rulesProfile,level}=x;
  must(x.currentHp==null||x.maxHp==null||x.currentHp<=x.maxHp,'HP_EXCEEDS_CAPACITY');
  must(new Set(x.saveProficiencies??[]).size===(x.saveProficiencies??[]).length,'DUPLICATE_SAVE_PROFICIENCY');
  const data={role,rulesProfile,level,source:source??null,proficiency:x.proficiency===undefined?
    (role==='player' && rulesProfile!=='custom'?2+Math.floor((level-1)/4):null):x.proficiency,
    abilities:Object.fromEntries(stats.map(stat=>[stat,x.abilities?.[stat]??null])),
    baseAc:x.baseAc??null,maxHp:x.maxHp??null,currentHp:x.currentHp??null,temporaryHp:x.temporaryHp??null,
    speeds:x.speeds??{},gameSeconds:x.gameSeconds??null,saveProficiencies:x.saveProficiencies??[],
    attacksPerAction:x.attacksPerAction??1,attunementCapacity:x.attunementCapacity??null,conditions:[],conditionSources:[],activeFormId:null,formExpiresAt:null,concentrationCastId:null,encounterId:null,
    poolIds:[],effectIds:[],skills:{}};
  await insert(ctx,'actor',actorId,actorId,name,data);
  return {actorId};
}
async function defineResource(ctx,x){
  const a=await actor(ctx,x.actorId);
  must(x.maximum==null||x.current==null||x.current<=x.maximum,'POOL_EXCEEDS_MAXIMUM');
  must((x.type==='hit_die')===(x.dieSides!=null) && (x.type==='spell_slot')===(x.slotLevel!=null),'POOL_SHAPE_INVALID');
  must(x.type!=='lifetime'||x.recovery==='none','LIFETIME_POOL_CANNOT_RECOVER');
  must(x.recovery!=='edition_hit_dice'||x.type==='hit_die','HIT_DICE_PROFILE_REQUIRED');
  must(x.type!=='integrity'||x.recovery!=='deployment','INTEGRITY_DEPLOYMENT_REFILL_UNSUPPORTED');
  must(a.data.poolIds.length<128,'ACTOR_POOL_LIMIT');
  const {poolId,actorId,name,...config}=x;
  await insert(ctx,'resource_pool',poolId,actorId,name,{...config,dieSides:x.dieSides??null,slotLevel:x.slotLevel??null,recoveryAmount:x.recoveryAmount??null});
  await save(ctx,a,{...a.data,poolIds:[...a.data.poolIds,poolId]});
  return {poolId,current:x.current,maximum:x.maximum};
}
async function defineForm(ctx,x){
  const a=await actor(ctx,x.actorId);
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
  await insert(ctx,'form',formId,actorId,name,{...config,abilityOverrides:x.abilityOverrides??{},speeds:x.speeds??{},grounded:x.grounded??false});
  for(const id of poolIds)await ctx.link(formId,'uses_pool',id);
  if(x.sourceItemId)await ctx.link(formId,'requires_item',x.sourceItemId);
  return {formId};
}
async function changeForm(ctx,x){
  const a=await actor(ctx,x.actorId), old=a.data.activeFormId;
  must(x.formId!==old,'FORM_ALREADY_SELECTED');
  const form=x.formId?await owned(ctx,'form',x.formId,a.id):null;
  const elapsed=x.elapsedSeconds, config=form?.data;
  if(config){
    must(await gearEligible(ctx,config.sourceItemId,a.id,config.requiresAttunement,config.requiresOperating),'FORM_SOURCE_INELIGIBLE');
    if(config.activationSeconds>6)must(elapsed>=config.activationSeconds && x.uninterrupted===true && !a.data.encounterId,'UNINTERRUPTED_DEPLOYMENT_REQUIRED');
    if(config.integrityPoolId){
      const p=await owned(ctx,'resource_pool',config.integrityPoolId,a.id);
      must(p.data.current!=null && p.data.current>0,'FORM_INTEGRITY_UNAVAILABLE');
    }
  }
  const next=await addClock(ctx,a,elapsed);
  must(!config?.durationSeconds||next.gameSeconds!=null,'GAME_CLOCK_UNKNOWN');
  const costs=[];
  if(config?.deploymentPoolId)costs.push(await spend(ctx,a.id,config.deploymentPoolId,1));
  if(config?.resetPoolId){
    const p=await owned(ctx,'resource_pool',config.resetPoolId,a.id);
    await save(ctx,p,{...p.data,current:p.data.maximum});
  }
  await useTiming(ctx,a,config?.timing??'action');
  const expires=config?.durationSeconds?next.gameSeconds+config.durationSeconds:null;
  await save(ctx,a,{...next,activeFormId:x.formId,formExpiresAt:expires});
  if(old)await ctx.unlink(a.id,'active_form',old);
  if(form)await ctx.link(a.id,'active_form',form.id);
  return {actorId:a.id,previousFormId:old,formId:x.formId,expires,costs};
}
async function effect(ctx,x){
  const a=await actor(ctx,x.actorId);
  must(a.data.effectIds.length<128,'ACTOR_EFFECT_LIMIT');
  const numeric=['ability_score','ability_check','skill','save','passive','initiative','ac','attack','spell_attack','spell_dc'];
  if(numeric.includes(x.target)){
    must(['add','set'].includes(x.operation)&&x.amount!=null,'NUMERIC_EFFECT_INVALID');
    if(['ability_score','ability_check','save'].includes(x.target))must(x.key==='all'||stats.includes(x.key),'INVALID_ABILITY');
    if(['ac','initiative'].includes(x.target))must(x.key==='all','INVALID_CALCULATION_KEY');
  }else if(x.target==='damage_defense'){
    must(damageTypes.includes(x.key)&&['immunity','resistance','vulnerability'].includes(x.operation)&&x.amount===undefined,'DEFENSE_INVALID');
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
  await insert(ctx,'effect',effectId,actorId,name,{...config,enabled:x.enabled??true,priority:x.priority??0});
  await save(ctx,a,{...a.data,effectIds:[...a.data.effectIds,effectId]});
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
    must(before.currentHp!=null&&before.maxHp!=null,'HP_UNKNOWN');
    after.currentHp=Math.min(before.maxHp,before.currentHp+amount);
  }else{
    must(before.temporaryHp===0||x.replaceTemporary===true,'TEMPORARY_HP_DECISION_REQUIRED');
    after.temporaryHp=x.amount;
  }
  await save(ctx,a,after);
  return {actorId:a.id,kind:x.kind,beforeHp:before.currentHp,afterHp:after.currentHp,temporaryHp:after.temporaryHp,absorbedByTemporaryHp:absorbed,absorbedByIntegrity:shell,activeFormId:after.activeFormId};
}
async function applyAttackTarget(ctx,x){
  const attempt=await owned(ctx,'attack_attempt',x.attemptId,x.actorId), d=attempt.data;
  must(d.targets?.includes(x.targetActorId) && !d.targetResults.some(r=>r.targetActorId===x.targetActorId),'TARGET_UNDECLARED_OR_ALREADY_RESOLVED');
  const target=await actor(ctx,x.targetActorId), p=await sheet(ctx,target.id,target.data.contextTags??[]);
  let decision,half=false,applies=true;
  if(d.resolution==='attack'){
    must(!x.saveRollId && (d.hit.natural===1||d.hit.natural===20||p.ac.value!=null),'TARGET_AC_UNKNOWN');
    applies=d.hit.natural!==1 && (d.hit.natural===20 || d.hit.total>=p.ac.value);
    decision=applies?'hit':'miss';
  }else if(d.resolution==='save'){
    must(x.saveRollId && d.save?.success,'SAVE_OUTCOME_CONFIGURATION_REQUIRED');
    const roll=await owned(ctx,'roll',x.saveRollId,target.id);
    must(roll.data.forAttemptId===attempt.id && roll.data.kind==='save' && roll.data.status==='resolved' &&
      roll.data.key===d.save.ability && !roll.data.adjudicated,'SAVE_ROLL_UNAVAILABLE');
    const success=roll.data.total>=d.save.dc;
    applies=!success || d.save.success!=='none';half=success && d.save.success==='half';
    decision=success?'save_succeeded':'save_failed';
    await save(ctx,roll,{...roll.data,adjudicated:true});
  }else{
    must(d.resolution==='automatic'&&!x.saveRollId,'UNSUPPORTED_TARGET_RESOLUTION');decision='automatic';
  }
  must(!applies||Array.isArray(d.damage),'ATTEMPT_DAMAGE_REQUIRED');
  const byType=new Map();
  for(const c of d.damage??[])byType.set(c.type,(byType.get(c.type)??0)+Math.max(0,c.total));
  if(half && [...byType.values()].filter(value=>value%2!==0).length>1)must(false,'MIXED_DAMAGE_ROUNDING_REQUIRES_ADJUDICATION');
  const components=[];
  for(const [type,rolled] of byType){
    const defenses=p.defenses.filter(r=>r.type===type), keys=new Set(defenses.map(r=>r.kind));
    let amount=applies?(half?Math.floor(rolled/2):rolled):0;
    if(keys.has('immunity'))amount=0;
    else { if(keys.has('resistance'))amount=Math.floor(amount/2);if(keys.has('vulnerability'))amount*=2; }
    components.push({type,rolled,applied:amount,defenses});
  }
  const total=components.reduce((n,c)=>n+c.applied,0);
  const applied=total?await hp(ctx,{actorId:target.id,kind:'damage',amount:total}):null;
  const result={targetActorId:target.id,decision,ac:d.resolution==='attack'?p.ac.value:null,saveRollId:x.saveRollId??null,
    amount:total,components,hp:applied,source:x.source,commandId:ctx.commandId};
  const targetResults=[...d.targetResults,result], complete=targetResults.length===d.targets.length;
  await save(ctx,attempt,{...d,targetResults,outcome:complete?'resolved':'pending',appliedDamage:targetResults.reduce((n,r)=>n+r.amount,0)});
  if(x.saveRollId)await ctx.link(attempt.id,'resolved_save',x.saveRollId);
  return {attemptId:attempt.id,...result,complete};
}
async function completeRest(ctx,x){
  const a=await actor(ctx,x.actorId), long=x.kind==='long_rest';
  must(x.confirmed && x.elapsedSeconds>=(long?28800:3600) && !a.data.encounterId,'REST_INELIGIBLE');
  must(!long || x.hitDice.length===0,'LONG_REST_CANNOT_SPEND_HIT_DICE');
  must(long || !x.restoreHitDice?.length,'SHORT_REST_CANNOT_RESTORE_HIT_DICE');
  must(!long || a.data.rulesProfile!=='custom','CUSTOM_LONG_REST_REQUIRES_ADJUDICATION');
  const pools=new Map();
  for(const id of a.data.poolIds)pools.set(id,await owned(ctx,'resource_pool',id,a.id));
  const startHp=a.data.currentHp, max=a.data.maxHp;
  const con=x.hitDice.length?(await sheet(ctx,a.id)).abilities.CON.modifier:null;
  let hp=startHp;const dice=[],deltas=[],unresolved=[];
  const selected=new Set();
  for(const entry of x.hitDice){
    const p=pools.get(entry.poolId);
    must(!selected.has(entry.poolId)&&p?.data.type==='hit_die'&&p.data.dieSides!=null,'HIT_DIE_SELECTION_INVALID');
    selected.add(entry.poolId);
    must(entry.faces.length>0&&p.data.current!=null&&p.data.current>=entry.faces.length&&con!=null&&hp!=null&&max!=null,'HIT_DICE_OR_HP_UNKNOWN');
    for(const face of entry.faces){
      must(face>=1 && face<=p.data.dieSides,'INVALID_HIT_DIE_FACE');
      const healing=Math.max(0,face+con),actual=Math.min(healing,max-hp); hp+=actual;
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
  const next=await addClock(ctx,a,x.elapsedSeconds);
  if(a.data.activeFormId && !next.activeFormId)await ctx.unlink(a.id,'active_form',a.data.activeFormId);
  if(long && hp!=null && max!=null)hp=max;
  if(long && (hp==null||max==null))unresolved.push({reason:'unknown_hit_points'});
  if(long && a.data.concentrationCastId)await ctx.unlink(a.id,'concentrates_on',a.data.concentrationCastId);
  await save(ctx,a,{...next,currentHp:hp,temporaryHp:long&&next.temporaryHp!=null?0:next.temporaryHp,concentrationCastId:long?null:next.concentrationCastId});
  const reportId=ctx.commandId;
  const report={actorId:a.id,kind:x.kind,status:'completed',elapsedSeconds:x.elapsedSeconds,beforeHp:startHp,afterHp:hp,
    actualHealing:hp==null||startHp==null?null:hp-startHp,hitDice:dice,deltas,unresolved,
    remainingHitDice:await Promise.all([...pools.values()].filter(p=>p.data.type==='hit_die').map(async p=>({poolId:p.id,remaining:(await owned(ctx,'resource_pool',p.id,a.id)).data.current}))),
    commandId:ctx.commandId,recordedAt:ctx.now};
  await insert(ctx,'rest_report',reportId,a.id,long?'Long rest':'Short rest',report);
  for(const id of new Set(deltas.map(d=>d.poolId)))await ctx.link(reportId,'affected_pool',id);
  return {reportId,...report};
}
export const MECHANIC_OPERATIONS=Object.freeze({
  define_actor:op('character',actorSchema,defineActor),
  set_actor_ability:op('character',fields({actorId:identifier,ability:option(...stats),score:nullable(whole(0,100))},['actorId','ability','score']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId);const before=a.data.abilities[x.ability];
    await save(ctx,a,{...a.data,abilities:{...a.data.abilities,[x.ability]:x.score}});
    return {actorId:a.id,ability:x.ability,before,after:x.score};
  }),
  set_skill_training:op('character',fields({actorId:identifier,skillId:identifier,name:named(120),ability:option(...stats),rank:whole(0,2)},['actorId','skillId','name','ability','rank']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId);
    must(!['__proto__','constructor','prototype'].includes(x.skillId) && (!Object.hasOwn(standardSkills,x.skillId)||standardSkills[x.skillId]===x.ability),'SKILL_IDENTITY_CONFLICT');
    must(Object.hasOwn(a.data.skills,x.skillId)||Object.keys(a.data.skills).length<128,'SKILL_LIMIT');
    const train={name:x.name,ability:x.ability,rank:x.rank};await save(ctx,a,{...a.data,skills:{...a.data.skills,[x.skillId]:train}});
    return {skillId:x.skillId,...train};
  }),
  define_resource:op('resources',resourceSchema,defineResource),
  spend_resource:op('resources',fields({actorId:identifier,poolId:identifier,amount:whole(1)},['actorId','poolId','amount']),async(ctx,x)=>spend(ctx,x.actorId,x.poolId,x.amount)),
  complete_rest:op('resources',restSchema,completeRest),
  define_form:op('character',formSchema,defineForm),
  change_form:op('character',fields({actorId:identifier,formId:nullable(identifier),elapsedSeconds:whole(0,86400),uninterrupted:boolean},['actorId','formId','elapsedSeconds']),changeForm),
  advance_actor_time:op('character',fields({actorId:identifier,elapsedSeconds:whole(1,31536000),confirmed:{const:true}},['actorId','elapsedSeconds','confirmed']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId);const next=await addClock(ctx,a,x.elapsedSeconds);
    if(a.data.activeFormId && !next.activeFormId)await ctx.unlink(a.id,'active_form',a.data.activeFormId);
    await save(ctx,a,next);return {actorId:a.id,gameSeconds:next.gameSeconds,formId:next.activeFormId};
  }),
  define_effect:op('character',effectSchema,effect),
  enable_effect:op('character',fields({actorId:identifier,effectId:identifier,enabled:boolean},['actorId','effectId','enabled']),async(ctx,x)=>{
    const e=await owned(ctx,'effect',x.effectId,x.actorId);await save(ctx,e,{...e.data,enabled:x.enabled});return {effectId:e.id,enabled:x.enabled};
  }),
  resolve_attack_target:op('combat',fields({actorId:identifier,attemptId:identifier,targetActorId:identifier,saveRollId:identifier,source:named(300)},['actorId','attemptId','targetActorId','source']),applyAttackTarget),
  apply_hp_change:op('combat',fields({actorId:identifier,kind:option('damage','heal','temporary'),amount:whole(),replaceTemporary:boolean},['actorId','kind','amount']),hp),
  ...rollOperations,...combatOperations,...spellOperations,...actionOperations
});
