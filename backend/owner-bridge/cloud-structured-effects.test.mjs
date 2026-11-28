import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { cloudActions } from './cloud-actions-view.mjs';
import { cloudSheet } from './cloud-http.mjs';
import { MECHANIC_KINDS, MECHANIC_OPERATIONS } from './cloud-mechanics.mjs';
import { RECORD_KINDS, RECORD_OPERATIONS } from './cloud-records.mjs';
import { CloudService } from './cloud-service.mjs';
import { cloudSpellbook } from './cloud-spellbook.mjs';
import { cloudParity } from './fixtures/cloud-parity.mjs';
import { SqliteAdapter } from './sqlite-adapter.mjs';

const ddl=await readFile(new URL('./schema.sql',import.meta.url),'utf8');
const ownerId='synthetic-effects-owner',continuity='qa-structured-effects',actorId='tester',source='Isolated effect fixture';
const owner={ownerId,role:'owner',credentialId:'sites-owner'},dm={ownerId,role:'connector',credentialId:'sites-mcp'};
const dice=(...faces)=>({origin:'player_physical',faces});
const component=(id,type,dice,sides,flat,options={})=>({id,name:id,type,dice,sides,flat,...options});
async function fixture(t,character={}){
  const db=new SqliteAdapter();t.after(()=>db.close());db.exec(ddl);
  const service=new CloudService(db,{ownerId,isolated:true,kinds:{...RECORD_KINDS,...MECHANIC_KINDS},operations:{...RECORD_OPERATIONS,...MECHANIC_OPERATIONS}});
  await service.createIsolated(owner,continuity);
  const categories=['character','combat','spells','resources','rolls','inventory'];
  await service.setGrant(owner,continuity,'sites-mcp',{scopes:categories.flatMap(s=>[s+'.read',s+'.write']),auto:categories.map(s=>s+'.write'),
    actors:['*'],expiresAt:Date.now()+3600000,revoked:false,expectedGrantRevision:0});
  const submit=cloudParity(()=>db,ownerId,continuity);
  const run=async(operation,args,options={})=>{
    const command={version:1,epoch:1,commandId:crypto.randomUUID(),operation,args,expectedRevision:(await service.continuity(continuity)).revision};
    const result=await submit(command,options);
    assert.equal(result.status,'COMMITTED');
    assert.deepEqual((await service.receipt(dm,continuity,command.commandId)).receipt,result.receipt);
    return result.receipt.outcome;
  };
  await run('define_actor',{actorId,name:'Fixture actor',role:'player',rulesProfile:'revised_2024',level:2,gameSeconds:0,
    abilities:{STR:12,DEX:10,CON:10,INT:10,WIS:10,CHA:10},maxHp:20,currentHp:13,temporaryHp:4,...character});
  await run('define_item_definition',{definitionId:'fixture-definition',name:'Test focus',description:'Synthetic test equipment',category:'gear',
    rarity:null,stackable:false,wearable:true,handheld:false,attunement:'none',source});
  await run('acquire_item',{itemId:'focus',definitionId:'fixture-definition',ownerId:actorId,quantity:1,source});
  const effect=(effectId,target,key,amount,extra={})=>run('define_effect',{actorId,effectId,target,key,amount,name:effectId,
    operation:'add',source,sourceItemId:'focus',...extra});
  return {run,effect,equip:equipment=>run('equip_item',{itemId:'focus',equipment,source}),
    sheet:async()=>(await cloudSheet(service,dm,continuity,actorId)).sheet,
    actions:async()=>(await cloudActions(service,dm,continuity,actorId)).rows,
    spells:async()=>(await cloudSpellbook(service,dm,continuity,actorId)).rows,
    record:async id=>(await service.state(dm,continuity,{id})).records[0]};
}

test('equipped maximum HP, Constitution baseline and separate Integrity retain their original balances',async t=>{
  const f=await fixture(t,{baselineConstitutionModifier:0});
  await f.run('define_resource',{actorId,poolId:'frame-health',name:'Frame health',type:'integrity',current:23,maximum:75,recovery:'none',source});
  await f.run('define_form',{actorId,formId:'frame',name:'Frame',source,timing:'other',activationSeconds:0,integrityPoolId:'frame-health'});
  await f.run('change_form',{actorId,formId:'frame',elapsedSeconds:0});
  await f.effect('stronger','max_hp','all',4,{stackingKey:'capacity'});
  await f.effect('weaker','max_hp','all',2,{stackingKey:'capacity'});
  await f.effect('constitution','ability_score','CON',2);
  const vitals=async()=>{
    const actor=(await f.record(actorId)).data,sheet=await f.sheet();
    return [actor.maxHp,sheet.maxHp,actor.currentHp,actor.temporaryHp,actor.activeFormId,(await f.record('frame-health')).data.current];
  };
  assert.deepEqual(await vitals(),[20,20,13,4,'frame',23]);
  await f.equip('worn');
  assert.deepEqual(await vitals(),[20,26,13,4,'frame',23]);
  const hp=(await f.sheet()).hpMaximum;
  assert.deepEqual([hp.baseMaxHp,hp.constitutionAdjustment,hp.sources.map(s=>s.id)],[20,2,['stronger']]);
  await f.run('apply_hp_change',{actorId,kind:'heal',amount:100});
  await f.equip('packed');
  assert.deepEqual(await vitals(),[20,20,26,4,'frame',23]);
  const capped=await f.run('apply_hp_change',{actorId,kind:'heal',amount:5});
  assert.deepEqual([capped.beforeHp,capped.afterHp],[26,26]);
  await f.equip('worn');
  assert.deepEqual(await vitals(),[20,26,26,4,'frame',23]);
  const hit=await f.run('apply_hp_change',{actorId,kind:'damage',amount:5});
  assert.deepEqual([hit.absorbedByIntegrity,hit.afterHp,(await f.record('frame-health')).data.current],[5,26,18]);
});

