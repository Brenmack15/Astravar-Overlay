import assert from 'node:assert/strict';
import test from 'node:test';
import {activeForm,checkTiming,formPermitsAttack} from './cloud-mechanics-core.mjs';
import {eligibleForm} from './cloud-form-deployment.mjs';

test('worn-form eligibility uses the exact equipped, operating physical source',async()=>{
  const actor={id:'pilot',actorId:'pilot',life:'active',data:{activeFormId:'mask-mode'}};
  const item={id:'mask',kind:'item_instance',life:'active',data:{custodianId:actor.id,partyAccess:true,
    holding:'carried',quantity:1,equipment:'worn',operating:true,attuned:false}};
  const form={id:'mask-mode',kind:'form',actorId:actor.id,life:'active',data:{sourceItemId:item.id,
    requiresOperating:true,requiresWorn:true}};
  const ctx={require(){},current:id=>id===item.id?item:null,get:(kind,id)=>id===form.id?form:id===actor.id?actor:null};
  for(const [equipment,operating,eligible] of [['packed',true,false],['held',true,false],['worn',false,false],['worn',true,true]]){
    Object.assign(item.data,{equipment,operating});
    assert.equal((await activeForm(ctx,actor))?.id??null,eligible?form.id:null);
    if(eligible)await eligibleForm(ctx,actor,form,true);
    else await assert.rejects(eligibleForm(ctx,actor,form,true),{code:'FORM_SOURCE_INELIGIBLE'});
  }
});

test('form attack restrictions preserve ordinary attacks and other actors’ pools',()=>{
  const p={ruleFormIds:['mask-mode'],suppressesNaturalAttacks:true,blockedAttackPoolId:'burst'};
  assert.equal(formPermitsAttack({classification:'natural'},p),false);
  assert.equal(formPermitsAttack({cost:{poolId:'burst'}},p),false);
  assert.equal(formPermitsAttack({cost:{poolId:'arrows'}},p),true);
  assert.equal(formPermitsAttack({formId:'mantle-mode'},p),false);
  assert.equal(formPermitsAttack({classification:'natural'},{ruleFormIds:[]}),true);
});

test('a full action uses the same default attack allowance as a fresh turn',async()=>{
  const a={id:'pilot',data:{encounterId:'scene'}};
  const turn={action:true,attacks:1};
  const encounter={life:'active',data:{active:true,participants:[a.id],index:0,economy:{[a.id]:turn}}};
  const ctx={get:async()=>encounter};
  assert.equal((await checkTiming(ctx,a,'action')).turn,turn);
  a.data.attacksPerAction=2;
  await assert.rejects(checkTiming(ctx,a,'action'),{code:'ACTION_UNAVAILABLE'});
  turn.attacks=2;
  assert.equal((await checkTiming(ctx,a,'action')).turn,turn);
});
