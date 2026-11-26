import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { crc32, deflateSync } from 'node:zlib';
import { fileBucket } from './fixtures/media-bucket.mjs';
import { handleWorker } from './worker.mjs';
import { SqliteAdapter } from './sqlite-adapter.mjs';
import { CloudService } from './cloud-service.mjs';
import { CLOUD_MEDIA_KINDS, CLOUD_MEDIA_OPERATIONS, handleCloudMedia, stageCloudMcpImage } from './cloud-media.mjs';
import { encodeMediaPng, inspectMediaPng, mediaDigest } from './cloud-media-codec.mjs';
import { CLOUD_LIFECYCLE_OPERATIONS } from './cloud-lifecycle.mjs';

const schema=await readFile(new URL('./schema.sql',import.meta.url),'utf8');
const id='qa-media';
const ownerId='synthetic-art-owner';
const owner={ownerId,role:'owner',credentialId:'sites-owner'};
const dm={ownerId,role:'connector',credentialId:'sites-mcp'};
const scopes=['character.read','character.write','media.read','media.write'];

async function fixture(t) {
  const db=new SqliteAdapter();t.after(()=>db.close());db.exec(schema);
  const service=new CloudService(db,{ownerId,isolated:true,operations:{...CLOUD_MEDIA_OPERATIONS,...CLOUD_LIFECYCLE_OPERATIONS},kinds:{actor:'character',...CLOUD_MEDIA_KINDS}});
  service.mediaBucket=await fileBucket(t);
  await service.createIsolated(owner,id);
  await service.setGrant(owner,id,'sites-mcp',{scopes,auto:['character.write','media.write'],actors:['actor-a'],revoked:false,
    expectedGrantRevision:0,expiresAt:Date.now()+3600000});
  const insert=db.db.prepare(`INSERT INTO cloud_entities(owner_id,continuity_id,id,kind,actor_id,title,data_json,revision)
    VALUES(?,?,?,'actor',?,'Fixture','{"currentHp":17}',1)`);
  for(const actorId of ['actor-a','actor-b'])insert.run(ownerId,id,actorId,actorId);
  const request=(principal,path,method='GET',bytes=null,hash=null,kind='actor',target='actor-a')=>{
    const url='https://private-bridge.example/v3/continuities/'+id+'/'+path+
      (method==='PUT'?'?targetKind='+kind+'&targetId='+target:'');
    const options=bytes?{method,body:bytes,headers:{'Content-Type':'image/png','X-Astravar-Content-SHA256':hash}}:{method};
    return handleCloudMedia(new Request(url,options),service,principal,id,path);
  };
  const submit=(uploadId,assetId,hash,expectedRevision=0,targetId='actor-a')=>service.submit(dm,id,{
    version:1,commandId:'cmd-'+uploadId,epoch:1,expectedRevision,operation:'assign_primary_image',
    args:{uploadId,assetId,targetId,targetKind:'actor',sha256:hash}});
  return {service,db,request,submit};
}
async function fixtureImage(value=40,width=180,height=4) {
  const pixels=new Uint8Array(width*height*4).fill(value);
  for(let i=3;i<pixels.length;i+=4)pixels[i]=255;
  const bytes=await encodeMediaPng(width,height,pixels);
  return {bytes,hash:await mediaDigest(bytes)};
}

test('static PNG decoder verifies full pixels and creates a bounded independently decodable thumbnail',async()=>{
  const {bytes,hash}=await fixtureImage();
  const {meta,thumbnail}=await inspectMediaPng(bytes);
  assert.deepEqual([meta.sha256,meta.width,meta.height,meta.thumbnailWidth,meta.thumbnailHeight],[hash,180,4,160,4]);
  const preview=await inspectMediaPng(thumbnail);
  assert.deepEqual([preview.meta.width,preview.meta.height,preview.meta.sha256],[160,4,meta.thumbnailSha256]);
  const altered=bytes.slice();altered[altered.length-14]^=1;
  await assert.rejects(inspectMediaPng(altered),{code:'INVALID_OR_UNSUPPORTED_PNG'});
  await assert.rejects(inspectMediaPng(bytes.subarray(0,bytes.length-1)),{code:'INVALID_OR_UNSUPPORTED_PNG'});
  await assert.rejects(inspectMediaPng(new TextEncoder().encode('<svg></svg>')),{code:'INVALID_OR_UNSUPPORTED_PNG'});
});

