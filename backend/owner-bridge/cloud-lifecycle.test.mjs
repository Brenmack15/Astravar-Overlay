import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { SqliteAdapter } from './sqlite-adapter.mjs';
import { CloudService } from './cloud-service.mjs';
import { CLOUD_LIFECYCLE_OPERATIONS, cloudArchiveView } from './cloud-lifecycle.mjs';
import { cloudObject, CLOUD_ID } from './cloud-validation.mjs';

const schema=readFileSync(new URL('./schema.sql',import.meta.url),'utf8');
const ownerId='synthetic-lifecycle-owner',id='qa-lifecycle';
const owner={ownerId,role:'owner',credentialId:'sites-owner'},dm={ownerId,role:'connector',credentialId:'sites-mcp'};
const privateText='Isolated synthetic text Ω 780043';
const writeNote={
  scope:'world',schema:cloudObject({id:CLOUD_ID,title:{type:'string',minLength:1,maxLength:80}}),
  async plan(ctx,args){const old=await ctx.get('world_entity',args.id);
    if(old?.life==='archived')throw new Error('fixture requires active note');
    await ctx.put({id:args.id,kind:'world_entity',actorId:null,title:args.title,data:{narrative:args.title}});return {id:args.id};},
};
async function lab(t){
  const db=new SqliteAdapter();t.after(()=>db.close());db.exec(schema);
  const service=new CloudService(db,{ownerId,isolated:true,kinds:{world_entity:'world',actor:'character'},
    operations:{...CLOUD_LIFECYCLE_OPERATIONS,record_note:writeNote}});
  await service.createIsolated(owner,id);
  const scopes=['lifecycle.read','lifecycle.write','world.read','world.write'];
  await service.setGrant(owner,id,'sites-mcp',{scopes,auto:['world.write','lifecycle.write'],actors:['*'],revoked:false,
    expectedGrantRevision:0,expiresAt:Date.now()+3600000});
  const submit=(commandId,operation,args,revision,as=dm)=>service.submit(as,id,{version:1,commandId,epoch:1,expectedRevision:revision,operation,args});
  const note=(commandId,record,title,revision)=>submit(commandId,'record_note',{id:record,title},revision);
  const choose=(key,revision,kind='world_entity')=>({id:key,revision,kind});
  const del=(records,extras={})=>({records,confirmation:'PERMANENT_DELETE',acknowledgeExternalBackups:true,...extras});
  const approve=p=>service.review(owner,id,p.commandId,p.digest,'verify');
  return {db,service,submit,note,choose,del,approve};
}

test('exact owner review archives and restores identity and gameplay state, then permanently removes normal and detailed evidence',async t=>{
  const {db,service,submit,note,choose,del,approve}=await lab(t);
  assert.equal((await note('create-one','record-a',privateText,0)).revision,1);
  await note('create-two','record-b','unrelated fixture',1);
  const arch=await submit('archive-one','archive_records',{records:[choose('record-a',1)]},2);
  assert.equal(arch.status,'PENDING_APPROVAL');
  await assert.rejects(service.review(dm,id,arch.commandId,arch.digest,'verify'),{code:'OWNER_REQUIRED'});
  assert.equal((await service.approvals(owner,id)).requests[0].plan.changes[0].before.data.narrative,privateText);
  assert.equal((await approve(arch)).status,'COMMITTED');
  assert.equal((await service.state(dm,id,{id:'record-a'})).records.length,0);
  assert.equal((await cloudArchiveView(service,dm,id,{query:'Ω',kind:'world_entity'})).records[0].id,'record-a');
  const restore=await submit('restore-one','restore_records',{records:[choose('record-a',2)]},3);
  await approve(restore);
  const restored=(await service.state(dm,id,{id:'record-a'})).records[0];
  assert.deepEqual([restored.id,restored.revision,restored.data.narrative],['record-a',3,privateText]);
  await assert.rejects(submit('stale-one','permanently_delete_records',del([choose('record-a',2)]),4),{code:'LIFECYCLE_SELECTION_CHANGED'});
  const deletion=await submit('delete-one','permanently_delete_records',del([choose('record-a',3)]),4);
  assert.equal((await service.approvals(owner,id)).requests[0].plan.result.externalBackupsMayRetainData,true);
  const done=await approve(deletion);assert.equal(done.status,'COMMITTED');
  assert.deepEqual(done.receipt.deletedIds,['record-a']);assert.equal(done.receipt.evidenceErased,true);
  assert.equal((await service.receipt(dm,id,'create-one')).receipt.evidenceErased,true);
  assert.equal((await service.receipt(dm,id,'create-two')).receipt.changes[0].after.title,'unrelated fixture');
  assert.deepEqual(await approve(deletion),done);
  assert.equal(db.db.prepare("SELECT count(*) AS n FROM cloud_entities WHERE id='record-a'").get().n,0);
  assert.equal(db.db.prepare("SELECT count(*) AS n FROM cloud_changes WHERE entity_id='record-a' AND change_type='delete'").get().n,1);
  for(const [table,column] of [['cloud_requests','request_json'],['cloud_receipts','result_json'],['cloud_evidence_segments','payload_json']]){
    assert.equal(db.db.prepare('SELECT count(*) AS n FROM '+table+' WHERE instr('+column+',?)>0').get(privateText).n,0);
  }
  assert.ok(db.db.prepare("SELECT count(*) AS n FROM cloud_evidence_erased WHERE entity_id='record-a'").get().n>0);
  await assert.rejects(note('recreate-one','record-a','recovery attempt',5),{code:'TRANSACTION_PRECONDITION_FAILED'});
  assert.equal((await service.continuity(id)).revision,5);
  assert.deepEqual(db.db.prepare('PRAGMA foreign_key_check').all(),[]);
});

