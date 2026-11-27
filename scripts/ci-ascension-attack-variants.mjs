/** Independent registered DM readback for the disposable Android attack-variant fixture. */
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';
import {harness,continuityId} from './ci-ascension-cloud-gateway.mjs';

const [phase,privateDirectory,output]=process.argv.slice(2);
const bridge=harness(privateDirectory);
const actorId='native-holder',ranged='synthetic-ranged-probe',melee='synthetic-contact-probe';
const definitions=[[ranged,'Synthetic ranged probe','ranged',4],[melee,'Synthetic contact probe','melee',8]];
if(phase==='seed'){
  await bridge.stateAt(4,12);
  const names=['character','combat','resources','inventory','economy','world','chronicle','rolls','spells'];
  const scopes=names.map(s=>s+'.read').concat(['combat.write']);
  await bridge.control('/v3/continuities/'+continuityId+'/grants/sites-mcp',{
    scopes,actors:['*'],auto:['combat.write'],expiresAt:Date.now()+86400000,expectedGrantRevision:1,revoked:false
  });
  let revision=4;
  for(const [attackId,name,delivery,sides] of definitions){
    const args={actorId,attackId,name,timing:'attack',resolution:'attack',fixedHit:5,delivery,
      ...(delivery==='ranged'?{range:30,longRange:120}:{reach:5}),source:'Reviewed fictional training equipment',
      components:[{id:'element',name:'Element',type:'fire',variants:['fire','cold','acid'],dice:1,sides,flat:0}]};
    await bridge.commit(bridge.cmd(revision,['seed-'+attackId,'define_attack',args]));revision++;
  }
  console.log(JSON.stringify({scope:continuityId,revision,attackIds:definitions.map(r=>r[0])}));
}else if(phase==='verify'){
  const before=JSON.parse(readFileSync(join(output,'variant-before.json'))),after=JSON.parse(readFileSync(join(output,'variant-after.json')));
  assert.equal(before.api,37);assert.equal(after.api,37);
  assert.equal(before.revision,9);assert.equal(after.revision,9);
  assert.equal(before.rangedId,ranged);assert.equal(before.meleeId,melee);
  assert.equal(after.oldReceiptsReverified,3);assert.deepEqual(after.selected,{[ranged]:'fire',[melee]:'cold'});
  const expected=[[ranged,'acid'],[melee,'cold'],[ranged,'fire']];
  assert.equal(before.receipts.length,3);
  const digests=[];
  for(const [i,deviceReceipt] of before.receipts.entries()){
    const independent=await bridge.rpc('get_cloud_receipt',{commandId:deviceReceipt.commandId});
    assert.deepEqual(independent,deviceReceipt);assert.equal(independent.status,'COMMITTED');
    const {outcome,revision,transactionId,changes}=independent.receipt;
    assert.equal(revision,7+i);assert.equal(outcome.attackId,expected[i][0]);
    assert.equal(outcome.componentId,'element');assert.equal(outcome.type,expected[i][1]);assert.equal(outcome.formId,null);
    assert.deepEqual(changes.map(x=>x.after.id),[expected[i][0]]);
    digests.push({revision,transactionId,sha256:createHash('sha256').update(JSON.stringify(independent)).digest('hex')});
  }
  assert.equal(new Set(digests.map(d=>d.transactionId)).size,3);
  const state=await bridge.rpc('get_cloud_state',{limit:100});assert.equal(state.revision,9);
  assert.ok(!state.records.some(r=>['roll','attack_attempt','resource_event'].includes(r.kind)));
  for(const [id,type] of Object.entries(after.selected)){
    const row=state.records.find(r=>r.id===id);assert.equal(row.kind,'attack');assert.equal(row.actorId,actorId);
    assert.deepEqual(row.data.selections,[{actorId,formId:null,componentId:'element',type}]);
  }
  console.log(JSON.stringify({source:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),synthetic:true,api:37,
    revision:state.revision,attackChoices:after.selected,oldReceiptsReverified:3,receiptDigests:digests}));
}else throw Error('Use seed or verify');
