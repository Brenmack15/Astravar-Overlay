import { checkDiceOwner, stats, identifier, boolean, named, whole, arrayOf, option, fields, mode, evidence, attackSchema, must, op, actor, owned, insert, save, sheet, transferableSource, gearEligible, numeric, rollPolicy, spend, useTiming } from './cloud-mechanics-core.mjs';
import { observed } from './cloud-mechanics-rolls.mjs';

export const damageEvidence=arrayOf(fields({id:identifier,evidence},['id','evidence']),24);
export function componentRules(list,allowScaling=false){
  must(new Set(list.map(c=>c.id)).size===list.length,'DUPLICATE_DAMAGE_COMPONENT');
  for(const c of list){
    must(c.dice>0||c.flat!==0||c.abilityBonus,'EMPTY_DAMAGE_COMPONENT');
    must(!c.variants || c.variants.length>0 && c.variants.includes(c.type) && new Set(c.variants).size===c.variants.length,'INVALID_COMPONENT_VARIANTS');
    must(allowScaling||!c.extraDicePerLevel,'UNSUPPORTED_ATTACK_SCALING');
    must(allowScaling||c.type!=='healing','ATTACK_COMPONENT_TYPE_INVALID');
  }
}
export function snapshotComponents(components,p,chosen={},levels=0){
  return components.map(c=>{
    const bonus=c.abilityBonus?p.abilities[c.abilityBonus].modifier:0;
    must(bonus!=null,'DAMAGE_ABILITY_UNKNOWN');
    return {id:c.id,name:c.name,type:chosen[c.id]??c.type,dice:c.dice+(c.extraDicePerLevel??0)*levels,sides:c.sides,
      flat:c.flat+bonus,critical:c.critical!==false};
  });
}
export function rollComponents(components,recorded,critical){
  const entries=new Map(recorded.map(e=>[e.id,e.evidence]));
  must(entries.size===recorded.length && entries.size===components.length,'DAMAGE_COMPONENT_EVIDENCE_REQUIRED');
  return components.map(c=>{
    const e=entries.get(c.id);must(e,'COMPONENT_MISSING');
    const dice=c.dice*(critical&&c.critical?2:1);
    return {...observed(c.name,dice,c.sides,c.flat,'sum',e),id:c.id,type:c.type};
  });
}
async function validateAttack(ctx,x){
  const a=await actor(ctx,x.actorId);
  componentRules(x.components);
  must((!x.requiresAttunement&&!x.requiresOperating) || x.sourceItemId,'ATTACK_ITEM_REQUIRED');
  must(x.resolution!=='attack'||x.fixedHit!=null||x.ability,'HIT_FORMULA_REQUIRED');
  must(x.resolution!=='save'||x.saveAbility && ((x.saveDc!=null)!==!!x.dcAbility),'SAVE_FORMULA_REQUIRED');
  must(x.dcAbility || (x.dcBase==null && x.dcProficient==null && x.dcBonus==null),'SAVE_DC_ABILITY_REQUIRED');
  must(x.resolution==='save' || (x.dcAbility==null && x.saveDc==null),'DC_ON_NON_SAVE');
  must(x.resolution==='attack'||(x.fixedHit==null&&x.hitBonus==null),'HIT_ON_NON_ATTACK');
  must(x.longRange==null||x.range!=null&&x.longRange>=x.range,'INVALID_LONG_RANGE');
  if(x.sourceItemId)must((await ctx.get('item_instance',x.sourceItemId))?.life==='active','ATTACK_SOURCE_MISSING');
  must(!x.formId || !x.excludedForms?.includes(x.formId),'INVALID_FORM_REQUIREMENT');
  must(new Set(x.excludedForms??[]).size===(x.excludedForms?.length??0),'DUPLICATE_EXCLUDED_FORM');
  const riders=x.riders??[];
  must(new Set(riders.map(r=>r.id)).size===riders.length,'DUPLICATE_RIDER');
  must(riders.every(r=>!r.componentId || x.components.some(c=>c.id===r.componentId)),'RIDER_COMPONENT_MISSING');
  must(!riders.length || x.resolution==='manual','RIDER_REQUIRES_REVIEWED_ADJUDICATION');
  await transferableSource(ctx,x);
  if(x.formId)await owned(ctx,'form',x.formId,a.id);
  for(const id of x.excludedForms??[])await owned(ctx,'form',id,a.id);
  if(x.cost)await owned(ctx,'resource_pool',x.cost.poolId,a.id);
}

