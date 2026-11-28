import {actor,arrayOf,boolean,checkDiceOwner,d20Evidence,fields,gearEligible,identifier,must,named,op,option,owned,rollPolicy,save,sheet,useTiming,whole} from './cloud-mechanics-core.mjs';
import {physicalChargePools} from './cloud-item-pools.mjs';
import {observed} from './cloud-mechanics-rolls.mjs';

const sizes=['tiny','small','medium','large','huge','gargantuan'];
const size=option(...sizes);
const hand=option('left','right');
const limit=fields({size,movementFeet:whole(0,1200),heightFeet:whole(0,1200)},['size','movementFeet','heightFeet']);
export const bandsDefinitions={bandsPoolId:identifier,gravityLimits:arrayOf(limit,6),maximumDurationSeconds:whole(1,86400),sizeCap:size};
export const bandsArguments={
  bands:fields({type:option('attempt','extend'),targetActorId:identifier,distanceFeet:whole(0,1200),visible:boolean,hit:d20Evidence,resist:d20Evidence},
    ['type','targetActorId','distanceFeet','visible']),
  gravity:fields({type:option('move','hold','transfer','release'),targetActorId:identifier,distanceFeet:whole(0,1200),endDistanceFeet:whole(0,1200),
    movementFeet:whole(0,1200),heightFeet:whole(0,1200),destination:named(160),hand,freeHand:{const:true}},
    ['type','targetActorId','distanceFeet'])
};

export async function validateBands(ctx,x){
  if(x.type==='bands_control'){
    must(x.sourceItemId&&x.timing==='action'&&x.cost?.amount===1&&x.range&&x.fixedBonus!=null&&x.durationSeconds&&x.maximumDurationSeconds>=x.durationSeconds&&x.sizeCap&&x.saveAbility==='STR'&&x.saveDc,'BANDS_CONFIGURATION_REQUIRED');
  }else must(x.maximumDurationSeconds===undefined&&x.sizeCap===undefined,'UNEXPECTED_BANDS_CONFIGURATION');
  if(x.type==='gravity_move'){
    must(x.sourceItemId&&x.timing==='action'&&x.range&&x.bandsPoolId&&!x.cost&&x.gravityLimits?.length,'GRAVITY_CONFIGURATION_REQUIRED');
    must(new Set(x.gravityLimits.map(v=>v.size)).size===x.gravityLimits.length,'GRAVITY_SIZE_LIMIT_DUPLICATE');
  }else must(x.bandsPoolId===undefined&&x.gravityLimits===undefined,'UNEXPECTED_GRAVITY_CONFIGURATION');
  const poolId=x.type==='bands_control'?x.cost.poolId:x.type==='gravity_move'?x.bandsPoolId:null;
  if(poolId){
    const pool=await owned(ctx,'resource_pool',poolId,x.actorId),item=await owned(ctx,'item_instance',x.sourceItemId,x.actorId);
    must(pool.data.type==='charge'&&physicalChargePools(item.data).includes(poolId)&&(!pool.data.sourceItemId||pool.data.sourceItemId===item.id),'BANDS_MUST_USE_PHYSICAL_POOL');
  }
}

function d20(ctx,a,p,key,modifier,evidence,attack=false){
  must(evidence&&Number.isSafeInteger(modifier),'BANDS_ROLL_REQUIRED');
  checkDiceOwner(ctx,a,attack?'attack':key.startsWith('skill:')?'skill':'ability',evidence.origin);
  const mode=rollPolicy(p.effects,key,'normal');
  return observed(key,mode.count,20,modifier,mode.mode==='normal'?'sum':mode.mode==='advantage'?'highest':'lowest',evidence,attack);
}
async function immunity(ctx,target){
  const enc=target.data.encounterId?await ctx.get('encounter',target.data.encounterId):null;
  if(!enc?.data.active)return {outsideEncounter:true};
  const i=enc.data.participants.indexOf(target.id);
  must(i>=0,'BANDS_TARGET_TURN_UNKNOWN');
  return {encounterId:enc.id,round:enc.data.round+(i<=enc.data.index?1:0),index:i};
}
async function blocked(ctx,target,prior){
  if(!prior)return false;
  if(prior.outsideEncounter)return true;
  const e=await ctx.get('encounter',prior.encounterId);
  return e?.data.active && (e.data.round<prior.round||e.data.round===prior.round&&e.data.index<=prior.index);
}
const remaining=(binding,target)=>binding?.targetActorId===target.id&&target.data.gameSeconds!=null&&binding.expiresAt>target.data.gameSeconds&&
  target.data.conditionSources?.some(s=>s.key==='restrained'&&s.sourceId===binding.useId);
