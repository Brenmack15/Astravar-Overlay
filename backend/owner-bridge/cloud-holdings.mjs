import { recordsFail, recordObject, recordId, recordText, recordSource, recordTitle, recordChoice, recordMaybe, recordInt, recordArray, recordBool, recordsOperation, recordRead, recordNew, recordEdit, partyActor, validateRecordValue, historyRecordId } from './cloud-records-contract.mjs';

const denominations = ['cp','sp','ep','gp','pp'];
const copperPerCoin = { cp: 1n, sp: 10n, ep: 50n, gp: 100n, pp: 1000n };
const maximumCoins = 1_000_000_000;
const count = recordInt(1, 1_000_000);
const nullableId = recordMaybe(recordId);
const coinSpec = spec => recordObject(Object.fromEntries(denominations.map(key => [key, spec])));
const coins = coinSpec(recordInt(0, maximumCoins));
const balances = coinSpec(recordMaybe(recordInt(0, maximumCoins)));
const deltaCoins = coinSpec(recordInt(-maximumCoins, maximumCoins));
const equipmentState = recordChoice('packed','held','worn');
const attunementRule = recordChoice('none','required','configured_review');
const defineWallet = recordObject({ walletId: recordId, title: recordTitle, holderId: nullableId, shared: recordBool, coins: balances, source: recordSource });
const transferCoins = recordObject({ fromWalletId: recordId, toWalletId: recordId, coins, reason: recordTitle, source: recordSource });
const adjustCoins = recordObject({ walletId: recordId, change: deltaCoins, reason: recordTitle, source: recordSource });
const exchangeCoins = recordObject({ walletId: recordId, give: coins, receive: coins, reason: recordTitle, source: recordSource });
const definitionFields = { definitionId: recordId, name: recordTitle, category: recordTitle, description: recordText,
  rarity: recordMaybe(recordTitle), stackable: recordBool, wearable: recordBool, handheld: recordBool, attunement: attunementRule, source: recordSource };
const definition = recordObject({ ...definitionFields, activation: recordChoice('passive','manual','review'), attunementSeconds: recordMaybe(recordInt(0,604800)) },Object.keys(definitionFields));
const acquisitionFields = { itemId: recordId, definitionId: recordId, ownerId: recordId, quantity: count, source: recordSource };
const acquireItem = recordObject(acquisitionFields);
const purchaseItem = recordObject({ ...acquisitionFields, walletId: recordId, price: coins, sellerId: nullableId });
const destination = recordObject({ type: recordChoice('party_actor','storage','unassigned','external_gift','lost','dropped','loan'),
  actorId: nullableId, locationId: nullableId, recipientId: nullableId, recipientName: recordMaybe(recordTitle) });
const sellItem = recordObject({ itemId: recordId, quantity: count, splitId: nullableId, walletId: recordId,
  proceeds: coins, buyerId: nullableId, buyerName: recordMaybe(recordTitle), source: recordSource });
const transferItem = recordObject({ itemId: recordId, quantity: count, splitId: nullableId, destination,
  reason: recordTitle, source: recordSource });
const splitItem = recordObject({ itemId: recordId, splitId: recordId, quantity: count, source: recordSource });
const mergeItem = recordObject({ keepId: recordId, mergeId: recordId, source: recordSource });
const recoverItem = recordObject({ itemId: recordId, ownerId: recordId, source: recordSource, reason: recordTitle });
const equipItem = recordObject({ itemId: recordId, equipment: equipmentState, source: recordSource });
const historicalOwner = recordObject({kind:recordChoice('actor','world_entity','named','unknown'),id:nullableId,name:recordMaybe(recordTitle)});
const historicalItemEvent = recordObject({eventId:recordId,itemId:recordId,
  eventType:recordChoice('acquisition','purchase','gift','transfer','sale','storage','retrieval','borrowing','return','loss','recovery','modification','integration','other'),
  previousOwner:recordMaybe(historicalOwner),newOwner:recordMaybe(historicalOwner),reason:recordTitle,worldTime:recordMaybe(recordTitle),
  sourceEventId:recordId,sourcePublishedAt:recordMaybe(recordTitle),source:recordSource,evidence:recordChoice('confirmed','uncertain'),correctsId:nullableId});

