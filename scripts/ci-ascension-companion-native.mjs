/** Compare observations from the reserved API 37 guest with independent registered DM receipts. */
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {harness,continuityId} from './ci-ascension-cloud-gateway.mjs';
import {defineFrameFixture} from '../backend/owner-bridge/fixtures/representative-forms.mjs';

export const actorId='api37-frame',formId='rig-form',prefix='qa37-form-';
export const steps=['blink-base','begin','clock-30','clock-60','complete','blink-heavy','hit-shell','almost-expired','expired','rest','redeploy','eject'];
const fixturePath=new URL('../backend/owner-bridge/fixtures/representative-forms.mjs',import.meta.url);
const scopeNames=['character','resources','combat','inventory','rolls','spells','economy','chronicle','world'];
export const readsAndWrites=scopeNames.flatMap(s=>[s+'.read',s+'.write']);
const sha=value=>createHash('sha256').update(value).digest('hex');

export async function seedNativeCompanion(bridge){
  // This phase runs only after the preceding force-stop fixture has committed its independently verified return edit.
  await bridge.stateAt(10,14);
  await bridge.control('/v3/continuities/'+continuityId+'/grants/sites-mcp',{scopes:readsAndWrites,actors:['*'],auto:readsAndWrites.filter(s=>s.endsWith('.write')&&s!=='world.write'),
    expiresAt:Date.now()+86400000,expectedGrantRevision:1,revoked:false});
  let revision=10;
  const command=async(operation,args)=>{
    const approved=await bridge.commit(bridge.cmd(revision,[prefix+'seed-'+(revision+1),operation,args]));
    revision=approved.revision;
  };
  const f={command,
    defined:args=>command('define_actor',{actorId,name:'Synthetic frame companion',role:'player',rulesProfile:'legacy_2014',level:12,gameSeconds:100,
      abilities:{STR:8,DEX:14,CON:14,INT:20,WIS:11,CHA:10},baseAc:16,maxHp:60,currentHp:60,temporaryHp:0,...args}),
    pool:(poolId,type,current,maximum,recovery,more={})=>command('define_resource',{actorId,poolId,name:poolId,type,current,maximum,recovery,source:'Synthetic rig',...more}),
    effect:(effectId,target,key,operation,more={})=>command('define_effect',{actorId,effectId,name:effectId,target,key,operation,source:'Synthetic rig',...more})};
  await defineFrameFixture(f,actorId);
  await command('apply_hp_change',{actorId,kind:'damage',amount:4});
  const state=await bridge.rpc('get_cloud_actor_sheet',{id:actorId});
  assert.equal(state.sheet.currentHp,56);assert.equal(state.sheet.ac.value,16);assert.equal(state.revision,revision);
  return {fixture:'EQ26-0091',actorId,formId,baseRevision:revision,fixtureSha256:sha(readFileSync(fixturePath))};
}