async function update(ctx,item,d){await save(ctx,item,{...item.data,...d});}
async function resisted(ctx,item,target){
  await update(ctx,item,{bandBinding:null,bandImmunities:{...item.data.bandImmunities,[target.id]:await immunity(ctx,target)}});
}
async function applyBands(ctx,a,action,x,result,condition){
  const d=action.data,r=x.bands,target=await actor(ctx,r.targetActorId),item=await owned(ctx,'item_instance',d.sourceItemId,a.id);
  ctx.require('combat',target.id,true);
  must(target.id!==a.id&&r.visible&&r.distanceFeet<=d.range,'BANDS_TARGET_INELIGIBLE');
  const tp=await sheet(ctx,target.id),n=sizes.indexOf(tp.size);
  must(n>=0&&n<=sizes.indexOf(d.sizeCap)&&target.data.gameSeconds!=null,'BANDS_SIZE_OR_CLOCK_UNKNOWN');
  const binding=item.data.bandBinding;
  if(r.type==='extend'){
    must(!r.hit&&!r.resist&&binding?.actorId===a.id&&binding.actionId===action.id&&remaining(binding,target),'NO_CURRENT_BAND_RESTRAINT');
    const expiresAt=binding.expiresAt+d.durationSeconds;
    must(expiresAt-binding.startedAt<=d.maximumDurationSeconds,'BANDS_DURATION_LIMIT');
    await condition(ctx,target.id,'restrained',false,binding.useId);
    await condition(ctx,target.id,'restrained',true,binding.useId,expiresAt);
    await update(ctx,item,{bandBinding:{...binding,expiresAt}});result.bandResult={type:r.type,targetId:target.id,expiresAt};return;
  }
  const oldTarget=binding?await ctx.get('actor',binding.targetActorId):null;
  must(!oldTarget||!remaining(binding,oldTarget),'BANDS_ALREADY_HOLD_A_TARGET');
  must(!await blocked(ctx,target,item.data.bandImmunities?.[target.id]),'BANDS_TEMPORARY_IMMUNITY');
  const ap=await sheet(ctx,a.id),hit=d20(ctx,a,ap,'attack:'+action.id,d.fixedBonus,r.hit,true);
  must(tp.ac.value!=null,'TARGET_AC_UNKNOWN');
  const struck=hit.natural===20||hit.natural!==1&&hit.total>=tp.ac.value;
  if(!struck){must(!r.resist,'MISS_HAS_NO_RESISTANCE_ROLL');result.bandResult={targetId:target.id,hit,restrained:false};return;}
  const check=d20(ctx,target,tp,'ability:STR',tp.checks.STR.value,r.resist);
  const isImmune=tp.effects.some(e=>e.data.target==='condition_immunity'&&e.data.key==='restrained');
  const restrained=check.total<d.saveDc&&!isImmune;
  if(restrained){
    const expiresAt=target.data.gameSeconds+d.durationSeconds;
    await condition(ctx,target.id,'restrained',true,x.useId,expiresAt);
    await update(ctx,item,{bandBinding:{useId:x.useId,actionId:action.id,actorId:a.id,targetActorId:target.id,poolId:d.cost.poolId,startedAt:target.data.gameSeconds,expiresAt,hand:null,heightFeet:0}});
  }else await resisted(ctx,item,target);
  result.bandResult={targetId:target.id,hit,check,restrained};
}
async function move(ctx,a,action,x,result){
  const d=action.data,r=x.gravity,item=await owned(ctx,'item_instance',d.sourceItemId,a.id),target=await actor(ctx,r.targetActorId),b=item.data.bandBinding;
  ctx.require('combat',target.id,true);
  must(b?.actorId===a.id&&b.poolId===d.bandsPoolId&&remaining(b,target),'OWN_PHYSICAL_BAND_RESTRAINT_REQUIRED');
  const source=await owned(ctx,'action_definition',b.actionId,a.id);
  must(source.data.type==='bands_control'&&source.data.sourceItemId===item.id&&source.data.cost?.poolId===d.bandsPoolId,'BAND_RESTRAINT_SOURCE_MISMATCH');
  const type=r.type;
  if(type==='release'){
    must(b.hand&&!r.hand&&!r.destination&&r.movementFeet===undefined&&r.heightFeet===undefined,'GRAVITY_RELEASE_INVALID');
    await update(ctx,item,{bandBinding:{...b,hand:null,heightFeet:0}});
    result.gravityResult={type,targetId:target.id,fallHeightFeet:b.heightFeet};return 'other';
  }
  must(r.distanceFeet<=d.range,'GRAVITY_CONTROL_OUT_OF_RANGE');
  const form=await sheet(ctx,target.id),limits=d.gravityLimits.find(v=>v.size===form.size);
  must(limits,'GRAVITY_SIZE_UNSUPPORTED');
  if(type==='hold'){
    must(b.hand&&!r.hand&&!r.destination&&r.movementFeet===undefined&&r.heightFeet===undefined&&r.endDistanceFeet===undefined,'GRAVITY_STATIONARY_HOLD_ONLY');
    result.gravityResult={type,targetId:target.id,hand:b.hand,heightFeet:b.heightFeet};return 'other';
  }
  if(type==='transfer'){
    must(b.hand&&r.hand&&b.hand!==r.hand&&r.freeHand&&!r.destination&&r.movementFeet===undefined&&r.heightFeet===undefined&&r.endDistanceFeet===undefined,'GRAVITY_TRANSFER_INVALID');
    await update(ctx,item,{bandBinding:{...b,hand:r.hand}});result.gravityResult={type,targetId:target.id,hand:r.hand};return;
  }
  must(r.hand&&(b.hand?b.hand===r.hand:r.freeHand),'GRAVITY_CONTROLLING_HAND_UNAVAILABLE');
  must(r.destination&&r.endDistanceFeet!==undefined&&r.endDistanceFeet<=d.range&&r.movementFeet>0&&r.heightFeet!==undefined&&
    r.movementFeet<=limits.movementFeet&&r.heightFeet<=limits.heightFeet&&Math.abs(b.heightFeet-r.heightFeet)<=r.movementFeet&&
    Math.abs(r.endDistanceFeet-r.distanceFeet)<=r.movementFeet,'GRAVITY_MOVEMENT_OR_HEIGHT_EXCEEDED');
  await save(ctx,target,{...target.data,position:r.destination});
  await update(ctx,item,{bandBinding:{...b,hand:r.hand,heightFeet:r.heightFeet}});
  result.gravityResult={type,targetId:target.id,hand:r.hand,distanceFeet:r.movementFeet,heightFeet:r.heightFeet,destination:r.destination};
}
export async function useBands(ctx,a,action,x,data,condition){
  const t=action.data.type;
  must((t==='bands_control')===Boolean(x.bands)&&(t==='gravity_move')===Boolean(x.gravity),'BAND_ACTION_ARGUMENT_MISMATCH');
  if(t==='bands_control')await applyBands(ctx,a,action,x,data,condition);
  if(t==='gravity_move')return move(ctx,a,action,x,data);
}
export function bandOperations(condition){
  const request=fields({actorId:identifier,controllerId:identifier,sourceItemId:identifier,targetActorId:identifier,
    reason:option('escape','contest','forced_open','controller_unconscious','out_of_range','turn_ended'),
    distanceFeet:whole(0,1200),check:d20Evidence,defense:d20Evidence,confirmed:{const:true},source:named(300)},
    ['actorId','controllerId','sourceItemId','targetActorId','reason','source']);
  return {resolve_band_control:op('combat',request,async(ctx,x)=>{
    const actorNow=await actor(ctx,x.actorId),controller=await actor(ctx,x.controllerId),target=await actor(ctx,x.targetActorId);
    ctx.require('combat',controller.id,true);ctx.require('combat',target.id,true);
    const item=await owned(ctx,'item_instance',x.sourceItemId,controller.id),b=item.data.bandBinding;
    if(x.reason==='turn_ended'){
      must(x.actorId===target.id&&x.confirmed&&!target.data.encounterId&&item.data.bandImmunities?.[target.id]?.outsideEncounter,'BANDS_TURN_CONFIRMATION_UNAVAILABLE');
      const immunities={...item.data.bandImmunities};delete immunities[target.id];await update(ctx,item,{bandImmunities:immunities});return {targetId:target.id,immunityEnded:true};
    }
    must(b?.actorId===controller.id&&remaining(b,target),'NO_CURRENT_BAND_RESTRAINT');
    const rule=await owned(ctx,'action_definition',b.actionId,controller.id);
    if(x.reason==='escape'){
      must(actorNow.id===target.id&&!x.defense,'BANDS_ESCAPE_INVALID');const p=await sheet(ctx,target.id),check=d20(ctx,target,p,'ability:STR',p.checks.STR.value,x.check);
      await useTiming(ctx,target,'action');
      if(check.total>=rule.data.saveDc){await condition(ctx,target.id,'restrained',false,b.useId);await resisted(ctx,item,target);}
      return {targetId:target.id,check,escaped:check.total>=rule.data.saveDc,fallHeightFeet:check.total>=rule.data.saveDc?b.heightFeet:0};
    }
    must(b.hand,'GRAVITY_CONTROL_INACTIVE');
    if(x.reason==='contest'){
      must(actorNow.id!==controller.id&&x.distanceFeet!==undefined&&x.distanceFeet<=5,'GRAVITY_CONTEST_OUT_OF_RANGE');
      const cp=await sheet(ctx,controller.id),xp=await sheet(ctx,actorNow.id);
      const attacker=d20(ctx,actorNow,xp,'skill:athletics',xp.skills.athletics.value,x.check),defender=d20(ctx,controller,cp,'skill:athletics',cp.skills.athletics.value,x.defense);
      await useTiming(ctx,actorNow,'action');if(attacker.total<=defender.total)return {released:false,attacker,defender};
    }else{
      must(actorNow.id===controller.id&&x.confirmed,'GRAVITY_RELEASE_CONFIRMATION_REQUIRED');
      if(x.reason==='controller_unconscious')must((await sheet(ctx,controller.id)).conditions.includes('unconscious'),'CONTROLLER_CONSCIOUS');
      if(x.reason==='out_of_range')must(x.distanceFeet>rule.data.range,'GRAVITY_STILL_IN_RANGE');
    }
    await update(ctx,item,{bandBinding:{...b,hand:null,heightFeet:0}});return {released:true,reason:x.reason,targetId:target.id,fallHeightFeet:b.heightFeet};
  })};
}
