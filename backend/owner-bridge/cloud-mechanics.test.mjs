import { defineFrameFixture,defineMorphFixture,wornFixture } from './fixtures/representative-forms.mjs';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { test } from 'node:test';
import { CloudService } from './cloud-service.mjs';
import { cloudParity } from './fixtures/cloud-parity.mjs';
import { CloudPlan } from './cloud-plan.mjs';
import { SqliteAdapter } from './sqlite-adapter.mjs';
import { MECHANIC_KINDS,MECHANIC_OPERATIONS,mechanicActorSheet,mechanicFormPreview } from './cloud-mechanics.mjs';
import { RECORD_KINDS,RECORD_OPERATIONS } from './cloud-records.mjs';

const schema=await readFile(new URL('./schema.sql',import.meta.url),'utf8');
const ownerId='synthetic-owner',cid='qa-rules';
const owner={ownerId,role:'owner',credentialId:'sites-owner'};
const dm={ownerId,role:'connector',credentialId:'sites-mcp'};
const base={actorId:'actor-one',name:'Fictional engineer',role:'player',rulesProfile:'revised_2024',level:12,
  abilities:{STR:8,DEX:14,CON:14,INT:20,WIS:11,CHA:10},baseAc:16,maxHp:60,currentHp:42,temporaryHp:0,gameSeconds:100,attacksPerAction:2};
async function setup(t,path=':memory:'){
  let activePath=path,db=new SqliteAdapter(path);t.after(()=>db.close());db.exec(schema);
  const createService=()=>new CloudService(db,{ownerId,kinds:{...MECHANIC_KINDS,...RECORD_KINDS},operations:{...MECHANIC_OPERATIONS,...RECORD_OPERATIONS},isolated:true});
  let service=createService();
  await service.createIsolated(owner,cid);
  const cats=['character','resources','rolls','spells','combat','inventory'];
  await service.setGrant(owner,cid,'sites-mcp',{scopes:cats.flatMap(k=>[k+'.read',k+'.write']),auto:cats.map(k=>k+'.write'),actors:['*'],expiresAt:Date.now()+3600000,revoked:false,expectedGrantRevision:0});
  const paritySubmit=cloudParity(()=>db,ownerId,cid);
  let count=0;
  async function command(operation,args){
    const expectedRevision=(await service.continuity(cid)).revision;
    const cmd={version:1,commandId:'fixture-cmd-'+(++count),epoch:1,expectedRevision,operation,args};
    const result=await paritySubmit(cmd);
    assert.equal(result.status,'COMMITTED');
    assert.deepEqual((await service.receipt(dm,cid,cmd.commandId)).receipt,result.receipt);
    return {command:cmd,out:result.receipt.outcome,result};
  }
  const read=async id=>(await service.state(dm,cid,{id})).records[0];
  const defined=async(values={})=>command('define_actor',{...base,...values});
  const pool=async(poolId,type,current,maximum,recovery,extra={})=>command('define_resource',{actorId:base.actorId,poolId,name:poolId,type,current,maximum,recovery,source:'fixture configuration',...extra});
  const effect=async(effectId,target,key,operation,extra={})=>command('define_effect',{actorId:base.actorId,effectId,name:effectId,source:'reviewed fixture effect',target,key,operation,...extra});
  const sheet=async(id=base.actorId)=>mechanicActorSheet(new CloudPlan(service,service.key(cid),await service.grantForSubject(cid,'sites-mcp'),{commandId:'read-only'},Date.now()),id);
  const preview=async formId=>mechanicFormPreview(new CloudPlan(service,service.key(cid),await service.grantForSubject(cid,'sites-mcp'),{commandId:'preview-only'},Date.now()),base.actorId,formId);
  function reopen(nextPath=activePath){assert.notEqual(nextPath,':memory:');db.close();activePath=nextPath;db=new SqliteAdapter(activePath);service=createService();}
  return {get db(){return db;},get service(){return service;},reopen,command,retry:paritySubmit,read,defined,pool,effect,sheet,preview};
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
  assert.equal((await f.retry(dam.command)).receipt.transactionId,dam.result.receipt.transactionId);
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
  const authority=await f.service.continuity(cid);
  const delegation=await f.service.submit(dm,cid,{version:1,commandId:'approve-hit-dice',epoch:1,expectedRevision:authority.revision,operation:'set_dice_delegation',args:{actorId:who,category:'hit_dice',enabled:true,source:'fixture table agreement'}});
  assert.equal(delegation.status,'PENDING_APPROVAL');await f.service.review(owner,cid,delegation.commandId,delegation.digest,'verify');
  await assert.rejects(f.command('record_check',{actorId:who,rollId:'unapproved-skill',check:check('skill','history'),evidence:{origin:'delegated',faces:[15]}}),/DICE_DELEGATION_REQUIRED/);
  const short={actorId:who,kind:'short_rest',elapsedSeconds:3600,confirmed:true,hitDice:[{poolId:'d8',origin:'delegated',faces:[3,7,2]}]};
  await assert.rejects(f.command('complete_rest',{...short,elapsedSeconds:12}),/REST_INELIGIBLE/);
  const r=await f.command('complete_rest',short);
  assert.deepEqual(r.out.hitDice.map(v=>[v.face,v.modifier,v.actual]),[[3,2,5],[7,2,2],[2,2,0]]);
  assert.deepEqual([r.out.beforeHp,r.out.afterHp,r.out.actualHealing],[53,60,7]);
  assert.equal((await f.read('d8')).data.current,9);assert.equal((await f.read('short')).data.current,1);
  assert.equal((await f.read('long')).data.current,1);
  assert.deepEqual((await f.read(r.out.reportId)).data.hitDice,r.out.hitDice);
  await f.retry(r.command);
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
  const hurt=await f.command('apply_hp_change',{actorId:who,kind:'damage',amount:26});
  assert.equal((await f.read(hurt.out.concentrationCheckId)).data.dc,13);
  await f.command('record_check',{actorId:who,rollId:'concentrate',check:check('save','CON'),evidence:dice(3),forConcentrationId:hurt.out.concentrationCheckId});
  const checkArgs={actorId:who,checkId:hurt.out.concentrationCheckId,rollId:'concentrate'};
  assert.equal((await f.command('resolve_concentration_check',checkArgs)).out.status,'ended');
  assert.equal((await f.read(who)).data.concentrationCastId,null);
  await assert.rejects(f.command('resolve_concentration_check',checkArgs),/CONCENTRATION_CHECK_UNAVAILABLE/);
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
  await assert.rejects(f.command('start_encounter',{encounterId:'combat-reused',name:'Different event',participants:[{actorId:who,initiativeRollId:'initiative-'+who}]}),/INITIATIVE_ALREADY_USED/);
  assert.equal(await f.read('combat-reused'),undefined);
  assert.equal((await f.read(who)).data.encounterId,null);
});

test('form-bound blink and selective fear use independent pools; unqualified destinations preserve charges',async t=>{
  const f=await setup(t);await f.defined();await f.defined({actorId:'listener',name:'Synthetic listener'});await f.defined({actorId:'bystander',name:'Synthetic bystander'});
  await f.pool('shared-blink','charge',3,3,'long_rest');await f.pool('once-roar','charge',0,1,'deployment');
  await f.command('define_form',{actorId:who,formId:'heavy-body',name:'Heavy configuration',source:'fixture',size:'large',grounded:true,timing:'other',activationSeconds:60,durationSeconds:3600,resetPoolId:'once-roar'});
  await f.command('define_action',{actorId:who,actionId:'blink-action',name:'Blink',source:'fixture',type:'teleport',timing:'bonus_action',range:30,cost:{poolId:'shared-blink',amount:1}});
  await f.command('define_action',{actorId:who,actionId:'fear-action',name:'Selective fear',source:'fixture',type:'frighten',timing:'bonus_action',range:30,saveDc:15,saveAbility:'WIS',durationSeconds:60,formId:'heavy-body',cost:{poolId:'once-roar',amount:1}});
  const blink={actorId:who,actionId:'blink-action',declared:true,useId:'blink-one',destination:{label:'stone floor',distanceFeet:30,unoccupied:true,visible:true,grounded:true,availableSize:'large',passengers:0,permittedByBarriers:true}};
  await f.command('use_action',blink);
  await f.command('change_form',{actorId:who,formId:'heavy-body',elapsedSeconds:60,uninterrupted:true});
  for(const invalid of [{distanceFeet:31},{passengers:1},{grounded:false},{availableSize:'tiny'},{permittedByBarriers:false}])
    await assert.rejects(f.command('use_action',{...blink,useId:'bad-blink',destination:{...blink.destination,...invalid}}));
  assert.equal((await f.read('shared-blink')).data.current,2);
  await f.command('use_action',{...blink,useId:'blink-two'});
  await f.command('use_action',{actorId:who,actionId:'fear-action',declared:true,useId:'fear',targets:[{actorId:'listener',hears:true,distanceFeet:25}]});
  assert.deepEqual((await f.read('listener')).data.conditions,[]);
  await f.command('record_check',{actorId:'listener',rollId:'wis-save',check:check('save','WIS'),evidence:dice(4),forUseId:'fear'});
  await f.command('resolve_action_save',{actorId:who,useId:'fear',targetActorId:'listener',rollId:'wis-save'});
  assert.deepEqual((await f.read('listener')).data.conditions,['frightened']);assert.deepEqual((await f.read('bystander')).data.conditions,[]);
  await f.command('advance_actor_time',{actorId:'listener',elapsedSeconds:60,confirmed:true});
  assert.deepEqual((await f.read('listener')).data.conditions,[]);
  await f.command('advance_actor_time',{actorId:who,elapsedSeconds:3600,confirmed:true});
  assert.equal((await f.read(who)).data.activeFormId,null);assert.equal((await f.read('shared-blink')).data.current,1);assert.equal((await f.read('once-roar')).data.current,0);
});
test('authorized interrupt preserves original faces, consumes reaction and pool once, and keeps actual result',async t=>{
  const f=await setup(t);await f.defined();await f.pool('insight-charge','charge',2,2,'long_rest');
  await f.command('define_action',{actorId:who,actionId:'assist-roll',name:'Assist',source:'fixture',type:'roll_bonus',timing:'reaction',bonusAbility:'INT',range:30,cost:{poolId:'insight-charge',amount:1}});
  await f.command('record_check',{actorId:who,rollId:'original',check:check('skill','arcana'),evidence:dice(4)});
  const use={actorId:who,actionId:'assist-roll',useId:'reaction-001',declared:true,reaction:{rollId:'original',targetActorId:who,distanceFeet:0,visible:true,windowOpen:true}};
  await f.command('use_action',use);
  const result=(await f.read('original')).data;
  assert.equal(result.originalTotal,9);assert.equal(result.total,14);assert.equal(result.faces[0].value,4);
  await assert.rejects(f.command('use_action',{...use,useId:'reaction-002'}),/ROLL_REACTION_INELIGIBLE/);
  assert.equal((await f.read('insight-charge')).data.current,1);
});

