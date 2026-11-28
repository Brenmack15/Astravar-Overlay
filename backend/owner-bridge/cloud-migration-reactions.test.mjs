import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {CloudService} from './cloud-service.mjs';
import {RECORD_KINDS,RECORD_OPERATIONS} from './cloud-records.mjs';
import {MECHANIC_KINDS,MECHANIC_OPERATIONS} from './cloud-mechanics.mjs';
import {cloudParity} from './fixtures/cloud-parity.mjs';

const observed=(...faces)=>({origin:'player_physical',faces});
async function lab(t) {
  const db=new SqliteAdapter();t.after(()=>db.close());
  db.exec(await readFile(new URL('./schema.sql',import.meta.url),'utf8'));
  const ownerId='synthetic-reactions',continuity='qa-reactions',source='Fictional test fixture';
  const owner={role:'owner',ownerId,credentialId:'sites-owner'};
  const service=new CloudService(db,{ownerId,isolated:true,kinds:{...RECORD_KINDS,...MECHANIC_KINDS},operations:{...RECORD_OPERATIONS,...MECHANIC_OPERATIONS}});
  await service.createIsolated(owner,continuity);
  const scopes=['character','inventory','rolls','resources','combat','spells'].flatMap(v=>[v+'.read',v+'.write']);
  await service.setGrant(owner,continuity,'sites-mcp',{scopes,auto:scopes.filter(v=>v.endsWith('.write')),actors:['*'],revoked:false,expiresAt:Date.now()+3600000,expectedGrantRevision:0});
  const submit=cloudParity(()=>db,ownerId,continuity);
  const command=async(operation,args)=>{const result=await submit({version:1,epoch:1,commandId:crypto.randomUUID(),expectedRevision:(await service.continuity(continuity)).revision,operation,args});return result.receipt.outcome;};
  const data=id=>JSON.parse(db.db.prepare('SELECT data_json FROM cloud_entities WHERE id=?').get(id).data_json);
  const seeded=(id,kind,actorId,value)=>db.db.prepare("INSERT INTO cloud_entities(owner_id,continuity_id,id,kind,actor_id,title,data_json,life,revision) VALUES(?,?,?,?,?,?,?,'active',1)").run(ownerId,continuity,id,kind,actorId,id,JSON.stringify(value));
  const keeper='keeper',attacker='duelist',armor='shell';
  for(const actorId of [keeper,attacker])await command('define_actor',{actorId,name:actorId,role:'player',rulesProfile:'revised_2024',level:6,baseAc:14,gameSeconds:0,abilities:{STR:14,DEX:14,CON:10,INT:10,WIS:10,CHA:10},maxHp:40,currentHp:40,temporaryHp:0});
  seeded('shell-type','item_definition',null,{handheld:false,wearable:true,attunement:'required',activation:'manual'});
  seeded(armor,'item_instance',keeper,{definitionId:'shell-type',ownerId:keeper,custodianId:keeper,quantity:1,partyAccess:true,locationId:null,equipment:'worn',attuned:true,operating:true,holding:'carried'});
  await command('define_resource',{actorId:keeper,poolId:'shared',name:'Shared reaction',source,type:'charge',maximum:1,current:1,recovery:'short_rest'});
  const common={actorId:keeper,name:'Fixture reaction',type:'equipment_reaction',sourceItemId:armor,requiresAttunement:true,requiresOperating:true,timing:'reaction',source};
  for(const [actionId,reactionRule,detail] of [['brace','ground_displacement',{maxReductionFeet:6}],['guard','physical_guard',{cost:{poolId:'shared',amount:1}}],['reflect','physical_reflection',{fixedBonus:4,cost:{poolId:'shared',amount:1}}]])
    await command('define_action',{...common,actionId,reactionRule,...detail});
  for(const [attackId,classification,delivery] of [['sword','weapon','melee'],['ray','ray','ranged']])
    await command('define_attack',{actorId:attacker,attackId,name:attackId,source,timing:'attack',delivery,classification,resolution:'attack',fixedHit:4,
      components:[{id:'blade',name:'Blade',type:'slashing',dice:1,sides:10,flat:3,magical:true}]});
  const use=(actionId,useId,details)=>command('use_action',{actorId:keeper,actionId,useId,declared:true,...details});
  const strike=(attackId,attemptId,face=18)=>command('resolve_attack',{actorId:attacker,attackId,attemptId,targets:[keeper],mode:'normal',hit:observed(face),includeDamage:true,damage:[{id:'blade',evidence:observed(8)}]});
  const settle=attemptId=>command('resolve_attack_target',{actorId:attacker,targetActorId:keeper,attemptId,source});
  return {use,strike,settle,command,data,seeded,keeper,attacker};
}

