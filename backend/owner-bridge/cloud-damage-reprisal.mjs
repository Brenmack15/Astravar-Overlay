import {actor,gearEligible,must,owned,reactionRollWindow,save} from './cloud-mechanics-core.mjs';

export async function verifyReprisal(ctx,a,x,attack,reach){
  const present=x.reactionRuleId!==undefined||x.triggerAttemptId!==undefined;
  if(!present)return null;
  must(x.reactionRuleId&&x.triggerAttemptId&&!x.actionUseId&&!x.replacesAttackId,'REACTION_TRIGGER_REQUIRED');
  const rule=await owned(ctx,'action_definition',x.reactionRuleId,a.id);
  must(rule.data.type==='damage_reprisal'&&rule.data.reactionAttacks?.includes(attack.id)&&rule.data.timing==='reaction','REACTION_ATTACK_INELIGIBLE');
  must(attack.data.delivery==='melee'&&x.mode!==undefined&&Number.isSafeInteger(x.targetDistanceFeet)&&x.targetDistanceFeet<=Math.min(reach??0,rule.data.range),'REACTION_ATTACK_OUT_OF_REACH');
  must(await gearEligible(ctx,rule.data.sourceItemId,a.id,rule.data.requiresAttunement,rule.data.requiresOperating),'REACTION_SOURCE_INELIGIBLE');
  const incoming=await ctx.current(x.triggerAttemptId);
  must(['attack_attempt','cast_instance'].includes(incoming?.kind)&&incoming.life==='active'&&incoming.actorId!==a.id&&x.targets?.length===1&&x.targets[0]===incoming.actorId,'REACTION_TRIGGER_ATTACKER_MISMATCH');
  ctx.require(incoming.kind==='cast_instance'?'spells':'combat',incoming.actorId);await actor(ctx,incoming.actorId);
  const hit=incoming.data.targetResults?.find(r=>r.targetActorId===a.id&&r.decision==='hit'&&r.amount>0&&!r.healing);
  must(incoming.data.resolution==='attack'&&hit&&!hit.reprisalAttemptId&&Object.hasOwn(hit,'reprisalWindow'),'DAMAGING_ATTACK_TRIGGER_REQUIRED');
  const token=await reactionRollWindow(ctx,a);
  must(hit.reprisalWindow===token&&(token!=null||a.data.gameSeconds!=null&&hit.reprisalGameSeconds===a.data.gameSeconds),'REACTION_TRIGGER_EXPIRED');
  await save(ctx,incoming,{...incoming.data,targetResults:incoming.data.targetResults.map(r=>r===hit?{...r,reprisalAttemptId:x.attemptId}:r)});
  return {ruleId:rule.id,triggerAttemptId:incoming.id,triggerKind:incoming.kind};
}
