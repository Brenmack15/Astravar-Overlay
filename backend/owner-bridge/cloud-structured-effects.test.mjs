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
  const run=async(operation,args)=>{
    const command={version:1,epoch:1,commandId:crypto.randomUUID(),operation,args,expectedRevision:(await service.continuity(continuity)).revision};
    const result=await submit(command);
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
