import {anchorRule} from './cloud-equipment-anchor.mjs';
import {readyAnchor, fields, identifier, whole, arrayOf, option, evidence, must, save, owned, spend, checkDiceOwner} from './cloud-mechanics-core.mjs';
import {observed} from './cloud-mechanics-rolls.mjs';

export const modeDefinition={
  anchorRule,activateModes:arrayOf(fields({id:identifier,seconds:whole(1,86400)},['id','seconds']),8),
  endModes:arrayOf(identifier,8),secondaryPools:arrayOf(identifier,8),
  tempHpRule:fields({dice:whole(0,10),sides:whole(2,20),flat:whole(0,1000)},['dice','sides','flat'])
};
export const modeInput={confirmAnchorDock:{const:true},tempHpDice:evidence,tempHpChoice:option('keep','replace')};

export async function validateEquipmentMode(ctx,x){
  const opts=['activateModes','endModes','secondaryPools','tempHpRule','anchorRule'];
  if(x.type!=='equipment_mode'){
    must(opts.every(key=>x[key]===undefined),'UNEXPECTED_EQUIPMENT_MODE_RULE');
    return;
  }
  must(x.sourceItemId && ((x.activateModes??[]).length || (x.endModes??[]).length || x.tempHpRule),'MODE_CONFIGURATION_REQUIRED');
  const ids=[...(x.activateModes??[]).map(m=>m.id),...(x.endModes??[])];
  must(new Set(ids).size===ids.length,'DUPLICATE_EQUIPMENT_MODE');
  if(x.anchorRule){
    must(x.anchorRule.itemId!==x.sourceItemId && x.activateModes?.some(m=>m.id===x.anchorRule.modeId),'ANCHOR_MODE_MISMATCH');
    await owned(ctx,'item_instance',x.anchorRule.itemId,x.actorId);
  }
  const pools=[...(x.secondaryPools??[]),...(x.cost?[x.cost.poolId]:[])];
  must(new Set(pools).size===pools.length,'DUPLICATE_EQUIPMENT_COST');
  for(const id of x.secondaryPools??[])await owned(ctx,'resource_pool',id,x.actorId);
}
export async function useEquipmentMode(ctx,a,action,x,result){
  const d=action.data;
  if(d.type!=='equipment_mode'){must(!x.confirmAnchorDock&&!x.tempHpChoice&&!x.tempHpDice,'UNEXPECTED_TEMP_HP');return;}
  const current=await owned(ctx,'actor',a.id,a.id);
  const state=current.data;
  must(Number.isSafeInteger(state.gameSeconds),'GAME_CLOCK_UNKNOWN');
  must(d.sourceItemId && ((d.activateModes??[]).length || (d.endModes??[]).length || d.tempHpRule),'MODE_CONFIGURATION_REQUIRED');
  const old=state.equipmentModes??[];
  const active=old.filter(m=>m.expiresAt>state.gameSeconds);
  const end=new Set(d.endModes??[]);
  must(!end.size || [...end].every(id=>active.some(m=>m.itemId===d.sourceItemId && m.id===id)),'EQUIPMENT_MODE_INACTIVE');
  const refreshed=new Set((d.activateModes??[]).map(m=>m.id));
  const modes=active.filter(m=>m.itemId!==d.sourceItemId || (!end.has(m.id)&&!refreshed.has(m.id)));
  must(Boolean(x.confirmAnchorDock)===Boolean(d.anchorRule),'ANCHOR_DOCK_CONFIRMATION_REQUIRED');
  const rod=d.anchorRule?await readyAnchor(ctx,a.id,d.anchorRule.itemId):null;
  must(!d.anchorRule||rod,'ANCHOR_ROD_UNAVAILABLE');
  for(const m of d.activateModes??[]){
    const anchored=d.anchorRule?.modeId===m.id;
    modes.push({id:m.id,itemId:d.sourceItemId,expiresAt:state.gameSeconds+m.seconds,
      ...(anchored?{anchor:{...d.anchorRule,itemRevision:rod.revision,useId:x.useId,recordedAt:ctx.now}}:{})});
  }
  let temp=state.temporaryHp;
  if(d.tempHpRule){
    const r=d.tempHpRule;must(temp!=null && x.tempHpChoice,'TEMP_HP_CHOICE_REQUIRED');
    let value=r.flat;
    if(r.dice){must(x.tempHpDice,'TEMP_HP_DICE_REQUIRED');checkDiceOwner(ctx,a,'custom',x.tempHpDice.origin);
      const rolled=observed(action.title,r.dice,r.sides,r.flat,'sum',x.tempHpDice);value=rolled.total;result.tempHpRoll=rolled;}
    else must(!x.tempHpDice,'UNEXPECTED_TEMP_HP_DICE');
    if(x.tempHpChoice==='replace')temp=value;
  } else must(!x.tempHpChoice&&!x.tempHpDice,'UNEXPECTED_TEMP_HP');
  for(const pool of d.secondaryPools??[])result.costs.push(await spend(ctx,a.id,pool,1));
  await save(ctx,current,{...state,equipmentModes:modes,...(temp!=null?{temporaryHp:temp}:{})});
  result.modeState=modes;
}
