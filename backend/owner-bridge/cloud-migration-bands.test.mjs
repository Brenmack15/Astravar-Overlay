import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {CloudService} from './cloud-service.mjs';
import {cloudActions} from './cloud-actions-view.mjs';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {cloudParity} from './fixtures/cloud-parity.mjs';
import {wornFixture} from './fixtures/representative-forms.mjs';
import {MECHANIC_OPERATIONS,MECHANIC_KINDS} from './cloud-mechanics.mjs';
import {RECORD_OPERATIONS,RECORD_KINDS} from './cloud-records.mjs';

async function fixture(t){
  const ownerId='fixture-band-owner',continuity='qa-fixture-lifting',source='Synthetic lifting rig';
  const owner={ownerId,role:'owner',credentialId:'sites-owner'},reader={ownerId,role:'connector',credentialId:'sites-mcp'};
  const db=new SqliteAdapter();t.after(()=>db.close());
  db.exec(await readFile(new URL('./schema.sql',import.meta.url),'utf8'));
  const service=new CloudService(db,{ownerId,isolated:true,kinds:{...RECORD_KINDS,...MECHANIC_KINDS},operations:{...RECORD_OPERATIONS,...MECHANIC_OPERATIONS}});
  await service.createIsolated(owner,continuity);
  const cats=['character','combat','resources','inventory','spells','rolls'];
  await service.setGrant(owner,continuity,'sites-mcp',{scopes:cats.flatMap(k=>[k+'.read',k+'.write']),auto:cats.map(k=>k+'.write'),actors:['*'],expiresAt:Date.now()+3600000,revoked:false,expectedGrantRevision:0});
  const submit=cloudParity(()=>db,ownerId,continuity);
  async function command(operation,args){
    const reply=await submit({version:1,commandId:crypto.randomUUID(),epoch:1,expectedRevision:(await service.continuity(continuity)).revision,operation,args});
    assert.equal(reply.status,'COMMITTED');return reply.receipt.outcome;
  }
  for(const [id,size] of [['owner','medium'],['target','small'],['colossus','gargantuan']]){
    await command('define_actor',{actorId:id,name:id,role:id==='owner'?'player':'creature',rulesProfile:'revised_2024',level:3,attunementCapacity:1,
      abilities:{STR:12,DEX:10,CON:10,INT:10,WIS:10,CHA:10},baseAc:10,currentHp:10,maxHp:10,temporaryHp:0,gameSeconds:0,size});
  }
  await wornFixture({command},'rig',{attuned:true,ownerId:'owner'});
  const common={actorId:'owner',sourceItemId:'rig',requiresAttunement:true,requiresOperating:true,source,timing:'action'};
  const pool=(poolId,type,extra={})=>command('define_resource',{actorId:'owner',poolId,name:poolId,type,current:3,maximum:3,recovery:'long_rest',source,...extra});
  await pool('ties','charge');await command('bind_item_charge_pool',{itemId:'rig',poolId:'ties',source});
  await command('define_action',{...common,actionId:'bind',name:'Bind',type:'bands_control',cost:{poolId:'ties',amount:1},range:65,fixedBonus:7,
    saveAbility:'STR',saveDc:14,durationSeconds:42,maximumDurationSeconds:84,sizeCap:'huge'});
  await command('define_action',{...common,actionId:'lift',name:'Lift',type:'gravity_move',range:65,bandsPoolId:'ties',gravityLimits:[
    {size:'tiny',movementFeet:30,heightFeet:30},{size:'small',movementFeet:20,heightFeet:20}]});
  return {command,common,pool,actions:()=>cloudActions(service,reader,continuity,'owner'),read:async id=>(await service.state(reader,continuity,{id})).records[0],
    use:(actionId,useId,args)=>command('use_action',{actorId:'owner',actionId,useId,declared:true,...args})};
}
const die=v=>({origin:'player_physical',faces:[v]});
test('one physical restraint restricts movement, height, extensions, contests and escape immunity',async t=>{
  const f=await fixture(t);
  const attempt={type:'attempt',targetActorId:'target',distanceFeet:35,visible:true,hit:die(18),resist:die(1)};
  await assert.rejects(f.use('bind','invisible',{bands:{...attempt,visible:false}}),/BANDS_TARGET_INELIGIBLE/);
  await assert.rejects(f.use('bind','too-large',{bands:{...attempt,targetActorId:'colossus'}}),/BANDS_SIZE_OR_CLOCK_UNKNOWN/);
  await f.use('bind','snare-one',{bands:attempt});
  assert.equal((await f.read('ties')).data.current,2);
  const motion={type:'move',targetActorId:'target',distanceFeet:35,endDistanceFeet:35,movementFeet:20,heightFeet:20,hand:'left',freeHand:true,destination:'test point'};
  await assert.rejects(f.use('lift','wrong-target',{gravity:{...motion,targetActorId:'colossus'}}),/OWN_PHYSICAL_BAND_RESTRAINT_REQUIRED/);
  await assert.rejects(f.use('lift','too-high',{gravity:{...motion,heightFeet:21}}),/GRAVITY_MOVEMENT_OR_HEIGHT_EXCEEDED/);
  await assert.rejects(f.use('lift','too-far',{gravity:{...motion,movementFeet:21}}),/GRAVITY_MOVEMENT_OR_HEIGHT_EXCEEDED/);
  await f.use('lift','lift-one',{gravity:motion});
  const base={targetActorId:'target',distanceFeet:35};
  const hold=await f.use('lift','hold',{gravity:{...base,type:'hold'}});assert.equal(hold.timing,'other');
  const actorId='owner',controllerId='owner',sourceItemId='rig',targetActorId='target',source='Fixture observations';
  await assert.rejects(f.use('lift','other-hand',{gravity:{...motion,hand:'right'}}),/GRAVITY_CONTROLLING_HAND_UNAVAILABLE/);
  await f.use('lift','transfer',{gravity:{...base,type:'transfer',hand:'right',freeHand:true}});
  await f.use('bind','extend-one',{bands:{...base,type:'extend',visible:true}});
  await assert.rejects(f.use('bind','too-long',{bands:{...base,type:'extend',visible:true}}),/BANDS_DURATION_LIMIT/);
  const control={actorId,controllerId,sourceItemId,targetActorId,source};
  const tie=await f.command('resolve_band_control',{...control,reason:'contest',actorId:'colossus',distanceFeet:5,check:die(10),defense:die(10)});
  assert.equal(tie.released,false);
  const win=await f.command('resolve_band_control',{...control,reason:'contest',actorId:'colossus',distanceFeet:5,check:die(20),defense:die(1)});
  assert.equal(win.fallHeightFeet,20);
  assert.ok((await f.read('target')).data.conditions.includes('restrained'));
  const escaped=await f.command('resolve_band_control',{...control,actorId:'target',reason:'escape',check:die(17)});
  assert.ok(escaped.escaped);assert.ok(!(await f.read('target')).data.conditions.includes('restrained'));
  await assert.rejects(f.use('bind','immune',{bands:attempt}),/BANDS_TEMPORARY_IMMUNITY/);
  await f.command('resolve_band_control',{...control,actorId:'target',reason:'turn_ended',confirmed:true});
  await f.use('bind','next-attempt',{bands:attempt});
  assert.equal((await f.read('ties')).data.current,0);
});
test('one prepared payload is purged on its own equipment without restoring a slot',async t=>{
  const f=await fixture(t),{common}=f;
  await f.command('define_spell',{actorId:'owner',spellId:'test-arc',name:'Test arc',source:'fixture',baseLevel:1,concentration:false,
    ritual:false,resolution:'automatic',timing:'action',components:[{id:'impact',name:'Impact',type:'force',dice:1,sides:4,flat:0}]});
  await f.command('define_action',{...common,actionId:'store',name:'Store',type:'store_payload',timing:'bonus_action',capacity:1,releasesPerTurn:1,
    payloadVariants:[{id:'arc',spellId:'test-arc',level:1,slotLevel:1}]});
  await f.command('define_action',{...common,actionId:'purge',name:'Purge',type:'purge_payload',timing:'bonus_action'});
  await f.pool('slot','spell_slot',{slotLevel:1});
  await assert.rejects(f.use('purge','premature',{}),/STORED_PAYLOAD_UNAVAILABLE/);
  const load={variantId:'arc',slotPoolId:'slot'};
  await assert.rejects(f.use('store','ambiguous',{load:{...load,scrollItemId:'rig'}}),/ONE_PAYLOAD_SOURCE_REQUIRED/);
  await f.use('store','payload',{load});
  assert.equal((await f.read('slot')).data.current,2);
  await assert.rejects(f.use('store','full',{load}),/RESERVOIR_FULL/);
  await f.use('purge','purged',{});
  assert.deepEqual((await f.read('rig')).data.storedPayloadIds,[]);
  assert.equal((await f.read('payload')).data.purgedBy,'purged');
  assert.equal((await f.read('slot')).data.current,2);
  await assert.rejects(f.use('purge','second',{}),/STORED_PAYLOAD_UNAVAILABLE/);
});
test('reviewed instantaneous damage can be stored from a matching scroll without importing its ordinary riders',async t=>{
  const f=await fixture(t),{common}=f;
  await f.command('define_spell',{actorId:'owner',spellId:'sample-wave',name:'Sample spell',source:'fixture',baseLevel:1,concentration:false,ritual:false,
    resolution:'manual',timing:'action',duration:'Instantaneous',components:[]});
  const part=(id,type,dice,sides,flat=0)=>({id,name:id,type,dice,sides,flat,critical:true});
  await f.command('define_action',{...common,actionId:'library',name:'Library',type:'store_payload',timing:'bonus_action',capacity:1,releasesPerTurn:1,levelBonusDieSides:6,
    payloadVariants:[{id:'wave',spellId:'sample-wave',level:1,slotLevel:1,allowSpellLinkedScroll:true,directDamage:[part('pulse','radiant',2,4)],directDamageSource:'Fixture damage confirmation'}]});
  await f.command('define_action',{...common,actionId:'purge',name:'Purge',type:'purge_payload',timing:'bonus_action'});
  await f.command('define_item_definition',{definitionId:'scroll-def',name:'Sample scroll',category:'consumable',description:'Bound damage projection',rarity:null,
    stackable:true,wearable:false,handheld:false,attunement:'none',scrollSpellId:'sample-wave',scrollLevel:1,source:'fixture'});
  await f.command('acquire_item',{itemId:'scrolls',definitionId:'scroll-def',ownerId:'owner',quantity:2,source:'fixture'});
  await f.pool('slots','spell_slot',{slotLevel:1});
  await f.command('define_attack',{...common,attackId:'projected',name:'Projected strike',delivery:'melee',resolution:'attack',timing:'attack',fixedHit:6,reach:5,
    payloadRule:{componentId:'ordinary',storeActionId:'library'},components:[part('physical','bludgeoning',1,6),part('ordinary','fire',1,4)]});
  const attack=(attemptId,payload)=>f.command('resolve_attack',{actorId:'owner',attackId:'projected',attemptId,mode:'normal',hit:die(14),includeDamage:false,payload});
  const load={variantId:'wave',scrollItemId:'scrolls'};
  await assert.rejects(f.use('library','wrong-item',{load:{...load,scrollItemId:'rig'}}),/SCROLL_DEFINITION_REQUIRED/);
  await f.use('library','first',{load});assert.equal((await f.read('scrolls')).data.quantity,1);
  await f.command('complete_rest',{actorId:'owner',kind:'short_rest',elapsedSeconds:3600,confirmed:true,hitDice:[]});
  assert.deepEqual((await f.read('rig')).data.storedPayloadIds,['first']);
  await assert.rejects(attack('missing-rule',{variantId:'wave',storedUseId:'first'}),/PAYLOAD_LEVEL_DAMAGE_ADJUDICATION_REQUIRED/);
  assert.deepEqual((await f.read('rig')).data.storedPayloadIds,['first']);
  const levelBonusAdjudication={type:'cold',source:'Synthetic per-use ruling'};
  const shot=await attack('stored',{variantId:'wave',storedUseId:'first',levelBonusAdjudication});
  assert.deepEqual(shot.components.map(c=>[c.type,c.dice,c.sides]),[['bludgeoning',1,6],['radiant',2,4],['cold',1,6]]);
  assert.deepEqual(shot.payload.levelBonusAdjudication,levelBonusAdjudication);
  await assert.rejects(attack('immediate-same-turn',{variantId:'wave',slotPoolId:'slots',levelBonusAdjudication}),/PAYLOAD_TURN_LIMIT/);
  assert.equal((await f.read('slots')).data.current,3);
  await f.command('complete_rest',{actorId:'owner',kind:'short_rest',elapsedSeconds:3600,confirmed:true,hitDice:[]});
  const instant=await attack('immediate',{variantId:'wave',slotPoolId:'slots',levelBonusAdjudication});
  assert.equal(instant.costs[0].after,2);
  await f.command('define_action',{...common,actionId:'prepare',name:'Prepared grip',type:'prepare_strike',timing:'bonus_action',
    requiresWeaponBus:true,chargeAdjudicationRequired:true,chargeAbilities:['STR','INT']});
  await assert.rejects(f.use('prepare','missing-type',{}),/CHARGE_DAMAGE_ADJUDICATION_REQUIRED/);
  await f.use('prepare','preparation',{chargeAdjudication:{type:'bludgeoning',source:'Synthetic charge ruling'}});
  assert.equal((await f.read('rig')).data.preparedStrike.flat,1);
});
test('two independent worn items qualify an extra die and a magical natural strike without adding a second flat bonus',async t=>{
  const f=await fixture(t),who='owner',source='Fixture natural rule',key='natural-strike';
  await wornFixture(f,'charm',{ownerId:who});
  const effect=(id,target,operation,attributes)=>f.command('define_effect',{actorId:who,effectId:id,name:id,source,target,operation,...attributes});
  await effect('qualifier','natural_attack_magic','grant',{sourceItemId:'charm',requiresWorn:true,key:'natural'});
  const part=(id,dice,sides,flat)=>({id,name:id,type:'piercing',dice,sides,flat,critical:true});
  await f.command('define_attack',{actorId:who,attackId:key,name:key,source,delivery:'melee',resolution:'attack',timing:'attack',fixedHit:5,reach:5,
    classification:'natural',enforceReach:true,naturalMagicItemId:'charm',naturalMagicEffectId:'qualifier',naturalMagicRequiresAttunement:false,components:[part('main',1,8,2)]});
  await effect('extra','attack_component','add',{sourceItemId:'rig',key,requiresAttunement:true,requiresWorn:true,
    extraComponent:{...part('rider',1,4,0),magical:false},inheritAttackMagic:true});
  for(const target of ['attack','damage'])await effect('bonus-'+target,target,'add',{sourceItemId:'charm',key,requiresWorn:true,amount:1});
  const strike=(attemptId,args={})=>f.command('resolve_attack',{actorId:who,attackId:key,attemptId,mode:'normal',hit:die(20),targetDistanceFeet:5,includeDamage:false,...args});
  const profile=out=>out.components.map(d=>[d.id,d.dice,d.sides,d.flat,d.magical]);
  await assert.rejects(strike('far',{targetDistanceFeet:6}),/ATTACK_OUT_OF_REACH/);
  const expected=[['main',1,8,3,true],['rider',1,4,0,true]];
  const preview=(await f.actions()).rows.find(a=>a.id===key);
  assert.deepEqual(profile(preview),expected);
  const critical=await strike('critical',{includeDamage:true,damage:[{id:'main',evidence:{origin:'player_physical',faces:[5,3]}},{id:'rider',evidence:{origin:'player_physical',faces:[4,2]}}]});
  assert.equal(critical.critical,true);assert.equal(critical.hit.modifier,6);assert.deepEqual(profile(critical),expected);
  assert.deepEqual(critical.damage.map(d=>[d.total,d.magical]),[[11,true],[6,true]]);
  await f.command('equip_item',{itemId:'charm',equipment:'packed',source});
  const ordinary=await strike('ordinary');assert.equal(ordinary.hit.modifier,5);assert.deepEqual(profile(ordinary),[['main',1,8,2,false],['rider',1,4,0,false]]);
  await f.command('equip_item',{itemId:'charm',equipment:'worn',source});
  await f.command('set_item_attunement',{itemId:'rig',attuned:false,elapsedSeconds:0,confirmed:true,source});
  assert.deepEqual(profile(await strike('without-rig')),[expected[0]]);
});
test('recognized ally protection requires a visible interceptable attack and applies its fixed reduction once',async t=>{
  const f=await fixture(t),source='Fixture recognition',actionId='interpose';
  await f.command('define_action',{actorId:'owner',actionId,name:actionId,source,type:'deflection',timing:'reaction',range:5,recognizedAllyInterception:true,reduction:{dice:2,sides:6,flat:2}});
  const id='swipe';
  await f.command('define_attack',{actorId:'colossus',attackId:id,name:id,source,delivery:'melee',resolution:'attack',timing:'attack',fixedHit:7,components:[{id:'hit',name:'Hit',dice:1,sides:12,flat:6,type:'bludgeoning'}]});
  await f.command('resolve_attack',{actorId:'colossus',attackId:id,attemptId:'incoming',mode:'normal',targets:['target'],hit:die(15),includeDamage:true,
    damage:[{id:'hit',evidence:die(9)}]});
  const eligible={recognized:true,possible:true,source};
  const deflect=(useId,overrides)=>f.use(actionId,useId,{deflection:{attemptId:'incoming',targetActorId:'target',distanceFeet:5,visible:true,
    dice:{origin:'player_physical',faces:[2,4]},interception:eligible,...overrides}});
  for(const [id,args] of [['unrecognized',{interception:{...eligible,recognized:false}}],['blocked',{interception:{...eligible,possible:false}}],['distant',{distanceFeet:6}]]){
    await assert.rejects(deflect(id,args),/DEFLECTION_WINDOW_UNAVAILABLE/);
  }
  const result=await deflect('accepted',{});
  assert.equal(result.deflection.roll.total,8);assert.equal(result.deflection.allocation[0].amount,8);
  await assert.rejects(deflect('repeated',{}),/DEFLECTION_WINDOW_UNAVAILABLE/);
});
