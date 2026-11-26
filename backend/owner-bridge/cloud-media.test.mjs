import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
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

// Test the storage API with independently readable disk files, including a
// missing-object failure between the review preview and its actual decision.
async function fileBucket(t) {
  const dir=await mkdtemp(join(tmpdir(),'astravar-media-'));
  t.after(()=>rm(dir,{force:true,recursive:true}));
  const path=key=>join(dir,key.replaceAll('/','_'));
  return {
    keys:new Set(),
    async put(key,bytes,options){
      assert.equal(await mediaDigest(bytes),options.sha256);
      await writeFile(path(key),bytes);
      this.keys.add(key);
    },
    async get(key){
      try {const bytes=await readFile(path(key)); return {body:new Blob([bytes]).stream(),size:bytes.length};}
      catch(error){if(error.code==='ENOENT')return null;throw error;}
    },
    async head(key){try{return {size:(await stat(path(key))).size};}catch(error){if(error.code==='ENOENT')return null;throw error;}},
    async delete(keys){for(const key of keys){await rm(path(key),{force:true});this.keys.delete(key);}},
  };
}
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
