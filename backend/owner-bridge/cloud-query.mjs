import { checkpointChronology } from './cloud-chronology.mjs';
import { cloudAllowed, cloudEntity } from './cloud-plan.mjs';
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
  if(!grant.scopes.includes('chronicle.read')||actorId&&(!validCloudId(actorId)||!grant.actors.includes('*')&&!grant.actors.includes(actorId))||sinceRevision!==null&&(!Number.isSafeInteger(sinceRevision)||sinceRevision<0||sinceRevision>c.revision))cloudFail(403,'CONTEXT_SCOPE_DENIED');
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
  const chronology=await checkpointChronology(service,b,grant,latest?.id);
  await confirm(service,principal,id,opened);
  return {continuityId:id,chronology,epoch:c.epoch,revision:c.revision,checkpoint:latest?cloudEntity(latest):null,checkpointRevision:latest?.cloud_revision??null,
    actors,objectives,changesSince:after,recentChanges:changes.slice(0,25),moreChanges:changes.length>25,grantRevision:grant.revision,nextChangeAfter:changes.length>25?changes[24].revision+'~'+changes[24].ordinal:null};
}
function checkPage(opened,options) {
  const {grant,continuity:c}=opened,limit=options.limit??50;
  if(!Number.isSafeInteger(limit)||limit<1||limit>100)cloudFail(400,'INVALID_PAGE');
  if(options.after && [options.atEpoch,options.atRevision,options.grantRevision].some(x=>!Number.isSafeInteger(x)))cloudFail(400,'PAGE_BASELINE_REQUIRED');
  if(options.atEpoch!==undefined&&options.atEpoch!==c.epoch || options.atRevision!==undefined&&options.atRevision!==c.revision ||
      options.grantRevision!==undefined&&options.grantRevision!==grant.revision)cloudFail(409,'SNAPSHOT_CHANGED');
  return limit;
}

export async function cloudChanges(service,principal,id,options={}) {
  const opened=await service.access(principal,id),{grant,continuity:c}=opened,limit=checkPage(opened,options);
  const {actorId,sinceRevision=0,after}=options, prev=visible(service,grant,'h'),current=visible(service,grant,'e');
  if(!grant.scopes.includes('chronicle.read')||actorId&&(!validCloudId(actorId)||!grant.actors.includes('*')&&!grant.actors.includes(actorId)))cloudFail(403,'CONTEXT_SCOPE_DENIED');
  if(!Number.isSafeInteger(sinceRevision)||sinceRevision<0||sinceRevision>c.revision)cloudFail(400,'INVALID_BASELINE');
  const pair=after?.split('~');
  if(after&&(pair.length!==2||pair.some(n=>!/^(0|[1-9][0-9]{0,15})$/.test(n)||!Number.isSafeInteger(Number(n)))))cloudFail(400,'INVALID_CURSOR');
  const rows=(await service.s(`SELECT h.revision,h.ordinal,h.entity_id,h.kind,h.change_type FROM cloud_changes h
    JOIN cloud_entities e ON e.owner_id=h.owner_id AND e.continuity_id=h.continuity_id AND e.id=h.entity_id
    WHERE h.owner_id=? AND h.continuity_id=? AND e.life='active' AND h.revision>? AND ${prev.sql} AND ${current.sql}
    ${actorId?'AND (h.actor_id=? OR h.actor_id IS NULL)':''}
    ${after?'AND (h.revision,h.ordinal)<(?,?)':''} ORDER BY h.revision DESC,h.ordinal DESC LIMIT ?`,
    ...service.key(id),sinceRevision,...prev.values,...current.values,...(actorId?[actorId]:[]),...(after?pair.map(Number):[]),limit+1).all()).results;
  await confirm(service,principal,id,opened);
  return {epoch:c.epoch,revision:c.revision,grantRevision:grant.revision,changes:rows.slice(0,limit),
    nextAfter:rows.length>limit?rows[limit-1].revision+'~'+rows[limit-1].ordinal:null};
}