test('reviewed attack edits preserve form variants, previous attempts, component identity and resource links',async t=>{
  const f=await setup(t);await f.defined();
  await f.pool('pool-one','charge',3,3,'long_rest');
  await f.pool('pool-two','charge',4,4,'long_rest');
  const x={actorId:who,attackId:'reviewed',name:'Scope test',source:'synthetic workbench',
    timing:'attack',delivery:'ranged',resolution:'attack',fixedHit:3,range:40,longRange:80,
    components:[component('fixed','force',0,6,1),component('rider','cold',1,6,0,{variants:['cold','fire']})],
    cost:{poolId:'pool-one',amount:1}};
  await f.command('define_attack',x);
  await f.command('select_damage_variant',{actorId:who,attackId:x.attackId,componentId:'rider',type:'fire'});
  await f.command('resolve_attack',{actorId:who,attackId:x.attackId,attemptId:'before-edit',mode:'normal',hit:dice(16),includeDamage:true,
    damage:[{id:'fixed',evidence:dice()},{id:'rider',evidence:dice(4)}]});
  const before=await f.read('before-edit');
  const previous=await f.read(x.attackId),originalRevision=previous.revision;
  const next={...x,name:'Revised scope test',expectedAttackRevision:originalRevision,range:60,longRange:120,
    components:[x.components[1],x.components[0]],cost:{poolId:'pool-two',amount:2}};
  const edit=await f.command('update_attack',next);
  assert.equal(edit.out.attackRevision,originalRevision+1);
  let saved=await f.read(x.attackId);
  assert.equal(saved.title,'Revised scope test');assert.equal(saved.data.selections[0].type,'fire');
  assert.deepEqual(saved.data.components.map(c=>c.id),['rider','fixed']);
  assert.deepEqual(await f.read('before-edit'),before);
  assert.equal((await f.read('pool-one')).data.current,2);
  assert.equal((await f.read('pool-two')).data.current,4);
  const links=(await f.db.prepare('SELECT relation,to_id FROM cloud_links WHERE from_id=?').bind(x.attackId).all()).results;
  assert.ok(links.some(e=>e.relation==='uses_pool'&&e.to_id==='pool-two'));
  assert.ok(!links.some(e=>e.relation==='uses_pool'&&e.to_id==='pool-one'));
  await assert.rejects(f.command('update_attack',next),/ATTACK_REVISION_CHANGED/);
  const removal={...next,expectedAttackRevision:saved.revision,components:[component('rider','cold',1,6,0)]};
  await assert.rejects(f.command('update_attack',removal),/REVIEW_VARIANT_REMOVAL/);
  assert.deepEqual(await f.read(x.attackId),saved);
  const removed=await f.command('update_attack',{...removal,discardUnavailableSelections:true});
  assert.deepEqual(removed.out.removedSelections,saved.data.selections);
  saved=await f.read(x.attackId);assert.deepEqual(saved.data.selections,[]);
  const rider={id:'after-hit',trigger:'hit',effect:'Owner-resolved fixture condition',componentId:'rider'};
  await assert.rejects(f.command('update_attack',{...removal,expectedAttackRevision:saved.revision,riders:[rider]}),/RIDER_REQUIRES_REVIEWED_ADJUDICATION/);
  const {fixedHit,...manual}=removal;
  await f.command('update_attack',{...manual,expectedAttackRevision:saved.revision,resolution:'manual',riders:[rider]});
  assert.deepEqual((await f.read(x.attackId)).data.riders,[rider]);
});

test('target resolution validates linked saves, source defenses, pending damage and exact-once target HP',async t=>{
  const f=await setup(t);await f.defined();await f.defined({actorId:'listener',name:'Fictional listener',role:'creature',maxHp:30,currentHp:30,temporaryHp:2});
  await f.command('define_effect',{actorId:'listener',effectId:'ward',name:'Cold ward',source:'synthetic definition',target:'damage_defense',key:'cold',operation:'resistance'});
  await f.command('define_attack',{actorId:who,attackId:'cone',name:'Saving throw area',source:'fixture',timing:'action',delivery:'area',resolution:'save',saveAbility:'DEX',saveDc:15,saveSuccess:'half',components:[component('cold','cold',2,8,0)]});
  await f.command('resolve_attack',{actorId:who,attackId:'cone',attemptId:'area-hit',targets:['listener'],mode:'normal',includeDamage:false});
  const request={actorId:who,attemptId:'area-hit',targetActorId:'listener',saveRollId:'actual-save',source:'resolved fixture action'};
  await f.command('record_check',{actorId:'listener',rollId:'unrelated-save',check:check('save','DEX'),evidence:dice(19)});
  await assert.rejects(f.command('resolve_attack_target',{...request,saveRollId:'unrelated-save'}),/SAVE_ROLL_UNAVAILABLE/);
  await f.command('record_check',{actorId:'listener',rollId:'actual-save',check:check('save','DEX'),evidence:dice(19),forAttemptId:'area-hit'});
  await assert.rejects(f.command('resolve_attack_target',request),/ATTEMPT_DAMAGE_REQUIRED/);
  await f.command('roll_attack_damage',{actorId:who,attemptId:'area-hit',damage:[{id:'cold',evidence:dice(7,7)}]});
  const first=await f.command('resolve_attack_target',request);
  assert.deepEqual([first.out.decision,first.out.amount,first.out.hp.absorbedByTemporaryHp,first.out.hp.afterHp],['save_succeeded',3,2,29]);
  await f.retry(first.command);await assert.rejects(f.command('resolve_attack_target',request),/TARGET_UNDECLARED_OR_ALREADY_RESOLVED/);
  assert.equal((await f.read('listener')).data.currentHp,29);
});

test('mixed signed formulas and pending workshop context use real evidence and native three-face policy',async t=>{
  const f=await setup(t);await f.defined();
  const custom={kind:'custom',key:'formula',name:'Mixed dice',mode:'normal',terms:[{count:1,sides:8,sign:'add'},{count:1,sides:4,sign:'subtract'}],flat:3};
  const r=await f.command('record_check',{actorId:who,rollId:'mixed',check:custom,evidence:dice(6,2)});
  assert.equal(r.out.total,7);assert.deepEqual(r.out.faces.map(f=>f.sign),[1,-1]);
  const tagged={...check('skill','history','advantage'),contextTags:['pocket-workshop']};
  await assert.rejects(f.command('record_check',{actorId:who,rollId:'unverified',check:tagged,evidence:dice(6,12)}),/ROLL_CONTEXT_UNVERIFIED/);
  const revision=(await f.service.continuity(cid)).revision;
  const pending=await f.service.submit(dm,cid,{version:1,commandId:'context-confirm',epoch:1,expectedRevision:revision,operation:'set_roll_context',args:{actorId:who,tag:'pocket-workshop',active:true,source:'owner-reviewed access'}});
  assert.equal(pending.status,'PENDING_APPROVAL');await f.service.review(owner,cid,pending.commandId,pending.digest,'verify');
  await f.effect('three','roll_edge','skill:history','keep_highest_three',{contextTag:'pocket-workshop'});
  const s=await f.sheet();assert.equal(s.rollContexts['pocket-workshop'].rollModes['skill:history'].advantage.count,3);
  const actual=await f.command('record_check',{actorId:who,rollId:'verified',check:tagged,evidence:dice(6,12,9)});
  assert.deepEqual(actual.out.faces.map(f=>f.kept),[false,true,false]);
});

test('a pending batch reserves roll identity, requires later actual evidence and cannot resolve twice',async t=>{
  const f=await setup(t);await f.defined();
  const batch=await f.command('record_roll_batch',{actorId:who,batchId:'await-roll',entries:[{rollId:'future-die',check:check('ability','STR')}]});
  assert.equal(batch.out.status,'pending');assert.equal((await f.read('future-die')).data.status,'pending');
  await assert.rejects(f.command('record_check',{actorId:who,rollId:'future-die',check:check('ability','STR'),evidence:dice(1)}),/MECHANIC_ID_EXISTS/);
  const completion={actorId:who,batchId:'await-roll',rollId:'future-die',evidence:dice(17)};
  const final=await f.command('complete_pending_check',completion);
  assert.equal(final.out.result.total,16);assert.equal((await f.read('await-roll')).data.status,'resolved');
  await assert.rejects(f.command('complete_pending_check',completion),/ROLL_NOT_PENDING/);
  assert.equal((await f.service.receipt(dm,cid,batch.command.commandId)).receipt.outcome.status,'pending');
});

test('fixed and calculated DCs share targeting, while an existing attempt keeps its recorded DC after an ability update',async t=>{
  const f=await setup(t);await f.defined({proficiency:4});
  const {cloudActions}=await import('./cloud-actions-view.mjs');
  const x={actorId:who,attackId:'save-blast',name:'Practice wave',source:'synthetic review',
    timing:'action',delivery:'area',resolution:'save',saveAbility:'DEX',saveSuccess:'half',dcAbility:'INT',dcBase:8,dcProficient:true,dcBonus:1,components:[]};
  await f.command('define_attack',x);
  const project=async()=> (await cloudActions(f.service,dm,cid,who)).rows.find(r=>r.id===x.attackId);
  assert.equal((await project()).resolvedSaveDc,18);
  await assert.rejects(f.command('update_attack',{...x,expectedAttackRevision:1,saveDc:18}),/SAVE_FORMULA_REQUIRED/);
  const attempt={actorId:who,attemptId:'first-save-attempt',attackId:x.attackId,mode:'normal',includeDamage:false};
  await f.command('resolve_attack',attempt);
  assert.equal((await f.read(attempt.attemptId)).data.save.dc,18);
  await f.command('set_actor_ability',{actorId:who,ability:'INT',score:18});
  assert.equal((await project()).resolvedSaveDc,17);
  assert.equal((await f.read(attempt.attemptId)).data.save.dc,18);
  const {dcAbility,dcBase,dcProficient,dcBonus,...fixed}=x;
  await f.command('update_attack',{...fixed,expectedAttackRevision:1,saveDc:14});
  assert.equal((await project()).resolvedSaveDc,14);
});

test('profile and feature edits reference existing mechanics; progression advances once and preserves spent resources',async t=>{
  const f=await setup(t);await f.defined({level:4});await f.pool('dice-pool','hit_die',1,4,'edition_hit_dice',{dieSides:8});
  await f.command('set_actor_profile',{actorId:who,background:'Former artisan',source:'fixture interview'});
  await f.command('set_actor_training',{actorId:who,category:'save',key:'DEX',trained:true,source:'fixture training'});
  assert.equal((await f.sheet()).saves.DEX.value,4);
  await f.effect('passive-feat','passive','perception','add',{amount:5});
  await f.command('define_feature',{actorId:who,featureId:'watching',kind:'feat',name:'Alert eye',description:'Synthetic feat',source:'fixture',references:[{kind:'effect',id:'passive-feat'}]});
  assert.equal((await f.sheet()).passives.perception.value,15);
  const args={actorId:who,eventId:'level-event',fromLevel:4,toLevel:5,source:'confirmed fixture milestone',abilityGains:{STR:1},hitPointGain:5,capacities:[{poolId:'dice-pool',previous:4,maximum:5,granted:1}]};
  const promoted=await f.command('advance_actor_level',args);assert.equal(promoted.out.proficiency,3);assert.equal(promoted.out.hp,42);
  assert.deepEqual([(await f.read('dice-pool')).data.current,(await f.read('dice-pool')).data.maximum],[2,5]);
  await f.retry(promoted.command);await assert.rejects(f.command('advance_actor_level',{...args,eventId:'another-event'}),/ADVANCEMENT_LEVEL_CHANGED/);
  assert.equal((await f.read(who)).data.abilities.STR,9);assert.equal((await f.read(who)).data.biography.background.value,'Former artisan');
});