test('only an exact owner-reviewed candidate becomes the displayed image and leaves actor mechanics untouched',async t=>{
  const {service,db,request,submit}=await fixture(t);
  const {bytes,hash}=await fixtureImage();
  const before=db.db.prepare('SELECT * FROM cloud_entities WHERE id=?').get('actor-a');
  const uploaded=await (await request(dm,'media/uploads/first-image','PUT',bytes,hash)).json();
  assert.equal(uploaded.sha256,hash);
  assert.equal(uploaded.status,'CANDIDATE_READY');
  assert.equal((await (await request(dm,'media/uploads/first-image','PUT',bytes,hash)).json()).sha256,hash);
  assert.equal(service.mediaBucket.keys.size,2);
  const pending=await submit('first-image','asset-a',hash);
  assert.equal(pending.status,'PENDING_APPROVAL');
  await assert.rejects(request(owner,'media/primary/actor-a/original'),{code:'IMAGE_UNAVAILABLE'});
  await assert.rejects(service.review(owner,id,'cmd-first-image',pending.digest,'verify'),{code:'OWNER_IMAGE_PREVIEW_REQUIRED'});
  await request(dm,'media/uploads/first-image/original');
  await request(owner,'media/uploads/first-image/thumbnail');
  await assert.rejects(service.review(owner,id,'cmd-first-image',pending.digest,'verify'),{code:'OWNER_IMAGE_PREVIEW_REQUIRED'});
  const image=await request(owner,'media/uploads/first-image/original');
  assert.equal(image.headers.get('x-astravar-content-sha256'),hash);
  assert.deepEqual(new Uint8Array(await image.arrayBuffer()),bytes);
  assert.equal((await service.review(owner,id,'cmd-first-image',pending.digest,'verify')).status,'COMMITTED');
  assert.deepEqual(new Uint8Array(await (await request(dm,'media/primary/actor-a/original')).arrayBuffer()),bytes);
  const result=await (await request(dm,'media/primary/actor-a/thumbnail')).arrayBuffer();
  assert.equal((await inspectMediaPng(new Uint8Array(result))).meta.width,160);
  assert.deepEqual(db.db.prepare('SELECT * FROM cloud_entities WHERE id=?').get('actor-a'),before);
  assert.equal((await submit('first-image','asset-a',hash)).status,'COMMITTED');
  assert.equal(db.db.prepare("SELECT COUNT(*) AS n FROM cloud_links WHERE relation='primary_image'").get().n,1);
  await assert.rejects(request(owner,'media/uploads/first-image','DELETE'),{code:'TRANSACTION_PRECONDITION_FAILED'});
});

test('wrong actor, different digest, missing objects and refusal preserve the prior association',async t=>{
  const {service,db,request,submit}=await fixture(t);
  const first=await fixtureImage(19),next=await fixtureImage(123);
  await assert.rejects(request(dm,'media/uploads/first','PUT',first.bytes,first.hash,'actor','actor-b'),{code:'IMAGE_UNAVAILABLE'});
  await assert.rejects(request(dm,'media/uploads/first','PUT',first.bytes,next.hash),{code:'IMAGE_DIGEST_MISMATCH'});
  await request(dm,'media/uploads/first','PUT',first.bytes,first.hash);
  await request(owner,'media/uploads/first/original');
  const confirmed=await submit('first','first-asset',first.hash);
  await service.review(owner,id,'cmd-first',confirmed.digest,'verify');
  const oldKeys=new Set(service.mediaBucket.keys);
  await request(dm,'media/uploads/replacement','PUT',next.bytes,next.hash);
  await request(owner,'media/uploads/replacement/original');
  const replacement=await submit('replacement','next-asset',next.hash,1);
  await assert.rejects(request(owner,'media/uploads/replacement','DELETE'),{code:'TRANSACTION_PRECONDITION_FAILED'});
  await service.mediaBucket.delete([...service.mediaBucket.keys].filter(key=>!oldKeys.has(key)));
  await assert.rejects(service.review(owner,id,'cmd-replacement',replacement.digest,'verify'),{code:'IMAGE_OBJECT_UNAVAILABLE'});
  const retained=await request(dm,'media/primary/actor-a/original');
  assert.equal(retained.headers.get('x-astravar-content-sha256'),first.hash);
  assert.equal((await service.review(owner,id,'cmd-replacement',replacement.digest,'refuse')).status,'REFUSED');
  assert.equal((await (await request(owner,'media/uploads/replacement','DELETE')).json()).status,'REMOVED');
  assert.equal(db.db.prepare("SELECT COUNT(*) AS n FROM cloud_entities WHERE kind='media_asset'").get().n,1);
});