test('physical reflection on a hit or a miss spends the shared resource; only a hit reflects Force',async t=>{
  for(const [label,face,protectedHp,sourceHp] of [['hit',15,40,35],['miss',2,29,40]]){
    await t.test(label,async t=>{
      const f=await lab(t);await f.strike('sword','incoming');
      const use=await f.use('reflect','reaction',{incomingStrike:{attemptId:'incoming',reflectionRoll:observed(face)}});
      assert.equal(f.data('shared').current,0);
      assert.equal(use.equipmentGuard.negated,label==='hit');
      await assert.rejects(f.use('guard','again',{incomingStrike:{attemptId:'incoming'}}),/PHYSICAL_HIT_REQUIRED/);
      const result=await f.settle('incoming');assert.equal(result.decision,'hit');
      assert.equal(f.data(f.keeper).currentHp,protectedHp);assert.equal(f.data(f.attacker).currentHp,sourceHp);
      if(label==='hit')assert.deepEqual([result.reflection.type,result.reflection.rolled,result.reflection.applied],['force',5,5]);
      await assert.rejects(f.settle('incoming'),/TARGET_UNDECLARED_OR_ALREADY_RESOLVED/);
    });
  }
});

test('physical guard rejects a ray with physical damage, an incoming miss, and packed equipment',async t=>{
  const f=await lab(t);
  await f.strike('ray','beam');await assert.rejects(f.use('guard','no-ray',{incomingStrike:{attemptId:'beam'}}),/PHYSICAL_HIT_REQUIRED/);
  await f.strike('sword','missed',1);await assert.rejects(f.use('guard','no-miss',{incomingStrike:{attemptId:'missed'}}),/PHYSICAL_HIT_REQUIRED/);
  await f.strike('sword','actual');
  await f.command('equip_item',{itemId:'shell',equipment:'packed',source:'Isolated fixture'});
  await assert.rejects(f.use('guard','no-gear',{incomingStrike:{attemptId:'actual'}}),/ACTION_ITEM_INELIGIBLE/);
  assert.equal(f.data('shared').current,1);
});

test('ground displacement reduces only the confirmed remaining distance and reserves no shared charge',async t=>{
  const f=await lab(t),ground={eventId:'push',forced:true,alongGround:true,distanceFeet:9,reductionFeet:5};
  await assert.rejects(f.use('brace','invalid',{groundDisplacement:{...ground,reductionFeet:7}}),/INVALID_GROUND_DISPLACEMENT/);
  await assert.rejects(f.use('brace','air',{groundDisplacement:{...ground,alongGround:false}}),/INVALID|SCHEMA/);
  const r=await f.use('brace','braced',{groundDisplacement:ground});
  assert.equal(r.groundDisplacement.remainingFeet,4);assert.equal(f.data('shared').current,1);
  await assert.rejects(f.use('brace','duplicate-event',{groundDisplacement:ground}),/GROUND_DISPLACEMENT_ALREADY_ADJUDICATED/);
  await f.strike('sword','new-hit');
  await f.use('guard','guarded',{incomingStrike:{attemptId:'new-hit'}});
  const result=await f.settle('new-hit');
  assert.equal(result.amount,0);assert.equal(result.decision,'hit');assert.equal(f.data(f.attacker).currentHp,40);
});

test('ground protection uses the same tracked reaction as a guard and never consumes the shared guard charge',async t=>{
  const f=await lab(t);
  for(const [actorId,face] of [[f.attacker,15],[f.keeper,5]])
    await f.command('record_check',{actorId,rollId:'initiative-'+actorId,check:{kind:'initiative',key:'initiative',mode:'normal'},evidence:observed(face)});
  await f.command('start_encounter',{encounterId:'turns',name:'Tracked encounter',participants:[f.attacker,f.keeper].map(actorId=>({actorId,initiativeRollId:'initiative-'+actorId}))});
  await f.strike('sword','tracked');
  await f.use('brace','grounded',{groundDisplacement:{eventId:'forced',forced:true,alongGround:true,distanceFeet:4,reductionFeet:4}});
  await assert.rejects(f.use('guard','no-reaction',{incomingStrike:{attemptId:'tracked'}}),/ACTION_UNAVAILABLE/);
  assert.equal(f.data('shared').current,1);
  assert.equal(f.data('tracked').equipmentGuard,undefined);
});