test('unknown HP remains unknown and a completed rest uses only capacity active at its actual end',async t=>{
  const f=await fixture(t,{maxHp:null,currentHp:3});
  await f.effect('add-to-unknown','max_hp','all',2);
  await f.equip('worn');
  assert.equal((await f.sheet()).maxHp,null);
  await assert.rejects(f.run('apply_hp_change',{actorId,kind:'heal',amount:3}),/HP_UNKNOWN/);
  await f.effect('known-cap','max_hp','all',15,{operation:'set'});
  await f.effect('expiring','max_hp','all',8,{expiresAt:120});
  assert.equal((await f.sheet()).maxHp,25);
  await f.run('define_resource',{actorId,poolId:'dice',name:'Hit dice',type:'hit_die',dieSides:20,current:2,maximum:2,recovery:'edition_hit_dice',source});
  const rest=await f.run('complete_rest',{actorId,kind:'short_rest',elapsedSeconds:3600,confirmed:true,hitDice:[{poolId:'dice',origin:'player_physical',faces:[19]}]});
  assert.deepEqual([rest.beforeHp,rest.afterHp,rest.maxHp,rest.actualHealing,rest.hitDice[0].actual],[3,17,17,14,14]);
  assert.equal((await f.record(rest.reportId)).data.actualHealing,14);
  await f.run('apply_hp_change',{actorId,kind:'damage',amount:9});
  const long=await f.run('complete_rest',{actorId,kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[]});
  assert.deepEqual([long.afterHp,long.maxHp,long.actualHealing],[17,17,5]);
  await f.equip('packed');
  assert.equal((await f.sheet()).maxHp,null);
  assert.equal((await f.record(actorId)).data.currentHp,17);
});

test('attack and spell damage use the selected typed components, observed dice and snapshotted source bonuses',async t=>{
  const f=await fixture(t);
  const attack=(attackId,components)=>f.run('define_attack',{actorId,attackId,name:attackId,source,timing:'other',
    delivery:'melee',resolution:'attack',fixedHit:4,components});
  const components=[component('impact','force',1,6,3,{abilityBonus:'STR'}),
    component('element','cold',1,4,1,{variants:['cold','fire']}),component('residue','fire',0,4,2,{critical:false})];
  await attack('primary',components);await attack('secondary',[component('cut','slashing',1,6,0)]);
  const spell=async(spellId,componentType)=>{
    await f.run('define_spell',{actorId,spellId,name:spellId,source,baseLevel:0,concentration:false,
      timing:'other',ritual:false,resolution:'automatic',components:[component('result',componentType,1,4,1)]});
    await f.run('grant_spell',{actorId,spellId,grantId:'grant-'+spellId,source,route:'at_will',levels:[0],preparation:'not_required'});
  };
  await spell('light','radiant');await spell('mend','healing');
  await f.effect('weapon-modifier','attack','all',1,{stackingKey:'hit'});
  await f.effect('primary-accuracy','attack','primary',2,{stackingKey:'hit'});
  await f.effect('common','damage','all',2,{stackingKey:'output'});
  await f.effect('competing','damage','primary',1,{stackingKey:'output',damageComponentId:'impact'});
  await f.effect('selected-fire','damage','primary',3,{damageComponentId:'element',damageType:'fire'});
  await f.effect('all-fire','damage','primary',1,{damageType:'fire',eachMatchingComponent:true});
  await f.effect('spell-radiance','damage','light',4,{damageRoute:'spell',damageComponentId:'result',damageType:'radiant'});
  await f.effect('all-spells','damage','all',1,{damageRoute:'spell'});
  await assert.rejects(f.effect('invalid','damage','primary',7,{damageComponentId:'missing'}),/DAMAGE_COMPONENT_TARGET_UNAVAILABLE/);
  await assert.rejects(f.effect('invalid','damage','all',7,{eachMatchingComponent:true}),/TYPED_COMPONENT_SELECTOR_REQUIRED/);
  await assert.rejects(f.effect('invalid','damage','mend',7,{damageRoute:'spell',damageComponentId:'result'}),/DAMAGE_COMPONENT_TARGET_UNAVAILABLE/);
  await f.equip('worn');
  const preview=async(id)=>(await f.actions()).find(row=>row.id===id);
  assert.deepEqual([(await preview('primary')).hit,(await preview('secondary')).hit],[6,5]);
  assert.deepEqual((await preview('primary')).components.map(c=>c.flat),[6,1,3]);
  await f.run('select_damage_variant',{actorId,attackId:'primary',componentId:'element',type:'fire'});
  assert.deepEqual((await preview('primary')).components.map(c=>c.flat),[6,5,3]);
  const hit=await f.run('resolve_attack',{actorId,attackId:'primary',attemptId:'attempt',mode:'normal',hit:dice(20),includeDamage:false});
  assert.deepEqual([hit.hit.modifier,hit.hit.total,hit.critical,hit.damage,hit.appliedDamage],[6,26,true,null,null]);
  assert.deepEqual(hit.components[0].damageSources.map(s=>s.id),['common']);
  const proj=Object.fromEntries((await f.spells()).map(r=>[r.spellId,r]));
  assert.deepEqual([proj.light.components[0].flat,proj.mend.components[0].flat],[6,1]);
  const cast=await f.run('cast_spell',{actorId,grantId:'grant-light',castId:'cast-light',level:0});
  assert.equal(cast.components[0].flat,6);
  await f.equip('packed');
  assert.deepEqual([(await preview('primary')).hit,(await preview('primary')).components.map(c=>c.flat)],[4,[4,1,2]]);
  const rolled=await f.run('roll_attack_damage',{actorId,attemptId:'attempt',damage:[
    {id:'impact',evidence:dice(5,4)},{id:'element',evidence:dice(3,2)},{id:'residue',evidence:dice()}]});
  assert.deepEqual(rolled.damage.map(c=>[c.type,c.modifier,c.faces.length,c.total]),[['force',6,2,15],['fire',5,2,10],['fire',3,0,3]]);
  assert.deepEqual((await f.record('attempt')).data.damage,rolled.damage);
  await assert.rejects(f.run('roll_attack_damage',{actorId,attemptId:'attempt',damage:[]}),/ATTEMPT_DAMAGE_ALREADY_RECORDED/);
  const linked=await f.run('link_cast_roll',{actorId,castId:'cast-light',damage:[{id:'result',evidence:dice(3)}]});
  assert.deepEqual([linked.damage[0].total,linked.costs.length],[9,0]);
  assert.equal((await f.record('cast-light')).data.damage[0].modifier,6);
  await f.equip('worn');
  await f.run('cast_spell',{actorId,grantId:'grant-mend',castId:'cast-mend',level:0});
  const heal=await f.run('link_cast_roll',{actorId,castId:'cast-mend',damage:[{id:'result',evidence:dice(3)}]});
  assert.deepEqual([heal.damage[0].total,heal.damage[0].damageSources],[4,undefined]);
});