function attackLinks(config) {
  return [
    ['uses_pool',config.cost?.poolId],
    ['requires_item',config.sourceItemId],
    ['requires_form',config.formId],
    ...(config.excludedForms??[]).map(id=>['excludes_form',id])
  ].filter(([,id])=>id);
}

async function writeAttack(ctx,x,editing=false){
  const {actorId,attackId,name,expectedAttackRevision,discardUnavailableSelections,...config}=x;
  const old=editing?await owned(ctx,'attack',attackId,actorId):null;
  if(old)must(old.revision===expectedAttackRevision,'ATTACK_REVISION_CHANGED');
  await validateAttack(ctx,x);
  const oldSelections=old?.data.selections??[];
  const selections=oldSelections.filter(s=>config.components.find(c=>c.id===s.componentId)?.variants?.includes(s.type));
  must(selections.length===oldSelections.length||discardUnavailableSelections===true,'REVIEW_VARIANT_REMOVAL');
  const data={...config,selections};
  const record=old
    ? await ctx.put({...old,title:name,data})
    : await insert(ctx,'attack',attackId,actorId,name,data);
  const prior=attackLinks(old?.data??{}), next=attackLinks(config);
  const has=(links,relation,id)=>links.some(([r,t])=>r===relation&&t===id);
  for(const [relation,id] of prior)if(!has(next,relation,id))await ctx.unlink(attackId,relation,id);
  for(const [relation,id] of next)if(!has(prior,relation,id))await ctx.link(attackId,relation,id);
  return {attackId,attackRevision:record.revision,removedSelections:oldSelections.filter(s=>!selections.includes(s))};
}
async function loadAttack(ctx,x){
  const a=await actor(ctx,x.actorId),attack=await owned(ctx,'attack',x.attackId,a.id),p=await sheet(ctx,a.id);
  const d=attack.data;
  must(!p.conditions.some(c=>['incapacitated','unconscious','stunned','petrified','paralyzed'].includes(c)),'ACTOR_CANNOT_ATTACK');
  must((!d.formId||d.formId===p.formId)&&!d.excludedForms?.includes(p.formId),'ATTACK_FORM_INELIGIBLE');
  must(await gearEligible(ctx,d.sourceItemId,a.id,d.requiresAttunement,d.requiresOperating),'ATTACK_SOURCE_INELIGIBLE');
  const chosen=Object.fromEntries(d.selections.filter(s=>(s.actorId??attack.actorId)===a.id&&s.formId===p.formId).map(s=>[s.componentId,s.type]));
  return {a,attack,p,components:snapshotComponents(d.components,p,chosen)};
}
async function chooseVariant(ctx,x){
  const {attack,p}=await loadAttack(ctx,x);
  const component=attack.data.components.find(c=>c.id===x.componentId);
  must(component?.variants?.includes(x.type),'DAMAGE_VARIANT_UNAVAILABLE');
  const selections=attack.data.selections.filter(s=>(s.actorId??attack.actorId)!==attack.actorId||s.formId!==p.formId||s.componentId!==x.componentId);
  selections.push({actorId:attack.actorId,formId:p.formId,componentId:x.componentId,type:x.type});
  await save(ctx,attack,{...attack.data,selections});
  return {attackId:attack.id,formId:p.formId,componentId:x.componentId,type:x.type};
}
export function attackModifier(attack,p) {
  const d=attack.data;
  const mod=d.ability?p.abilities[d.ability].modifier:null;
  must(d.fixedHit!=null || mod!=null && (!d.proficient || p.proficiency!=null),'ATTACK_MODIFIER_UNKNOWN');
  const base=d.fixedHit??((mod??0)+(d.proficient?p.proficiency:0)+(d.hitBonus??0));
  return numeric(base,p.effects,'attack',attack.id).value;
}

