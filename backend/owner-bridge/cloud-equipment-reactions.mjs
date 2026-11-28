import {actor,checkDiceOwner,d20Evidence,fields,gearEligible,identifier,must,option,reactionRollWindow,save,sheet,whole} from './cloud-mechanics-core.mjs';
import {observed} from './cloud-mechanics-rolls.mjs';

export const equipmentReactionDefinition={reactionRule:option('ground_displacement','physical_guard','physical_reflection'),maxReductionFeet:whole(1,1000)};
export const equipmentReactionArguments={
  groundDisplacement:fields({eventId:identifier,forced:{const:true},alongGround:{const:true},distanceFeet:whole(1,100000),reductionFeet:whole(0,100000)},['eventId','forced','alongGround','distanceFeet','reductionFeet']),
  incomingStrike:fields({attemptId:identifier,reflectionRoll:d20Evidence},['attemptId'])
};
const physicalTypes=new Set(['bludgeoning','piercing','slashing']);

// Reactions need positive evidence of a direct physical attack. Delivery and damage
// type alone cannot distinguish an enchanted projectile from an ineligible magical ray.
export function directPhysicalStrike(d) {
  return d.resolution==='attack' && ['melee','ranged'].includes(d.delivery) &&
    ['natural','weapon','physical_weapon','physical_projectile','melee_weapon','ranged_weapon'].includes(d.classification);
}
export function validateEquipmentReaction(x) {
  if(x.type!=='equipment_reaction'){
    must(!x.reactionRule&&x.maxReductionFeet===undefined,'UNEXPECTED_EQUIPMENT_REACTION_RULE');return;
  }
  must(x.reactionRule&&x.timing==='reaction'&&x.sourceItemId&&x.requiresOperating,'EQUIPMENT_REACTION_SOURCE_REQUIRED');
  if(x.reactionRule==='ground_displacement')must(x.maxReductionFeet>0&&!x.cost&&x.fixedBonus===undefined,'GROUND_REACTION_CONFIGURATION_REQUIRED');
  else must(x.cost?.amount===1&&x.maxReductionFeet===undefined&&
    (x.reactionRule==='physical_reflection'?Number.isSafeInteger(x.fixedBonus):x.fixedBonus===undefined),'GUARD_REACTION_CONFIGURATION_REQUIRED');
}
export async function useEquipmentReaction(ctx,a,action,x,data){
  const d=action.data,isRule=d.type==='equipment_reaction',ground=isRule&&d.reactionRule==='ground_displacement';
  must(Boolean(x.groundDisplacement)===ground&&Boolean(x.incomingStrike)===(isRule&&!ground),'EQUIPMENT_REACTION_ARGUMENT_MISMATCH');
  if(!isRule)return;
  must(await gearEligible(ctx,d.sourceItemId,a.id,d.requiresAttunement,true,undefined,true),'REACTION_ARMOR_MUST_BE_WORN');
  if(ground){
    const r=x.groundDisplacement;
    must(r.forced===true&&r.alongGround===true&&r.reductionFeet<=Math.min(d.maxReductionFeet,r.distanceFeet),'INVALID_GROUND_DISPLACEMENT');
    const previous=await ctx.service.s(`SELECT 1 FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND kind='action_use'
      AND actor_id=? AND json_extract(data_json,'$.groundDisplacement.eventId')=? LIMIT 1`,...ctx.binding,a.id,r.eventId).first();
    must(!previous,'GROUND_DISPLACEMENT_ALREADY_ADJUDICATED');
    data.groundDisplacement={...r,remainingFeet:r.distanceFeet-r.reductionFeet};
    return;
  }
  const r=x.incomingStrike,attempt=await ctx.get('attack_attempt',r.attemptId),inbound=attempt?.data;
  must(attempt?.life==='active'&&inbound.resolution==='attack'&&inbound.physicalStrike===true&&attempt.actorId!==a.id&&
    inbound.targets?.length===1&&inbound.targets[0]===a.id&&inbound.outcome==='pending'&&!inbound.chargeRelease&&
    !inbound.targetResults?.length&&!inbound.equipmentGuard&&Array.isArray(inbound.damage)&&inbound.damage.length,'PHYSICAL_HIT_REQUIRED');
  const attacker=await actor(ctx,attempt.actorId),target=await sheet(ctx,a.id),hit=inbound.hit;
  must((attacker.data.encounterId??null)===(a.data.encounterId??null)&&
    (inbound.strikeWindow??null)===await reactionRollWindow(ctx,a)&&
    (attacker.data.encounterId||inbound.strikeClock===attacker.data.gameSeconds),'STRIKE_REACTION_WINDOW_CLOSED');
  must(hit&&hit.natural!==1&&(hit.natural===20||target.ac.value!=null&&hit.total>=target.ac.value),'PHYSICAL_HIT_REQUIRED');
  const physical=inbound.damage.filter(c=>physicalTypes.has(c.type));
  must(physical.length>0&&inbound.damage.every(c=>c.type!=='healing'&&Number.isSafeInteger(c.total)),'PHYSICAL_DAMAGE_REQUIRED');
  const sum=physical.reduce((n,c)=>n+Math.max(0,c.total),0);
  const reflection=d.reactionRule==='physical_reflection';
  must(Boolean(r.reflectionRoll)===reflection,'REFLECTION_DIE_REQUIRED');
  let roll=null, attackerAc=null, negated=true;
  if(reflection){
    checkDiceOwner(ctx,a,'attack',r.reflectionRoll.origin);
    roll=observed(action.title,1,20,d.fixedBonus,'sum',r.reflectionRoll,true);
    attackerAc=(await sheet(ctx,attacker.id)).ac.value;
    must(attackerAc!=null,'REFLECTION_TARGET_AC_UNKNOWN');
    negated=roll.natural!==1&&(roll.natural===20||roll.total>=attackerAc);
  }
  const guard={useId:x.useId,attemptId:attempt.id,protectedActorId:a.id,attackerId:attacker.id,rule:d.reactionRule,
    roll,attackerAc,negated,rolledPhysical:sum,reflectedForce:reflection&&negated?Math.floor(sum/2):0};
  await save(ctx,attempt,{...inbound,equipmentGuard:guard});
  data.equipmentGuard=guard;
}

// Settle reflected Force through the attacker's actual defenses and original HP path.
export async function settleEquipmentReflection(ctx,guard,applyHp){
  if(!guard?.reflectedForce)return null;
  const defenses=(await sheet(ctx,guard.attackerId)).defenses.filter(r=>r.type==='force'&&!r.nonmagicalOnly);
  const kinds=new Set(defenses.map(r=>r.kind));let amount=guard.reflectedForce;
  if(kinds.has('immunity'))amount=0;
  else {if(kinds.has('resistance'))amount=Math.floor(amount/2);if(kinds.has('vulnerability'))amount*=2;}
  return {type:'force',rolled:guard.reflectedForce,applied:amount,defenses,
    hp:amount?await applyHp(ctx,{actorId:guard.attackerId,kind:'damage',amount}):null};
}