function assertPositive(payment) {
  if (denominations.every(key => payment[key] === 0)) recordsFail('NONZERO_COINS_REQUIRED');
}
export function coinSummary(values) {
  validateRecordValue(balances, values);
  let knownCopper = 0n;
  const unknown = [];
  for (const key of denominations) {
    if (values[key] === null) unknown.push(key);
    else knownCopper += BigInt(values[key]) * copperPerCoin[key];
  }
  return { knownCopper: knownCopper.toString(), complete: unknown.length === 0, unknown };
}
function changedCoins(before, change) {
  validateRecordValue(balances, before);
  const next = { ...before };
  for (const key of denominations) {
    if (change[key] === 0) continue;
    if (before[key] === null) recordsFail('UNKNOWN_DENOMINATION', 409);
    const result = before[key] + change[key];
    if (result < 0) recordsFail('INSUFFICIENT_DENOMINATION', 409);
    if (result > maximumCoins) recordsFail('WALLET_LIMIT', 409);
    next[key] = result;
  }
  return next;
}
function debit(payment) { return Object.fromEntries(denominations.map(key => [key, -payment[key]])); }
function usable(item) {
  if (item.life !== 'active' || !item.data.partyAccess || !['carried','storage','unassigned'].includes(item.data.holding)) recordsFail('ITEM_UNAVAILABLE', 409);
}
function compareStack(first, second) {
  const fields = ['definitionId','ownerId','custodianId','locationId','holding','equipment','attuned','operating','condition','customized','resourcePoolId'];
  return fields.every(key => first.data[key] === second.data[key]);
}
function portable(item, def) {
  usable(item);
  if (!def.data.stackable || item.data.attuned || item.data.operating || item.data.equipment !== 'packed' ||
      item.data.resourcePoolId !== null || item.data.customized || !Number.isSafeInteger(item.data.quantity)) recordsFail('STACK_INCOMPATIBLE', 409);
}
/** A visible physical holding authorizes reading its linked definition for the
 * operation on that holding. It grants no direct access to the shared catalog. */
async function definitionForHolding(ctx,item) {
  const held=await ctx.get('item_instance',item.id);
  if(!held) recordsFail('RECORD_UNAVAILABLE',409);
  if(held.life!=='active' && ctx.changes.get(held.id)?.before?.life!=='active') ctx.require('lifecycle',held.actorId,false);
  if(held.data.definitionId!==item.data.definitionId) recordsFail('ITEM_DEFINITION_MISMATCH',409);
  const def=await ctx.current(held.data.definitionId);
  if(!def || def.kind!=='item_definition' || def.actorId!==null || def.life!=='active') recordsFail('RECORD_UNAVAILABLE',409);
  return def;
}
async function historicalOwnerRef(ctx,owner) {
  if(owner===null) return null;
  const linked=owner.kind==='actor'||owner.kind==='world_entity';
  if(linked!==Boolean(owner.id) || (owner.kind==='named')!==Boolean(owner.name)) recordsFail('HISTORICAL_OWNER_REFERENCE');
  const row=linked?await recordRead(ctx,owner.kind,owner.id,true):null;
  return {row,summary:{ownerId:owner.kind==='actor'?owner.id:null,externalRecipientId:owner.kind==='world_entity'?owner.id:null,
    externalRecipientName:owner.name,kind:owner.kind}};
}
async function recordHistoricalItemEvent(ctx,args) {
  await recordNew(ctx,'item_event',args.eventId);
  const item=await recordRead(ctx,'item_instance',args.itemId,true);
  if(['storage','retrieval','borrowing','return','loss','modification','integration'].includes(args.eventType) && args.newOwner!==null)
    recordsFail('HISTORICAL_EVENT_DID_NOT_CONVEY_OWNERSHIP');
  const previous=await historicalOwnerRef(ctx,args.previousOwner),next=await historicalOwnerRef(ctx,args.newOwner);
  if(args.correctsId) {
    await recordRead(ctx,'item_event',args.correctsId);
    const prior=await ctx.service.s("SELECT 1 AS linked FROM cloud_links WHERE owner_id=? AND continuity_id=? AND from_id=? AND relation='item_subject' AND to_id=?",
      ...ctx.binding,args.correctsId,item.id).first();
    if(!prior) recordsFail('ITEM_HISTORY_SUBJECT_MISMATCH',409);
  }
  const duplicate=await ctx.service.s(`SELECT 1 AS existing FROM cloud_entities e JOIN cloud_links l
    ON e.owner_id=l.owner_id AND e.continuity_id=l.continuity_id AND e.id=l.from_id
    WHERE l.owner_id=? AND l.continuity_id=? AND l.to_id=? AND l.relation='item_subject' AND e.kind='item_event'
      AND json_extract(e.data_json,'$.sourceEventId')=? LIMIT 1`,...ctx.binding,item.id,args.sourceEventId).first();
  if(duplicate) recordsFail('ITEM_SOURCE_EVENT_ALREADY_RECORDED',409);
  const scopes=new Set([item.actorId,...[previous?.row,next?.row].filter(Boolean).map(row=>row.actorId)]);
  await ctx.put({id:args.eventId,kind:'item_event',actorId:scopes.size===1?item.actorId:null,title:args.reason,data:{
    type:args.eventType,historical:true,before:previous?.summary??null,after:next?.summary??null,evidence:args.evidence,
    worldTime:args.worldTime,sourceEventId:args.sourceEventId,sourcePublishedAt:args.sourcePublishedAt,source:args.source,
    recordedAt:ctx.now,commandId:ctx.commandId}});
  await ctx.link(args.eventId,'item_subject',item.id);
  if(previous?.row)await ctx.link(args.eventId,'historical_previous_owner',previous.row.id);
  if(next?.row)await ctx.link(args.eventId,'historical_new_owner',next.row.id);
  if(args.correctsId)await ctx.link(args.eventId,'corrects_history',args.correctsId);
  return {eventId:args.eventId,itemId:item.id,historical:true,holdingRevision:item.revision};
}

