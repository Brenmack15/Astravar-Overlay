import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {CloudService} from './cloud-service.mjs';
import {cloudParity} from './fixtures/cloud-parity.mjs';
import {RECORD_KINDS, RECORD_OPERATIONS} from './cloud-records.mjs';
import {SqliteAdapter} from './sqlite-adapter.mjs';

test('withdraw and return an original physical holding through its reviewed integrated container',async t=>{
  const ownerId='fixture-owner', continuity='qa-storage';
  const owner={ownerId,role:'owner',credentialId:'sites-owner'};
  const db=new SqliteAdapter(); t.after(()=>db.close());
  db.exec(await readFile(new URL('./schema.sql',import.meta.url),'utf8'));
  const service=new CloudService(db,{ownerId,isolated:true,kinds:{...RECORD_KINDS,actor:'character'},operations:RECORD_OPERATIONS});
  await service.createIsolated(owner,continuity);
  const scopes=['inventory','character','crafting','lifecycle'].flatMap(s=>[s+'.read',s+'.write']);
  await service.setGrant(owner,continuity,'sites-mcp',{scopes,auto:scopes.filter(s=>s.endsWith('.write')),actors:['*'],revoked:false,expiresAt:Date.now()+3600000,expectedGrantRevision:0});
  function seed(id,kind,actorId,data){
    db.db.prepare("INSERT INTO cloud_entities(owner_id,continuity_id,id,kind,actor_id,title,data_json,life,revision) VALUES(?,?,?,?,?,?,?,'active',1)")
      .run(ownerId,continuity,id,kind,actorId,id,JSON.stringify(data));
  }
  const data=id=>JSON.parse(db.db.prepare('SELECT data_json FROM cloud_entities WHERE id=?').get(id).data_json);
  const count=()=>db.db.prepare("SELECT count(*) AS n FROM cloud_entities WHERE kind='item_instance'").get().n;
  const edge=()=>Boolean(db.db.prepare("SELECT 1 FROM cloud_links WHERE from_id='spare-prism' AND relation='stored_in' AND to_id='jacket'").get());
  const source='Isolated fixture';
  for(const [id,role] of [['wearer','player'],['ally','companion']]) seed(id,'actor',id,{role});
  seed('module','invention_project',null,{status:'completed'});
  seed('material','item_definition',null,{attunement:'none',handheld:true,wearable:false,stackable:false});
  const basic={definitionId:'material',ownerId:'wearer',custodianId:'wearer',quantity:1,partyAccess:true,locationId:null,equipment:'packed',attuned:false,operating:false,holding:'carried'};
  seed('jacket','item_instance','wearer',{...basic,equipment:'worn',operating:true,integratedStorageProjectId:'module',integratedStorageOperatorId:'wearer'});
  seed('spare-prism','item_instance','wearer',{...basic,ownerId:'ally',holding:'storage',containerItemId:'jacket'});
  seed('new-prism','item_instance','wearer',basic);
  seed('grown-stack','item_instance','wearer',{...basic,quantity:3,approvedStorageReturnId:'jacket',approvedStorageReturnQuantity:2});
  seed('folded-space','item_instance','wearer',{...basic,extradimensional:true,approvedStorageReturnId:'jacket',approvedStorageReturnQuantity:1});
  db.db.prepare('INSERT INTO cloud_links(owner_id,continuity_id,from_id,relation,to_id) VALUES(?,?,?,?,?)').run(ownerId,continuity,'spare-prism','stored_in','jacket');
  const body=async(operation,args)=>({version:1,epoch:1,commandId:crypto.randomUUID(),expectedRevision:(await service.continuity(continuity)).revision,operation,args});
  const submit=cloudParity(()=>db,ownerId,continuity);
  const run=async(operation,args)=>submit(await body(operation,args));
  const item={actorId:'wearer',itemId:'spare-prism',containerItemId:'jacket',source};
  const withdraw='withdraw_from_integrated_storage', store='store_in_integrated_storage';
  await assert.rejects(run(withdraw,{...item,actorId:'ally'}),/INTEGRATED_STORAGE_UNAVAILABLE/);
  await assert.rejects(run('equip_item',{itemId:'spare-prism',equipment:'held',source}),/WITHDRAW_STORED_ITEM_FIRST/);
  await assert.rejects(run('transfer_item',{itemId:'jacket',quantity:1,splitId:null,source,reason:'Change holder',destination:{type:'party_actor',actorId:'ally',locationId:null,recipientId:null,recipientName:null}}),/INTEGRATED_STORAGE_HAS_CONTENTS/);
  assert.equal(edge(),true);
  const command=await body(withdraw,item);
  const first=await submit(command);
  assert.equal(first.status,'COMMITTED');
  assert.deepEqual((await submit(command)).receipt,first.receipt);
  const carried=data('spare-prism');
  assert.deepEqual([carried.holding,carried.equipment,carried.ownerId,carried.custodianId,carried.containerItemId,edge()],['carried','packed','ally','wearer',undefined,false]);
  await assert.rejects(run(withdraw,item),/ITEM_NOT_IN_INTEGRATED_STORAGE/);
  for(const itemId of ['new-prism','grown-stack','folded-space'])
    await assert.rejects(run(store,{...item,itemId}),/INTEGRATED_STORAGE_PLACEMENT_UNCONFIRMED/);
  await assert.rejects(run(store,{...item,itemId:'jacket'}),/INTEGRATED_STORAGE_CYCLE/);
  await run('equip_item',{itemId:'spare-prism',equipment:'held',source});
  await assert.rejects(run(store,item),/INTEGRATED_STORAGE_ITEM_INELIGIBLE/);
  await run('equip_item',{itemId:'spare-prism',equipment:'packed',source});
  assert.equal((await run(store,item)).status,'COMMITTED');
  const stored=data('spare-prism');
  assert.deepEqual([stored.containerItemId,stored.holding,stored.equipment,stored.quantity,stored.ownerId,stored.custodianId,edge(),count()],['jacket','storage','packed',1,'ally','wearer',true,5]);
});
