import { BridgeError } from './core.mjs';

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
export const actionCost = option('action','bonus_action','reaction','attack','other');
export const mode = option('normal','advantage','disadvantage');
export const origin = option('player_digital','player_physical','dm','delegated','app');
export const component = fields({
  id:identifier, name:named(120), type:option(...damageTypes,'healing'), dice:whole(0,100), sides:whole(2,1000),
  flat:whole(-1000,1000), critical:boolean, abilityBonus:option(...stats),
  variants:arrayOf(option(...damageTypes),13), extraDicePerLevel:whole(0,20)
}, ['id','name','type','dice','sides','flat']);
export const actorSchema = fields({
  actorId:identifier, name:named(160), role:option('player','companion','npc','creature'),
  rulesProfile:option('legacy_2014','revised_2024','custom'), level:whole(1,20),
  proficiency:nullable(whole(0,30)), maxHp:nullable(whole()), currentHp:nullable(whole()), temporaryHp:nullable(whole()),
  baseAc:nullable(whole(0,100)), abilities:abilityMap, speeds:movementMap, saveProficiencies:arrayOf(option(...stats),6),
  attacksPerAction:whole(1,12), attunementCapacity:nullable(whole(0,12)), gameSeconds:nullable(whole(0,100000000000)),
  source:named(300)
}, ['actorId','name','role','rulesProfile','level']);
export const resourceSchema = fields({
  actorId:identifier, poolId:identifier, name:named(160),
  type:option('ordinary','hit_die','spell_slot','integrity','lifetime','charge'), maximum:nullable(whole()),
  current:nullable(whole()), recovery:option('none','short_rest','long_rest','either_rest','dawn','deployment','edition_hit_dice'),
  recoveryAmount:nullable(whole(1)), dieSides:nullable(whole(2,1000)), slotLevel:nullable(whole(1,9)), source:named(300)
}, ['actorId','poolId','name','type','maximum','current','recovery','source']);
export const formSchema = fields({
  actorId:identifier, formId:identifier, name:named(160), source:named(300), description:named(2000),
  size:option('tiny','small','medium','large','huge','gargantuan'), creatureType:named(60),
  sourceItemId:identifier, requiresAttunement:boolean, requiresOperating:boolean, abilityOverrides:abilityMap, speeds:movementMap,
  grounded:boolean, baseAc:nullable(whole(0,100)), activationSeconds:whole(0,86400), durationSeconds:nullable(whole(1,31536000)),
  timing:actionCost, deploymentPoolId:identifier, integrityPoolId:identifier, resetPoolId:identifier
}, ['actorId','formId','name','source','timing','activationSeconds']);
export const effectSchema = fields({
  actorId:identifier, effectId:identifier, name:named(160), source:named(300),
  target:option('ability_score','ability_check','skill','save','passive','initiative','ac','attack','spell_attack','spell_dc','damage_defense','condition_immunity','roll_edge'),
  key:identifier, operation:option('add','set','resistance','immunity','vulnerability','advantage','disadvantage','keep_highest_three'),
  amount:whole(-1000,1000), priority:whole(-100,100), stackingKey:identifier,
  sourceItemId:identifier, requiresAttunement:boolean, requiresOperating:boolean, formId:identifier, contextTag:identifier,
  expiresAt:nullable(whole(0,100000000000)), enabled:boolean
}, ['actorId','effectId','name','source','target','key','operation']);
export const costSchema = fields({poolId:identifier,amount:whole(1)},['poolId','amount']);
export const restSchema = fields({
  actorId:identifier, kind:option('short_rest','long_rest'), elapsedSeconds:whole(0,31536000), confirmed:{const:true},
  hitDice:arrayOf(fields({poolId:identifier,faces:arrayOf(whole(1,1000),100),origin},['poolId','faces','origin']),32),
  restoreHitDice:arrayOf(costSchema,32)
}, ['actorId','kind','elapsedSeconds','confirmed','hitDice']);
export const checkSpec = fields({
  kind:option('ability','save','skill','initiative','custom'), key:identifier, name:named(120), mode,
  contextTags:arrayOf(identifier,12), dice:whole(1,100), sides:whole(2,1000), flat:whole(-10000,10000)
}, ['kind','key','mode']);
export const evidence = fields({origin,faces:arrayOf(whole(1,1000),200)},['origin','faces']);
export const attackSchema = fields({
  actorId:identifier, attackId:identifier, name:named(160), source:named(300), sourceItemId:identifier,
  requiresAttunement:boolean, requiresOperating:boolean, formId:identifier, excludedForms:arrayOf(identifier,16), classification:named(60),
  delivery:option('melee','ranged','area','other'), resolution:option('attack','save','automatic','manual'),
  timing:actionCost, ability:option(...stats), proficient:boolean, hitBonus:whole(-200,200),
  fixedHit:whole(-200,200), saveAbility:option(...stats), saveDc:whole(1,100),saveSuccess:option('none','half','full'),
  reach:whole(0,10000), range:whole(0,10000), longRange:whole(0,10000), area:named(160),
  components:arrayOf(component,24), cost:costSchema, description:named(2000)
},['actorId','attackId','name','source','timing','delivery','resolution','components']);
export const spellSchema = fields({
  actorId:identifier, spellId:identifier, name:named(160), source:named(300), baseLevel:whole(0,9), concentration:boolean,
  timing:actionCost, ritual:boolean, resolution:option('attack','save','automatic','manual'), saveAbility:option(...stats),saveSuccess:option('none','half','full'),
  description:named(2400), range:named(120), duration:named(120), components:arrayOf(component,16)
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
export async function gearEligible(ctx,itemId,actorId,attunement=false,requiresOperating=false) {
  if (!itemId) return !attunement && !requiresOperating;
  const item=await ctx.get('item_instance',itemId);
  const data=item?.data;
  return !!data && item.life==='active' && data.custodianId===actorId && data.partyAccess===true &&
    data.holding==='carried' && ['held','worn'].includes(data.equipment) && (!requiresOperating || data.operating===true) &&
    data.quantity===1 && (!attunement || data.attuned===true);
}
export async function activeForm(ctx,a) {
  const d=a.data;
  if (!d.activeFormId) return null;
  const f=await ctx.get('form',d.activeFormId);
  if (!f || f.actorId!==a.id || f.life!=='active') return null;
  if (d.formExpiresAt!=null && (d.gameSeconds==null || d.gameSeconds>=d.formExpiresAt)) return null;
  return await gearEligible(ctx,f.data.sourceItemId,a.id,f.data.requiresAttunement,f.data.requiresOperating) ? f:null;
}
export async function activeEffects(ctx,a,form,contextTags=[]) {
  const winners=new Map(), suppressed=[];
  for (const id of a.data.effectIds) {
    const row=await ctx.get('effect',id);
    if (!row || row.actorId!==a.id || row.life!=='active') continue;
    const d=row.data;
    const eligible=d.enabled && (!d.formId || d.formId===form?.id) &&
      (!d.contextTag || contextTags.includes(d.contextTag)) &&
      (d.expiresAt==null || (a.data.gameSeconds!=null && a.data.gameSeconds<d.expiresAt)) &&
      await gearEligible(ctx,d.sourceItemId,a.id,d.requiresAttunement,d.requiresOperating);
    if (!eligible) {suppressed.push(id); continue;}
    // A shared anti-stacking key affects only the same mechanical target and subject.
    const key=JSON.stringify([d.stackingKey??id,d.target,d.key,d.target==='roll_edge'||d.target==='damage_defense'?d.operation:null]);
    const prior=winners.get(key);
    if (!prior || d.priority>prior.data.priority || (d.priority===prior.data.priority &&
        ((d.amount??0)>(prior.data.amount??0) || ((d.amount??0)===(prior.data.amount??0) && row.id<prior.id)))) {
      if(prior) suppressed.push(prior.id);
      winners.set(key,row);
    } else suppressed.push(id);
  }
  return {active:[...winners.values()],suppressed};
}
export function numeric(base,active,target,key) {
  const matching=active.filter(r=>r.data.target===target && (r.data.key===key||r.data.key==='all'));
  const sets=matching.filter(r=>r.data.operation==='set').sort((a,b)=>b.data.priority-a.data.priority||b.data.amount-a.data.amount||a.id.localeCompare(b.id));
  const applied=matching.filter(r=>r.data.operation==='add');
  const starting=sets.length ? sets[0].data.amount : base;
  return {value:starting==null?null:starting+applied.reduce((s,r)=>s+r.data.amount,0),
    base,sources:[...sets.slice(0,1),...applied].map(r=>({id:r.id,source:r.data.source,operation:r.data.operation,value:r.data.amount}))};
}
export function rollPolicy(active,key,requested='normal') {
  const modes=active.filter(r=>r.data.target==='roll_edge' && (r.data.key===key||r.data.key==='all')).map(r=>r.data.operation);
  const high=requested==='advantage'||modes.includes('advantage')||modes.includes('keep_highest_three');
  const low=requested==='disadvantage'||modes.includes('disadvantage');
  if(high===low)return {mode:'normal',count:1};
  return {mode:high?'advantage':'disadvantage',count:high && modes.includes('keep_highest_three')?3:2};
}
export async function sheet(ctx,id,contextTags=[]) {
  const a=await actor(ctx,id), form=await activeForm(ctx,a);
  const {active,suppressed}=await activeEffects(ctx,a,form,contextTags);
  const abilities={},checks={},saves={},skills={},passives={};
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
    const trained=base==null || (rank && pb==null)?null:base+(rank?rank*pb:0);
    skills[key]=numeric(trained,active,'skill',key);
    const mode=rollPolicy(active,'skill:'+key).mode;
    const adjustment=mode==='advantage'?5:mode==='disadvantage'?-5:0;
    passives[key]={...numeric(skills[key].value==null?null:10+skills[key].value+adjustment,active,'passive',key),mode,adjustment};
  }
  const movement={...a.data.speeds,...form?.data.speeds};
  if(form?.data.grounded){movement.fly=0; movement.hover=0;}
  const ac=numeric(form?.data.baseAc??a.data.baseAc,active,'ac','all');
  return {actorId:id,formId:form?.id??null,abilities,checks,saves,skills,passives,ac,movement,
    initiative:numeric(abilities.DEX.modifier,active,'initiative','all'),proficiency:a.data.proficiency,
    currentHp:a.data.currentHp,maxHp:a.data.maxHp,temporaryHp:a.data.temporaryHp,
    activeEffects:active.map(r=>r.id),suppressedEffects:suppressed,conditions:a.data.conditions,
    defenses:active.filter(r=>r.data.target==='damage_defense').map(r=>({type:r.data.key,kind:r.data.operation,source:r.id})),
    effects:active};
}
export async function spend(ctx,actorId,id,amount) {
  const p=await owned(ctx,'resource_pool',id,actorId);
  must(Number.isSafeInteger(p.data.current)&&p.data.current>=amount,'RESOURCE_EMPTY_OR_UNKNOWN');
  const after=p.data.current-amount;
  await save(ctx,p,{...p.data,current:after});
  return {poolId:id,before:p.data.current,after,amount};
}
export async function useTiming(ctx,a,timing) {
  if(!a.data.encounterId || timing==='other') return;
  const e=await ctx.get('encounter',a.data.encounterId);
  must(e && e.life==='active' && e.data.active && e.data.participants.includes(a.id),'ENCOUNTER_STATE_UNRESOLVED');
  const t=e.data.economy[a.id];
  must(t && (timing==='reaction'||e.data.participants[e.data.index]===a.id),'TURN_UNAVAILABLE');
  const next={...t};
  if(timing==='attack'){
    must(t.action && t.attacks>0,'ACTION_UNAVAILABLE');
    next.attacks--; next.action=next.attacks>0;
  }else{
    must(t[timing]===true && (timing!=='action'||t.attacks===a.data.attacksPerAction),'ACTION_UNAVAILABLE');
    next[timing]=false;
    if(timing==='action')next.attacks=0;
  }
  await save(ctx,e,{...e.data,economy:{...e.data.economy,[a.id]:next}});
}
export async function addClock(ctx,a,seconds) {
  const now=a.data.gameSeconds;
  must(now!=null || seconds===0,'GAME_CLOCK_UNKNOWN');
  const next=now==null?null:now+seconds;
  must(next==null||Number.isSafeInteger(next)&&next<=100000000000,'GAME_CLOCK_OUT_OF_RANGE');
  const expired=a.data.formExpiresAt!=null && next!=null && next>=a.data.formExpiresAt;
  const sources=(a.data.conditionSources??[]).filter(s=>s.expiresAt==null || next==null || s.expiresAt>next);
  const expiredKeys=new Set((a.data.conditionSources??[]).filter(s=>!sources.includes(s)).map(s=>s.key));
  const conditions=a.data.conditions.filter(key=>!expiredKeys.has(key) || sources.some(s=>s.key===key));
  return {...a.data,gameSeconds:next,conditions,conditionSources:sources,...(expired?{activeFormId:null,formExpiresAt:null}:{})};
}