test('compound attack authorizes the configured families exactly once within its action window',async t=>{
  const f=await setup(t);await f.defined();
  for(const [name,type,diceCount,die] of [['beak','piercing',2,10],['claw','slashing',3,8]])
    await f.command('define_attack',{actorId:who,attackId:name,name:name,source:'synthetic chassis',timing:'attack',delivery:'melee',resolution:'attack',fixedHit:9,classification:'magical',components:[component('impact',type,diceCount,die,5)]});
  await f.command('define_action',{actorId:who,actionId:'both',name:'Compound strike',type:'multiattack',source:'fixture',timing:'action',attackSequence:['beak','claw']});
  await f.command('use_action',{actorId:who,actionId:'both',useId:'declared-both',declared:true});
  const common={actorId:who,actionUseId:'declared-both',mode:'normal',hit:dice(11),includeDamage:false};
  await f.command('resolve_attack',{...common,attackId:'claw',attemptId:'first-claw'});
  await assert.rejects(f.command('resolve_attack',{...common,attackId:'claw',attemptId:'second-claw'}),/MULTIATTACK_ATTACK_UNAVAILABLE/);
  await f.command('resolve_attack',{...common,attackId:'beak',attemptId:'single-beak'});
  assert.deepEqual((await f.read('declared-both')).data.remainingAttacks,[]);
  await assert.rejects(f.command('resolve_attack',{...common,attackId:'beak',attemptId:'second-beak'}),/MULTIATTACK_WINDOW_CLOSED/);
  await f.command('use_action',{actorId:who,actionId:'both',useId:'interrupted-compound',declared:true});
  await f.command('advance_actor_time',{actorId:who,elapsedSeconds:6,confirmed:true});
  await assert.rejects(f.command('resolve_attack',{...common,actionUseId:'interrupted-compound',attackId:'beak',attemptId:'expired-beak'}),/MULTIATTACK_WINDOW_CLOSED/);
});

test('a worn optional die substitution creates no roll face and recovers only on a separately confirmed dawn',async t=>{
  const f=await setup(t);await f.defined();await f.pool('dawn-use','charge',1,1,'dawn');
  await f.command('define_item_definition',{definitionId:'gear',name:'Test gear',category:'amulet',description:'Synthetic fixture',rarity:null,stackable:false,wearable:true,handheld:false,attunement:'none',source:'fixture'});
  await f.command('acquire_item',{itemId:'physical-gear',definitionId:'gear',ownerId:who,quantity:1,source:'fixture'});
  await f.command('define_action',{actorId:who,actionId:'fixed-rule',name:'Choose ten',type:'fixed_attack_die',timing:'other',source:'fixture',sourceItemId:'physical-gear',fixedDieValue:10,cost:{poolId:'dawn-use',amount:1}});
  await f.command('define_attack',{actorId:who,attackId:'weapon',name:'Test attack',source:'fixture',resolution:'attack',timing:'attack',delivery:'melee',fixedHit:10,components:[component('damage','piercing',1,4,0)]});
  const req={actorId:who,attackId:'weapon',attemptId:'substitution-test',substitutionId:'fixed-rule',mode:'normal',includeDamage:false};
  await assert.rejects(f.command('resolve_attack',req),/DIE_SUBSTITUTION_INELIGIBLE/);
  await f.command('equip_item',{itemId:'physical-gear',equipment:'worn',source:'fixture'});
  const result=await f.command('resolve_attack',req);
  assert.deepEqual([result.out.hit.total,result.out.hit.natural,result.out.hit.faces,result.out.critical],[20,null,[],false]);
  await f.retry(result.command);assert.equal((await f.read('dawn-use')).data.current,0);
  await f.command('complete_rest',{actorId:who,kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[]});
  assert.equal((await f.read('dawn-use')).data.current,0);
  const dawn={actorId:who,eventId:'dawn-event',gameSeconds:(await f.read(who)).data.gameSeconds,confirmed:true,source:'fictional observed dawn'};
  await f.command('complete_dawn_recovery',dawn);assert.equal((await f.read('dawn-use')).data.current,1);
  await assert.rejects(f.command('complete_dawn_recovery',{...dawn,eventId:'another-dawn'}),/DAWN_TIME_UNCONFIRMED_OR_RECORDED/);
});

test('three sourced forms and read-only previews keep one actor, shared resources and distinct physical equipment',async t=>{
  const f=await setup(t);await f.defined({role:'companion',proficiency:4,maxHp:154,currentHp:141,baseAc:19,speeds:{walk:30},senses:{darkvision:60},size:'large',creatureType:'dragon'});
  await f.pool('breath','ordinary',1,1,'none');await f.pool('strike','ordinary',2,3,'long_rest');
  await f.command('define_item_definition',{definitionId:'focus-def',name:'Focus',category:'focus',description:'Fictional test article',rarity:null,stackable:false,wearable:true,handheld:false,attunement:'none',source:'fixture'});
  for(const itemId of ['main-focus','spare-focus'])await f.command('acquire_item',{itemId,definitionId:'focus-def',ownerId:who,quantity:1,source:'fixture'});
  await f.command('equip_item',{itemId:'main-focus',equipment:'worn',source:'fixture'});
  await f.effect('focus-aid','skill','perception','add',{amount:1,sourceItemId:'main-focus'});
  const form={actorId:who,name:'Fictional form',source:'fixture',sourceItemId:'main-focus',timing:'action',activationSeconds:6,requiresWilling:true};
  for(const [id,size] of [['winged','large'],['guise','medium'],['hybrid','large']])await f.command('define_form',{...form,formId:id,size,...(id==='guise'?{senses:{darkvision:null},speeds:{fly:null}}:{})});
  const before=await f.service.continuity(cid);
  const original=await f.read(who);
  const preview=await f.preview('guise');assert.equal(preview.projection.senses.darkvision,null);assert.equal(preview.activeFormId,null);
  assert.deepEqual(await f.read(who),original);assert.equal((await f.service.continuity(cid)).revision,before.revision);
  await assert.rejects(f.command('change_form',{actorId:who,formId:'guise',elapsedSeconds:6}),/FORM_TRANSFORMATION_INELIGIBLE/);
  for(const id of ['winged','guise','hybrid'])await f.command('change_form',{actorId:who,formId:id,elapsedSeconds:6,willing:true});
  assert.equal((await f.read(who)).data.currentHp,141);assert.equal((await f.read('strike')).data.current,2);assert.equal((await f.read('breath')).data.current,1);
  assert.equal((await f.read('spare-focus')).data.equipment,'packed');assert.equal((await f.read('main-focus')).data.equipment,'worn');
  await f.command('define_form',{actorId:who,formId:'suppressed-gear',name:'Inactive equipment configuration',timing:'other',source:'fixture',activationSeconds:0,suppressedItemIds:['main-focus']});
  const p=await f.preview('suppressed-gear');assert.equal(p.projection.suppressedEffects.includes('focus-aid'),true);assert.equal((await f.sheet()).activeEffects.includes('focus-aid'),true);
  await f.command('change_form',{actorId:who,formId:'suppressed-gear',elapsedSeconds:0});assert.equal((await f.sheet()).suppressedEffects.includes('focus-aid'),true);
  await f.command('change_form',{actorId:who,formId:null,elapsedSeconds:0});assert.equal((await f.sheet()).activeEffects.includes('focus-aid'),true);
  assert.equal((await f.read('main-focus')).data.equipment,'worn');
});

test('spell-specific protection and configured scaling share cast identity and never debit for a linked outcome',async t=>{
  const f=await setup(t);await f.defined();await f.defined({actorId:'patient',name:'Test patient',role:'creature',maxHp:20,currentHp:13});
  await f.pool('higher-slot','spell_slot',2,2,'long_rest',{slotLevel:3});
  const spell={actorId:who,spellId:'synthetic-bolts',name:'Synthetic bolts',source:'fixture',baseLevel:1,concentration:false,ritual:false,resolution:'automatic',timing:'action',
    scaling:{targets:1,targetsPerLevel:1,durationSeconds:10,durationPerLevel:5,areaFeet:5,areaPerLevel:5,source:'fixture scaling'},components:[component('bolt','force',1,4,1)]};
  await f.command('define_spell',spell);
  await f.command('grant_spell',{actorId:who,spellId:spell.spellId,grantId:'bolts',source:'fixture grant',route:'slot',levels:[1,3],preparation:'prepared'});
  await f.command('define_effect',{actorId:'patient',effectId:'specific-ward',name:'Ward',source:'fixture protection',target:'spell_damage_immunity',key:spell.spellId,operation:'immunity'});
  const cast=await f.command('cast_spell',{actorId:who,grantId:'bolts',castId:'shielded-cast',level:3,slotPoolId:'higher-slot',targets:['patient']});
  assert.deepEqual(cast.out.scaling,{targets:3,durationSeconds:20,areaFeet:15,source:'fixture scaling'});
  await f.command('link_cast_roll',{actorId:who,castId:'shielded-cast',damage:[{id:'bolt',evidence:dice(3)}]});
  const target={actorId:who,castId:'shielded-cast',targetActorId:'patient',source:'confirmed fixture target'};
  const resolved=await f.command('resolve_cast_target',target);
  assert.equal(resolved.out.amount,0);assert.equal((await f.read('patient')).data.currentHp,13);
  assert.equal((await f.read('higher-slot')).data.current,1);await assert.rejects(f.command('resolve_cast_target',target),/TARGET_UNDECLARED_OR_ALREADY_RESOLVED/);
  await f.command('define_spell',{...spell,spellId:'repair-spell',name:'Repair fixture',baseLevel:0,components:[component('repair','healing',1,10,5)]});
  await f.command('grant_spell',{actorId:who,spellId:'repair-spell',grantId:'repair',source:'fixture grant',route:'at_will',levels:[0],preparation:'not_required'});
  await f.command('cast_spell',{actorId:who,grantId:'repair',castId:'repair-cast',level:0,targets:['patient']});
  await f.command('link_cast_roll',{actorId:who,castId:'repair-cast',damage:[{id:'repair',evidence:dice(9)}]});
  const healed=await f.command('resolve_cast_target',{...target,castId:'repair-cast'});
  assert.equal(healed.out.healing,14);assert.equal(healed.out.actualHealing,7);assert.equal((await f.read('patient')).data.currentHp,20);
});

test('correcting an effect retains one source; a spell edit leaves an already committed cast frozen',async t=>{
  const f=await setup(t);await f.defined();await f.effect('typed','initiative','all','add',{amount:1});
  await f.command('revise_effect',{actorId:who,effectId:'typed',name:'Corrected',source:'verified fixture source',target:'initiative',key:'all',operation:'add',amount:2});
  assert.deepEqual([(await f.sheet()).initiative.value,(await f.read(who)).data.effectIds],[4,['typed']]);
  const s={actorId:who,spellId:'timed',name:'Timed test',source:'fixture',baseLevel:0,concentration:true,ritual:false,resolution:'automatic',timing:'action',scaling:{durationSeconds:60,source:'fixture duration'},components:[component('roll','force',1,4,0)]};
  await f.command('define_spell',s);await f.command('grant_spell',{actorId:who,spellId:'timed',grantId:'timed-grant',source:'fixture',route:'at_will',levels:[0],preparation:'not_required'});
  await f.command('cast_spell',{actorId:who,grantId:'timed-grant',castId:'timed-cast',level:0});
  await f.command('revise_spell',{...s,components:[component('roll','force',3,4,0)],scaling:{durationSeconds:3600,source:'later fixture design'}});
  const first=await f.command('link_cast_roll',{actorId:who,castId:'timed-cast',damage:[{id:'roll',evidence:dice(2)}]});assert.equal(first.out.damage[0].total,2);
  await f.command('advance_actor_time',{actorId:who,elapsedSeconds:60,confirmed:true});assert.equal((await f.read(who)).data.concentrationCastId,null);
});

