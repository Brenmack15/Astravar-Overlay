import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {CloudService} from './cloud-service.mjs';
import {cloudParity} from './fixtures/cloud-parity.mjs';
import {fileBucket} from './fixtures/media-bucket.mjs';
import {encodeMediaPng,mediaDigest} from './cloud-media-codec.mjs';
import {CLOUD_SCOPES} from './cloud-validation.mjs';

test('owner-reviewed resource, delegation, context, artwork and lifecycle produce a shared terminal disposition',async t=>{
  const db=new SqliteAdapter();t.after(()=>db.close());
  db.exec(await readFile(new URL('./schema.sql',import.meta.url),'utf8'));
  const ownerId='review-parity-owner',cid='qa-review-parity',owner={role:'owner',ownerId,credentialId:'sites-owner'};
  const admin=new CloudService(db,{ownerId,isolated:true,kinds:{},operations:{}});
  await admin.createIsolated(owner,cid);
  const categories=CLOUD_SCOPES.filter(cat=>cat!=='campaign');
  const scopes=categories.flatMap(s=>[s+'.read',s+'.write']);
  await admin.setGrant(owner,cid,'sites-mcp',{scopes,actors:['*'],auto:categories.map(s=>s+'.write'),expiresAt:Date.now()+3600000,revoked:false,expectedGrantRevision:0});
  const submit=cloudParity(()=>db,ownerId,cid,await fileBucket(t));
  let revision=0;
  async function run(operation,args,options={}){
    const response=await submit({version:1,commandId:crypto.randomUUID(),expectedRevision:revision,epoch:1,operation,args},options);
    assert.equal(response.status,'COMMITTED');assert.equal(response.revision,++revision);
    return response;
  }
  const settings={version:1,commandId:'owner-settings-command',expectedRevision:revision,epoch:1,operation:'update_continuity_settings',args:{name:'Protected settings',notes:null}};
  await assert.rejects(submit(settings),/OWNER_REQUIRED/);
  const actorId='review-actor',poolId='review-pool',source='isolated table agreement';
  for(const id of [actorId,'disposable'])await run('define_actor',{actorId:id,name:'Synthetic actor',role:'companion',rulesProfile:'legacy_2014',level:1});
  await run('define_resource',{actorId,poolId,name:'Fixture uses',type:'charge',maximum:4,current:null,recovery:'none',source});
  await run('correct_resource_balance',{actorId,poolId,before:null,after:2,source},{review:true});
  await run('set_dice_delegation',{actorId,category:'hit_dice',enabled:true,source},{review:true});
  await run('set_roll_context',{actorId,tag:'workbench',active:true,source},{review:true});
  const pixels=new Uint8Array([42,64,85,255]),bytes=await encodeMediaPng(1,1,pixels),sha256=await mediaDigest(bytes);
  const uploadId='specific-image',assetId='primary-image';
  const inspect=await submit.stageImage(uploadId,actorId,bytes,sha256);
  await run('assign_primary_image',{uploadId,assetId,targetId:actorId,targetKind:'actor',sha256},{review:true,inspect});
  const records=entityRevision=>[{id:'disposable',kind:'actor',revision:entityRevision}];
  await run('archive_records',{records:records(1)},{review:true});
  await run('restore_records',{records:records(2)},{review:true});
  await run('permanently_delete_records',{records:records(3),confirmation:'PERMANENT_DELETE',acknowledgeExternalBackups:true},{review:true});
  assert.equal(db.db.prepare("SELECT count(*) AS n FROM cloud_tombstones WHERE id='disposable'").get().n,1);
});
