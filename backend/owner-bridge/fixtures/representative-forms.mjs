// Shared synthetic configurations exercised through the registered mechanics and recovery entry points.
const component=(id,type,num,sides,flat)=>({id,name:id,type,dice:num,sides,flat});

export async function wornFixture(f,id,{attuned=false,packed=false,manual=false,ownerId='actor-one'}={}) {
  await f.command('define_item_definition',{definitionId:id+'-def',name:'Fixture '+id,category:'gear',
    description:'Synthetic configured fixture',rarity:null,stackable:false,wearable:!packed,handheld:false,
    attunement:attuned?'required':'none',...(attuned?{attunementSeconds:0}:{}),...(manual?{activation:'manual'}:{}),source:'fixture'});
  await f.command('acquire_item',{itemId:id,definitionId:id+'-def',ownerId,quantity:1,source:'fixture'});
  if(!packed)await f.command('equip_item',{itemId:id,equipment:'worn',source:'fixture'});
  if(attuned)await f.command('set_item_attunement',{itemId:id,attuned:true,elapsedSeconds:0,confirmed:true,source:'fixture'});
  if(manual)await f.command('set_item_operating',{itemId:id,operating:true,source:'fixture'});
}

export async function defineFrameFixture(f,who) {
  await f.defined({role:'companion',proficiency:4,currentHp:60,baseAc:16,attunementCapacity:1,
    abilities:{STR:8,DEX:18,CON:10,INT:10,WIS:12,CHA:12},speeds:{walk:5,fly:60},senses:{darkvision:180},
    size:'tiny',creatureType:'construct',saveProficiencies:['DEX']});
  await f.defined({actorId:'listener',name:'Fixture listener',role:'creature'});await f.defined({actorId:'ally',name:'Fixture ally'});
  const ids=['stabilizer','vocoder','ward','dial','optic','step','keepsake','shell-rig'];
  for(const id of ids)await wornFixture(f,id,{attuned:id==='ward',packed:id==='keepsake',ownerId:who});
  for(const [id,details] of [['speech',{sourceItemId:'vocoder',speech:{capacity:6,languages:['Fixture A','Fixture B','Fixture C'],fullSentences:true}}],
    ['private-link',{telepathy:{partnerActorId:'ally',twoWay:true,sharedSenses:false}}],['flyby',{mobility:'flyby'}]])
    await f.command('define_feature',{actorId:who,featureId:id,name:id,kind:'companion',description:'Synthetic source capability',source:'fixture',references:[],...details});
  await f.pool('blink','charge',3,3,'long_rest');await f.pool('dial-pool','charge',1,1,'dawn');
  await f.pool('deploy','charge',1,1,'long_rest');await f.pool('roar','charge',1,1,'deployment');await f.pool('rig-integrity','integrity',75,75,'none');
  await f.command('define_form',{actorId:who,formId:'rig-form',name:'Heavy frame',source:'fixture',size:'large',creatureType:'construct',
    sourceItemId:'shell-rig',grounded:true,baseAc:18,abilityOverrides:{STR:20},speeds:{walk:40},timing:'other',activationSeconds:60,
    durationSeconds:3600,deploymentPoolId:'deploy',integrityPoolId:'rig-integrity',resetPoolId:'roar',requiresWilling:true});
  for(const [id,stat,rank] of [['stealth','DEX',2],['perception','WIS',1]])await f.command('set_skill_training',{actorId:who,skillId:id,ability:stat,rank,name:id});
  await f.effect('precision','skill','stealth','add',{amount:1,sourceItemId:'stabilizer'});await f.effect('perception-base','skill','perception','add',{amount:2});
  await f.effect('visual','skill','perception','add',{amount:2,contextTag:'heat',sourceItemId:'optic'});
  await f.effect('heat-edge','roll_edge','skill:perception','advantage',{sourceItemId:'optic',contextTag:'heat'});
  for(const key of ['skill:stealth','save:DEX'])await f.effect('edge-'+key.split(':')[1],'roll_edge',key,'keep_highest_three',{sourceItemId:'stabilizer'});
  for(const key of ['acid','necrotic','poison','psychic'])await f.effect('plate-'+key,'damage_defense',key,'immunity');
  for(const key of ['blinded','charmed','exhaustion','frightened','incapacitated','paralyzed','petrified','poisoned','prone','stunned','unconscious'])
    await f.effect('condition-'+key,'condition_immunity',key,'immunity');
  await f.effect('force-ward','damage_defense','force','resistance',{sourceItemId:'ward',requiresAttunement:true});
  await f.effect('evasion','save_mitigation','DEX','evade');
  await f.command('define_spell',{actorId:'ally',spellId:'guided-bolts',name:'Fictional guided spell',source:'fixture',baseLevel:1,concentration:false,
    ritual:false,resolution:'automatic',timing:'action',components:[component('dart','force',1,4,1)]});
  await f.effect('spell-ward','spell_damage_immunity','guided-bolts','immunity',{sourceItemId:'ward',requiresAttunement:true});
  const basic={actorId:who,source:'fixture',timing:'attack',resolution:'attack',classification:'magical'};
  for(const [id,type,num,sides,flat,hit,extra] of [
    ['rake','slashing',3,4,4,8,{delivery:'melee',reach:5,excludedForms:['rig-form']}],
    ['nip','piercing',3,4,4,8,{delivery:'melee',reach:5,excludedForms:['rig-form']}],
    ['pulse','force',3,6,4,9,{delivery:'ranged',range:30,excludedForms:['rig-form']}],
    ['large-bite','piercing',2,10,5,9,{delivery:'melee',reach:5,formId:'rig-form'}],
    ['large-rake','slashing',3,8,5,9,{delivery:'melee',reach:5,formId:'rig-form'}]])
    await f.command('define_attack',{...basic,attackId:id,name:id,fixedHit:hit,components:[component('impact',type,num,sides,flat)],...extra});
  const action={actorId:who,source:'fixture'};
  await f.command('define_action',{...action,actionId:'basic-pair',name:'Normal pair',type:'multiattack',timing:'action',excludedForms:['rig-form'],
    attackSequence:['rake','nip'],substitutions:[{attackId:'pulse',replaces:['rake','nip'],maximum:1}]});
  await f.command('define_action',{...action,actionId:'large-pair',name:'Large pair',type:'multiattack',timing:'action',formId:'rig-form',attackSequence:['large-bite','large-rake']});
  await f.command('define_action',{...action,actionId:'scan',name:'Thermal inspection',type:'thermal_scan',timing:'other',sourceItemId:'optic',thermal:{normal:150,faint:60,skillId:'perception',tag:'heat'}});
  await f.command('define_action',{...action,actionId:'fixed',name:'Fixed face',type:'fixed_attack_die',timing:'other',sourceItemId:'dial',fixedDieValue:10,cost:{poolId:'dial-pool',amount:1}});
  await f.command('define_action',{...action,actionId:'shift',name:'Short displacement',type:'teleport',timing:'bonus_action',range:30,sourceItemId:'step',cost:{poolId:'blink',amount:1}});
  await f.command('define_action',{...action,actionId:'cry',name:'Selective cry',type:'frighten',timing:'bonus_action',range:30,sourceItemId:'shell-rig',formId:'rig-form',
    saveDc:15,saveAbility:'WIS',durationSeconds:60,cost:{poolId:'roar',amount:1}});
  return ids;
}

