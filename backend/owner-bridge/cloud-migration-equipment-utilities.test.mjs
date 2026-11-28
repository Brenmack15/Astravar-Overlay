import assert from 'node:assert/strict';
import {test} from 'node:test';
import {checkPointArea,useSensing} from './cloud-equipment-sensing.mjs';
import {useWorkshop} from './cloud-workshop-equipment.mjs';
import {MECHANIC_OPERATIONS} from './cloud-mechanics.mjs';
import {cloudMatches} from './cloud-validation.mjs';

test('reviewed utility operations accept typed input on the real command schemas',()=>{
 const define=MECHANIC_OPERATIONS.define_action.schema,use=MECHANIC_OPERATIONS.use_action.schema;
 for(const config of [{type:'creature_locator',radiusMiles:3,lineage:'synthetic',timing:'action'},{type:'clockwork_device',maximumObjects:3,cantripId:'synthetic-cantrip',timing:'other'},{type:'equipment_spectrum',timing:'other'}]){
  assert.ok(cloudMatches({actorId:'pilot',actionId:'fixture-tool',name:'Fixture',source:'Synthetic',...config},define));
 }
 assert.ok(cloudMatches({actorId:'pilot',actionId:'fixture-tool',useId:'use-1',declared:true,clockwork:{step:'create',deviceId:'tool-one',elapsedSeconds:600,effect:'sensory',options:'One quiet musical note'}},use));
});
test('targeting a point checks the radius, range, exact set of occupants and all-creatures declaration',()=>{
 const rule={range:100,radius:5},area={centerDistanceFeet:100,allCreaturesIncluded:true,creatures:[{actorId:'test',distanceFromCenter:5}]};
 checkPointArea(rule,area,['test']);for(const c of [{...area,centerDistanceFeet:101},{...area,allCreaturesIncluded:false},{...area,creatures:[]},{...area,creatures:[{actorId:'test',distanceFromCenter:6}]}])assert.throws(()=>checkPointArea(rule,c,['test']));
});
test('locator does not return a hidden world identity or an out-of-range result; crafting keeps exact fractions',async()=>{
 const action={data:{type:'creature_locator',lineage:'test',radiusMiles:1}},args={finding:{lineage:'test',nearestVerified:true,detected:true,direction:'east',distanceFeet:5280,source:'Fixture'}},out={};
 await useSensing({}, {},action,args,out);assert.equal(out.finding.distanceFeet,5280);
 await assert.rejects(useSensing({}, {},action,{finding:{...args.finding,distanceFeet:5281}},{}));
 const result={};await useWorkshop({}, {},{data:{type:'crafting_allowance'}},{craftQuote:{rarity:'common',ordinaryCopper:13,ordinaryWorkSeconds:7,basis:'Test catalog'}},result);
 assert.deepEqual(result.craftingAllowance.workSeconds,{numerator:7,denominator:4});
});
import {checkFittedModelChange,validateFittedModel} from './cloud-fitted-armor.mjs';
test('changing the model requires a compatible physical suit, an unspent current rest and the original smith tools in hand',async()=>{
 const base={id:'pilot',actorId:'pilot',kind:'actor',data:{gameSeconds:3600}};
 const model={id:'alternate',actorId:'pilot',kind:'form',data:{sourceItemId:'chassis',fittedModel:{model:'fixture-alternate',compatibilitySource:'Approved fixture plan'},activationSeconds:0,timing:'other'}};
 const state=[base,model,{id:'chassis',actorId:'pilot',kind:'item_instance',life:'active',data:{fittedArmorOperatorId:'pilot',approvedArmorModel:'fixture-base',holding:'carried',equipment:'worn',custodianId:'pilot'}},
 {id:'rest',actorId:'pilot',kind:'rest_report',life:'active',data:{status:'completed',kind:'short_rest',endGameSeconds:3600}},{id:'kit',kind:'item_instance',actorId:'pilot',life:'active',data:{definitionId:'smith',quantity:1,custodianId:'pilot',holding:'carried',equipment:'held'}},{id:'smith',kind:'item_definition',title:"Smith's Tools",data:{}}];
 const ctx={get:(k,id)=>state.find(r=>r.id===id&&r.kind===k)},proof={toolItemId:'kit',restReportId:'rest'};
 await validateFittedModel(ctx,{...model.data,actorId:'pilot'});assert.equal(await checkFittedModelChange(ctx,base,null,model,proof),'rest');
 await assert.rejects(checkFittedModelChange(ctx,base,null,model));state[4].data.equipment='packed';await assert.rejects(checkFittedModelChange(ctx,base,null,model,proof));
 state[4].data.equipment='held';base.data.lastArmorRestId='rest';await assert.rejects(checkFittedModelChange(ctx,base,null,model,proof));
});
