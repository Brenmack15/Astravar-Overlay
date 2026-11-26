import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { CloudService } from './cloud-service.mjs';
import { CloudPlan } from './cloud-plan.mjs';
import { SqliteAdapter } from './sqlite-adapter.mjs';
import { MECHANIC_KINDS,MECHANIC_OPERATIONS,mechanicActorSheet } from './cloud-mechanics.mjs';
import { RECORD_KINDS,RECORD_OPERATIONS } from './cloud-records.mjs';

const schema=await readFile(new URL('./schema.sql',import.meta.url),'utf8');
const ownerId='synthetic-owner',cid='qa-rules';
const owner={ownerId,role:'owner',credentialId:'sites-owner'};
const dm={ownerId,role:'connector',credentialId:'sites-mcp'};
const base={actorId:'actor-one',name:'Fictional engineer',role:'player',rulesProfile:'revised_2024',level:12,
  abilities:{STR:8,DEX:14,CON:14,INT:20,WIS:11,CHA:10},baseAc:16,maxHp:60,currentHp:42,temporaryHp:0,gameSeconds:100,attacksPerAction:2};
async function setup(t){
  const db=new SqliteAdapter();t.after(()=>db.close());db.exec(schema);
  const service=new CloudService(db,{ownerId,kinds:{...MECHANIC_KINDS,...RECORD_KINDS},operations:{...MECHANIC_OPERATIONS,...RECORD_OPERATIONS},isolated:true});
  await service.createIsolated(owner,cid);
  const cats=['character','resources','rolls','spells','combat','inventory'];
  await service.setGrant(owner,cid,'sites-mcp',{scopes:cats.flatMap(k=>[k+'.read',k+'.write']),auto:cats.map(k=>k+'.write'),actors:['*'],expiresAt:Date.now()+3600000,revoked:false,expectedGrantRevision:0});
  let count=0;
  async function command(operation,args){
    const expectedRevision=(await service.continuity(cid)).revision;
    const cmd={version:1,commandId:'fixture-cmd-'+(++count),epoch:1,expectedRevision,operation,args};
    const result=await service.submit(dm,cid,cmd);
    assert.equal(result.status,'COMMITTED');
    assert.deepEqual((await service.receipt(dm,cid,cmd.commandId)).receipt,result.receipt);
    return {command:cmd,out:result.receipt.outcome,result};
  }
  const read=async id=>(await service.state(dm,cid,{id})).records[0];
  const defined=async(values={})=>command('define_actor',{...base,...values});
  const pool=async(poolId,type,current,maximum,recovery,extra={})=>command('define_resource',{actorId:base.actorId,poolId,name:poolId,type,current,maximum,recovery,source:'fixture configuration',...extra});
  const effect=async(effectId,target,key,operation,extra={})=>command('define_effect',{actorId:base.actorId,effectId,name:effectId,source:'reviewed fixture effect',target,key,operation,...extra});
  const sheet=async(id=base.actorId)=>mechanicActorSheet(new CloudPlan(service,service.key(cid),await service.grantForSubject(cid,'sites-mcp'),{commandId:'read-only'},Date.now()),id);
  return {db,service,command,read,defined,pool,effect,sheet};
}
const who=base.actorId;
const dice=(...faces)=>({origin:'player_physical',faces});
const check=(kind,key,mode='normal')=>({kind,key,mode});
const component=(id,type,num,sides,flat,extra={})=>({id,name:id,type,dice:num,sides,flat,...extra});