async function walletRecord(ctx, id) {
  const wallet = await recordRead(ctx, 'wallet', id);
  if (wallet.data.holderId !== wallet.actorId || wallet.data.shared !== (wallet.actorId === null)) recordsFail('INVALID_WALLET', 409);
  if (wallet.actorId !== null) await partyActor(ctx, wallet.actorId);
  validateRecordValue(balances, wallet.data.coins);
  return wallet;
}
async function recordPayment(ctx, type, legs, reason, source) {
  const id = await historyRecordId('payment', ctx.commandId);
  await recordNew(ctx, 'coin_transaction', id);
  const holders = new Set(legs.map(leg => leg.wallet.actorId));
  const lines = legs.map(({ wallet, next }) => ({ walletId: wallet.id, before: wallet.data.coins, after: next }));
  await ctx.put({ id, kind: 'coin_transaction', actorId: holders.size === 1 ? legs[0].wallet.actorId : null,
    title: reason, data: { type, lines, source, commandId: ctx.commandId, recordedAt: ctx.now } });
  for (const {wallet,next} of legs) {
    await ctx.put(recordEdit(wallet, { ...wallet.data, coins: next }));
    await ctx.link(id, 'wallet_leg', wallet.id);
  }
  return { transactionRecordId: id, wallets: lines };
}
async function itemEvent(ctx, type, before, after, reason, source, relatedIds = []) {
  const id = await historyRecordId('holding', ctx.commandId);
  await recordNew(ctx, 'item_event', id);
  const owners = [before?.actorId, after.actorId].filter(value => value !== undefined);
  const summarize = row => row ? { ownerId: row.data.ownerId, custodianId: row.data.custodianId, locationId: row.data.locationId,
    holding: row.data.holding, quantity: row.data.quantity, equipment: row.data.equipment,
    externalRecipientId:row.data.externalRecipientId??null,externalRecipientName:row.data.externalRecipientName??null } : null;
  const definition=await definitionForHolding(ctx,after);
  await ctx.put({ id, kind: 'item_event', title: reason, actorId: owners.every(actor => actor === after.actorId) ? after.actorId : null,
    data: { type, definitionId:definition.id, definitionRevision:definition.revision, before: summarize(before), after: summarize(after), source, commandId: ctx.commandId, recordedAt: ctx.now } });
  for (const itemId of new Set([after.id,...relatedIds])) await ctx.link(id, 'item_subject', itemId);
  for (const originalId of new Set(relatedIds)) if(originalId !== after.id) {
    await ctx.link(id,'from_stack',originalId);
    await ctx.link(id,'into_stack',after.id);
  }
  for (const [relation,actorId] of [['previous_owner',before?.data.ownerId],['new_owner',after.data.ownerId],
      ['previous_custodian',before?.data.custodianId],['new_custodian',after.data.custodianId]]) {
    if (actorId) await ctx.link(id,relation,actorId);
  }
  return id;
}
async function outputItem(ctx, args) {
  await recordNew(ctx, 'item_instance', args.itemId);
  await partyActor(ctx, args.ownerId);
  const def = await recordRead(ctx, 'item_definition', args.definitionId);
  if ((!def.data.stackable || def.data.attunement !== 'none') && args.quantity !== 1) recordsFail('UNIQUE_ITEM_QUANTITY');
  const item = { kind:'item_instance', id:args.itemId, actorId:args.ownerId, title:def.title, life:'active',
    data: { definitionId:def.id, ownerId:args.ownerId, custodianId:args.ownerId, locationId:null, quantity:args.quantity,
      holding:'carried', partyAccess:true, equipment:'packed', attuned:false, operating:false, resourcePoolId:null,
      customized:false, favorite:false, condition:'standard', source:args.source } };
  await ctx.put(item);
  await ctx.link(item.id,'definition',def.id);
  await ctx.link(item.id,'owned_by',args.ownerId);
  await ctx.link(item.id,'held_by',args.ownerId);
  return item;
}
async function createWallet(ctx, args) {
  const {walletId,title,holderId,shared,coins: opening,source} = args;
  if (shared !== (holderId === null)) recordsFail('WALLET_HOLDER_MISMATCH');
  await recordNew(ctx, 'wallet', walletId);
  if (holderId !== null) await partyActor(ctx,holderId);
  await ctx.put({ id:walletId,kind:'wallet',actorId:holderId,title,data:{holderId,shared,coins:opening,source} });
  if (holderId !== null) await ctx.link(walletId,'held_by',holderId);
  return {walletId,coins:opening};
}
async function buy(ctx,args) {
  assertPositive(args.price);
  const wallet = await walletRecord(ctx,args.walletId);
  if (wallet.actorId !== null && wallet.actorId !== args.ownerId) recordsFail('PURCHASE_WALLET_HOLDER_MISMATCH',409);
  if (args.sellerId !== null) await recordRead(ctx, 'world_entity', args.sellerId);
  const next = changedCoins(wallet.data.coins, debit(args.price));
  const item = await outputItem(ctx,args);
  const payment = await recordPayment(ctx,'purchase',[{wallet,next}],'Purchase',args.source);
  const eventId = await itemEvent(ctx,'purchase',null,item,'Purchase',args.source);
  await ctx.link(eventId,'payment',payment.transactionRecordId);
  if (args.sellerId !== null) await ctx.link(eventId,'seller',args.sellerId);
  return { ...payment,itemId:item.id,quantity:item.data.quantity,itemEventId:eventId };
}
async function divide(ctx, original, quantity, newId) {
  if (newId === original.id || quantity >= original.data.quantity) recordsFail('INVALID_STACK_SPLIT');
  await recordNew(ctx,'item_instance',newId);
  const def = await definitionForHolding(ctx,original);
  portable(original,def);
  await ctx.put(recordEdit(original,{...original.data,quantity:original.data.quantity-quantity}));
  const child = { ...recordEdit(original,{...original.data,quantity}), id:newId };
  await ctx.put(child);
  await ctx.link(child.id,'split_from',original.id);
  await ctx.link(child.id,'definition',def.id);
  for(const [relation,field] of [['owned_by','ownerId'],['held_by','custodianId'],['located_at','locationId']])
    if(child.data[field] !== null) await ctx.link(child.id,relation,child.data[field]);
  return child;
}
async function applyPlacement(ctx, original, next) {
  if (original.data.custodianId !== next.data.custodianId) await moveItemRules(ctx,original,next);
  await ctx.put(next);
  for (const [relation,field] of [['owned_by','ownerId'],['held_by','custodianId'],['located_at','locationId']]) {
    if (original.data[field] === next.data[field]) continue;
    if (original.data[field] !== null) await ctx.unlink(original.id,relation,original.data[field]);
    if (next.data[field] !== null) await ctx.link(next.id,relation,next.data[field]);
  }
}
async function moveItemRules(ctx,item,next) {
  const from=item.data.custodianId, to=next.data.custodianId;
  const grants=(await ctx.service.s(`SELECT e.id,e.kind FROM cloud_links l JOIN cloud_entities e
    ON e.owner_id=l.owner_id AND e.continuity_id=l.continuity_id AND e.id=l.from_id
    WHERE l.owner_id=? AND l.continuity_id=? AND l.to_id=? AND l.relation='requires_item'
    AND e.life='active' AND e.kind IN('attack','effect','action_definition')
    AND json_extract(e.data_json,'$.transfersWithItem')=1 ORDER BY e.id LIMIT 65`,...ctx.binding,item.id).all()).results;
  if(grants.length>64) recordsFail('ITEM_GRANT_REVIEW_LIMIT',409);
  const poolId=item.data.resourcePoolId;
  if(!grants.length && !poolId) return;
  if(poolId){
    // Past expenditure and rest receipts keep their original actor. Active unrelated
    // consumers need explicit review before a physical item can take this pool away.
    const dependency=await ctx.service.s(`SELECT 1 AS present FROM cloud_links l JOIN cloud_entities e
      ON e.owner_id=l.owner_id AND e.continuity_id=l.continuity_id AND e.id=l.from_id
      WHERE l.owner_id=? AND l.continuity_id=? AND l.to_id=? AND l.from_id<>?
        AND l.relation IN('charge_pool','uses_pool') AND e.life='active'
        AND l.from_id NOT IN(SELECT value FROM json_each(?)) LIMIT 1`,
      ...ctx.binding,poolId,item.id,JSON.stringify(grants.map(row=>row.id))).first();
    if(dependency) recordsFail('SHARED_CHARGE_POOL_TRANSFER_REVIEW_REQUIRED',409);
  }
  const records=[];
  for(const grant of grants) {
    const row=await recordRead(ctx,grant.kind,grant.id);
    if(row.data.formId || row.data.excludedForms?.length || row.data.sourceItemId!==item.id ||
        (row.data.cost && row.data.cost.poolId!==poolId)) recordsFail('ITEM_GRANT_TRANSFER_REVIEW_REQUIRED',409);
    records.push(row);
  }
  if(poolId){
    const pool=await recordRead(ctx,'resource_pool',poolId);
    if(pool.data.type!=='charge') recordsFail('CHARGE_POOL_TRANSFER_REVIEW_REQUIRED',409);
    records.push(pool);
  }
  if(records.some(record=>record.actorId!==from)) recordsFail('ITEM_GRANT_CUSTODY_MISMATCH',409);
  const effectIds=grants.filter(row=>row.kind==='effect').map(row=>row.id);
  const poolIds=poolId?[poolId]:[];
  for(const [actorId,attach] of [[from,false],[to,true]]) {
    if(actorId===null || (!effectIds.length && !poolIds.length)) continue;
    const actor=await partyActor(ctx,actorId);
    const data={...actor.data};
    for(const [key,ids] of [['effectIds',effectIds],['poolIds',poolIds]]){
      if(!ids.length) continue;
      const previous=actor.data[key];
      if(!Array.isArray(previous) || ids.some(id=>previous.includes(id)===attach))
        recordsFail('ITEM_GRANT_ACTOR_INDEX_MISMATCH',409);
      const updated=attach?[...previous,...ids]:previous.filter(id=>!ids.includes(id));
      if(updated.length>128) recordsFail('ACTOR_MECHANIC_CAPACITY',409);
      data[key]=updated;
    }
    await ctx.put(recordEdit(actor,data));
  }
  for(const record of records){
    await ctx.put(recordEdit(record,record.data,{actorId:to}));
    if(from!==null) await ctx.unlink(record.id,'actor',from);
    if(to!==null) await ctx.link(record.id,'actor',to);
  }
}
function validateDestination(d) {
  const outside = ['external_gift','external_sale','lost','dropped'].includes(d.type);
  if (['party_actor','loan'].includes(d.type) !== (d.actorId !== null) ||
      (d.type === 'storage' && d.locationId === null) ||
      (!outside && d.type !== 'storage' && d.locationId !== null) ||
      (!['external_gift','external_sale'].includes(d.type) && (d.recipientId !== null || d.recipientName !== null))) recordsFail('INVALID_DESTINATION');
}
async function moveItem(ctx,args,eventType = 'transfer') {
  validateDestination(args.destination);
  const item = await recordRead(ctx,'item_instance',args.itemId);
  usable(item);
  if (item.data.quantity === null || item.data.quantity < args.quantity) recordsFail('INSUFFICIENT_KNOWN_QUANTITY',409);
  const partial = args.quantity < item.data.quantity;
  if (partial !== (args.splitId !== null)) recordsFail('PARTIAL_TRANSFER_REQUIRES_NEW_ID');
  const sourceItem = partial ? await divide(ctx,item,args.quantity,args.splitId) : item;
  const d = args.destination;
  if (d.actorId !== null) await partyActor(ctx,d.actorId);
  if (d.locationId !== null) {
    const place = await recordRead(ctx,'world_entity',d.locationId);
    if (place.data.category !== 'place' || (d.type === 'storage' && !place.data.partyStorage)) recordsFail('STORAGE_LOCATION_REQUIRED',409);
  }
  if (d.recipientId !== null) await recordRead(ctx,'world_entity',d.recipientId);
  const external = ['external_gift','external_sale','lost','dropped'].includes(d.type);
  const holding = external ? 'external' : d.type === 'storage' ? 'storage' : d.type === 'unassigned' ? 'unassigned' : 'carried';
  const ownerId = d.type === 'party_actor' ? d.actorId : ['external_gift','external_sale'].includes(d.type) ? null : sourceItem.data.ownerId;
  const custodianId = ['party_actor','loan'].includes(d.type) ? d.actorId : null;
  const scopeActor = external || (custodianId && ownerId && custodianId !== ownerId) ? null : (custodianId ?? ownerId);
  if (d.actorId !== null) await ctx.require('inventory',d.actorId,true);
  const data = { ...sourceItem.data, ownerId, custodianId, locationId:d.locationId, holding,
    partyAccess:!external, equipment:'packed', attuned:false, operating:false, source:args.source,
    externalRecipientId:d.recipientId, externalRecipientName:d.recipientName,
    lastPossessorId:sourceItem.data.custodianId, disposition:d.type };
  const moved = recordEdit(sourceItem,data,{ actorId:scopeActor,life:external?'archived':'active' });
  if (d.type === 'party_actor' && sourceItem.data.holding === 'carried' && sourceItem.data.ownerId === d.actorId && sourceItem.data.custodianId === d.actorId) recordsFail('ITEM_ALREADY_HELD',409);
  await guardForm(ctx,sourceItem,moved);
  await applyPlacement(ctx,sourceItem,moved);
  if (d.recipientId !== null) await ctx.link(moved.id,'external_recipient',d.recipientId);
  const eventId = await itemEvent(ctx,eventType,sourceItem,moved,args.reason,args.source,[item.id]);
  return { itemId:moved.id, fromItemId:item.id, quantity:args.quantity, holding, itemEventId:eventId };
}