export function attackSaveDc(d,p) {
  if(d.saveDc!=null) return d.saveDc;
  const modifier=p.abilities[d.dcAbility]?.modifier;
  must(modifier!=null && (!d.dcProficient || p.proficiency!=null),'SAVE_DC_UNKNOWN');
  return (d.dcBase??8)+modifier+(d.dcProficient?p.proficiency:0)+(d.dcBonus??0);
}

async function standaloneDamage(ctx,x) {
  ctx.require('combat',x.actorId);
  const {a,attack,p,components}=await loadAttack(ctx,x);
  for(const part of x.damage)checkDiceOwner(ctx,a,'damage',part.evidence.origin);
  if(attack.data.cost) {
    const cost=attack.data.cost,pool=await owned(ctx,'resource_pool',cost.poolId,a.id);
    must(pool.data.current!=null && pool.data.current>=cost.amount,'RESOURCE_EMPTY_OR_UNKNOWN');
  }
  must(attack.data.resolution!=='manual','MANUAL_ADJUDICATION_REQUIRED');
  const results=rollComponents(components,x.damage,false);
  const result={kind:'attack_damage',status:'resolved',actorId:x.actorId,formId:p.formId,attackId:attack.id,
    components:results,text:results.map(r=>r.text).join('\n'),appliedDamage:null,commandId:ctx.commandId,recordedAt:ctx.now};
  await insert(ctx,'roll',x.rollId,x.actorId,attack.title+' damage',result);
  await ctx.link(x.rollId,'source_attack',attack.id);
  return result;
}