test('declared optional pooled effects spend once at the attempt or at an independently resolved hit',async t=>{
  const f=await fixture(t);
  await f.run('define_actor',{actorId:'target',name:'Synthetic rival',role:'npc',rulesProfile:'legacy_2014',level:1,
    baseAc:15,maxHp:30,currentHp:30,temporaryHp:0,gameSeconds:0});
  await f.run('define_resource',{actorId,poolId:'cells',name:'Focus charges',type:'charge',current:10,maximum:10,recovery:'none',source});
  await f.run('bind_item_charge_pool',{itemId:'focus',poolId:'cells',source});
  await f.run('define_attack',{actorId,attackId:'strike',name:'Focused strike',source,timing:'other',resolution:'attack',delivery:'melee',
    fixedHit:2,components:[component('force','force',1,4,1)]});
  const charge=(trigger,amount)=>({poolId:'cells',trigger,amount});
  await f.effect('aim','attack','strike',2,{charge:charge('attempt',1)});
  await f.effect('boost','damage','strike',4,{charge:charge('confirmed_hit',2)});
  await assert.rejects(f.effect('invalid','attack','strike',2,{charge:charge('confirmed_hit',1)}),/EFFECT_CHARGE_TRIGGER_INVALID/);
  await f.equip('worn');
  const projection=(await f.actions()).find(r=>r.id==='strike');
  assert.deepEqual([projection.hit,projection.components[0].flat,projection.optionalCharges.map(c=>c.id).sort()],[2,1,['aim','boost']]);
  const balance=async()=>{const d=(await f.record('cells')).data;return [d.current,(d.effectHolds??[]).reduce((n,h)=>n+h.amount,0)];};
  const attack=(attemptId,face,extra={})=>f.run('resolve_attack',{actorId,attackId:'strike',attemptId,mode:'normal',hit:dice(face),includeDamage:false,
    targets:['target'],...extra});
  const damage=attemptId=>f.run('roll_attack_damage',{actorId,attemptId,damage:[{id:'force',evidence:dice(3)}]});
  const adjudicate=attemptId=>f.run('resolve_attack_target',{actorId,attemptId,targetActorId:'target',source});
  await attack('ordinary',12);
  assert.deepEqual(await balance(),[10,0]);
  await assert.rejects(attack('no-target',13,{targets:[],chargedEffectIds:['boost']}),/HIT_CHARGE_REQUIRES_TARGET/);
  await assert.rejects(attack('duplicate',13,{chargedEffectIds:['aim','aim']}),/CHARGED_EFFECT_SELECTION_INVALID/);
  assert.equal(await f.record('no-target'),undefined);
  const boosted=await attack('charged',11,{chargedEffectIds:['aim','boost']});
  assert.deepEqual([boosted.hit.total,boosted.components[0].flat,boosted.costs[0].amount],[15,5,1]);
  assert.deepEqual(await balance(),[9,2]);
  await assert.rejects(f.run('spend_resource',{actorId,poolId:'cells',amount:8}),/RESOURCE_EMPTY_OR_UNKNOWN/);
  await assert.rejects(adjudicate('charged'),/ATTEMPT_DAMAGE_REQUIRED/);
  await f.equip('packed');
  assert.equal((await damage('charged')).damage[0].total,8);
  assert.deepEqual(await balance(),[9,2]);
  const applied=await adjudicate('charged');
  assert.deepEqual([applied.decision,applied.amount,applied.effectCosts[0].amount,(await f.record('target')).data.currentHp],['hit',8,2,22]);
  assert.deepEqual(await balance(),[7,0]);
  await assert.rejects(adjudicate('charged'),/TARGET_UNDECLARED_OR_ALREADY_RESOLVED/);
  await f.equip('worn');
  const field={chargedEffectIds:['aim','boost'],includeDamage:true,damage:[{id:'force',evidence:dice(1)}]};
  await attack('missed',5,field);assert.deepEqual(await balance(),[6,2]);
  assert.equal((await adjudicate('missed')).decision,'miss');assert.deepEqual(await balance(),[6,0]);
  await attack('automatic-miss',1,field);assert.deepEqual(await balance(),[5,0]);
  await attack('abandoned',12,{chargedEffectIds:['boost']});assert.deepEqual(await balance(),[5,2]);
  const released=await f.run('retire_unresolved_effect_charges',{actorId,useId:'abandoned',kind:'attack_attempt',source:'Owner confirmed the scene ended unresolved'},{review:true});
  assert.equal(released.effectCharges[0].status,'released');assert.deepEqual(await balance(),[5,0]);
  await assert.rejects(damage('abandoned'),/ATTEMPT_CLOSED/);
  await f.run('spend_resource',{actorId,poolId:'cells',amount:3});
  await assert.rejects(attack('underfunded',18,{chargedEffectIds:['aim','boost']}),/RESOURCE_EMPTY_OR_UNKNOWN/);
  assert.deepEqual(await balance(),[2,0]);assert.equal(await f.record('underfunded'),undefined);
});

