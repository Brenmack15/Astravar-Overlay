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
const incorporationMethod=recordChoice('installed','incorporated','material_consumed');
const materialRequirement=recordObject({definitionId:recordId,quantity:recordInt(1,1_000_000),method:incorporationMethod});
const stageFields={ stageId: recordId, projectId: recordId, blueprintId: maybeId, title: recordTitle,
  prerequisites: recordArray(recordId,16), requiredWork: maybeText, checkRequired: { type: 'boolean' }, source: recordSource };
const stage=recordObject({...stageFields,materials:recordArray(materialRequirement,24),minimumPayment:projectPaymentCoins},Object.keys(stageFields));
const expectedRevision=recordInt(1,Number.MAX_SAFE_INTEGER);
const progress = recordObject({ stageId: recordId, outcome: recordChoice('active','completed','failed','partial'),
  rollId: maybeId, worldTimeSpent: maybeText, evidence: recordText, source: recordSource });
const component = recordObject({ eventId: recordId, projectId: recordId, stageId: recordId, donorItemId: recordId, outputItemId: recordId,
  method: incorporationMethod, quantity: recordInt(1,1_000_000), evidence: recordText, source: recordSource });
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
async function integratedQuantity(ctx,stageId,definitionId,method,outputId) {
  const row=await ctx.service.s(`WITH RECURSIVE lineage(id) AS (VALUES(?) UNION
    SELECT json_extract(c.data_json,'$.donorItemId') FROM lineage JOIN cloud_entities c ON c.owner_id=? AND c.continuity_id=?
    AND c.kind='component_event' AND json_extract(c.data_json,'$.outputItemId')=lineage.id)
    SELECT coalesce(sum(json_extract(data_json,'$.quantity')),0) AS quantity
    FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND kind='component_event' AND json_extract(data_json,'$.stageId')=?
      AND json_extract(data_json,'$.definitionId')=? AND json_extract(data_json,'$.method')=?
      AND (? IS NULL OR json_extract(data_json,'$.outputItemId') IN(SELECT id FROM lineage))`,
    outputId,...ctx.binding,...ctx.binding,stageId,definitionId,method,outputId).first();
  return row.quantity;
}
async function verifyStageRequirements(ctx,stage,outputId){
  for(const material of stage.data.materials??[]) {
    if((await integratedQuantity(ctx,stage.id,material.definitionId,material.method,outputId))!==material.quantity) recordsFail('STAGE_MATERIAL_EVIDENCE_REQUIRED',409);
  }
  const expected=stage.data.minimumPayment;
  if(!expected)return;
  const paid=(await ctx.service.s(`SELECT p.id FROM cloud_links l JOIN cloud_entities p
    ON p.owner_id=l.owner_id AND p.continuity_id=l.continuity_id AND p.id=l.from_id
    WHERE l.owner_id=? AND l.continuity_id=? AND l.to_id=? AND l.relation='paid_stage' AND p.kind='coin_transaction'
    ORDER BY p.id LIMIT 129`,...ctx.binding,stage.id).all()).results;
  if(paid.length>128) recordsFail('STAGE_PAYMENT_REVIEW_LIMIT',409);
  const totals={cp:0,sp:0,ep:0,gp:0,pp:0};
  for(const ref of paid) {
    const p=await recordRead(ctx,'coin_transaction',ref.id);
    if(p.data.type!=='construction' || !Array.isArray(p.data.lines) || p.data.lines.length!==1) recordsFail('STAGE_PAYMENT_REVIEW_REQUIRED',409);
    const entry=p.data.lines[0];
    for(const coin of Object.keys(totals))if(entry.before[coin]!==entry.after[coin])totals[coin]+=entry.before[coin]-entry.after[coin];
  }
  if(Object.keys(totals).some(k=>totals[k]<expected[k]))recordsFail('STAGE_PAYMENT_EVIDENCE_REQUIRED',409);
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
  const family=await ctx.service.s(`WITH RECURSIVE ancestors(id) AS (VALUES(?) UNION
    SELECT json_extract(e.data_json,'$.donorItemId') FROM ancestors a JOIN cloud_entities e
    ON e.owner_id=? AND e.continuity_id=? AND e.kind='component_event' AND json_extract(e.data_json,'$.outputItemId')=a.id)
    SELECT max(id=?) AS cycle,max(id<>?) AS hasChildren FROM ancestors`,donor.id,...ctx.binding,output.id,donor.id).first();
  if(family.cycle)recordsFail('COMPONENT_CYCLE',409);
  // A whole assembly keeps its earlier component links. Consuming its materials would need an explicit disposition for those children.
  if(family.hasChildren && args.method==='material_consumed') recordsFail('ASSEMBLY_CONSUMPTION_REVIEW_REQUIRED',409);
  const definition=await recordRead(ctx,'item_definition',donor.data.definitionId);
  if(stage.data.materials?.length){
    const requirement=stage.data.materials.find(m=>m.definitionId===definition.id && m.method===args.method);
    if(!requirement || args.quantity+await integratedQuantity(ctx,stage.id,definition.id,args.method,null)>requirement.quantity)
      recordsFail('STAGE_MATERIAL_REQUIREMENT_MISMATCH',409);
  }
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
  revise_research_finding:recordsOperation('crafting',recordObject({researchId:recordId,expectedRevision,title:recordTitle,
    finding:recordText,evidence:research.properties.evidence,reason:recordTitle,source:recordSource}),async(ctx,args)=>{
    const current=await recordRead(ctx,'research_note',args.researchId);
    if(current.revision!==args.expectedRevision) recordsFail('RESEARCH_REVISION_CHANGED',409);
    await ctx.put(recordEdit(current,{...current.data,finding:args.finding,evidence:args.evidence,source:args.source,
      revisionReason:args.reason,revisedAt:ctx.now},{title:args.title}));
    return {researchId:current.id,reason:args.reason};
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
  revise_invention_plan:recordsOperation('crafting',recordObject({projectId:recordId,expectedRevision,title:recordTitle,intent:recordText,
    requirements:recordArray(recordTitle,30),additionalResearchIds:recordArray(recordId,16),reason:recordTitle,source:recordSource}),async(ctx,args)=>{
    const current=await openProject(ctx,args.projectId);
    if(current.revision!==args.expectedRevision)recordsFail('PROJECT_REVISION_CHANGED',409);
    uniqueRecordIds(args.additionalResearchIds);
    for(const id of args.additionalResearchIds){await recordRead(ctx,'research_note',id);await ctx.link(current.id,'research_basis',id);}
    await ctx.put(recordEdit(current,{...current.data,intent:args.intent,requirements:args.requirements,source:args.source,
      revisionReason:args.reason,revisedAt:ctx.now},{title:args.title}));
    return {projectId:current.id,status:current.data.status,reason:args.reason};
  }),
  link_project_dependency:recordsOperation('crafting',recordObject({projectId:recordId,requiredProjectId:recordId,source:recordSource}),async(ctx,args)=>{
    const project=await openProject(ctx,args.projectId);
    const required=await recordRead(ctx,'invention_project',args.requiredProjectId);
    const result=await ctx.service.s(`WITH RECURSIVE upstream(id) AS (VALUES(?) UNION SELECT l.to_id FROM upstream
      JOIN cloud_links l ON l.owner_id=? AND l.continuity_id=? AND l.relation='requires_project' AND l.from_id=upstream.id)
      SELECT EXISTS(SELECT 1 FROM upstream WHERE id=?) AS cycle,
      EXISTS(SELECT 1 FROM cloud_links WHERE owner_id=? AND continuity_id=? AND from_id=? AND relation='requires_project' AND to_id=?) AS linked`,
      required.id,...ctx.binding,project.id,...ctx.binding,project.id,required.id).first();
    if(result.cycle || result.linked) recordsFail('PROJECT_DEPENDENCY_CONFLICT',409);
    await ctx.link(project.id,'requires_project',required.id);
    return {projectId:project.id,requiredProjectId:required.id,source:args.source};
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
    const materials=args.materials??[];
    uniqueRecordIds(materials.map(m=>JSON.stringify([m.definitionId,m.method])));
    for(const m of materials)await recordRead(ctx,'item_definition',m.definitionId);
    await ctx.put({id:args.stageId,kind:'project_stage',actorId:proj.actorId,title:args.title,data:{projectId:proj.id,prerequisites:args.prerequisites,
      requiredWork:args.requiredWork,checkRequired:args.checkRequired,materials,minimumPayment:args.minimumPayment??null,status:'planned',source:args.source}});
    await ctx.link(args.stageId,'stage_of',proj.id);
    if(args.blueprintId) await ctx.link(args.stageId,'blueprint',args.blueprintId);
    for(const id of args.prerequisites) await ctx.link(args.stageId,'requires_stage',id);
    for(const id of new Set(materials.map(m=>m.definitionId)))await ctx.link(args.stageId,'requires_material',id);
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
    reason:recordTitle,source:recordSource,stageId:recordId},['projectId','walletId','coins','reason','source']),async(ctx,args)=>{
    const proj=await openProject(ctx,args.projectId);
    if(args.stageId)await sameProject(ctx,'project_stage',args.stageId,proj.id);
    await ctx.require('crafting',proj.actorId,true);
    const paid=await payForProject(ctx,proj,args.walletId,args.coins,args.reason,args.source);
    if(args.stageId)await ctx.link(paid.transactionRecordId,'paid_stage',args.stageId);
    return paid;
  }),
  integrate_component:recordsOperation('crafting',component,integrate),
  link_completed_invention:recordsOperation('crafting',finish,async(ctx,args)=>{
    const proj=await openProject(ctx,args.projectId);
    uniqueRecordIds(args.stageIds);
    const found=(await ctx.service.s(`SELECT id FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND kind='project_stage'
      AND json_extract(data_json,'$.projectId')=? ORDER BY id LIMIT 31`,...ctx.binding,proj.id).all()).results;
    if(found.length!==args.stageIds.length) recordsFail('COMPLETE_STAGE_EVIDENCE_REQUIRED',409);
    const stages=[];
    for(const row of found)stages.push(await recordRead(ctx,'project_stage',row.id));
    if(stages.length!==args.stageIds.length || stages.some(r=>r.life!=='active'||r.data.status!=='completed'||!args.stageIds.includes(r.id)))
      recordsFail('COMPLETE_STAGE_EVIDENCE_REQUIRED',409);
    const output=await recordRead(ctx,'item_instance',args.outputItemId);
    if(!output.data.partyAccess || output.data.ownerId!==proj.actorId || output.data.quantity!==1) recordsFail('AVAILABLE_OUTPUT_REQUIRED',409);
    const dependencies=(await ctx.service.s("SELECT to_id FROM cloud_links WHERE owner_id=? AND continuity_id=? AND from_id=? AND relation='requires_project' LIMIT 129",
      ...ctx.binding,proj.id).all()).results;
    if(dependencies.length>128)recordsFail('PROJECT_DEPENDENCY_REVIEW_LIMIT',409);
    for(const ref of dependencies)if((await recordRead(ctx,'invention_project',ref.to_id)).data.status!=='completed') recordsFail('INCOMPLETE_PROJECT_DEPENDENCY',409);
    const disconnected=await ctx.service.s(`WITH RECURSIVE lineage(id) AS (VALUES(?) UNION
      SELECT json_extract(e.data_json,'$.donorItemId') FROM lineage a JOIN cloud_entities e ON e.owner_id=? AND e.continuity_id=?
      AND e.kind='component_event' AND json_extract(e.data_json,'$.outputItemId')=a.id)
      SELECT 1 AS mismatched FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND kind='component_event'
      AND json_extract(data_json,'$.projectId')=? AND json_extract(data_json,'$.outputItemId') NOT IN(SELECT id FROM lineage) LIMIT 1`,
      output.id,...ctx.binding,...ctx.binding,proj.id).first();
    if(disconnected)recordsFail('PROJECT_OUTPUT_LINEAGE_MISMATCH',409);
    for(const stage of stages)await verifyStageRequirements(ctx,stage,output.id);
    await ctx.put(recordEdit(proj,{...proj.data,status:'completed',outputId:output.id,source:args.source}));
    await ctx.link(proj.id,'completed_output',output.id);
    return {projectId:proj.id,itemId:output.id,status:'completed'};
  }),
});
