import {applyClassGain,checkedClassTracks} from './cloud-class-progression.mjs';
import { classIdentityFields,classTracksSchema,subclassPin,stats, identifier, whole, named, option, nullable, boolean, arrayOf, fields, must, op, actor, owned, insert, save } from './cloud-mechanics-core.mjs';

const notes=nullable(named(4000));
const profileSchema=fields({actorId:identifier,source:named(300),name:named(160),classTracks:classTracksSchema,species:notes,className:notes,
  subclass:notes,background:notes,appearance:notes,personality:notes,backstory:notes,notes},['actorId','source']);
const trainSchema=fields({actorId:identifier,category:option('save','tool','weapon','armor','language'),key:named(120),
  trained:boolean,source:named(300)},['actorId','category','key','trained','source']);
const featureKinds=['effect','action_definition','attack','resource_pool','spell_grant'];
const featureSchema=fields({actorId:identifier,featureId:identifier,name:named(160),kind:option('class','subclass','species','feat','homebrew','companion','form'),
  description:named(3000),source:named(300),formId:identifier,sourceItemId:identifier,requiresOperating:boolean,requiresAttunement:boolean,
  speech:fields({languages:arrayOf(named(80),24),capacity:whole(0,24),fullSentences:boolean},['languages','capacity','fullSentences']),
  telepathy:fields({partnerActorId:identifier,twoWay:boolean,sharedSenses:boolean,limitFeet:whole(1,1000000)},['partnerActorId','twoWay','sharedSenses']),
  lipReading:fields({visibleMouth:{const:true},understoodLanguage:{const:true}},['visibleMouth','understoodLanguage']),
  feat:fields({familyId:identifier,edition:option('legacy_2014','revised_2024','homebrew'),
    includedAbility:fields({ability:option(...stats),increase:whole(1,10)},['ability','increase']),
    includedExpertise:arrayOf(identifier,32)},['familyId','edition']),
  mobility:option('flyby'),references:arrayOf(fields({id:identifier,kind:option(...featureKinds)},['id','kind']),24)},
  ['actorId','featureId','name','kind','description','source','references']);
const capacity=fields({poolId:identifier,previous:whole(),maximum:whole(),granted:whole()},['poolId','previous','maximum','granted']);
const advancementSchema=fields({actorId:identifier,eventId:identifier,fromLevel:whole(1,19),toLevel:whole(2,20),source:named(300),
  classGain:fields({classId:identifier,fromClassLevel:whole(0,19),newClass:fields(classIdentityFields,['name','edition','source']),subclass:subclassPin},['classId','fromClassLevel']),
  abilityGains:fields(Object.fromEntries(stats.map(k=>[k,whole(1,100)]))),hitPointGain:whole(),increaseCurrentHp:boolean,
  reviewedProficiency:whole(0,30),capacities:arrayOf(capacity,24)},['actorId','eventId','fromLevel','toLevel','source']);

