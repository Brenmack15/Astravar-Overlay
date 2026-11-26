import { BridgeError } from './core.mjs';
import { CloudPlan } from './cloud-plan.mjs';
import { cloudFail, validCloudId } from './cloud-validation.mjs';
import { sheet, gearEligible, numeric } from './cloud-mechanics-core.mjs';
import { snapshotComponents } from './cloud-mechanics-combat.mjs';

export async function cloudSpellbook(service,principal,id,actorId,options={}) {
  if(!validCloudId(actorId))cloudFail(400,'INVALID_ACTOR_ID');
  const opened=await service.access(principal,id),{grant,continuity:c}=opened;
  const ctx=new CloudPlan(service,service.key(id),grant,{commandId:'spellbook-preview',operation:'inspect_spells'},service.now());
  ctx.require('spells',actorId,false);ctx.require('resources',actorId,false);
  const a=await ctx.get('actor',actorId);
  if(!a||a.life!=='active')cloudFail(404,'ACTOR_UNAVAILABLE');
  const slots=[],pools=new Map();
  for(const poolId of a.data.poolIds){
    const p=await ctx.get('resource_pool',poolId);if(!p||p.life!=='active'||p.actorId!==actorId)continue;
    pools.set(poolId,p);
    if(p.data.type==='spell_slot')slots.push({id:p.id,title:p.title,level:p.data.slotLevel,current:p.data.current,maximum:p.data.maximum});
  }
  const pageSize=options.limit??24,after=options.after??'';
  if(!Number.isInteger(pageSize)||pageSize<1||pageSize>50||after&&!validCloudId(after))cloudFail(400,'INVALID_PAGE');
  if(after&&(options.atRevision!==c.revision||options.atEpoch!==c.epoch||options.grantRevision!==grant.revision))cloudFail(409,'SNAPSHOT_CHANGED');
  const rowIds=(await service.s("SELECT id FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND actor_id=? AND kind='spell_grant' AND life='active' AND id>? ORDER BY id LIMIT ?",...service.key(id),actorId,after,pageSize+1).all()).results;
  const stats=await sheet(ctx,actorId),rows=[];
  for(const {id:grantId} of rowIds.slice(0,pageSize)){
    const g=await ctx.get('spell_grant',grantId),d=g?.data;
    if(!d)cloudFail(409,'SNAPSHOT_CHANGED');
    const spell=await ctx.get('spell',d.spellId);
    if(!spell||spell.life!=='active'||spell.actorId!==actorId)continue;
    const def=spell.data;
    const allowedGear=await gearEligible(ctx,d.sourceItemId,actorId,d.requiresAttunement,d.requiresOperating);
    const base=d.ability?stats.abilities[d.ability]?.modifier:null;
    const sourceBonus=base!=null&&stats.proficiency!=null?base+stats.proficiency:null;
    const hitBase=d.fixedHit??sourceBonus, dcBase=d.fixedDc??(sourceBonus==null?null:8+sourceBonus);
    const hit=def.resolution==='attack'&&hitBase!=null?numeric(hitBase,stats.effects,'spell_attack',grantId).value:null;
    const dc=def.resolution==='save'&&dcBase!=null?numeric(dcBase,stats.effects,'spell_dc',grantId).value:null;
    for(const level of d.levels){
      const reasons=[],eligibleSlots=slots.filter(s=>s.level===level&&s.current!==null&&s.current>0);
      if(d.preparation==='known')reasons.push('PREPARATION_REQUIRED');
      if(!allowedGear)reasons.push('SOURCE_ITEM_UNAVAILABLE');
      if(d.prerequisites||def.resolution==='manual')reasons.push('MANUAL_ADJUDICATION_REQUIRED');
      if(d.route==='slot'&&!eligibleSlots.length)reasons.push('MATCHING_SLOT_UNAVAILABLE');
      const shared=d.resourceId?pools.get(d.resourceId)?.data:null;
      if(d.resourceId&&(shared?.current==null||shared.current<d.cost))reasons.push('GRANT_RESOURCE_UNAVAILABLE');
      if(stats.conditions.some(s=>['incapacitated','unconscious','stunned','paralyzed','petrified'].includes(s)))reasons.push('CASTER_INCAPACITATED');
      if(d.route==='ritual'&&a.data.encounterId)reasons.push('RITUAL_UNAVAILABLE');
      if(def.resolution==='attack'&&hit===null||def.resolution==='save'&&dc===null)reasons.push('CASTING_STAT_UNKNOWN');
      let components=[];
      try{components=snapshotComponents(def.components,stats,{},level-def.baseLevel);}
      catch(e){if(!(e instanceof BridgeError))throw e;reasons.push(e.code);}
      rows.push({grantId,spellId:spell.id,name:spell.title,baseLevel:def.baseLevel,level,route:d.route,source:d.source,preparation:d.preparation,
        concentration:def.concentration,requiresConcentrationDecision:Boolean(def.concentration&&a.data.concentrationCastId),
        timing:def.timing,range:def.range??null,duration:def.duration??null,description:def.description??null,
        resolution:def.resolution,hit,dc,saveAbility:def.saveAbility??null,components,eligibleSlotIds:eligibleSlots.map(s=>s.id),
        resource:d.resourceId?{id:d.resourceId,current:shared?.current??null,cost:d.cost}:null,unavailable:reasons});
    }
  }
  const again=await service.access(principal,id);
  if(again.continuity.revision!==c.revision||again.continuity.epoch!==c.epoch||again.grant.revision!==grant.revision)cloudFail(409,'SNAPSHOT_CHANGED');
  return {continuityId:id,actorId,epoch:c.epoch,revision:c.revision,grantRevision:grant.revision,slots,rows,nextAfter:rowIds.length>pageSize?rowIds[pageSize-1].id:null};
}