test('an uploaded candidate and pending image survive missing storage and revoked access without leaking media',async t=>{
  const {service,request,submit}=await fixture(t);
  const {bytes,hash}=await fixtureImage();
  await request(dm,'media/uploads/disabled','PUT',bytes,hash);
  const pending=await submit('disabled','asset-a',hash);
  await service.setGrant(owner,id,'sites-mcp',{scopes,auto:['media.write'],actors:['actor-b'],revoked:false,expectedGrantRevision:1,expiresAt:Date.now()+3600000});
  await assert.rejects(request(dm,'media/uploads/disabled/original'),{code:'IMAGE_UNAVAILABLE'});
  assert.equal((await service.review(owner,id,'cmd-disabled',pending.digest,'verify')).status,'CONFLICT');
  service.mediaBucket=null;
  await assert.rejects(request(owner,'media/uploads/disabled/original'),{code:'PRIVATE_IMAGE_STORAGE_UNAVAILABLE'});
});

test('MCP stages supplied bytes under its existing actor grant and rejects malformed or oversized attachments',async t=>{
  const {service,request}=await fixture(t),{bytes,hash}=await fixtureImage();
  const args={continuityId:id,uploadId:'from-mcp',targetId:'actor-a',targetKind:'actor',sha256:hash,bytesBase64:Buffer.from(bytes).toString('base64')};
  const staged=await stageCloudMcpImage(service,dm,args);
  assert.equal(staged.status,'CANDIDATE_READY');
  assert.equal(staged.sha256,hash);
  assert.deepEqual(new Uint8Array(await (await request(owner,'media/uploads/from-mcp/original')).arrayBuffer()),bytes);
  await assert.rejects(stageCloudMcpImage(service,dm,{...args,bytesBase64:'a'.repeat(49156)}),{code:'INVALID_MEDIA_ENCODING'});
  await assert.rejects(stageCloudMcpImage(service,dm,{...args,bytesBase64:'ai=='}),{code:'INVALID_MEDIA_ENCODING'});
  await assert.rejects(stageCloudMcpImage(service,dm,{...args,targetId:'actor-b'}),{code:'IMAGE_UNAVAILABLE'});
});

