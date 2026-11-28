import {test} from 'node:test';
import assert from 'node:assert/strict';
import {verifyReprisal} from './cloud-damage-reprisal.mjs';

function fixture(kind='attack_attempt',targetResult={}){
  const defender={id:'defender',actorId:'defender',kind:'actor',life:'active',data:{gameSeconds:6}};
  const attacker={...defender,id:'attacker',actorId:'attacker'};
  const incoming={id:'incoming',actorId:'attacker',kind,life:'active',data:{resolution:'attack',targetResults:[{
    targetActorId:'defender',decision:'hit',amount:3,healing:0,reprisalWindow:null,reprisalGameSeconds:6,...targetResult}]}};
  const rule={id:'retaliate',actorId:'defender',kind:'action_definition',life:'active',data:{type:'damage_reprisal',timing:'reaction',range:10,reactionAttacks:['close-strike']}};
  const attack={id:'close-strike',data:{delivery:'melee'}};
  const records=new Map([attacker,defender,incoming,rule].map(r=>[r.id,r]));
  const ctx={current:id=>records.get(id),get:(kind,id)=>records.get(id)?.kind===kind?records.get(id):null,require(){},put:r=>records.set(r.id,r)};
  const x={attemptId:'reply',reactionRuleId:rule.id,triggerAttemptId:incoming.id,targets:[attacker.id],targetDistanceFeet:10,mode:'normal'};
  return {check:(extra={},reach=10)=>verifyReprisal(ctx,defender,{...x,...extra},attack,reach),records,defender,incoming};
}
test('melee reprisal requires a current damaging weapon or spell attack against the defender and consumes it once',async()=>{
  for(const kind of ['attack_attempt','cast_instance']){
    const f=fixture(kind);
    await assert.rejects(f.check({targets:['other']}),/REACTION_TRIGGER_ATTACKER_MISMATCH/);
    await assert.rejects(f.check({targetDistanceFeet:11}),/REACTION_ATTACK_OUT_OF_REACH/);
    await assert.rejects(f.check({},5),/REACTION_ATTACK_OUT_OF_REACH/);
    assert.equal((await f.check()).triggerKind,kind);
    assert.equal(f.records.get('incoming').data.targetResults[0].reprisalAttemptId,'reply');
    await assert.rejects(f.check(),/DAMAGING_ATTACK_TRIGGER_REQUIRED/);
  }
  for(const result of [{decision:'miss'},{amount:0},{healing:3},{targetActorId:'other'}]){
    await assert.rejects(fixture('attack_attempt',result).check(),/DAMAGING_ATTACK_TRIGGER_REQUIRED/);
  }
  const expired=fixture();expired.defender.data.gameSeconds=7;
  await assert.rejects(expired.check(),/REACTION_TRIGGER_EXPIRED/);
});
