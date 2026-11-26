import { recordsFail, recordObject, recordId, recordText, recordSource, recordTitle, recordChoice, recordMaybe, recordInt, recordArray, recordsOperation, recordRead, recordNew, recordEdit, partyActor, uniqueRecordIds } from './cloud-records-contract.mjs';

import { payForProject, projectPaymentCoins } from './cloud-holdings.mjs';

const maybeId = recordMaybe(recordId);
const maybeText = recordMaybe(recordText);
const research = recordObject({ researchId: recordId, title: recordTitle, actorId: maybeId, finding: recordText,
  evidence: recordChoice('confirmed','partial','failed','unknown','hypothesis'), itemId: maybeId, rollId: maybeId, source: recordSource });
const project = recordObject({ projectId: recordId, title: recordTitle, actorId: maybeId, intent: recordText,
  requirements: recordArray(recordTitle,30), researchIds: recordArray(recordId,16), source: recordSource });
const blueprint = recordObject({ blueprintId: recordId, projectId: recordId, title: recordTitle, specification: recordText,
  supersedesId: maybeId, source: recordSource });
const stage = recordObject({ stageId: recordId, projectId: recordId, blueprintId: maybeId, title: recordTitle,
  prerequisites: recordArray(recordId,16), requiredWork: maybeText, checkRequired: { type: 'boolean' }, source: recordSource });
const progress = recordObject({ stageId: recordId, outcome: recordChoice('active','completed','failed','partial'),
  rollId: maybeId, worldTimeSpent: maybeText, evidence: recordText, source: recordSource });
const component = recordObject({ eventId: recordId, projectId: recordId, stageId: recordId, donorItemId: recordId, outputItemId: recordId,
  method: recordChoice('installed','incorporated','material_consumed'), quantity: recordInt(1,1_000_000), evidence: recordText, source: recordSource });
const finish = recordObject({ projectId: recordId, outputItemId: recordId, stageIds: recordArray(recordId,30,1), source: recordSource });

