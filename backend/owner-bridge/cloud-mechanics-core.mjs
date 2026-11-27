import { activeCastAids } from './cloud-cast-aid.mjs';
import { BridgeError, sha256 } from './core.mjs';

export const stats = ['STR','DEX','CON','INT','WIS','CHA'];
export const damageTypes = ['acid','bludgeoning','cold','fire','force','lightning','necrotic','piercing','poison','psychic','radiant','slashing','thunder'];
export const standardSkills = Object.freeze({
  acrobatics:'DEX', animal_handling:'WIS', arcana:'INT', athletics:'STR', deception:'CHA', history:'INT',
  insight:'WIS', intimidation:'CHA', investigation:'INT', medicine:'WIS', nature:'INT', perception:'WIS',
  performance:'CHA', persuasion:'CHA', religion:'INT', sleight_of_hand:'DEX', stealth:'DEX', survival:'WIS'
});
export const identifier = {type:'string', pattern:'^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$', minLength:1, maxLength:128};
export const boolean = {type:'boolean'};
export const named = maxLength => ({type:'string', minLength:1, maxLength});
export const whole = (minimum = 0, maximum = 1000000) => ({type:'integer', minimum, maximum});
export const option = (...values) => ({type:'string', enum:values});
export const nullable = schema => ({anyOf:[schema, {type:'null'}]});
export const arrayOf = (items, maxItems = 64) => ({type:'array', items, maxItems});
export const fields = (properties, required = []) => ({type:'object', additionalProperties:false, properties, required});
export const abilityMap = fields(Object.fromEntries(stats.map(stat => [stat, nullable(whole(0,100))])));
export const movementMap = fields(Object.fromEntries(['walk','fly','swim','climb','burrow','hover'].map(key => [key,nullable(whole(0,10000))])));
export const sensesMap = fields(Object.fromEntries(['darkvision','blindsight','tremorsense','truesight'].map(key=>[key,nullable(whole(0,10000))])));
export const actionCost = option('action','bonus_action','reaction','attack','other');
export const mode = option('normal','advantage','disadvantage');
export const origin = option('player_digital','player_physical','dm','delegated','app');
export const component = fields({
  id:identifier, name:named(120), type:option(...damageTypes,'healing'), dice:whole(0,100), sides:whole(2,1000),
  flat:whole(-1000,1000), critical:boolean, abilityBonus:option(...stats),
  variants:arrayOf(option(...damageTypes),13), extraDicePerLevel:whole(0,20)
}, ['id','name','type','dice','sides','flat']);
const classText = limit=>({...named(limit),pattern:'\\S'});
export const classIdentityFields = {name:classText(120),edition:option('legacy_2014','revised_2024','custom'),source:classText(300)};
export const subclassPin = fields({name:classText(120),source:classText(300)},['name','source']);
export const classTracksSchema = {...arrayOf(fields({classId:identifier,...classIdentityFields,subclass:subclassPin,level:whole(1,20)},
  ['classId','name','edition','source','level']),20),minItems:1};
