/** Synthetic installed-Android evidence for the reviewed companion. Never contacts a campaign service. */
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {join} from 'node:path';
import {harness,continuityId} from './ci-ascension-cloud-gateway.mjs';
import {readsAndWrites} from './ci-ascension-companion-native.mjs';
import {seedCompanionAcceptance} from '../backend/owner-bridge/fixtures/companion-traits.mjs';

export const scope='eq37-',who='actor-one',operator='ally',listener='listener';
const target='rig-form',source='Instrumented synthetic encounter',sha=x=>createHash('sha256').update(x).digest('hex');
export const families={
  rake:['Talons',8,'slashing',3,4,4,5],nip:['Bite',8,'piercing',3,4,4,5],pulse:['Force Strike',9,'force',3,6,4,30],
  'large-bite':['Magical Beak',9,'piercing',2,10,5,5],'large-rake':['Magical Claw',9,'slashing',3,8,5,5]
};
export async function fixtures(command){
  await seedCompanionAcceptance(command);
  // Separate synthetic hostile sources exercise the companion's existing defensive effects.
  for(const [id,resolution,type,amount] of [['qa-force','automatic','force',10],['qa-wave','save','fire',14]]){
    await command('define_attack',{actorId:listener,attackId:id,name:id,source,resolution,timing:'action',delivery:'area',
      ...(resolution==='save'?{saveAbility:'DEX',saveDc:18,saveSuccess:'half'}:{}),
      components:[{id:'impact',name:'Fixture incoming effect',type,dice:0,sides:6,flat:amount}]});
  }
  await command('grant_spell',{actorId:operator,spellId:'guided-bolts',grantId:'qa-bolts',source,route:'at_will',levels:[1],preparation:'not_required'});
}
const dice=(...faces)=>({origin:'player_physical',faces});
const attack=(id,extra={})=>({actorId:who,attackId:id,mode:'normal',includeDamage:false,...extra});
const use=(id,fields={})=>({actorId:who,actionId:id,declared:true,...fields});
const check=(actorId,kind,key,faces,fields={})=>({actorId,check:{kind,key,mode:'normal'},evidence:dice(...faces),...fields});
export function sequence(){
  const groups={before:[],deployment:[],after:[],recovery:[]};let queue=groups.before;
  const add=(id,op,args,refused)=>queue.push({id,op,args,...(refused?{refused}:{})});
  function action(id,actionId,fields,refused){add(id,'use_action',use(actionId,{useId:scope+id,...fields}),refused);}
  function strike(id,attackId,fields={},refused){add(id,'resolve_attack',attack(attackId,{attemptId:scope+id,...fields}),refused);}
  function roll(id,actorId,kind,key,faces,fields={}){add(id,'record_check',{rollId:scope+id,...check(actorId,kind,key,faces,fields)});}
  action('heat-normal','scan',{thermalReading:{distanceFeet:150,faint:false,obstacle:'none'}});
  roll('heat-check',who,'skill','perception',[9,15],{forSenseId:scope+'heat-normal'});
  for(const [id,d,f,o] of [['heat-faint',60,true,'none'],['heat-far',61,true,'none'],['heat-wall',2,false,'solid']])
    action(id,'scan',{thermalReading:{distanceFeet:d,faint:f,obstacle:o}});
  roll('ordinary-stealth',who,'skill','stealth',[6,11,18]);
  roll('ordinary-dex',who,'save','DEX',[1,9,15]);
  action('ordinary-pair','basic-pair',{});
  strike('pair-substitute','pulse',{actionUseId:scope+'ordinary-pair',replacesAttackId:'rake',hit:dice(11)});
  strike('forbid-two-substitutions','pulse',{actionUseId:scope+'ordinary-pair',replacesAttackId:'nip',hit:dice(12)},'MULTIATTACK_SUBSTITUTION_UNAVAILABLE');
  strike('amulet-fixed','nip',{actionUseId:scope+'ordinary-pair',substitutionId:'fixed'});
  add('support','cast_spell',{actorId:operator,castId:'support-live',grantId:'aid-grant',level:1,slotPoolId:'operator-slot',targets:[who]});
  queue=groups.deployment;
  add('begin','begin_form_deployment',{actorId:who,formId:target,deploymentId:scope+'deployment',declared:true,willing:true});
  const complete={actorId:who,formId:target,deploymentId:scope+'deployment',elapsedSeconds:0,uninterrupted:true,willing:true};
  add('half','advance_actor_time',{actorId:who,elapsedSeconds:30,confirmed:true});
  add('too-early','change_form',complete,'DEPLOYMENT_TIME_REMAINING');
  add('minute','advance_actor_time',{actorId:who,elapsedSeconds:30,confirmed:true});add('activate','change_form',complete);
  queue=groups.after;
  strike('wrong-form','rake',{hit:dice(10)},'ATTACK_FORM_INELIGIBLE');
  const dest={label:'Synthetic perch',distanceFeet:30,unoccupied:true,visible:true,grounded:false,availableSize:'large',passengers:0,permittedByBarriers:true};
  action('no-air','shift',{destination:dest},'GROUNDED_DESTINATION_REQUIRED');
  action('no-small','shift',{destination:{...dest,grounded:true,availableSize:'tiny'}},'DESTINATION_TOO_SMALL');
  roll('heavy-stealth',who,'skill','stealth',[3,12,18]);roll('heavy-dex',who,'save','DEX',[5,8,16]);
  function impact(id,attackId){strike(id,attackId,{actorId:listener,includeDamage:true,targets:[who],damage:[{id:'impact',evidence:dice()}]});}
  function settle(id,base,extra={}){add(id,'resolve_attack_target',{actorId:listener,attemptId:scope+base,targetActorId:who,source,...extra});}
  impact('force-strike-on-rig','qa-force');settle('force-resisted','force-strike-on-rig');
  for(const [id,faces] of [['pass',[2,10,17]],['fail',[2,3,4]]]){
    impact('wave-'+id,'qa-wave');roll('save-'+id,who,'save','DEX',faces,{forAttemptId:scope+'wave-'+id});
    settle('evade-'+id,'wave-'+id,{saveRollId:scope+'save-'+id});
  }
  add('ward-cast','cast_spell',{actorId:operator,grantId:'qa-bolts',castId:scope+'guided',level:1,targets:[who]});
  add('ward-dice','link_cast_roll',{actorId:operator,castId:scope+'guided',damage:[{id:'dart',evidence:dice(3)}]});
  add('ward-deflects','resolve_cast_target',{actorId:operator,castId:scope+'guided',targetActorId:who,source});
  for(const [actor,face] of [[who,18],[listener,8]])roll('initiative-'+actor,actor,'initiative','initiative',[face]);
  add('encounter','start_encounter',{encounterId:scope+'fight',name:'Synthetic two-actor encounter',
    participants:[who,listener].map(actorId=>({actorId,initiativeRollId:scope+'initiative-'+actorId}))});
  for(const [id,detail] of [['no-hearing',{hears:false,distanceFeet:30}],['beyond-roar',{hears:true,distanceFeet:31}]]){
    action(id,'cry',{targets:[{actorId:listener,...detail}]},'ACTION_TARGET_INELIGIBLE');
  }
  action('roar','cry',{targets:[{actorId:listener,hears:true,distanceFeet:30}]});
  action('heavy-pair','large-pair',{});
  for(const id of ['large-bite','large-rake'])strike('linked-'+id,id,{actionUseId:scope+'heavy-pair',hit:dice(13)});
  roll('first-wis',listener,'save','WIS',[2],{forUseId:scope+'roar'});
  const save={actorId:who,useId:scope+'roar',targetActorId:listener};
  add('frightened','resolve_action_save',{...save,rollId:scope+'first-wis'});
  add('next-turn','advance_encounter_turn',{encounterId:scope+'fight',currentActorId:who});
  roll('repeat-wis',listener,'save','WIS',[17],{forUseId:scope+'roar'});
  add('recovered','resolve_action_save',{...save,rollId:scope+'repeat-wis',endOfTurnConfirmed:true});
  add('encounter-ended','end_encounter',{encounterId:scope+'fight',currentActorId:listener,outcome:'Synthetic fixture concluded'});
  queue=groups.recovery;
  add('expire','advance_actor_time',{actorId:who,elapsedSeconds:3600,confirmed:true});
  add('rest','complete_rest',{actorId:who,kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[]});
  add('redeploy','change_form',{actorId:who,formId:target,elapsedSeconds:60,uninterrupted:true,willing:true});
  add('overflow','apply_hp_change',{actorId:who,kind:'damage',amount:70});
  return groups;
}
export async function verify(bridge,device) {
  assert.equal(device.api,37);assert.equal(device.androidRelease,'17');assert.equal(device.emulator,true);
  assert.equal(device.package,'com.yazsras.astravar.v2uidebug');assert.equal(device.reopened,true);
  const groups=sequence(),ordered=Object.values(groups).flat();
  const denied=Object.fromEntries(ordered.filter(s=>s.refused).map(s=>[s.id,s.refused]));
  assert.deepEqual(device.denied,denied);
  const ui=Object.keys(families).map(id=>'ui-'+id),order=[
    ...groups.before,...ui.slice(0,3).map(id=>({id})),{id:'ui-base-shift'},...groups.deployment,
    ...ui.slice(3).map(id=>({id})),{id:'ui-heavy-shift'},...groups.after,...groups.recovery,{id:'dawn'}
  ].filter(s=>!s.refused).map(s=>s.id);
  assert.deepEqual(device.drawn,Object.keys(families));
  assert.deepEqual(Object.keys(device.transactions).sort(),[...order].sort());
  assert.equal(device.baseRevision+order.length,device.finalRevision);
  const receipts={},outputs={},references=[];
  for(const [i,id] of order.entries()){
    const commandId=scope+id,r=await bridge.rpc('get_cloud_receipt',{commandId});
    assert.equal(r.status,'COMMITTED');assert.equal(r.receipt.transactionId,device.transactions[id]);
    assert.equal(r.receipt.revision,device.baseRevision+i+1);
    receipts[id]=r.receipt;outputs[id]=r.receipt.outcome;
    references.push({step:id,commandId,transactionId:r.receipt.transactionId,revision:r.receipt.revision,sha256:sha(JSON.stringify(r))});
  }
  assert.equal(new Set(references.map(r=>r.transactionId)).size,order.length);
  const get=async id=>(await bridge.rpc('get_cloud_state',{id})).records[0];
  const last=await bridge.rpc('get_cloud_actor_sheet',{id:who});
  assert.equal(last.revision,device.finalRevision);
  assert.deepEqual([last.sheet.formId,last.sheet.currentHp,last.sheet.maxHp],[null,53,60]);
  const accepted=[];
  for(const [id,[label,hit,type,dice,sides,flat,range]] of Object.entries(families)){
    const heavy=id.startsWith('large-'),phase=heavy?device.heavy:device.ordinary;
    const projection=phase.actions.find(r=>r.id===id),attempt=outputs['ui-'+id];
    assert.ok(projection);const definition=await get(id);
    assert.equal(projection.configuration.reach??projection.configuration.range,range);
    assert.equal(definition.data.reach??definition.data.range,range);assert.equal(projection.hit,hit);
    assert.deepEqual(attempt.components.map(c=>[c.type,c.dice,c.sides,c.flat]),[[type,dice,sides,flat]]);
    assert.deepEqual([attempt.actorId,attempt.formId,attempt.attackId,attempt.hit.modifier,attempt.hit.origin],[who,heavy?target:null,id,hit,'app']);
    assert.equal(attempt.appliedDamage,null);assert.equal(attempt.damage,null);assert.deepEqual(attempt.targets,[]);
    assert.deepEqual(attempt.costs,[]);assert.equal(attempt.timing,'attack');
    assert.ok(attempt.hit.natural>=1&&attempt.hit.natural<=20);
    assert.deepEqual(attempt.hit.faces,[{value:attempt.hit.natural,sides:20,kept:true}]);
    const extra=attempt.hit.aidDice??[];
    if(!heavy){assert.equal(extra.length,1);assert.equal(extra[0].castId,'support-live');assert.equal(extra[0].sides,4);
      assert.equal(extra[0].origin,'app');assert.ok(extra[0].face>=1&&extra[0].face<=4);}
    else assert.deepEqual(extra,[]);
    assert.equal(attempt.hit.total,attempt.hit.natural+hit+(extra[0]?.face??0));
    assert.equal(attempt.outcome,attempt.hit.natural===1?'miss':'pending');
    const row=await get(attempt.attemptId);assert.equal(row.kind,'attack_attempt');
    assert.equal(row.data.commandId,scope+'ui-'+id);assert.deepEqual(row.data.hit,attempt.hit);assert.deepEqual(row.data.components,attempt.components);
    accepted.push({name:label,fixtureAttackId:id,formId:attempt.formId,attemptId:row.id,reachOrRangeFeet:range,modifier:hit,
      damageConfiguration:{dice,sides,flat,type},observedHit:attempt.hit,calculatedPotentialOnly:true,appliedDamage:attempt.appliedDamage,
      receipt:references.find(r=>r.step==='ui-'+id)});
  }
  for(const [phase,heavy] of [[device.ordinary,false],[device.heavy,true]]){
    assert.deepEqual([phase.formId,phase.size,phase.ac.value,phase.abilities.STR.effective,phase.movement.walk,phase.movement.fly,phase.currentHp,phase.senses.darkvision],
      heavy?[target,'large',18,20,40,0,60,180]:[null,'tiny',16,8,5,60,60,180]);
    const ids=phase.actions.filter(r=>r.kind==='attack').map(r=>r.id).sort();
    assert.deepEqual(ids,accepted.filter(a=>a.formId===(heavy?target:null)).map(a=>a.fixtureAttackId).sort());
    assert.deepEqual([phase.skills.stealth.value,phase.saves.DEX.value,phase.skills.perception.value],[13,8,7]);
    assert.equal(phase.rollModes['save:DEX'].normal.count,3);assert.equal(phase.rollModes['skill:stealth'].normal.count,3);
    assert.equal(phase.mobilityRules.find(r=>r.kind==='flyby').eligible,!heavy);
    assert.ok(phase.activeEffects.includes('evasion'));assert.ok(phase.activeEffects.includes('spell-ward'));
    assert.deepEqual(phase.defenses.map(d=>[d.type,d.kind]).sort(),[['acid','immunity'],['force','resistance'],['necrotic','immunity'],['poison','immunity'],['psychic','immunity']]);
    for(const key of ['blinded','charmed','exhaustion','frightened','incapacitated','paralyzed','petrified','poisoned','prone','stunned','unconscious'])assert.ok(phase.activeEffects.includes('condition-'+key));
    assert.equal(phase.companionSpellSlots,0);assert.equal(phase.pools['operator-slot'],1);
    assert.equal(phase.communications.find(x=>x.kind==='telepathy').sharedSenses,false);
    assert.equal(phase.communications.find(x=>x.kind==='telepathy').twoWay,true);
    assert.equal(phase.communications.find(x=>x.kind==='speech').unassigned,3);
    assert.equal(phase.pools['rig-integrity'],75);
  }
  assert.deepEqual(device.ordinary.communications,device.heavy.communications);
  const cast=(await get('support-live')).data;
  assert.equal(cast.actorId,operator);assert.deepEqual(cast.targets,[who]);assert.equal(cast.rollAid.sides,4);
  assert.equal(device.ordinary.rollAids[0].castId,'support-live');assert.deepEqual(device.heavy.rollAids,[]);
  const expected={};function checkData(id,name,got,want){assert.deepEqual(got,want,id+' '+name);(expected[id]??={})[name]=got;}
  for(const [id,eligible,limit] of [['heat-normal',true,150],['heat-faint',true,60],['heat-far',false,60],['heat-wall',false,150]]){
    checkData(id,'reading',[outputs[id].sensor.eligible,outputs[id].sensor.limit,outputs[id].sourceItemId],[eligible,limit,'optic']);
  }
  checkData('heat-check','modeAndResult',[outputs['heat-check'].mode,outputs['heat-check'].modifier,outputs['heat-check'].total],['advantage',9,24]);
  for(const id of ['ordinary-stealth','heavy-stealth','ordinary-dex','heavy-dex']){
    const v=outputs[id];checkData(id,'highestOfThree',[v.faces.length,v.natural,v.modifier],[3,Math.max(...v.faces.map(f=>f.value)),id.endsWith('stealth')?13:8]);
  }
  checkData('amulet-fixed','fixedWithoutDie',[outputs['amulet-fixed'].hit.substitutedValue,outputs['amulet-fixed'].hit.natural,outputs['amulet-fixed'].hit.faces,
    outputs['amulet-fixed'].hit.total,outputs['amulet-fixed'].critical,outputs['amulet-fixed'].costs[0].after],[10,null,[],18,false,0]);
  for(const [id,before,after,grounded] of [['ui-base-shift',3,2,false],['ui-heavy-shift',2,1,true]]){
    const o=outputs[id];checkData(id,'samePool',[o.costs[0].poolId,o.costs[0].before,o.costs[0].after,o.destination.grounded],['blink',before,after,grounded]);
  }
  checkData('begin','timeAndCost',[outputs.begin.requiredSeconds,outputs.begin.costs.length],[60,0]);
  checkData('activate','timeAndCharge',[outputs.activate.elapsedSeconds,outputs.activate.costs[0].after],[60,0]);
  checkData('force-resisted','appliedAgainstIntegrity',[outputs['force-resisted'].amount,outputs['force-resisted'].hp.absorbedByIntegrity],[5,5]);
  checkData('ward-deflects','targetedSpellNegated',[outputs['ward-deflects'].amount,outputs['ward-deflects'].targetActorId],[0,who]);
  for(const [id,n] of [['evade-pass',0],['evade-fail',7]])checkData(id,'evasion',[outputs[id].amount,outputs[id].evasion],[n,'evasion']);
  const pair=(await get(scope+'heavy-pair')).data;
  checkData('heavy-pair','oneOfEach',[pair.attemptIds,pair.remainingAttacks],[['eq37-linked-large-bite','eq37-linked-large-rake'],[]]);
  const roar=outputs.roar;
  checkData('roar','selectiveWisdom',[roar.save.ability,roar.save.dc,roar.targets.map(t=>t.actorId),roar.targets[0].expiresAt-roar.targets[0].clockAtUse,roar.costs[0].after],['WIS',15,[listener],60,0]);
  checkData('frightened','status',outputs.frightened.status,'frightened');checkData('recovered','endOfTurn',outputs.recovered.status,'saved');
  assert.deepEqual((await get(listener)).data.conditions,[]);assert.deepEqual((await get(operator)).data.conditions,[]);
  checkData('expire','form',outputs.expire.formId,null);
  checkData('overflow','noDuplicateDamage',[outputs.overflow.absorbedByIntegrity,outputs.overflow.beforeHp,outputs.overflow.afterHp],[63,60,53]);
  assert.equal(device.shellBeforeRecovery,63);assert.equal(device.final.pools['rig-integrity'],0);assert.equal(device.final.pools['dial-pool'],1);
  const dusk=(await get('dial-pool')).data;assert.equal(dusk.recovery,'dawn');assert.equal(dusk.current,1);
  const gear={};for(const id of ['stabilizer','ward','vocoder','dial','optic','step','keepsake','shell-rig']){
    const item=await get(id);assert.equal(item.kind,'item_instance');assert.equal(item.data.quantity,1);
    assert.equal(item.data.attuned,id==='ward');assert.equal(item.data.equipment,id==='keepsake'?'packed':'worn');
    gear[id]={holding:item.data.holding,equipment:item.data.equipment,attuned:item.data.attuned,quantity:item.data.quantity};
  }
  return {requirement:'EQ26-0091',synthetic:true,api:37,androidRelease:'17',emulator:true,physicalDevice:false,baselineRevision:device.baseRevision,
    finalRevision:device.finalRevision,receiptCount:references.length,refused:denied,attacks:accepted,effects:expected,physicalItemRecords:gear,
    actorId:who,eligibleForms:[null,target],reopenedNativeCache:true,immutableReceipts:references};
}
if(process.argv[1]&&import.meta.url===new URL('file://'+process.argv[1]).href){
  const [phase,directory,file]=process.argv.slice(2);const bridge=harness(directory);
  if(phase==='seed'){
    await bridge.stateAt(4,12);
    await bridge.control('/v3/continuities/'+continuityId+'/grants/sites-mcp',{scopes:readsAndWrites,actors:['*'],auto:readsAndWrites.filter(s=>s.endsWith('.write')),
      expiresAt:Date.now()+86400000,expectedGrantRevision:1,revoked:false});
    let revision=4;
    await fixtures(async(operation,args)=>{const r=await bridge.commit(bridge.cmd(revision,[scope+'seed-'+(revision+1),operation,args]));revision=r.revision;});
    writeFileSync(join(directory,'companion-instrumented-plan.json'),JSON.stringify(sequence()),{mode:0o600});
    console.log(JSON.stringify({synthetic:true,actorId:who,sourceFixture:'companion-traits',revision}));
  }else{
    assert.equal(phase,'verify');const witness=readFileSync(file);const report=await verify(bridge,JSON.parse(witness));
    const git=(arg)=>execFileSync('git',['rev-parse',arg],{encoding:'utf8'}).trim();
    console.log(JSON.stringify({...report,frozenProductCommit:'90ea56725f4bd11c2741823f8039c0bc37e28203',testCommit:git('HEAD'),appMainTree:git('HEAD:app/src/main'),
      rulesTree:git('HEAD:rules'),workerTree:git('HEAD:backend/owner-bridge'),deviceWitnessSha256:sha(witness),
      fixtureSha256:Object.fromEntries(['representative-forms.mjs','companion-traits.mjs'].map(n=>[n,sha(readFileSync('backend/owner-bridge/fixtures/'+n))]))},null,2));
  }
}