async function resolvedRoll(ctx,id,actorId) {
  const roll=await recordRead(ctx,'roll',id);
  if(roll.data.status!=='resolved' || (actorId!==null && roll.actorId!==actorId)) recordsFail('RESOLVED_ACTOR_ROLL_REQUIRED',409);
  return roll;
}
async function openProject(ctx,id) {
  const value=await recordRead(ctx,'invention_project',id);
  if(value.data.status==='completed') recordsFail('PROJECT_ALREADY_COMPLETED',409);
  return value;
}
async function sameProject(ctx,kind,id,projectId) {
  const row=await recordRead(ctx,kind,id);
  if(row.data.projectId!==projectId) recordsFail('PROJECT_DEPENDENCY_MISMATCH',409);
  return row;
}
async function integrate(ctx,args) {
  await recordNew(ctx,'component_event',args.eventId);
  const target=await openProject(ctx,args.projectId);
  const stage=await sameProject(ctx,'project_stage',args.stageId,target.id);
  if(stage.data.status!=='completed') recordsFail('CONFIRMED_STAGE_REQUIRED',409);
  if(args.donorItemId===args.outputItemId) recordsFail('DISTINCT_ITEMS_REQUIRED');
  const donor=await recordRead(ctx,'item_instance',args.donorItemId);
  const output=await recordRead(ctx,'item_instance',args.outputItemId);
  for(const row of [donor,output]) {
    if(!row.data.partyAccess || !['carried','storage','unassigned'].includes(row.data.holding) ||
       row.data.equipment!=='packed' || row.data.attuned || row.data.operating) recordsFail('COMPONENT_DEPENDENCY_REVIEW_REQUIRED',409);
  }
  if(output.data.quantity!==1 || output.data.ownerId!==target.actorId || donor.data.ownerId!==target.actorId ||
     !Number.isSafeInteger(donor.data.quantity) || args.quantity>donor.data.quantity ||
     (args.method!=='material_consumed' && (donor.data.quantity!==1 || args.quantity!==1))) recordsFail('COMPONENT_QUANTITY_OR_OWNER_CONFLICT',409);
  if(donor.data.resourcePoolId!==null || donor.data.customized) recordsFail('CUSTOM_COMPONENT_REVIEW_REQUIRED',409);
  // The existing graph is authoritative across every holder. A narrowed grant cannot establish acyclicity.
  await ctx.require('crafting',null,false);
  const events=await ctx.list('component_event');
  const parents=new Map();
  for(const row of events){
    const set=parents.get(row.data.outputItemId)??new Set();
    set.add(row.data.donorItemId);parents.set(row.data.outputItemId,set);
  }
  const queue=[donor.id],seen=new Set();
  while(queue.length){
    const id=queue.pop();
    if(id===output.id) recordsFail('COMPONENT_CYCLE',409);
    if(seen.has(id))continue;
    seen.add(id);queue.push(...(parents.get(id)??[]));
  }
  // A whole assembly keeps its earlier component links. Consuming its materials would need an explicit disposition for those children.
  if(parents.has(donor.id) && args.method==='material_consumed') recordsFail('ASSEMBLY_CONSUMPTION_REVIEW_REQUIRED',409);
  const definition=await recordRead(ctx,'item_definition',donor.data.definitionId);
  const quantity=args.method==='material_consumed'?donor.data.quantity-args.quantity:donor.data.quantity;
  const unavailable=args.method!=='material_consumed'||quantity===0;
  const holding=args.method==='material_consumed'?'consumed':args.method;
  const next=recordEdit(donor,unavailable?{...donor.data,partyAccess:false,holding,quantity,custodianId:null,locationId:null,
    equipment:'packed',attuned:false,operating:false}:{...donor.data,quantity});
  await ctx.put(next);
  if(unavailable && donor.data.custodianId) await ctx.unlink(donor.id,'held_by',donor.data.custodianId);
  if(unavailable && donor.data.locationId) await ctx.unlink(donor.id,'located_at',donor.data.locationId);
  await ctx.put({id:args.eventId,kind:'component_event',actorId:target.actorId,title:args.method,data:{
    projectId:target.id,stageId:stage.id,donorItemId:donor.id,outputItemId:output.id,
    donorRevision:donor.revision,definitionId:definition.id,definitionRevision:definition.revision,
    outputRevision:output.revision,quantity:args.quantity,beforeQuantity:donor.data.quantity,afterQuantity:quantity,
    method:args.method,evidence:args.evidence,source:args.source,commandId:ctx.commandId,recordedAt:ctx.now}});
  for(const [relation,id] of [['project',target.id],['resolved_stage',stage.id],['source_component',donor.id],['resulting_item',output.id],['source_definition',definition.id]])
    await ctx.link(args.eventId,relation,id);
  return {componentEventId:args.eventId,donorItemId:donor.id,outputItemId:output.id,remaining:quantity,independentlyAvailable:!unavailable};
}
export const CRAFT_OPERATIONS=Object.freeze({
  record_research:recordsOperation('crafting',research,async(ctx,args)=>{
    await recordNew(ctx,'research_note',args.researchId);
    if(args.actorId) await partyActor(ctx,args.actorId);
    if(args.itemId) await recordRead(ctx,'item_instance',args.itemId);
    if(args.rollId) await resolvedRoll(ctx,args.rollId,args.actorId);
    await ctx.put({id:args.researchId,kind:'research_note',actorId:args.actorId,title:args.title,data:{
      finding:args.finding,evidence:args.evidence,recordedAt:ctx.now,source:args.source}});
    if(args.itemId) await ctx.link(args.researchId,'item_studied',args.itemId);
    if(args.rollId) await ctx.link(args.researchId,'research_roll',args.rollId);
    return {researchId:args.researchId,evidence:args.evidence};
  }),
  propose_invention:recordsOperation('crafting',project,async(ctx,args)=>{
    await recordNew(ctx,'invention_project',args.projectId);
    if(args.actorId) await partyActor(ctx,args.actorId);
    uniqueRecordIds(args.researchIds);
    for(const id of args.researchIds) await recordRead(ctx,'research_note',id);
    await ctx.put({id:args.projectId,kind:'invention_project',actorId:args.actorId,title:args.title,
      data:{intent:args.intent,requirements:args.requirements,status:'proposed',source:args.source,recordedAt:ctx.now}});
    for(const id of args.researchIds) await ctx.link(args.projectId,'research_basis',id);
    return {projectId:args.projectId,status:'proposed'};
  }),
  record_blueprint_version:recordsOperation('crafting',blueprint,async(ctx,args)=>{
    await recordNew(ctx,'blueprint_version',args.blueprintId);
    const proj=await openProject(ctx,args.projectId);
    if(args.supersedesId) await sameProject(ctx,'blueprint_version',args.supersedesId,proj.id);
    await ctx.put({id:args.blueprintId,kind:'blueprint_version',actorId:proj.actorId,title:args.title,
      data:{projectId:proj.id,specification:args.specification,source:args.source,recordedAt:ctx.now}});
    await ctx.link(args.blueprintId,'design_for',proj.id);
    if(args.supersedesId) await ctx.link(args.blueprintId,'supersedes_version',args.supersedesId);
    return {blueprintId:args.blueprintId};
  }),
  define_construction_stage:recordsOperation('crafting',stage,async(ctx,args)=>{
    await recordNew(ctx,'project_stage',args.stageId);
    const proj=await openProject(ctx,args.projectId);
    if(args.blueprintId) await sameProject(ctx,'blueprint_version',args.blueprintId,proj.id);
    uniqueRecordIds(args.prerequisites);
    for(const id of args.prerequisites) await sameProject(ctx,'project_stage',id,proj.id);
    await ctx.put({id:args.stageId,kind:'project_stage',actorId:proj.actorId,title:args.title,data:{projectId:proj.id,prerequisites:args.prerequisites,
      requiredWork:args.requiredWork,checkRequired:args.checkRequired,status:'planned',source:args.source}});
    await ctx.link(args.stageId,'stage_of',proj.id);
    if(args.blueprintId) await ctx.link(args.stageId,'blueprint',args.blueprintId);
    for(const id of args.prerequisites) await ctx.link(args.stageId,'requires_stage',id);
    return {stageId:args.stageId,status:'planned'};
  }),
  resolve_construction_stage:recordsOperation('crafting',progress,async(ctx,args)=>{
    const current=await recordRead(ctx,'project_stage',args.stageId);
    const proj=await openProject(ctx,current.data.projectId);
    if(current.data.status==='completed') recordsFail('STAGE_ALREADY_COMPLETED',409);
    for(const id of current.data.prerequisites) {
      if((await sameProject(ctx,'project_stage',id,proj.id)).data.status!=='completed') recordsFail('UNFINISHED_PREREQUISITE',409);
    }
    if(current.data.checkRequired && args.outcome!=='active' && !args.rollId) recordsFail('ACTUAL_ROLL_REQUIRED',409);
    if(args.rollId) await resolvedRoll(ctx,args.rollId,current.actorId);
    await ctx.put(recordEdit(current,{...current.data,status:args.outcome,worldTimeSpent:args.worldTimeSpent,evidence:args.evidence,source:args.source}));
    if(proj.data.status==='proposed') await ctx.put(recordEdit(proj,{...proj.data,status:'active'}));
    if(args.rollId) await ctx.link(current.id,'adjudicated_roll',args.rollId);
    return {stageId:current.id,status:args.outcome,rollId:args.rollId};
  }),
  pay_construction_cost:recordsOperation('crafting',recordObject({projectId:recordId,walletId:recordId,coins:projectPaymentCoins,
    reason:recordTitle,source:recordSource}),async(ctx,args)=>{
    const proj=await openProject(ctx,args.projectId);
    await ctx.require('crafting',proj.actorId,true);
    return payForProject(ctx,proj,args.walletId,args.coins,args.reason,args.source);
  }),
  integrate_component:recordsOperation('crafting',component,integrate),
  link_completed_invention:recordsOperation('crafting',finish,async(ctx,args)=>{
    const proj=await openProject(ctx,args.projectId);
    uniqueRecordIds(args.stageIds);
    const stages=(await ctx.list('project_stage')).filter(row=>row.data.projectId===proj.id);
    if(stages.length!==args.stageIds.length || stages.some(r=>r.life!=='active'||r.data.status!=='completed'||!args.stageIds.includes(r.id)))
      recordsFail('COMPLETE_STAGE_EVIDENCE_REQUIRED',409);
    const output=await recordRead(ctx,'item_instance',args.outputItemId);
    if(!output.data.partyAccess || output.data.ownerId!==proj.actorId || output.data.quantity!==1) recordsFail('AVAILABLE_OUTPUT_REQUIRED',409);
    await ctx.put(recordEdit(proj,{...proj.data,status:'completed',outputId:output.id,source:args.source}));
    await ctx.link(proj.id,'completed_output',output.id);
    return {projectId:proj.id,itemId:output.id,status:'completed'};
  }),
});