test('typed effects preserve permanent abilities, passive scope, stacking and original equipped item',async t=>{
  const f=await setup(t);await f.defined();
  await f.command('define_item_definition',{definitionId:'stone-def',name:'Test stone',category:'stone',description:'Synthetic calibration stone',rarity:null,stackable:false,wearable:true,handheld:false,attunement:'none',source:'fixture'});
  await f.command('acquire_item',{itemId:'stone-one',definitionId:'stone-def',ownerId:who,quantity:1,source:'fixture'});
  await f.effect('stone-int','ability_score','INT','add',{amount:1,sourceItemId:'stone-one'});
  assert.equal((await f.sheet()).abilities.INT.effective,20);
  await f.command('equip_item',{itemId:'stone-one',equipment:'worn',source:'fixture'});
  assert.equal((await f.sheet()).abilities.INT.effective,21);
  await f.command('equip_item',{itemId:'stone-one',equipment:'packed',source:'fixture'});
  assert.deepEqual([(await f.sheet()).abilities.INT.effective,(await f.read(who)).data.abilities.INT],[20,20]);
  for(const [skillId,ability,rank] of [['perception','WIS',1],['investigation','INT',2],['insight','WIS',2]])
    await f.command('set_skill_training',{actorId:who,skillId,ability,rank,name:skillId});
  await f.effect('skill-benefit','skill','all','add',{amount:1,stackingKey:'same-item'});
  await f.effect('save-benefit','save','all','add',{amount:1,stackingKey:'same-item'});
  await f.effect('init-benefit','initiative','all','add',{amount:1,stackingKey:'same-item'});
  await f.effect('passive-watch','passive','perception','add',{amount:5});
  await f.effect('passive-study','passive','investigation','add',{amount:5});
  const s=await f.sheet();
  assert.deepEqual(['perception','investigation','insight'].map(k=>s.skills[k].value),[5,14,9]);
  assert.deepEqual(['perception','investigation','insight'].map(k=>s.passives[k].value),[20,29,19]);
  assert.equal(s.initiative.value,3);assert.equal(s.saves.DEX.value,3);
  await f.effect('dex-edge','roll_edge','skill:perception','advantage');
  assert.equal((await f.sheet()).passives.perception.value,25);
  await f.effect('dex-opposite','roll_edge','skill:perception','disadvantage');
  assert.equal((await f.sheet()).passives.perception.value,20);
});

test('timed form spends one deployment; unknown ordinary HP stays independent of Integrity; overflow and retries',async t=>{
  const f=await setup(t);await f.defined({currentHp:60,speeds:{walk:5,fly:60}});
  await f.pool('deploy','charge',1,1,'long_rest');await f.pool('shell','integrity',75,75,'none');await f.pool('roar','charge',0,1,'deployment');
  await f.pool('blink','charge',3,3,'long_rest');
  await f.command('define_form',{actorId:who,formId:'siege',name:'Siege shell',source:'fixture',size:'large',grounded:true,abilityOverrides:{STR:20},speeds:{walk:40},
    baseAc:18,activationSeconds:60,durationSeconds:3600,timing:'other',deploymentPoolId:'deploy',integrityPoolId:'shell',resetPoolId:'roar'});
  const transform={actorId:who,formId:'siege',elapsedSeconds:60,uninterrupted:true};
  await assert.rejects(f.command('change_form',{...transform,elapsedSeconds:59}),/UNINTERRUPTED/);
  assert.equal((await f.read('deploy')).data.current,1);
  await f.command('change_form',transform);
  const s=await f.sheet();assert.equal(s.abilities.STR.effective,20);assert.equal(s.abilities.STR.base,8);assert.equal(s.ac.value,18);assert.equal(s.movement.fly,0);
  assert.equal((await f.read('roar')).data.current,1);assert.equal((await f.read('blink')).data.current,3);
  await f.command('apply_hp_change',{actorId:who,kind:'damage',amount:50});
  assert.equal((await f.read('shell')).data.current,25);assert.equal((await f.read(who)).data.currentHp,60);
  const dam=await f.command('apply_hp_change',{actorId:who,kind:'damage',amount:30});
  assert.deepEqual([dam.out.absorbedByIntegrity,dam.out.afterHp,dam.out.activeFormId],[25,55,null]);
  assert.equal((await f.service.submit(dm,cid,dam.command)).receipt.transactionId,dam.result.receipt.transactionId);
  assert.equal((await f.read(who)).data.currentHp,55);assert.equal((await f.sheet()).movement.fly,60);
  await assert.rejects(f.command('change_form',transform),/FORM_INTEGRITY_UNAVAILABLE/);
});

