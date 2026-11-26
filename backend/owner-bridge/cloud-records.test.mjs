import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { SqliteAdapter } from './sqlite-adapter.mjs';
import { CloudService } from './cloud-service.mjs';
import { cloudRecordView } from './cloud-records-query.mjs';
import { RECORD_KINDS, RECORD_OPERATIONS } from './cloud-records.mjs';

const ddl=await readFile(new URL('./schema.sql',import.meta.url),'utf8');
const owner={role:'owner',credentialId:'sites-owner',ownerId:'test-owner'};
const dm={role:'connector',credentialId:'sites-mcp',ownerId:owner.ownerId};
const continuity='qa-records';
const source='Isolated rules fixture';
const kinds={...RECORD_KINDS,actor:'character',roll:'rolls',form:'character',encounter:'combat'};
const allScopes=[...new Set(Object.values(kinds))].flatMap(s=>[s+'.read',s+'.write']).concat('lifecycle.read');
const amounts=(values={})=>({cp:0,sp:0,ep:0,gp:0,pp:0,...values});
const definition=(id='supply',stackable=true)=>({definitionId:id,name:'Supplies',category:'gear',description:'Carried supplies',
  rarity:null,stackable,wearable:!stackable,handheld:!stackable,attunement:'none',source});
async function fixture(t) {
  const dir=await mkdtemp(join(tmpdir(),'astravar-records-'));
  const path=join(dir,'private.sqlite');
  let db=new SqliteAdapter(path);
  db.exec(ddl);
  const create=()=>new CloudService(db,{ownerId:owner.ownerId,operations:RECORD_OPERATIONS,kinds,isolated:true});
  let service=create();
  t.after(async()=>{db.close();await rm(dir,{recursive:true,force:true});});
  await service.createIsolated(owner,continuity);
  for(const [id,role] of [['hero','player'],['ally','companion']]) {
    await db.prepare("INSERT INTO cloud_entities(owner_id,continuity_id,id,kind,actor_id,title,data_json,life,revision) VALUES(?,?,?,'actor',?,?,?,'active',1)")
      .bind(owner.ownerId,continuity,id,id,id,JSON.stringify({role})).run();
  }
  const grant=async(scopes=allScopes,actors=['*'],write=true)=>{
    const previous=await db.prepare('SELECT revision FROM cloud_grants WHERE owner_id=? AND continuity_id=? AND subject=?')
      .bind(owner.ownerId,continuity,'sites-mcp').first();
    return service.setGrant(owner,continuity,'sites-mcp',{scopes,actors,
      auto:write?scopes.filter(s=>s.endsWith('.write')):[],expiresAt:Date.now()+3600000,revoked:false,expectedGrantRevision:previous?.revision??0});
  };
  await grant();
  const body=async(operation,args)=>({version:1,epoch:1,commandId:crypto.randomUUID(),expectedRevision:(await service.continuity(continuity)).revision,operation,args});
  const submit=(cmd,p=dm)=>service.submit(p,continuity,cmd);
  const run=async(op,args,p=dm)=>(await submit(await body(op,args),p)).receipt.outcome;
  const record=async(id,p=owner)=>(await service.state(p,continuity,{id,activeOnly:false})).records[0];
  return {get db(){return db;},get service(){return service;},body,submit,run,record,grant,reopen(){db.close();db=new SqliteAdapter(path);service=create();}};
}
test('multi-actor coin transfers and atomic purchases conserve exact denominations across retries and reopen',async t=>{
  const f=await fixture(t);
  await f.run('define_wallet',{walletId:'purse-a',title:'Carried coin',holderId:'hero',shared:false,coins:amounts({gp:40,ep:null}),source});
  await f.run('define_wallet',{walletId:'purse-b',title:'Companion purse',holderId:'ally',shared:false,coins:amounts({gp:4}),source});
  const input={fromWalletId:'purse-a',toWalletId:'purse-b',coins:amounts({gp:25}),reason:'Declared transfer',source};
  const transfer=await f.body('transfer_coins',input);
  const original=await f.submit(transfer);
  assert.equal(original.receipt.outcome.wallets.length,2);
  assert.equal((await f.record('purse-a')).data.coins.gp,15);
  assert.equal((await f.record('purse-b')).data.coins.gp,29);
  assert.equal((await f.record('purse-a')).data.coins.ep,null);
  await assert.rejects(f.run('transfer_coins',{...input,coins:amounts({pp:1})}),/INSUFFICIENT_DENOMINATION/);
  await assert.rejects(f.run('transfer_coins',{...input,coins:amounts({ep:1})}),/UNKNOWN_DENOMINATION/);
  await f.run('define_item_definition',definition());
  const inputBuy={itemId:'sack',definitionId:'supply',ownerId:'hero',quantity:10,source,walletId:'purse-a',price:amounts({gp:6}),sellerId:null};
  const buy=await f.body('purchase_item',inputBuy);
  const bought=await f.submit(buy);
  assert.equal(bought.status,'COMMITTED');assert.equal((await f.record('sack')).data.quantity,10);
  const receipt=bought.receipt;
  f.reopen();
  assert.equal((await f.submit(buy)).receipt.transactionId,receipt.transactionId);
  assert.equal((await f.submit(transfer)).receipt.transactionId,original.receipt.transactionId);
  assert.equal((await f.record('purse-a')).data.coins.gp,9);
  assert.equal((await f.record(receipt.outcome.itemEventId)).data.type,'purchase');
  const edge=await f.db.prepare("SELECT to_id FROM cloud_links WHERE from_id=? AND relation='payment'").bind(receipt.outcome.itemEventId).first();
  assert.equal(edge.to_id,receipt.outcome.transactionRecordId);
  const revision=(await f.service.continuity(continuity)).revision;
  await assert.rejects(f.run('purchase_item',{...inputBuy,itemId:'sack',price:amounts({gp:3})}),/RECORD_ID_ALREADY_IN_USE/);
  assert.equal((await f.service.continuity(continuity)).revision,revision);
  assert.equal((await f.record('purse-a')).data.coins.gp,9);
});
test('partial external departures, merge, shop sale and legitimate recovery keep the old identity',async t=>{
  const f=await fixture(t);
  await f.run('define_item_definition',definition());
  await f.run('define_wallet',{walletId:'purse',title:'Coin',holderId:'hero',shared:false,coins:amounts(),source});
  await f.run('acquire_item',{itemId:'rations',definitionId:'supply',ownerId:'hero',quantity:10,source});
  await f.run('split_stack',{itemId:'rations',splitId:'smaller',quantity:4,source});
  const merged=await f.run('merge_stacks',{keepId:'rations',mergeId:'smaller',source});
  assert.equal(merged.quantity,10);
  assert.equal((await f.record('smaller')).data.holding,'merged');
  const sale=await f.run('sell_item',{itemId:'rations',quantity:2,splitId:'sold',walletId:'purse',proceeds:amounts({cp:7}),buyerId:null,buyerName:'Traveling buyer',source});
  assert.equal(sale.quantity,2);assert.equal((await f.record('rations')).data.quantity,8);
  assert.equal((await f.record('sold')).data.partyAccess,false);
  assert.equal((await f.record('sold')).life,'archived');
  assert.equal((await f.record('purse')).data.coins.cp,7);
  await assert.rejects(f.run('split_stack',{itemId:'sold',splitId:'again',quantity:1,source}),/RECORD_UNAVAILABLE/);
  await f.run('recover_item',{itemId:'sold',ownerId:'ally',source,reason:'Confirmed reacquisition'});
  assert.equal((await f.record('sold')).actorId,'ally');
  assert.equal((await f.record('sold')).data.quantity,2);
  assert.equal((await f.record('purse')).data.coins.cp,7);
});
test('wrong actor or category blocks both wallet legs and the acquired holding',async t=>{
  const f=await fixture(t);
  await f.run('define_item_definition',definition());
  for(const id of ['hero','ally']) await f.run('define_wallet',{walletId:id+'-purse',title:'Coins',holderId:id,shared:false,coins:amounts({gp:8}),source});
  const input={fromWalletId:'hero-purse',toWalletId:'ally-purse',coins:amounts({gp:3}),reason:'Declared transfer',source};
  await f.grant(['character.read','economy.read','economy.write'],['hero']);
  await assert.rejects(f.run('transfer_coins',input),/CLOUD_GRANT_DENIED/);
  await f.grant(['character.read','economy.read','economy.write']);
  await assert.rejects(f.run('purchase_item',{itemId:'no-item',definitionId:'supply',ownerId:'hero',quantity:1,walletId:'hero-purse',price:amounts({gp:3}),sellerId:null,source}),/CLOUD_GRANT_DENIED/);
  assert.equal((await f.record('hero-purse')).data.coins.gp,8);
  assert.equal((await f.record('ally-purse')).data.coins.gp,8);
  assert.equal(await f.record('no-item'),undefined);
});
test('a failed commit rolls back the real SQL item, accounting, history and request, then the same request succeeds',async t=>{
  const f=await fixture(t);
  await f.run('define_item_definition',definition());
  await f.run('define_wallet',{walletId:'purse',title:'Wallet',holderId:'hero',shared:false,coins:amounts({gp:9}),source});
  const command=await f.body('purchase_item',{itemId:'bolts',definitionId:'supply',ownerId:'hero',quantity:5,walletId:'purse',price:amounts({gp:5}),sellerId:null,source});
  f.db.exec("CREATE TRIGGER fixture_rollback BEFORE INSERT ON cloud_receipts BEGIN SELECT RAISE(ABORT,'CLOUD_TEST_ABORT'); END;");
  await assert.rejects(f.submit(command),/TRANSACTION_PRECONDITION_FAILED/);
  assert.equal((await f.record('purse')).data.coins.gp,9);assert.equal(await f.record('bolts'),undefined);
  assert.equal((await f.db.prepare("SELECT count(*) AS n FROM cloud_entities WHERE kind IN ('item_event','coin_transaction')").bind().first()).n,0);
  f.db.exec("DROP TRIGGER fixture_rollback");
  assert.equal((await f.submit(command)).status,'COMMITTED');
  assert.equal((await f.record('purse')).data.coins.gp,4);
});