async function guardForm(ctx, item, next) {
  if (!item.data.custodianId) return;
  const actor = await partyActor(ctx,item.data.custodianId);
  if (!actor.data.activeFormId) return;
  const form = await recordRead(ctx,'form',actor.data.activeFormId);
  if (form.data.sourceItemId === item.id && (item.data.custodianId !== next.data.custodianId || next.data.equipment === 'packed' ||
      (item.data.operating && !next.data.operating) || (form.data.requiresAttunement && !next.data.attuned))) recordsFail('FORM_REVERSION_REQUIRED',409);
}
export const HOLDING_OPERATIONS = Object.freeze({
  record_historical_item_event:recordsOperation('inventory',historicalItemEvent,recordHistoricalItemEvent),
  define_wallet: recordsOperation('economy',defineWallet,createWallet),
  transfer_coins: recordsOperation('economy',transferCoins,async (ctx,args) => {
    if (args.fromWalletId === args.toWalletId) recordsFail('DISTINCT_WALLETS_REQUIRED');
    assertPositive(args.coins);
    const from = await walletRecord(ctx,args.fromWalletId);
    const to = await walletRecord(ctx,args.toWalletId);
    return recordPayment(ctx,'transfer',[{wallet:from,next:changedCoins(from.data.coins,debit(args.coins))},
      {wallet:to,next:changedCoins(to.data.coins,args.coins)}],args.reason,args.source);
  }),
  adjust_coins: recordsOperation('economy',adjustCoins,async (ctx,args) => {
    assertPositive(args.change);
    const wallet = await walletRecord(ctx,args.walletId);
    return recordPayment(ctx,'adjustment',[{wallet,next:changedCoins(wallet.data.coins,args.change)}],args.reason,args.source);
  }),
  exchange_coins: recordsOperation('economy',exchangeCoins,async (ctx,args) => {
    assertPositive(args.give); assertPositive(args.receive);
    if (denominations.some(key => args.give[key] && args.receive[key]) || coinSummary(args.give).knownCopper !== coinSummary(args.receive).knownCopper)
      recordsFail('EXCHANGE_VALUE_MISMATCH');
    const wallet = await walletRecord(ctx,args.walletId);
    const next = changedCoins(changedCoins(wallet.data.coins,debit(args.give)),args.receive);
    return recordPayment(ctx,'exchange',[{wallet,next}],args.reason,args.source);
  }),
  define_item_definition: recordsOperation('inventory',definition,async (ctx,args) => {
    const {definitionId,name,...data} = args;
    if (data.stackable && data.attunement !== 'none') recordsFail('STACK_ATTUNEMENT_INCOMPATIBLE');
    await recordNew(ctx,'item_definition',definitionId);
    await ctx.put({ id:definitionId,kind:'item_definition',actorId:null,title:name,data:{...data,activation:data.activation??'passive',attunementSeconds:data.attunementSeconds??null} });
    return {definitionId};
  }),
  edit_item_definition: recordsOperation('inventory',recordObject({definitionId:recordId,name:recordTitle,description:recordText,
    category:recordTitle,rarity:recordMaybe(recordTitle),source:recordSource}),async(ctx,args)=>{
    const def=await recordRead(ctx,'item_definition',args.definitionId);
    const {definitionId,name,...changes}=args;
    await ctx.put(recordEdit(def,{...def.data,...changes},{title:name}));
    // Instances resolve display from the shared definition unless explicitly customized.
    return {definitionId,appliesTo:'shared_definition'};
  }),
  customize_item: recordsOperation('inventory',recordObject({itemId:recordId,name:recordTitle,description:recordText,condition:recordTitle,source:recordSource}),async(ctx,args)=>{
    const item=await recordRead(ctx,'item_instance',args.itemId);
    usable(item);
    if(item.data.quantity!==1) recordsFail('CUSTOMIZE_SINGLE_INSTANCE_REQUIRED',409);
    const changed=recordEdit(item,{...item.data,customized:true,description:args.description,condition:args.condition,source:args.source},{title:args.name});
    await ctx.put(changed);
    return {itemId:item.id,itemEventId:await itemEvent(ctx,'customization',item,changed,'Customization',args.source)};
  }),
  set_item_favorite: recordsOperation('inventory',recordObject({itemId:recordId,favorite:recordBool}),async(ctx,args)=>{
    const item=await recordRead(ctx,'item_instance',args.itemId);
    usable(item);
    if(item.data.favorite===args.favorite) recordsFail('FAVORITE_ALREADY_SET',409);
    await ctx.put(recordEdit(item,{...item.data,favorite:args.favorite}));
    return {itemId:item.id,favorite:args.favorite};
  }),
  acquire_item: recordsOperation('inventory',acquireItem,async (ctx,args) => {
    const item = await outputItem(ctx,args);
    return {itemId:item.id,itemEventId:await itemEvent(ctx,'acquisition',null,item,'Acquisition',args.source),quantity:args.quantity};
  }),
  purchase_item: recordsOperation('economy',purchaseItem,buy),
  sell_item: recordsOperation('economy',sellItem,async(ctx,args)=>{
    const wallet=await walletRecord(ctx,args.walletId);
    const item=await recordRead(ctx,'item_instance',args.itemId);
    if (wallet.actorId !== null && wallet.actorId !== item.data.ownerId) recordsFail('SALE_WALLET_HOLDER_MISMATCH',409);
    assertPositive(args.proceeds);
    const next=changedCoins(wallet.data.coins,args.proceeds);
    const moved=await moveItem(ctx,{...args,reason:'Sale',destination:{type:'external_sale',actorId:null,
      locationId:null,recipientId:args.buyerId,recipientName:args.buyerName}},'sale');
    const payment=await recordPayment(ctx,'sale',[{wallet,next}],'Sale',args.source);
    await ctx.link(moved.itemEventId,'payment',payment.transactionRecordId);
    return {...moved,...payment};
  }),
  transfer_item: recordsOperation('inventory',transferItem,moveItem),
  split_stack: recordsOperation('inventory',splitItem,async (ctx,args) => {
    const item = await recordRead(ctx,'item_instance',args.itemId);
    const child = await divide(ctx,item,args.quantity,args.splitId);
    return { parentId:item.id, childId:child.id, quantity:args.quantity, remaining:item.data.quantity-args.quantity,
      itemEventId:await itemEvent(ctx,'split',item,child,'Split stack',args.source,[item.id]) };
  }),
  merge_stacks: recordsOperation('inventory',mergeItem,async (ctx,args) => {
    if (args.keepId === args.mergeId) recordsFail('DISTINCT_STACKS_REQUIRED');
    const first = await recordRead(ctx,'item_instance',args.keepId);
    const other = await recordRead(ctx,'item_instance',args.mergeId);
    const def = await definitionForHolding(ctx,first);
    portable(first,def); portable(other,def);
    const quantity = first.data.quantity + other.data.quantity;
    if (!compareStack(first,other) || quantity > 1_000_000) recordsFail('STACK_INCOMPATIBLE',409);
    const result = recordEdit(first,{...first.data,quantity});
    await ctx.put(result);
    await ctx.put(recordEdit(other,{...other.data,quantity:0,partyAccess:false,holding:'merged',mergedInto:first.id},{life:'archived'}));
    await ctx.link(other.id,'merged_into',first.id);
    return {itemId:first.id,absorbedId:other.id,quantity,itemEventId:await itemEvent(ctx,'merge',first,result,'Merge stacks',args.source,[other.id])};
  }),
  recover_item: recordsOperation('inventory',recoverItem,async (ctx,args) => {
    const item = await recordRead(ctx,'item_instance',args.itemId,true);
    if (item.data.holding !== 'external' || item.data.partyAccess || !(item.data.quantity > 0)) recordsFail('EXTERNAL_HOLDING_REQUIRED',409);
    await partyActor(ctx,args.ownerId);
    const result = recordEdit(item,{...item.data,ownerId:args.ownerId,custodianId:args.ownerId,locationId:null,holding:'carried',
      partyAccess:true,equipment:'packed',attuned:false,operating:false,source:args.source,externalRecipientId:null,
      externalRecipientName:null,disposition:'recovered'}, {actorId:args.ownerId,life:'active'});
    await applyPlacement(ctx,item,result);
    if (item.data.externalRecipientId) await ctx.unlink(item.id,'external_recipient',item.data.externalRecipientId);
    return {itemId:item.id,itemEventId:await itemEvent(ctx,'recovery',item,result,args.reason,args.source)};
  }),
  set_item_attunement: recordsOperation('inventory',recordObject({itemId:recordId,attuned:recordBool,elapsedSeconds:recordInt(0,604800),
    confirmed:recordBool,source:recordSource}),async(ctx,args)=>{
    const item=await recordRead(ctx,'item_instance',args.itemId);
    usable(item);
    if(!args.confirmed || item.data.holding!=='carried' || item.data.quantity!==1 || !item.data.custodianId) recordsFail('CONFIRMED_ATTUNEMENT_REQUIRED',409);
    const actor=await partyActor(ctx,item.data.custodianId);
    const def=await definitionForHolding(ctx,item);
    if(def.data.attunement!=='required' || def.data.attunementSeconds==null || args.elapsedSeconds<def.data.attunementSeconds || actor.data.encounterId)
      recordsFail('ATTUNEMENT_PREREQUISITES',409);
    if(item.data.attuned===args.attuned) recordsFail('ATTUNEMENT_ALREADY_SET',409);
    if(args.attuned){
      if(!Number.isSafeInteger(actor.data.attunementCapacity)) recordsFail('ATTUNEMENT_LIMIT_UNKNOWN',409);
      ctx.require('inventory',actor.id,false);
      const {count}=await ctx.service.s(`SELECT count(*) AS count FROM cloud_entities WHERE owner_id=? AND continuity_id=?
        AND kind='item_instance' AND life='active' AND json_extract(data_json,'$.custodianId')=?
        AND json_extract(data_json,'$.partyAccess')=1 AND json_extract(data_json,'$.attuned')=1`,...ctx.binding,actor.id).first();
      if(count>=actor.data.attunementCapacity) recordsFail('ATTUNEMENT_CAPACITY',409);
    }
    const result=recordEdit(item,{...item.data,attuned:args.attuned,operating:args.attuned?item.data.operating:false,source:args.source});
    await guardForm(ctx,item,result);await ctx.put(result);
    return {itemId:item.id,attuned:args.attuned,itemEventId:await itemEvent(ctx,'attunement',item,result,'Attunement change',args.source)};
  }),
  set_item_operating: recordsOperation('inventory',recordObject({itemId:recordId,operating:recordBool,source:recordSource}),async(ctx,args)=>{
    const item=await recordRead(ctx,'item_instance',args.itemId);
    usable(item);
    const def=await definitionForHolding(ctx,item);
    if(def.data.activation!=='manual' || item.data.holding!=='carried' || item.data.quantity!==1 || !item.data.custodianId ||
       (args.operating && (item.data.equipment==='packed'|| (def.data.attunement==='required'&&!item.data.attuned)))) recordsFail('ACTIVATION_PREREQUISITES',409);
    if(args.operating===item.data.operating) recordsFail('ACTIVATION_ALREADY_SET',409);
    const result=recordEdit(item,{...item.data,operating:args.operating,source:args.source});
    await guardForm(ctx,item,result);await ctx.put(result);
    return {itemId:item.id,operating:args.operating,itemEventId:await itemEvent(ctx,'activation',item,result,'Operating state',args.source)};
  }),
  bind_item_charge_pool: recordsOperation('inventory',recordObject({itemId:recordId,poolId:recordId,source:recordSource}),async(ctx,args)=>{
    const item=await recordRead(ctx,'item_instance',args.itemId);usable(item);
    const pool=await recordRead(ctx,'resource_pool',args.poolId);
    if(item.data.quantity!==1 || item.data.holding!=='carried' || item.data.resourcePoolId || pool.actorId!==item.data.custodianId ||
        pool.data.type!=='charge') recordsFail('CHARGE_POOL_REVIEW_REQUIRED',409);
    const result=recordEdit(item,{...item.data,resourcePoolId:pool.id,source:args.source});
    await ctx.put(result);await ctx.link(item.id,'charge_pool',pool.id);
    return {itemId:item.id,poolId:pool.id};
  }),
  equip_item: recordsOperation('inventory',equipItem,async (ctx,args) => {
    const item = await recordRead(ctx,'item_instance',args.itemId);
    usable(item);
    if (item.data.holding !== 'carried' || item.data.custodianId === null || item.data.quantity !== 1) recordsFail('SINGLE_CARRIED_ITEM_REQUIRED',409);
    const def = await definitionForHolding(ctx,item);
    if ((args.equipment === 'held' && !def.data.handheld) || (args.equipment === 'worn' && !def.data.wearable) ||
        def.data.attunement === 'configured_review') recordsFail('EQUIPMENT_REVIEW_REQUIRED',409);
    if (item.data.equipment === args.equipment) recordsFail('EQUIPMENT_ALREADY_SET',409);
    const result = recordEdit(item,{...item.data,equipment:args.equipment,operating:args.equipment==='packed'?false:item.data.operating,source:args.source});
    await guardForm(ctx,item,result);
    await ctx.put(result);
    return {itemId:item.id,equipment:args.equipment,itemEventId:await itemEvent(ctx,'equipment',item,result,'Equipment change',args.source)};
  }),
});

export const projectPaymentCoins = coins;
export async function payForProject(ctx, project, walletId, payment, reason, source) {
  const wallet=await walletRecord(ctx,walletId);
  if(wallet.actorId !== null && wallet.actorId !== project.actorId) recordsFail('PROJECT_WALLET_HOLDER_MISMATCH',409);
  assertPositive(payment);
  const result=await recordPayment(ctx,'construction',[{wallet,next:changedCoins(wallet.data.coins,debit(payment))}],reason,source);
  await ctx.link(result.transactionRecordId,'construction_project',project.id);
  return result;
}
