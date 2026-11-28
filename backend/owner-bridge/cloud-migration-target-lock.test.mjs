import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFile} from 'node:fs/promises';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {CloudService} from './cloud-service.mjs';
import {MECHANIC_KINDS,MECHANIC_OPERATIONS} from './cloud-mechanics.mjs';
import {RECORD_KINDS,RECORD_OPERATIONS} from './cloud-records.mjs';
import {wornFixture} from './fixtures/representative-forms.mjs';

test('tracking nominates one target, consumes its source, applies cover and range privileges, and ends on release or rest',async t=>{
  const ownerId='synthetic-targeting',continuity='qa-targeting-test',actorId='fixture-reader',source='Fixture optics';
  const owner={role:'owner',ownerId,credentialId:'sites-owner'},db=new SqliteAdapter();
  t.after(()=>db.close()); db.exec(await readFile(new URL('./schema.sql',import.meta.url),'utf8'));
  const service=new CloudService(db,{ownerId,isolated:true,kinds:{...RECORD_KINDS,...MECHANIC_KINDS},operations:{...RECORD_OPERATIONS,...MECHANIC_OPERATIONS}});
  await service.createIsolated(owner,continuity);
  const command=async(operation,args)=>{
    const reply=await service.submit(owner,continuity,{version:1,epoch:1,commandId:crypto.randomUUID(),expectedRevision:(await service.continuity(continuity)).revision,operation,args});
    assert.equal(reply.status,'COMMITTED');return reply.receipt.outcome;
  };
  for(const id of [actorId,'blue','pink'])await command('define_actor',{actorId:id,name:id,role:id===actorId?'player':'creature',rulesProfile:'revised_2024',level:4,gameSeconds:0,maxHp:18,currentHp:18,temporaryHp:0,
    abilities:{STR:12,DEX:13,CON:14,INT:12,WIS:15,CHA:13}});
  await wornFixture({command},'optic',{ownerId:actorId});
  await command('define_resource',{actorId,poolId:'tracking-use',name:'Tracking uses',type:'ordinary',source,current:1,maximum:1,recovery:'short_rest'});
  await command('define_action',{actorId,actionId:'tracking',name:'Track',source,timing:'bonus_action',type:'weapon_target_lock',
    sourceItemId:'optic',requiresOperating:true,cost:{poolId:'tracking-use',amount:1},lockRangeFeet:90});
  await command('define_attack',{actorId,attackId:'ray',name:'Relay',source,timing:'attack',delivery:'ranged',resolution:'attack',fixedHit:4,range:25,longRange:50,longstrikeLimit:90,
    sourceItemId:'optic',requiresOperating:true,components:[{id:'pulse',name:'Pulse',type:'force',dice:1,sides:6,flat:2}]});
  const evidence=faces=>({origin:'player_physical',faces});
  const aim=(target='blue',extra={})=>command('use_action',{actorId,actionId:'tracking',declared:true,useId:crypto.randomUUID(),
    lockTarget:{targetActorId:target,distanceFeet:55,seenOrResolved:true,totalCover:false,...extra}});
  const fire=(targetActorId,distance,faces=[7,14],extra={})=>command('resolve_attack',{actorId,attackId:'ray',attemptId:crypto.randomUUID(),hit:evidence(faces),
    mode:'normal',targets:[targetActorId],targetDistanceFeet:distance,includeDamage:false,longstrike:{targetResolved:true,viablePath:true,totalCover:false},...extra});
  const rest=()=>command('complete_rest',{actorId,kind:'short_rest',elapsedSeconds:3600,hitDice:[],confirmed:true});
  await assert.rejects(aim('blue',{totalCover:true}),/LOCK_TARGET_UNAVAILABLE/);
  await assert.rejects(aim('blue',{distanceFeet:91}),/LOCK_TARGET_UNAVAILABLE/);
  await assert.rejects(fire('blue',60),/LONG_RANGE_DISADVANTAGE/);
  await aim();await assert.rejects(aim('pink'),/RESOURCE_EMPTY_OR_UNKNOWN/);
  const result=await fire('blue',90);
  assert.equal(result.hit.natural,14);assert.equal(result.weaponTargetLock.targetActorId,'blue');assert.equal(result.weaponTargetLock.ignoresPartialCover,true);
  await assert.rejects(fire('pink',90),/LONG_RANGE_DISADVANTAGE/);
  await assert.rejects(fire('blue',91),/SHOT_OUT_OF_RANGE/);
  await assert.rejects(fire('blue',90,[7,14],{longstrike:{targetResolved:true,viablePath:true,totalCover:true}}),/LONGSTRIKE_TARGET_UNCONFIRMED/);
  await command('release_weapon_target_lock',{actorId,targetActorId:'blue',reason:'released',confirmed:true});
  await assert.rejects(fire('blue',90),/LONG_RANGE_DISADVANTAGE/);
  await rest();await aim();await rest();
  await assert.rejects(fire('blue',90),/LONG_RANGE_DISADVANTAGE/);
});