test('condition and equipment projections adjust check modes, movement and senses reversibly',async t=>{
  const f=await setup(t);await f.defined({speeds:{walk:30,fly:60}});
  await f.effect('vision','sense','darkvision','set',{amount:90});
  await f.effect('mobility','speed','walk','add',{amount:10});
  assert.equal((await f.sheet()).movement.walk,40);assert.equal((await f.sheet()).senses.darkvision,90);
  const cond=key=>({actorId:who,key,enabled:true,sourceId:'fixture'});
  await f.command('set_condition',cond('Poisoned'));
  const p=await f.sheet();assert.deepEqual(p.rollModes['skill:insight'].normal,{mode:'disadvantage',count:2});
  assert.equal(p.rollModes['skill:insight'].advantage.mode,'normal');
  await f.command('set_condition',cond('restrained'));
  assert.equal((await f.sheet()).movement.walk,0);assert.equal((await f.sheet()).movement.fly,0);
  await f.command('set_condition',{...cond('restrained'),enabled:false});assert.equal((await f.sheet()).movement.walk,40);
});

test('automatic save failure resolves from condition provenance without inventing a player-facing die',async t=>{
  const f=await setup(t);await f.defined();await f.defined({actorId:'affected',name:'Test target'});
  await f.command('set_condition',{actorId:'affected',key:'stunned',enabled:true,sourceId:'synthetic-hit'});
  await f.command('define_attack',{actorId:who,attackId:'saving-attack',name:'Test effect',source:'fixture',timing:'action',delivery:'area',resolution:'save',saveAbility:'DEX',saveDc:13,saveSuccess:'none',components:[component('force','force',0,4,4)]});
  await f.command('resolve_attack',{actorId:who,attackId:'saving-attack',attemptId:'no-roll',targets:['affected'],mode:'normal',includeDamage:true,damage:[{id:'force',evidence:dice()}]});
  await assert.rejects(f.command('record_check',{actorId:'affected',rollId:'forbidden',check:check('save','DEX'),evidence:dice(19),forAttemptId:'no-roll'}),/AUTOMATIC_SAVE_FAILURE/);
  const r=await f.command('resolve_attack_target',{actorId:who,attemptId:'no-roll',targetActorId:'affected',source:'verified automatic failure'});
  assert.equal(r.out.decision,'automatic_save_failure');assert.equal(r.out.saveRollId,null);assert.equal(r.out.amount,4);
});

test('registered updates, separate damage and shared pool expenditure are independently readable in both client directions',async t=>{
  const f=await setup(t);await f.defined();await f.pool('battery','ordinary',4,4,'none');
  const debit=await f.command('spend_resource',{actorId:who,poolId:'battery',amount:2});
  assert.equal(debit.out.after,2);assert.equal((await f.read('battery')).data.current,2);
  await f.effect('evidence-effect','ability_check','STR','add',{amount:2});
  const effect=await f.command('enable_effect',{actorId:who,effectId:'evidence-effect',enabled:false});
  assert.equal(effect.out.enabled,false);assert.equal((await f.read('evidence-effect')).data.enabled,false);
  const feature={actorId:who,featureId:'historical-feature',kind:'homebrew',name:'Fixture training',description:'Original fixture explanation',source:'fixture',references:[{id:'battery',kind:'resource_pool'}]};
  await f.command('define_feature',feature);
  const revision=await f.command('revise_feature',{...feature,description:'Revised source',references:[{id:'evidence-effect',kind:'effect'}]});
  assert.deepEqual(revision.out.references,[{id:'evidence-effect',kind:'effect'}]);assert.equal((await f.read('historical-feature')).data.description,'Revised source');
  const shape={actorId:who,formId:'reviewed-form',name:'Fixture form',source:'fixture',timing:'other',activationSeconds:0,speeds:{walk:40}};
  await f.command('define_form',shape);
  await f.command('revise_form',{...shape,speeds:{walk:35},description:'Reviewed body'});
  assert.equal((await f.preview('reviewed-form')).projection.movement.walk,35);assert.equal((await f.read(who)).data.activeFormId,null);
  await f.command('define_attack',{actorId:who,attackId:'standalone-weapon',name:'Fixture impact',source:'fixture',timing:'attack',delivery:'melee',resolution:'attack',fixedHit:4,cost:{poolId:'battery',amount:1},components:[component('impact','bludgeoning',1,6,2)]});
  const damage=await f.command('roll_damage_only',{actorId:who,attackId:'standalone-weapon',rollId:'standalone-damage',damage:[{id:'impact',evidence:dice(4)}]});
  assert.equal(damage.out.components[0].total,6);assert.equal((await f.read('standalone-damage')).data.appliedDamage,null);
  assert.equal((await f.read('battery')).data.current,2);assert.equal((await f.read(who)).data.currentHp,base.currentHp);
});

test('custom skill removal clears its pin, preserves ruled history and refuses removal of a standard skill',async t=>{
  const f=await setup(t);await f.defined();
  const skillId='fixture-tool',args={actorId:who,skillId};
  await f.command('set_skill_training',{...args,name:'Fixture tool',ability:'INT',rank:2});
  await f.command('set_skill_pin',{...args,pinned:true});
  assert.deepEqual((await f.sheet()).skillDefinitions[skillId],{name:'Fixture tool',ability:'INT',rank:2,standard:false,pinned:true});
  await f.command('record_check',{actorId:who,rollId:'tool-roll',check:{kind:'skill',key:skillId,mode:'normal'},evidence:{origin:'player_physical',faces:[11]}});
  const before=await f.read('tool-roll');
  await f.command('remove_custom_skill',args);
  assert.equal((await f.sheet()).skills[skillId],undefined);
  assert.deepEqual((await f.read(who)).data.pinnedSkillIds,[]);
  assert.deepEqual(await f.read('tool-roll'),before);
  await assert.rejects(f.command('remove_custom_skill',{actorId:who,skillId:'athletics'}),/ACTIVE_CUSTOM_SKILL_REQUIRED/);
});

test('separate active repair, disabled reconstruction and dormant transitions verify elapsed time, tool, actual cast and distinct recovery owner',async t=>{
  const f=await setup(t);await f.defined({role:'companion',proficiency:4,currentHp:44,temporaryHp:0,speeds:{walk:5,fly:60}});
  const operatorId='engineer-fixture';await f.defined({actorId:operatorId,name:'Caretaker'});
  await f.pool('active-repair','ordinary',1,1,'short_rest');await f.pool('reconstruction','ordinary',1,1,'long_rest',{recoveryActorId:operatorId});
  await f.command('define_item_definition',{definitionId:'kit-def',name:'Workshop implements',category:'tool',description:'Fictional kit',rarity:null,stackable:false,wearable:false,handheld:true,attunement:'none',source:'fixture'});
  await f.command('acquire_item',{itemId:'kit-instance',definitionId:'kit-def',ownerId:operatorId,quantity:1,source:'fixture'});
  await f.command('define_spell',{actorId:operatorId,spellId:'care-spell',name:'Repair cantrip',source:'fixture',baseLevel:0,concentration:false,ritual:false,timing:'other',resolution:'automatic',components:[]});
  await f.command('grant_spell',{actorId:operatorId,spellId:'care-spell',grantId:'care-grant',source:'fixture',route:'at_will',levels:[0],preparation:'not_required'});
  await f.command('define_care_protocol',{actorId:who,operatorId,protocolId:'care-protocol',spellId:'care-spell',toolDefinitionId:'kit-def',repairPoolId:'active-repair',reconstructionPoolId:'reconstruction',repairDice:2,repairSides:6,repairSeconds:60,reconstructionSeconds:600,dormancySeconds:60,source:'fixture care'});
  const cast=id=>f.command('cast_spell',{actorId:operatorId,grantId:'care-grant',castId:id,level:0,targets:[who]});
  await cast('active-care-1');const common={actorId:who,operatorId,protocolId:'care-protocol',uninterrupted:true};
  const repair={...common,castId:'active-care-1',kind:'repair',elapsedSeconds:60,dice:dice(4,3)};
  await assert.rejects(f.command('complete_actor_care',{...repair,elapsedSeconds:59}),/CARE_DURATION_INCOMPLETE/);
  const fixed=await f.command('complete_actor_care',repair);assert.deepEqual([fixed.out.afterHp,fixed.out.roll.faces.map(d=>d.value)],[51,[4,3]]);
  await assert.rejects(f.command('complete_actor_care',repair),/ACTUAL_UNUSED_CARE_CAST_REQUIRED/);
  await f.command('set_actor_dormancy',{...common,state:'dormant',elapsedSeconds:60});assert.equal((await f.sheet()).movement.fly,0);
  await assert.rejects(f.command('complete_rest',{actorId:who,kind:'short_rest',elapsedSeconds:3600,hitDice:[],confirmed:true}),/INACTIVE_ACTOR_CANNOT_REST/);
  await f.command('set_actor_dormancy',{...common,state:'active',elapsedSeconds:60});assert.equal((await f.read(who)).data.currentHp,51);assert.equal((await f.read('active-repair')).data.current,0);
  await f.command('apply_hp_change',{actorId:who,kind:'damage',amount:75});assert.equal((await f.read(who)).data.activityState,'disabled');
  await assert.rejects(f.command('apply_hp_change',{actorId:who,kind:'heal',amount:60}),/DISABLED_ACTOR_REQUIRES_RECONSTRUCTION/);
  await cast('rebuild-1');const rebuild={...common,castId:'rebuild-1',kind:'reconstruct',elapsedSeconds:600,toolItemId:'kit-instance'};
  await assert.rejects(f.command('complete_actor_care',{...rebuild,toolItemId:'missing-tool'}),/RECONSTRUCTION_TOOL_UNAVAILABLE/);
  const restored=await f.command('complete_actor_care',rebuild);assert.equal(restored.out.afterHp,60);
  assert.equal((await f.read('active-repair')).data.current,0);assert.equal((await f.read('reconstruction')).data.current,0);
  await f.command('complete_rest',{actorId:who,kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[]});assert.equal((await f.read('reconstruction')).data.current,0);
  await f.command('complete_rest',{actorId:operatorId,kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[]});assert.equal((await f.read('reconstruction')).data.current,1);
});

test('reviewed item text binds the physical holding and definition revisions without inferring attack accuracy',async t=>{
  const f=await setup(t);await f.defined();
  const def={definitionId:'text-def',name:'Fixture lantern',category:'item',description:'Deals 1d8 + 4 radiant damage.',
    rarity:null,stackable:false,wearable:true,handheld:false,attunement:'none',source:'synthetic source'};
  await f.command('define_item_definition',def);
  await f.command('acquire_item',{itemId:'text-item',definitionId:def.definitionId,ownerId:who,quantity:1,source:'fixture'});
  const proof={definitionId:def.definitionId,itemRevision:(await f.read('text-item')).revision,definitionRevision:(await f.read(def.definitionId)).revision};
  const cmd={attackId:'text-attack',actorId:who,name:'Reviewed lantern',source:'Fictional owner review',delivery:'other',resolution:'manual',timing:'action',
    components:[component('ray','radiant',1,8,4)],sourceItemId:'text-item',sourceReview:proof};
  await f.command('edit_item_definition',{definitionId:def.definitionId,name:def.name,description:'Deals 2d8 cold damage.',category:def.category,rarity:null,source:'fixture correction'});
  await assert.rejects(f.command('define_attack',cmd),/ATTACK_ITEM_REVIEW_CHANGED/);
  assert.equal(await f.read('text-attack'),undefined);
  const definitionRevision=(await f.read(def.definitionId)).revision;
  const saved=await f.command('define_attack',{...cmd,sourceReview:{...proof,definitionRevision},components:[component('ray','cold',2,8,0)]});
  const row=await f.read(saved.out.attackId);
  assert.equal(row.data.sourceReview.definitionRevision,definitionRevision);assert.equal(row.data.fixedHit,undefined);
  assert.equal(row.data.sourceItemId,'text-item');assert.equal(row.data.components[0].type,'cold');
});