export const actorSchema = fields({
  actorId:identifier, name:named(160), classTracks:classTracksSchema, role:option('player','companion','npc','creature'),
  rulesProfile:option('legacy_2014','revised_2024','custom'), level:whole(1,20),initiativeAfterActorId:identifier,
  proficiency:nullable(whole(0,30)), maxHp:nullable(whole()), baselineConstitutionModifier:nullable(whole(-100,100)),currentHp:nullable(whole()), temporaryHp:nullable(whole()),
  baseAc:nullable(whole(0,100)), abilities:abilityMap, speeds:movementMap, senses:sensesMap, size:option('tiny','small','medium','large','huge','gargantuan'),creatureType:named(60), saveProficiencies:arrayOf(option(...stats),6),
  attacksPerAction:whole(1,12), attunementCapacity:nullable(whole(0,12)), exhaustionLevel:nullable(whole(0,6)), gameSeconds:nullable(whole(0,100000000000)),
  source:named(300)
}, ['actorId','name','role','rulesProfile','level']);
export const resourceSchema = fields({
  actorId:identifier, poolId:identifier, name:named(160), classId:identifier,
  type:option('ordinary','hit_die','spell_slot','integrity','lifetime','charge'), maximum:nullable(whole()),
  current:nullable(whole()), recovery:option('none','short_rest','long_rest','either_rest','dawn','deployment','edition_hit_dice','recharge_roll'),rechargeSides:whole(2,100),rechargeMinimum:whole(1,100),
  recoveryActorId:identifier,recoveryAmount:nullable(whole(1)), dieSides:nullable(whole(2,1000)), slotLevel:nullable(whole(1,9)), source:named(300)
}, ['actorId','poolId','name','type','maximum','current','recovery','source']);
export const formSchema = fields({
  actorId:identifier, formId:identifier, name:named(160), source:named(300), description:named(2000),
  size:option('tiny','small','medium','large','huge','gargantuan'), creatureType:named(60),
  sourceItemId:identifier, requiresAttunement:boolean, requiresOperating:boolean, abilityOverrides:abilityMap, speeds:movementMap,
  suppressedItemIds:arrayOf(identifier,24),requiresWilling:boolean,revertOnSourceLoss:boolean,senses:sensesMap,grounded:boolean, baseAc:nullable(whole(0,100)), activationSeconds:whole(0,86400), durationSeconds:nullable(whole(1,31536000)),
  timing:actionCost, deploymentPoolId:identifier, integrityPoolId:identifier, resetPoolId:identifier
}, ['actorId','formId','name','source','timing','activationSeconds']);
export const effectSchema = fields({
  actorId:identifier, effectId:identifier, name:named(160), source:named(300),
  target:option('ability_score','ability_check','skill','save','passive','initiative','ac','max_hp','attack','damage','spell_attack','spell_dc','speed','sense','damage_defense','condition_immunity','spell_damage_immunity','save_mitigation','roll_edge'),
  key:identifier, operation:option('add','set','minimum','maximum','resistance','immunity','vulnerability','advantage','disadvantage','keep_highest_three','evade'),
  amount:whole(-1000,1000), priority:whole(-100,100), stackingKey:identifier, wornResistanceUpgrade:{const:true},
  sourceItemId:identifier, transfersWithItem:boolean, requiresAttunement:boolean, requiresOperating:boolean, requiresWorn:{const:true}, formId:identifier, contextTag:identifier,
  damageRoute:option('attack','spell','both'),damageComponentId:identifier,damageType:option(...damageTypes),eachMatchingComponent:boolean,
  charge:fields({poolId:identifier,amount:whole(1),trigger:option('attempt','confirmed_hit')},['poolId','amount','trigger']),
  expiresAt:nullable(whole(0,100000000000)), enabled:boolean
}, ['actorId','effectId','name','source','target','key','operation']);
export const costSchema = fields({poolId:identifier,amount:whole(1)},['poolId','amount']);
export const restSchema = fields({
  actorId:identifier, kind:option('short_rest','long_rest'), elapsedSeconds:whole(0,31536000), confirmed:{const:true},
  exhaustionRecoveryEligible:boolean,
  hitDice:arrayOf(fields({poolId:identifier,faces:arrayOf(whole(1,1000),100),origin},['poolId','faces','origin']),32),
  restoreHitDice:arrayOf(costSchema,32)
}, ['actorId','kind','elapsedSeconds','confirmed','hitDice']);
export const checkSpec = fields({
  kind:option('ability','save','skill','initiative','custom'), key:identifier, name:named(120), mode,
  social:fields({featureId:identifier,targetActorId:identifier,attitude:option('friendly','indifferent','hostile','unknown'),perceives:boolean,purpose:option('entertain','goodwill','other'),harmlessEndearing:boolean},['featureId','targetActorId','attitude','perceives','purpose','harmlessEndearing']),
  contextTags:arrayOf(identifier,12), dice:whole(1,200), sides:whole(2,1000), flat:whole(-1000000,1000000),
  terms:arrayOf(fields({count:whole(1,200),sides:whole(2,1000),sign:option('add','subtract')},['count','sides','sign']),100)
}, ['kind','key','mode']);
export const evidence = fields({origin,faces:arrayOf(whole(1,1000),200)},['origin','faces']);
export const d20Evidence=fields({...evidence.properties,aidDice:arrayOf(fields({family:identifier,face:whole(1,100)},['family','face']),16)},['origin','faces']);
export const attackSchema = fields({
  actorId:identifier, attackId:identifier, name:named(160), source:named(300), sourceItemId:identifier, transfersWithItem:boolean,
  requiresAttunement:boolean, requiresOperating:boolean, formId:identifier, excludedForms:arrayOf(identifier,16), classification:named(60),
  delivery:option('melee','ranged','area','other'), resolution:option('attack','save','automatic','manual'),
  timing:actionCost, ability:option(...stats), proficient:boolean, hitBonus:whole(-200,200),
  fixedHit:whole(-200,200), saveAbility:option(...stats), saveDc:whole(1,100), dcAbility:option(...stats),
  dcBase:whole(0,100), dcProficient:boolean, dcBonus:whole(-100,100),saveSuccess:option('none','half','full'),
  reach:whole(0,10000), range:whole(0,10000), longRange:whole(0,10000), area:named(160),
  components:arrayOf(component,24), cost:costSchema, description:named(2000),
  weaponBus:fields({cooldownSeconds:whole(1,86400)}),chargeActionId:identifier,
  payloadRule:fields({componentId:identifier,storeActionId:identifier},['componentId','storeActionId']),
  preparedPush:fields({feet:whole(1,200),ability:option(...stats),dc:whole(1,100),prone:boolean},['feet','ability','dc','prone']),
  riders:arrayOf(fields({id:identifier,trigger:option('hit','failed_save','prepared_charge','custom'),effect:named(500),
    duration:named(160),componentId:identifier,saveAbility:option(...stats),saveDc:whole(1,100)},['id','trigger','effect']),16)
},['actorId','attackId','name','source','timing','delivery','resolution','components']);
export const spellSchema = fields({
  actorId:identifier, spellId:identifier, name:named(160), source:named(300), baseLevel:whole(0,9), concentration:boolean,
  timing:actionCost, ritual:boolean, resolution:option('attack','save','automatic','manual'), saveAbility:option(...stats),saveSuccess:option('none','half','full'),
  scaling:fields({targets:whole(1,10000),targetsPerLevel:whole(0,1000),durationSeconds:whole(0,31536000),durationPerLevel:whole(0,31536000),
    areaFeet:whole(0,10000),areaPerLevel:whole(0,10000),source:named(300)},['source']),
  rollAid:fields({family:identifier,sides:whole(2,100),applies:arrayOf(option('attack','save'),2)},['family','sides','applies']),
  description:named(2400),rangeKind:option('touch','self','distance','other'), range:named(120), duration:named(120), components:arrayOf(component,16)
},['actorId','spellId','name','source','baseLevel','concentration','timing','ritual','resolution','components']);