test('deleting an owner-selected target and its exclusive image removes reviewed bytes, staged candidates and detailed evidence',async t=>{
  const {service,db,request,submit}=await fixture(t),{bytes,hash}=await fixtureImage();
  await request(dm,'media/uploads/attached','PUT',bytes,hash);
  await request(owner,'media/uploads/attached/original');
  const image=await submit('attached','archival-image',hash);
  await service.review(owner,id,image.commandId,image.digest,'verify');
  await request(dm,'media/uploads/draft-one','PUT',bytes,hash);
  const erase=(cmd,records)=>service.submit(owner,id,{version:1,commandId:cmd,epoch:1,expectedRevision:1,operation:'permanently_delete_records',
    args:{records,confirmation:'PERMANENT_DELETE',acknowledgeExternalBackups:true}});
  const records=[{id:'actor-a',kind:'actor',revision:1},{id:'archival-image',kind:'media_asset',revision:1}];
  await assert.rejects(erase('omit-artwork',records.slice(0,1)),{code:'DEPENDENT_RECORDS_RETAINED'});
  const stale=await erase('delete-both-stale',records);
  await request(dm,'media/uploads/draft-two','PUT',bytes,hash);
  await assert.rejects(service.review(owner,id,stale.commandId,stale.digest,'verify'),{code:'TRANSACTION_PRECONDITION_FAILED'});
  assert.equal((await request(owner,'media/primary/actor-a/original')).status,200);
  await service.review(owner,id,stale.commandId,stale.digest,'refuse');
  const exact=await erase('delete-both-reviewed',records);
  assert.equal((await service.approvals(owner,id)).requests[0].plan.result.media.remove.length,3);
  const retainedBucket=service.mediaBucket;
  service.mediaBucket=null;
  const pending=await service.review(owner,id,exact.commandId,exact.digest,'verify');
  assert.equal(pending.status,'COMMITTED');
  assert.deepEqual(pending.receipt.assetCleanup,{status:'PENDING',assets:3});
  assert.equal(db.db.prepare("SELECT count(*) AS n FROM cloud_entities WHERE id IN ('actor-a','archival-image')").get().n,0);
  service.mediaBucket=retainedBucket;
  const done=await service.receipt(owner,id,exact.commandId);
  assert.deepEqual(done.receipt.assetCleanup,{status:'COMPLETE',assets:3});
  assert.equal(retainedBucket.keys.size,0);
  assert.equal(db.db.prepare('SELECT count(*) AS n FROM cloud_media_uploads').get().n,0);
  assert.equal(db.db.prepare('SELECT count(*) AS n FROM cloud_media_previews').get().n,0);
  assert.equal((await service.receipt(owner,id,exact.commandId)).revision,done.revision);
  await assert.rejects(request(dm,'media/primary/actor-a/original'),{code:'IMAGE_UNAVAILABLE'});
  await assert.rejects(request(owner,'media/uploads/attached','PUT',bytes,hash,'actor','actor-b'),{code:'TRANSACTION_PRECONDITION_FAILED'});
  assert.deepEqual(db.db.prepare('PRAGMA foreign_key_check').all(),[]);
});

test('the registered Worker accepts a scoped MCP image attachment and serves the exact approved bytes through the owner route',async t=>{
  const {db,service}=await fixture(t),{bytes,hash}=await fixtureImage();
  const origin='https://synthetic-ui.example', path='/v3/continuities/'+id;
  const env={DB:db,OWNER_SITE_USER_ID:ownerId,ASTRAVAR_CLOUD_ENABLED:'1',ASTRAVAR_CLOUD_ENV:'isolated',ASTRAVAR_MEDIA:service.mediaBucket};
  const request=(url,method='GET',body=null,authenticated=true)=>handleWorker(new Request(origin+url,{method,
    headers:{...(authenticated?{'oai-authenticated-user-id':ownerId}:{}),
      ...(method==='GET'?{}:{Origin:origin,'Content-Type':'application/json'})},...(body===null?{}:{body:JSON.stringify(body)})}),env);
  const rpc=async(name,args)=>{
    const response=await request('/mcp','POST',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId:id,...args}}});
    const output=await response.json();
    assert.equal(output.result?.isError,false,JSON.stringify(output));
    return output.result.structuredContent;
  };
  const args={targetId:'actor-a',targetKind:'actor',uploadId:'routed-image',sha256:hash};
  assert.equal((await rpc('stage_cloud_image',{...args,bytesBase64:Buffer.from(bytes).toString('base64')})).status,'CANDIDATE_READY');
  const command={version:1,epoch:1,expectedRevision:0,commandId:'registered-media',operation:'assign_primary_image',args:{...args,assetId:'through-worker'}};
  const pending=await rpc('submit_cloud_command',{command});
  assert.equal(pending.status,'PENDING_APPROVAL');
  const target=path+'/media/uploads/routed-image/original';
  assert.equal((await request(target,'GET',null,false)).status,401);
  assert.equal((await request('/owner/ascension/'+id)).status,200);
  const actual=await request(target);
  assert.equal(actual.headers.get('content-type'),'image/png');
  assert.deepEqual(new Uint8Array(await actual.arrayBuffer()),bytes);
  const review=await request(path+'/approvals/'+command.commandId+'/verify','POST',{digest:pending.digest});
  assert.equal((await review.json()).status,'COMMITTED');
  const proof=await rpc('get_cloud_receipt',{commandId:command.commandId});
  assert.equal(proof.receipt.outcome.assetId,'through-worker');
  const shown=await request(path+'/media/primary/actor-a/original');
  assert.equal(shown.headers.get('x-astravar-content-sha256'),hash);
  assert.deepEqual(new Uint8Array(await shown.arrayBuffer()),bytes);
});