export async function verifyNativeCompanion(bridge,witness,native=true){
  const final=await bridge.rpc('get_cloud_actor_sheet',{id:actorId}),data=final.sheet;
  assert.equal(final.epoch,1);
  assert.deepEqual([data.formId,data.currentHp,data.maxHp,data.ac.value,data.abilities.STR.effective,data.movement.walk,data.movement.fly],
    [null,54,60,16,8,5,60]);
  const get=async id=>(await bridge.rpc('get_cloud_state',{id})).records[0].data;
  assert.deepEqual(await Promise.all(['rig-integrity','blink','deploy','roar'].map(async id=>(await get(id)).current)),[0,3,0,1]);
  const hashes=[],out={};
  for(const [i,name] of steps.entries()){
    const receipt=await bridge.rpc('get_cloud_receipt',{commandId:prefix+name});
    assert.equal(receipt.status,'COMMITTED');
    assert.equal(receipt.receipt.revision,witness.baseRevision+i+1);
    assert.equal(receipt.receipt.transactionId,witness.transactions[name]);
    out[name]=receipt.receipt.outcome;
    hashes.push({step:name,revision:receipt.revision,transactionId:receipt.receipt.transactionId,sha256:sha(JSON.stringify(receipt))});
  }
  assert.equal(new Set(hashes.map(r=>r.transactionId)).size,steps.length);
  assert.equal(final.revision,witness.baseRevision+steps.length);
  assert.deepEqual([out['blink-base'].costs[0].after,out['blink-heavy'].costs[0].after],[2,1]);
  assert.equal(out['blink-base'].destination.grounded,false);assert.equal(out['blink-heavy'].destination.grounded,true);
  assert.equal(out['blink-heavy'].destination.availableSize,'large');
  const deployment=await get(out.begin.deploymentId);
  assert.equal(deployment.status,'completed');assert.equal(deployment.elapsedSeconds,60);assert.equal(deployment.formId,formId);
  assert.equal(out['hit-shell'].absorbedByIntegrity,11);assert.equal(out.eject.absorbedByIntegrity,64);
  const ordinary=await get(actorId);
  assert.equal(ordinary.activeFormId,null);assert.equal(ordinary.currentHp,54);
  // Verify the exact canonical mutation in the immutable evidence, including the original ordinary HP on either side.
  const pair=async(step,id)=>{
    const receipt=await bridge.rpc('get_cloud_receipt',{commandId:prefix+step});
    const row=receipt.receipt.changes.find(c=>c.after?.id===id);assert.ok(row);return [row.before.data,row.after.data];
  };
  assert.deepEqual((await pair('hit-shell','rig-integrity')).map(d=>d.current),[75,64]);
  assert.deepEqual((await pair('eject','rig-integrity')).map(d=>d.current),[64,0]);
  assert.deepEqual((await pair('eject',actorId)).map(d=>d.currentHp),[60,54]);
  const expiry=await pair('expired',actorId);
  assert.equal(expiry[0].activeFormId,formId);assert.equal(expiry[1].activeFormId,null);assert.equal(expiry[1].currentHp,56);
  const second=await pair('redeploy',actorId);
  assert.deepEqual(second.map(d=>d.currentHp),[60,60]);
  assert.deepEqual(witness.observed,{initialHp:56,initialIntegrity:75,halfwaySeconds:30,completedSeconds:60,deployedStrength:20,deployedAc:18,
    ordinaryBlink:2,deployedBlink:1,remainingAt3599:1,afterNaturalExpiryHp:56,shellAfterHit:64,restoredOrdinaryHp:60,redeployedIntegrity:64,
    absorbedOnEjection:64,overflow:6,finalHp:54,finalIntegrity:0});
  assert.deepEqual(witness.refused,['DEPLOYMENT_TIME_REMAINING','GROUNDED_DESTINATION_REQUIRED','DESTINATION_TOO_SMALL']);
  assert.equal(witness.exactRedelivery,true);if(native)assert.equal(witness.reopenedNativeCache,true);
  return {requirement:'EQ26-0091',fixtureSha256:sha(readFileSync(fixturePath)),baselineRevision:witness.baseRevision,finalRevision:final.revision,
    observed:witness.observed,refused:witness.refused,exactRedelivery:true,...(native?{reopenedNativeCache:true}:{}),immutableReceipts:hashes};
}

if(process.argv[1] && import.meta.url===new URL('file://'+process.argv[1]).href){
  const [phase,directory,deviceFile]=process.argv.slice(2);
  assert.ok(directory && ['seed','verify'].includes(phase));
  const bridge=harness(directory);
  if(phase==='seed')console.log(JSON.stringify(await seedNativeCompanion(bridge)));
  else{
    const device=JSON.parse(readFileSync(deviceFile,'utf8'));
    assert.equal(device.api,37);assert.equal(device.androidRelease,'17');assert.equal(device.emulator,true);
    const verified=await verifyNativeCompanion(bridge,device);
    const source=execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8',cwd:fileURLToPath(new URL('..',import.meta.url))}).trim();
    assert.match(source,/^[0-9a-f]{40}$/);
    console.log(JSON.stringify({...verified,sourceCommit:source,api:37,androidRelease:'17',emulator:true,deviceWitnessSha256:sha(readFileSync(deviceFile))},null,2));
  }
}