async function profileDetails(ctx,x){
  const a=await actor(ctx,x.actorId), biography={...a.data.biography};
  const entries=Object.entries(x).filter(([key])=>!['actorId','source','name','classTracks'].includes(key));
  must(entries.length>0||x.name||x.classTracks,'PROFILE_CHANGE_REQUIRED');
  must(!x.classTracks||!a.data.classTracks,'CLASS_BASELINE_ALREADY_PINNED');
  const classes=x.classTracks?{classTracks:checkedClassTracks(a.data.level,x.classTracks),classBaselineSource:x.source}:{};
  for(const [key,value] of entries)biography[key]={value,source:x.source};
  await ctx.put({...a,title:x.name??a.title,data:{...a.data,biography,...classes,...(x.name?{nameSource:x.source}:{})}});
  return {actorId:a.id,fields:[...entries.map(([key])=>key),...(x.classTracks?['classTracks']:[])],name:x.name??a.title};
}
async function training(ctx,x){
  const a=await actor(ctx,x.actorId), key=x.key.trim();
  must(key.length>0,'TRAINING_NAME_REQUIRED');
  if(x.category==='save')must(stats.includes(key),'INVALID_ABILITY');
  const prior=a.data.training??[];
  const list=prior.filter(t=>t.category!==x.category||t.key.toLowerCase()!==key.toLowerCase());
  if(x.trained)list.push({category:x.category,key,source:x.source});
  must(list.length<=160,'TRAINING_LIMIT');
  const data={...a.data,training:list};
  if(x.category==='save'){
    const saves=new Set(data.saveProficiencies??[]);
    if(x.trained)saves.add(key);else saves.delete(key);
    data.saveProficiencies=[...saves].sort();
  }
  await save(ctx,a,data);return {actorId:a.id,category:x.category,key,trained:x.trained,source:x.source};
}
async function feature(ctx,x,edit){
  const a=await actor(ctx,x.actorId),old=edit?await owned(ctx,'feature',x.featureId,a.id):null;
  if(x.formId)await owned(ctx,'form',x.formId,a.id);
  must(x.kind!=='form'||x.formId,'FEATURE_FORM_REQUIRED');
  must((!x.requiresOperating&&!x.requiresAttunement)||x.sourceItemId,'FEATURE_ITEM_REQUIRED');
  if(x.sourceItemId)must((await ctx.get('item_instance',x.sourceItemId))?.life==='active','FEATURE_ITEM_REQUIRED');
  if(x.speech)must(x.speech.languages.length<=x.speech.capacity && new Set(x.speech.languages.map(l=>l.toLowerCase())).size===x.speech.languages.length,'INVALID_LANGUAGE_PACKAGES');
  if(x.telepathy){must(x.telepathy.partnerActorId!==a.id,'FEATURE_PARTNER_INVALID');await actor(ctx,x.telepathy.partnerActorId);}
  if(old?.data.feat)must(x.feat?.familyId===old.data.feat.familyId,'FEATURE_FAMILY_IDENTITY_REQUIRED');
  if(x.feat){
    must(x.kind==='feat','FEAT_CONFIGURATION_REQUIRES_FEAT');
    const duplicate=await ctx.service.s(`SELECT id FROM cloud_entities WHERE owner_id=? AND continuity_id=?
      AND actor_id=? AND kind='feature' AND id!=? AND json_extract(data_json,'$.feat.familyId')=? LIMIT 1`,
      ...ctx.binding,a.id,x.featureId,x.feat.familyId).first();
    must(!duplicate,'FEATURE_FAMILY_ALREADY_ASSIGNED');
    const included=x.feat.includedAbility;
    if(included)must(a.data.abilities[included.ability]!=null && a.data.abilities[included.ability]>=included.increase,
      'INCLUDED_ABILITY_VALUE_UNKNOWN');
    const expertise=x.feat.includedExpertise??[];
    must(new Set(expertise).size===expertise.length && expertise.every(id=>Object.hasOwn(a.data.skills,id) && a.data.skills[id].rank===2),
      'INCLUDED_EXPERTISE_NOT_ESTABLISHED');
  }
  const sensory=Boolean(x.speech||x.telepathy||x.mobility||x.lipReading);
  const indexed=(a.data.sensoryFeatureIds??[]).filter(id=>id!==x.featureId);
  if(sensory)indexed.push(x.featureId);
  must(indexed.length<=64,'SENSORY_FEATURE_LIMIT');
  must(new Set(x.references.map(r=>r.id)).size===x.references.length,'DUPLICATE_FEATURE_REFERENCE');
  for(const r of x.references)await owned(ctx,r.kind,r.id,a.id);
  const data={category:x.kind,description:x.description,source:x.source,formId:x.formId??null,references:x.references,
    ...(x.sourceItemId?{sourceItemId:x.sourceItemId,requiresAttunement:x.requiresAttunement??false,requiresOperating:x.requiresOperating??false}:{}),
    ...(x.feat?{feat:x.feat}:{}),...(x.lipReading?{lipReading:x.lipReading}:{}),
    ...(x.speech?{speech:x.speech}:{}),...(x.telepathy?{telepathy:x.telepathy}:{}),...(x.mobility?{mobility:x.mobility}:{})};
  if(old){
    for(const r of old.data.references)if(!x.references.some(n=>n.id===r.id))await ctx.unlink(old.id,'feature_mechanic',r.id);
    if(old.data.formId&&old.data.formId!==x.formId)await ctx.unlink(old.id,'feature_form',old.data.formId);
    await ctx.put({...old,title:x.name,data});
  }else await insert(ctx,'feature',x.featureId,a.id,x.name,data);
  if(sensory||a.data.sensoryFeatureIds?.includes(x.featureId))await save(ctx,a,{...a.data,sensoryFeatureIds:indexed});
  for(const [kind,prev,next] of [['feature_source',old?.data.sourceItemId,x.sourceItemId],['communicates_with',old?.data.telepathy?.partnerActorId,x.telepathy?.partnerActorId]]){
    if(prev && prev!==next)await ctx.unlink(x.featureId,kind,prev);
    if(next && prev!==next)await ctx.link(x.featureId,kind,next);
  }
  for(const r of x.references)if(!old?.data.references.some(b=>b.id===r.id))await ctx.link(x.featureId,'feature_mechanic',r.id);
  if(x.formId && x.formId!==old?.data.formId)await ctx.link(x.featureId,'feature_form',x.formId);
  return {actorId:a.id,featureId:x.featureId,...data};
}
async function advance(ctx,x){
  const a=await actor(ctx,x.actorId), before=a.data, next={...before,abilities:{...before.abilities}};
  must(before.level===x.fromLevel && x.toLevel===x.fromLevel+1,'ADVANCEMENT_LEVEL_CHANGED');
  must(!before.encounterId,'ADVANCEMENT_IN_ENCOUNTER');
  const classChange=applyClassGain(before,x,next);
  must(x.increaseCurrentHp===undefined||x.hitPointGain!==undefined,'HIT_POINT_GAIN_REQUIRED');
  for(const [key,rise] of Object.entries(x.abilityGains??{})){
    must(before.abilities[key]!=null && before.abilities[key]+rise<=100,'ABILITY_GAIN_REQUIRES_KNOWN_BASE');
    next.abilities[key]=before.abilities[key]+rise;
  }
  if(x.hitPointGain!==undefined){
    must(before.maxHp!=null && before.maxHp+x.hitPointGain<=1000000,'ADVANCEMENT_HP_UNAVAILABLE');
    next.maxHp=before.maxHp+x.hitPointGain;
    if(x.increaseCurrentHp){must(before.currentHp!=null,'HP_UNKNOWN');next.currentHp=before.currentHp+x.hitPointGain;}
  }
  if(x.reviewedProficiency!==undefined){next.proficiency=x.reviewedProficiency;next.proficiencyMethod='reviewed';}
  else if(before.proficiencyMethod==='level')next.proficiency=2+Math.floor((x.toLevel-1)/4);
  next.level=x.toLevel;
  const seen=new Set(),changes=[];
  for(const c of x.capacities??[]){
    must(!seen.has(c.poolId),'DUPLICATE_CAPACITY');seen.add(c.poolId);
    const p=await owned(ctx,'resource_pool',c.poolId,a.id);
    must(!p.data.classId||p.data.classId===x.classGain?.classId,'HIT_DIE_CLASS_MISMATCH');
    must(p.data.maximum===c.previous && c.maximum>=c.previous && c.granted<=c.maximum-c.previous,'ADVANCEMENT_CAPACITY_CHANGED');
    must(!c.granted||p.data.current!=null,'BALANCE_UNKNOWN');
    const resulting=p.data.current==null?null:p.data.current+c.granted;
    await save(ctx,p,{...p.data,maximum:c.maximum,current:resulting});
    changes.push({...c,before:p.data.current,after:resulting});
  }
  await save(ctx,a,next);
  const data={fromLevel:x.fromLevel,toLevel:x.toLevel,...(classChange?{classChange}:{}),beforeAbilities:before.abilities,afterAbilities:next.abilities,
    previousMaxHp:before.maxHp,maxHp:next.maxHp,previousHp:before.currentHp,hp:next.currentHp,previousProficiency:before.proficiency,
    proficiency:next.proficiency,pools:changes,source:x.source,commandId:ctx.commandId,recordedAt:ctx.now};
  await insert(ctx,'progression_event',x.eventId,a.id,`Level ${x.toLevel}`,data);
  for(const c of changes)await ctx.link(x.eventId,'affected_pool',c.poolId);
  return {eventId:x.eventId,actorId:a.id,...data};
}
export const profileOperations=Object.freeze({
  set_actor_profile:op('character',profileSchema,profileDetails),set_actor_training:op('character',trainSchema,training),
  define_feature:op('character',featureSchema,(ctx,x)=>feature(ctx,x,false)),revise_feature:op('character',featureSchema,(ctx,x)=>feature(ctx,x,true)),
  advance_actor_level:op('character',advancementSchema,advance)
});
