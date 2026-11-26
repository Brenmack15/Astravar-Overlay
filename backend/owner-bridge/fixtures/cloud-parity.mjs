/** Exercise canonical gameplay through the registered MCP and paired Android wire routes. */
import { strict as assert } from 'node:assert';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { handleWorker } from '../worker.mjs';
import { sha256 } from '../core.mjs';

const coverage=new Map();
const direction=process.env.ASTRAVAR_PARITY_SIDE==='device'?'device':'mcp';
if(process.env.ASTRAVAR_PARITY_REPORT_DIR)process.once('exit',()=>{
  writeFileSync(join(process.env.ASTRAVAR_PARITY_REPORT_DIR,process.pid+'-'+direction+'.json'),JSON.stringify({direction,operations:Object.fromEntries(coverage)}));
});

export function cloudParity(dbProvider,ownerId,continuityId,mediaBucket=null){
  const origin='https://isolated-parity.invalid',base='/v3/continuities/'+continuityId;
  const device='parity-device',subject='device:'+device,token=crypto.randomUUID().replaceAll('-','')+crypto.randomUUID().replaceAll('-','');
  let synced=-1;
  async function wire(route,body,identity){
    const response=await handleWorker(new Request(origin+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',
      ...(identity==='device'?{Authorization:'Bearer '+token,'X-Astravar-Credential':device}:{'oai-authenticated-user-id':ownerId,Origin:origin})},
      ...(body===undefined?{}:{body:JSON.stringify(body)})}),{DB:dbProvider(),OWNER_SITE_USER_ID:ownerId,ASTRAVAR_CLOUD_ENABLED:'1',ASTRAVAR_CLOUD_ENV:'isolated',ASTRAVAR_MEDIA:mediaBucket});
    const value=await response.json();
    if(!response.ok)throw Object.assign(new Error(value.error?.code??'WIRE_FAILURE'),{code:value.error?.code});
    return value;
  }
  async function mcp(name,args) {
    const response=await wire('/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId,...args}}},'owner');
    if(response.result?.isError){const code=JSON.parse(response.result.content[0].text).error;throw Object.assign(new Error(code),{code});}
    assert(response.result?.structuredContent,'MCP returned no structured outcome');
    return response.result.structuredContent;
  }
  async function synchronizeDevice(){
    const db=dbProvider();
    const g=await db.prepare('SELECT * FROM cloud_grants WHERE owner_id=? AND continuity_id=? AND subject=?').bind(ownerId,continuityId,'sites-mcp').first();
    if(!g||g.revision===synced)return;
    if(synced===-1)await db.prepare("INSERT INTO bridge_credentials(id,owner_id,campaign_id,token_hash,role,scopes_json,device_name,expires_at) VALUES(?,?,?,?,'device','[]','Synthetic parity client',?)")
      .bind(device,ownerId,continuityId,await sha256(token),Date.now()+86400000).run();
    const old=await db.prepare('SELECT revision FROM cloud_grants WHERE owner_id=? AND continuity_id=? AND subject=?').bind(ownerId,continuityId,subject).first();
    await wire(base+'/grants/'+subject,{scopes:JSON.parse(g.scopes_json),actors:JSON.parse(g.actors_json),auto:JSON.parse(g.auto_json),
      expiresAt:g.expires_at,revoked:Boolean(g.revoked),expectedGrantRevision:old?.revision??0},'owner');
    synced=g.revision;
  }
  async function submit(command,options={}) {
    await synchronizeDevice();
    const fromDevice=direction==='device';
    const send=()=>fromDevice?wire(base+'/commands',command,'device'):mcp('submit_cloud_command',{command});
    let result=await send();
    if(options.review){
      assert.equal(result.status,'PENDING_APPROVAL');
      const before=await dbProvider().prepare('SELECT revision FROM cloud_continuities WHERE owner_id=? AND continuity_id=?').bind(ownerId,continuityId).first();
      assert.equal(before.revision,command.expectedRevision);
      const inbox=await wire(base+'/approvals',undefined,'owner');
      const waiting=inbox.requests.find(row=>row.commandId===command.commandId);
      assert.equal(waiting.digest,result.digest);
      if(options.inspect)await options.inspect();
      result=await wire(base+'/approvals/'+command.commandId+'/verify',{digest:waiting.digest},'owner');
      assert.equal(result.status,'COMMITTED');
    }
    if(result.status!=='COMMITTED')return result;
    assert.deepEqual(await send(),result,'Exact retry changed the receipt');
    const independent=fromDevice?await mcp('get_cloud_receipt',{commandId:command.commandId}):
        await wire(base+'/receipts/'+command.commandId,undefined,'device');
    assert.deepEqual(independent,result,'The two authorized protocols disagree on the immutable receipt');
    const existing=coverage.get(command.operation)??{commands:0,readbacks:0};
    existing.commands++;
    for(const change of result.receipt.changes??[]){
      const expected=change.after,id=expected?.id??change.before.id;
      const page=fromDevice ? await mcp('get_cloud_state',{id}) : await wire(base+'/state?id='+id,undefined,'device');
      assert(page.revision>=result.revision);
      if(page.revision===result.revision){
        if(expected?.life==='active')assert.deepEqual(page.records,[expected]);
        else assert.deepEqual(page.records,[]);
      }
      existing.readbacks++;
    }
    coverage.set(command.operation,existing);
    return result;
  }
  // The owner must actually open the exact private candidate. This synthetic
  // verification client checks the original bytes and their target independently.
  submit.stageImage=async(uploadId,targetId,bytes,digest)=>{
    await synchronizeDevice();
    const env={DB:dbProvider(),OWNER_SITE_USER_ID:ownerId,ASTRAVAR_CLOUD_ENABLED:'1',ASTRAVAR_CLOUD_ENV:'isolated',ASTRAVAR_MEDIA:mediaBucket};
    const endpoint=origin+base+'/media/uploads/'+uploadId;
    if(direction==='mcp'){
      const staged=await mcp('stage_cloud_image',{uploadId,targetId,targetKind:'actor',sha256:digest,bytesBase64:Buffer.from(bytes).toString('base64')});
      assert.equal(staged.sha256,digest);
    }else{
      const response=await handleWorker(new Request(endpoint+'?targetKind=actor&targetId='+encodeURIComponent(targetId),{
        method:'PUT',body:bytes,headers:{'Content-Type':'image/png','X-Astravar-Content-SHA256':digest,Authorization:'Bearer '+token,'X-Astravar-Credential':device}}),env);
      assert(response.ok);assert.equal((await response.json()).sha256,digest);
    }
    return async()=>{
      const response=await handleWorker(new Request(endpoint+'/original',{headers:{'oai-authenticated-user-id':ownerId}}),env);
      assert(response.ok);assert.deepEqual(new Uint8Array(await response.arrayBuffer()),bytes);
    };
  };
  return submit;
}
