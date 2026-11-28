import {fields,identifier,whole,named,boolean,arrayOf,must,owned,save} from './cloud-mechanics-core.mjs';

export const sensingDefinition={lineage:identifier,radiusMiles:whole(1,1000),maximumSpellLevel:whole(0,9)};
export const sensingArgs={
  finding:fields({lineage:identifier,nearestVerified:{const:true},detected:boolean,distanceFeet:whole(0,5280000),direction:named(120),source:named(300)},['lineage','nearestVerified','detected','source']),
  ward:fields({castId:identifier,casterId:identifier,directlyTargeted:{const:true},beforeEffect:{const:true}},['castId','casterId','directlyTargeted','beforeEffect'])
};
export const pointAreaInput=fields({centerDistanceFeet:whole(0,10000),allCreaturesIncluded:{const:true},creatures:arrayOf(fields({actorId:identifier,distanceFromCenter:whole(0,10000)},['actorId','distanceFromCenter']),16)},['centerDistanceFeet','allCreaturesIncluded','creatures']);

export function checkPointArea(rule,area,targets){
  if(!rule){must(!area,'UNEXPECTED_POINT_AREA');return;}
  must(area && area.centerDistanceFeet<=rule.range && area.allCreaturesIncluded,'POINT_AREA_POSITION_REQUIRED');
  const all=new Set(targets??[]);
  must(area.creatures.length===all.size && area.creatures.every(v=>all.delete(v.actorId) && v.distanceFromCenter<=rule.radius),'AREA_TARGET_OUT_OF_BOUNDS');
}
export async function useSensing(ctx,a,action,x,out){
  const d=action.data;
  must(Boolean(x.ward)===(d.type==='direct_spell_ward') && Boolean(x.finding)===(d.type==='creature_locator'),'SENSING_ARGUMENT_MISMATCH');
  if(x.finding){
    const f=x.finding;
    must(f.lineage===d.lineage && f.nearestVerified,'LOCATOR_LINEAGE_MISMATCH');
    if(f.detected)must(f.distanceFeet!==undefined && f.distanceFeet<=5280*d.radiusMiles && f.direction,'LOCATOR_OUTSIDE_RADIUS');
    else must(f.distanceFeet===undefined&&!f.direction,'UNEXPECTED_LOCATOR_POSITION');
    out.finding=f;
  }
  if(x.ward){
    const w=x.ward,cast=await owned(ctx,'cast_instance',w.castId,w.casterId);
    const spell=await owned(ctx,'spell',cast.data.spellId,w.casterId),s=spell.data,c=cast.data;
    must(c.outcome==='pending' && c.level<=d.maximumSpellLevel && c.targets.includes(a.id) && !s.area && !c.scaling?.areaFeet &&
      !(c.targetResults??[]).some(v=>v.targetActorId===a.id),'SPELL_NOT_INTERCEPTABLE');
    ctx.require('spells',w.casterId,true);
    const previous=c.targetResults??[],targetResults=[...previous,{targetActorId:a.id,decision:'warded',amount:0,healing:0,components:[],useId:x.useId}];
    await save(ctx,cast,{...c,targetResults,outcome:targetResults.length===c.targets.length?'resolved':'pending'});
    if((a.data.rollAidRefs??[]).some(v=>v.castId===cast.id)){
      ctx.require('character',a.id,true);
      await save(ctx,a,{...a.data,rollAidRefs:a.data.rollAidRefs.filter(v=>v.castId!==cast.id)});
    }
    out.interceptedCastId=cast.id;
  }
}