export async function cloudRelationships(service,principal,id,entityId,options={}) {
  if(!validCloudId(entityId))cloudFail(400,'INVALID_ENTITY_ID');
  const opened=await service.access(principal,id),{grant,continuity:c}=opened,limit=checkPage(opened,options),b=service.key(id);
  const vis=visible(service,grant,'a'),other=visible(service,grant,'z'),after=options.after,tuple=after?.split('~');
  if(after&&(tuple.length!==3||!validCloudId(tuple[0])||!/^[a-z0-9_]{1,80}$/.test(tuple[1])||!validCloudId(tuple[2])))cloudFail(400,'INVALID_CURSOR');
  const rows=(await service.s(`SELECT l.from_id,l.relation,l.to_id FROM cloud_links l
    JOIN cloud_entities a ON a.owner_id=l.owner_id AND a.continuity_id=l.continuity_id AND a.id=l.from_id
    JOIN cloud_entities z ON z.owner_id=l.owner_id AND z.continuity_id=l.continuity_id AND z.id=l.to_id
    WHERE l.owner_id=? AND l.continuity_id=? AND (l.from_id=? OR l.to_id=?) AND ${vis.sql} AND ${other.sql}
    AND a.life='active' AND z.life='active' ${after?'AND (l.from_id,l.relation,l.to_id)>(?,?,?)':''}
    ORDER BY l.from_id,l.relation,l.to_id LIMIT ?`,...b,entityId,entityId,...vis.values,...other.values,...(after?tuple:[]),limit+1).all()).results;
  await confirm(service,principal,id,opened);
  const links=rows.slice(0,limit),tail=links.at(-1);
  return {epoch:c.epoch,revision:c.revision,grantRevision:grant.revision,links,truncated:rows.length>limit,
    nextAfter:rows.length>limit?[tail.from_id,tail.relation,tail.to_id].join('~'):null};
}

/** A pool ledger is readable at resource scope. Disclose transaction identity only
 * when the reader also holds every scope on that receipt. */
export async function cloudPoolHistory(service, principal, id, poolId, options = {}) {
  if (!validCloudId(poolId)) cloudFail(400, 'INVALID_POOL_ID');
  const opened = await service.access(principal, id);
  const { grant, continuity } = opened, limit = checkPage(opened, options);
  const cursor = options.after;
  if (cursor && (!/^[1-9][0-9]{0,15}$/.test(cursor) || !Number.isSafeInteger(Number(cursor)))) cloudFail(400, 'INVALID_CURSOR');
  const binding = service.key(id);
  const pool = await service.s("SELECT actor_id FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id=? AND kind='resource_pool' AND life='active'", ...binding, poolId).first();
  if (!pool || !cloudAllowed(grant, 'resources', pool.actor_id)) cloudFail(404, 'POOL_UNAVAILABLE');
  const rows = (await service.s(`SELECT h.revision, r.command_id, r.transaction_id, r.created_at, r.access_json,
      json_extract(q.request_json,'$.command.operation') AS operation,
      (s.payload_json IS NOT NULL) AS has_evidence,
      json_extract(s.payload_json,'$.before.data.current') AS before_balance,
      json_extract(s.payload_json,'$.after.data.current') AS after_balance
    FROM cloud_changes h JOIN cloud_receipts r ON (r.owner_id,r.continuity_id,r.revision)=(h.owner_id,h.continuity_id,h.revision)
    JOIN cloud_requests q ON (q.owner_id,q.continuity_id,q.command_id)=(r.owner_id,r.continuity_id,r.command_id)
    LEFT JOIN cloud_evidence_segments s ON (s.owner_id,s.continuity_id,s.command_id,s.part)=(r.owner_id,r.continuity_id,r.command_id,'c'||h.ordinal)
    WHERE h.owner_id=? AND h.continuity_id=? AND h.entity_id=? AND h.actor_id=? AND r.status='COMMITTED'
      AND h.kind='resource_pool' ${cursor ? 'AND h.revision<?' : ''}
    ORDER BY h.revision DESC LIMIT ?`, ...binding, poolId, pool.actor_id, ...(cursor ? [Number(cursor)] : []), limit + 1).all()).results;
  const events = rows.slice(0, limit).map(row => {
    const access = JSON.parse(row.access_json);
    const receiptVisible = access.length > 0 && access.every(e => cloudAllowed(grant, e.scope, e.actorId));
    return { revision: row.revision, recordedAt: row.created_at, before: row.before_balance, after: row.after_balance,
      evidenceAvailable: Boolean(row.has_evidence),
      ...(receiptVisible ? { commandId: row.command_id, transactionId: row.transaction_id, operation: row.operation } : {}) };
  });
  await confirm(service, principal, id, opened);
  return { continuityId: id, poolId, epoch: continuity.epoch, revision: continuity.revision,
    grantRevision: grant.revision, events, nextAfter: rows.length > limit ? String(events.at(-1).revision) : null };
}
