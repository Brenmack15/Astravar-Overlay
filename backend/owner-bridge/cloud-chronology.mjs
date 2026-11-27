import {
  recordsFail, recordObject, recordId, recordText, recordTitle, recordSource, recordChoice, recordMaybe,
  recordInt, recordArray, recordsOperation, recordRead, recordNew, uniqueRecordIds
} from './cloud-records-contract.mjs';

const nullableId=recordMaybe(recordId), optionalText=recordMaybe(recordText);
const base={
  title:recordTitle,summary:optionalText,state:recordChoice('planned','active','complete','unresolved'),
  worldStart:optionalText,worldEnd:optionalText,eventIds:recordArray(recordId,64),source:recordSource
};
const arc={...base,arcId:recordId,classification:recordChoice('adventure','story_arc'),parentArcId:nullableId};
const session={...base,sessionId:recordId,arcId:nullableId,previousSessionId:nullableId,playedOn:optionalText,
  openingCheckpointId:nullableId,closingCheckpointId:nullableId};
const revision=recordInt(1,Number.MAX_SAFE_INTEGER);
const relationships={
  parentArcId:['part_of_arc','story_arc'],
  previousSessionId:['follows_session','campaign_session'],
  arcId:['session_in_arc','story_arc'],
  openingCheckpointId:['opens_at_checkpoint','checkpoint'],
  closingCheckpointId:['closes_at_checkpoint','checkpoint']
};

function links(data,kind){
  const result=(data.eventIds??[]).map(id=>({type:'includes_event',kind:'timeline_event',id}));
  const fields=kind==='story_arc'?['parentArcId']:['previousSessionId','arcId','openingCheckpointId','closingCheckpointId'];
  for(const field of fields)if(data[field]){
    const [type,targetKind]=relationships[field];
    result.push({type,kind:targetKind,id:data[field]});
  }
  return result;
}
async function checkAncestry(ctx,id,edge){
  if(!['part_of_arc','follows_session'].includes(edge.type))return;
  if(edge.id===id)recordsFail('CHRONOLOGY_CYCLE',409);
  // UNION terminates even if an imported dataset already contains a cycle.
  const found=await ctx.service.s(`WITH RECURSIVE ancestry(id) AS (VALUES (?)
    UNION SELECT l.to_id FROM cloud_links l JOIN ancestry a ON l.from_id=a.id
    WHERE l.owner_id=? AND l.continuity_id=? AND l.relation=?) SELECT 1 AS matched FROM ancestry WHERE id=? LIMIT 1`,
    edge.id,...ctx.binding,edge.type,id).first();
  if(found)recordsFail('CHRONOLOGY_CYCLE',409);
}
function temporalOperation(kind,properties,idField,editing){
  const schema=recordObject(editing?{...properties,expectedRevision:revision,reason:recordTitle}:properties);
  return recordsOperation('chronicle',schema,async(ctx,args)=>{
    const id=args[idField];
    const prior=editing?await recordRead(ctx,kind,id):null;
    if(prior && prior.revision!==args.expectedRevision)recordsFail('CHRONOLOGY_REVISION_CHANGED',409);
    if(!prior)await recordNew(ctx,kind,id);
    uniqueRecordIds(args.eventIds);
    // World dates and session dates are supplied independently of recording time and may remain unknown.
    const {[idField]:ignored,title,expectedRevision,reason,...values}=args;
    const currentTime=ctx.now;
    const data={...values,recordedAt:prior?.data.recordedAt??currentTime,lastRecordedAt:currentTime};
    const next=links(data,kind),previous=links(prior?.data??{},kind);
    const matches=(a,b)=>a.id===b.id && a.type===b.type;
    for(const edge of next){
      await recordRead(ctx,edge.kind,edge.id,true);
      await checkAncestry(ctx,id,edge);
    }
    const updated=await ctx.put({id,kind,actorId:null,life:'active',title,data});
    for(const before of previous)if(!next.some(now=>matches(before,now)))await ctx.unlink(id,before.type,before.id);
    for(const now of next)if(!previous.some(before=>matches(before,now)))await ctx.link(id,now.type,now.id);
    return {id,kind,revision:updated.revision,priorRevision:expectedRevision??null,reason:reason??null,links:next};
  });
}
export const CHRONOLOGY_OPERATIONS=Object.freeze({
  define_story_arc:temporalOperation('story_arc',arc,'arcId',false),
  revise_story_arc:temporalOperation('story_arc',arc,'arcId',true),
  record_campaign_session:temporalOperation('campaign_session',session,'sessionId',false),
  revise_campaign_session:temporalOperation('campaign_session',session,'sessionId',true)
});

/** The handoff contains only calendar records explicitly linked to the recovered checkpoint. */
export async function checkpointChronology(service,binding,grant,checkpointId){
  if(!checkpointId || !grant.scopes.includes('chronicle.read') || !grant.actors.includes('*'))
    return {sessions:[],storyArcs:[],truncated:false};
  const rows=(await service.s(`SELECT DISTINCT e.id,e.title,e.revision,e.data_json FROM cloud_links l JOIN cloud_entities e
    ON e.owner_id=l.owner_id AND e.continuity_id=l.continuity_id AND e.id=l.from_id
    WHERE l.owner_id=? AND l.continuity_id=? AND l.to_id=? AND l.relation IN ('opens_at_checkpoint','closes_at_checkpoint')
      AND e.kind='campaign_session' AND e.life='active' AND e.actor_id IS NULL
    ORDER BY json_extract(e.data_json,'$.lastRecordedAt') DESC,e.id LIMIT 9`,...binding,checkpointId).all()).results;
  const sessions=rows.slice(0,8).map(row=>{
    const data=JSON.parse(row.data_json);
    return {id:row.id,title:row.title,revision:row.revision,arcId:data.arcId,previousSessionId:data.previousSessionId,
      state:data.state,playedOn:data.playedOn,worldStart:data.worldStart,worldEnd:data.worldEnd,source:data.source};
  });
  const ids=[...new Set(sessions.map(row=>row.arcId).filter(Boolean))];
  const arcs=ids.length?(await service.s(`SELECT id,title,revision,data_json FROM cloud_entities WHERE owner_id=? AND continuity_id=?
    AND kind='story_arc' AND actor_id IS NULL AND life='active' AND id IN(SELECT value FROM json_each(?)) ORDER BY id`,...binding,JSON.stringify(ids)).all()).results:[];
  const storyArcs=arcs.map(row=>{
    const data=JSON.parse(row.data_json);
    return {id:row.id,title:row.title,revision:row.revision,parentArcId:data.parentArcId,classification:data.classification,state:data.state,source:data.source};
  });
  return {sessions,storyArcs,truncated:rows.length>8};
}