test('party projections count actual compatible stock and unknown wallets, timeline deduplicates purchase under each subject',async t=>{
  const f=await fixture(t),view=(name,opts={},p=dm)=>cloudRecordView(f.service,p,continuity,name,opts);
  await f.run('define_item_definition',definition());
  await f.run('define_wallet',{walletId:'purse',title:'Unknown electrum',holderId:'hero',shared:false,coins:amounts({gp:30,ep:null}),source});
  await f.run('purchase_item',{itemId:'a',definitionId:'supply',ownerId:'hero',quantity:5,source,walletId:'purse',price:amounts({gp:2}),sellerId:null});
  await f.run('acquire_item',{itemId:'b',definitionId:'supply',ownerId:'ally',quantity:5,source});
  const party=await view('inventory');assert.equal(party.groups.length,1);assert.equal(party.groups[0].knownQuantity,10);
  const distribution=await view('inventory',{id:'supply'});
  assert.deepEqual(distribution.items.map(v=>v.custodianId),['hero','ally']);
  const totals=(await view('wallets')).totals;
  assert.equal(totals.knownCopper,'2800');assert.equal(totals.knownGp,'28.00');assert.equal(totals.complete,false);
  assert.equal(totals.unknownDenominations.ep,1);assert.equal(totals.missingWalletCount,1);
  assert.equal((await view('timeline')).events.length,2);
  assert.equal((await view('timeline',{id:'purse'})).events[0].data.type,'purchase');
  assert.equal((await view('timeline',{id:'hero'})).events[0].data.type,'purchase');
  const first=await view('timeline',{limit:1});
  const second=await view('timeline',{limit:1,after:first.nextAfter,atEpoch:first.epoch,atRevision:first.revision,grantRevision:first.grantRevision});
  assert.notEqual(first.events[0].id,second.events[0].id);
  await assert.rejects(view('timeline',{after:first.nextAfter}),/PAGE_BASELINE_REQUIRED/);
  await f.run('split_stack',{itemId:'a',splitId:'a-child',quantity:2,source});
  const ancestry=await view('item_history',{id:'a-child'});
  assert.deepEqual(ancestry.events.map(e=>e.data.type),['split','purchase']);
  await assert.rejects(view('timeline',{after:first.nextAfter,atEpoch:first.epoch,atRevision:first.revision,grantRevision:first.grantRevision}),/SNAPSHOT_CHANGED/);
  await f.grant(['economy.read','character.read'],['hero']);
  await assert.rejects(view('wallets'),/PARTY_AGGREGATE_SCOPE_REQUIRED/);
  await assert.rejects(view('item_history',{id:'a'}),/CLOUD_GRANT_DENIED/);
});
test('a new conversation retrieves the unresolved checkpoint and scoped source links without rolling dice',async t=>{
  const f=await fixture(t);
  await f.run('define_connection',{entityId:'camp',name:'Resting place',category:'place',partyStorage:true,actorId:null,
    knownDescription:'A place known to the party.',source});
  await f.run('record_chronicle_event',{eventId:'arrival',title:'Arrived at camp',narrative:'The party reached camp.',
    category:'travel',status:'confirmed',worldTime:null,subjects:[{kind:'world_entity',id:'camp'},{kind:'actor',id:'hero'}],correctsId:null,source});
  await f.run('define_objective',{objectiveId:'map',title:'Map the road',actorId:'hero',category:'personal',status:'active',
    knownRequirements:['Reach the camp'],linkedProjectId:null,source});
  await f.run('record_objective_milestone',{objectiveId:'map',eventId:'arrival',status:'active',source});
  const cp=await f.run('save_checkpoint',{checkpointId:'checkpoint-one',title:'At camp',scene:'At the camp entrance.',
    locationId:'camp',worldTime:null,lastEventId:'arrival',unresolved:'Choose a path.',pendingRollId:null,pendingRollPrompt:'A player check is outstanding.',
    encounterId:null,objectiveIds:['map'],actorIds:['hero'],source});
  f.reopen();
  const {cloudContext,cloudRelationships}=await import('./cloud-query.mjs');
  const state=await cloudContext(f.service,dm,continuity);
  const associations=await cloudRelationships(f.service,dm,continuity,cp.checkpointId);
  assert.equal(state.checkpoint.id,'checkpoint-one');assert.equal(state.checkpoint.data.worldTime,null);
  assert.equal(state.checkpoint.data.unresolved,'Choose a path.');
  assert.equal(associations.links.length,4);
  assert.equal((await f.service.state(dm,continuity,{kind:'roll'})).records.length,0);
  await f.run('record_chronicle_event',{eventId:'clarification',title:'Clarified arrival',narrative:'The group reached the east entrance.',
    category:'travel',status:'confirmed',worldTime:null,subjects:[{kind:'world_entity',id:'camp'}],correctsId:'arrival',source});
  assert.equal((await cloudContext(f.service,dm,continuity)).recentChanges[0].entity_id,'clarification');
});
test('actual material quantity, decided stage, blueprint history and linked output survive restart',async t=>{
  const f=await fixture(t);
  await f.run('define_item_definition',definition());
  await f.run('define_item_definition',definition('frame',false));
  await f.run('acquire_item',{itemId:'wood',definitionId:'supply',ownerId:'hero',quantity:5,source});
  await f.run('acquire_item',{itemId:'fixture-frame',definitionId:'frame',ownerId:'hero',quantity:1,source});
  await f.run('record_research',{researchId:'notes',title:'Joint',actorId:'hero',finding:'The fixture takes the prepared piece.',
    evidence:'confirmed',itemId:'fixture-frame',rollId:null,source});
  await f.run('propose_invention',{projectId:'build',title:'Fixture assembly',actorId:'hero',intent:'Join two pieces.',
    requirements:['Use reviewed fit'],researchIds:['notes'],source});
  await f.run('record_blueprint_version',{blueprintId:'draw',projectId:'build',title:'Drawing',specification:'A reviewed assembly.',supersedesId:null,source});
  await f.run('define_construction_stage',{stageId:'fit',projectId:'build',blueprintId:'draw',title:'Fit',prerequisites:[],requiredWork:'One fixture session.',checkRequired:false,source});
  const make={eventId:'integration',projectId:'build',stageId:'fit',donorItemId:'wood',outputItemId:'fixture-frame',method:'material_consumed',
    quantity:2,evidence:'Confirmed material use in the fixture.',source};
  await assert.rejects(f.run('integrate_component',make),/CONFIRMED_STAGE_REQUIRED/);
  await f.run('resolve_construction_stage',{stageId:'fit',outcome:'partial',rollId:null,worldTimeSpent:null,evidence:'Incomplete fitting.',source});
  await f.run('resolve_construction_stage',{stageId:'fit',outcome:'completed',rollId:null,worldTimeSpent:'One session',evidence:'Confirmed fitted.',source});
  const cmd=await f.body('integrate_component',make);
  const applied=await f.submit(cmd);assert.equal(applied.receipt.outcome.remaining,3);
  await f.run('link_completed_invention',{projectId:'build',outputItemId:'fixture-frame',stageIds:['fit'],source});
  f.reopen();
  assert.equal((await f.submit(cmd)).receipt.transactionId,applied.receipt.transactionId);
  assert.equal((await f.record('wood')).data.quantity,3);assert.equal((await f.record('build')).data.outputId,'fixture-frame');
  assert.equal((await cloudRecordView(f.service,dm,continuity,'genealogy',{id:'wood'})).events[0].data.definitionId,'supply');
  assert.equal((await cloudRecordView(f.service,dm,continuity,'genealogy',{id:'fixture-frame'})).events[0].data.beforeQuantity,5);
});
