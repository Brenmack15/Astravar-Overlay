import { identifier, whole, named, fields, option, evidence, must, op, actor, owned, insert, save, spend, addClock, checkDiceOwner } from './cloud-mechanics-core.mjs';
import { observed } from './cloud-mechanics-rolls.mjs';

const protocol=fields({actorId:identifier,operatorId:identifier,protocolId:identifier,spellId:identifier,toolDefinitionId:identifier,
  repairPoolId:identifier,reconstructionPoolId:identifier,repairDice:whole(1,16),repairSides:whole(2,100),repairSeconds:whole(1,86400),
  reconstructionSeconds:whole(1,86400),dormancySeconds:whole(1,86400),source:named(300)},
  ['actorId','operatorId','protocolId','spellId','toolDefinitionId','repairPoolId','reconstructionPoolId','repairDice','repairSides','repairSeconds','reconstructionSeconds','dormancySeconds','source']);
async function define(ctx,x){
  const a=await actor(ctx,x.actorId);await actor(ctx,x.operatorId);
  must(a.id!==x.operatorId && !a.data.recoveryProtocolId && x.repairPoolId!==x.reconstructionPoolId,'CARE_PROTOCOL_EXISTS_OR_INVALID');
  const spell=await owned(ctx,'spell',x.spellId,x.operatorId);
  must(spell.data.resolution==='automatic' && !spell.data.components.length,'DEDICATED_CARE_SPELL_REQUIRED');
  must((await ctx.get('item_definition',x.toolDefinitionId))?.life==='active','TOOL_DEFINITION_MISSING');
  const repair=await owned(ctx,'resource_pool',x.repairPoolId,a.id),rebuild=await owned(ctx,'resource_pool',x.reconstructionPoolId,a.id);
  must(repair.data.recovery==='short_rest' && (repair.data.recoveryActorId??a.id)===a.id && rebuild.data.recovery==='long_rest' &&
    rebuild.data.recoveryActorId===x.operatorId,'CARE_RECOVERY_PROFILES_INVALID');
  const {actorId,protocolId,...data}=x;
  await insert(ctx,'care_protocol',protocolId,a.id,'Repair and reconstruction',data);
  for(const [relation,id] of [['care_operator',x.operatorId],['requires_spell',x.spellId],['requires_tool',x.toolDefinitionId],['uses_pool',repair.id],['uses_pool',rebuild.id]])await ctx.link(protocolId,relation,id);
  await save(ctx,a,{...a.data,recoveryProtocolId:protocolId,activityState:a.data.currentHp==null?'unknown':a.data.currentHp===0?'disabled':'active'});
  return {protocolId,actorId};
}
async function access(ctx,x){
  const a=await actor(ctx,x.actorId),operator=await actor(ctx,x.operatorId),p=await owned(ctx,'care_protocol',x.protocolId,a.id);
  must(a.data.recoveryProtocolId===p.id && p.data.operatorId===operator.id,'CARE_PROTOCOL_UNAVAILABLE');
  must(!a.data.encounterId && !operator.data.encounterId && !['disabled','dormant'].includes(operator.data.activityState),'UNINTERRUPTED_CARE_REQUIRED');
  return {a,operator,p};
}
async function complete(ctx,x){
  const {a,operator,p}=await access(ctx,x), d=p.data, reconstruction=x.kind==='reconstruct';
  const cast=await owned(ctx,'cast_instance',x.castId,operator.id);
  must(cast.data.spellId===d.spellId && cast.data.targets.includes(a.id) && cast.data.outcome==='pending' && !cast.data.careEventId,'ACTUAL_UNUSED_CARE_CAST_REQUIRED');
  must(x.elapsedSeconds>=(reconstruction?d.reconstructionSeconds:d.repairSeconds),'CARE_DURATION_INCOMPLETE');
  must(a.data.currentHp!=null && a.data.maxHp!=null,'HP_UNKNOWN');
  let roll=null,after;
  if(reconstruction){
    must(a.data.activityState==='disabled' && a.data.currentHp===0 && !x.dice && x.toolItemId,'RECONSTRUCTION_REQUIRES_DISABLED_ACTOR_AND_TOOL');
    const tool=await ctx.get('item_instance',x.toolItemId);
    must(tool?.life==='active' && tool.data.definitionId===d.toolDefinitionId && tool.data.custodianId===operator.id && tool.data.partyAccess && tool.data.holding==='carried' && tool.data.quantity>0,'RECONSTRUCTION_TOOL_UNAVAILABLE');
    after=a.data.maxHp;
  }else{
    must(a.data.activityState==='active' && a.data.currentHp>0 && x.dice && !x.toolItemId,'REPAIR_REQUIRES_ACTIVE_ACTOR_AND_DICE');
    checkDiceOwner(ctx,operator,'spell',x.dice.origin);
    roll=observed('Repair',d.repairDice,d.repairSides,0,'sum',x.dice);
    after=Math.min(a.data.maxHp,a.data.currentHp+roll.total);
  }
  const cost=await spend(ctx,a.id,reconstruction?d.reconstructionPoolId:d.repairPoolId,1);
  const next=await addClock(ctx,a,x.elapsedSeconds);await save(ctx,operator,await addClock(ctx,operator,x.elapsedSeconds));
  if(a.data.activeFormId && !next.activeFormId)await ctx.unlink(a.id,'active_form',a.data.activeFormId);
  await save(ctx,a,{...next,currentHp:after,activityState:'active'});
  const result={actorId:a.id,operatorId:operator.id,protocolId:p.id,castId:cast.id,type:x.kind,pool:cost,roll,beforeHp:a.data.currentHp,afterHp:after,
    actualHealing:after-a.data.currentHp,elapsedSeconds:x.elapsedSeconds,toolItemId:x.toolItemId??null,recordedAt:ctx.now,commandId:ctx.commandId};
  await insert(ctx,'care_event',ctx.commandId,a.id,reconstruction?'Reconstruction':'Repair',result);
  await save(ctx,cast,{...cast.data,outcome:'resolved',careEventId:ctx.commandId});await ctx.link(ctx.commandId,'used_cast',cast.id);
  return result;
}
export const careOperations=Object.freeze({
  define_care_protocol:op('character',protocol,define),
  complete_actor_care:op('resources',fields({actorId:identifier,operatorId:identifier,protocolId:identifier,castId:identifier,kind:option('repair','reconstruct'),
    elapsedSeconds:whole(1,86400),uninterrupted:{const:true},toolItemId:identifier,dice:evidence},['actorId','operatorId','protocolId','castId','kind','elapsedSeconds','uninterrupted']),complete),
  set_actor_dormancy:op('character',fields({actorId:identifier,operatorId:identifier,protocolId:identifier,state:option('dormant','active'),elapsedSeconds:whole(1,86400),
    uninterrupted:{const:true}},['actorId','operatorId','protocolId','state','elapsedSeconds','uninterrupted']),async(ctx,x)=>{
    const {a,operator,p}=await access(ctx,x);
    must(a.data.currentHp>0 && a.data.activityState===(x.state==='dormant'?'active':'dormant') && x.elapsedSeconds>=p.data.dormancySeconds,'DORMANCY_INELIGIBLE');
    const next=await addClock(ctx,a,x.elapsedSeconds);if(a.data.activeFormId && !next.activeFormId)await ctx.unlink(a.id,'active_form',a.data.activeFormId);
    await save(ctx,operator,await addClock(ctx,operator,x.elapsedSeconds));await save(ctx,a,{...next,activityState:x.state});
    return {actorId:a.id,state:x.state,elapsedSeconds:x.elapsedSeconds,hp:a.data.currentHp};
  })
});