async function fixtureEncounter(f,id,participants) {
  for(const [actorId,face] of participants)await f.command('record_check',{actorId,rollId:id+'-initiative-'+actorId,check:check('initiative','initiative'),evidence:dice(face)});
  return f.command('start_encounter',{encounterId:id,name:'Fictional encounter',participants:participants.map(([actorId])=>({actorId,initiativeRollId:id+'-initiative-'+actorId}))});
}
const declaredAction=(actionId,useId,fields={})=>({actorId:who,actionId,useId,declared:true,...fields});
const declaredAttack=(attackId,attemptId,fields={})=>({actorId:who,attackId,attemptId,mode:'normal',includeDamage:false,...fields});

test('shared breath recharge checks the current turn, actual face, failed checks, retry and later success',async t=>{
  const f=await setup(t);await f.defined({role:'companion'});await f.defined({actorId:'opponent',name:'Fixture opponent',role:'creature'});
  await f.pool('breath-cycle','ordinary',0,1,'recharge_roll',{rechargeSides:6,rechargeMinimum:5});
  const request={actorId:who,poolId:'breath-cycle',origin:'player_physical',atStartOfTurn:true};
  await assert.rejects(f.command('resolve_pool_recharge',{...request,face:6}),/RECHARGE_WINDOW_UNAVAILABLE/);
  await fixtureEncounter(f,'recharge-enc',[[who,18],['opponent',2]]);
  await assert.rejects(f.command('resolve_pool_recharge',{...request,origin:'dm',face:6}),/PLAYER_DICE_REQUIRE_PLAYER_OR_DELEGATION/);
  const miss=await f.command('resolve_pool_recharge',{...request,face:4});
  assert.equal(miss.out.after,0);assert.equal(miss.out.face,4);
  await assert.rejects(f.command('resolve_pool_recharge',{...request,face:6}),/RECHARGE_ALREADY_CHECKED/);
  await f.command('advance_encounter_turn',{encounterId:'recharge-enc',currentActorId:who});
  await assert.rejects(f.command('resolve_pool_recharge',{...request,face:6}),/RECHARGE_WINDOW_UNAVAILABLE/);
  await f.command('advance_encounter_turn',{encounterId:'recharge-enc',currentActorId:'opponent'});
  const hit=await f.command('resolve_pool_recharge',{...request,face:5});
  assert.equal(hit.out.after,1);assert.notEqual(hit.out.window,miss.out.window);await f.retry(hit.command);
  assert.equal((await f.read('breath-cycle')).data.current,1);
});

test('one small actor retains eight physical holdings, ordinary attacks, graded senses and a shared grounded deployment',async t=>{
  const f=await setup(t);const ids=await defineFrameFixture(f,who);
  const ordinary=await f.sheet();assert.equal(ordinary.mobilityRules[0].eligible,true);
  assert.equal(ordinary.communications.find(v=>v.kind==='speech').unassigned,3);assert.equal(ordinary.communications.find(v=>v.kind==='telepathy').sharedSenses,false);
  assert.deepEqual([ordinary.size,ordinary.ac.value,ordinary.skills.stealth.value,ordinary.saves.DEX.value,ordinary.skills.perception.value,ordinary.senses.darkvision],['tiny',16,13,8,7,180]);
  assert.equal(ordinary.rollModes['save:DEX'].normal.count,3);assert.equal(ordinary.rollModes['skill:stealth'].normal.count,3);
  const scan=(id,distanceFeet,faint,obstacle)=>f.command('use_action',declaredAction('scan',id,{thermalReading:{distanceFeet,faint,obstacle}}));
  assert.equal((await scan('warm',150,false,'none')).out.sensor.eligible,true);
  const seen=await f.command('record_check',{actorId:who,rollId:'scan-roll',check:check('skill','perception'),evidence:dice(9,15),forSenseId:'warm'});
  assert.equal(seen.out.total,24);await assert.rejects(f.command('record_check',{actorId:who,rollId:'duplicate-scan',check:check('skill','perception'),evidence:dice(15,15),forSenseId:'warm'}),/SENSOR_CHECK_UNAVAILABLE/);
  assert.equal((await scan('faint',60,true,'none')).out.sensor.eligible,true);assert.equal((await scan('distant',61,true,'none')).out.sensor.eligible,false);
  assert.equal((await scan('wall',2,false,'solid')).out.sensor.eligible,false);
  await f.command('use_action',declaredAction('basic-pair','ordinary-turn'));
  await f.command('resolve_attack',declaredAttack('pulse','pulse-attempt',{actionUseId:'ordinary-turn',replacesAttackId:'rake',hit:dice(11)}));
  await assert.rejects(f.command('resolve_attack',declaredAttack('pulse','pulse-again',{actionUseId:'ordinary-turn',replacesAttackId:'nip',hit:dice(12)})),/SUBSTITUTION_UNAVAILABLE/);
  const fixed=await f.command('resolve_attack',declaredAttack('nip','nip-attempt',{actionUseId:'ordinary-turn',substitutionId:'fixed'}));
  assert.deepEqual([fixed.out.hit.total,fixed.out.hit.faces.length,fixed.out.critical],[18,0,false]);
  const dest={label:'test surface',distanceFeet:30,unoccupied:true,visible:true,grounded:false,availableSize:'tiny',passengers:0,permittedByBarriers:true};
  await f.command('use_action',declaredAction('shift','ordinary-shift',{destination:dest}));
  assert.equal((await f.preview('rig-form')).projection.abilities.STR.effective,20);assert.equal((await f.read('deploy')).data.current,1);
  await f.command('change_form',{actorId:who,formId:'rig-form',elapsedSeconds:60,uninterrupted:true,willing:true});
  const large=await f.sheet();assert.deepEqual(large.communications,ordinary.communications);assert.equal(large.mobilityRules[0].eligible,false);
  assert.deepEqual([large.size,large.ac.value,large.movement.walk,large.movement.fly,large.currentHp],['large',18,40,0,60]);
  await assert.rejects(f.command('use_action',declaredAction('basic-pair','invalid-pair')),/ACTION_FORM_INELIGIBLE/);
  await assert.rejects(f.command('resolve_attack',declaredAttack('rake','invalid-rake',{hit:dice(10)})),/ATTACK_FORM_INELIGIBLE/);
  await assert.rejects(f.command('use_action',declaredAction('shift','bad-destination',{destination:dest})),/GROUNDED_DESTINATION_REQUIRED/);
  await f.command('use_action',declaredAction('shift','large-shift',{destination:{...dest,grounded:true,availableSize:'large'}}));
  assert.equal((await f.read('blink')).data.current,1);
  await fixtureEncounter(f,'cry-enc',[[who,18],['listener',8]]);
  await f.command('use_action',declaredAction('cry','selective',{targets:[{actorId:'listener',hears:true,distanceFeet:30}]}));
  await f.command('use_action',declaredAction('large-pair','paired'));
  for(const id of ['large-bite','large-rake'])await f.command('resolve_attack',declaredAttack(id,'strike-'+id,{actionUseId:'paired',hit:dice(13)}));
  await f.command('record_check',{actorId:'listener',rollId:'first-save',check:check('save','WIS'),evidence:dice(2),forUseId:'selective'});
  await f.command('resolve_action_save',{actorId:who,useId:'selective',targetActorId:'listener',rollId:'first-save'});
  assert.deepEqual((await f.read('ally')).data.conditions,[]);assert.deepEqual((await f.read('listener')).data.conditions,['frightened']);
  await f.command('advance_encounter_turn',{encounterId:'cry-enc',currentActorId:who});
  await f.command('record_check',{actorId:'listener',rollId:'repeat-save',check:check('save','WIS'),evidence:dice(17),forUseId:'selective'});
  await f.command('resolve_action_save',{actorId:who,useId:'selective',targetActorId:'listener',rollId:'repeat-save',endOfTurnConfirmed:true});
  assert.deepEqual((await f.read('listener')).data.conditions,[]);assert.equal((await f.read('roar')).data.current,0);
  await f.command('end_encounter',{encounterId:'cry-enc',currentActorId:'listener',outcome:'synthetic completion'});
  const hit=await f.command('apply_hp_change',{actorId:who,kind:'damage',amount:80});await f.retry(hit.command);
  assert.equal((await f.read('rig-integrity')).data.current,0);assert.equal((await f.read(who)).data.currentHp,55);assert.equal((await f.sheet()).formId,null);
  const items=await Promise.all(ids.map(id=>f.read(id)));assert.deepEqual(items.map(i=>i.id),ids);assert.deepEqual(items.filter(i=>i.data.attuned).map(i=>i.id),['ward']);
  assert.deepEqual(items.filter(i=>i.data.equipment==='packed').map(i=>i.id),['keepsake']);
});

test('three anatomies share four holdings, one rechargeable breath and one timed melee enhancement',async t=>{
  const f=await setup(t);await defineMorphFixture(f,who);
  const change=id=>f.command('change_form',{actorId:who,formId:id,elapsedSeconds:0,willing:true});
  await change('civilian');const human=await f.sheet();assert.deepEqual([human.currentHp,human.ac.value,human.proficiency,human.initiative.value,human.movement.walk,human.movement.fly],[154,19,4,4,30,0]);
  for(const id of ['claw-base','bite-hybrid','stream','frost'])await assert.rejects(f.command('resolve_attack',declaredAttack(id,'bad-'+id)),/ATTACK_FORM_INELIGIBLE/);
  assert.equal((await f.preview('winged-hybrid')).projection.movement.swim,null);
  assert.equal((await f.read('shared-breath')).data.current,1);await change('winged-hybrid');
  for(const id of ['frost','gas'])await assert.rejects(f.command('resolve_attack',declaredAttack(id,'bad-'+id)),/ATTACK_FORM_INELIGIBLE/);
  const natural=await f.command('resolve_attack',declaredAttack('claw-hybrid','unenhanced',{hit:dice(11)}));assert.equal(natural.out.reach,5);
  await f.command('use_action',declaredAction('extended','mode-start'));
  const empowered=await f.command('resolve_attack',declaredAttack('claw-hybrid','melee-mode',{hit:dice(12),includeDamage:true,
    damage:[{id:'natural',evidence:dice(4,6)},{id:'surge',evidence:dice(5)}]}));
  assert.equal(empowered.out.reach,15);assert.deepEqual(empowered.out.damage.map(p=>p.type),['slashing','force']);
  const shaped=await f.command('resolve_attack',declaredAttack('focused','one-geometry'));assert.equal(shaped.out.hit,null);
  assert.deepEqual(shaped.out.components.map(c=>c.type),['acid']);assert.equal((await f.read('shared-breath')).data.current,0);
  await assert.rejects(f.command('resolve_attack',declaredAttack('fan','second-geometry')),/RESOURCE_EMPTY_OR_UNKNOWN/);
  await change('original');const full=await f.sheet();assert.deepEqual([full.movement.walk,full.movement.fly,full.movement.swim],[40,80,40]);
  assert.deepEqual(full.defenses.map(d=>[d.type,d.kind]),[['acid','immunity'],['cold','resistance'],['poison','resistance']]);
  const stillActive=await f.command('resolve_attack',declaredAttack('bite-base','full-mode',{hit:dice(10)}));assert.equal(stillActive.out.reach,15);
  await f.command('advance_actor_time',{actorId:who,elapsedSeconds:60,confirmed:true});
  const ended=await f.command('resolve_attack',declaredAttack('bite-base','ended-mode',{hit:dice(12)}));assert.equal(ended.out.reach,10);assert.equal(ended.out.components.length,2);
  await f.command('apply_hp_change',{actorId:who,kind:'damage',amount:10});await change('civilian');
  await f.command('set_item_operating',{itemId:'primary-focus',operating:false,source:'fixture suppression'});
  assert.deepEqual([(await f.sheet()).formId,(await f.sheet()).currentHp,(await f.read('shared-mode')).data.current,(await f.read('shared-breath')).data.current],[null,144,0,0]);
  assert.equal((await f.read('reserve-focus')).data.equipment,'packed');await assert.rejects(change('winged-hybrid'),/FORM_SOURCE_UNAVAILABLE|FORM_SOURCE_INELIGIBLE/);
});

