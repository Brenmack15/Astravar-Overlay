import { cloudEntity } from './cloud-plan.mjs';
import { cloudFail, validCloudId } from './cloud-validation.mjs';

function visible(service,grant,alias) {
  const kinds=Object.keys(service.kinds).filter(k=>grant.scopes.includes(service.kinds[k]+'.read'));
  const test=[alias+'.kind IN(SELECT value FROM json_each(?))'], values=[JSON.stringify(kinds)];
  if(!grant.actors.includes('*')){test.push(alias+'.actor_id IN(SELECT value FROM json_each(?))');values.push(JSON.stringify(grant.actors));}
  return {sql:test.join(' AND '),values};
}
async function confirm(service,principal,id,opened){
  const again=await service.access(principal,id);
  if(again.grant.revision!==opened.grant.revision||again.continuity.epoch!==opened.continuity.epoch||again.continuity.revision!==opened.continuity.revision)cloudFail(409,'SNAPSHOT_CHANGED');
}
export async function cloudContext(service,principal,id,{actorId=null,sinceRevision=null}={}) {
  const opened=await service.access(principal,id),{grant,continuity:c}=opened,b=service.key(id);
  if(!grant.scopes.includes('chronicle.read')||actorId&&!validCloudId(actorId)||sinceRevision!==null&&(!Number.isSafeInteger(sinceRevision)||sinceRevision<0||sinceRevision>c.revision))cloudFail(403,'CONTEXT_SCOPE_DENIED');
  const vis=visible(service,grant,'e');
  const read=(kind,limit)=>service.s(`SELECT e.*,min(h.revision) AS cloud_revision FROM cloud_entities e
    JOIN cloud_changes h ON h.owner_id=e.owner_id AND h.continuity_id=e.continuity_id AND h.entity_id=e.id
    WHERE e.owner_id=? AND e.continuity_id=? AND e.life='active' AND e.kind=? AND ${vis.sql}
    ${actorId?'AND (e.actor_id=? OR e.actor_id IS NULL)':''} GROUP BY e.id ORDER BY cloud_revision DESC,e.id LIMIT ?`,...b,kind,...vis.values,...(actorId?[actorId]:[]),limit).all();
  const latest=(await read('checkpoint',1)).results[0], after=sinceRevision??latest?.cloud_revision??0;
  const cv=visible(service,grant,'h');
  const changes=(await service.s(`SELECT h.revision,h.ordinal,h.entity_id,h.kind,h.change_type FROM cloud_changes h
    JOIN cloud_entities nowrow ON nowrow.owner_id=h.owner_id AND nowrow.continuity_id=h.continuity_id AND nowrow.id=h.entity_id
    WHERE nowrow.life='active' AND h.owner_id=? AND h.continuity_id=? AND h.revision>? AND ${cv.sql} AND ${visible(service,grant,'nowrow').sql}
    ${actorId?'AND (h.actor_id=? OR h.actor_id IS NULL)':''} ORDER BY h.revision DESC,h.ordinal DESC LIMIT 26`,
    ...b,after,...cv.values,...visible(service,grant,'nowrow').values,...(actorId?[actorId]:[])).all()).results;
  const actors=(await read('actor',16)).results.map(cloudEntity),objectives=(await read('objective',16)).results.map(cloudEntity);
  await confirm(service,principal,id,opened);
  return {continuityId:id,epoch:c.epoch,revision:c.revision,checkpoint:latest?cloudEntity(latest):null,checkpointRevision:latest?.cloud_revision??null,
    actors,objectives,changesSince:after,recentChanges:changes.slice(0,25),moreChanges:changes.length>25};
}
export async function cloudRelationships(service,principal,id,entityId) {
  if(!validCloudId(entityId))cloudFail(400,'INVALID_ENTITY_ID');
  const opened=await service.access(principal,id),{grant,continuity:c}=opened,b=service.key(id),vis=visible(service,grant,'a'),other=visible(service,grant,'z');
  const rows=(await service.s(`SELECT l.from_id,l.relation,l.to_id FROM cloud_links l
    JOIN cloud_entities a ON a.owner_id=l.owner_id AND a.continuity_id=l.continuity_id AND a.id=l.from_id
    JOIN cloud_entities z ON z.owner_id=l.owner_id AND z.continuity_id=l.continuity_id AND z.id=l.to_id
    WHERE l.owner_id=? AND l.continuity_id=? AND (l.from_id=? OR l.to_id=?) AND ${vis.sql} AND ${other.sql}
    AND a.life='active' AND z.life='active' ORDER BY l.from_id,l.relation,l.to_id LIMIT 101`,...b,entityId,entityId,...vis.values,...other.values).all()).results;
  await confirm(service,principal,id,opened);
  return {epoch:c.epoch,revision:c.revision,links:rows.slice(0,100),truncated:rows.length>100};
}