export async function defineMorphFixture(f,who) {
  await f.defined({role:'companion',proficiency:4,currentHp:154,maxHp:154,baseAc:19,attunementCapacity:1,
    abilities:{STR:20,DEX:18,CON:18},size:'large',creatureType:'dragon',speeds:{walk:40,fly:80,swim:40}});
  await wornFixture(f,'primary-focus',{manual:true,ownerId:who});await wornFixture(f,'reserve-focus',{packed:true,ownerId:who});await wornFixture(f,'tattoo',{attuned:true,ownerId:who});await wornFixture(f,'harness',{ownerId:who});
  for(const [key,op] of [['acid','immunity'],['cold','resistance'],['poison','resistance']])await f.effect('biological-'+key,'damage_defense',key,op);
  await f.pool('shared-breath','ordinary',1,1,'recharge_roll',{rechargeSides:6,rechargeMinimum:5});await f.pool('shared-mode','charge',1,1,'dawn');
  for(const [id,size,walk,fly,swim] of [['original','large',40,80,40],['civilian','medium',30,0,null],['winged-hybrid','medium',30,40,null]]){
    await f.command('define_form',{actorId:who,formId:id,name:id,source:'fixture',sourceItemId:'primary-focus',requiresOperating:true,revertOnSourceLoss:true,
      timing:'action',activationSeconds:0,requiresWilling:true,size,speeds:{walk,fly,swim}});
  }
  const atk={actorId:who,source:'synthetic combat profile',timing:'attack',delivery:'melee',resolution:'attack',ability:'STR',proficient:true};
  for(const [suffix,extra] of [['base',{excludedForms:['civilian','winged-hybrid']}],['hybrid',{formId:'winged-hybrid'}]]){
    for(const [id,type,die] of [['bite','piercing',10],['claw','slashing',6]])await f.command('define_attack',{...atk,...extra,attackId:id+'-'+suffix,
      name:id,reach:suffix==='base'?10:5,components:[component('natural',type,2,die,6),...(id==='bite'?[component('acid','acid',2,6,0)]:[])]});
    await f.command('define_action',{actorId:who,...extra,actionId:'three-'+suffix,name:'Natural sequence',type:'multiattack',timing:'action',source:'fixture',
      attackSequence:['bite-'+suffix,'claw-'+suffix,'claw-'+suffix]});
  }
  await f.command('define_action',{actorId:who,actionId:'extended',name:'Timed reach',source:'fixture',sourceItemId:'tattoo',requiresAttunement:true,
    type:'timed_attack_mode',timing:'bonus_action',durationSeconds:60,modeReach:15,cost:{poolId:'shared-mode',amount:1},
    modeAttacks:['bite-base','claw-base','bite-hybrid','claw-hybrid'],modeComponent:component('surge','force',1,6,0)});
  const breath={actorId:who,source:'synthetic source-backed geometry',timing:'action',delivery:'area',resolution:'save',saveSuccess:'half',cost:{poolId:'shared-breath',amount:1}};
  for(const [id,type,save,die,sides,extra] of [
    ['stream','acid','DEX',8,8,{range:60,area:'60 by 5 line',excludedForms:['civilian']}],['focused','acid','DEX',8,8,{range:60,area:'one target',sourceItemId:'harness',excludedForms:['civilian']}],
    ['fan','acid','DEX',8,8,{range:30,area:'cone',sourceItemId:'harness',excludedForms:['civilian']}],['frost','cold','CON',8,8,{area:'synthetic cold geometry',excludedForms:['civilian','winged-hybrid']}],
    ['gas','poison','CON',8,6,{range:30,area:'30-foot area',excludedForms:['civilian','winged-hybrid']}]])
    await f.command('define_attack',{...breath,...extra,attackId:id,name:id,saveAbility:save,saveDc:16,components:[component('breath',type,die,sides,0)]});
}
