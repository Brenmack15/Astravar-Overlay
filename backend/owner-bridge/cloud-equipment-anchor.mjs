import {actor,fields,identifier,must,named,op,owned,readyAnchor,reactionRollWindow,save,useTiming,whole} from './cloud-mechanics-core.mjs';

export const anchorRule=fields({modeId:identifier,itemId:identifier,maximumPounds:whole(1,1000000),dc:whole(1,100),displacementFeet:whole(1,100)},['modeId','itemId','maximumPounds','dc','displacementFeet']);

export const anchorOperations={
  resolve_equipment_anchor:op('combat',fields({actorId:identifier,modeUseId:identifier,loadPounds:whole(0,1000000000),
    opposingActorId:identifier,rollId:identifier,distanceFeet:whole(0,100),destination:named(200)},['actorId','modeUseId']),async(ctx,x)=>{
    ctx.require('combat',x.actorId);
    const a=await actor(ctx,x.actorId),modes=a.data.equipmentModes??[];
    const mode=modes.find(m=>m.anchor?.useId===x.modeUseId&&a.data.gameSeconds!=null&&m.expiresAt>a.data.gameSeconds);
    must(mode&&await readyAnchor(ctx,a.id,mode.anchor.itemId,mode.anchor.itemRevision),'ANCHOR_INACTIVE');
    const rule=mode.anchor;
    if(x.loadPounds!==undefined){
      must(!x.opposingActorId&&!x.rollId&&x.distanceFeet===undefined&&!x.destination,'ANCHOR_EVIDENCE_INVALID');
      const deactivated=x.loadPounds>rule.maximumPounds;
      if(deactivated)await save(ctx,a,{...a.data,equipmentModes:modes.filter(m=>m!==mode)});
      return {modeUseId:x.modeUseId,loadPounds:x.loadPounds,deactivated};
    }
    must(x.opposingActorId&&x.opposingActorId!==a.id&&x.rollId&&x.distanceFeet!==undefined&&Boolean(x.distanceFeet)===Boolean(x.destination),'ANCHOR_EVIDENCE_INVALID');
    const other=await actor(ctx,x.opposingActorId),roll=await owned(ctx,'roll',x.rollId,other.id),r=roll.data;
    ctx.require('combat',other.id,true);
    const currentWindow=await reactionRollWindow(ctx,other);
    must(r.kind==='skill'&&r.key==='athletics'&&r.status==='resolved'&&!r.adjudicated&&r.recordedAt>=rule.recordedAt&&
      (r.reactionWindow??null)===currentWindow,'ANCHOR_ATHLETICS_ROLL_REQUIRED');
    const success=r.total>=rule.dc;
    must(x.distanceFeet<=(success?rule.displacementFeet:0),'ANCHOR_DISPLACEMENT_EXCEEDED');
    await useTiming(ctx,other,'action');
    await save(ctx,roll,{...r,adjudicated:true});
    if(x.distanceFeet)await save(ctx,a,{...a.data,position:x.destination});
    return {modeUseId:x.modeUseId,rollId:roll.id,dc:rule.dc,success,distanceFeet:x.distanceFeet};
  })
};
