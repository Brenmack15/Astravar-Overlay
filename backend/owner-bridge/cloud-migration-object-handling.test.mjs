import assert from 'node:assert/strict';
import {test} from 'node:test';
import {assessHandling} from './cloud-object-handling.mjs';
test('handling capacity respects method, total load, object suitability and missing hands',()=>{
  const limits={carryPounds:12,pushDragLiftPounds:24,hasHands:false};
  const query=(method,totalPounds,anatomicallySuitable=true,requiresHands=false)=>assessHandling(limits,{method,totalPounds,anatomicallySuitable,requiresHands});
  assert.equal(query('carry',12).eligible,true);assert.equal(query('carry',13).eligible,false);
  for(const method of ['drag','push','lift']){
    assert.equal(query(method,24).eligible,true);assert.equal(query(method,25).eligible,false);
  }
  assert.equal(query('carry',1,false).eligible,false);assert.equal(query('carry',1,true,true).eligible,false);
  assert.equal(query('carry',1).transferred,false);
});

import {activeCastAids} from './cloud-cast-aid.mjs';
test('a recipient-scoped cast aid requires the authorized caster, actual spell and grant, and current concentration',async()=>{
  const rule={family:'fixture-support',sides:6,applies:['attack','save']};
  const actor={kind:'actor',id:'caster',life:'active',data:{concentrationCastId:'cast'}};
  const recipient={id:'recipient',data:{gameSeconds:1,sensoryFeatureIds:['feature'],rollAidRefs:[{casterId:'caster',castId:'cast',expiresAt:60}]}};
  const feature={id:'feature',kind:'feature',life:'active',actorId:recipient.id,data:{acceptedCastAid:{...rule,casterId:actor.id}}};
  const grant={id:'grant',kind:'spell_grant',life:'active',actorId:actor.id,data:{spellId:'spell'}};
  const spell={id:'spell',kind:'spell',life:'active',actorId:actor.id,data:{rollAid:rule}};
  const cast={id:'cast',kind:'cast_instance',life:'active',actorId:actor.id,revision:2,data:{rollAid:rule,targets:[recipient.id],spellId:spell.id,grantId:grant.id}};
  const records=new Map([actor,feature,grant,spell,cast].map(r=>[r.id,r]));
  const project=()=>activeCastAids({current:id=>records.get(id)},recipient);
  assert.deepEqual((await project())[0].sourceFeatureIds,[feature.id]);
  feature.data.acceptedCastAid.casterId='another';const [ordinary]=await project();
  assert.equal(ordinary.family,rule.family);assert.equal(ordinary.sourceFeatureIds,undefined);
  feature.data.acceptedCastAid.casterId=actor.id;grant.data.spellId='other-spell';assert.deepEqual(await project(),[]);
  grant.data.spellId=spell.id;actor.data.concentrationCastId=null;assert.deepEqual(await project(),[]);
});

import {MECHANIC_OPERATIONS} from './cloud-mechanics.mjs';
import {canonical} from './core.mjs';
test('an imported untransformed actor returns serializable damage and preserves unknown temporary HP',async()=>{
  let row={id:'legacy',actorId:'legacy',kind:'actor',life:'active',data:{currentHp:12,temporaryHp:null}};
  const ctx={require(){},get:()=>row,put:r=>{row=r;return row;}};
  const apply=()=>MECHANIC_OPERATIONS.apply_hp_change.plan(ctx,{actorId:'legacy',kind:'damage',amount:4});
  await assert.rejects(apply,/HP_UNKNOWN/);
  assert.equal(row.data.currentHp,12);
  row.data.temporaryHp=0;
  const result=await apply();
  assert.equal(JSON.parse(canonical(result)).activeFormId,null);
  assert.equal(result.afterHp,8);
});