test('one uploader writes a reservation and failed partial writes allow a clean exact retry',async t=>{
  const {service,request,db}=await fixture(t),{bytes,hash}=await fixtureImage();
  const bucket=service.mediaBucket,put=bucket.put.bind(bucket);
  let signal,unlock;
  const started=new Promise(resolve=>signal=resolve), gate=new Promise(resolve=>unlock=resolve);
  let writes=0;
  bucket.put=async(key,value,options)=>{writes++;if(writes===1){signal();await gate;}return put(key,value,options);};
  const first=request(dm,'media/uploads/concurrent','PUT',bytes,hash);
  await started;
  try {await assert.rejects(request(dm,'media/uploads/concurrent','PUT',bytes,hash),{code:'IMAGE_UPLOAD_IN_PROGRESS'});}
  finally {unlock();}
  assert.equal((await first).status,200);
  assert.equal(writes,2);
  bucket.put=async(key,value,options)=>{if(key.endsWith('thumbnail.png'))throw Error('private fixture outage');return put(key,value,options);};
  await assert.rejects(request(dm,'media/uploads/try-again','PUT',bytes,hash),/private fixture outage/);
  assert.equal(db.db.prepare("SELECT count(*) AS n FROM cloud_media_uploads WHERE upload_id='try-again'").get().n,0);
  assert.equal(bucket.keys.size,2);
  bucket.put=put;
  assert.equal((await request(dm,'media/uploads/try-again','PUT',bytes,hash)).status,200);
  assert.equal(bucket.keys.size,4);
});