export function must(condition, code, status=409) {
  if (!condition) throw new BridgeError(status,code);
}
export function op(scope,schema,plan) { return {scope,schema,plan}; }
export async function owned(ctx,kind,id,actorId) {
  const row=await ctx.get(kind,id);
  must(row && row.life==='active' && row.actorId===actorId,'MECHANIC_REFERENCE_UNAVAILABLE');
  return row;
}
export async function actor(ctx,id) { return owned(ctx,'actor',id,id); }
export async function insert(ctx,kind,id,actorId,title,data) {
  must(!await ctx.get(kind,id),'MECHANIC_ID_EXISTS');
  const row=await ctx.put({id,kind,actorId,title,data});
  if (id!==actorId) await ctx.link(id,'actor',actorId);
  return row;
}
export async function save(ctx,row,data) { return ctx.put({...row,data}); }
export const diceCategories=['ability','save','skill','initiative','custom','attack','damage','spell','hit_dice'];
export function checkDiceOwner(ctx,a,category,source){
  if(source==='dm')must(a.data.role==='npc'||a.data.role==='creature','PLAYER_DICE_REQUIRE_PLAYER_OR_DELEGATION');
  if(source==='delegated')must((a.data.diceDelegations??[]).includes(category),'DICE_DELEGATION_REQUIRED');
  if(source==='app')must(ctx.grant.subject==='sites-owner'||ctx.grant.subject.startsWith('device:'),'APP_DICE_REQUIRE_DEVICE_ORIGIN');
}
export async function transferableSource(ctx,config){
  if(!config.transfersWithItem)return;
  const item=config.sourceItemId?await ctx.get('item_instance',config.sourceItemId):null;
  must(item?.life==='active' && item.data.partyAccess && item.data.quantity===1 && item.data.custodianId===config.actorId &&
    !config.formId && !config.excludedForms?.length,'TRANSFERABLE_RULE_REQUIRES_INDEPENDENT_ITEM');
  const cost=config.cost??config.charge;
  if(cost){
    must(item.data.resourcePoolId===cost.poolId,'TRANSFERABLE_RULE_REQUIRES_ITEM_CHARGES');
    const pool=await owned(ctx,'resource_pool',cost.poolId,config.actorId);
    must(pool.data.type==='charge','TRANSFERABLE_RULE_REQUIRES_ITEM_CHARGES');
  }
}
export async function gearEligible(ctx,itemId,actorId,attunement=false,requiresOperating=false,formOverride=undefined,wornOnly=false) {
  if (!itemId) return !attunement && !requiresOperating;
  const item=await ctx.current(itemId);
  if(item?.kind!=='item_instance' || item.data.custodianId!==actorId)return false;
  ctx.require('inventory',actorId,false);
  const data=item.data;
  const equipped=!!data && item.life==='active' && data.custodianId===actorId && data.partyAccess===true &&
    data.holding==='carried' && (data.equipment==='worn'||!wornOnly && data.equipment==='held') && (!requiresOperating || data.operating===true) &&
    data.quantity===1 && (!attunement || data.attuned===true);
  if(!equipped)return false;
  const form=formOverride===undefined?await activeForm(ctx,await actor(ctx,actorId)):formOverride;
  return !form?.data.suppressedItemIds?.includes(itemId);
}
export async function activeForm(ctx,a) {
  const d=a.data;
  if (!d.activeFormId) return null;
  const f=await ctx.get('form',d.activeFormId);
  if (!f || f.actorId!==a.id || f.life!=='active') return null;
  if (d.formExpiresAt!=null && (d.gameSeconds==null || d.gameSeconds>=d.formExpiresAt)) return null;
  return await gearEligible(ctx,f.data.sourceItemId,a.id,f.data.requiresAttunement,f.data.requiresOperating,null) ? f:null;
}
export function preferredEffect(candidate,prior) {
  const priority=candidate.data.priority??0,previous=prior?.data.priority??0;
  return !prior || priority>previous || (priority===previous &&
    ((candidate.data.amount??0)>(prior.data.amount??0) || ((candidate.data.amount??0)===(prior.data.amount??0) && candidate.id<prior.id)));
}
export function exhaustionOf(data,active=[]) {
  const level=Object.hasOwn(data,'exhaustionLevel')?data.exhaustionLevel:(data.conditions??[]).includes('exhaustion')?null:0;
  const rulesProfile=data.rulesProfile==='custom'?(data.customExhaustionRule??'manual'):data.rulesProfile;
  const immune=active.some(e=>e.data.target==='condition_immunity' && e.data.key==='exhaustion');
  const effectiveLevel=immune?0:level;
  const status=effectiveLevel===0?(immune&&level!==0?'suppressed':'clear'):effectiveLevel===null||rulesProfile==='manual'?'adjudication_required':
    effectiveLevel===6?'fatal':'active';
  return {level,rulesProfile,effectiveLevel,status,source:data.exhaustionSource??null,adjudicationSource:data.exhaustionAdjudicationSource??null};
}
export function requirePlayableExhaustion(exhaustion) {
  must(exhaustion.status!=='fatal','FATAL_EXHAUSTION_REQUIRES_RESOLUTION');
  must(exhaustion.status!=='adjudication_required','EXHAUSTION_ADJUDICATION_REQUIRED');
}
function ruleFormIds(form) {
  return form?[form.id,...(form.data.inheritedRuleFormIds??[])]:[];
}
export function matchesForm(rule,ids) {
  return (!rule.formId||ids.includes(rule.formId)) && !rule.excludedForms?.some(id=>ids.includes(id));
}
export async function activeEffects(ctx,a,form,contextTags=[],chargedEffectIds=[]) {
  const winners=new Map(), suppressed=[],optional=[];
  for (const id of a.data.effectIds) {
    const row=await ctx.get('effect',id);
    if (!row || row.actorId!==a.id || row.life!=='active') continue;
    const d=row.data;
    const eligible=d.enabled && matchesForm(d,ruleFormIds(form)) &&
      (!d.contextTag || contextTags.includes(d.contextTag)) &&
      (d.expiresAt==null || (a.data.gameSeconds!=null && a.data.gameSeconds<d.expiresAt)) &&
      await gearEligible(ctx,d.sourceItemId,a.id,d.requiresAttunement,d.requiresOperating,form,d.requiresWorn||d.wornResistanceUpgrade);
    if (!eligible) {suppressed.push(id); continue;}
    if(d.charge){
      const canInspect=ctx.grant.scopes.includes('resources.read') && (ctx.grant.actors.includes('*')||ctx.grant.actors.includes(a.id));
      const pool=canInspect?await ctx.get('resource_pool',d.charge.poolId):null;
      optional.push({id,title:row.title,target:d.target,key:d.key,damageRoute:d.damageRoute??'attack',damageType:d.damageType??null,
        damageComponentId:d.damageComponentId??null,operation:d.operation,value:d.amount,charge:d.charge,
        available:pool?.life==='active'&&pool.actorId===a.id?availableUses(pool.data):null});
      if(!chargedEffectIds.includes(id))continue;
    }
    // A shared anti-stacking key affects only the same mechanical target and subject.
    const qualifier=d.target==='damage'?[d.damageRoute??'attack',d.damageComponentId??null,d.damageType??null,!!d.eachMatchingComponent]:
      d.target==='roll_edge'||d.target==='damage_defense'?d.operation:null;
    const key=JSON.stringify([d.stackingKey??id,d.target,d.key,qualifier]);
    const prior=winners.get(key);
    if (preferredEffect(row,prior)) {
      if(prior) suppressed.push(prior.id);
      winners.set(key,row);
    } else suppressed.push(id);
  }
  const active=[...winners.values()];
  if(a.data.rulesProfile!=='custom'){
    const tags=new Set(contextTags),conditions=(a.data.conditions??[]).map(k=>k.toLowerCase());
    const immunity=new Set(active.filter(r=>r.data.target==='condition_immunity').map(r=>r.data.key));
    const applies=c=>conditions.includes(c)&&!immunity.has(c);
    function add(c,target,key,operation,amount=null){
      active.push({id:'condition:'+c+':'+target+':'+key,data:{target,key,operation,amount,priority:10000,source:`${a.data.rulesProfile} ${c}`}});
    }
    const genericChecks=['ability:all','skill:all','initiative:initiative','attack:all','spell_attack:all'];
    if(applies('poisoned') && !applies('petrified'))for(const key of genericChecks)add('poisoned','roll_edge',key,'disadvantage');
    if(applies('frightened') && tags.has('fear-source-visible'))for(const key of genericChecks)add('frightened','roll_edge',key,'disadvantage');
    for(const c of ['blinded','prone','restrained'])if(applies(c))for(const key of ['attack:all','spell_attack:all'])add(c,'roll_edge',key,'disadvantage');
    if(applies('restrained'))add('restrained','roll_edge','save:DEX','disadvantage');
    if(applies('invisible') && tags.has('target-cannot-see-you'))for(const key of ['attack:all','spell_attack:all'])add('invisible','roll_edge',key,'advantage');
    if(a.data.rulesProfile==='revised_2024'){
      if(applies('invisible'))add('invisible','roll_edge','initiative:initiative','advantage');
      for(const c of ['incapacitated','paralyzed','petrified','unconscious','stunned'])if(applies(c))add(c,'roll_edge','initiative:initiative','disadvantage');
    }
    for(const c of ['grappled','restrained','paralyzed','petrified','unconscious'])if(applies(c))add(c,'speed','all','maximum',0);
    if(applies('petrified'))for(const type of damageTypes)add('petrified','damage_defense',type,'resistance');
  }
  const exhaustion=exhaustionOf(a.data,active),level=exhaustion.effectiveLevel;
  if(level>0 && exhaustion.rulesProfile!=='manual'){
    const source=exhaustion.rulesProfile+' exhaustion level '+level;
    const add=(target,key,operation,amount=null)=>active.push({id:'condition:exhaustion:'+target+':'+key,data:{target,key,operation,amount,priority:10000,source}});
    if(exhaustion.rulesProfile==='revised_2024'){
      for(const target of ['ability_check','save','initiative','attack','spell_attack'])add(target,'all','add',-2*level);
      add('speed','all','add',-5*level);
    }else{
      for(const key of ['ability:all','skill:all','initiative:initiative',...(level>=3?['attack:all','spell_attack:all','save:all']:[])])add('roll_edge',key,'disadvantage');
      if(level>=5)add('speed','all','maximum',0);
    }
  }
  return {active,suppressed,optional,exhaustion};
}
export function numeric(base,active,target,key) {
  const applicable=new Map();
  for(const row of active){
    if(row.data.target!==target || row.data.key!==key && row.data.key!=='all')continue;
    const group=row.data.stackingKey??row.id;
    if(preferredEffect(row,applicable.get(group)))applicable.set(group,row);
  }
  const matching=[...applicable.values()];
  const sets=matching.filter(r=>r.data.operation==='set').sort((a,b)=>b.data.priority-a.data.priority||b.data.amount-a.data.amount||a.id.localeCompare(b.id));
  const applied=matching.filter(r=>r.data.operation==='add');
  const starting=sets.length ? sets[0].data.amount : base;
  let value=starting==null?null:starting+applied.reduce((sum,r)=>sum+r.data.amount,0);
  const bounds=matching.filter(r=>['minimum','maximum'].includes(r.data.operation)).sort((a,b)=>b.data.operation.localeCompare(a.data.operation));
  for(const r of bounds)if(value!=null)value=r.data.operation==='minimum'?Math.max(value,r.data.amount):Math.min(value,r.data.amount);
  return {value,base,sources:[...sets.slice(0,1),...applied,...bounds].map(r=>({id:r.id,source:r.data.source,operation:r.data.operation,value:r.data.amount}))};
}
export function rollPolicy(active,key,requested='normal') {
  const modes=active.filter(r=>r.data.target==='roll_edge' && (r.data.key===key||r.data.key===key.split(':')[0]+':all'||r.data.key==='all')).map(r=>r.data.operation);
  const high=requested==='advantage'||modes.includes('advantage')||modes.includes('keep_highest_three');
  const low=requested==='disadvantage'||modes.includes('disadvantage');
  if(high===low)return {mode:'normal',count:1};
  return {mode:high?'advantage':'disadvantage',count:high && modes.includes('keep_highest_three')?3:2};
}
export function healedHp(current,maximum,amount){
  return current+Math.min(Math.max(0,maximum-current),amount);
}
export async function sheet(ctx,id,contextTags=[],previewFormId=undefined,chargedEffectIds=[]) {
  const a=await actor(ctx,id), form=previewFormId===undefined?await activeForm(ctx,a):previewFormId?await owned(ctx,'form',previewFormId,id):null;
  const {active,suppressed,optional,exhaustion}=await activeEffects(ctx,a,form,contextTags,chargedEffectIds);
  const abilities={},checks={},saves={},skills={},passives={},skillDefinitions={};
  for(const stat of stats){
    const base=a.data.abilities[stat]??null;
    const effective=numeric(form && Object.hasOwn(form.data.abilityOverrides,stat)?form.data.abilityOverrides[stat]:base,active,'ability_score',stat);
    const mod=effective.value==null?null:Math.floor((effective.value-10)/2);
    abilities[stat]={base,effective:effective.value,modifier:mod,sources:effective.sources};
    checks[stat]=numeric(mod,active,'ability_check',stat);
    const trained=(a.data.saveProficiencies??[]).includes(stat);
    const pb=a.data.proficiency;
    saves[stat]=numeric(mod==null || (trained && pb==null)?null:mod+(trained?pb:0),active,'save',stat);
  }
  const keys=new Set([...Object.keys(standardSkills),...Object.keys(a.data.skills)]);
  for(const key of keys){
    const configured=Object.hasOwn(a.data.skills,key)?a.data.skills[key]:{};
    const stat=configured.ability??standardSkills[key], base=checks[stat].value, pb=a.data.proficiency;
    const rank=configured.rank??0;
    skillDefinitions[key]={name:configured.name??key,ability:stat,rank,standard:Object.hasOwn(standardSkills,key),pinned:(a.data.pinnedSkillIds??[]).includes(key)};
    const trained=base==null || (rank && pb==null)?null:base+(rank?rank*pb:0);
    skills[key]={...numeric(trained,active,'skill',key),name:configured.name??key.split('_').map(w=>w[0].toUpperCase()+w.slice(1)).join(' '),ability:stat,rank};
    const mode=rollPolicy(active,'skill:'+key).mode;
    const adjustment=mode==='advantage'?5:mode==='disadvantage'?-5:0;
    passives[key]={...numeric(skills[key].value==null?null:10+skills[key].value+adjustment,active,'passive',key),mode,adjustment};
  }
  const movement={...a.data.speeds,...form?.data.speeds};
  const senses={...a.data.senses,...form?.data.senses};
  for(const [target,output] of [['speed',movement],['sense',senses]]){
    const modes=new Set([...Object.keys(output),...active.filter(r=>r.data.target===target&&r.data.key!=='all').map(r=>r.data.key)]);
    for(const k of modes){const value=numeric(output[k]??null,active,target,k).value;output[k]=value==null?null:Math.max(0,value);}
  }
  if(form?.data.grounded){movement.fly=0; movement.hover=0;}
  const legacyExhaustion=exhaustion.rulesProfile==='legacy_2014'?(exhaustion.effectiveLevel??0):0;
  if(legacyExhaustion>=2)for(const k of Object.keys(movement))if(movement[k]!=null)movement[k]=Math.floor(movement[k]/2);
  if(['disabled','dormant'].includes(a.data.activityState))for(const k of Object.keys(movement))movement[k]=0;
  const ac=numeric(form?.data.baseAc??a.data.baseAc,active,'ac','all');
  const baselineCon=a.data.baselineConstitutionModifier??null;
  const conDelta=baselineCon===null?0:abilities.CON.modifier===null?null:a.data.level*(abilities.CON.modifier-baselineCon);
  const hpBase=a.data.maxHp==null||conDelta===null?null:a.data.maxHp+conDelta;
  const hpMaximum=numeric(hpBase,active,'max_hp','all');
  if(hpMaximum.value!==null)hpMaximum.value=Math.max(0,hpMaximum.value);
  if(legacyExhaustion>=4){
    if(hpMaximum.value!==null)hpMaximum.value=Math.floor(hpMaximum.value/2);
    hpMaximum.sources.push({id:'condition:exhaustion:max_hp',source:'Legacy exhaustion level '+legacyExhaustion,operation:'divide',value:2});
  }
  const subjects=[...stats.flatMap(k=>['ability:'+k,'save:'+k]),...Array.from(keys,k=>'skill:'+k),'initiative:initiative'];
  const rollRestrictions={};
  if(a.data.rulesProfile!=='custom'){
    const immune=new Set(active.filter(r=>r.data.target==='condition_immunity').map(r=>r.data.key));
    const conditions=(a.data.conditions??[]).map(k=>k.toLowerCase()).filter(c=>!immune.has(c));
    if(conditions.some(c=>['paralyzed','petrified','unconscious','stunned'].includes(c)))for(const key of ['save:STR','save:DEX'])rollRestrictions[key]='AUTOMATIC_SAVE_FAILURE';
    if(conditions.includes('blinded')&&contextTags.includes('requires-sight')||conditions.includes('deafened')&&contextTags.includes('requires-hearing'))
      for(const key of subjects.filter(k=>k.startsWith('ability:')||k.startsWith('skill:')))rollRestrictions[key]='REQUIRED_SENSE_UNAVAILABLE';
  }
  if(['fatal','adjudication_required'].includes(exhaustion.status)){
    const reason=exhaustion.status==='fatal'?'FATAL_EXHAUSTION_REQUIRES_RESOLUTION':'EXHAUSTION_ADJUDICATION_REQUIRED';
    for(const key of subjects)rollRestrictions[key]=reason;
  }
  const rollModes=Object.fromEntries(subjects.map(key=>[key,Object.fromEntries(['normal','advantage','disadvantage'].map(mode=>[mode,rollPolicy(active,key,mode)]))]));
  const communications=[],mobilityRules=[];
  for(const featureId of a.data.sensoryFeatureIds??[]){
    const feature=await ctx.get('feature',featureId),d=feature?.data;
    if(feature?.life!=='active'||feature.actorId!==id||!matchesForm(d,ruleFormIds(form))||
      !await gearEligible(ctx,d.sourceItemId,id,d.requiresAttunement,d.requiresOperating,form))continue;
    if(d.speech)communications.push({featureId,kind:'speech',...d.speech,unassigned:d.speech.capacity-d.speech.languages.length});
    if(d.telepathy)communications.push({featureId,kind:'telepathy',...d.telepathy});
    if(d.lipReading)communications.push({featureId,kind:'lip_reading',requiresVisibleMouth:true,requiresUnderstoodLanguage:true});
    if(d.mobility)mobilityRules.push({featureId,kind:d.mobility,eligible:movement.fly>0,requiresMovement:'fly'});
  }
  const defenses=active.filter(row=>row.data.target==='damage_defense');
  const defenseEntries=defenses.map(({id,title,data})=>{
    const independent=data.wornResistanceUpgrade && defenses.find(other=>other.data.key===data.key &&
      other.data.operation==='resistance' && other.data.sourceItemId!==data.sourceItemId && !other.data.wornResistanceUpgrade);
    return {type:data.key,kind:independent?'immunity':data.operation,source:id,title,provenance:data.source??null,
      sourceItemId:data.sourceItemId??null,formId:data.formId??null,requiresAttunement:data.requiresAttunement===true,
      requiresOperating:data.requiresOperating===true,contextTag:data.contextTag??null,
      ...(independent?{independentResistanceId:independent.id}:{})};
  });
  return {rollAids:await activeCastAids(ctx,a),actorId:id,level:a.data.level,rulesProfile:a.data.rulesProfile,classTracks:a.data.classTracks??null,activeDeploymentId:a.data.activeDeploymentId??null,communications,mobilityRules,exhaustion,activityState:a.data.activityState??null,formId:form?.id??null,ruleFormIds:ruleFormIds(form),size:form?.data.size??a.data.size??null,creatureType:form?.data.creatureType??a.data.creatureType??null,
    senses,abilities,checks,saves,skills,passives,skillDefinitions,ac,movement,
    initiative:numeric(abilities.DEX.modifier,active,'initiative','all'),proficiency:a.data.proficiency,
    currentHp:a.data.currentHp,maxHp:hpMaximum.value,temporaryHp:a.data.temporaryHp,
    hpMaximum:{...hpMaximum,baseMaxHp:a.data.maxHp,baselineConstitutionModifier:baselineCon,constitutionAdjustment:conDelta},
    activeEffects:active.map(r=>r.id),suppressedEffects:suppressed,optionalChargedEffects:optional,conditions:a.data.conditions,rollModes,rollRestrictions,contextTags:a.data.contextTags??[],
    defenses:defenseEntries,
    effects:active};
}
export function availableUses(pool){
  return pool.current==null?null:pool.current-(pool.effectHolds??[]).reduce((n,h)=>n+h.amount,0);
}
export async function spend(ctx,actorId,id,amount) {
  const p=await owned(ctx,'resource_pool',id,actorId);
  const available=availableUses(p.data);
  must(Number.isSafeInteger(available)&&available>=amount,'RESOURCE_EMPTY_OR_UNKNOWN');
  const after=p.data.current-amount;
  await save(ctx,p,{...p.data,current:after});
  return {poolId:id,before:p.data.current,after,amount};
}
// The opaque turn binding lets an actor-scoped roll retain its original interrupt window
// without including the encounter roster, hidden actor identities or initiative order.
export async function reactionRollWindow(ctx,a) {
  if(!a?.data.encounterId)return null;
  const encounter=await ctx.current(a.data.encounterId),d=encounter?.data;
  must(encounter?.kind==='encounter' && encounter.life==='active' && d.active && d.participants.includes(a.id),'ENCOUNTER_STATE_UNRESOLVED');
  // Active records from before turn tokens retain a stable binding until the next turn.
  return d.turnWindow??sha256(JSON.stringify([encounter.id,d.round,d.index]));
}
export async function checkTiming(ctx,a,timing) {
  if(a.data.exhaustionLevel!==0 && (Object.hasOwn(a.data,'exhaustionLevel')||(a.data.conditions??[]).includes('exhaustion')))
    requirePlayableExhaustion((await sheet(ctx,a.id)).exhaustion);
  if(!a.data.encounterId || timing==='other') return null;
  const encounter=await ctx.get('encounter',a.data.encounterId);
  must(encounter?.life==='active' && encounter.data.active && encounter.data.participants.includes(a.id),'ENCOUNTER_STATE_UNRESOLVED');
  const turn=encounter.data.economy[a.id];
  must(turn && (timing==='reaction'||encounter.data.participants[encounter.data.index]===a.id),'TURN_UNAVAILABLE');
  if(timing==='attack')must(turn.action && turn.attacks>0,'ACTION_UNAVAILABLE');
  else must(turn[timing]===true && (timing!=='action'||turn.attacks===a.data.attacksPerAction),'ACTION_UNAVAILABLE');
  return {encounter,turn};
}
export async function useTiming(ctx,a,timing) {
  const cost=await checkTiming(ctx,a,timing);
  if(!cost)return;
  const {encounter,turn}=cost,next={...turn};
  if(timing==='attack'){next.attacks--;next.action=next.attacks>0;}
  else {next[timing]=false;if(timing==='action')next.attacks=0;}
  await save(ctx,encounter,{...encounter.data,actedThisTurn:encounter.data.actedThisTurn===true||encounter.data.participants[encounter.data.index]===a.id,economy:{...encounter.data.economy,[a.id]:next}});
}
export async function addClock(ctx,a,seconds) {
  const now=a.data.gameSeconds;
  must(now!=null || seconds===0,'GAME_CLOCK_UNKNOWN');
  const next=now==null?null:now+seconds;
  must(next==null||Number.isSafeInteger(next)&&next<=100000000000,'GAME_CLOCK_OUT_OF_RANGE');
  const expired=a.data.formExpiresAt!=null && next!=null && next>=a.data.formExpiresAt;
  const concentrationExpired=a.data.concentrationCastId && a.data.concentrationExpiresAt!=null && next!=null && next>=a.data.concentrationExpiresAt;
  if(concentrationExpired)await ctx.unlink(a.id,'concentrates_on',a.data.concentrationCastId);
  const sources=(a.data.conditionSources??[]).filter(s=>s.expiresAt==null || next==null || s.expiresAt>next);
  const expiredKeys=new Set((a.data.conditionSources??[]).filter(s=>!sources.includes(s)).map(s=>s.key));
  const conditions=a.data.conditions.filter(key=>!expiredKeys.has(key) || sources.some(s=>s.key===key));
  return {...a.data,gameSeconds:next,conditions,conditionSources:sources,...(concentrationExpired?{concentrationCastId:null,concentrationExpiresAt:null}:{}),...(expired?{activeFormId:null,formExpiresAt:null}:{})};
}