test('a spell roll settles only its declared optional effects and retains paid slot and target independence',async t=>{
  const f=await fixture(t);await f.equip('worn');
  await f.run('define_actor',{actorId:'foe',name:'Synthetic target',role:'creature',rulesProfile:'legacy_2014',level:1,baseAc:10,maxHp:20,currentHp:20,temporaryHp:0});
  await f.run('define_resource',{actorId,poolId:'crystal',name:'Crystal uses',type:'charge',current:4,maximum:4,recovery:'none',source});
  await f.run('define_resource',{actorId,poolId:'slot',name:'Spell slot',type:'spell_slot',slotLevel:1,current:1,maximum:1,recovery:'long_rest',source});
  await f.run('define_spell',{actorId,spellId:'ray',name:'Test ray',source,baseLevel:1,concentration:false,
    timing:'action',ritual:false,resolution:'attack',components:[component('beam','radiant',1,4,1)]});
  await f.run('grant_spell',{actorId,spellId:'ray',grantId:'known-ray',source,route:'slot',levels:[1],preparation:'prepared',fixedHit:3});
  await f.effect('spell-aim','spell_attack','known-ray',2,{charge:{poolId:'crystal',amount:1,trigger:'attempt'}});
  await f.effect('spell-boost','damage','ray',3,{damageRoute:'spell',charge:{poolId:'crystal',amount:2,trigger:'confirmed_hit'}});
  assert.deepEqual((await f.spells())[0].optionalCharges.map(c=>c.id).sort(),['spell-aim','spell-boost']);
  await f.run('cast_spell',{actorId,grantId:'known-ray',castId:'cast',level:1,targets:['foe'],slotPoolId:'slot',chargedEffectIds:['spell-aim','spell-boost']});
  assert.equal((await f.record('slot')).data.current,0);assert.equal((await f.record('crystal')).data.current,4);
  const rolled=await f.run('link_cast_roll',{actorId,castId:'cast',hit:dice(8),damage:[{id:'beam',evidence:dice(3)}]});
  assert.deepEqual([rolled.hit.total,rolled.damage[0].total,(await f.record('crystal')).data.current],[13,7,3]);
  await f.run('resolve_cast_target',{actorId,castId:'cast',targetActorId:'foe',source});
  assert.deepEqual([(await f.record('slot')).data.current,(await f.record('crystal')).data.current,(await f.record('foe')).data.currentHp],[0,1,13]);
});

test('pinned exhaustion changes only its edition-specific rolls, movement, capacity and eligible recovery',async t=>{
  const f=await fixture(t,{rulesProfile:'legacy_2014',speeds:{walk:30,fly:45}});
  const set=(level,extra={})=>f.run('set_condition',{actorId,key:'exhaustion',enabled:level>0,level,sourceId:'confirmed-exposure',...extra});
  await assert.rejects(f.run('set_condition',{actorId,key:'exhaustion',enabled:true,sourceId:'unknown-level'}),/EXHAUSTION_LEVEL_REQUIRED/);
  await set(1);let s=await f.sheet();
  assert.deepEqual([s.rollModes['ability:STR'].normal.mode,s.rollModes['save:STR'].normal.mode,s.passives.perception.value],['disadvantage','normal',5]);
  await set(2);s=await f.sheet();assert.deepEqual([s.movement.walk,s.movement.fly],[15,22]);
  await set(3);assert.equal((await f.sheet()).rollModes['save:CON'].normal.mode,'disadvantage');
  await set(4);s=await f.sheet();assert.deepEqual([s.maxHp,s.currentHp,s.temporaryHp],[10,13,4]);
  await set(5);assert.deepEqual((await f.sheet()).movement,{walk:0,fly:0});
  await set(6);
  await assert.rejects(f.run('record_check',{actorId,rollId:'fatal',check:{kind:'ability',key:'STR',mode:'normal'},evidence:dice(9)}),/FATAL_EXHAUSTION/);
  await assert.rejects(f.run('complete_rest',{actorId,kind:'long_rest',elapsedSeconds:28800,hitDice:[],confirmed:true}),/FATAL_EXHAUSTION/);
  await assert.rejects(f.run('apply_hp_change',{actorId,kind:'heal',amount:2}),/FATAL_EXHAUSTION/);
  await set(4);
  const rest=extra=>f.run('complete_rest',{actorId,kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[],...extra});
  assert.equal((await rest({})).exhaustion.after,4);
  assert.deepEqual([(await rest({exhaustionRecoveryEligible:true})).exhaustion.after,(await f.sheet()).maxHp],[3,20]);
  await assert.rejects(set(2,{customExhaustionRule:'revised_2024',adjudicationSource:'Source'}),/PINNED_EXHAUSTION_RULE_REQUIRED/);
  await set(0);assert.equal((await f.sheet()).conditions.includes('exhaustion'),false);
  const r=await fixture(t,{rulesProfile:'revised_2024',speeds:{walk:30}});
  await r.run('set_condition',{actorId,key:'exhaustion',enabled:true,level:2,sourceId:'confirmed'});
  const revised=await r.sheet();
  assert.deepEqual([revised.abilities.STR.modifier,revised.checks.STR.value,revised.skills.athletics.value,revised.saves.STR.value,revised.initiative.value,
    revised.movement.walk,revised.maxHp,revised.rollModes['ability:STR'].normal.mode],[1,-3,-3,-3,-4,20,20,'normal']);
  await r.run('define_effect',{actorId,effectId:'ward',sourceItemId:'focus',name:'Exhaustion ward',source,target:'condition_immunity',key:'exhaustion',operation:'immunity'});
  await r.equip('worn');assert.deepEqual([(await r.sheet()).exhaustion.status,(await r.sheet()).exhaustion.level,(await r.sheet()).movement.walk],['suppressed',2,30]);
  await r.equip('packed');assert.equal((await r.sheet()).movement.walk,20);
  assert.equal((await r.run('complete_rest',{actorId,kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[]})).exhaustion.after,1);
  const custom=await fixture(t,{rulesProfile:'custom',speeds:{walk:30}});
  const update={actorId,key:'exhaustion',enabled:true,level:2,sourceId:'confirmed'};
  await custom.run('set_condition',update);assert.equal((await custom.sheet()).exhaustion.status,'adjudication_required');
  await assert.rejects(custom.run('record_check',{actorId,rollId:'unknown',check:{kind:'save',key:'STR',mode:'normal'},evidence:dice(9)}),/ADJUDICATION_REQUIRED/);
  await custom.run('set_condition',{...update,customExhaustionRule:'legacy_2014',adjudicationSource:'Reviewed custom profile adopts legacy exhaustion'});
  assert.equal((await custom.sheet()).movement.walk,15);
});