test('reviewed removal checks the selected target, actor grant, digest and revision and preserves a shared asset',async t=>{
  const {service,db,request,submit}=await fixture(t), {bytes,hash}=await fixtureImage();
  await request(dm,'media/uploads/shared','PUT',bytes,hash);
  await request(owner,'media/uploads/shared/original');
  const added=await submit('shared','shared-asset',hash);
  await service.review(owner,id,added.commandId,added.digest,'verify');
  // A recovered pre-existing second owner relationship must survive removing the first.
  db.db.prepare("INSERT INTO cloud_links(owner_id,continuity_id,from_id,relation,to_id) VALUES(?,?,'actor-b','primary_image','shared-asset')").run(ownerId,id);
  const plain=async(principal,target)=>(await request(principal,'media/primary/'+target+'/association')).json();
  const relation=await plain(dm,'actor-a');
  const args={assetId:relation.assetId,targetKind:'actor',targetId:'actor-a',targetRevision:relation.targetRevision,sha256:relation.sha256};
  const remove=(commandId,extra={})=> service.submit(dm,id,{version:1,epoch:1,expectedRevision:1,commandId,operation:'remove_primary_image',args:{...args,...extra}});
  await assert.rejects(plain(dm,'actor-b'),{code:'IMAGE_UNAVAILABLE'});
  await assert.rejects(remove('wrong-actor',{targetId:'actor-b'}),{code:'CLOUD_GRANT_DENIED'});
  await assert.rejects(remove('bad-revision',{targetRevision:args.targetRevision+1}),{code:'IMAGE_ASSOCIATION_CHANGED'});
  await assert.rejects(remove('bad-digest',{sha256:'a'.repeat(64)}),{code:'IMAGE_ASSOCIATION_CHANGED'});
  await assert.rejects(remove('bad-asset',{assetId:'other'}),{code:'IMAGE_ASSOCIATION_CHANGED'});
  const rowsBefore=db.db.prepare("SELECT id,data_json FROM cloud_entities WHERE kind='actor' ORDER BY id").all();
  const denied=await remove('refused-deletion');
  assert.equal(denied.status,'PENDING_APPROVAL');
  assert.equal((await service.review(owner,id,denied.commandId,denied.digest,'refuse')).status,'REFUSED');
  assert.equal((await plain(dm,'actor-a')).assetId,'shared-asset');
  const exact=await remove('remove-first');
  assert.equal((await plain(dm,'actor-a')).assetId,'shared-asset');
  const committed=await service.review(owner,id,exact.commandId,exact.digest,'verify');
  assert.equal(committed.receipt.outcome.previousSha256,hash);
  assert.equal(committed.receipt.outcome.assetId,null);
  assert.deepEqual(await remove('remove-first'),committed);
  assert.deepEqual(await service.receipt(dm,id,exact.commandId),committed);
  assert.equal((await plain(dm,'actor-a')).assetId,null);
  await assert.rejects(request(dm,'media/primary/actor-a/original'),{code:'IMAGE_UNAVAILABLE'});
  assert.equal((await plain(owner,'actor-b')).assetId,'shared-asset');
  assert.deepEqual(new Uint8Array(await (await request(owner,'media/primary/actor-b/original')).arrayBuffer()),bytes);
  assert.deepEqual(db.db.prepare("SELECT id,data_json FROM cloud_entities WHERE kind='actor' ORDER BY id").all(),rowsBefore);
  assert.equal(service.mediaBucket.keys.size,2);
});

test('staging optional artwork from a paid creation receipt cannot replay the acquisition or change its price',async t=>{
  const {db,service}=await fixture(t),{bytes,hash}=await fixtureImage();
  const categories=['character','inventory','economy','media'];
  await service.setGrant(owner,id,'sites-mcp',{scopes:categories.flatMap(k=>[k+'.read',k+'.write']),actors:['*'],
    auto:categories.map(k=>k+'.write'),expectedGrantRevision:1,expiresAt:Date.now()+3600000,revoked:false});
  const env={DB:db,OWNER_SITE_USER_ID:ownerId,ASTRAVAR_CLOUD_ENABLED:'1',ASTRAVAR_CLOUD_ENV:'isolated',ASTRAVAR_MEDIA:service.mediaBucket};
  const origin='https://creation-fixture.example',headers={'oai-authenticated-user-id':ownerId,Origin:origin,'Content-Type':'application/json'};
  const rpc=async(name,args)=>{
    const request=new Request(origin+'/mcp',{method:'POST',headers,body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId:id,...args}}})});
    const wire=await (await handleWorker(request,env)).json();
    if(wire.result.isError)throw Object.assign(new Error('Scoped artwork validation'),{code:JSON.parse(wire.result.content[0].text).error});
    return wire.result.structuredContent;
  };
  const command=(commandId,operation,args,expectedRevision)=>rpc('submit_cloud_command',{command:{commandId,operation,args,expectedRevision,epoch:1,version:1}});
  const coins=gp=>({cp:0,sp:0,ep:0,gp,pp:0});
  await command('define-buyer','define_actor',{actorId:'buyer',name:'Isolated buyer',role:'player',rulesProfile:'legacy_2014',level:3,abilities:{INT:12}},0);
  await command('price-wallet','define_wallet',{walletId:'fixture-wallet',holderId:'buyer',shared:false,title:'Fixture coins',coins:coins(9),source:'Isolated fixture'},1);
  await command('price-type','define_item_definition',{definitionId:'catalog',name:'Instrument',description:'Fixture',category:'tool',rarity:null,stackable:false,wearable:false,handheld:true,attunement:'none',source:'Isolated fixture'},2);
  const paid={walletId:'fixture-wallet',price:coins(4),itemId:'paid-item',definitionId:'catalog',ownerId:'buyer',quantity:1,sellerId:null,source:'Fixture purchase'};
  const receipt=await command('single-payment','purchase_item',paid,3);
  assert.equal(receipt.status,'COMMITTED');
  const args={uploadId:'paid-picture',targetId:'paid-item',targetKind:'item_instance',creationCommandId:'single-payment',bytesBase64:Buffer.from(bytes).toString('base64'),sha256:hash};
  await assert.rejects(rpc('stage_cloud_image',{...args,creationCommandId:'price-type'}),{code:'CREATED_IMAGE_TARGET_UNVERIFIED'});
  await assert.rejects(rpc('stage_cloud_image',{...args,sha256:'0'.repeat(64)}),{code:'IMAGE_DIGEST_MISMATCH'});
  const staged=await rpc('stage_cloud_image',args);
  assert.deepEqual(staged.creation,{commandId:'single-payment',transactionId:receipt.receipt.transactionId});
  assert.deepEqual((await rpc('stage_cloud_image',args)).creation,staged.creation);
  const item=await rpc('get_cloud_state',{id:'paid-item'}),wallet=await rpc('get_cloud_state',{id:'fixture-wallet'});
  assert.equal(item.revision,4);assert.equal(item.records.length,1);assert.equal(item.records[0].data.quantity,1);
  assert.deepEqual(wallet.records[0].data.coins,coins(5));
  assert.equal((await rpc('get_cloud_primary_image',{id:'paid-item'})).assetId,null);
  assert.deepEqual(await command('single-payment','purchase_item',paid,3),receipt);
  assert.equal(db.db.prepare("SELECT count(*) AS n FROM cloud_receipts WHERE command_id='single-payment'").get().n,1);
});