test('dependent records, cross-scope references, pending overlaps and stale selections prevent unsafe deletion',async t=>{
  const {db,service,submit,note,choose,del,approve}=await lab(t);
  await note('test-anchor','anchor-a','Anchor',0);
  await note('test-child','child-b','Dependent',1);
  db.db.prepare('INSERT INTO cloud_links(owner_id,continuity_id,from_id,relation,to_id) VALUES(?,?,?,?,?)').run(ownerId,id,'child-b','source','anchor-a');
  await assert.rejects(submit('delete-anchor','permanently_delete_records',del([choose('anchor-a',1)]),2),{code:'DEPENDENT_RECORDS_RETAINED'});
  await assert.rejects(submit('duplicate-target','permanently_delete_records',del([choose('child-b',1),choose('child-b',1)]),2),{code:'DUPLICATE_SELECTION'});
  const first=await submit('delete-both','permanently_delete_records',del([choose('anchor-a',1),choose('child-b',1)]),2);
  const later=await submit('archive-later','archive_records',{records:[choose('anchor-a',1)]},2);
  await assert.rejects(approve(first),{code:'TRANSACTION_PRECONDITION_FAILED'});
  assert.equal((await service.receipt(owner,id,'delete-both')).status,'PENDING_APPROVAL');
  assert.equal(db.db.prepare('SELECT count(*) AS n FROM cloud_tombstones').get().n,0);
  assert.equal((await service.review(owner,id,later.commandId,later.digest,'refuse')).status,'REFUSED');
  assert.equal((await approve(first)).status,'COMMITTED');
  assert.equal(db.db.prepare('SELECT count(*) AS n FROM cloud_links').get().n,0);
});

test('pre-segmented private receipt needs acknowledged retention and becomes inaccessible through normal receipt reads',async t=>{
  const {db,service,submit,note,choose,del,approve}=await lab(t);
  await note('original-note','legacy-note','Legacy annotation',0);
  const access=JSON.stringify([{scope:'world',actorId:null,write:true}]);
  const old=JSON.stringify({command:{operation:'record_note',args:{id:'legacy-note',title:privateText},epoch:1,expectedRevision:0}});
  const content=JSON.stringify({outcome:{id:'legacy-note',narrative:privateText}});
  db.exec('BEGIN');
  db.db.prepare('INSERT INTO cloud_requests(owner_id,continuity_id,command_id,subject,digest,request_json,access_json,status,submitted_at) VALUES(?,?,?,?,?,?,?,?,?)')
    .run(ownerId,id,'old-writer','sites-mcp','0'.repeat(64),old,access,'COMMITTED',1);
  db.db.prepare('INSERT INTO cloud_receipts(owner_id,continuity_id,command_id,transaction_id,status,epoch,revision,result_json,access_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)')
    .run(ownerId,id,'old-writer','old-transaction','COMMITTED',1,1,content,access,1);
  db.exec('COMMIT');
  assert.equal((await service.receipt(dm,id,'old-writer')).receipt.outcome.narrative,privateText);
  await assert.rejects(submit('delete-legacy','permanently_delete_records',del([choose('legacy-note',1)]),1),{code:'LEGACY_IMMUTABLE_RECEIPTS_REQUIRE_REVIEW'});
  const deletion=await submit('confirmed-delete','permanently_delete_records',del([choose('legacy-note',1)],{acknowledgeLegacyReceipts:true}),1);
  assert.equal((await service.approvals(owner,id)).requests[0].plan.result.legacyImmutableReceipts,1);
  await approve(deletion);
  const oldReceipt=await service.receipt(dm,id,'old-writer');
  assert.equal(oldReceipt.receipt.legacyRetention,true);
  assert.equal(JSON.stringify(oldReceipt).includes(privateText),false);
  assert.equal(db.db.prepare("SELECT result_json FROM cloud_receipts WHERE command_id='old-writer'").get().result_json,content);
});
