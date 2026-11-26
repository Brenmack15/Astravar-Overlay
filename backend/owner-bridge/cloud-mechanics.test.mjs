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
  await f.service.submit(dm,cid,first.command);await assert.rejects(f.command('resolve_attack_target',request),/TARGET_UNDECLARED_OR_ALREADY_RESOLVED/);
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
  await f.service.submit(dm,cid,promoted.command);await assert.rejects(f.command('advance_actor_level',{...args,eventId:'another-event'}),/ADVANCEMENT_LEVEL_CHANGED/);
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
  await f.service.submit(dm,cid,result.command);assert.equal((await f.read('dawn-use')).data.current,0);
  await f.command('complete_rest',{actorId:who,kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[]});
  assert.equal((await f.read('dawn-use')).data.current,0);
  const dawn={actorId:who,eventId:'dawn-event',gameSeconds:(await f.read(who)).data.gameSeconds,confirmed:true,source:'fictional observed dawn'};
  await f.command('complete_dawn_recovery',dawn);assert.equal((await f.read('dawn-use')).data.current,1);
  await assert.rejects(f.command('complete_dawn_recovery',{...dawn,eventId:'another-dawn'}),/DAWN_TIME_UNCONFIRMED_OR_RECORDED/);
});
