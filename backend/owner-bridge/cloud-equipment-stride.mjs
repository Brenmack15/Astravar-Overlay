import {actor,boolean,fields,identifier,must,named,op,option,reactionRollWindow,save,sheet,useTiming,whole} from './cloud-mechanics-core.mjs';

export const strideOperations={
  resolve_equipment_jump:op('combat',fields({actorId:identifier,effectId:identifier,
    jump:option('long','high'),runUpFeet:whole(0,1000),remainingMovementFeet:whole(0,10000),distanceFeet:whole(1,1000),
    dash:boolean,destination:named(200),source:named(300)},['actorId','effectId','jump','runUpFeet','remainingMovementFeet','distanceFeet','destination','source']),async(ctx,x)=>{
    ctx.require('combat',x.actorId);
    const a=await actor(ctx,x.actorId),p=await sheet(ctx,a.id),effect=p.effects.find(e=>e.id===x.effectId);
    must(effect?.data.target==='equipment_property'&&effect.data.key==='striding'&&p.movement.walk>0,'JUMP_EQUIPMENT_UNAVAILABLE');
    const rule=effect.data.strideRule,str=p.abilities.STR;
    must(str.effective!=null&&str.modifier!=null,'JUMP_STRENGTH_UNKNOWN');
    const window=await reactionRollWindow(ctx,a);
    if(window){const e=await ctx.get('encounter',a.data.encounterId);must(e.data.participants[e.data.index]===a.id,'JUMP_OUTSIDE_TURN');}
    const prior=window&&a.data.equipmentJumpMovement?.window===window?a.data.equipmentJumpMovement:null;
    const remaining=prior?.remainingFeet??p.movement.walk;
    if(x.dash)await useTiming(ctx,a,'action');
    // The table supplies movement left after other movement and run-up. The tracked jump ledger prevents reusing the same movement in the turn.
    const base=x.jump==='long'?str.effective:Math.max(0,3+str.modifier);
    const available=Math.min(x.remainingMovementFeet,Math.max(0,remaining-x.runUpFeet))+(x.dash?p.movement.walk:0);
    const maximumFeet=Math.min(available,Math.floor(base*rule.jumpMultiplier*(x.runUpFeet>=10?1:0.5)));
    must(x.distanceFeet<=maximumFeet,'JUMP_EXCEEDS_DISTANCE_OR_MOVEMENT');
    const remainingFeet=available-x.distanceFeet;
    await save(ctx,a,{...a.data,position:x.destination,...(window?{equipmentJumpMovement:{window,remainingFeet}}:{})});
    return {actorId:a.id,effectId:effect.id,dash:Boolean(x.dash),jump:x.jump,distanceFeet:x.distanceFeet,remainingFeet,destination:x.destination,source:x.source};
  })
};