test('reflected Force respects the original attacker’s damage resistance',async t=>{
  const f=await lab(t);
  await f.command('define_effect',{actorId:f.attacker,effectId:'ward',name:'Force ward',source:'Fixture',target:'damage_defense',key:'force',operation:'resistance'});
  await f.strike('sword','warded');
  await f.use('reflect','bounce',{incomingStrike:{attemptId:'warded',reflectionRoll:observed(19)}});
  const result=await f.settle('warded');
  assert.deepEqual([result.reflection.rolled,result.reflection.applied,f.data(f.attacker).currentHp],[5,2,38]);
});

test('a critical protection on the declared target preserves its natural twenty hit and normalizes weapon and spell damage',async t=>{
  const f=await lab(t),source='Fixture';
  await f.command('define_effect',{actorId:f.keeper,effectId:'reinforced',name:'Reinforcement',source,target:'critical_defense',key:'all',operation:'immunity',sourceItemId:'shell',requiresWorn:true,requiresAttunement:true});
  const protectedStrike=await f.strike('sword','protected',20);
  assert.equal(protectedStrike.critical,false);
  assert.deepEqual(protectedStrike.criticalWards,[{targetActorId:f.keeper,effectId:'reinforced'}]);
  assert.equal(protectedStrike.hit.natural,20);
  assert.equal((await f.settle('protected')).decision,'hit');
  await f.command('define_spell',{actorId:f.attacker,spellId:'spark',name:'Spark',source,baseLevel:0,timing:'action',concentration:false,ritual:false,resolution:'attack',components:[{id:'ray',name:'Ray',type:'fire',dice:1,sides:6,flat:0}]});
  await f.command('grant_spell',{actorId:f.attacker,spellId:'spark',grantId:'learned',source,route:'at_will',levels:[0],preparation:'not_required',fixedHit:4});
  await f.command('cast_spell',{actorId:f.attacker,grantId:'learned',castId:'aimed',level:0,targets:[f.keeper]});
  const cast=await f.command('link_cast_roll',{actorId:f.attacker,castId:'aimed',hit:observed(20),damage:[{id:'ray',evidence:observed(3)}]});
  assert.equal(cast.criticalWards[0].effectId,'reinforced');
  await f.command('equip_item',{itemId:'shell',equipment:'packed',source});
  await assert.rejects(f.strike('sword','unprotected',20),/INVALID_RECORDED_FACES/);
  const ordinary=await f.command('resolve_attack',{actorId:f.attacker,attackId:'sword',attemptId:'unprotected',targets:[f.keeper],mode:'normal',hit:observed(20),includeDamage:true,damage:[{id:'blade',evidence:observed(8,3)}]});
  assert.equal(ordinary.critical,true);
});

test('an active warning effect reaches a known party ally at the encounter onset, and awakens only natural sleep',async t=>{
  const f=await lab(t),source='Fixture';
  await f.command('define_effect',{actorId:f.keeper,effectId:'lookout',name:'Lookout',source,target:'roll_edge',key:'initiative:initiative',operation:'advantage',
    warningRadiusFeet:15,sourceItemId:'shell',requiresWorn:true,requiresAttunement:true,requiresOperating:true});
  const record=(rollId,distanceFeet,faces,extra={})=>f.command('record_check',{actorId:f.attacker,rollId,check:{kind:'initiative',key:'initiative',mode:'normal',
    onsetWarning:{wearerId:f.keeper,effectId:'lookout',distanceFeet},...extra},evidence:observed(...faces)});
  for(const key of ['natural_sleep','magical_sleep'])await f.command('set_condition',{actorId:f.attacker,key,enabled:true,sourceId:'source'});
  await assert.rejects(record('outside',16,[5,8]),/WARNING_OUT_OF_RANGE_OR_INACTIVE/);
  assert.equal(f.data(f.attacker).conditions.includes('natural_sleep'),true);
  const accepted=await record('nearby',15,[5,8]);
  assert.equal(accepted.onsetWarning.awakened,true);assert.equal(accepted.mode,'advantage');
  assert.deepEqual(f.data(f.attacker).conditions,['magical_sleep']);
  await assert.rejects(record('other',5,[8,11],{kind:'ability',key:'STR'}),/WARNING_ONSET_REQUIRED/);
  await f.command('equip_item',{itemId:'shell',equipment:'packed',source});
  await assert.rejects(record('packed',4,[5,8]),/WARNING_OUT_OF_RANGE_OR_INACTIVE/);
});

