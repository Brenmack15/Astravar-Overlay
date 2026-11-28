import {fields,identifier,stats,option,whole,arrayOf,named,must,owned,actor,op,save,insert,spend,useTiming,sheet} from './cloud-mechanics-core.mjs';

export const preparedItemRule=fields({poolId:identifier,ability:option(...stats),capacity:whole(1,99),
  eligibleItemIds:arrayOf(identifier,100),eligibleSpellIds:arrayOf(identifier,150)
},['poolId','ability','capacity','eligibleItemIds','eligibleSpellIds']);

export async function validatePreparedItem(ctx, actorId, rule) {
  const pool=await owned(ctx,'resource_pool',rule.poolId,actorId);
  must(pool.data.maximum===rule.capacity && pool.data.recovery==='none' && pool.data.type!=='spell_slot',
    'STORAGE_REQUIRES_INDEPENDENT_POOL');
  for(const id of rule.eligibleItemIds)await owned(ctx,'item_instance',id,actorId);
  for(const id of rule.eligibleSpellIds){
    const s=await owned(ctx,'spell',id,actorId);
    must([1,2].includes(s.data.baseLevel) && s.data.timing==='action','STORAGE_SPELL_INELIGIBLE');
  }
}
function inHand(item,id){
  return item?.life==='active' && item.data.custodianId===id && item.data.quantity===1 &&
    item.data.partyAccess && item.data.holding==='carried' && ['held','worn'].includes(item.data.equipment);
}
export const preparedItemOperations={
  load_prepared_spell_item:op('spells',fields({actorId:identifier,featureId:identifier,itemId:identifier,spellId:identifier,
    restReportId:identifier,confirmed:{const:true}},['actorId','featureId','itemId','spellId','restReportId','confirmed']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId), feature=await owned(ctx,'feature',x.featureId,a.id), rule=feature.data.preparedItemRule;
    must(rule && rule.eligibleItemIds.includes(x.itemId) && rule.eligibleSpellIds.includes(x.spellId),'STORAGE_UNAPPROVED_ITEM_OR_SPELL');
    const rest=await owned(ctx,'rest_report',x.restReportId,a.id);
    must(rest.data.kind==='long_rest' && rest.data.status==='completed' && Number.isSafeInteger(a.data.gameSeconds) &&
      rest.data.endGameSeconds===a.data.gameSeconds && feature.data.lastLoadRestId!==rest.id,'STORAGE_REQUIRES_FRESH_LONG_REST');
    const pool=await owned(ctx,'resource_pool',rule.poolId,a.id), item=await ctx.get('item_instance',x.itemId);
    must(inHand(item,a.id),'STORAGE_HOST_NOT_HELD');
    must(pool.data.current===0 && pool.data.recovery==='none' && pool.data.maximum===rule.capacity &&
      !feature.data.loadedSpellItem && !item.data.preparedSpellFeatureId,'STORAGE_NOT_EMPTY');
    const spell=await owned(ctx,'spell',x.spellId,a.id);
    must([1,2].includes(spell.data.baseLevel) && spell.data.timing==='action','STORAGE_SPELL_INELIGIBLE');
    ctx.require('spells',a.id,true);
    await save(ctx,pool,{...pool.data,current:rule.capacity});
    await save(ctx,item,{...item.data,preparedSpellFeatureId:feature.id});
    await save(ctx,feature,{...feature.data,loadedSpellItem:{itemId:item.id,spellId:spell.id},lastLoadRestId:rest.id});
    await ctx.link(feature.id,'loaded_item',item.id);
    return {itemId:item.id,spellId:spell.id,poolId:pool.id,remaining:rule.capacity,restReportId:rest.id};
  }),
  use_prepared_spell_item:op('spells',fields({actorId:identifier,creatorId:identifier,featureId:identifier,itemId:identifier,
    castId:identifier,adjudication:named(500),replaceConcentration:{const:true}},['actorId','creatorId','featureId','itemId','castId','adjudication']),async(ctx,x)=>{
    const holder=await actor(ctx,x.actorId), creator=await actor(ctx,x.creatorId);
    const feature=await owned(ctx,'feature',x.featureId,creator.id),rule=feature.data.preparedItemRule, load=feature.data.loadedSpellItem;
    must(rule && load && load.itemId===x.itemId,'NO_STORED_SPELL_EFFECT');
    const item=await ctx.get('item_instance',x.itemId);
    must(inHand(item,holder.id) && item.data.preparedSpellFeatureId===feature.id,'STORAGE_HOST_NOT_HELD');
    const spell=await owned(ctx,'spell',load.spellId,creator.id), prof=await sheet(ctx,creator.id), active=await sheet(ctx,holder.id);
    must(!['disabled','dormant'].includes(holder.data.activityState) &&
      !active.conditions.some(v=>['incapacitated','stunned','unconscious','petrified','paralyzed'].includes(v)),'ACTOR_INACTIVE');
    const modifier=prof.abilities[rule.ability].modifier;
    must(modifier!=null,'STORAGE_ABILITY_UNKNOWN');
    must(!spell.data.concentration || !holder.data.concentrationCastId || x.replaceConcentration,'CONCENTRATION_DECISION_REQUIRED');
    await useTiming(ctx,holder,'action');
    const cost=await spend(ctx,creator.id,rule.poolId,1);
    const pool=await owned(ctx,'resource_pool',rule.poolId,creator.id);
    const data={actorId:holder.id,sourceCasterId:creator.id,sourceAbility:rule.ability,sourceModifier:modifier,spellId:spell.id,
      sourceItemId:item.id,sourceFeatureId:feature.id,route:'stored_item_effect',baseLevel:spell.data.baseLevel,level:spell.data.baseLevel,
      costs:[cost],timing:'action',resolution:'manual',components:[],concentration:spell.data.concentration,
      duration:spell.data.duration??null,targets:[],targetResults:[],outcome:'adjudicated',adjudication:x.adjudication,
      recordedAt:ctx.now,commandId:ctx.commandId};
    await insert(ctx,'cast_instance',x.castId,holder.id,spell.title,data);
    await ctx.link(x.castId,'cast_spell',spell.id);await ctx.link(x.castId,'requires_item',item.id);
    if(spell.data.concentration){
      if(holder.data.concentrationCastId)await ctx.unlink(holder.id,'concentrates_on',holder.data.concentrationCastId);
      await ctx.link(holder.id,'concentrates_on',x.castId);
      const fresh=await actor(ctx,holder.id);
      const seconds=spell.data.scaling?.durationSeconds;
      await save(ctx,fresh,{...fresh.data,concentrationCastId:x.castId,concentrationExpiresAt:
        Number.isSafeInteger(seconds) && Number.isSafeInteger(fresh.data.gameSeconds)?fresh.data.gameSeconds+seconds:null});
    }
    if(pool.data.current===0){
      const {preparedSpellFeatureId,...remainingItem}=item.data, {loadedSpellItem,...remainingFeature}=feature.data;
      await save(ctx,item,remainingItem);await save(ctx,feature,remainingFeature);await ctx.unlink(feature.id,'loaded_item',item.id);
    }
    return {castId:x.castId,itemId:item.id,spellId:spell.id,sourceCasterId:creator.id,sourceModifier:modifier,
      holderId:holder.id,concentration:spell.data.concentration,remaining:pool.data.current};
  })
};
