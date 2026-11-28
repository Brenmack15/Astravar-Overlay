import {actor,boolean,fields,gearEligible,identifier,must,op,option,owned,save,whole} from './cloud-mechanics-core.mjs';

export const lockTarget=fields({targetActorId:identifier,distanceFeet:whole(0,10000),seenOrResolved:boolean,totalCover:boolean},
  ['targetActorId','distanceFeet','seenOrResolved','totalCover']);

export async function acquireTargetLock(ctx,controller,action,args,result){
  const choice=args.lockTarget,d=action.data;
  must(choice.targetActorId!==controller.id&&choice.seenOrResolved&&!choice.totalCover&&choice.distanceFeet<=d.lockRangeFeet,'LOCK_TARGET_UNAVAILABLE');
  const target=await actor(ctx,choice.targetActorId);
  must(!target.data.conditions?.includes('dead'),'LOCK_TARGET_UNAVAILABLE');
  const item=await owned(ctx,'item_instance',d.sourceItemId,controller.id);
  const cooldown=item.data.weaponBusCooldown;
  if(cooldown)must(cooldown.actorId===controller.id&&controller.data.gameSeconds!=null&&controller.data.gameSeconds>=cooldown.until,'WEAPON_BUS_UNAVAILABLE');
  const lock={actionId:action.id,sourceItemId:item.id,targetActorId:target.id,useId:args.useId};
  await save(ctx,controller,{...controller.data,weaponTargetLock:lock});
  result.lock=lock;
}
export async function applicableTargetLock(ctx,controller,attack,targets){
  const lock=controller.data.weaponTargetLock;
  if(!lock||attack.data.resolution!=='attack'||attack.data.sourceItemId!==lock.sourceItemId||targets?.length!==1||targets[0]!==lock.targetActorId)return null;
  const target=await ctx.get('actor',lock.targetActorId);
  if(target?.life!=='active'||target.data.conditions?.includes('dead'))return null;
  const use=await ctx.get('action_use',lock.useId),rule=await ctx.get('action_definition',lock.actionId);
  if(use?.life!=='active'||use.actorId!==controller.id||use.data.actionId!==lock.actionId||
    use.data.lock?.targetActorId!==target.id||use.data.lock?.sourceItemId!==lock.sourceItemId||
    rule?.life!=='active'||rule.actorId!==controller.id||rule.data.type!=='weapon_target_lock'||rule.data.sourceItemId!==lock.sourceItemId)return null;
  if(!await gearEligible(ctx,lock.sourceItemId,controller.id,rule.data.requiresAttunement,rule.data.requiresOperating))return null;
  return lock;
}
export const releaseTargetLock=op('combat',fields({actorId:identifier,targetActorId:identifier,reason:option('released','target_died'),confirmed:{const:true}},
  ['actorId','targetActorId','reason','confirmed']),async(ctx,x)=>{
    ctx.require('combat',x.actorId,true);
    const a=await actor(ctx,x.actorId),lock=a.data.weaponTargetLock;
    must(lock?.targetActorId===x.targetActorId,'NO_MATCHING_TARGET_LOCK');
    await save(ctx,a,{...a.data,weaponTargetLock:null});
    return {actorId:a.id,targetActorId:x.targetActorId,ended:x.reason};
  });