test('an attuned worn ward upgrades only while resistance from an independent source is active',async t=>{
  const f=await fixture(t,{attunementCapacity:1});
  await f.run('define_item_definition',{definitionId:'brocade-def',name:'Fictional brocade',description:'Fixture garment',
    category:'gear',rarity:null,stackable:false,wearable:true,handheld:true,attunement:'required',attunementSeconds:0,source});
  await f.run('acquire_item',{itemId:'brocade',definitionId:'brocade-def',ownerId:actorId,quantity:1,source});
  const effect={actorId,name:'Fixture cold defense',source,target:'damage_defense',key:'cold',operation:'resistance'};
  await f.run('define_effect',{...effect,effectId:'frost-ward',sourceItemId:'brocade',requiresAttunement:true,wornResistanceUpgrade:true});
  await assert.rejects(f.run('define_effect',{...effect,effectId:'misconfigured',wornResistanceUpgrade:true}),/WORN_RESISTANCE_SOURCE_REQUIRED/);
  const ward=async()=>(await f.sheet()).defenses.find(r=>r.source==='frost-ward');
  const equip=equipment=>f.run('equip_item',{itemId:'brocade',equipment,source});
  const attune=attuned=>f.run('set_item_attunement',{itemId:'brocade',attuned,elapsedSeconds:0,confirmed:true,source});
  await equip('worn');assert.equal(await ward(),undefined);
  await attune(true);assert.equal((await ward()).kind,'resistance');
  await f.run('define_effect',{...effect,effectId:'conditional-lining',nonmagicalOnly:true});
  assert.equal((await ward()).kind,'resistance');
  await f.run('define_effect',{...effect,effectId:'alias-on-same-item',sourceItemId:'brocade'});
  await f.run('define_effect',{...effect,effectId:'other-element',key:'fire'});
  assert.equal((await ward()).kind,'resistance');
  await f.run('define_effect',{...effect,effectId:'separate-lining',sourceItemId:'focus'});
  await f.equip('worn');assert.equal((await ward()).kind,'immunity');
  assert.equal((await ward()).independentResistanceId,'separate-lining');
  await equip('held');assert.equal(await ward(),undefined);
  await equip('worn');await f.equip('packed');assert.equal((await ward()).kind,'resistance');
  await f.run('define_effect',{...effect,effectId:'innate-resistance'});
  assert.equal((await ward()).independentResistanceId,'innate-resistance');
  await attune(false);assert.equal(await ward(),undefined);
  assert.equal((await f.sheet()).defenses.some(r=>r.kind==='immunity'),false);
});

test('installed source eligibility follows its operating owner and cannot supply a worn-only rule',async ()=>{
  const {gearEligible}=await import('./cloud-mechanics-core.mjs');
  const data={custodianId:'pilot',ownerId:'pilot',installedOnActorId:'pilot',partyAccess:true,holding:'installed',
    equipment:'packed',quantity:1,operating:true,attuned:false};
  const row={id:'module',kind:'item_instance',life:'active',data};
  const context={current:()=>row,require:(scope,id,write)=>assert.deepEqual([scope,id,write],['inventory','pilot',false])};
  const eligible=(attuned=false,worn=false)=>gearEligible(context,'module','pilot',attuned,true,null,worn);
  assert.equal(await eligible(),true);assert.equal(await eligible(true),false);assert.equal(await eligible(false,true),false);
  data.operating=false;assert.equal(await eligible(),false);data.operating=true;
  data.installedOnActorId='other';assert.equal(await eligible(),false);data.installedOnActorId='pilot';
  data.ownerId='other';assert.equal(await eligible(),false);data.ownerId='pilot';
  data.partyAccess=false;assert.equal(await eligible(),false);
});

test('hover depends on the active source and eligible flight',async t=>{
  const f=await fixture(t,{speeds:{walk:20}});
  await f.run('define_feature',{actorId,featureId:'stationary-flight',name:'Stationary flight',kind:'companion',source,
    description:'Test airframe can hover during powered flight',references:[],mobility:'hover',sourceItemId:'focus',requiresOperating:true});
  await f.effect('thrust','speed','fly',30,{operation:'set',sourceItemId:'focus',requiresOperating:true,requiresWorn:true});
  await f.equip('worn');
  assert.deepEqual((await f.sheet()).mobilityRules.map(r=>[r.kind,r.eligible]),[['hover',true]]);
  await f.equip('packed');
  assert.deepEqual((await f.sheet()).mobilityRules,[]);
});

test('qualified defense separates same-type damage by provenance and preserves unresolved attempts', async t => {
  const f = await fixture(t);
  await assert.rejects(f.effect('invalid-provenance','max_hp','all',1,{nonmagicalOnly:true}),
    /QUALIFIED_DEFENSE_INVALID/);
  const targetActorId = 'synthetic-ward-target';
  await f.run('define_actor', {actorId:targetActorId,name:'Ward target',role:'npc',rulesProfile:'revised_2024',
    level:1,baseAc:12,maxHp:40,currentHp:40,temporaryHp:0});
  await f.run('define_effect', {actorId:targetActorId,effectId:'mundane-pierce-ward',name:'Piercing ward',source,
    target:'damage_defense',key:'piercing',operation:'resistance',nonmagicalOnly:true});
  async function strike(attackId, components) {
    await f.run('define_attack', {actorId,attackId,name:attackId,source,timing:'other',
      resolution:'automatic',delivery:'other',components});
    const attemptId = attackId + '-attempt';
    await f.run('resolve_attack', {actorId,attackId,attemptId,mode:'normal',targets:[targetActorId],
      includeDamage:true,damage:components.map(c => ({id:c.id,evidence:dice(6)}))});
    return attemptId;
  }
  const resolve = attemptId => f.run('resolve_attack_target', {actorId,attemptId,targetActorId,source});
  const hp = async () => (await f.record(targetActorId)).data.currentHp;

  const mixed = await strike('split', [
    component('plain','piercing',1,6,0,{magical:false}),
    component('enchanted','piercing',1,6,0,{magical:true})
  ]);
  const receipt = await resolve(mixed);
  assert.deepEqual(receipt.components.map(c => [c.magical,c.rolled,c.applied,c.defenses.map(d => d.source)]),
    [[false,6,3,['mundane-pierce-ward']], [true,6,6,[]]]);
  assert.deepEqual([receipt.amount,await hp()], [9,31]);

  const unspecified = await strike('unclassified',[component('unknown-origin','piercing',1,6,0)]);
  await assert.rejects(resolve(unspecified), /DAMAGE_PROVENANCE_REQUIRED/);
  const pending = (await f.record(unspecified)).data;
  assert.deepEqual(pending.targetResults ?? [], []);
  assert.equal(await hp(),31);

  const unrelated = await strike('unaffected',[component('other-type','cold',1,6,0)]);
  assert.equal((await resolve(unrelated)).amount,6);
  assert.equal(await hp(),25);
});

