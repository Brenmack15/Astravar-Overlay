import {partyActor, recordEdit, recordId, recordObject, recordRead, recordSource, recordsFail, recordsOperation} from './cloud-records-contract.mjs';

const placement = recordObject({actorId:recordId, itemId:recordId, containerItemId:recordId, source:recordSource});

export function clearStorageReturn(data) {
  const {approvedStorageReturnId, approvedStorageReturnQuantity, ...rest} = data;
  return rest;
}

export async function requireEmptyIntegratedStorage(ctx, item) {
  if (!item.data.integratedStorageProjectId) return;
  const child = await ctx.service.s(`SELECT 1 AS present FROM cloud_entities
    WHERE owner_id=? AND continuity_id=? AND kind='item_instance' AND life='active'
    AND json_extract(data_json,'$.containerItemId')=? LIMIT 1`, ...ctx.binding, item.id).first();
  if (child) recordsFail('INTEGRATED_STORAGE_HAS_CONTENTS',409);
}

export function integratedStorageOperations(itemEvent) {
  async function change(ctx, args, stowing) {
    const {actorId,itemId,containerItemId} = args;
    ctx.require('inventory',actorId,true);
    await partyActor(ctx,actorId);
    if (itemId === containerItemId) recordsFail('INTEGRATED_STORAGE_CYCLE',409);
    const item = await recordRead(ctx,'item_instance',itemId);
    const host = await recordRead(ctx,'item_instance',containerItemId);
    const d = item.data, h = host.data;
    if (!h.integratedStorageProjectId || h.integratedStorageOperatorId !== actorId ||
        h.custodianId !== actorId || h.holding !== 'carried' || !h.partyAccess ||
        !h.operating || h.quantity !== 1 || h.containerItemId || h.locationId)
      recordsFail('INTEGRATED_STORAGE_UNAVAILABLE',409);
    const project = await recordRead(ctx,'invention_project',h.integratedStorageProjectId);
    if (project.data.status !== 'completed') recordsFail('COMPLETED_STORAGE_MODULE_REQUIRED',409);
    if (d.custodianId !== actorId || !d.partyAccess || d.equipment !== 'packed' || d.locationId ||
        d.installedOnActorId || d.covenantLocked || d.integratedStorageProjectId || !Number.isSafeInteger(d.quantity) || d.quantity <= 0)
      recordsFail('INTEGRATED_STORAGE_ITEM_INELIGIBLE',409);
    let data;
    if (stowing) {
      // A reviewed placement can be restored after withdrawal. It confers no capacity
      // for new purchases, growing stacks, or a different extradimensional container.
      if (d.holding !== 'carried' || d.containerItemId || d.extradimensional === true ||
          d.approvedStorageReturnId !== host.id || !Number.isSafeInteger(d.approvedStorageReturnQuantity) ||
          d.quantity > d.approvedStorageReturnQuantity)
        recordsFail('INTEGRATED_STORAGE_PLACEMENT_UNCONFIRMED',409);
      data = {...clearStorageReturn(d), holding:'storage', containerItemId:host.id};
    } else {
      if (d.holding !== 'storage' || d.containerItemId !== host.id)
        recordsFail('ITEM_NOT_IN_INTEGRATED_STORAGE',409);
      const {containerItemId:prior, ...rest} = d;
      data = {...rest, holding:'carried', approvedStorageReturnId:prior, approvedStorageReturnQuantity:d.quantity};
    }
    const next = recordEdit(item,data);
    await ctx.put(next);
    if (stowing) await ctx.link(item.id,'stored_in',host.id);
    else await ctx.unlink(item.id,'stored_in',host.id);
    const reason = stowing ? 'Storage in integrated container' : 'Retrieval from integrated container';
    const eventId = await itemEvent(ctx,stowing?'storage':'retrieval',item,next,reason,args.source);
    return {itemId,containerItemId,holding:data.holding,itemEventId:eventId};
  }
  return {
    store_in_integrated_storage:recordsOperation('inventory',placement,(ctx,args)=>change(ctx,args,true)),
    withdraw_from_integrated_storage:recordsOperation('inventory',placement,(ctx,args)=>change(ctx,args,false))
  };
}
