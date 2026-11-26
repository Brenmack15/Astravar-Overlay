/** Host half of the disposable Android 17 TLS/MCP acceptance. Only the isolated loopback gateway is addressable. */
import https from 'node:https';
import {readFileSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';

export const continuityId='qa-api37-reconnect';
const base='/v3/continuities/'+continuityId, actorId='native-holder', source='Synthetic Android 17 fixture';
const coins=gp=>({cp:0,sp:0,ep:0,gp,pp:0});
const readScopes=['character','inventory','economy','resources','chronicle','world'].map(s=>s+'.read');
const scopes=[...readScopes,...readScopes.map(s=>s.replace('.read','.write'))];
const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const ids=['native-change-actor','native-atomic-purchase','native-spend-charge','native-timeline-event'];
const definitions=[
  ['native-seed-actor','define_actor',{actorId,name:'Synthetic engineer',role:'player',rulesProfile:'legacy_2014',level:3,abilities:{INT:12},currentHp:7,maxHp:11}],
  ['native-seed-wallet','define_wallet',{walletId:'native-wallet',title:'Fixture purse',holderId:actorId,shared:false,coins:coins(12),source}],
  ['native-seed-definition','define_item_definition',{definitionId:'native-type',name:'Fixture key',category:'tool',description:'A synthetic key',rarity:null,stackable:false,wearable:false,handheld:true,attunement:'none',source}],
  ['native-seed-resource','define_resource',{actorId,poolId:'native-pool',name:'Fixture charges',type:'ordinary',current:3,maximum:3,recovery:'none',source}],
];
const remote=[
  [ids[0],'set_actor_ability',{actorId,ability:'INT',score:13}],
  [ids[1],'purchase_item',{walletId:'native-wallet',itemId:'native-key',definitionId:'native-type',ownerId:actorId,quantity:1,price:coins(7),sellerId:null,source}],
  [ids[2],'spend_resource',{actorId,poolId:'native-pool',amount:2}],
  [ids[3],'record_chronicle_event',{eventId:'native-event',title:'Fixture discovery',narrative:'The synthetic key was found.',category:'discovery',status:'confirmed',worldTime:null,subjects:[{kind:'actor',id:actorId}],correctsId:null,source}],
];
export function harness(directory,port=18443) {
  const settings=JSON.parse(readFileSync(join(directory,'server-config.json'),'utf8'));
  assert.equal(settings.origin,'https://hp-bridge.example');
  assert.ok(settings.ownerId?.startsWith('ci-fictional-') && settings.controlToken);
  const ca=readFileSync(join(directory,'ca.crt'));
  async function control(path,requestBody) {
    const body=JSON.stringify({method:'POST',path,body:requestBody});
    return new Promise((resolve,reject)=>{
      const req=https.request({hostname:'127.0.0.1',servername:'hp-bridge.example',port,ca,rejectUnauthorized:true,
        method:'POST',path:'/__ci/owner',timeout:12000,headers:{host:'hp-bridge.example','Content-Type':'application/json',
          'X-Astravar-Ci-Control':settings.controlToken}}, res=>{
        const chunks=[];let size=0;
        res.on('data',chunk=>{size+=chunk.length;if(size>2_000_000)res.destroy();else chunks.push(chunk);});
        res.on('error',reject);
        res.on('end',()=>{try{assert.ok(res.statusCode>=200&&res.statusCode<300,'Gateway status '+res.statusCode);
          resolve(JSON.parse(Buffer.concat(chunks).toString()));}catch(error){reject(error);}});
      });
      req.on('timeout',()=>req.destroy(Error('Synthetic gateway timeout')));req.on('error',reject);req.end(body);
    });
  }
  async function rpc(name,args={}) {
    const answer=await control('/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId,...args}}});
    assert.equal(answer.result?.isError,false,'MCP refused '+name);
    return answer.result.structuredContent;
  }
  const cmd=(rev,row)=>({version:1,epoch:1,expectedRevision:rev,commandId:row[0],operation:row[1],args:row[2]});
  async function commit(command) {
    const result=await rpc('submit_cloud_command',{command});
    assert.equal(result.status,'COMMITTED',command.commandId);
    assert.equal(result.revision,command.expectedRevision+1);
    assert.deepEqual(result,await rpc('get_cloud_receipt',{commandId:command.commandId}));
    return result;
  }
  async function stateAt(revision,intelligence) {
    const state=await rpc('get_cloud_state',{limit:100});
    assert.equal(state.epoch,1); assert.equal(state.revision,revision);
    const rows=state.records, find=id=>rows.find(r=>r.id===id);
    assert.equal(rows.length,new Set(rows.map(r=>r.id)).size);
    assert.equal(find(actorId).data.abilities.INT,intelligence);
    if(revision>=8){
      assert.deepEqual(find('native-wallet').data.coins,coins(5));
      assert.equal(find('native-key').data.quantity,1);
      assert.equal(find('native-pool').data.current,1);
      assert.equal(find('native-event').data.status,'confirmed');
      assert.equal(!!find('native-approved'),revision>=9);
    }
    return {revision:state.revision,epoch:state.epoch,visibleRecordCount:rows.length};
  }
  return {control,rpc,cmd,commit,stateAt};
}
export async function runHost(directory,phase,port=18443) {
  const {control,rpc,cmd,commit,stateAt}=harness(directory,port);
  if(phase==='setup'){
    const created=await control(base+'/isolated',{});assert.equal(created.authority,'cloud');
    await control(base+'/grants/sites-mcp',{scopes,actors:['*'],auto:scopes.filter(s=>s.endsWith('.write')&&s!=='world.write'),
      expiresAt:Date.now()+86400000,expectedGrantRevision:0,revoked:false});
    for(let i=0;i<definitions.length;i++)await commit(cmd(i,definitions[i]));
    return {synthetic:true,phase,...await stateAt(4,12)};
  }
  if(phase==='offline'){
    await stateAt(4,12);
    const receipts=[];
    for(let i=0;i<remote.length;i++)receipts.push(await commit(cmd(4+i,remote[i])));
    assert.deepEqual(await rpc('submit_cloud_command',{command:cmd(5,remote[1])}),receipts[1]);
    const pending=await rpc('submit_cloud_command',{command:cmd(8,['native-pending-world','define_connection',
      {entityId:'native-approved',name:'Reviewed fixture',category:'place',actorId:null,knownDescription:'Synthetic location',source}])});
    assert.equal(pending.status,'PENDING_APPROVAL');
    assert.equal((await rpc('get_cloud_receipt',{commandId:'native-pending-world'})).status,'PENDING_APPROVAL');
    const receiptHashes=receipts.map((value,index)=>({commandId:ids[index],transactionId:value.receipt.transactionId,sha256:digest(value)}));
    writeFileSync(join(directory,'cloud-receipts.json'),JSON.stringify(receiptHashes),{flag:'wx',mode:0o600});
    return {synthetic:true,phase,...await stateAt(8,13),pending:'PENDING_APPROVAL',receiptHashes};
  }
  assert.equal(phase,'device-readback');
  const prior=JSON.parse(readFileSync(join(directory,'cloud-receipts.json'),'utf8'));
  for(const r of prior)assert.equal(digest(await rpc('get_cloud_receipt',{commandId:r.commandId})),r.sha256);
  const approval=await rpc('get_cloud_receipt',{commandId:'native-pending-world'});
  assert.equal(approval.status,'COMMITTED');assert.equal(approval.receipt.revision,9);
  const edit=await rpc('get_cloud_receipt',{commandId:'native-return-edit'});
  assert.equal(edit.status,'COMMITTED');assert.equal(edit.receipt.revision,10);
  assert.equal(edit.receipt.changes.find(c=>c.after?.id===actorId).after.data.abilities.INT,14);
  return {synthetic:true,phase,...await stateAt(10,14),deviceCommandId:edit.commandId,
    deviceTransactionId:edit.receipt.transactionId,deviceReceiptSha256:digest(edit),priorReceiptsReverified:prior.length};
}
if(process.argv[1] && import.meta.url===new URL('file://'+process.argv[1]).href){
  const [phase,directory]=process.argv.slice(2);
  if(!directory||!['setup','offline','device-readback'].includes(phase))throw Error('An explicit private synthetic gateway directory and phase are required');
  console.log(JSON.stringify(await runHost(directory,phase)));
}