test('an older attack editor preserves exact qualifiers on the same component IDs', async t => {
  const f=await fixture(t);
  const attackId='legacy-draft';
  const input={actorId,attackId,name:'Draft',source,sourceItemId:'focus',timing:'other',delivery:'melee',
    resolution:'attack',fixedHit:3,reach:7};
  const plain=component('spine','piercing',1,6,0), infused=component('spark','cold',1,4,0);
  await f.run('define_attack',{...input,enforceReach:true,requiresWorn:true,naturalMagicItemId:'focus',
    components:[{...plain,magical:false},{...infused,magical:true}]});
  const edit=async fields=>f.run('update_attack',{...input,expectedAttackRevision:(await f.record(attackId)).revision,
    components:[infused,plain],...fields});
  await edit({name:'Renamed'});
  const config=async()=>(await f.record(attackId)).data;
  const read=await config();
  assert.deepEqual([read.enforceReach,read.requiresWorn,read.naturalMagicItemId],[true,true,'focus']);
  assert.deepEqual(read.components.map(c=>[c.id,c.magical]),[['spark',true],['spine',false]]);
  await edit({components:[{...infused,magical:false},component('replacement','cold',1,4,0)]});
  assert.deepEqual((await config()).components.map(c=>[c.id,c.magical]),[['spark',false],['replacement',undefined]]);
  await assert.rejects(edit({delivery:'ranged'}),/REACH_CONFIGURATION_REQUIRED/);
  await assert.rejects(edit({naturalMagicItemId:'unknown-source'}));
  assert.deepEqual([(await config()).delivery,(await config()).naturalMagicItemId],['melee','focus']);
});

test('an exceptional ranged attack validates the submitted distance and targeting evidence', async t => {
  const f=await fixture(t);
  await f.equip('worn');
  const attackId='beam', fields={actorId,attackId,name:'Fixture beam',source,sourceItemId:'focus',timing:'other',delivery:'ranged',
    resolution:'attack',fixedHit:1,range:12,longRange:24,components:[component('flash','cold',1,4,0)]};
  await f.run('define_attack',{...fields,longstrikeLimit:45});
  const edit=async changes=>f.run('update_attack',{...fields,expectedAttackRevision:(await f.record(attackId)).revision,...changes});
  await edit({name:'Edited by old client'});
  assert.equal((await f.record(attackId)).data.longstrikeLimit,45);
  await assert.rejects(edit({longstrikeLimit:23}),/LONGSTRIKE_RANGE_CONFIGURATION_REQUIRED/);
  await edit({longstrikeLimit:48});
  const shot=(attemptId,extra)=>f.run('resolve_attack',{actorId,attackId,attemptId,includeDamage:false,
    mode:'normal',hit:dice(12),...extra});
  await assert.rejects(shot('missing-distance',{}),/ATTACK_DISTANCE_REQUIRED/);
  await shot('short',{targetDistanceFeet:12});
  await assert.rejects(shot('ignored-long-range',{targetDistanceFeet:20}),/LONG_RANGE_DISADVANTAGE_REQUIRED/);
  await shot('distant',{targetDistanceFeet:24,mode:'disadvantage',hit:dice(12,10)});
  await assert.rejects(shot('unconfirmed',{targetDistanceFeet:47}),/LONGSTRIKE_TARGET_UNCONFIRMED/);
  const clear={targetResolved:true,viablePath:true,totalCover:false};
  await assert.rejects(shot('covered',{targetDistanceFeet:47,longstrike:{...clear,totalCover:true}}),/LONGSTRIKE_TARGET_UNCONFIRMED/);
  await assert.rejects(shot('exceptional-normal',{targetDistanceFeet:48,longstrike:clear}),/LONG_RANGE_DISADVANTAGE_REQUIRED/);
  await shot('exceptional',{targetDistanceFeet:48,longstrike:clear,mode:'disadvantage',hit:dice(13,11)});
  await assert.rejects(shot('out-of-range',{targetDistanceFeet:49,longstrike:clear}),/SHOT_OUT_OF_RANGE/);
  const targetActorId='beam-target', actionId='aiming-aid', poolId='aiming-cell';
  await f.run('define_actor',{actorId:targetActorId,name:'Calibration target',role:'npc',rulesProfile:'custom',level:1});
  await f.run('define_resource',{actorId,poolId,name:'Focusing cell',type:'charge',maximum:1,current:1,recovery:'short_rest',source});
  await f.run('define_action',{actorId,actionId,name:'Focus on target',type:'weapon_target_lock',source,
    timing:'bonus_action',sourceItemId:'focus',requiresOperating:true,cost:{poolId,amount:1},lockRangeFeet:40});
  const lockTarget={targetActorId,distanceFeet:18,seenOrResolved:true,totalCover:false};
  await assert.rejects(f.run('use_action',{actorId,actionId,useId:'bad-lock',declared:true,lockTarget:{...lockTarget,totalCover:true}}),/LOCK_TARGET_UNAVAILABLE/);
  await f.run('use_action',{actorId,actionId,useId:'aimed',declared:true,lockTarget});
  assert.equal((await f.record(poolId)).data.current,0);
  await shot('with-lock',{targetDistanceFeet:22,targets:[targetActorId],hit:dice(11,15)});
  const aimed=(await f.record('with-lock')).data;
  assert.deepEqual([aimed.hit.natural,aimed.weaponTargetLock?.targetActorId],[15,targetActorId]);
  await f.run('release_weapon_target_lock',{actorId,targetActorId,reason:'released',confirmed:true});
  await assert.rejects(shot('after-release',{targetDistanceFeet:22,targets:[targetActorId]}),/LONG_RANGE_DISADVANTAGE_REQUIRED/);
});