test('recorded checks and reusable presets retain signed modifiers and all real faces; validation is atomic',async t=>{
  const f=await setup(t);await f.defined();
  await f.effect('precision','roll_edge','save:DEX','keep_highest_three');
  const entries=[check('save','DEX'),check('skill','arcana'),{kind:'custom',key:'sample',name:'Sample',mode:'normal',dice:2,sides:6,flat:-3}];
  await f.command('save_roll_preset',{actorId:who,presetId:'test-preset',name:'Test batch',update:false,entries});
  assert.equal((await f.service.state(dm,cid,{kind:'roll'})).records.length,0);
  await f.command('set_actor_ability',{actorId:who,ability:'INT',score:22});
  const submitted={actorId:who,presetId:'test-preset',batchId:'batch-one',rolls:[
    {rollId:'r1',evidence:dice(4,20,13)},{rollId:'r2',evidence:dice(14)},{rollId:'r3',evidence:dice(1,1)}]};
  const done=await f.command('execute_roll_preset',submitted);
  assert.deepEqual(done.out.results.map(r=>r.total),[22,20,-1]);
  assert.deepEqual(done.out.results.map(r=>r.natural),[20,14,null]);
  assert.deepEqual(done.out.results[0].faces.map(v=>v.kept),[false,true,false]);
  assert.match(done.out.results[0].text,/Nat 20/);assert.doesNotMatch(done.out.results[1].text,/Nat 20/);
  const fail={...submitted,batchId:'bad-batch',rolls:[{rollId:'new1',evidence:dice(1,2,3)},{rollId:'new2',evidence:dice(21)},{rollId:'new3',evidence:dice(3,4)}]};
  await assert.rejects(f.command('execute_roll_preset',fail),/INVALID_RECORDED_FACES/);
  assert.equal(await f.read('new1'),undefined);
  assert.equal((await f.service.state(dm,cid,{kind:'roll'})).records.length,3);
});

