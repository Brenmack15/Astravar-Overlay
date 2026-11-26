import { checkDiceOwner, stats, identifier, boolean, named, whole, nullable, arrayOf, option, fields, evidence, mode, spellSchema, must, op, actor, owned, insert, save, gearEligible, numeric, sheet, rollPolicy, spend, useTiming } from './cloud-mechanics-core.mjs';
import { damageEvidence, componentRules, snapshotComponents, rollComponents } from './cloud-mechanics-combat.mjs';
import { observed } from './cloud-mechanics-rolls.mjs';

export function scaledSpellEffects(spell,level){
  const d=spell.scaling??{},extra=level-spell.baseLevel;
  const derived=(key,increment)=>d[key]==null?null:d[key]+(d[increment]??0)*extra;
  return {targets:derived('targets','targetsPerLevel'),durationSeconds:derived('durationSeconds','durationPerLevel'),areaFeet:derived('areaFeet','areaPerLevel'),source:d.source??null};
}
async function defineSpell(ctx,x,edit=false){
  await actor(ctx,x.actorId);componentRules(x.components,true);
  must(x.resolution!=='save'||x.saveAbility,'SPELL_SAVE_ABILITY_REQUIRED');
  for(const [base,perLevel] of [['targets','targetsPerLevel'],['durationSeconds','durationPerLevel'],['areaFeet','areaPerLevel']])
    must(x.scaling?.[perLevel]===undefined||x.scaling[base]!==undefined,'SPELL_SCALING_BASE_REQUIRED');
  const {actorId,spellId,name,...data}=x;
  if(edit){
    const old=await owned(ctx,'spell',spellId,actorId);
    // Casting-source grants already encode legal levels and accuracy requirements.
    must(old.data.baseLevel===x.baseLevel && old.data.resolution===x.resolution,'SPELL_GRANT_COMPATIBILITY_REQUIRED');
    await ctx.put({...old,title:name,data});
  }else await insert(ctx,'spell',spellId,actorId,name,data);
  return {spellId};
}
const grantSchema=fields({
  actorId:identifier,spellId:identifier,grantId:identifier,source:named(300),route:option('at_will','slot','ritual','limited','item'),
  levels:arrayOf(whole(0,9),10),preparation:option('known','prepared','always_prepared','not_required'),
  ability:option(...stats),fixedHit:whole(-100,100),fixedDc:whole(1,100),resourceId:identifier,cost:whole(1),
  sourceItemId:identifier,requiresAttunement:boolean,requiresOperating:boolean,prerequisites:named(500)
},['actorId','spellId','grantId','source','route','levels','preparation']);
async function grantSpell(ctx,x){
  const d=(await owned(ctx,'spell',x.spellId,x.actorId)).data;
  must(x.levels.length>0 && new Set(x.levels).size===x.levels.length && x.levels.every(l=>l>=d.baseLevel),'INVALID_CASTING_LEVELS');
  if(d.baseLevel===0)must(x.levels.length===1&&x.levels[0]===0&&x.route!=='slot','CANTRIP_ROUTE_INVALID');
  if(x.route==='slot')must(d.baseLevel>0 && !x.resourceId && !x.cost,'SLOT_ROUTE_INVALID');
  if(x.route==='ritual')must(d.ritual&&x.levels.length===1&&x.levels[0]===d.baseLevel,'RITUAL_ROUTE_INVALID');
  if(x.route==='at_will'||x.route==='ritual')must(!x.cost&&!x.resourceId,'UNEXPECTED_SPELL_COST');
  if(x.route==='item')must(x.sourceItemId,'SPELL_SOURCE_ITEM_REQUIRED');
  if(x.route==='limited'||x.cost||x.resourceId)must(x.resourceId && x.cost,'SPELL_COST_REQUIRED');
  if(x.sourceItemId)must(await ctx.get('item_instance',x.sourceItemId),'SPELL_SOURCE_ITEM_UNAVAILABLE');
  must((!x.requiresAttunement&&!x.requiresOperating)||x.sourceItemId,'SPELL_SOURCE_ITEM_REQUIRED');
  if(x.resourceId)await owned(ctx,'resource_pool',x.resourceId,x.actorId);
  if(d.resolution==='attack')must(x.fixedHit!=null||x.ability,'SPELL_ACCURACY_REQUIRED');
  if(d.resolution==='save')must(x.fixedDc!=null||x.ability,'SPELL_DC_REQUIRED');
  const {actorId,grantId,...data}=x;
  await insert(ctx,'spell_grant',grantId,actorId,x.source,data);
  await ctx.link(grantId,'grants_spell',x.spellId);
  if(x.resourceId)await ctx.link(grantId,'uses_pool',x.resourceId);
  if(x.sourceItemId)await ctx.link(grantId,'requires_item',x.sourceItemId);
  return {grantId,spellId:x.spellId,route:x.route,levels:x.levels};
}
async function cast(ctx,x){
  const a=await actor(ctx,x.actorId),g=await owned(ctx,'spell_grant',x.grantId,a.id),grant=g.data;
  const spell=await owned(ctx,'spell',grant.spellId,a.id),d=spell.data,p=await sheet(ctx,a.id);
  must(grant.levels.includes(x.level) && grant.preparation!=='known','CASTING_ROUTE_UNAVAILABLE');
  must(!['disabled','dormant'].includes(a.data.activityState),'ACTOR_INACTIVE');
  must(!p.conditions.some(c=>['incapacitated','unconscious','stunned','paralyzed','petrified'].includes(c)),'CASTER_INCAPACITATED');
  must(d.resolution!=='manual'&&!grant.prerequisites,'MANUAL_ADJUDICATION_REQUIRED');
  must(await gearEligible(ctx,grant.sourceItemId,a.id,grant.requiresAttunement,grant.requiresOperating),'SPELL_SOURCE_ITEM_UNAVAILABLE');
  must(!d.concentration||!a.data.concentrationCastId||x.replaceConcentration===true,'CONCENTRATION_DECISION_REQUIRED');
  const costs=[];
  if(grant.route==='slot'){
    must(x.slotPoolId,'MATCHING_SLOT_REQUIRED');
    const pool=await owned(ctx,'resource_pool',x.slotPoolId,a.id);
    must(pool.data.type==='spell_slot'&&pool.data.slotLevel===x.level,'MATCHING_SLOT_REQUIRED');
    if(a.data.encounterId&&a.data.rulesProfile==='revised_2024'){
      const e=await ctx.get('encounter',a.data.encounterId);
      must(e && e.data.slotSpentActorIds?.includes(a.id)!==true,'SLOT_ALREADY_SPENT_THIS_TURN');
      await save(ctx,e,{...e.data,slotSpentActorIds:[...(e.data.slotSpentActorIds??[]),a.id]});
    }
    costs.push(await spend(ctx,a.id,pool.id,1));
  }else{
    must(!x.slotPoolId,'CAST_DOES_NOT_USE_SLOT');
    if(grant.resourceId)costs.push(await spend(ctx,a.id,grant.resourceId,grant.cost));
  }
  if(grant.route==='ritual')must(!a.data.encounterId&&x.confirmedRitualTime===true,'RITUAL_TIME_REQUIRED');
  else await useTiming(ctx,a,d.timing);
  const base=grant.ability?p.abilities[grant.ability].modifier:null;
  const derived=base!=null&&p.proficiency!=null?base+p.proficiency:null;
  const hit=d.resolution==='attack'?numeric(grant.fixedHit??derived,p.effects,'spell_attack',g.id).value:null;
  const dc=d.resolution==='save'?numeric(grant.fixedDc??(derived!=null?derived+8:null),p.effects,'spell_dc',g.id).value:null;
  must(d.resolution!=='attack'||hit!=null,'SPELL_ACCURACY_UNKNOWN');
  must(d.resolution!=='save'||dc!=null,'SPELL_DC_UNKNOWN');
  const scaling=scaledSpellEffects(d,x.level),targets=x.targets??[];
  must(new Set(targets).size===targets.length && (scaling.targets==null||targets.length<=scaling.targets),'SPELL_TARGET_COUNT_INVALID');
  if(x.deliveryActorId){must(d.rangeKind==='touch' && targets.length===1 && x.deliveryActorId!==a.id,'SPELL_DELIVERY_INELIGIBLE');await actor(ctx,x.deliveryActorId);}
  const data={actorId:a.id,rangeKind:d.rangeKind??null,deliveryActorId:x.deliveryActorId??null,formId:p.formId,spellId:spell.id,grantId:g.id,baseLevel:d.baseLevel,level:x.level,
    route:grant.route,source:grant.source,sourceItemId:grant.sourceItemId??null,costs,timing:grant.route==='ritual'?'ritual':d.timing,
    resolution:d.resolution,hitModifier:hit,save:d.resolution==='save'?{ability:d.saveAbility,dc,success:d.saveSuccess??null}:null,components:snapshotComponents(d.components,p,{},x.level-d.baseLevel,'spell',spell.id),
    concentration:d.concentration,duration:d.duration??null,scaling,targets,targetResults:[],outcome:'pending',hit:null,damage:null,
    effectivePolicy:{normal:rollPolicy(p.effects,'spell_attack:'+g.id),advantage:rollPolicy(p.effects,'spell_attack:'+g.id,'advantage'),disadvantage:rollPolicy(p.effects,'spell_attack:'+g.id,'disadvantage')},
    recordedAt:ctx.now,commandId:ctx.commandId};
  for(const id of data.targets)await actor(ctx,id);
  await insert(ctx,'cast_instance',x.castId,a.id,spell.title,data);
  await ctx.link(x.castId,'cast_spell',spell.id);await ctx.link(x.castId,'used_grant',g.id);
  for(const id of data.targets)await ctx.link(x.castId,'target',id);
  if(d.concentration){
    if(a.data.concentrationCastId)await ctx.unlink(a.id,'concentrates_on',a.data.concentrationCastId);
    await ctx.link(a.id,'concentrates_on',x.castId);
    await save(ctx,a,{...a.data,concentrationCastId:x.castId,concentrationExpiresAt:scaling.durationSeconds!=null && a.data.gameSeconds!=null?a.data.gameSeconds+scaling.durationSeconds:null});
  }
  return {castId:x.castId,...data};
}
async function castRoll(ctx,x){
  const c=await owned(ctx,'cast_instance',x.castId,x.actorId),d=c.data;
  const a=await actor(ctx,x.actorId);
  if(x.hit)checkDiceOwner(ctx,a,'spell',x.hit.origin);
  for(const part of x.damage??[])checkDiceOwner(ctx,a,'spell',part.evidence.origin);
  let hit=d.hit,damage=d.damage;
  must(x.hit||x.damage,'CAST_ROLL_EVIDENCE_REQUIRED');
  if(x.hit){
    must(d.resolution==='attack'&&hit===null,'CAST_HIT_UNAVAILABLE');
    const p=d.effectivePolicy[x.mode??'normal'];
    hit=observed(c.title+' attack',p.count,20,d.hitModifier,p.mode==='normal'?'sum':p.mode==='advantage'?'highest':'lowest',x.hit,true);
  }else must(!x.mode,'UNEXPECTED_ROLL_MODE');
  if(x.damage){
    must(damage===null&&d.components.length>0,'CAST_DAMAGE_UNAVAILABLE');
    must(d.resolution!=='attack'||hit,'RESOLVE_ATTACK_FIRST');
    damage=rollComponents(d.components,x.damage,hit?.natural===20);
  }
  const outcome=hit?.natural===1?'miss':d.outcome;
  await save(ctx,c,{...d,hit,damage,outcome});return {castId:c.id,hit,damage,outcome,costs:d.costs};
}
export const spellOperations=Object.freeze({
  define_spell:op('spells',spellSchema,(ctx,x)=>defineSpell(ctx,x,false)),
  revise_spell:op('spells',spellSchema,(ctx,x)=>defineSpell(ctx,x,true)),
  grant_spell:op('spells',grantSchema,grantSpell),
  prepare_spell:op('spells',fields({actorId:identifier,grantId:identifier,prepared:boolean},['actorId','grantId','prepared']),async(ctx,x)=>{
    const g=await owned(ctx,'spell_grant',x.grantId,x.actorId);
    must(['known','prepared'].includes(g.data.preparation),'GRANT_PREPARATION_FIXED');
    const preparation=x.prepared?'prepared':'known';
    await save(ctx,g,{...g.data,preparation});return {grantId:g.id,preparation};
  }),
  cast_spell:op('spells',fields({actorId:identifier,grantId:identifier,castId:identifier,level:whole(0,9),slotPoolId:identifier,replaceConcentration:boolean,confirmedRitualTime:boolean,deliveryActorId:identifier,targets:arrayOf(identifier,12)},['actorId','grantId','castId','level']),cast),
  link_cast_roll:op('spells',fields({actorId:identifier,castId:identifier,hit:evidence,mode,damage:damageEvidence},['actorId','castId']),castRoll)
});
