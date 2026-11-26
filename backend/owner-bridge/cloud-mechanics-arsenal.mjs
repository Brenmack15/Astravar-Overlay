import { consumeItemUnit } from './cloud-holdings.mjs';
import { owned, must, save, gearEligible, spend, fields, identifier, whole, arrayOf, option, stats, damageTypes } from './cloud-mechanics-core.mjs';

const variant=fields({id:identifier,spellId:identifier,level:whole(0,9),slotLevel:whole(1,9),scrollDefinitionId:identifier},['id','spellId','level','slotLevel']);
export const arsenalDefinitions={payloadVariants:arrayOf(variant,32),capacity:whole(1,16),releasesPerTurn:whole(1,16),
  chargeAbilities:arrayOf(option(...stats),6),chargeType:option(...damageTypes)};
export const loadChoice=fields({variantId:identifier,slotPoolId:identifier,scrollItemId:identifier,replaceUseId:identifier},['variantId']);
export const shotChoice=fields({variantId:identifier,storedUseId:identifier,slotPoolId:identifier},['variantId']);
export async function validateArsenal(ctx,x) {
  if(x.type==='store_payload'){
    must(x.sourceItemId && x.timing==='bonus_action' && x.payloadVariants?.length && x.capacity && x.releasesPerTurn &&
      new Set(x.payloadVariants.map(v=>v.id)).size===x.payloadVariants.length,'PAYLOAD_LIBRARY_INVALID');
    for(const v of x.payloadVariants){
      const spell=await owned(ctx,'spell',v.spellId,x.actorId);
      must(spell.data.baseLevel<=v.level && spell.data.components.length && !spell.data.concentration && spell.data.resolution!=='manual' &&
        spell.data.components.every(c=>c.type!=='healing'&&!c.variants),'PAYLOAD_REQUIRES_ADJUDICATION');
      if(v.scrollDefinitionId)must((await ctx.current(v.scrollDefinitionId))?.kind==='item_definition','SCROLL_DEFINITION_REQUIRED');
    }
  }else must(!(x.payloadVariants||x.capacity||x.releasesPerTurn),'UNEXPECTED_PAYLOAD_LIBRARY');
  if(x.type==='prepare_strike')must(x.sourceItemId && x.timing==='bonus_action' && x.chargeType && x.chargeAbilities?.length &&
    new Set(x.chargeAbilities).size===x.chargeAbilities.length,'CHARGE_CONFIGURATION_INVALID');
  else must(!x.chargeType&&!x.chargeAbilities,'UNEXPECTED_CHARGE_CONFIGURATION');
}
async function paySlot(ctx,a,poolId,level) {
  const pool=await owned(ctx,'resource_pool',poolId,a.id);
  must(pool.data.type==='spell_slot' && pool.data.slotLevel===level,'MATCHING_SLOT_REQUIRED');
  if(a.data.encounterId && a.data.rulesProfile==='revised_2024'){
    const e=await ctx.get('encounter',a.data.encounterId);
    must(!e.data.slotSpentActorIds?.includes(a.id),'SLOT_ALREADY_SPENT_THIS_TURN');
    await save(ctx,e,{...e.data,slotSpentActorIds:[...(e.data.slotSpentActorIds??[]),a.id]});
  }
  return spend(ctx,a.id,poolId,1);
}
async function snapshotPayload(ctx,a,rule,variantId,p,snapshot){
  const variant=rule.data.payloadVariants.find(v=>v.id===variantId);
  must(variant,'PAYLOAD_VARIANT_UNAVAILABLE');
  const spell=await owned(ctx,'spell',variant.spellId,a.id),d=spell.data;
  must(d.baseLevel<=variant.level && d.components.length && !d.concentration && d.resolution!=='manual' &&
    d.components.every(c=>c.type!=='healing'&&!c.variants),'PAYLOAD_REQUIRES_ADJUDICATION');
  return {...variant,spellRevision:spell.revision,name:spell.title,components:snapshot(d.components,{...p,effects:[]},{},variant.level-d.baseLevel)};
}
async function turnWindow(ctx,a){
  must(a.data.gameSeconds!=null,'GAME_CLOCK_UNKNOWN');
  const enc=a.data.encounterId?await ctx.get('encounter',a.data.encounterId):null;
  if(enc)must(enc.data.active && enc.data.participants[enc.data.index]===a.id,'TURN_UNAVAILABLE');
  return enc?enc.id+':'+enc.data.round+':'+enc.data.index:'clock:'+Math.floor(a.data.gameSeconds/6);
}
export async function useArsenal(ctx,a,rule,x,data,p,snapshot){
  const d=rule.data;
  must((x.load!==undefined)===(d.type==='store_payload'),'UNEXPECTED_PAYLOAD_LOAD');
  if(d.type==='prepare_strike'){
    const item=await ctx.get('item_instance',d.sourceItemId),window=await turnWindow(ctx,a),previous=item.data.preparedStrike;
    must(!previous || previous.actorId!==a.id || previous.window!==window || previous.consumedBy,'STRIKE_ALREADY_PREPARED');
    const values=d.chargeAbilities.map(k=>p.abilities[k]?.modifier);must(values.every(v=>v!=null),'CHARGE_ABILITIES_UNKNOWN');
    const prepared={actorId:a.id,ruleId:rule.id,useId:x.useId,window,type:d.chargeType,flat:values.reduce((n,v)=>n+v,0)};
    await save(ctx,item,{...item.data,preparedStrike:prepared});data.preparedStrike=prepared;return;
  }
  if(d.type!=='store_payload')return;
  const item=await ctx.get('item_instance',d.sourceItemId),r=x.load;
  const payload=await snapshotPayload(ctx,a,rule,r.variantId,p,snapshot);
  const prior=item.data.storedPayloadIds??[],replace=r.replaceUseId;
  must(!replace||prior.includes(replace),'STORED_PAYLOAD_UNAVAILABLE');
  must(prior.length-(replace?1:0)<d.capacity,'RESERVOIR_FULL');
  must(Boolean(r.slotPoolId)!==Boolean(r.scrollItemId),'ONE_PAYLOAD_SOURCE_REQUIRED');
  if(r.slotPoolId)data.costs.push(await paySlot(ctx,a,r.slotPoolId,payload.slotLevel));
  else{
    must(payload.scrollDefinitionId && r.scrollItemId!==item.id,'SCROLL_DEFINITION_REQUIRED');
    data.scroll=await consumeItemUnit(ctx,r.scrollItemId,payload.scrollDefinitionId,a.id,'Payload preparation',x.useId);
  }
  if(replace){const old=await owned(ctx,'action_use',replace,a.id);await save(ctx,old,{...old.data,replacedBy:x.useId});}
  await save(ctx,item,{...item.data,storedPayloadIds:[...prior.filter(id=>id!==replace),x.useId]});
  data.payload={...payload,sourceItemId:item.id,sourceKind:r.slotPoolId?'slot':'scroll',loadedBy:a.id};
}
export async function checkArsenal(ctx,a,d){
  if(!d.weaponBus && !d.chargeActionId)return null;
  const item=await ctx.get('item_instance',d.sourceItemId);
  must(item && a.data.gameSeconds!=null,'WEAPON_CLOCK_UNKNOWN');
  const cooldown=item.data.weaponBusCooldown;
  if(d.weaponBus && cooldown)must(cooldown.actorId===a.id && a.data.gameSeconds>=cooldown.until,'WEAPON_BUS_UNAVAILABLE');
  const charge=d.chargeActionId?item.data.preparedStrike:null;
  if(d.chargeActionId)must(charge?.ruleId===d.chargeActionId && charge.actorId===a.id && !charge.consumedBy &&
    charge.window===await turnWindow(ctx,a),'PREPARED_STRIKE_REQUIRED');
  return charge;
}
export async function armedComponents(ctx,a,attack,attemptId,initial,p,choice,snapshot,commit){
  const d=attack.data;
  const charged=await checkArsenal(ctx,a,d);
  let components=initial, payload=null;
  const costs=[];
  must(Boolean(choice)===Boolean(d.payloadRule),'PAYLOAD_SELECTION_REQUIRED');
  if(choice){
    const rule=await owned(ctx,'action_definition',d.payloadRule.storeActionId,a.id);
    must(rule.data.type==='store_payload' && rule.data.sourceItemId===d.sourceItemId && await gearEligible(ctx,d.sourceItemId,a.id,rule.data.requiresAttunement,rule.data.requiresOperating),'PAYLOAD_LIBRARY_UNAVAILABLE');
    const item=await ctx.get('item_instance',d.sourceItemId);
    must(!(choice.storedUseId&&choice.slotPoolId),'ONE_PAYLOAD_SOURCE_REQUIRED');
    if(choice.storedUseId){
      const use=await owned(ctx,'action_use',choice.storedUseId,a.id);
      must(item.data.storedPayloadIds?.includes(use.id) && use.data.actionId===rule.id && use.data.payload?.id===choice.variantId &&
        !use.data.releasedAttemptId && !use.data.replacedBy,'STORED_PAYLOAD_UNAVAILABLE');
      payload={...use.data.payload,storedUseId:use.id};
      if(commit){
        await save(ctx,use,{...use.data,releasedAttemptId:attemptId});
        await save(ctx,item,{...item.data,storedPayloadIds:item.data.storedPayloadIds.filter(k=>k!==use.id)});
      }
    }else{
      payload=await snapshotPayload(ctx,a,rule,choice.variantId,p,snapshot);
      if(commit){must(choice.slotPoolId,'IMMEDIATE_PAYLOAD_SLOT_REQUIRED');costs.push(await paySlot(ctx,a,choice.slotPoolId,payload.slotLevel));}
    }
    if(commit){
      const window=await turnWindow(ctx,a),fresh=await ctx.get('item_instance',d.sourceItemId),usage=fresh.data.releases;
      const count=usage?.actorId===a.id && usage.window===window?usage.count:0;
      must(count<rule.data.releasesPerTurn,'PAYLOAD_TURN_LIMIT');
      await save(ctx,fresh,{...fresh.data,releases:{actorId:a.id,window,count:count+1}});
    }
    const replacement=d.payloadRule.componentId;
    components=components.flatMap(c=>c.id===replacement?payload.components.map(s=>({...s,id:'imbued-'+s.id})):c);
  }
  if(charged){
    components=[...components,{id:'prepared-bonus',name:'Prepared strike',type:charged.type,dice:0,sides:4,flat:charged.flat,critical:false}];
    if(commit){const item=await ctx.get('item_instance',d.sourceItemId);await save(ctx,item,{...item.data,preparedStrike:{...charged,consumedBy:attemptId}});}
  }
  must(new Set(components.map(c=>c.id)).size===components.length,'PAYLOAD_COMPONENT_COLLISION');
  if(commit && d.weaponBus?.cooldownSeconds){
    const item=await ctx.get('item_instance',d.sourceItemId);
    await save(ctx,item,{...item.data,weaponBusCooldown:{actorId:a.id,until:a.data.gameSeconds+d.weaponBus.cooldownSeconds,attemptId}});
  }
  if(d.chargeActionId||d.payloadRule)components=snapshot(components,p,{},0,'attack',attack.id);
  return {components,payload,costs,preparedUseId:charged?.useId??null};
}