test('short/long rest report contains actual dice, HP and pool deltas; separate dawn and lifetime uses survive',async t=>{
  const f=await setup(t); await f.defined({currentHp:53});
  await f.pool('d8','hit_die',12,12,'edition_hit_dice',{dieSides:8});
  await f.pool('d6','hit_die',2,3,'edition_hit_dice',{dieSides:6});
  await f.pool('short','charge',0,4,'short_rest',{recoveryAmount:1});
  await f.pool('long','charge',1,3,'long_rest');
  await f.pool('dawn','charge',0,1,'dawn');await f.pool('once','lifetime',2,3,'none');
  await f.pool('mystery','ordinary',null,5,'long_rest');await f.pool('slot2','spell_slot',0,2,'long_rest',{slotLevel:2});
  const short={actorId:who,kind:'short_rest',elapsedSeconds:3600,confirmed:true,hitDice:[{poolId:'d8',origin:'delegated',faces:[3,7,2]}]};
  await assert.rejects(f.command('complete_rest',{...short,elapsedSeconds:12}),/REST_INELIGIBLE/);
  const r=await f.command('complete_rest',short);
  assert.deepEqual(r.out.hitDice.map(v=>[v.face,v.modifier,v.actual]),[[3,2,5],[7,2,2],[2,2,0]]);
  assert.deepEqual([r.out.beforeHp,r.out.afterHp,r.out.actualHealing],[53,60,7]);
  assert.equal((await f.read('d8')).data.current,9);assert.equal((await f.read('short')).data.current,1);
  assert.equal((await f.read('long')).data.current,1);
  assert.deepEqual((await f.read(r.out.reportId)).data.hitDice,r.out.hitDice);
  await f.service.submit(dm,cid,r.command);
  assert.equal((await f.read('short')).data.current,1);
  const z=await f.command('complete_rest',{...short,hitDice:[]});assert.equal(z.out.hitDice.length,0);
  const lr=await f.command('complete_rest',{actorId:who,kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[]});
  assert.deepEqual(await Promise.all(['d8','d6','short','long','dawn','once','mystery','slot2'].map(async id=>(await f.read(id)).data.current)),[12,3,3,3,0,2,null,2]);
  assert.ok(lr.out.unresolved.some(v=>v.poolId==='mystery'));
});
test('legacy multiclass Hit Dice restoration requires bounded allocation',async t=>{
  const f=await setup(t);await f.defined({rulesProfile:'legacy_2014'});await f.pool('p1','hit_die',0,3,'edition_hit_dice',{dieSides:6});await f.pool('p2','hit_die',0,3,'edition_hit_dice',{dieSides:10});
  const rest={actorId:who,kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[]};
  await assert.rejects(f.command('complete_rest',rest),/CHOOSE_HIT_DICE_RESTORATION/);
  await f.command('complete_rest',{...rest,restoreHitDice:[{poolId:'p2',amount:3}]});
  assert.deepEqual([(await f.read('p1')).data.current,(await f.read('p2')).data.current],[0,3]);
});
test('families retain independent variants, attack-only and combined attempts preserve costs and pending targets',async t=>{
  const f=await setup(t);await f.defined();await f.pool('uses','charge',2,2,'long_rest');
  const parts=[component('base','force',1,12,5),component('payload','cold',1,8,0,{variants:['cold','fire']})];
  const setupAttack=id=>f.command('define_attack',{actorId:who,attackId:id,name:id,source:'fixture',timing:'attack',delivery:'ranged',resolution:'attack',fixedHit:9,components:parts,cost:{poolId:'uses',amount:1}});
  await setupAttack('first');await setupAttack('second');
  await f.command('select_damage_variant',{actorId:who,attackId:'first',componentId:'payload',type:'fire'});
  assert.deepEqual((await f.read('second')).data.selections,[]);
  const hit=await f.command('resolve_attack',{actorId:who,attackId:'first',attemptId:'attempt-one',mode:'advantage',hit:dice(3,20),includeDamage:false});
  assert.equal(hit.out.hit.total,29);assert.equal(hit.out.damage,null);assert.equal(hit.out.outcome,'pending');
  const follow=await f.command('roll_attack_damage',{actorId:who,attemptId:'attempt-one',damage:[{id:'base',evidence:dice(6,7)},{id:'payload',evidence:dice(2,8)}]});
  assert.deepEqual(follow.out.damage.map(d=>[d.type,d.total]),[['force',18],['fire',10]]);
  assert.equal((await f.read('uses')).data.current,1);
  await assert.rejects(f.command('roll_attack_damage',{actorId:who,attemptId:'attempt-one',damage:[]}),/ALREADY_RECORDED/);
  const missed=await f.command('resolve_attack',{actorId:who,attackId:'second',attemptId:'attempt-two',mode:'normal',hit:dice(1),includeDamage:true,
    damage:[{id:'base',evidence:dice(10)},{id:'payload',evidence:dice(3)}]});
  assert.equal(missed.out.outcome,'miss');assert.equal(missed.out.appliedDamage,null);
  assert.equal(missed.out.damage[1].type,'cold');assert.equal((await f.read('uses')).data.current,0);
});
test('upcast commits only chosen slot and grant; independent cast rolls preserve original costs and concentration',async t=>{
  const f=await setup(t);await f.defined();await f.pool('slot1','spell_slot',2,2,'long_rest',{slotLevel:1});await f.pool('slot3','spell_slot',2,2,'long_rest',{slotLevel:3});
  await f.command('define_spell',{actorId:who,spellId:'binding-spell',name:'Test binding',source:'synthetic',baseLevel:1,concentration:true,ritual:false,
    resolution:'attack',timing:'action',components:[component('a','force',1,6,2,{extraDicePerLevel:1})]});
  await f.command('grant_spell',{actorId:who,spellId:'binding-spell',grantId:'g1',source:'reviewed test grant',route:'slot',levels:[1,2,3],preparation:'known',ability:'INT'});
  const cast={actorId:who,grantId:'g1',castId:'cast1',level:3,slotPoolId:'slot3'};
  await assert.rejects(f.command('cast_spell',cast),/CASTING_ROUTE_UNAVAILABLE/);
  await f.command('prepare_spell',{actorId:who,grantId:'g1',prepared:true});
  await assert.rejects(f.command('cast_spell',{...cast,slotPoolId:'slot1'}),/MATCHING_SLOT_REQUIRED/);
  const result=await f.command('cast_spell',cast);assert.equal(result.out.level,3);
  assert.equal((await f.read('slot1')).data.current,2);assert.equal((await f.read('slot3')).data.current,1);
  assert.equal((await f.read(who)).data.concentrationCastId,'cast1');
  const rolled=await f.command('link_cast_roll',{actorId:who,castId:'cast1',mode:'normal',hit:dice(15),damage:[{id:'a',evidence:dice(1,5,6)}]});
  assert.deepEqual([rolled.out.hit.total,rolled.out.damage[0].total,rolled.out.outcome],[24,14,'pending']);
  await assert.rejects(f.command('link_cast_roll',{actorId:who,castId:'cast1',damage:[{id:'a',evidence:dice(6,6,6)}]}),/CAST_DAMAGE_UNAVAILABLE/);
  await assert.rejects(f.command('cast_spell',{...cast,castId:'cast2'}),/CONCENTRATION_DECISION_REQUIRED/);
  assert.equal((await f.read('slot3')).data.current,1);
});
test('encounter initiatives are actual roll references; extra attacks have a shared budget and no free turn',async t=>{
  const f=await setup(t);await f.defined();await f.defined({actorId:'actor-two',name:'Fixture opponent',role:'creature',proficiency:3});
  for(const [id,face] of [[who,16],['actor-two',4]])await f.command('record_check',{actorId:id,rollId:'initiative-'+id,check:check('initiative','initiative'),evidence:dice(face)});
  const started=await f.command('start_encounter',{encounterId:'combat-one',name:'Fixture encounter',participants:[who,'actor-two'].map(actorId=>({actorId,initiativeRollId:'initiative-'+actorId}))});
  assert.equal(started.out.currentActorId,who);
  await f.command('define_attack',{actorId:who,attackId:'unarmed',name:'Fixture attack',source:'fixture',timing:'attack',delivery:'melee',resolution:'attack',ability:'STR',proficient:true,components:[component('impact','bludgeoning',0,4,1)]});
  const attack={actorId:who,attackId:'unarmed',mode:'normal',hit:dice(15),includeDamage:false};
  await f.command('resolve_attack',{...attack,attemptId:'one'});
  await f.command('resolve_attack',{...attack,attemptId:'two'});
  await assert.rejects(f.command('resolve_attack',{...attack,attemptId:'three'}),/ACTION_UNAVAILABLE/);
  await f.command('advance_encounter_turn',{encounterId:'combat-one',currentActorId:who});
  await assert.rejects(f.command('resolve_attack',{...attack,attemptId:'three'}),/TURN_UNAVAILABLE/);
  await f.command('advance_encounter_turn',{encounterId:'combat-one',currentActorId:'actor-two'});
  await f.command('resolve_attack',{...attack,attemptId:'three'});
  await f.command('end_encounter',{encounterId:'combat-one',currentActorId:who,outcome:'synthetic fixture complete'});
  assert.equal((await f.read(who)).data.encounterId,null);
});
