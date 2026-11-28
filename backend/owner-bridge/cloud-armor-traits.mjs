import {actorMembership} from './cloud-continuity.mjs';
import {actor, must, save, sheet} from './cloud-mechanics-core.mjs';

export function validateArmorTrait(d) {
  if(d.bareAbilityOnly)must(d.target==='ability_check','BARE_ABILITY_EFFECT_INVALID');
  must(Boolean(d.strideRule)===(d.target==='equipment_property'&&d.key==='striding'),'STRIDE_RULE_MISMATCH');
  if(d.target==='equipment_property')
    must(d.operation==='grant'&&d.sourceItemId&&d.requiresWorn&&d.amount===undefined&&
      (['silent_footsteps','striding'].includes(d.key)||/^language:[a-z][a-z_-]*$/.test(d.key)),'EQUIPMENT_PROPERTY_INVALID');
  if(d.target==='critical_defense')
    must(d.operation==='immunity'&&d.key==='all'&&d.amount===undefined,'CRITICAL_DEFENSE_INVALID');
  if(d.warningRadiusFeet!==undefined)
    must(d.target==='roll_edge'&&d.key==='initiative:initiative'&&d.operation==='advantage'&&d.sourceItemId&&d.requiresWorn,'WARNING_SOURCE_INVALID');
}

export async function criticalProtection(ctx,targets) {
  const sources=[];
  for(const id of targets){
    const p=await sheet(ctx,id);
    const ward=p.effects.find(e=>e.data.target==='critical_defense'&&e.data.operation==='immunity'&&e.data.key==='all');
    if(ward)sources.push({targetActorId:id,effectId:ward.id});
  }
  must(!sources.length||sources.length===targets.length,'SPLIT_CRITICAL_TARGETS_REQUIRED');
  return sources;
}

export async function onsetWarning(ctx,recipient,s) {
  const input=s.onsetWarning;
  must(s.kind==='initiative'&&s.key==='initiative'&&!recipient.data.encounterId&&input.wearerId!==recipient.id,'WARNING_ONSET_REQUIRED');
  const wearer=await actor(ctx,input.wearerId);
  const party=id=>{const m=actorMembership(id.data);return m.inParty&&m.participation==='active';};
  must(party(wearer)&&party(recipient),'WARNING_ALLY_REQUIRED');
  const p=await sheet(ctx,wearer.id),effect=p.effects.find(e=>e.id===input.effectId);
  must(effect?.data.warningRadiusFeet!=null&&input.distanceFeet<=effect.data.warningRadiusFeet,'WARNING_OUT_OF_RANGE_OR_INACTIVE');
  const awakened=(recipient.data.conditions??[]).includes('natural_sleep');
  if(awakened){
    ctx.require('combat',recipient.id);
    await save(ctx,recipient,{...recipient.data,conditions:recipient.data.conditions.filter(k=>k!=='natural_sleep'),
      conditionSources:(recipient.data.conditionSources??[]).filter(entry=>entry.key!=='natural_sleep')});
  }
  return {edge:effect,receipt:{wearerId:wearer.id,effectId:effect.id,distanceFeet:input.distanceFeet,awakened}};
}
