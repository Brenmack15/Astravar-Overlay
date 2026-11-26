import { stats, identifier, whole, named, option, nullable, boolean, arrayOf, fields, must, op, actor, owned, insert, save } from './cloud-mechanics-core.mjs';

const notes=nullable(named(4000));
const profileSchema=fields({actorId:identifier,source:named(300),name:named(160),species:notes,className:notes,
  subclass:notes,background:notes,appearance:notes,personality:notes,backstory:notes,notes},['actorId','source']);
const trainSchema=fields({actorId:identifier,category:option('save','tool','weapon','armor','language'),key:named(120),
  trained:boolean,source:named(300)},['actorId','category','key','trained','source']);
const featureKinds=['effect','action_definition','attack','resource_pool','spell_grant'];
const featureSchema=fields({actorId:identifier,featureId:identifier,name:named(160),kind:option('class','subclass','species','feat','homebrew','companion','form'),
  description:named(3000),source:named(300),formId:identifier,references:arrayOf(fields({id:identifier,kind:option(...featureKinds)},['id','kind']),24)},
  ['actorId','featureId','name','kind','description','source','references']);
const capacity=fields({poolId:identifier,previous:whole(),maximum:whole(),granted:whole()},['poolId','previous','maximum','granted']);
const advancementSchema=fields({actorId:identifier,eventId:identifier,fromLevel:whole(1,19),toLevel:whole(2,20),source:named(300),
  abilityGains:fields(Object.fromEntries(stats.map(k=>[k,whole(1,100)]))),hitPointGain:whole(),increaseCurrentHp:boolean,
  reviewedProficiency:whole(0,30),capacities:arrayOf(capacity,24)},['actorId','eventId','fromLevel','toLevel','source']);

async function profileDetails(ctx,x){
  const a=await actor(ctx,x.actorId), biography={...a.data.biography};
  const entries=Object.entries(x).filter(([key])=>!['actorId','source','name'].includes(key));
  must(entries.length>0||x.name,'PROFILE_CHANGE_REQUIRED');
  for(const [key,value] of entries)biography[key]={value,source:x.source};
  await ctx.put({...a,title:x.name??a.title,data:{...a.data,biography,...(x.name?{nameSource:x.source}:{})}});
  return {actorId:a.id,fields:entries.map(([key])=>key),name:x.name??a.title};
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
  must(new Set(x.references.map(r=>r.id)).size===x.references.length,'DUPLICATE_FEATURE_REFERENCE');
  for(const r of x.references)await owned(ctx,r.kind,r.id,a.id);
  const data={category:x.kind,description:x.description,source:x.source,formId:x.formId??null,references:x.references};
  if(old){
    for(const r of old.data.references)if(!x.references.some(n=>n.id===r.id))await ctx.unlink(old.id,'feature_mechanic',r.id);
    if(old.data.formId&&old.data.formId!==x.formId)await ctx.unlink(old.id,'feature_form',old.data.formId);
    await ctx.put({...old,title:x.name,data});
  }else await insert(ctx,'feature',x.featureId,a.id,x.name,data);
  for(const r of x.references)if(!old?.data.references.some(b=>b.id===r.id))await ctx.link(x.featureId,'feature_mechanic',r.id);
  if(x.formId && x.formId!==old?.data.formId)await ctx.link(x.featureId,'feature_form',x.formId);
  return {actorId:a.id,featureId:x.featureId,...data};
}
async function advance(ctx,x){
  const a=await actor(ctx,x.actorId), before=a.data, next={...before,abilities:{...before.abilities}};
  must(before.level===x.fromLevel && x.toLevel===x.fromLevel+1,'ADVANCEMENT_LEVEL_CHANGED');
  must(!before.encounterId,'ADVANCEMENT_IN_ENCOUNTER');
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
    must(p.data.maximum===c.previous && c.maximum>=c.previous && c.granted<=c.maximum-c.previous,'ADVANCEMENT_CAPACITY_CHANGED');
    must(!c.granted||p.data.current!=null,'BALANCE_UNKNOWN');
    const resulting=p.data.current==null?null:p.data.current+c.granted;
    await save(ctx,p,{...p.data,maximum:c.maximum,current:resulting});
    changes.push({...c,before:p.data.current,after:resulting});
  }
  await save(ctx,a,next);
  const data={fromLevel:x.fromLevel,toLevel:x.toLevel,beforeAbilities:before.abilities,afterAbilities:next.abilities,
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