test('temporary HP and source-linked equipment modes require a choice, consume a finite pool and expire', async t => {
  const f=await fixture(t,{speeds:{walk:13}});
  await f.equip('worn');
  const poolId='module-charge', actionId='activate-mode';
  await f.run('define_resource',{actorId,poolId,name:'Module charge',source,type:'charge',current:2,maximum:2,recovery:'none'});
  await f.run('define_action',{actorId,actionId,name:'Pulse',source,sourceItemId:'focus',timing:'other',type:'equipment_mode',
    activateModes:[{id:'pulse',seconds:11}],secondaryPools:[poolId],tempHpRule:{dice:1,sides:6,flat:2}});
  await f.effect('mode-speed','speed','walk',5,{requiredItemMode:'pulse'});
  const modeEffect=async()=>(await f.sheet()).movement.walk===18;
  const use=(useId,extra)=>f.run('use_action',{actorId,actionId,useId,declared:true,...extra});
  assert.equal(await modeEffect(),false);
  await assert.rejects(use('no-choice',{tempHpDice:dice(2)}),/TEMP_HP_CHOICE_REQUIRED/);
  assert.equal((await f.record(poolId)).data.current,2);
  await use('keep',{tempHpChoice:'keep',tempHpDice:dice(3)});
  assert.equal((await f.record(actorId)).data.temporaryHp,4);
  assert.equal(await modeEffect(),true);
  await f.equip('packed'); assert.equal(await modeEffect(),false);
  await f.equip('worn'); assert.equal(await modeEffect(),true);
  await f.run('advance_actor_time',{actorId,elapsedSeconds:11,confirmed:true});
  assert.equal(await modeEffect(),false);
  await use('replace',{tempHpChoice:'replace',tempHpDice:dice(6)});
  assert.equal((await f.record(actorId)).data.temporaryHp,8);
  assert.equal((await f.record(poolId)).data.current,0);
  await assert.rejects(use('unfunded',{tempHpChoice:'replace',tempHpDice:dice(1)}),/RESOURCE_EMPTY_OR_UNKNOWN/);
  assert.equal((await f.record(actorId)).data.temporaryHp,8);
});

test('natural damage provenance follows only the worn and attuned qualifying item', async t => {
  const f=await fixture(t,{attunementCapacity:1});
  await f.run('define_item_definition',{definitionId:'natural-focus-def',name:'Synthetic circlet',category:'gear',
    description:'Qualifying test item',rarity:null,stackable:false,wearable:true,handheld:true,
    attunement:'required',attunementSeconds:0,source});
  await f.run('acquire_item',{itemId:'circlet',definitionId:'natural-focus-def',ownerId:actorId,quantity:1,source});
  await f.run('define_attack',{actorId,attackId:'natural-strike',name:'Spine',source,timing:'other',delivery:'melee',
    resolution:'automatic',naturalMagicItemId:'circlet',components:[component('tip','piercing',1,4,0)]});
  const magic=async attemptId=>(await f.run('resolve_attack',{actorId,attemptId,attackId:'natural-strike',
    mode:'normal',includeDamage:true,damage:[{id:'tip',evidence:dice(3)}]})).damage[0].magical;
  const equip=equipment=>f.run('equip_item',{itemId:'circlet',equipment,source});
  const attune=attuned=>f.run('set_item_attunement',{itemId:'circlet',attuned,elapsedSeconds:0,confirmed:true,source});
  await equip('worn');
  assert.equal(await magic('not-attuned'),false);
  await attune(true);
  assert.equal(await magic('worn-attuned'),true);
  await equip('held');
  assert.equal(await magic('only-held'),false);
  await equip('worn');await attune(false);
  assert.equal(await magic('ended-attunement'),false);
  assert.equal((await f.record('worn-attuned')).data.damage[0].magical,true);
});

test('reviewed manual spell grants charge the actual selected route and preserve explicit adjudication',async t=>{
  const f=await fixture(t), spellId='protect', limited='free-protect', slot='prepared-protect';
  const make=(id,timing)=>f.run('define_spell',{actorId,spellId:id,name:id,source,baseLevel:1,
    concentration:false,ritual:false,timing,resolution:'manual',components:[]});
  await make(spellId,'reaction'); await make('incoming-spark','action');
  await f.run('define_resource',{actorId,poolId:'daily',name:'Daily use',source,type:'ordinary',current:1,maximum:1,recovery:'long_rest'});
  await f.run('define_resource',{actorId,poolId:'one-slot',name:'Slot',source,type:'spell_slot',slotLevel:1,current:2,maximum:2,recovery:'long_rest'});
  const base={actorId,spellId,levels:[1],source,preparation:'always_prepared',ability:'INT',
    reviewedAdjudication:true,reactionTriggerSpellId:'incoming-spark'};
  await f.run('grant_spell',{...base,grantId:limited,route:'limited',resourceId:'daily',cost:1});
  await f.run('grant_spell',{...base,grantId:slot,route:'slot',sourceItemId:'focus',requiresWorn:true});
  const cast=(castId,grantId,opts)=>f.run('cast_spell',{actorId,castId,grantId,level:1,...opts});
  const adjudication={confirmed:true,componentsAccounted:true,summary:'Table resolved the reaction',trigger:'incoming_hit'};
  await assert.rejects(cast('unconfirmed',limited,{}),/MANUAL_ADJUDICATION_REQUIRED/);
  await assert.rejects(cast('wrong-spell',limited,{adjudication:{...adjudication,trigger:'targeted_by_spell',triggeringSpellId:'other'}}),/SPELL_REACTION_TRIGGER_UNVERIFIED/);
  await assert.rejects(cast('double-charged',limited,{adjudication,slotPoolId:'one-slot'}),/CAST_DOES_NOT_USE_SLOT/);
  const free=await cast('free',limited,{adjudication});
  assert.deepEqual([free.outcome,free.adjudication.summary,free.costs[0].poolId],['adjudicated',adjudication.summary,'daily']);
  assert.equal((await f.record('one-slot')).data.current,2);
  await assert.rejects(cast('empty',limited,{adjudication}),/RESOURCE_EMPTY_OR_UNKNOWN/);
  await assert.rejects(cast('unworn',slot,{adjudication,slotPoolId:'one-slot'}),/SPELL_SOURCE_ITEM_UNAVAILABLE/);
  await f.equip('worn');
  const paid=await cast('slot',slot,{slotPoolId:'one-slot',adjudication:{...adjudication,trigger:'targeted_by_spell',triggeringSpellId:'incoming-spark'}});
  assert.equal(paid.costs[0].poolId,'one-slot');
  assert.deepEqual([(await f.record('one-slot')).data.current,(await f.record('daily')).data.current],[1,0]);
  assert.equal((await f.spells()).filter(r=>r.spellId===spellId).every(r=>r.requiresAdjudication),true);
});

