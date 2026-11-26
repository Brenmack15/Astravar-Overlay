import { availableUses, arrayOf, identifier, must, owned, save } from './cloud-mechanics-core.mjs';

export const chargedEffectSelection=arrayOf(identifier,16);

export function offeredCharges(sheet,route,sourceId,accuracyId,components,resolution){
  if(resolution!=='attack')return [];
  return sheet.optionalChargedEffects.filter(e=>{
    if(e.target==='attack'||e.target==='spell_attack')return e.target===(route==='spell'?'spell_attack':'attack') && (e.key==='all'||e.key===accuracyId);
    return (e.damageRoute==='both'||e.damageRoute===route) && (e.key==='all'||e.key===sourceId) && components.some(c=>
      c.type!=='healing' && (!e.damageComponentId||e.damageComponentId===c.id) && (!e.damageType||e.damageType===c.type));
  });
}

// Holds protect hit-contingent costs from a competing command, without changing the recorded balance.
export async function reserveEffectCosts(ctx,actorId,useId,selected,sheet,accuracy,components,resolution,targets){
  if(!selected?.length)return [];
  const applied=new Set([...(accuracy?.sources??[]),...components.flatMap(c=>c.damageSources??[])].map(s=>s.id));
  must(resolution==='attack' && new Set(selected).size===selected.length,'CHARGED_EFFECT_SELECTION_INVALID');
  const result=[];
  for(const id of selected){
    const effect=sheet.effects.find(e=>e.id===id);
    must(effect?.data.charge && applied.has(id),'CHARGED_EFFECT_INELIGIBLE');
    const {poolId,amount,trigger}=effect.data.charge;
    must(trigger!=='confirmed_hit'||targets.length>0,'HIT_CHARGE_REQUIRES_TARGET');
    const pool=await owned(ctx,'resource_pool',poolId,actorId),holds=pool.data.effectHolds??[];
    must(holds.length<128 && !holds.some(h=>h.useId===useId && h.effectId===id),'EFFECT_CHARGE_HOLD_UNAVAILABLE');
    must(availableUses(pool.data)!=null && availableUses(pool.data)>=amount,'RESOURCE_EMPTY_OR_UNKNOWN');
    const hold={useId,effectId:id,amount};
    await save(ctx,pool,{...pool.data,effectHolds:[...holds,hold]});
    result.push({effectId:id,poolId,amount,trigger,status:'reserved',source:effect.data.source});
  }
  return result;
}

export async function settleEffectCosts(ctx,actorId,useId,charges,phase){
  const result=[],costs=[];
  for(const charge of charges??[]){
    if(charge.status!=='reserved'||phase!=='miss' && charge.trigger!==phase){result.push(charge);continue;}
    const pool=await owned(ctx,'resource_pool',charge.poolId,actorId),holds=pool.data.effectHolds??[];
    const hold=holds.find(h=>h.useId===useId&&h.effectId===charge.effectId);
    must(hold?.amount===charge.amount && pool.data.current!=null && pool.data.current>=charge.amount,'EFFECT_CHARGE_HOLD_UNAVAILABLE');
    const debit=phase!=='miss',after=pool.data.current-(debit?charge.amount:0);
    await save(ctx,pool,{...pool.data,current:after,effectHolds:holds.filter(h=>h!==hold)});
    result.push({...charge,status:debit?'spent':'released'});
    if(debit)costs.push({effectId:charge.effectId,poolId:pool.id,trigger:charge.trigger,amount:charge.amount,before:pool.data.current,after});
  }
  return {charges:result,costs};
}