test('separate actor turn, gate, Evasion and two competing reactions resolve against the original cast or incoming hit',async t=>{
  const f=await setup(t);const bond='bond',enemy='other';await f.defined({actorId:bond,currentHp:30,maxHp:30,baseAc:12});
  await f.defined({role:'companion',initiativeAfterActorId:bond,proficiency:4});await f.defined({actorId:enemy,role:'creature'});
  await f.effect('dodge','save_mitigation','DEX','evade');
  const action={actorId:who,source:'fixture'};
  await f.command('define_action',{...action,actionId:'gate',name:'Test gate',type:'gate',timing:'action'});
  const info={location:'fixture location',permittedHere:true,teleportAllowed:true};
  await assert.rejects(f.command('use_action',declaredAction('gate','invalid-gate',{gate:{...info,open:true,teleportAllowed:false}})),/GATE_STATE_OR_LOCATION_INELIGIBLE/);
  const opened=await f.command('use_action',declaredAction('gate','opened',{gate:{...info,open:true}}));
  assert.deepEqual(opened.out.gate.current.timeRatio,{inside:1,outside:1});
  await f.command('use_action',declaredAction('gate','closed',{gate:{...info,open:false}}));
  await f.command('define_action',{...action,actionId:'channel',name:'Touch channel',type:'touch_delivery',timing:'reaction',bondedActorId:bond,range:120});
  await f.command('define_action',{...action,actionId:'intercept',name:'Deflect',type:'deflection',timing:'reaction',bondedActorId:bond,range:5,reduction:{dice:3,sides:4}});
  await f.command('define_spell',{actorId:bond,spellId:'contact',name:'Contact',source:'fixture',baseLevel:0,concentration:false,
    ritual:false,resolution:'automatic',timing:'action',rangeKind:'touch',components:[component('renew','healing',0,6,1)]});
  await f.command('grant_spell',{actorId:bond,spellId:'contact',grantId:'contact-grant',source:'fixture',route:'at_will',levels:[0],preparation:'not_required'});
  await f.command('define_attack',{actorId:enemy,attackId:'incoming',name:'Incoming strike',source:'fixture',timing:'attack',delivery:'melee',resolution:'attack',fixedHit:5,components:[component('impact','slashing',0,6,14)]});
  await f.command('define_attack',{actorId:enemy,attackId:'burst',name:'Area',source:'fixture',timing:'action',delivery:'area',resolution:'save',saveAbility:'DEX',saveDc:14,saveSuccess:'half',components:[component('heat','fire',0,6,14)]});
  // The encounter accepts an actual independent actor initiative and the configured dependent's own turn.
  for(const [id,face] of [[bond,18],[enemy,10]])await f.command('record_check',{actorId:id,rollId:'order-'+id,check:check('initiative','initiative'),evidence:dice(face)});
  await f.command('start_encounter',{encounterId:'two-reactions',name:'Two reactions',participants:[{actorId:enemy,initiativeRollId:'order-'+enemy},{actorId:who},{actorId:bond,initiativeRollId:'order-'+bond}]});
  const order=(await f.read('two-reactions')).data;assert.deepEqual(order.participants,[bond,who,enemy]);assert.deepEqual(order.initiative[1],{actorId:who,total:20,rollId:null,afterActorId:bond});
  await f.command('cast_spell',{actorId:bond,castId:'contact-instance',grantId:'contact-grant',level:0,deliveryActorId:who,targets:[enemy]});
  await f.command('link_cast_roll',{actorId:bond,castId:'contact-instance',damage:[{id:'renew',evidence:dice()}]});
  const contact={actorId:bond,castId:'contact-instance',targetActorId:enemy,source:'confirmed fixture target'};
  await assert.rejects(f.command('resolve_cast_target',contact),/SPELL_AWAITS_DELIVERY_REACTION/);
  await f.command('advance_encounter_turn',{encounterId:'two-reactions',currentActorId:bond});
  const delivery={castId:'contact-instance',targetActorId:enemy,distanceToCaster:120,targetWithinReach:true};
  await assert.rejects(f.command('use_action',declaredAction('channel','out-of-range',{delivery:{...delivery,distanceToCaster:121}})),/TOUCH_DELIVERY_INELIGIBLE/);
  await f.command('use_action',declaredAction('channel','delivery',{delivery}));
  await f.command('resolve_cast_target',contact);
  await f.command('advance_encounter_turn',{encounterId:'two-reactions',currentActorId:who});
  const strike=id=>f.command('resolve_attack',declaredAttack('incoming',id,{actorId:enemy,hit:dice(16),includeDamage:true,targets:[bond],damage:[{id:'impact',evidence:dice()}]}));
  await strike('hit-a');
  const deflect=id=>f.command('use_action',declaredAction('intercept','deflect-'+id,{deflection:{attemptId:id,targetActorId:bond,distanceFeet:5,visible:true,dice:dice(3,4,2)}}));
  await assert.rejects(deflect('hit-a'),/ACTION_UNAVAILABLE/);
  const turn=actorId=>f.command('advance_encounter_turn',{encounterId:'two-reactions',currentActorId:actorId});
  await turn(enemy);await turn(bond);await turn(who);await strike('hit-b');
  const reaction=await deflect('hit-b');assert.equal(reaction.out.deflection.roll.total,9);
  const result=await f.command('resolve_attack_target',{actorId:enemy,attemptId:'hit-b',targetActorId:bond,source:'fixture resolved target'});
  assert.equal(result.out.amount,5);await f.retry(result.command);assert.equal((await f.read(bond)).data.currentHp,25);
  await f.command('end_encounter',{encounterId:'two-reactions',currentActorId:enemy,outcome:'fixture conclusion'});
  for(const [id,face,amount] of [['passed',18,0],['failed',2,7]]){
    await f.command('resolve_attack',declaredAttack('burst',id,{actorId:enemy,includeDamage:true,targets:[who],damage:[{id:'heat',evidence:dice()}]}));
    await f.command('record_check',{actorId:who,rollId:'save-'+id,check:check('save','DEX'),evidence:dice(face),forAttemptId:id});
    const resolution=await f.command('resolve_attack_target',{actorId:enemy,attemptId:id,targetActorId:who,saveRollId:'save-'+id,source:'confirmed target resolution'});
    assert.equal(resolution.out.amount,amount);
  }
});

test('revising a finite pool preserves identity, source and balance while replacing only its configured recovery trigger', async t => {
  const f=await setup(t); await f.defined();
  await f.defined({actorId:'operator',name:'Fixture operator'});
  await f.pool('reserve','charge',2,4,'long_rest');
  const edit={actorId:who,poolId:'reserve',name:'Emergency reserve',maximum:6,recovery:'short_rest',recoveryActorId:'operator',source:'reviewed instructions'};
  const first=await f.command('revise_resource',edit);
  assert.deepEqual([first.out.current,first.out.maximum],[2,6]);
  assert.equal((await f.read('reserve')).data.originalSource,'fixture configuration');
  assert.deepEqual((await f.read('operator')).data.recoveryPoolIds,['reserve']);
  assert.equal((await f.retry(first.command)).receipt.transactionId,first.result.receipt.transactionId);
  await assert.rejects(f.command('revise_resource',{...edit,maximum:1}),/POOL_EXCEEDS_MAXIMUM/);
  await assert.rejects(f.command('revise_resource',{...edit,recovery:'dawn'}),/EXTERNAL_RECOVERY_TRIGGER_INVALID/);
  assert.deepEqual([(await f.read('reserve')).data.current,(await f.read('reserve')).data.maximum],[2,6]);
  await f.command('revise_resource',{actorId:who,poolId:'reserve',recoveryActorId:null,recovery:'dawn',source:'reviewed dawn trigger'});
  assert.deepEqual((await f.read('operator')).data.recoveryPoolIds,[]);
  assert.equal((await f.read('reserve')).data.current,2);
  await f.pool('limited','lifetime',null,null,'none');
  await assert.rejects(f.command('revise_resource',{actorId:who,poolId:'limited',recovery:'long_rest',source:'fixture'}),/LIFETIME_POOL_CANNOT_RECOVER/);
  assert.equal((await f.read('limited')).data.current,null);
});

