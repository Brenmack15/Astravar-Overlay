/* Real local workerd+D1+R2 recovery. No public service, user credentials or campaign input. */
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const {execFileSync}=require('node:child_process');
const {pathToFileURL}=require('node:url');
const recovery=require('../tools/ascension_provider.cjs');
const mod=process.argv[2]?path.resolve(process.argv[2]):undefined;
const project=path.resolve(__dirname,'..');
const owner='isolated-provider-owner',id='qa-provider-backup',route='/v3/continuities/'+id;
const origin='https://fixture.invalid';
async function request(runtime,endpoint,method='GET',args=null,raw=false){
  const result=await runtime.dispatch(origin+endpoint,{method,headers:{'oai-authenticated-user-id':owner,Origin:origin,
    'Content-Type':'application/json'},...(args===null?{}:{body:JSON.stringify(args)})});
  if(raw){assert.equal(result.status,200);return Buffer.from(await result.arrayBuffer());}
  return {status:result.status,body:await result.json()};
}
async function required(runtime,endpoint,method='GET',args=null){
  const reply=await request(runtime,endpoint,method,args);
  assert.ok(reply.status<300,JSON.stringify(reply.body));return reply.body;
}
async function rpc(runtime,name,args){
  const reply=await required(runtime,'/mcp','POST',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId:id,...args}}});
  assert.ok(!reply.result.isError,JSON.stringify(reply.result));return reply.result.structuredContent;
}
const envelope=(commandId,revision,operation,args,epoch=1)=>({version:1,epoch,commandId,expectedRevision:revision,operation,args});
const submit=(r,cmd)=>rpc(r,'submit_cloud_command',{command:cmd});
const approve=(r,p)=>required(r,route+'/approvals/'+p.commandId+'/verify','POST',{digest:p.digest});
async function grant(r){
  const scopes=['character','media','chronicle'].flatMap(s=>[s+'.read',s+'.write']);
  return required(r,route+'/grants/sites-mcp','POST',{scopes,actors:['*'],auto:['character.write','media.write'],revoked:false,
    expiresAt:Date.now()+86400000,expectedGrantRevision:0});
}
async function unresolvedMappingBlocksProviderCutover(tmp){
  const blockedRoot=path.join(tmp,'retained-root'),native=path.join(tmp,'unresolved-native.json');
  const mapping=path.join(tmp,'review.json'),archive=path.join(tmp,'retained.zip');
  fs.writeFileSync(native,JSON.stringify({format:'astravar-reforge',schema:2,exportedAt:100,characters:[],
    rolls:[{id:'unresolved-roll',profileId:'unknown-member',formula:'1d20'}],changes:[]}));
  const cli=path.join(project,'tools/ascension_migrate.py'),scope=['--source',native,'--owner',owner,'--continuity',id];
  const plan=JSON.parse(execFileSync('python3',[cli,'mapping-template',...scope],{encoding:'utf8'}));
  assert.equal(plan.decisions.length,1);
  Object.assign(plan.decisions[0],{disposition:'retain',reason:'The original does not establish campaign membership.'});
  fs.writeFileSync(mapping,JSON.stringify(plan));
  execFileSync('python3',[cli,'convert-reviewed',...scope,mapping,archive]);
  await recovery.create(blockedRoot,owner,id,mod);
  const stage=await recovery.prepare(blockedRoot,archive,null,mod);
  await recovery.prepare(blockedRoot,archive,stage.approvalSha256,mod);
  await assert.rejects(recovery.cutover(blockedRoot,null,false,mod),/Source records still require reviewed reconciliation/);
  const state=await recovery.inspect(blockedRoot,mod);
  assert.equal(state.current,null);
  assert.equal(state.stage.authority,'staged');
}
async function verify(){
  process.umask(0o077);const previous=process.cwd(),tmp=fs.mkdtempSync(path.join(os.tmpdir(),'ascension-provider-proof-'));
  process.chdir(tmp);
  const root=path.join(tmp,'store'),zip=path.join(tmp,'cold.zip');
  try{
    await unresolvedMappingBlocksProviderCutover(tmp);
    await recovery.create(root,owner,id,mod);
    const {mediaDigest}=await import(pathToFileURL(path.join(project,'backend/owner-bridge/cloud-media-codec.mjs')));
    // Synthetic 18 × 12 RGB PNG emitted by Android's bitmap encoder, including its significant-bits metadata.
    const bytes=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAABIAAAAMCAIAAADgcHrrAAAAAXNSR0IArs4c6QAAAANzQklUCAgI2+FP4AAAABhJREFUKJFj3KbmyUA6YCJDz6i2UW1EAQATOAE9sOf9HQAAAABJRU5ErkJggg==','base64');
    const imageHash=await mediaDigest(bytes), actorId='synthetic-actor';
    let actorProof,pending;
    await recovery.withGameplay(root,async r=>{
      await required(r,route+'/isolated','POST',{});await grant(r);
      const cmd=envelope('provider-create-actor',0,'define_actor',{actorId,name:'Provider fixture',role:'player',rulesProfile:'legacy_2014',level:2,abilities:{INT:14}});
      actorProof=await submit(r,cmd);assert.equal(actorProof.status,'COMMITTED');
      const img={uploadId:'supplied-image',targetKind:'actor',targetId:actorId,sha256:imageHash};
      const candidate=await rpc(r,'stage_cloud_image',{...img,bytesBase64:Buffer.from(bytes).toString('base64')});
      assert.equal(candidate.sha256,imageHash);
      const proposal=await submit(r,envelope('provider-artwork',1,'assign_primary_image',{...img,assetId:'synthetic-image'}));
      assert.deepEqual(await request(r,route+'/media/uploads/supplied-image/original','GET',null,true),Buffer.from(bytes));
      assert.equal((await approve(r,proposal)).status,'COMMITTED');
      pending=await submit(r,envelope('provider-pending-event',2,'record_chronicle_event',{eventId:'fixture-event',title:'Proposed entry',
        narrative:'Awaiting owner verification.',category:'conversation',worldTime:null,subjects:[{kind:'actor',id:actorId}],correctsId:null,status:'confirmed',source:'isolated fixture'}));
      assert.equal(pending.status,'PENDING_APPROVAL');
      const queue=await required(r,route+'/review-inbox?limit=1');
      assert.equal(queue.actionableCount,1);
      assert.deepEqual(queue.requests.map(rec=>rec.commandId),[pending.commandId]);
      const exact=await required(r,route+'/review-inbox/'+pending.commandId+'?digest='+pending.digest);
      assert.equal(exact.readiness,'READY');
      assert.equal(exact.plan.changes[0].after.id,'fixture-event');
    },mod);
    const portable=await recovery.backup(root,zip,mod);
    const checked=JSON.parse(execFileSync('python3',[path.join(project,'tools/ascension_migrate.py'),'validate',zip],{encoding:'utf8'}));
    assert.equal(checked.manifestSha256,portable.manifestSha256);assert.equal(checked.counts.cloud_requests,3);
    assert.equal(checked.counts.cloud_receipts,2);assert.ok(checked.media>=1);
    // Logical snapshots containing missing/stale bytes must fail closed.
    await recovery.withGameplay(root,async r=>{
      const all=await r.bucket.list(),original=all.objects.find(o=>o.key.endsWith('/original.png'));
      await r.bucket.delete(original.key);
    },mod);
    await assert.rejects(recovery.backup(root,path.join(tmp,'incomplete.zip'),mod),/R2 object/);
    assert.equal(fs.existsSync(path.join(tmp,'incomplete.zip')),false);
    await recovery.withGameplay(root,async r=>{
      const all=await r.bucket.list(),key=all.objects.find(o=>o.key.endsWith('/thumbnail.png')).key.replace('/thumbnail.png','/original.png');
      await r.bucket.put(key,bytes,{sha256:imageHash,httpMetadata:{contentType:'image/png'}});
    },mod);
    const offline=await require('./verify-ascension-generation.cjs').rehearse(tmp,zip,mod);
    assert.ok(offline.rows>0 && offline.objects>=2 && offline.authority==='staged');
    console.log(JSON.stringify({offlineUnroutedGeneration:offline.status,rows:offline.rows,objects:offline.objects}));
    let inspected=await recovery.prepare(root,zip,null,mod);
    await assert.rejects(recovery.prepare(root,zip,'f'.repeat(64),mod),/reviewed stage/);
    await recovery.prepare(root,zip,inspected.approvalSha256,mod);
    const undo=await recovery.rollbackStage(root);
    assert.equal((await recovery.rollbackStage(root,undo.approvalSha256)).status,'ROLLED_BACK');
    inspected=await recovery.prepare(root,zip,null,mod);
    await recovery.prepare(root,zip,inspected.approvalSha256,mod);
    await assert.rejects(recovery.cutover(root,null,false,mod),/Pending work/);
    const preview=await recovery.cutover(root,null,true,mod);assert.equal(preview.pending,1);
    await assert.rejects(recovery.cutover(root,preview.approvalSha256,true,mod,()=>{throw Error('simulated maintenance interruption');}),/simulated maintenance/);
    await assert.rejects(recovery.withGameplay(root,()=>{},mod),/interrupted cutover/);
    const interrupted=await recovery.inspect(root,mod);
    assert.equal(interrupted.status,'CUTOVER_IN_PROGRESS');
    assert.deepEqual([interrupted.current.authority,interrupted.current.epoch,interrupted.stage.authority,interrupted.stage.epoch],
      ['staged',2,'staged',1]);
    const committed=await recovery.cutover(root,preview.approvalSha256,true,mod);assert.equal(committed.epoch,2);
    assert.equal((await recovery.cutover(root,preview.approvalSha256,true,mod)).status,'ALREADY_COMMITTED');
    const routed=await recovery.inspect(root,mod);assert.equal(routed.current.authority,'cloud');assert.equal(routed.previous.authority,'staged');
    const newer=path.join(tmp,'next.zip');
    await recovery.withGameplay(root,async r=>{
      const state=await required(r,route+'/state');assert.equal(state.epoch,2);assert.equal(state.revision,2);
      assert.equal(state.records.find(x=>x.id===actorId).data.abilities.INT,14);
      assert.deepEqual(await required(r,route+'/receipts/provider-create-actor'),actorProof);
      assert.deepEqual(await request(r,route+'/media/primary/'+actorId+'/original','GET',null,true),Buffer.from(bytes));
      assert.equal((await r.db.prepare('SELECT count(*) AS n FROM cloud_grants').first()).n,0);
      assert.equal((await r.db.prepare('SELECT count(*) AS n FROM bridge_credentials').first()).n,0);
      await grant(r);
      const decision=await approve(r,pending);assert.equal(decision.status,'CONFLICT');
      assert.equal((await required(r,route+'/state?id=fixture-event')).records.length,0);
      const stale=await request(r,route+'/commands','POST',envelope('prior-epoch-command',2,'set_actor_ability',{actorId,ability:'INT',score:12},1));
      assert.equal(stale.status,409);
      const newChange=await submit(r,envelope('provider-after-recovery',2,'set_actor_ability',{actorId,ability:'INT',score:16},2));
      assert.equal(newChange.status,'COMMITTED');assert.equal(newChange.revision,3);
    },mod);
    await assert.rejects(recovery.prepare(root,zip,null,mod),/newer or divergent/);
    await assert.rejects(recovery.rollbackStage(root),/unpublished/);
    const next=await recovery.backup(root,newer,mod);assert.equal(next.source.epoch,2);
    const newPreview=await recovery.prepare(root,newer,null,mod);await recovery.prepare(root,newer,newPreview.approvalSha256,mod);
    // A legitimate change after review must prevent stale authority publication.
    await recovery.withGameplay(root,async r=>{await submit(r,envelope('provider-after-staging',3,'set_actor_ability',{actorId,ability:'INT',score:17},2));},mod);
    await assert.rejects(recovery.cutover(root,null,false,mod),/Source changed after staging/);
    const growing=path.join(tmp,'changed-during-export.zip');
    await recovery.withGameplay(root,async r=>{
      await assert.rejects(recovery.backup(root,path.join(tmp,'contended.zip'),mod),/maintenance lock/);
      let changed=false;
      const changingBucket={
        get:async key=>{
          const object=await r.bucket.get(key);
          if(!changed){
            changed=true;
            const scopes=['character','media','chronicle'].flatMap(s=>[s+'.read',s+'.write']);
            await required(r,route+'/grants/sites-mcp','POST',{scopes,actors:['*'],auto:['character.write','media.write'],
              revoked:false,expiresAt:Date.now()+86400000,expectedGrantRevision:1});
          }
          return object;
        },
        head:key=>r.bucket.head(key),
      };
      await assert.rejects(recovery.backupBindings({db:r.db,bucket:changingBucket},owner,id,growing),/D1 changed during R2 capture/);
      assert.equal(fs.existsSync(growing),false);
      await required(r,'/v3/continuities/qa-sibling/isolated','POST',{});
    },mod);
    const scopedBackup=path.join(tmp,'one-continuity.zip');
    assert.equal((await recovery.backup(root,scopedBackup,mod)).source.revision,4);
    await assert.rejects(recovery.prepare(root,scopedBackup,null,mod),/shared provider root/);
    console.log(JSON.stringify({status:'VERIFIED',runtime:'workerd',initialRevision:2,recoveredEpoch:2,postRecoveryRevision:4,
      originalReceipts:2,preservedPending:1,restoredImage:true,failedExportPublished:false,staleCutoverRefused:true,inFlightGrantChangeDetected:true,sharedGenerationProtected:true,unresolvedSourceCutoverBlocked:true,providerInboxRead:true,production:false}));
  }finally{process.chdir(previous);fs.rmSync(tmp,{force:true,recursive:true});}
}
verify().catch(error=>{console.error(error.stderr?.toString().trim()||error.stack);process.exitCode=1;});
