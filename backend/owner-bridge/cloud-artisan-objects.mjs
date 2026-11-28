import {actor,identifier,whole,named,fields,option,arrayOf,must,owned,save,useTiming,addClock} from './cloud-mechanics-core.mjs';

export const artisanTypes=['tinker_object','artisan_tool_creation','clockwork_device'];
export const artisanDefinition={
 allowedToolIds:arrayOf(identifier,32),artisanOutputIds:arrayOf(identifier,32),maximumObjects:whole(1,24),cantripId:identifier
};
const property=fields({kind:option('light','message','sound','odor','image'),description:named(350),seconds:whole(0,6),radiusFeet:whole(0,5)},['kind','description']);
export const artisanInput={
 tinker:fields({itemId:identifier,toolId:identifier,touched:{const:true},tiny:{const:true},nonmagical:{const:true},property},['itemId','toolId','touched','tiny','nonmagical','property']),
 toolCreation:fields({toolId:identifier,definitionId:identifier,outputId:identifier,elapsedSeconds:whole(3600,3600),uninterrupted:{const:true},emptySpace:{const:true},distanceFeet:whole(0,5)},['toolId','definitionId','outputId','elapsedSeconds','uninterrupted','emptySpace','distanceFeet']),
 clockwork:fields({step:option('create','use','dismantle'),deviceId:identifier,elapsedSeconds:whole(0,600),touched:{const:true},effect:option('sensory','light_or_snuff','clean_or_soil','chill_warm_flavor','mark','trinket_or_illusion'),options:named(350)},['step','deviceId'])
};
async function usableTool(ctx,a,id,allowed){
 const row=await owned(ctx,'item_instance',id,a.id);
 const definition=await ctx.get('item_definition',row.data.definitionId);
 must(row.life==='active'&&row.data.ownerId===a.id&&row.data.custodianId===a.id&&row.data.partyAccess&&row.data.quantity===1&&row.data.holding==='carried'&&row.data.equipment==='held'&&(allowed.includes(row.data.definitionId)||['artisan tools','thieves tools'].includes(definition?.data.category?.toLowerCase())),'QUALIFYING_TOOL_IN_HAND_REQUIRED');
}
export async function validateArtisan(ctx,x){
 if(!artisanTypes.includes(x.type)){must(!x.allowedToolIds&&!x.artisanOutputIds&&!x.maximumObjects&&!x.cantripId,'UNEXPECTED_ARTISAN_RULE');return;}
 if(x.type==='clockwork_device')must(x.cantripId && (await owned(ctx,'spell',x.cantripId,x.actorId)).data.baseLevel===0 && x.maximumObjects,'CLOCKWORK_CANTRIP_REQUIRED');
 else {
  must(x.allowedToolIds?.length,'QUALIFYING_TOOLS_REQUIRED');
  for(const id of new Set([...(x.allowedToolIds??[]),...(x.artisanOutputIds??[])]))must((await ctx.get('item_definition',id))?.life==='active','TOOL_CATALOG_MISSING');
  if(x.type==='artisan_tool_creation')must(x.artisanOutputIds?.length,'TOOL_OUTPUT_CATALOG_MISSING');
  else must(x.maximumObjects,'OBJECT_LIMIT_REQUIRED');
 }
}
export async function useArtisan(ctx,a,action,x,result){
 const d=action.data;
 for(const [field,type] of [['tinker','tinker_object'],['toolCreation','artisan_tool_creation'],['clockwork','clockwork_device']])must(Boolean(x[field])===(d.type===type),'ARTISAN_ARGUMENT_MISMATCH');
 if(x.tinker){
  const o=x.tinker;await usableTool(ctx,a,o.toolId,d.allowedToolIds);
  const item=await owned(ctx,'item_instance',o.itemId,a.id),base=await ctx.get('item_definition',item.data.definitionId);
  must(base&&item.life==='active'&&item.data.holding==='carried'&&item.data.custodianId===a.id&&item.data.quantity===1&&!item.data.attuned&&base.data.attunement==='none'&&(!base.data.rarity||['none','mundane'].includes(base.data.rarity))&&!item.data.tinkerProperty,'TINY_NONMAGICAL_OBJECT_REQUIRED');
  const p=o.property;
  must(p.kind!=='light'||p.radiusFeet===5,'FIVE_FOOT_LIGHT_REQUIRED');
  must(p.kind!=='message'||p.seconds!==undefined,'MESSAGE_DURATION_REQUIRED');
  must(p.kind!=='image'||p.description.trim().split(/\s+/u).length<=25,'VISUAL_TEXT_TOO_LONG');
  let active=[...(a.data.tinkeredItemIds??[])];
  if(active.length===d.maximumObjects){
   const prior=await ctx.get('item_instance',active[0]);must(prior?.data.tinkerProperty?.caster===a.id,'TINKERED_OBJECT_CHANGED');
   await save(ctx,prior,{...prior.data,tinkerProperty:null});active=active.slice(1);
  }
  must(active.length<d.maximumObjects,'TINKERED_OBJECT_LIMIT');
  await save(ctx,item,{...item.data,tinkerProperty:{caster:a.id,property:p,useId:x.useId}});await save(ctx,a,{...a.data,tinkeredItemIds:[...active,item.id]});result.imbuedObject=item.id;
 }
 if(x.toolCreation){
  const o=x.toolCreation;must(!a.data.encounterId&&a.data.gameSeconds!=null,'UNINTERRUPTED_WORK_REQUIRED');
  await usableTool(ctx,a,o.toolId,d.allowedToolIds);
  const def=await ctx.get('item_definition',o.definitionId);must(d.artisanOutputIds.includes(o.definitionId)||def?.data.category?.toLowerCase()==='artisan tools','ARTISAN_OUTPUT_REQUIRED');must(def?.life==='active'&&!await ctx.current(o.outputId),'ARTISAN_OUTPUT_UNAVAILABLE');
  if(a.data.createdToolId){
    const prior=await ctx.get('item_instance',a.data.createdToolId);must(prior?.data.createdByActorId===a.id,'PREVIOUS_CREATED_TOOL_UNKNOWN');
    if(prior.life==='active')await ctx.put({...prior,life:'archived'});
  }
  await ctx.put({kind:'item_instance',id:o.outputId,actorId:a.id,title:def.title,data:{definitionId:def.id,ownerId:a.id,ownerType:'actor',custodianId:null,locationId:null,placement:{space:'unoccupied',distanceFeet:o.distanceFeet,creatorActorId:a.id},quantity:1,holding:'unassigned',partyAccess:true,equipment:'packed',attuned:false,operating:false,resourcePoolId:null,createdByActorId:a.id,creationUseId:x.useId}});
  await ctx.link(o.outputId,'definition',def.id);await ctx.link(o.outputId,'owned_by',a.id);
  const next=await addClock(ctx,a,3600);await save(ctx,a,{...next,createdToolId:o.outputId});
  result.createdToolId=o.outputId;
 }
 if(x.clockwork){
  const o=x.clockwork;must(a.data.gameSeconds!=null,'GAME_CLOCK_UNKNOWN');
  const now=a.data.gameSeconds;
  let devices=(a.data.clockworkDevices??[]).filter(v=>v.expiresAt>now),entry=devices.find(v=>v.id===o.deviceId);
  if(o.step==='create'){
   must(!a.data.encounterId&&!entry&&o.effect&&o.options&&o.elapsedSeconds===600&&!o.touched,'CLOCKWORK_CREATION_INVALID');
   // Expiration can occur during the ten-minute casting.
   devices=devices.filter(v=>v.expiresAt>now+600);must(devices.length<d.maximumObjects,'CLOCKWORK_DEVICE_LIMIT');
   await save(ctx,a,await addClock(ctx,a,600));devices.push({id:o.deviceId,effect:o.effect,options:o.options,expiresAt:now+600+28800});
  }else{
   must(entry&&o.touched&&!o.effect&&!o.options&&!o.elapsedSeconds,'ACTIVE_DEVICE_AND_TOUCH_REQUIRED');
   await useTiming(ctx,a,o.step==='use'?'bonus_action':'action');result.activatedEffect=o.step==='use'?entry:null;
   if(o.step==='dismantle')devices=devices.filter(v=>v!==entry);
  }
  const latest=await actor(ctx,a.id);await save(ctx,latest,{...latest.data,clockworkDevices:devices});result.activeDevices=devices;
 }
}