async function resolveAttack(ctx,x){
  const {a,attack,p,components}=await loadAttack(ctx,x),d=attack.data;
  must(d.resolution!=='manual','MANUAL_ADJUDICATION_REQUIRED');
  const targets=x.targets??[];
  must(new Set(targets).size===targets.length && (d.resolution!=='attack'||targets.length<=1),'ATTACK_TARGETS_INVALID');
  for(const id of targets)await actor(ctx,id);
  if(x.hit)checkDiceOwner(ctx,a,'attack',x.hit.origin);
  for(const part of x.damage??[])checkDiceOwner(ctx,a,'damage',part.evidence.origin);
  let hit=null,critical=false,substitution=null;
  if(d.resolution==='attack'){
    const total=attackModifier(attack,p);
    if(x.substitutionId){
      must(!x.hit && x.mode==='normal','SUBSTITUTION_REPLACES_DICE');
      const rule=await owned(ctx,'action_definition',x.substitutionId,a.id),f=rule.data;
      must(f.type==='fixed_attack_die' && f.cost && (!f.formId||f.formId===p.formId) &&
        await gearEligible(ctx,f.sourceItemId,a.id,f.requiresAttunement,f.requiresOperating),'DIE_SUBSTITUTION_INELIGIBLE');
      const cost=await spend(ctx,a.id,f.cost.poolId,f.cost.amount);
      hit={name:attack.title,status:'resolved',origin:'source_substitution',faces:[],substitutedValue:f.fixedDieValue,modifier:total,
        total:f.fixedDieValue+total,natural:null,mode:'fixed',text:`${attack.title}: (${f.fixedDieValue} fixed) ${total<0?'−':'+'} ${Math.abs(total)} = ${f.fixedDieValue+total}`};
      substitution={ruleId:rule.id,sourceItemId:f.sourceItemId,cost};
    }else{
      must(x.hit,'ATTACK_DICE_REQUIRED');
      const policy=rollPolicy(p.effects,'attack:'+attack.id,x.mode);
      hit=observed(attack.title,policy.count,20,total,policy.mode==='normal'?'sum':policy.mode==='advantage'?'highest':'lowest',x.hit,true);
      critical=hit.natural===20;
    }
  }else must(!x.hit && !x.substitutionId && x.mode==='normal','UNEXPECTED_ATTACK_ROLL');
  must(x.includeDamage || x.damage===undefined,'UNREQUESTED_DAMAGE');
  const damage=x.includeDamage?rollComponents(components,x.damage??[],critical):null;
  if(x.actionUseId){
    const use=await owned(ctx,'action_use',x.actionUseId,a.id),u=use.data;
    const enc=a.data.encounterId?await ctx.get('encounter',a.data.encounterId):null;
    must(u.type==='multiattack' && a.data.activeMultiattackId===use.id && u.formId===p.formId && u.encounterId===(enc?.id??null) &&
      (enc?enc.data.active && enc.data.participants[enc.data.index]===a.id && u.turn[0]===enc.data.round && u.turn[1]===enc.data.index:
        a.data.gameSeconds!=null && a.data.gameSeconds>=u.startedAt && a.data.gameSeconds<u.startedAt+6),'MULTIATTACK_WINDOW_CLOSED');
    const remaining=[...u.remainingAttacks],index=remaining.indexOf(attack.id);
    must(index>=0,'MULTIATTACK_ATTACK_UNAVAILABLE');remaining.splice(index,1);
    await save(ctx,use,{...u,remainingAttacks:remaining,attemptIds:[...u.attemptIds,x.attemptId]});
    if(remaining.length===0)await save(ctx,a,{...a.data,activeMultiattackId:null});
  }else await useTiming(ctx,a,d.timing);
  const costs=d.cost?[await spend(ctx,a.id,d.cost.poolId,d.cost.amount)]:[];
  if(substitution)costs.push(substitution.cost);
  const result={actorId:a.id,formId:p.formId,attackId:attack.id,substitution,sourceItemId:d.sourceItemId??null,resolution:d.resolution,
    sourceRevision:attack.revision,hit,critical,components,damage,costs,timing:x.actionUseId?'part_of_multiattack':d.timing,actionUseId:x.actionUseId??null,appliedDamage:null,targets,targetResults:[],
    save:d.resolution==='save'?{ability:d.saveAbility,dc:attackSaveDc(d,p),success:d.saveSuccess??null}:null,outcome:hit?.natural===1?'miss':'pending',
    commandId:ctx.commandId,recordedAt:ctx.now};
  await insert(ctx,'attack_attempt',x.attemptId,a.id,attack.title,result);
  await ctx.link(x.attemptId,'used_attack',attack.id);
  if(substitution)await ctx.link(x.attemptId,'used_substitution',substitution.ruleId);
  if(x.actionUseId)await ctx.link(x.attemptId,'part_of_multiattack',x.actionUseId);
  for(const id of targets)await ctx.link(x.attemptId,'declared_target',id);
  return {attemptId:x.attemptId,...result};
}
function freshTurn(a){return {action:true,bonus_action:true,reaction:true,attacks:a.data.attacksPerAction};}
async function startEncounter(ctx,x){
  must(x.participants.length>0 && new Set(x.participants.map(p=>p.actorId)).size===x.participants.length,'INVALID_PARTICIPANTS');
  const list=[];
  for(const entry of x.participants){
    const a=await actor(ctx,entry.actorId),r=await owned(ctx,'roll',entry.initiativeRollId,a.id);
    must(!a.data.encounterId && r.data.kind==='initiative' && r.data.status==='resolved','INITIATIVE_UNAVAILABLE');
    list.push({a,rollId:r.id,initiative:r.data.total});
  }
  list.sort((a,b)=>b.initiative-a.initiative);
  const data={active:true,participants:list.map(p=>p.a.id),initiative:list.map(p=>({actorId:p.a.id,total:p.initiative,rollId:p.rollId})),
    economy:Object.fromEntries(list.map(p=>[p.a.id,freshTurn(p.a)])),index:0,round:1};
  // Group encounter history is continuity-wide; a partial actor grant cannot read it.
  await ctx.require('combat',null);
  must(!await ctx.get('encounter',x.encounterId),'ENCOUNTER_ID_EXISTS');
  await ctx.put({id:x.encounterId,kind:'encounter',actorId:null,title:x.name,data});
  for(const p of list){
    await save(ctx,p.a,{...p.a.data,encounterId:x.encounterId});
    await ctx.link(x.encounterId,'participant',p.a.id);await ctx.link(x.encounterId,'initiative_roll',p.rollId);
  }
  return {encounterId:x.encounterId,round:1,currentActorId:list[0].a.id};
}
async function progress(ctx,x,end){
  const e=await ctx.get('encounter',x.encounterId);
  must(e?.life==='active'&&e.data.active && e.data.participants[e.data.index]===x.currentActorId,'ENCOUNTER_CHANGED');
  const d=e.data;
  if(end){
    for(const id of d.participants){
      const a=await actor(ctx,id);must(a.data.encounterId===e.id,'ENCOUNTER_STATE_UNRESOLVED');
      await save(ctx,a,{...a.data,encounterId:null});
    }
    await save(ctx,e,{...d,active:false,outcome:x.outcome});return {encounterId:e.id,active:false,outcome:x.outcome};
  }
  const index=(d.index+1)%d.participants.length,round=d.round+(index===0?1:0),a=await actor(ctx,d.participants[index]);
  await save(ctx,e,{...d,index,round,slotSpentActorIds:[],economy:{...d.economy,[a.id]:freshTurn(a)}});
  return {encounterId:e.id,round,currentActorId:a.id};
}
export const combatOperations=Object.freeze({
  define_attack:op('combat',attackSchema,(ctx,x)=>writeAttack(ctx,x)),
  update_attack:op('combat',fields({...attackSchema.properties,expectedAttackRevision:whole(1,Number.MAX_SAFE_INTEGER),
    discardUnavailableSelections:boolean},[...attackSchema.required,'expectedAttackRevision']),(ctx,x)=>writeAttack(ctx,x,true)),
  select_damage_variant:op('combat',fields({actorId:identifier,attackId:identifier,componentId:identifier,type:identifier},['actorId','attackId','componentId','type']),chooseVariant),
  resolve_attack:op('combat',fields({actorId:identifier,attemptId:identifier,attackId:identifier,actionUseId:identifier,substitutionId:identifier,mode,hit:evidence,includeDamage:boolean,damage:damageEvidence,targets:arrayOf(identifier,16)},['actorId','attemptId','attackId','mode','includeDamage']),resolveAttack),
  roll_damage_only:op('combat',fields({actorId:identifier,attackId:identifier,rollId:identifier,damage:damageEvidence},['actorId','attackId','rollId','damage']),standaloneDamage),
  roll_attack_damage:op('combat',fields({actorId:identifier,attemptId:identifier,damage:damageEvidence},['actorId','attemptId','damage']),async(ctx,x)=>{
    const r=await owned(ctx,'attack_attempt',x.attemptId,x.actorId);
    must(r.data.damage===null,'ATTEMPT_DAMAGE_ALREADY_RECORDED');
    const a=await actor(ctx,x.actorId);
    for(const part of x.damage)checkDiceOwner(ctx,a,'damage',part.evidence.origin);
    const damage=rollComponents(r.data.components,x.damage,r.data.critical);
    await save(ctx,r,{...r.data,damage});return {attemptId:r.id,damage,outcome:r.data.outcome,appliedDamage:null};
  }),
  start_encounter:op('combat',fields({encounterId:identifier,name:named(160),participants:arrayOf(fields({actorId:identifier,initiativeRollId:identifier},['actorId','initiativeRollId']),16)},['encounterId','name','participants']),startEncounter),
  advance_encounter_turn:op('combat',fields({encounterId:identifier,currentActorId:identifier},['encounterId','currentActorId']),(ctx,x)=>progress(ctx,x,false)),
  end_encounter:op('combat',fields({encounterId:identifier,currentActorId:identifier,outcome:named(500)},['encounterId','currentActorId','outcome']),(ctx,x)=>progress(ctx,x,true))
});