test('a removable anchor needs its accessible original and a current opposing Athletics roll; overload ends only its anchored mode',async t=>{
  const f=await lab(t),source='Fixture';
  f.seeded('insert-type','item_definition',null,{handheld:true,wearable:false,attunement:'none',activation:'manual'});
  f.seeded('insert','item_instance',f.keeper,{definitionId:'insert-type',ownerId:f.keeper,custodianId:f.keeper,quantity:1,partyAccess:true,locationId:null,equipment:'packed',attuned:false,operating:true,holding:'carried'});
  await f.command('define_action',{actorId:f.keeper,name:'Interlocked anchor',actionId:'anchor',type:'equipment_mode',sourceItemId:'shell',requiresAttunement:true,requiresOperating:true,timing:'action',source,
    activateModes:[{id:'anchored',seconds:60},{id:'shielded',seconds:100}],cost:{poolId:'shared',amount:1},anchorRule:{itemId:'insert',modeId:'anchored',maximumPounds:90,dc:12,displacementFeet:5}});
  await assert.rejects(f.use('anchor','failed',{confirmAnchorDock:true}),/ANCHOR_ROD_UNAVAILABLE/);assert.equal(f.data('shared').current,1);
  await f.command('equip_item',{itemId:'insert',equipment:'held',source});
  await f.use('anchor','active',{confirmAnchorDock:true});
  const resolve=details=>f.command('resolve_equipment_anchor',{actorId:f.keeper,modeUseId:'active',...details});
  const check=(id,face)=>f.command('record_check',{actorId:f.attacker,rollId:id,check:{kind:'skill',key:'athletics',mode:'normal'},evidence:observed(face)});
  const move=(rollId,distanceFeet)=>resolve({opposingActorId:f.attacker,rollId,distanceFeet,...(distanceFeet?{destination:'new spot'}:{})});
  await check('failed-check',3);await assert.rejects(move('failed-check',1),/ANCHOR_DISPLACEMENT_EXCEEDED/);
  assert.equal((await move('failed-check',0)).success,false);
  await check('beat-dc',14);await assert.rejects(move('beat-dc',6),/ANCHOR_DISPLACEMENT_EXCEEDED/);
  assert.equal((await move('beat-dc',5)).success,true);await assert.rejects(move('beat-dc',5),/ANCHOR_ATHLETICS_ROLL_REQUIRED/);
  assert.equal((await resolve({loadPounds:90})).deactivated,false);assert.equal((await resolve({loadPounds:91})).deactivated,true);
  assert.deepEqual(f.data(f.keeper).equipmentModes.map(m=>m.id),['shielded']);
});

test('striding preserves ordinary immobilization and records only physically possible movement',async t=>{
  const f=await lab(t),source='Fixture';
  await f.command('define_effect',{actorId:f.keeper,effectId:'stride',name:'Stride',source,target:'equipment_property',key:'striding',operation:'grant',
    sourceItemId:'shell',requiresWorn:true,requiresAttunement:true,strideRule:{minimumWalkFeet:30,jumpMultiplier:3,ignoreEncumbrance:true,ignoreHeavyArmor:true}});
  await f.command('define_effect',{actorId:f.keeper,effectId:'pace',name:'Walking',source,target:'speed',key:'walk',operation:'set',amount:30});
  const jump=(distanceFeet,extra={})=>f.command('resolve_equipment_jump',{actorId:f.keeper,effectId:'stride',jump:'long',runUpFeet:10,remainingMovementFeet:20,distanceFeet,destination:'new step',source,...extra});
  await assert.rejects(jump(21),/JUMP_EXCEEDS_DISTANCE_OR_MOVEMENT/);
  assert.equal((await jump(20)).remainingFeet,0);
  assert.equal((await jump(35,{dash:true})).remainingFeet,15);
  await f.command('set_condition',{actorId:f.keeper,key:'restrained',enabled:true,sourceId:'restraint'});
  await assert.rejects(jump(1),/JUMP_EQUIPMENT_UNAVAILABLE/);
});
