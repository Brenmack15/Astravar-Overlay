// Companion TLS contract rehearsal. It never creates an Android/device-attestation document.
import assert from 'node:assert/strict';
import {actorId,formId,prefix,readsAndWrites,seedNativeCompanion,verifyNativeCompanion} from '../../../scripts/ci-ascension-companion-native.mjs';
import {continuityId} from '../../../scripts/ci-ascension-cloud-gateway.mjs';

export async function rehearseCompanionWire(host,device,auth,credentialId){
  const base='/v3/continuities/'+continuityId,start=await seedNativeCompanion(host);
  const permissions=[...readsAndWrites.filter(s=>s.endsWith('.read')), 'character.write','resources.write','combat.write','approval.read'];
  await host.control(base+'/grants/device:'+credentialId,{scopes:permissions,auto:permissions.filter(s=>s.endsWith('.write')),actors:['*'],
    expectedGrantRevision:1,expiresAt:Date.now()+3600000,revoked:false});
  let rev=start.baseRevision;
  const observed={},transactions={},refused=[];
  const args=x=>({actorId,...x});
  const get=async id=>(await device(base+'/state?id='+id,null,auth)).records[0].data;
  const sheet=async ()=>(await device(base+'/sheet/'+actorId,null,auth)).sheet;
  const balance=async id=>(await get(id)).current;
  const envelope=(step,operation,x)=>host.cmd(rev,[prefix+step,operation,args(x)]);
  async function act(step,op,x,dm=false){
    const command=envelope(step,op,x);
    const result=dm?await host.commit(command):await device(base+'/commands',command,auth);
    assert.equal(result.status,'COMMITTED');
    assert.equal(result.revision,++rev);assert.deepEqual(result,await host.rpc('get_cloud_receipt',{commandId:command.commandId}));
    transactions[step]=result.receipt.transactionId;return result.receipt.outcome;
  }
  async function refuse(step,op,x,code){
    const result=await host.control('/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'submit_cloud_command',arguments:{continuityId,command:envelope(step,op,x)}}});
    assert.equal(result.result.isError,true);assert.equal(JSON.parse(result.result.content[0].text).error,code);refused.push(code);
    assert.equal((await host.rpc('get_cloud_state',{limit:1})).revision,rev);
  }
  const blink=(kind,grounded,availableSize)=>({actionId:'shift',useId:'api37-blink-'+kind,declared:true,
    destination:{label:'Test ledge '+kind,distanceFeet:30,visible:true,unoccupied:true,grounded,availableSize,passengers:0,permittedByBarriers:true}});
  const clock=(name,elapsedSeconds)=>act(name,'advance_actor_time',{elapsedSeconds,confirmed:true},true);
  observed.initialHp=(await sheet()).currentHp;observed.initialIntegrity=await balance('rig-integrity');
  await act('blink-base','use_action',blink('ordinary',false,'tiny'));observed.ordinaryBlink=await balance('blink');
  const begun=await act('begin','begin_form_deployment',{deploymentId:'wire-deployment',formId,declared:true,willing:true});
  const complete={deploymentId:begun.deploymentId,formId,elapsedSeconds:0,uninterrupted:true,willing:true};
  await clock('clock-30',30);await refuse('early','change_form',complete,'DEPLOYMENT_TIME_REMAINING');
  observed.halfwaySeconds=(await device(base+'/forms/'+actorId+'/'+formId,null,auth)).preview.deployment.elapsedSeconds;
  await clock('clock-60',30);await act('complete','change_form',complete);
  const heavy=await sheet();observed.deployedStrength=heavy.abilities.STR.effective;observed.deployedAc=heavy.ac.value;
  observed.completedSeconds=(await get(begun.deploymentId)).elapsedSeconds;
  await refuse('air','use_action',blink('air',false,'large'),'GROUNDED_DESTINATION_REQUIRED');
  await refuse('tight','use_action',blink('tight',true,'tiny'),'DESTINATION_TOO_SMALL');
  await act('blink-heavy','use_action',blink('heavy',true,'large'));observed.deployedBlink=await balance('blink');
  await act('hit-shell','apply_hp_change',{kind:'damage',amount:11});observed.shellAfterHit=await balance('rig-integrity');
  await clock('almost-expired',3599);const almost=await get(actorId);observed.remainingAt3599=almost.formExpiresAt-almost.gameSeconds;
  await clock('expired',1);observed.afterNaturalExpiryHp=(await sheet()).currentHp;
  await act('rest','complete_rest',{kind:'long_rest',elapsedSeconds:28800,confirmed:true,hitDice:[]});observed.restoredOrdinaryHp=(await sheet()).currentHp;
  await act('redeploy','change_form',{formId,elapsedSeconds:60,uninterrupted:true,willing:true});observed.redeployedIntegrity=await balance('rig-integrity');
  const damage={kind:'damage',amount:70},original=envelope('eject','apply_hp_change',damage);
  const eject=await act('eject','apply_hp_change',damage);observed.absorbedOnEjection=eject.absorbedByIntegrity;observed.overflow=eject.beforeHp-eject.afterHp;
  const retry=await device(base+'/commands',original,auth);
  assert.deepEqual(retry,await host.rpc('get_cloud_receipt',{commandId:original.commandId}));
  observed.finalHp=(await sheet()).currentHp;observed.finalIntegrity=await balance('rig-integrity');
  // The rehearsal proves the host-side oracle, but never marks an Android test as passed.
  return verifyNativeCompanion(host,{baseRevision:start.baseRevision,transactions,observed,refused,exactRedelivery:true},false);
}