test('seven armor attack families share one physical source, bounded charge, prepared payloads and weapon cooldown',async t=>{
  const f=await setup(t);await f.defined({abilities:{STR:21,DEX:16,CON:16,INT:20,WIS:12,CHA:10},attunementCapacity:1});
  await f.defined({actorId:'target',role:'creature',name:'Sparring fixture',maxHp:200,currentHp:200,baseAc:12});
  await wornFixture(f,'frame',{attuned:true});
  await f.command('define_item_definition',{definitionId:'scroll-def',name:'Fixture scroll',category:'consumable',description:'Bound fixture payload',rarity:null,
    stackable:true,wearable:false,handheld:false,attunement:'none',source:'fixture'});
  await f.command('acquire_item',{itemId:'scrolls',definitionId:'scroll-def',ownerId:who,quantity:2,source:'fixture'});
  await f.pool('slots','spell_slot',3,3,'long_rest',{slotLevel:2});await f.pool('blast-budget','ordinary',1,1,'long_rest');
  const gear={actorId:who,source:'fixture reviewed configuration',sourceItemId:'frame',requiresAttunement:true};
  await f.command('define_spell',{actorId:who,spellId:'spark',name:'Fictional spell',source:'reviewed damage-only variant',baseLevel:1,
    concentration:false,ritual:false,resolution:'automatic',timing:'action',components:[component('pulse','radiant',1,6,0,{extraDicePerLevel:1})]});
  await f.command('define_action',{...gear,actionId:'reservoir',name:'Reservoir',timing:'bonus_action',type:'store_payload',
    capacity:1,releasesPerTurn:1,payloadVariants:[{id:'spark-two',spellId:'spark',level:2,slotLevel:2,scrollDefinitionId:'scroll-def'}]});
  await f.command('define_action',{...gear,actionId:'prime',name:'Prime melee strike',timing:'bonus_action',type:'prepare_strike',
    chargeType:'thunder',chargeAbilities:['STR','DEX','INT']});
  const allowed=['acid','cold','fire','lightning','poison','thunder'];
  const force=component('force','force',3,12,5),selector=component('element','cold',2,8,0,{variants:allowed}),sound=component('thunder','thunder',1,8,5);
  const shot={...gear,timing:'attack',delivery:'ranged',resolution:'attack',fixedHit:9,range:300,longRange:1200,weaponBus:{},components:[force,selector]};
  const fist={...gear,timing:'attack',delivery:'melee',resolution:'attack',fixedHit:9,reach:5,weaponBus:{},components:[force,selector,sound]};
  const imbued={payloadRule:{componentId:'element',storeActionId:'reservoir'}};
  for(const [id,config] of [['shot',shot],['fist',fist],['primed',{...fist,chargeActionId:'prime',preparedPush:{feet:15,ability:'DEX',dc:15,prone:true}}],
    ['imbued-shot',{...shot,...imbued}],['imbued-fist',{...fist,...imbued}],
    ['blast',{...gear,timing:'action',delivery:'area',resolution:'save',saveDc:17,saveAbility:'DEX',saveSuccess:'half',weaponBus:{cooldownSeconds:600},cost:{poolId:'blast-budget',amount:1},components:[component('blast','force',12,12,0)]}],
    ['guardian',{...gear,timing:'attack',delivery:'melee',resolution:'attack',fixedHit:8,reach:5,components:[sound]}]])
    await f.command('define_attack',{...config,attackId:id,name:id});
  for(const [attackId,type] of [['shot','fire'],['fist','cold']])await f.command('select_damage_variant',{actorId:who,attackId,componentId:'element',type});
  await assert.rejects(f.command('select_damage_variant',{actorId:who,attackId:'shot',componentId:'element',type:'force'}),/DAMAGE_VARIANT_UNAVAILABLE/);
  const execute=(id,attempt,extra={})=>f.command('resolve_attack',declaredAttack(id,attempt,{hit:dice(14),...extra}));
  const fire=await execute('shot','ranged-attempt');assert.deepEqual(fire.out.components.map(c=>[c.type,c.dice,c.sides,c.flat]),[['force',3,12,5],['fire',2,8,0]]);
  const cold=await execute('fist','melee-attempt');assert.deepEqual(cold.out.components.map(c=>c.type),['force','cold','thunder']);
  const loaded=await f.command('use_action',declaredAction('reservoir','loaded',{load:{variantId:'spark-two',slotPoolId:'slots'}}));
  assert.equal(loaded.out.costs[0].after,2);await f.retry(loaded.command);
  await assert.rejects(f.command('use_action',declaredAction('reservoir','overfill',{load:{variantId:'spark-two',slotPoolId:'slots'}})),/RESERVOIR_FULL/);
  await assert.rejects(execute('imbued-shot','unselected'),/PAYLOAD_SELECTION_REQUIRED/);
  const release={variantId:'spark-two',storedUseId:'loaded'};
  const published=await execute('imbued-shot','first-release',{payload:release});
  assert.deepEqual(published.out.components.map(c=>[c.type,c.dice]),[['force',3],['radiant',2]]);
  assert.equal(published.out.payload.level,2);assert.deepEqual(published.out.costs,[]);assert.equal((await f.read('slots')).data.current,2);
  await assert.rejects(execute('imbued-shot','same-load',{payload:release}),/STORED_PAYLOAD_UNAVAILABLE/);
  const instant={variantId:'spark-two',slotPoolId:'slots'};
  await assert.rejects(execute('imbued-fist','too-soon',{payload:instant}),/PAYLOAD_TURN_LIMIT/);
  assert.equal((await f.read('slots')).data.current,2);assert.equal(await f.read('too-soon'),undefined);
  await f.command('advance_actor_time',{actorId:who,elapsedSeconds:6,confirmed:true});
  const castless=await execute('imbued-fist','second-release',{payload:instant});
  assert.deepEqual(castless.out.components.map(c=>c.type),['force','radiant','thunder']);assert.equal(castless.out.costs[0].after,1);
  const scroll=await f.command('use_action',declaredAction('reservoir','scroll-payload',{load:{variantId:'spark-two',scrollItemId:'scrolls'}}));
  assert.equal(scroll.out.scroll.quantityAfter,1);await f.retry(scroll.command);assert.equal((await f.read('scrolls')).data.quantity,1);
  await assert.rejects(execute('primed','no-prep'),/PREPARED_STRIKE_REQUIRED/);
  await f.command('use_action',declaredAction('prime','prepared'));
  const prepared=await execute('primed','charged-hit',{targets:['target'],includeDamage:true,damage:[
    {id:'force',evidence:dice(1,1,1)},{id:'element',evidence:dice(1,2)},{id:'thunder',evidence:dice(3)},{id:'prepared-bonus',evidence:dice()}]});
  assert.equal(prepared.out.preparedUseId,'prepared');assert.equal(prepared.out.components.at(-1).flat,13);
  await assert.rejects(execute('primed','double-charge'),/PREPARED_STRIKE_REQUIRED/);
  await f.command('resolve_attack_target',{actorId:who,attemptId:'charged-hit',targetActorId:'target',source:'observed hit'});
  await f.command('record_check',{actorId:'target',rollId:'rider-save',check:check('save','DEX'),evidence:dice(3),forRiderAttemptId:'charged-hit'});
  const rider={actorId:who,attemptId:'charged-hit',targetActorId:'target',rollId:'rider-save',feet:15,destination:'fixture marker',source:'clear path confirmed'};
  assert.equal((await f.command('resolve_attack_rider',rider)).out.prone,true);assert.deepEqual((await f.read('target')).data.conditions,['prone']);
  await assert.rejects(f.command('resolve_attack_rider',rider),/NO_PENDING_ATTACK_RIDER/);
  const blast=await f.command('resolve_attack',declaredAttack('blast','discharge',{includeDamage:true,damage:[{id:'blast',evidence:dice(...Array(12).fill(6))}]}));
  assert.equal(blast.out.hit,null);assert.equal(blast.out.damage[0].total,72);assert.equal((await f.read('blast-budget')).data.current,0);
  await assert.rejects(execute('shot','cooldown'),/WEAPON_BUS_UNAVAILABLE/);
  assert.ok((await execute('guardian','independent-weapon')).out.hit);
  await f.command('advance_actor_time',{actorId:who,elapsedSeconds:599,confirmed:true});await assert.rejects(execute('shot','not-yet'),/WEAPON_BUS_UNAVAILABLE/);
  await f.command('advance_actor_time',{actorId:who,elapsedSeconds:1,confirmed:true});assert.ok((await execute('shot','ready-again')).out.hit);
  assert.equal((await f.read('frame')).data.attuned,true);assert.equal((await f.read('blast-budget')).data.current,0);
});

test('long deployment follows actual encounter rounds, survives a service restart, and consumes one charge only on completion',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'astravar-deployment-'));
  const f=await setup(t,join(directory,'state.sqlite'));t.after(()=>rm(directory,{recursive:true,force:true}));
  await f.defined();await f.defined({actorId:'other',name:'Fixture participant'});
  await f.pool('deploy-use','charge',1,1,'long_rest');await f.pool('integrity','integrity',47,75,'none');await f.pool('cry-use','charge',0,1,'deployment');
  await wornFixture(f,'deploy-kit');
  const config={actorId:who,formId:'timed',name:'Synthetic timed form',source:'fixture',sourceItemId:'deploy-kit',requiresWilling:true,
    timing:'action',activationSeconds:60,durationSeconds:3600,deploymentPoolId:'deploy-use',integrityPoolId:'integrity',resetPoolId:'cry-use'};
  await f.command('define_form',config);
  const begin={actorId:who,formId:'timed',deploymentId:'tracked-work',declared:true,willing:true};
  const finish={actorId:who,formId:'timed',deploymentId:'tracked-work',elapsedSeconds:0,uninterrupted:true,willing:true};
  const next=currentActorId=>f.command('advance_encounter_turn',{encounterId:'timer-enc',currentActorId});
  await fixtureEncounter(f,'timer-enc',[['other',18],[who,5]]);
  await assert.rejects(f.command('begin_form_deployment',begin),/TURN_UNAVAILABLE/);
  await next('other');const started=await f.command('begin_form_deployment',begin);await f.retry(started.command);
  assert.equal((await f.read('timer-enc')).data.economy[who].action,false);assert.equal((await f.read('cry-use')).data.current,0);
  await assert.rejects(f.command('begin_form_deployment',{...begin,deploymentId:'duplicate-work'}),/DEPLOYMENT_ALREADY_PENDING/);
  await assert.rejects(f.command('change_form',{...finish,elapsedSeconds:60}),/RECORDED_DEPLOYMENT_TIME_REQUIRED/);
  await assert.rejects(f.command('change_form',{actorId:who,formId:'timed',elapsedSeconds:60,uninterrupted:true,willing:true}),/FINISH_OR_INTERRUPT_PENDING_DEPLOYMENT/);
  await assert.rejects(f.command('change_form',{...finish,actorId:'other'}),/MECHANIC_REFERENCE_UNAVAILABLE/);
  // A separately approved clock update or actual non-disabling damage supplies no invented combat rounds.
  await f.command('advance_actor_time',{actorId:who,elapsedSeconds:60,confirmed:true});await f.command('apply_hp_change',{actorId:who,kind:'damage',amount:3});
  assert.equal((await f.preview('timed')).deployment.elapsedSeconds,0);assert.equal((await f.read('deploy-use')).data.current,1);
  for(let i=0;i<4;i++){await next(who);await next('other');}
  assert.equal((await f.preview('timed')).deployment.elapsedSeconds,24);
  const independentSnapshot=join(directory,'restored.sqlite');
  f.db.db.prepare('VACUUM INTO ?').run(independentSnapshot);
  f.reopen(independentSnapshot);
  assert.equal((await f.preview('timed')).deployment.remainingSeconds,36);assert.equal((await f.sheet()).activeDeploymentId,'tracked-work');
  assert.equal((await f.read(who)).data.activeDeploymentId,'tracked-work');
  for(let i=0;i<5;i++){await next(who);await next('other');}
  await next(who);assert.equal((await f.preview('timed')).deployment.elapsedSeconds,54);
  await assert.rejects(f.command('change_form',finish),/DEPLOYMENT_TIME_REMAINING/);
  await next('other');
  const ready=await f.preview('timed');assert.equal(ready.deployment.ready,true);
  assert.equal((await f.read('timer-enc')).data.economy[who].action,true);
  await assert.rejects(f.command('change_form',{...finish,uninterrupted:false}),/RECORDED_DEPLOYMENT_TIME_REQUIRED/);
  await assert.rejects(f.command('change_form',{...finish,willing:false}),/FORM_TRANSFORMATION_INELIGIBLE/);
  const completed=await f.command('change_form',finish);await f.retry(completed.command);f.reopen();
  assert.equal(completed.out.elapsedSeconds,60);assert.equal((await f.read('tracked-work')).data.status,'completed');
  const a=(await f.read(who)).data;
  assert.deepEqual([a.activeDeploymentId,a.activeFormId,a.currentHp,a.gameSeconds,a.formExpiresAt],[null,'timed',39,160,3760]);
  assert.deepEqual(await Promise.all(['deploy-use','integrity','cry-use'].map(async id=>(await f.read(id)).data.current)),[0,47,1]);
  assert.equal((await f.read('timer-enc')).data.economy[who].action,true);
  const replay=(await f.service.receipt(dm,cid,completed.command.commandId));assert.deepEqual(replay.receipt,completed.result.receipt);
  await assert.rejects(f.command('interrupt_form_deployment',{actorId:who,deploymentId:'tracked-work',status:'cancelled',reason:'late'}),/DEPLOYMENT_NOT_PENDING/);
});

