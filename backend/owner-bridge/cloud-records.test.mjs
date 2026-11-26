import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { SqliteAdapter } from './sqlite-adapter.mjs';
import { CloudService } from './cloud-service.mjs';
import { cloudRecordView } from './cloud-records-query.mjs';
import { RECORD_KINDS, RECORD_OPERATIONS } from './cloud-records.mjs';
import { MECHANIC_KINDS, MECHANIC_OPERATIONS, mechanicActorSheet } from './cloud-mechanics.mjs';
import { cloudParity } from './fixtures/cloud-parity.mjs';
import { CloudPlan } from './cloud-plan.mjs';
import { cloudActions } from './cloud-actions-view.mjs';

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
async function fixture(t,withMechanics=false) {
  const dir=await mkdtemp(join(tmpdir(),'astravar-records-'));
  const path=join(dir,'private.sqlite');
  let db=new SqliteAdapter(path);
  db.exec(ddl);
  const create=()=>new CloudService(db,{ownerId:owner.ownerId,
    operations:withMechanics?{...MECHANIC_OPERATIONS,...RECORD_OPERATIONS}:RECORD_OPERATIONS,
    kinds:withMechanics?{...MECHANIC_KINDS,...kinds}:kinds,isolated:true});
  let service=create();
  t.after(async()=>{db.close();await rm(dir,{recursive:true,force:true});});
  await service.createIsolated(owner,continuity);
  const party=[['hero','player'],['ally','companion']];
  for(const [id,role] of withMechanics?[]:party) {
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
  const paritySubmit=cloudParity(()=>db,owner.ownerId,continuity);
  const submit=(cmd,p=dm)=>p===dm?paritySubmit(cmd):service.submit(p,continuity,cmd);
  const run=async(op,args,p=dm)=>(await submit(await body(op,args),p)).receipt.outcome;
  const record=async(id,p=owner)=>(await service.state(p,continuity,{id,activeOnly:false})).records[0];
  if(withMechanics){
    await grant([...new Set([...allScopes,'resources.read','resources.write'])]);
    for(const [actorId,role] of party)await run('define_actor',{actorId,role,name:actorId,rulesProfile:'revised_2024',level:5,
      abilities:{STR:10,DEX:10,CON:10,INT:10,WIS:10,CHA:10},temporaryHp:0,gameSeconds:0,source});
  }
  return {get db(){return db;},get service(){return service;},body,submit,run,record,grant,reopen(){db.close();db=new SqliteAdapter(path);service=create();}};
}
test('a transferred item carries only its granted rules and actual remaining charges across custody, storage and retrieval',async t=>{
  const f=await fixture(t,true);
  const projection=async actorId=>mechanicActorSheet(new CloudPlan(f.service,f.service.key(continuity),
    await f.service.grantForSubject(continuity,'sites-mcp'),{commandId:'read'},Date.now()),actorId);
  const move=(type,actorId)=>f.run('transfer_item',{itemId:'lens',quantity:1,splitId:null,source,reason:'Confirmed movement',
    destination:{type,actorId,recipientName:null,recipientId:null,locationId:null}});
  await f.run('define_item_definition',definition('lens-spec',false));
  await f.run('acquire_item',{itemId:'lens',definitionId:'lens-spec',ownerId:'hero',quantity:1,source});
  await f.run('define_resource',{actorId:'hero',poolId:'charges',name:'Lens charge',type:'charge',maximum:5,current:3,recovery:'dawn',source});
  await f.run('bind_item_charge_pool',{itemId:'lens',poolId:'charges',source});
  const grant={actorId:'hero',sourceItemId:'lens',transfersWithItem:true,source};
  await f.run('define_effect',{...grant,effectId:'lens-int',name:'Focus',target:'ability_score',key:'INT',operation:'add',amount:1});
  await f.run('define_action',{...grant,actionId:'lens-pulse',name:'Calibrate',type:'use',timing:'other',cost:{poolId:'charges',amount:1}});
  await f.run('define_attack',{...grant,attackId:'lens-ray',name:'Light',timing:'action',delivery:'ranged',
    resolution:'attack',fixedHit:2,components:[]});
  await f.run('define_effect',{...grant,transfersWithItem:false,effectId:'personal-bonus',name:'Trained handling',
    target:'ability_score',key:'DEX',operation:'add',amount:1});
  await f.run('equip_item',{itemId:'lens',equipment:'held',source});
  await f.run('use_action',{actorId:'hero',actionId:'lens-pulse',useId:'pulse-one',declared:true});
  assert.equal((await f.record('charges')).data.current,2);
  assert.equal((await projection('hero')).abilities.INT.effective,11);
  await move('party_actor','ally');
  assert.equal((await projection('hero')).abilities.INT.effective,10);
  assert.equal((await f.record('hero')).data.poolIds.length,0);
  assert.equal((await projection('ally')).abilities.INT.effective,10);
  await f.run('equip_item',{itemId:'lens',equipment:'held',source});
  assert.equal((await projection('ally')).abilities.INT.effective,11);
  assert.equal((await projection('ally')).abilities.DEX.effective,10);
  assert.equal((await f.record('personal-bonus')).actorId,'hero');
  assert.equal((await f.record('pulse-one')).actorId,'hero');
  assert.deepEqual((await cloudActions(f.service,dm,continuity,'ally')).rows.map(row=>row.id),['lens-pulse','lens-ray']);
  assert.equal((await f.record('charges')).actorId,'ally');
  assert.equal((await f.record('charges')).data.current,2);
  await move('unassigned',null);
  assert.equal((await f.record('charges')).actorId,null);
  assert.deepEqual((await f.record('ally')).data.poolIds,[]);
  assert.deepEqual((await cloudActions(f.service,dm,continuity,'ally')).rows,[]);
  f.reopen();
  await move('party_actor','hero');
  assert.deepEqual((await f.record('hero')).data.poolIds,['charges']);
  assert.equal((await f.record('charges')).data.current,2);
  await f.run('define_action',{actorId:'hero',actionId:'elsewhere',name:'Other charge use',type:'use',timing:'other',cost:{poolId:'charges',amount:1},source});
  await assert.rejects(move('party_actor','ally'),/SHARED_CHARGE_POOL_TRANSFER_REVIEW_REQUIRED/);
  assert.equal((await f.record('lens')).data.custodianId,'hero');
  assert.equal((await f.record('lens-pulse')).actorId,'hero');
});
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
test('a personal inventory grant inspects and equips a possession without exposing the catalog or another holder',async t=>{
  const f=await fixture(t);
  await f.run('define_item_definition',definition('publicly-held',false));
  await f.run('define_item_definition',{...definition('other-definition',false),name:'Unrelated catalog item'});
  await f.run('acquire_item',{itemId:'mine',definitionId:'publicly-held',ownerId:'hero',quantity:1,source});
  await f.run('acquire_item',{itemId:'theirs',definitionId:'other-definition',ownerId:'ally',quantity:1,source});
  await f.run('define_item_definition',definition('same-kind',true));
  for(const [itemId,ownerId] of [['my-stock','hero'],['other-stock','ally']])await f.run('acquire_item',{itemId,definitionId:'same-kind',ownerId,quantity:4,source});
  await f.run('edit_item_definition',{definitionId:'publicly-held',name:'Current shared name',description:'Current description',category:'gear',rarity:null,source});
  await f.grant(['character.read','inventory.read','inventory.write'],['hero']);
  const view=options=>cloudRecordView(f.service,dm,continuity,'inventory',options);
  const mine=await view({id:'hero',query:'Current'});
  assert.deepEqual(mine.items.map(row=>row.id),['mine']);
  assert.equal(mine.items[0].title,'Current shared name');
  const inspected=await view({id:'mine'});
  assert.equal(inspected.definition.id,'publicly-held');
  assert.equal(inspected.definition.data.description,'Current description');
  assert.equal(await f.record('publicly-held',dm),undefined);
  for(const id of ['ally','theirs','other-definition','publicly-held']) await assert.rejects(view({id}),/RECORD_UNAVAILABLE/);
  await assert.rejects(view({}),/PARTY_AGGREGATE_SCOPE_REQUIRED/);
  await f.run('equip_item',{itemId:'mine',equipment:'held',source});
  const split=await f.run('split_stack',{itemId:'my-stock',splitId:'my-child',quantity:1,source});
  assert.equal((await view({id:split.childId})).definition.id,'same-kind');
  assert.equal((await f.record('my-stock')).data.quantity,3);
  const inherited=await f.db.prepare("SELECT to_id FROM cloud_links WHERE from_id='my-child' AND relation='definition'").bind().first();
  assert.equal(inherited.to_id,'same-kind');
  await assert.rejects(f.run('split_stack',{itemId:'other-stock',splitId:'forbidden-child',quantity:1,source}),/CLOUD_GRANT_DENIED/);
  const plan=new CloudPlan(f.service,f.service.key(continuity),await f.service.grantForSubject(continuity,'sites-mcp'),{commandId:'inherited-proof'},Date.now());
  await assert.rejects(plan.linkInheritedItemDefinition('mine','my-stock'),/HOLDING_DEFINITION_PROOF_REQUIRED/);
  const original=await plan.get('item_instance','my-stock');
  await plan.put({...original,id:'forged-child',data:{...original.data,definitionId:'other-definition'}});
  await plan.link('forged-child','split_from',original.id);
  await assert.rejects(plan.linkInheritedItemDefinition('forged-child',original.id),/HOLDING_DEFINITION_PROOF_REQUIRED/);
  await assert.rejects(plan.linkInheritedItemDefinition('forged-child','other-stock'),/CLOUD_GRANT_DENIED/);
  await assert.rejects(f.run('equip_item',{itemId:'theirs',equipment:'held',source}),/CLOUD_GRANT_DENIED/);
  await assert.rejects(f.run('edit_item_definition',{definitionId:'publicly-held',name:'Invalid',description:'Invalid',category:'gear',rarity:null,source}),/CLOUD_GRANT_DENIED/);
  assert.equal((await f.record('mine')).data.equipment,'held');
  assert.equal((await f.record('theirs')).data.equipment,'packed');
  assert.equal((await f.record('publicly-held')).title,'Current shared name');
});
test('the limited borrower can operate actual lent gear and charges without taking the lender’s ownership',async t=>{
  const f=await fixture(t,true);
  await f.run('define_item_definition',definition('tool-def',false));
  await f.run('acquire_item',{itemId:'loan-unit',definitionId:'tool-def',ownerId:'hero',quantity:1,source});
  await f.run('define_resource',{actorId:'hero',poolId:'loan-charge',name:'Remaining charges',type:'charge',maximum:4,current:2,recovery:'none',source});
  await f.run('bind_item_charge_pool',{itemId:'loan-unit',poolId:'loan-charge',source});
  await f.run('define_action',{actorId:'hero',actionId:'tool-use',name:'Use instrument',type:'use',timing:'other',sourceItemId:'loan-unit',
    transfersWithItem:true,cost:{poolId:'loan-charge',amount:1},source});
  const transfer={itemId:'loan-unit',quantity:1,splitId:null,destination:{type:'loan',actorId:'ally',locationId:null,recipientId:null,recipientName:null},reason:'Confirmed loan',source};
  await f.run('transfer_item',transfer);
  await f.grant(['character.read','character.write','inventory.read','inventory.write','resources.read','resources.write','combat.read','combat.write'],['ally']);
  const lent=await cloudRecordView(f.service,dm,continuity,'inventory',{id:'loan-unit'});
  assert.equal(lent.holding.data.ownerId,'hero');
  assert.equal(lent.holding.data.custodianId,'ally');
  assert.equal(lent.definition.id,'tool-def');
  await f.run('equip_item',{itemId:'loan-unit',equipment:'held',source});
  await f.run('use_action',{actorId:'ally',actionId:'tool-use',useId:'loan-use',declared:true});
  await assert.rejects(f.run('transfer_item',{...transfer,destination:{...transfer.destination,type:'party_actor'}}),/CLOUD_GRANT_DENIED/);
  await assert.rejects(f.run('customize_item',{itemId:'loan-unit',name:'Changed',description:'Changed',condition:'altered',source}),/CLOUD_GRANT_DENIED/);
  assert.equal((await f.record('loan-charge')).data.current,1);
  assert.equal((await f.record('loan-charge')).actorId,'ally');
  assert.equal((await f.record('loan-unit')).data.ownerId,'hero');
  assert.equal(await f.record('hero',dm),undefined);
  assert.equal(await f.record('tool-def',dm),undefined);
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
test('a reviewed mixed party exchange conserves possessions and both exact wallets in one retry-safe commit',async t=>{
  const f=await fixture(t);
  await f.run('define_item_definition',definition());await f.run('define_item_definition',definition('instrument',false));
  await f.run('acquire_item',{itemId:'supplies',ownerId:'hero',definitionId:'supply',quantity:8,source});
  await f.run('acquire_item',{itemId:'tool',ownerId:'ally',definitionId:'instrument',quantity:1,source});
  await f.run('define_wallet',{walletId:'left-money',title:'Purse',holderId:'hero',shared:false,coins:amounts({gp:10,ep:null}),source});
  await f.run('define_wallet',{walletId:'right-money',title:'Purse',holderId:'ally',shared:false,coins:amounts({cp:7}),source});
  const give={actorId:'hero',walletId:'left-money',coins:amounts({gp:2}),items:[{itemId:'supplies',quantity:3,splitId:'received-supplies'}]};
  const receive={actorId:'ally',walletId:'right-money',coins:amounts({cp:2}),items:[{itemId:'tool',quantity:1,splitId:null}]};
  const args={left:give,right:receive,reason:'Confirmed exchange',source};
  await assert.rejects(f.run('exchange_party_assets',{...args,right:{...receive,items:[{itemId:'tool',quantity:2,splitId:null}]}}),/INSUFFICIENT_KNOWN_QUANTITY/);
  assert.equal((await f.record('left-money')).data.coins.gp,10);assert.equal(await f.record('received-supplies'),undefined);
  assert.equal((await f.record('supplies')).data.quantity,8);
  const command=await f.body('exchange_party_assets',args),original=await f.submit(command);
  const trade=original.receipt.outcome;
  assert.equal(trade.holdings.length,2);
  assert.equal((await f.record('left-money')).data.coins.gp,8);
  assert.equal((await f.record('left-money')).data.coins.ep,null);
  assert.equal((await f.record('right-money')).data.coins.cp,5);
  assert.equal((await f.record('tool')).data.ownerId,'hero');
  assert.equal((await f.record('received-supplies')).data.ownerId,'ally');
  assert.equal((await f.record('supplies')).data.quantity,5);
  const total=(await cloudRecordView(f.service,dm,continuity,'wallets',{})).totals;
  assert.equal(total.knownCopper,'1007');
  for(const id of ['hero','supplies','received-supplies','tool','right-money']){
    const entries=(await cloudRecordView(f.service,dm,continuity,'timeline',{id})).events.filter(row=>row.data.commandId===command.commandId);
    assert.deepEqual(entries.map(row=>row.id),[trade.tradeId]);
  }
  const itemHistory=(await cloudRecordView(f.service,dm,continuity,'item_history',{id:'tool'})).events;
  assert.equal(itemHistory[0].data.after.ownerId,'hero');
  f.reopen();assert.equal((await f.submit(command)).receipt.transactionId,original.receipt.transactionId);
});
test('jointly owned stock stays separate from custody and confirmed unknown currency is initialized once',async t=>{
  const f=await fixture(t);
  await f.run('define_item_definition',definition());
  await f.run('define_connection',{entityId:'store',name:'Shared store',category:'place',partyStorage:true,actorId:null,knownDescription:null,source});
  await f.run('define_wallet',{walletId:'joint',title:'Common purse',holderId:null,shared:true,coins:amounts({gp:null,ep:null}),source});
  const reconciliation={walletId:'joint',expectedRevision:1,denomination:'gp',previous:null,confirmedBalance:6,reason:'Recorded opening count',source};
  await f.run('reconcile_wallet_denomination',reconciliation);
  await assert.rejects(f.run('reconcile_wallet_denomination',reconciliation),/WALLET_RECONCILIATION_CONFLICT/);
  await f.run('reconcile_wallet_denomination',{...reconciliation,expectedRevision:2,denomination:'ep',confirmedBalance:0,reason:'Confirmed no electrum held'});
  assert.equal((await f.record('joint')).data.coins.ep,0);
  await f.run('purchase_item',{itemId:'shared-stack',definitionId:'supply',ownerId:null,storageId:'store',quantity:4,
    walletId:'joint',price:amounts({gp:2}),sellerId:null,source});
  const original=await f.record('shared-stack');
  assert.equal(original.data.ownerType,'party');assert.equal(original.data.ownerId,null);assert.equal(original.data.custodianId,null);
  assert.equal(original.data.holding,'storage');assert.equal(original.data.locationId,'store');
  await f.run('transfer_item',{itemId:'shared-stack',quantity:4,splitId:null,destination:{type:'loan',actorId:'ally',locationId:null,
    recipientId:null,recipientName:null},reason:'Confirmed custody',source});
  const held=await f.record('shared-stack');
  assert.equal(held.data.ownerType,'party');assert.equal(held.data.ownerId,null);assert.equal(held.data.custodianId,'ally');
  await f.run('transfer_item',{itemId:'shared-stack',quantity:4,splitId:null,destination:{type:'storage',actorId:null,partyOwned:true,
    locationId:'store',recipientId:null,recipientName:null},reason:'Returned to party storage',source});
  f.reopen();assert.equal((await f.record('joint')).data.coins.gp,4);
  assert.equal((await cloudRecordView(f.service,dm,continuity,'inventory',{})).groups[0].knownQuantity,4);
});
test('editing a known connection and relationship, an instance, a favorite and an exact signed wallet entry preserves other identities',async t=>{
  const f=await fixture(t);
  const place={entityId:'house',name:'House',category:'place',actorId:null,knownDescription:'A known entrance',source};
  await f.run('define_connection',place);
  await f.run('edit_connection',{...place,name:'North House',knownDescription:'A confirmed north entrance'});
  const relation={relationshipId:'host',from:{kind:'actor',id:'hero'},to:{kind:'world_entity',id:'house'},relationship:'Visiting',
    terms:'Invitation recorded',status:'current',source};
  await f.run('record_relationship',relation);
  await f.run('update_relationship',{...relation,terms:'Visit finished',status:'ended'});
  await f.run('define_item_definition',definition('device',false));
  for(const itemId of ['custom-device','original-device'])await f.run('acquire_item',{definitionId:'device',itemId,ownerId:'hero',quantity:1,source});
  await f.run('customize_item',{itemId:'custom-device',name:'Adjusted instrument',description:'Owner-approved markings',condition:'marked',source});
  await f.run('set_item_favorite',{itemId:'custom-device',favorite:true});
  await f.run('define_wallet',{walletId:'spending',title:'Held coins',holderId:'hero',shared:false,coins:amounts({cp:8,gp:null}),source});
  await f.run('adjust_coins',{walletId:'spending',change:amounts({cp:-5,sp:2}),reason:'Confirmed service and income',source});
  f.reopen();
  assert.equal((await f.record('house')).title,'North House');
  assert.equal((await f.record('host')).data.status,'ended');
  assert.equal((await f.record('custom-device')).data.favorite,true);
  assert.equal((await f.record('original-device')).title,'Supplies');
  assert.equal((await f.record('device')).title,'Supplies');
  assert.deepEqual((await f.record('spending')).data.coins,amounts({cp:3,sp:2,gp:null}));
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
test('sourced corrections preserve holdings, earlier receipts, unresolved facts and completed milestones',async t=>{
  const f=await fixture(t);
  await f.run('define_item_definition',definition());
  await f.run('acquire_item',{itemId:'bundle',definitionId:'supply',ownerId:'hero',quantity:3,source});
  const owned=await f.record('bundle');
  const historic={eventId:'attested',itemId:'bundle',eventType:'acquisition',previousOwner:{kind:'unknown',id:null,name:null},
    newOwner:{kind:'actor',id:'hero',name:null},reason:'Recorded prior acquisition',worldTime:null,sourceEventId:'page-record-one',
    sourcePublishedAt:null,source,evidence:'uncertain',correctsId:null};
  await f.run('record_historical_item_event',historic);
  await assert.rejects(f.run('record_historical_item_event',{...historic,eventId:'duplicate'}),/ITEM_SOURCE_EVENT_ALREADY_RECORDED/);
  await f.run('record_historical_item_event',{...historic,eventId:'clarified',sourceEventId:'page-record-two',evidence:'confirmed',
    previousOwner:{kind:'named',id:null,name:'Recorded prior holder'},correctsId:'attested',reason:'Clarified earlier source'});
  assert.deepEqual(await f.record('bundle'),owned);
  const history=await cloudRecordView(f.service,dm,continuity,'item_history',{id:'bundle'});
  assert.deepEqual(history.events.slice(0,2).map(row=>[row.id,row.data.evidence]),[['clarified','confirmed'],['attested','uncertain']]);
  const cp={checkpointId:'handoff',title:'Handoff',scene:'Source fixture',locationId:null,worldTime:null,lastEventKind:'item_event',lastEventId:'attested',
    unresolved:null,pendingRollId:null,encounterId:null,objectiveIds:[],actorIds:['hero'],source};
  await assert.rejects(f.run('save_checkpoint',cp),/CONFIRMED_EVENT_REQUIRED/);
  await f.run('save_checkpoint',{...cp,lastEventId:'clarified'});
  const original=await f.body('record_known_fact',{factId:'fact',subject:{kind:'item_instance',id:'bundle'},title:'Recorded marking',
    detail:'A mark was reported.',evidence:'rumor',learnedAt:null,originalWorldTime:null,source});
  const originalReceipt=(await f.submit(original)).receipt;
  const revision={factId:'fact',expectedRevision:1,title:'Recorded marking',detail:'A mark was inspected.',evidence:'confirmed',
    learnedAt:null,originalWorldTime:null,reason:'Inspected evidence',source};
  await f.run('revise_known_fact',revision);
  await assert.rejects(f.run('revise_known_fact',revision),/FACT_REVISION_CHANGED/);
  await f.run('define_objective',{objectiveId:'goal',title:'Examine a marking',actorId:'hero',category:'personal',status:'active',
    knownRequirements:[],linkedProjectId:null,source});
  await f.run('revise_objective',{objectiveId:'goal',expectedRevision:1,title:'Examine both marks',knownRequirements:['Compare the sides'],reason:'Clarified scope',source});
  assert.equal((await f.record('goal')).data.status,'active');
  for(const eventId of ['observed','compared']){
    await f.run('record_chronicle_event',{eventId,title:'Actual observation',narrative:'Observed in the fixture.',category:'discovery',
      status:'confirmed',worldTime:null,subjects:[{kind:'item_instance',id:'bundle'}],correctsId:null,source});
    await f.run('record_objective_milestone',{objectiveId:'goal',eventId,status:'active',source});
  }
  await assert.rejects(f.run('record_objective_milestone',{objectiveId:'goal',eventId:'observed',status:'completed',source}),/MILESTONE_ALREADY_RECORDED/);
  f.reopen();
  assert.equal((await f.record('fact')).data.evidence,'confirmed');
  assert.deepEqual((await f.service.receipt(dm,continuity,original.commandId)).receipt,originalReceipt);
  assert.equal((await f.service.state(dm,continuity,{kind:'coin_transaction'})).records.length,0);
});
test('project revisions and mandatory source materials, linked payments and completed predecessor are validated before completion',async t=>{
  const f=await fixture(t);
  await f.run('define_item_definition',definition());
  await f.run('define_item_definition',definition('shell',false));
  await f.run('acquire_item',{itemId:'thread',definitionId:'supply',ownerId:'hero',quantity:4,source});
  for(const itemId of ['prior-item','result-item','unrelated-item'])await f.run('acquire_item',{itemId,definitionId:'shell',ownerId:'hero',quantity:1,source});
  await f.run('define_wallet',{walletId:'purse',title:'Coins',holderId:'hero',shared:false,coins:amounts({gp:8,sp:2}),source});
  await f.run('record_research',{researchId:'finding',title:'Observation',actorId:'hero',finding:'Suspected fit',evidence:'hypothesis',itemId:'thread',rollId:null,source});
  await f.run('revise_research_finding',{researchId:'finding',expectedRevision:1,title:'Observation',finding:'Confirmed fit',evidence:'confirmed',reason:'Inspected components',source});
  for(const projectId of ['prior','result'])await f.run('propose_invention',{projectId,title:projectId,actorId:'hero',intent:'Prepare the fixture',requirements:[],researchIds:[],source});
  await f.run('revise_invention_plan',{projectId:'result',expectedRevision:1,title:'Reviewed assembly',intent:'Join the fixture',
    requirements:['Attach the source material'],additionalResearchIds:['finding'],reason:'Confirmed fit',source});
  await f.run('link_project_dependency',{projectId:'result',requiredProjectId:'prior',source});
  await assert.rejects(f.run('link_project_dependency',{projectId:'prior',requiredProjectId:'result',source}),/PROJECT_DEPENDENCY_CONFLICT/);
  for(const projectId of ['prior','result']){
    const stageId=projectId+'-stage';
    await f.run('define_construction_stage',{projectId,stageId,blueprintId:null,title:'Reviewed fitting',prerequisites:[],requiredWork:null,checkRequired:false,
      ...(projectId==='result'?{materials:[{definitionId:'supply',quantity:3,method:'material_consumed'}],minimumPayment:amounts({gp:3,sp:2})}:{}),source});
    await f.run('resolve_construction_stage',{stageId,outcome:'completed',rollId:null,worldTimeSpent:null,evidence:'Confirmed by fixture owner',source});
  }
  const finish={projectId:'result',outputItemId:'result-item',stageIds:['result-stage'],source};
  await assert.rejects(f.run('link_completed_invention',finish),/INCOMPLETE_PROJECT_DEPENDENCY/);
  await f.run('link_completed_invention',{projectId:'prior',outputItemId:'prior-item',stageIds:['prior-stage'],source});
  await assert.rejects(f.run('link_completed_invention',finish),/STAGE_MATERIAL_EVIDENCE_REQUIRED/);
  const install={eventId:'material-1',projectId:'result',stageId:'result-stage',donorItemId:'thread',outputItemId:'result-item',
    method:'material_consumed',quantity:3,evidence:'Three lengths were used',source};
  await assert.rejects(f.run('integrate_component',{...install,quantity:4}),/STAGE_MATERIAL_REQUIREMENT_MISMATCH/);
  await f.run('integrate_component',install);
  await assert.rejects(f.run('link_completed_invention',{...finish,outputItemId:'unrelated-item'}),/PROJECT_OUTPUT_LINEAGE_MISMATCH/);
  await assert.rejects(f.run('link_completed_invention',finish),/STAGE_PAYMENT_EVIDENCE_REQUIRED/);
  const payment={projectId:'result',stageId:'result-stage',walletId:'purse',coins:amounts({gp:3}),reason:'Recorded stage payment',source};
  await f.run('pay_construction_cost',payment);
  await assert.rejects(f.run('link_completed_invention',finish),/STAGE_PAYMENT_EVIDENCE_REQUIRED/);
  await f.run('pay_construction_cost',{...payment,coins:amounts({sp:2})});
  await f.run('link_completed_invention',finish);
  f.reopen();
  assert.equal((await f.record('result')).data.outputId,'result-item');
  assert.equal((await f.record('thread')).data.quantity,1);
  assert.deepEqual((await f.record('purse')).data.coins,amounts({gp:5}));
  assert.equal((await f.record('finding')).data.evidence,'confirmed');
});

test('explicit exchange conserves copper and insufficient exact coins do not silently make change',async t=>{
  const f=await fixture(t);
  await f.run('define_wallet',{walletId:'fund',title:'Shared funds',holderId:null,shared:true,coins:amounts({gp:3}),source});
  const convert={walletId:'fund',give:amounts({gp:1}),receive:amounts({sp:10}),source,reason:'Authorized exchange'};
  await f.run('exchange_coins',convert);
  assert.deepEqual((await f.record('fund')).data.coins,amounts({gp:2,sp:10}));
  await assert.rejects(f.run('exchange_coins',{...convert,receive:amounts({sp:11})}),/EXCHANGE_VALUE_MISMATCH/);
  const totals=await cloudRecordView(f.service,dm,continuity,'wallets');
  assert.equal(totals.totals.knownCopper,'300');assert.equal(totals.totals.missingWalletCount,2);
});
test('attunement and operating state respect configured elapsed time, known capacity, eligible physical item and active form',async t=>{
  const f=await fixture(t);
  const item={...definition('coil',false),attunement:'required',attunementSeconds:3600,activation:'manual'};
  await f.run('define_item_definition',item);
  await f.run('acquire_item',{itemId:'first',definitionId:item.definitionId,quantity:1,ownerId:'hero',source});
  await f.run('equip_item',{itemId:'first',equipment:'worn',source});
  const attune={itemId:'first',attuned:true,confirmed:true,elapsedSeconds:3600,source};
  await assert.rejects(f.run('set_item_operating',{itemId:'first',operating:true,source}),/ACTIVATION_PREREQUISITES/);
  await assert.rejects(f.run('set_item_attunement',attune),/ATTUNEMENT_LIMIT_UNKNOWN/);
  await f.db.prepare("UPDATE cloud_entities SET data_json=json_set(data_json,'$.attunementCapacity',1),revision=revision+1 WHERE id='hero'").bind().run();
  await assert.rejects(f.run('set_item_attunement',{...attune,elapsedSeconds:3599}),/ATTUNEMENT_PREREQUISITES/);
  await f.run('set_item_attunement',attune);
  await f.run('set_item_operating',{itemId:'first',operating:true,source});
  await f.run('acquire_item',{itemId:'second',definitionId:item.definitionId,quantity:1,ownerId:'hero',source});
  await assert.rejects(f.run('set_item_attunement',{...attune,itemId:'second'}),/ATTUNEMENT_CAPACITY/);
  await f.db.prepare("INSERT INTO cloud_entities(owner_id,continuity_id,id,kind,actor_id,title,data_json,life,revision) VALUES(?,?,?,'form','hero','Source form',?,'active',1)")
    .bind(owner.ownerId,continuity,'mode',JSON.stringify({sourceItemId:'first',requiresAttunement:true,requiresOperating:true})).run();
  await f.db.prepare("UPDATE cloud_entities SET data_json=json_set(data_json,'$.activeFormId','mode'),revision=revision+1 WHERE id='hero'").bind().run();
  await assert.rejects(f.run('set_item_operating',{itemId:'first',operating:false,source}),/FORM_REVERSION_REQUIRED/);
  await assert.rejects(f.run('set_item_attunement',{...attune,attuned:false}),/FORM_REVERSION_REQUIRED/);
  assert.equal((await f.record('first')).data.operating,true);
  await f.db.prepare("UPDATE cloud_entities SET data_json=json_set(data_json,'$.revertOnSourceLoss',json('true')),revision=revision+1 WHERE id='mode'").bind().run();
  await f.db.prepare("UPDATE cloud_entities SET data_json=json_set(data_json,'$.currentHp',9,'$.temporaryHp',2,'$.formExpiresAt',130),revision=revision+1 WHERE id='hero'").bind().run();
  await f.db.prepare("INSERT INTO cloud_links(owner_id,continuity_id,from_id,relation,to_id) VALUES(?,?,'hero','active_form','mode')").bind(owner.ownerId,continuity).run();
  await f.run('set_item_operating',{itemId:'first',operating:false,source});
  const reverted=(await f.record('hero')).data;
  assert.equal(reverted.activeFormId,null);assert.equal(reverted.formExpiresAt,null);
  assert.equal(reverted.currentHp,9);assert.equal(reverted.temporaryHp,2);
  assert.equal((await f.record('first')).data.attuned,true);
  assert.equal(await f.db.prepare("SELECT 1 FROM cloud_links WHERE from_id='hero' AND relation='active_form'").bind().first(),null);
});

test('successor assembly preserves earlier component identity and no nested item remains separately usable',async t=>{
  const f=await fixture(t);
  await f.run('define_item_definition',definition('machine',false));
  for(const id of ['core','frame','successor'])await f.run('acquire_item',{itemId:id,definitionId:'machine',quantity:1,ownerId:'hero',source});
  async function phase(projectId,donor,output) {
    await f.run('propose_invention',{projectId,title:'Synthetic assembly',actorId:'hero',intent:'Install existing reviewed parts.',requirements:[],researchIds:[],source});
    const stageId='stage-'+projectId;
    await f.run('define_construction_stage',{stageId,projectId,blueprintId:null,title:'Install',prerequisites:[],requiredWork:null,checkRequired:false,source});
    await f.run('resolve_construction_stage',{stageId,outcome:'completed',rollId:null,worldTimeSpent:null,evidence:'Confirmed installation.',source});
    await f.run('integrate_component',{eventId:'event-'+projectId,projectId,stageId,donorItemId:donor,outputItemId:output,
      method:'installed',quantity:1,evidence:'Explicit reusable installation.',source});
    await f.run('link_completed_invention',{projectId,outputItemId:output,stageIds:[stageId],source});
  }
  await phase('first','core','frame');
  await phase('next','frame','successor');
  for(const id of ['core','frame']) {
    const d=(await f.record(id)).data;
    assert.equal(d.holding,'installed');assert.equal(d.quantity,1);assert.equal(d.partyAccess,false);
    await assert.rejects(f.run('equip_item',{itemId:id,equipment:'held',source}),/ITEM_UNAVAILABLE/);
  }
  const graph=await cloudRecordView(f.service,dm,continuity,'genealogy',{id:'frame'});
  assert.deepEqual(graph.events.map(e=>e.id).sort(),['event-first','event-next']);
  const items=await cloudRecordView(f.service,dm,continuity,'inventory');
  assert.equal(items.groups.length,1);assert.equal(items.groups[0].knownQuantity,1);
});

test('story arcs and sessions retain sourced chronology, stable revision history and scoped event and checkpoint links',async t=>{
  const f=await fixture(t);
  const arc={arcId:'sourced-arc',classification:'adventure',title:'Fixture voyage',parentArcId:null,summary:null,
    state:'active',worldStart:null,worldEnd:null,eventIds:[],source:'Synthetic session notes'};
  await f.run('define_story_arc',arc);
  const event={eventId:'temporal-event',title:'Read the chart',narrative:'The route was identified.',category:'discovery',
    status:'confirmed',worldTime:null,subjects:[],correctsId:null,source};
  await f.run('record_chronicle_event',event);
  await f.run('save_checkpoint',{checkpointId:'temporal-checkpoint',title:'Before departure',scene:'At the quay',locationId:null,worldTime:null,
    lastEventId:event.eventId,unresolved:'Choose when to depart',pendingRollId:null,encounterId:null,objectiveIds:[],actorIds:[],source});
  const session={sessionId:'table-one',arcId:arc.arcId,previousSessionId:null,playedOn:null,title:'First session',summary:null,
    state:'complete',worldStart:null,worldEnd:null,eventIds:[event.eventId],openingCheckpointId:null,closingCheckpointId:'temporal-checkpoint',source};
  const first=await f.body('record_campaign_session',session);
  const saved=await f.submit(first);
  const child={...arc,classification:'story_arc',arcId:'coast-arc',parentArcId:arc.arcId,title:'The coast'};
  await f.run('define_story_arc',child);
  const next={...session,sessionId:'table-two',title:'Second session',state:'planned',arcId:child.arcId,
    previousSessionId:'table-one',eventIds:[],openingCheckpointId:'temporal-checkpoint',closingCheckpointId:null};
  await f.run('record_campaign_session',next);
  const firstRow=await f.record('table-one'),arcRow=await f.record(arc.arcId);
  assert.equal(firstRow.data.playedOn,null);assert.equal(firstRow.data.worldStart,null);
  assert.ok(Number.isSafeInteger(firstRow.data.recordedAt));
  await assert.rejects(f.run('revise_story_arc',{...arc,parentArcId:child.arcId,expectedRevision:arcRow.revision,reason:'Fixture cycle'}),/CHRONOLOGY_CYCLE/);
  await assert.rejects(f.run('revise_campaign_session',{...session,previousSessionId:'table-two',
    expectedRevision:firstRow.revision,reason:'Fixture cycle'}),/CHRONOLOGY_CYCLE/);
  await assert.rejects(f.run('revise_campaign_session',{...next,sessionId:'table-one',previousSessionId:null,eventIds:['absent'],
    expectedRevision:firstRow.revision,reason:'Fixture missing link'}),/RECORD_UNAVAILABLE/);
  await f.run('revise_story_arc',{...arc,title:'Revised voyage title',eventIds:[event.eventId],expectedRevision:arcRow.revision,reason:'Source title correction'});
  await f.run('revise_campaign_session',{...session,playedOn:'2026-11-26',worldStart:'First full moon',expectedRevision:firstRow.revision,reason:'Source chronology recovered'});
  await assert.rejects(f.run('revise_campaign_session',{...session,expectedRevision:firstRow.revision,reason:'Stale revision'}),/CHRONOLOGY_REVISION_CHANGED/);
  f.reopen();
  assert.deepEqual((await f.service.receipt(dm,continuity,first.commandId)).receipt,saved.receipt);
  const current=await f.record('table-one');assert.equal(current.data.recordedAt,firstRow.data.recordedAt);
  assert.equal(current.data.playedOn,'2026-11-26');assert.equal(current.data.worldEnd,null);
  const links=(await f.db.prepare('SELECT relation,to_id FROM cloud_links WHERE owner_id=? AND continuity_id=? AND from_id=? ORDER BY relation')
    .bind(owner.ownerId,continuity,'table-one').all()).results;
  assert.deepEqual(links.map(x=>[x.relation,x.to_id]),[['closes_at_checkpoint','temporal-checkpoint'],['includes_event',event.eventId],['session_in_arc',arc.arcId]]);
  assert.equal((await f.record(event.eventId)).revision,1);assert.equal((await f.record('temporal-checkpoint')).revision,1);
  const {cloudContext}=await import('./cloud-query.mjs');
  const handoff=await cloudContext(f.service,dm,continuity);
  assert.deepEqual(handoff.chronology.sessions.map(s=>s.id).sort(),['table-one','table-two']);
  assert.equal(handoff.chronology.storyArcs.length,2);assert.equal(handoff.chronology.truncated,false);
  await f.grant(['chronicle.read','chronicle.write'],['hero']);
  assert.equal((await f.service.state(dm,continuity,{kind:'campaign_session'})).records.length,0);
  assert.deepEqual((await cloudContext(f.service,dm,continuity)).chronology.sessions,[]);
  await assert.rejects(f.run('define_story_arc',{...arc,arcId:'narrow-arc'}),/DENIED|FORBIDDEN/);
});
