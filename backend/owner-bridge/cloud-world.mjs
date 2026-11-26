import { recordsFail, recordObject, recordId, recordText, recordSource, recordTitle, recordChoice, recordMaybe, recordInt, recordArray, recordsOperation, recordRead, recordNew, recordEdit, partyActor, uniqueRecordIds, RECORD_DOMAIN_KINDS } from './cloud-records-contract.mjs';

const maybeId = recordMaybe(recordId);
const maybeText = recordMaybe(recordText);
const worldKinds = recordChoice('person','being','faction','place','organization');
const credence = recordChoice('confirmed','rumor','interpretation','disputed','unknown');
const allowedTargets = [...Object.keys(RECORD_DOMAIN_KINDS),'actor','form','effect','resource_pool','roll','roll_batch',
  'encounter','attack','attack_attempt','action_definition','action_use','rest_report','spell','spell_grant','cast_instance'];
const subject = recordObject({ kind: recordChoice(...allowedTargets), id: recordId });
const subjectList = recordArray(subject, 12);
const worldFields = { entityId: recordId, name: recordTitle, category: worldKinds,
  actorId: maybeId, knownDescription: maybeText, source: recordSource };
const defineWorld = recordObject({ ...worldFields, partyStorage: {type:'boolean'} },Object.keys(worldFields));
const relateWorld = recordObject({ relationshipId: recordId, from: subject, to: subject, relationship: recordTitle,
  terms: maybeText, status: recordChoice('current','ended','disputed'), source: recordSource });
const fact = recordObject({ factId: recordId, subject, title: recordTitle, detail: recordText, evidence: credence,
  learnedAt: maybeText, originalWorldTime: maybeText, source: recordSource });
const chronicle = recordObject({ eventId: recordId, title: recordTitle, narrative: recordText,
  category: recordChoice('decision','conversation','agreement','discovery','research','travel','encounter','relationship','objective','advancement','crafting','other'),
  status: recordChoice('confirmed','planned','rumor','unresolved'), worldTime: maybeText, subjects: subjectList,
  correctsId: maybeId, source: recordSource });
const objectiveStatus = recordChoice('proposed','active','paused','completed','failed','abandoned');
const goal = recordObject({ objectiveId: recordId, title: recordTitle, actorId: maybeId, category: recordChoice('main','side','personal','party','companion','research','construction'),
  status: objectiveStatus, knownRequirements: recordArray(recordTitle,16), linkedProjectId: maybeId, source: recordSource });
const milestone = recordObject({ objectiveId: recordId, eventId: recordId, status: objectiveStatus, source: recordSource });
const checkpointFields = { checkpointId: recordId, title: recordTitle, scene: recordText, locationId: maybeId, worldTime: maybeText,
  lastEventId: maybeId, unresolved: maybeText, pendingRollId: maybeId, encounterId: maybeId, objectiveIds: recordArray(recordId,16),
  actorIds: recordArray(recordId,16), source: recordSource };
const lastEventKind=recordChoice('timeline_event','item_event','coin_transaction','party_exchange','component_event','rest_report');
const checkpoint = recordObject({ ...checkpointFields, pendingRollPrompt: maybeText, lastEventKind }, Object.keys(checkpointFields));
const expectedRevision=recordInt(1,Number.MAX_SAFE_INTEGER);