test('8-bit PNG source precision from the Android encoder preserves pixels and obeys channel count and ordering',async()=>{
  const signature=Buffer.from([137,80,78,71,13,10,26,10]);
  function chunk(type,payload) {
    const data=Buffer.from(payload),bytes=Buffer.alloc(data.length+12);
    bytes.writeUInt32BE(data.length);bytes.write(type,4,'ascii');data.copy(bytes,8);
    bytes.writeUInt32BE(crc32(bytes.subarray(4,-4)),8+data.length);
    return bytes;
  }
  function encoded(color,precision,position='before') {
    const header=Buffer.alloc(13);header.writeUInt32BE(1);header.writeUInt32BE(1,4);header[8]=8;header[9]=color;
    const bits=chunk('sBIT',precision);
    const channels=new Map([[0,1],[2,3],[3,1],[4,2],[6,4]]);
    const data=[signature,chunk('IHDR',header)];
    if(position==='before'||position==='duplicate')data.push(bits);
    if(color===3)data.push(chunk('PLTE',[9,18,27]));
    if(position==='after-palette'||position==='duplicate')data.push(bits);
    data.push(chunk('IDAT',deflateSync(Buffer.alloc(1+channels.get(color)))));
    if(position==='after-pixels')data.push(bits);
    return Buffer.concat([...data,chunk('IEND',[])]);
  }
  for(const [color,bits] of [[0,[8]],[2,[8,8,8]],[3,[5,6,5]],[4,[8,4]],[6,[8,8,8,8]]]){
    const {meta,thumbnail}=await inspectMediaPng(encoded(color,bits));
    assert.deepEqual([meta.width,meta.height],[1,1]);
    assert.equal((await inspectMediaPng(thumbnail)).meta.sha256,meta.thumbnailSha256);
  }
  for(const [color,bits,position] of [[2,[8,8]],[0,[0]],[6,[8,8,9,8]],[3,[8,8,8],'after-palette'],
    [2,[8,8,8],'after-pixels'],[0,[8],'duplicate']]){
    await assert.rejects(inspectMediaPng(encoded(color,bits,position)),{code:'INVALID_OR_UNSUPPORTED_PNG'});
  }
});