test('pending deployment preserves scene and encounter time, cancellations, source interruptions and reviewed definitions',async t=>{
  const f=await setup(t);await f.defined();await f.defined({actorId:'other',name:'Fixture participant'});await wornFixture(f,'changer',{manual:true});
  await f.pool('use','charge',1,1,'long_rest');
  const config={actorId:who,formId:'slow',name:'Synthetic mechanism',source:'fixture',sourceItemId:'changer',requiresOperating:true,
    timing:'other',activationSeconds:60,deploymentPoolId:'use'};
  await f.command('define_form',config);
  const begin=id=>f.command('begin_form_deployment',{actorId:who,formId:'slow',deploymentId:id,declared:true});
  const stop=(id,status,reason)=>f.command('interrupt_form_deployment',{actorId:who,deploymentId:id,status,reason});
  const finish=id=>f.command('change_form',{actorId:who,formId:'slow',deploymentId:id,elapsedSeconds:0,uninterrupted:true});
  await begin('cancel-me');await stop('cancel-me','cancelled','Player changed the declared action');
  assert.equal((await f.read('cancel-me')).data.status,'cancelled');assert.equal((await f.read('use')).data.current,1);
  await begin('interrupt-me');await stop('interrupt-me','interrupted','Work was interrupted');
  assert.equal((await f.read('interrupt-me')).data.reason,'Work was interrupted');
  await begin('source-lost');await f.command('set_item_operating',{itemId:'changer',operating:false,source:'confirmed suppression'});
  await f.command('set_item_operating',{itemId:'changer',operating:true,source:'confirmed reactivation'});
  assert.equal((await f.read('source-lost')).data.reason,'source_item_unavailable');await assert.rejects(finish('source-lost'),/DEPLOYMENT_NOT_PENDING/);
  await begin('incapacitated');await f.command('set_condition',{actorId:who,key:'stunned',enabled:true,sourceId:'approved-effect'});
  await f.command('set_condition',{actorId:who,key:'stunned',enabled:false,sourceId:'approved-effect'});
  assert.equal((await f.read('incapacitated')).data.reason,'actor_incapacitated');
  await begin('stale-config');await f.command('revise_form',{...config,source:'reviewed change',activationSeconds:72});
  assert.equal((await f.preview('slow')).deployment.blockedReason,'DEPLOYMENT_FORM_CHANGED');
  await assert.rejects(finish('stale-config'),/DEPLOYMENT_FORM_CHANGED/);await stop('stale-config','cancelled','Reviewed changed timing');
  await begin('split-clock');await f.command('advance_actor_time',{actorId:who,elapsedSeconds:12,confirmed:true});
  await fixtureEncounter(f,'split-enc',[[who,18],['other',2]]);
  const turn=currentActorId=>f.command('advance_encounter_turn',{encounterId:'split-enc',currentActorId});
  for(let i=0;i<2;i++){await turn(who);await turn('other');}
  assert.equal((await f.preview('slow')).deployment.elapsedSeconds,24);
  await f.command('end_encounter',{encounterId:'split-enc',currentActorId:who,outcome:'Encounter ended during work'});
  const progress=(await f.preview('slow')).deployment;
  assert.deepEqual([progress.elapsedSeconds,progress.remainingSeconds,progress.basis],[24,48,'actor_game_clock']);
  await assert.rejects(finish('split-clock'),/DEPLOYMENT_TIME_REMAINING/);
  await f.command('advance_actor_time',{actorId:who,elapsedSeconds:48,confirmed:true});
  await finish('split-clock');
  assert.equal((await f.read('use')).data.current,0);assert.equal((await f.read(who)).data.currentHp,42);
  await f.pool('other-use','charge',1,1,'none');
  await f.command('define_form',{...config,formId:'alternate',deploymentPoolId:'other-use'});
  await f.command('begin_form_deployment',{actorId:who,formId:'alternate',deploymentId:'late-cost',declared:true});
  await f.command('spend_resource',{actorId:who,poolId:'other-use',amount:1});
  await f.command('advance_actor_time',{actorId:who,elapsedSeconds:60,confirmed:true});
  assert.equal((await f.preview('alternate')).deployment.blockedReason,'RESOURCE_EMPTY_OR_UNKNOWN');
  await assert.rejects(f.command('change_form',{actorId:who,formId:'alternate',deploymentId:'late-cost',elapsedSeconds:0,uninterrupted:true}),/RESOURCE_EMPTY_OR_UNKNOWN/);
  assert.equal((await f.read('late-cost')).data.status,'deploying');assert.equal((await f.read(who)).data.activeFormId,'slow');
});

test('an actor-scoped deployment reads only its participant clock and refuses a foreign actor',async t=>{
  const f=await setup(t);await f.defined();await f.defined({actorId:'private-participant',name:'Other participant'});
  await f.command('define_form',{actorId:who,formId:'scoped-form',name:'Fixture form',source:'fixture',timing:'other',activationSeconds:60});
  await fixtureEncounter(f,'private-encounter',[[who,18],['private-participant',5]]);
  const prior=await f.service.grantForSubject(cid,'sites-mcp');
  await f.service.setGrant(owner,cid,'sites-mcp',{scopes:prior.scopes,auto:prior.auto,actors:[who],expiresAt:Date.now()+3600000,revoked:false,expectedGrantRevision:prior.revision});
  const begin=await f.command('begin_form_deployment',{actorId:who,formId:'scoped-form',deploymentId:'personal-deployment',declared:true});
  assert.doesNotMatch(JSON.stringify(begin.result.receipt),/private-participant/);
  const preview=await f.preview('scoped-form');assert.equal(preview.deployment.elapsedSeconds,0);assert.doesNotMatch(JSON.stringify(preview),/private-participant/);
  await assert.rejects(f.command('interrupt_form_deployment',{actorId:'private-participant',deploymentId:'personal-deployment',status:'interrupted',reason:'out of scope'}),/GRANT_DENIED|SCOPE/);
  const stopped=await f.command('interrupt_form_deployment',{actorId:who,deploymentId:'personal-deployment',status:'cancelled',reason:'actor-scoped cancellation'});
  assert.equal(stopped.out.status,'cancelled');
});

test('three pinned feat variants retain one identity each, verified prior gains and an independent physical luck source',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'ascension-feat-'));
  t.after(()=>rm(directory,{recursive:true,force:true}));
  const f=await setup(t,join(directory,'proof.sqlite'));
  const family='fixture-perceptive-training',skills=['perception','investigation','insight'];
  for(const [id,edition] of [['v1','legacy_2014'],['v2','revised_2024'],['combined','homebrew']]){
    const profile=edition==='homebrew'?'legacy_2014':edition;
    await f.defined({actorId:id,name:'Synthetic feat subject',rulesProfile:profile,level:12,proficiency:4,
      abilities:{STR:10,DEX:14,CON:12,INT:20,WIS:11,CHA:10}});
    for(const [skill,ability,rank] of [['perception','WIS',1],['investigation','INT',edition==='legacy_2014'?1:2],['insight','WIS',2]])
      await f.command('set_skill_training',{actorId:id,skillId:skill,ability,rank,name:skill});
    const refs=[];
    if(edition!=='revised_2024')for(const key of ['perception','investigation']){
      const effectId=id+'-watch-'+key;
      await f.command('define_effect',{actorId:id,effectId,name:'Passive training',source:edition,target:'passive',key,operation:'add',amount:5,stackingKey:family+':'+key});
      refs.push({id:effectId,kind:'effect'});
    }
    if(edition!=='legacy_2014'){
      const actionId=id+'-search';
      await f.command('define_action',{actorId:id,actionId,name:'Search',type:'use',source:edition,timing:'bonus_action'});
      refs.push({id:actionId,kind:'action_definition'});
    }
    const feat={actorId:id,featureId:id+'-feat',kind:'feat',name:'Perceptive training',description:'Synthetic edition regression',
      source:edition,references:refs,feat:{familyId:family,edition,includedAbility:{ability:'INT',increase:1},
        ...(edition==='legacy_2014'?{}:{includedExpertise:['investigation']})},
      ...(edition==='revised_2024'?{}:{lipReading:{visibleMouth:true,understoodLanguage:true}})};
    const created=await f.command('define_feature',feat);await f.retry(created.command);
    await assert.rejects(f.command('define_feature',{...feat,featureId:id+'-duplicate'}),/FEATURE_FAMILY_ALREADY_ASSIGNED/);
    if(edition!=='legacy_2014')await assert.rejects(f.command('revise_feature',{...feat,feat:{...feat.feat,includedExpertise:['perception']}}),/INCLUDED_EXPERTISE_NOT_ESTABLISHED/);
    const sheet=await f.sheet(id),record=await f.read(feat.featureId);
    assert.equal((await f.read(id)).data.rulesProfile,profile);assert.equal(sheet.abilities.INT.base,20);
    assert.deepEqual(record.data.feat,feat.feat);assert.deepEqual(record.data.references,refs);
    assert.equal(sheet.communications.some(c=>c.kind==='lip_reading'&&c.requiresVisibleMouth&&c.requiresUnderstoodLanguage),edition!=='revised_2024');
    assert.equal(sheet.skills.investigation.rank,edition==='legacy_2014'?1:2);
    assert.equal(sheet.passives.investigation.value-sheet.skills.investigation.value,edition==='revised_2024'?10:15);
    assert.equal((await f.read(id+'-search'))?.data.timing,edition==='legacy_2014'?undefined:'bonus_action');
  }
  await f.command('define_item_definition',{definitionId:'rule-holder',name:'Integrated equipment',category:'armor',description:'Synthetic source',rarity:null,stackable:false,wearable:true,handheld:false,attunement:'none',source:'fixture'});
  await f.command('acquire_item',{itemId:'worn-source',definitionId:'rule-holder',ownerId:'combined',quantity:1,source:'fixture'});
  for(const [suffix,target] of [['skills','skill'],['saves','save'],['initiative','initiative']])
    await f.command('define_effect',{actorId:'combined',effectId:'stone-'+suffix,name:'Integrated luck',source:'fixture equipment',target,key:'all',operation:'add',
      amount:1,stackingKey:'fixture-luck',sourceItemId:'worn-source'});
  const equipment=state=>f.command('equip_item',{itemId:'worn-source',equipment:state,source:'fixture selection'});
  const numbers=async()=>{const state=await f.sheet('combined');return [
    ...skills.map(k=>state.skills[k].value),...skills.map(k=>state.passives[k].value),state.initiative.value,state.saves.DEX.value,state.abilities.INT.base];};
  await equipment('worn');assert.deepEqual(await numbers(),[5,14,9,20,29,19,3,3,20]);
  const restored=join(directory,'restored.sqlite');f.db.db.prepare('VACUUM INTO ?').run(restored);f.reopen(restored);
  for(const [id,edition] of [['v1','legacy_2014'],['v2','revised_2024'],['combined','homebrew']])
    assert.equal((await f.read(id+'-feat')).data.feat.edition,edition);
  assert.deepEqual(await numbers(),[5,14,9,20,29,19,3,3,20]);
  await equipment('packed');assert.deepEqual(await numbers(),[4,13,8,19,28,18,2,2,20]);
  assert.equal((await f.read('combined-feat')).data.feat.includedAbility.increase,1);
});