async function reference(ctx, value) {
  return recordRead(ctx, value.kind, value.id);
}
async function worldRecord(ctx,args,updating) {
  if (args.partyStorage && args.category !== 'place') recordsFail('PLACE_REQUIRED');
  if (args.actorId !== null && !['person','being'].includes(args.category)) recordsFail('ACTOR_LINK_INCOMPATIBLE');
  if (args.actorId !== null) await recordRead(ctx,'actor',args.actorId);
  const before = updating ? await recordRead(ctx,'world_entity',args.entityId) : null;
  if (!updating) await recordNew(ctx,'world_entity',args.entityId);
  if (before && (before.actorId !== args.actorId || before.data.category !== args.category)) recordsFail('WORLD_IDENTITY_CONFLICT',409);
  const partyStorage=args.partyStorage??before?.data.partyStorage??false;
  if(before?.data.partyStorage && !partyStorage){
    const occupied=await ctx.service.s(`SELECT 1 AS present FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND kind='item_instance'
      AND json_extract(data_json,'$.holding')='storage' AND json_extract(data_json,'$.locationId')=? LIMIT 1`,...ctx.binding,before.id).first();
    if(occupied)recordsFail('STORAGE_DEPENDENCY_REVIEW_REQUIRED',409);
  }
  await ctx.put({id:args.entityId,kind:'world_entity',actorId:args.actorId,title:args.name,
    data:{category:args.category,knownDescription:args.knownDescription,partyStorage,source:args.source}});
  if(args.actorId !== null) await ctx.link(args.entityId,'represents_actor',args.actorId);
  return {worldEntityId:args.entityId};
}
async function relationship(ctx,args,update) {
  if(args.from.id === args.to.id) recordsFail('DISTINCT_SUBJECTS_REQUIRED');
  const first=await reference(ctx,args.from), second=await reference(ctx,args.to);
  const before=update?await recordRead(ctx,'relationship',args.relationshipId):null;
  if(!update) await recordNew(ctx,'relationship',args.relationshipId);
  // Endpoint identity is part of the relationship. Corrections to endpoints create a new sourced relationship.
  if(before && (before.data.fromId!==first.id || before.data.toId!==second.id)) recordsFail('RELATIONSHIP_ENDPOINT_CONFLICT',409);
  const actorId = first.actorId === second.actorId ? first.actorId : null;
  await ctx.put({id:args.relationshipId,kind:'relationship',actorId,title:args.relationship,
    data:{fromId:first.id,toId:second.id,terms:args.terms,status:args.status,source:args.source}});
  await ctx.link(args.relationshipId,'from_subject',first.id);
  await ctx.link(args.relationshipId,'to_subject',second.id);
  return {relationshipId:args.relationshipId,status:args.status};
}
async function recordTimeline(ctx,args) {
  await recordNew(ctx,'timeline_event',args.eventId);
  uniqueRecordIds(args.subjects.map(s=>s.id));
  const subjects=[];
  for(const value of args.subjects) subjects.push(await reference(ctx,value));
  if(args.correctsId) subjects.push(await recordRead(ctx,'timeline_event',args.correctsId));
  const scopes=new Set(subjects.map(row=>row.actorId));
  const actorId=scopes.size === 1?subjects[0].actorId:null;
  await ctx.put({id:args.eventId,kind:'timeline_event',actorId,title:args.title,data:{
    category:args.category,status:args.status,narrative:args.narrative,worldTime:args.worldTime,source:args.source,
    recordedAt:ctx.now,commandId:ctx.commandId }});
  for(const sub of args.subjects) await ctx.link(args.eventId,'event_subject',sub.id);
  if(args.correctsId) await ctx.link(args.eventId,'corrects',args.correctsId);
  return {eventId:args.eventId,status:args.status,correctsId:args.correctsId};
}
async function checkpointRecord(ctx,args) {
  await recordNew(ctx,'checkpoint',args.checkpointId);
  uniqueRecordIds(args.actorIds); uniqueRecordIds(args.objectiveIds);
  if(args.locationId) {
    const loc=await recordRead(ctx,'world_entity',args.locationId);
    if(loc.data.category!=='place') recordsFail('PLACE_REQUIRED');
  }
  if(args.lastEventKind&&!args.lastEventId) recordsFail('LAST_EVENT_REQUIRED',409);
  if(args.lastEventId) {
    const last=await recordRead(ctx,args.lastEventKind??'timeline_event',args.lastEventId);
    if((last.kind==='timeline_event' && last.data.status!=='confirmed') || (last.kind==='item_event' && last.data.evidence==='uncertain')) recordsFail('CONFIRMED_EVENT_REQUIRED',409);
  }
  if(args.encounterId && (await recordRead(ctx,'encounter',args.encounterId)).data.active!==true) recordsFail('ACTIVE_ENCOUNTER_REQUIRED',409);
  if(args.pendingRollId) {
    const roll=await recordRead(ctx,'roll',args.pendingRollId);
    if(roll.data.status!=='pending') recordsFail('ROLL_ALREADY_RESOLVED',409);
  }
  for(const actor of args.actorIds) await partyActor(ctx,actor);
  for(const id of args.objectiveIds) await recordRead(ctx,'objective',id);
  await ctx.put({id:args.checkpointId,kind:'checkpoint',actorId:null,title:args.title,data:{
    scene:args.scene,worldTime:args.worldTime,unresolved:args.unresolved,pendingRollPrompt:args.pendingRollPrompt??null,
    source:args.source,recordedAt:ctx.now,commandId:ctx.commandId }});
  for(const [relation,ids] of [['scene_location',[args.locationId]],['last_event',[args.lastEventId]],
      ['pending_roll',[args.pendingRollId]],['active_encounter',[args.encounterId]],['objective',args.objectiveIds],['party_actor',args.actorIds]])
    for(const id of ids) if(id) await ctx.link(args.checkpointId,relation,id);
  return {checkpointId:args.checkpointId,unresolved:args.unresolved};
}
export const WORLD_OPERATIONS=Object.freeze({
  define_connection:recordsOperation('world',defineWorld,(ctx,a)=>worldRecord(ctx,a,false)),
  edit_connection:recordsOperation('world',defineWorld,(ctx,a)=>worldRecord(ctx,a,true)),
  record_relationship:recordsOperation('world',relateWorld,(ctx,a)=>relationship(ctx,a,false)),
  update_relationship:recordsOperation('world',relateWorld,(ctx,a)=>relationship(ctx,a,true)),
  record_known_fact:recordsOperation('world',fact,async(ctx,args)=>{
    await recordNew(ctx,'known_fact',args.factId);
    const about=await reference(ctx,args.subject);
    await ctx.put({id:args.factId,kind:'known_fact',actorId:about.actorId,title:args.title,data:{
      detail:args.detail,evidence:args.evidence,learnedAt:args.learnedAt,originalWorldTime:args.originalWorldTime,
      recordedAt:ctx.now,source:args.source}});
    await ctx.link(args.factId,'known_about',about.id);
    return {factId:args.factId,evidence:args.evidence};
  }),
  revise_known_fact:recordsOperation('world',recordObject({factId:recordId,expectedRevision,title:recordTitle,detail:recordText,
    evidence:credence,learnedAt:maybeText,originalWorldTime:maybeText,reason:recordTitle,source:recordSource}),async(ctx,args)=>{
    const current=await recordRead(ctx,'known_fact',args.factId);
    if(current.revision!==args.expectedRevision) recordsFail('FACT_REVISION_CHANGED',409);
    const {factId,title,expectedRevision,reason,...changes}=args;
    await ctx.put(recordEdit(current,{...current.data,...changes,revisionReason:reason,revisedAt:ctx.now},{title}));
    return {factId,previousRevision:expectedRevision,reason};
  }),
  record_chronicle_event:recordsOperation('chronicle',chronicle,recordTimeline),
  define_objective:recordsOperation('chronicle',goal,async(ctx,args)=>{
    await recordNew(ctx,'objective',args.objectiveId);
    if(args.actorId) await partyActor(ctx,args.actorId);
    if(args.linkedProjectId) await recordRead(ctx,'invention_project',args.linkedProjectId);
    // Completion of an existing objective needs a dated, confirmed milestone.
    if(!['proposed','active'].includes(args.status)) recordsFail('OBJECTIVE_INITIAL_STATUS');
    await ctx.put({id:args.objectiveId,kind:'objective',actorId:args.actorId,title:args.title,data:{
      category:args.category,status:args.status,knownRequirements:args.knownRequirements,source:args.source,recordedAt:ctx.now}});
    if(args.actorId) await ctx.link(args.objectiveId,'assigned_actor',args.actorId);
    if(args.linkedProjectId) await ctx.link(args.objectiveId,'linked_project',args.linkedProjectId);
    return {objectiveId:args.objectiveId};
  }),
  revise_objective:recordsOperation('chronicle',recordObject({objectiveId:recordId,expectedRevision,title:recordTitle,
    knownRequirements:recordArray(recordTitle,16),reason:recordTitle,source:recordSource}),async(ctx,args)=>{
    const current=await recordRead(ctx,'objective',args.objectiveId);
    if(current.revision!==args.expectedRevision) recordsFail('OBJECTIVE_REVISION_CHANGED',409);
    await ctx.put(recordEdit(current,{...current.data,knownRequirements:args.knownRequirements,source:args.source,
      revisionReason:args.reason,revisedAt:ctx.now},{title:args.title}));
    return {objectiveId:current.id,status:current.data.status,reason:args.reason};
  }),
  record_objective_milestone:recordsOperation('chronicle',milestone,async(ctx,args)=>{
    const goal=await recordRead(ctx,'objective',args.objectiveId),event=await recordRead(ctx,'timeline_event',args.eventId);
    if(event.data.status!=='confirmed' || goal.data.lastMilestoneId===event.id) recordsFail('CONFIRMED_NEW_MILESTONE_REQUIRED',409);
    const previous=await ctx.service.s("SELECT 1 AS existing FROM cloud_links WHERE owner_id=? AND continuity_id=? AND from_id=? AND relation='milestone' AND to_id=?",
      ...ctx.binding,goal.id,event.id).first();
    if(previous) recordsFail('MILESTONE_ALREADY_RECORDED',409);
    await ctx.put(recordEdit(goal,{...goal.data,status:args.status,lastMilestoneId:event.id,source:args.source}));
    await ctx.link(goal.id,'milestone',event.id);
    return {objectiveId:goal.id,eventId:event.id,status:args.status};
  }),
  save_checkpoint:recordsOperation('chronicle',checkpoint,checkpointRecord),
});