test('empty spell storage loads only after a real long rest and the current holder spends its independent pool', async t=>{
  const f=await fixture(t,{gameSeconds:100}), featureId='storage-method',itemId='focus',poolId='cell',spellId='lamp';
  await f.run('define_resource',{actorId,poolId,name:'Independent cell',source,type:'ordinary',current:0,maximum:2,recovery:'none'});
  await f.run('define_spell',{actorId,spellId,name:'Lamp',source,baseLevel:1,concentration:true,timing:'action',ritual:false,resolution:'manual',components:[]});
  await f.run('define_feature',{actorId,featureId,name:'Storage method',kind:'class',source,description:'Reviewed synthetic item storage',
    references:[{id:poolId,kind:'resource_pool'}],preparedItemRule:{poolId,ability:'INT',capacity:2,eligibleSpellIds:[spellId],eligibleItemIds:[itemId]}});
  await f.equip('worn');
  const load=restReportId=>f.run('load_prepared_spell_item',{actorId,featureId,itemId,spellId,restReportId,confirmed:true});
  const activate=castId=>f.run('use_prepared_spell_item',{actorId,creatorId:actorId,featureId,itemId,castId,
    replaceConcentration:true,adjudication:'Synthetic source effect resolved'});
  await assert.rejects(activate('empty-effect'),/NO_STORED_SPELL_EFFECT/);
  await assert.rejects(load('missing-rest'));
  const rest=await f.run('complete_rest',{actorId,kind:'long_rest',elapsedSeconds:28800,hitDice:[],confirmed:true});
  assert.equal((await f.record(poolId)).data.current,0);
  await load(rest.reportId);
  assert.equal((await f.record(poolId)).data.current,2);
  await assert.rejects(load(rest.reportId),/FRESH_LONG_REST/);
  const first=await activate('actual-effect');
  assert.deepEqual([first.sourceCasterId,first.holderId,first.sourceModifier,first.remaining],[actorId,actorId,0,1]);
  assert.equal((await f.record(actorId)).data.concentrationCastId,'actual-effect');
  const ally='storage-user';
  await f.run('define_actor',{actorId:ally,name:'Second holder',role:'player',rulesProfile:'revised_2024',level:1,
    gameSeconds:20,abilities:{STR:10,DEX:10,CON:10,INT:18,WIS:10,CHA:10}});
  await f.equip('packed');
  await f.run('transfer_item',{itemId,quantity:1,splitId:null,source,reason:'Fixture custody',
    destination:{type:'party_actor',actorId:ally,recipientName:null,recipientId:null,locationId:null}});
  await f.equip('worn');
  await assert.rejects(activate('wrong-holder'),/STORAGE_HOST_NOT_HELD/);
  const transferred=await f.run('use_prepared_spell_item',{actorId:ally,creatorId:actorId,featureId,itemId,
    castId:'second-effect',adjudication:'The current holder activated the reviewed effect.'});
  assert.deepEqual([transferred.holderId,transferred.sourceModifier,transferred.remaining],[ally,0,0]);
  assert.equal((await f.record(ally)).data.concentrationCastId,'second-effect');
  assert.equal((await f.record(poolId)).data.current,0);
  assert.equal((await f.record(itemId)).data.preparedSpellFeatureId,undefined);
  await assert.rejects(load(rest.reportId),/FRESH_LONG_REST/);
  await assert.rejects(activate('no-third-effect'),/NO_STORED_SPELL_EFFECT/);
});

test('reviewed formulas bind one physical target and honor separate ordinary and armor-only capacities',async t=>{
  const f=await fixture(t,{gameSeconds:5}), featureId='formula-set';
  for(const itemId of ['frame','unreviewed','spare'])
    await f.run('acquire_item',{itemId,definitionId:'fixture-definition',ownerId:actorId,quantity:1,source});
  const formulas=['Lens','Prism','Spindle','Dormant coil'];
  const target=(itemId,part)=>({itemId,part,formulas,source,nonmagicalConfirmed:true});
  await f.run('define_feature',{actorId,featureId,name:'Reviewed formulas',kind:'class',description:'Test allocation ledger',source,references:[],
    infusionRule:{knownFormulas:formulas,ordinaryLimit:1,armorOnlyLimit:1,armorItemIds:['frame'],
      eligibleTargets:[target('focus','whole'),target('frame','helmet'),target('frame','boots'),target('spare','whole')]}});
  const rest=await f.run('complete_rest',{actorId,kind:'long_rest',elapsedSeconds:28800,hitDice:[],confirmed:true});
  const assign=(itemId,part,formula,allocation,operation='assign')=>f.run('record_reviewed_infusion',{actorId,featureId,itemId,part,formula,allocation,operation,
    restReportId:rest.reportId,touchConfirmed:true,existingAssignmentsConfirmed:true,effectAdjudication:'Recorded the reviewed fixture effect.'});
  await assert.rejects(assign('unreviewed','whole','Lens','ordinary'),/INFUSION_TARGET_INELIGIBLE/);
  await assert.rejects(assign('focus','whole','Unknown design','ordinary'),/INFUSION_FORMULA_UNKNOWN/);
  await assign('focus','whole','Lens','ordinary');
  await assert.rejects(assign('spare','whole','Prism','ordinary'),/INFUSION_CAPACITY_EXCEEDED/);
  await assert.rejects(assign('spare','whole','Prism','armor_only'),/INFUSION_TARGET_INELIGIBLE/);
  await assert.rejects(assign('frame','helmet','Lens','armor_only'),/INFUSION_ALREADY_ASSIGNED/);
  await assign('frame','helmet','Prism','armor_only');
  await assert.rejects(assign('frame','helmet','Spindle','armor_only'),/INFUSION_ALREADY_ASSIGNED/);
  await assert.rejects(assign('frame','boots','Spindle','armor_only'),/INFUSION_CAPACITY_EXCEEDED/);
  await assign('frame','helmet','Prism','armor_only','end');
  const last=await assign('frame','boots','Spindle','armor_only');
  assert.deepEqual(last.active.map(r=>r.part),['whole','boots']);
  assert.deepEqual([(await f.record('focus')).data.quantity,(await f.record('frame')).data.quantity],[1,1]);
  assert.equal((await f.record('frame')).data.attuned,false);
});
