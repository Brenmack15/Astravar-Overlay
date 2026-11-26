import {defineFrameFixture} from './representative-forms.mjs';

export async function seedCompanionAcceptance(command) {
  const actorId='actor-one',operator='ally';
  const f={command,
    defined:values=>command('define_actor',{actorId,name:'Test companion',role:'player',rulesProfile:'legacy_2014',level:12,maxHp:60,currentHp:42,temporaryHp:0,
      gameSeconds:100,baseAc:16,abilities:{STR:8,DEX:14,CON:14,INT:20,WIS:11,CHA:10},...values}),
    pool:(poolId,type,current,maximum,recovery,more={})=>command('define_resource',{actorId,poolId,name:poolId,type,current,maximum,recovery,source:'Fixture recovery',...more}),
    effect:(effectId,target,key,operation,more={})=>command('define_effect',{actorId,effectId,name:effectId,target,key,operation,source:'Fixture source',...more})};
  await defineFrameFixture(f,actorId);
  const feature=(id,name,extra={})=>command('define_feature',{actorId,featureId:id,name,kind:'companion',description:name,source:'Synthetic established trait',references:[],...extra});
  await feature('known-pattern','Original known pattern',{actorId:operator,kind:'class'});
  await f.pool('dedicated-capacity','ordinary',0,1,'none',{actorId:operator});
  await f.pool('ordinary-capacity','ordinary',2,2,'none',{actorId:operator});
  await feature('original-link','Dedicated companion bond',{infusionBond:{operatorId:operator,knownFeatureId:'known-pattern',dedicatedPoolId:'dedicated-capacity'}});
  await feature('calibration','Mechanical calibration',{includedScore:{ability:'DEX',before:16,after:18}});
  await feature('welcome','Audience rapport',{socialCheck:'nonhostile_goodwill_or_entertainment'});
  await command('revise_feature',{actorId,featureId:'speech',name:'Speech packages',kind:'companion',description:'Installed speech packages',source:'Reviewed synthetic hardware',
    references:[],sourceItemId:'vocoder',speech:{capacity:6,languages:['Fixture A','Fixture B','Fixture C'],fullSentences:true,translation:true}});
  await command('set_actor_training',{actorId:operator,category:'language',key:'Fixture B',trained:true,source:'Fixture language'});
  await command('define_action',{actorId,actionId:'translate',name:'Interpret message',type:'translate',timing:'other',source:'Synthetic configured speech',sourceItemId:'vocoder',communicationFeatureId:'speech'});
  await f.pool('active-repair','ordinary',1,1,'short_rest');
  await f.pool('reconstruction','ordinary',1,1,'long_rest',{recoveryActorId:operator});
  await command('define_item_definition',{definitionId:'kit-def',name:'Test tools',category:'tool',description:'Reusable fixture tools',rarity:null,
    stackable:false,wearable:false,handheld:true,attunement:'none',source:'Fixture tools'});
  await command('acquire_item',{itemId:'kit-instance',definitionId:'kit-def',ownerId:operator,quantity:1,source:'Fixture holdings'});
  const spell=(id,name,level,concentration,more={})=>command('define_spell',{actorId:operator,spellId:id,name,source:'Fixture spell',baseLevel:level,concentration,ritual:false,
    resolution:'automatic',timing:'action',components:[],...more});
  await spell('repair-spell','Maintenance cantrip',0,false);
  await command('grant_spell',{actorId:operator,spellId:'repair-spell',grantId:'repair-grant',source:'Fixture cantrip',route:'at_will',levels:[0],preparation:'not_required'});
  await command('define_care_protocol',{actorId,operatorId:operator,protocolId:'maintenance',spellId:'repair-spell',toolDefinitionId:'kit-def',repairPoolId:'active-repair',
    reconstructionPoolId:'reconstruction',repairDice:2,repairSides:6,repairSeconds:60,reconstructionSeconds:600,dormancySeconds:60,source:'Fixture protocol'});
  await spell('aid-spell','Support blessing',1,true,{rollAid:{family:'support',sides:4,applies:['attack','save']},scaling:{source:'Fixture spell',targets:3,durationSeconds:60}});
  await command('grant_spell',{actorId:operator,spellId:'aid-spell',grantId:'aid-grant',source:'Fixture known spell',route:'slot',levels:[1],preparation:'prepared'});
  await f.pool('operator-slot','spell_slot',2,2,'long_rest',{actorId:operator,slotLevel:1});
}
