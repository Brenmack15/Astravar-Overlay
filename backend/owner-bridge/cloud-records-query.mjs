import { cloudAllowed, cloudEntity } from './cloud-plan.mjs';
import { cloudFail, validCloudId } from './cloud-validation.mjs';

const views=['inventory','wallets','item_history','timeline','genealogy'];
function requireScope(grant,scope) {
  if(!grant.scopes.includes(scope+'.read'))cloudFail(403,'CLOUD_GRANT_DENIED');
}
function options(input) {
  const allowed=['id','query','after','limit','atRevision','atEpoch','grantRevision'];
  if(!input || typeof input!=='object' || Object.keys(input).some(key=>!allowed.includes(key))) cloudFail(400,'INVALID_RECORD_QUERY');
  const limit=input.limit??25,query=input.query??'',after=input.after??'';
  if(!Number.isSafeInteger(limit)||limit<1||limit>100||typeof query!=='string'||query.length>120||
      typeof after!=='string'||after.length>180||(input.id!=null&&!validCloudId(input.id))) cloudFail(400,'INVALID_RECORD_QUERY');
  if(after && [input.atRevision,input.atEpoch,input.grantRevision].some(n=>!Number.isSafeInteger(n))) cloudFail(400,'PAGE_BASELINE_REQUIRED');
  return {...input,limit,query,after};
}
function actorRule(grant,alias) {
  return grant.actors.includes('*')?{sql:'1=1',values:[]}:
    {sql:alias+'.actor_id IN(SELECT value FROM json_each(?))',values:[JSON.stringify(grant.actors)]};
}
function fullParty(grant,scope) {
  requireScope(grant,scope);requireScope(grant,'character');
  if(!grant.actors.includes('*'))cloudFail(403,'PARTY_AGGREGATE_SCOPE_REQUIRED');
}
async function subject(service,b,grant,id,expected) {
  if(!validCloudId(id))cloudFail(400,'SUBJECT_REQUIRED');
  const row=await service.s('SELECT * FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id=?',...b,id).first();
  if(!row || (expected&&row.kind!==expected) || !cloudAllowed(grant,service.kinds[row.kind],row.actor_id) ||
      (row.life!=='active'&&!cloudAllowed(grant,'lifecycle',row.actor_id)))cloudFail(404,'RECORD_UNAVAILABLE');
  return row;
}
const partyActors=`SELECT id FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND kind='actor'
 AND life='active' AND json_extract(data_json,'$.role') IN('player','companion')`;
const walletBase=`WITH party AS (${partyActors}), wallets AS (
 SELECT w.* FROM cloud_entities w WHERE w.owner_id=? AND w.continuity_id=? AND w.kind='wallet' AND w.life='active'
 AND ((w.actor_id IN(SELECT id FROM party) AND json_extract(w.data_json,'$.holderId')=w.actor_id)
 OR (w.actor_id IS NULL AND json_extract(w.data_json,'$.shared')=1))) `;
async function walletOverview(service,b,grant,opts) {
  fullParty(grant,'economy');
  const keys=['cp','sp','ep','gp','pp'];
  const stats=keys.flatMap(key=>[
    "CAST(COALESCE(sum(json_extract(data_json,'$.coins."+key+"')),0) AS TEXT) AS "+key,
    "count(*) - count(json_extract(data_json,'$.coins."+key+"')) AS unknown_"+key
  ]).join(',');
  const aggregate=await service.s(walletBase+'SELECT '+stats+
    ', (SELECT count(*) FROM party WHERE id NOT IN (SELECT actor_id FROM wallets WHERE actor_id IS NOT NULL)) AS missingWalletCount, count(*) AS walletCount FROM wallets',...b,...b).first();
  const values={cp:1n,sp:10n,ep:50n,gp:100n,pp:1000n};
  const copper=keys.reduce((sum,key)=>sum+BigInt(aggregate[key])*values[key],0n);
  const unknown=Object.fromEntries(keys.map(key=>[key,aggregate['unknown_'+key]]));
  const rows=(await service.s(walletBase+`SELECT * FROM wallets WHERE id>? AND instr(lower(title),lower(?))>0
    ORDER BY id LIMIT ?`,...b,...b,opts.after,opts.query,opts.limit+1).all()).results;
  const wallets=rows.slice(0,opts.limit).map(cloudEntity);
  return {wallets,nextAfter:rows.length>opts.limit?wallets.at(-1).id:null,totals:{
    knownCopper:copper.toString(),knownGp:(copper/100n).toString()+'.'+(copper%100n).toString().padStart(2,'0'),
    coins:Object.fromEntries(keys.map(key=>[key,aggregate[key]])),unknownDenominations:unknown,
    missingWalletCount:aggregate.missingWalletCount,walletCount:aggregate.walletCount,
    complete:aggregate.walletCount>0 && aggregate.missingWalletCount===0 && keys.every(key=>!unknown[key])}};
}
const itemBase=`WITH party AS (${partyActors}), items AS (
 SELECT i.id,i.actor_id,i.data_json,i.title,d.id AS definition_id,
  CASE WHEN json_extract(d.data_json,'$.stackable')=1 AND json_extract(i.data_json,'$.customized')=0
   AND json_extract(i.data_json,'$.attuned')=0 AND json_extract(i.data_json,'$.operating')=0
   AND json_extract(i.data_json,'$.resourcePoolId') IS NULL AND json_extract(i.data_json,'$.condition')='standard'
   THEN 'd:'||d.id ELSE 'i:'||i.id END AS group_key
 FROM cloud_entities i JOIN cloud_entities d ON d.owner_id=i.owner_id AND d.continuity_id=i.continuity_id
  AND d.id=json_extract(i.data_json,'$.definitionId') AND d.kind='item_definition' AND d.life='active'
 WHERE i.owner_id=? AND i.continuity_id=? AND i.kind='item_instance' AND i.life='active'
 AND json_extract(i.data_json,'$.partyAccess')=1 AND json_extract(i.data_json,'$.holding') IN('carried','storage','unassigned')
 AND (json_extract(i.data_json,'$.ownerId') IN(SELECT id FROM party) OR json_extract(i.data_json,'$.custodianId') IN(SELECT id FROM party)
 OR (json_extract(i.data_json,'$.ownerId') IS NULL AND json_extract(i.data_json,'$.holding') IN('storage','unassigned')))) `;
async function inventoryOverview(service,b,grant,opts) {
  fullParty(grant,'inventory');
  if(opts.id) {
    const definition=await subject(service,b,grant,opts.id,'item_definition');
    const rows=(await service.s(itemBase+`SELECT id,data_json AS data FROM items WHERE definition_id=? AND id>? ORDER BY id LIMIT ?`,
      ...b,...b,definition.id,opts.after,opts.limit+1).all()).results;
    const items=rows.slice(0,opts.limit).map(row=>({id:row.id,...JSON.parse(row.data)}));
    return {definitionId:definition.id,items,nextAfter:rows.length>opts.limit?items.at(-1).id:null};
  }
  const rows=(await service.s(itemBase+`SELECT group_key AS groupId,definition_id AS definitionId,min(title) AS title,
   count(*) AS instances,coalesce(sum(json_extract(data_json,'$.quantity')),0) AS knownQuantity,
   count(*)-count(json_extract(data_json,'$.quantity')) AS unknownQuantityCount
   FROM items WHERE group_key>? AND instr(lower(title),lower(?))>0 GROUP BY group_key,definition_id ORDER BY group_key LIMIT ?`,
   ...b,...b,opts.after,opts.query,opts.limit+1).all()).results;
  const groups=rows.slice(0,opts.limit);
  return {groups,nextAfter:rows.length>opts.limit?groups.at(-1).groupId:null};
}
function chronologicalCursor(after) {
  if(!after)return [Number.MAX_SAFE_INTEGER,''];
  const match=/^(\d+)~([a-zA-Z0-9_.:-]+)$/.exec(after);
  if(!match||!Number.isSafeInteger(Number(match[1]))||!validCloudId(match[2]))cloudFail(400,'INVALID_HISTORY_CURSOR');
  return [Number(match[1]),match[2]];
}
async function history(service,b,grant,view,opts) {
  requireScope(grant,view==='item_history'?'inventory':'chronicle');
  if(view==='item_history'||opts.id)await subject(service,b,grant,opts.id,view==='item_history'?'item_instance':null);
  const kinds=view==='item_history'?['item_event']:['timeline_event','item_event','coin_transaction','component_event']
    .filter(k=>grant.scopes.includes(service.kinds[k]+'.read'));
  const visible=actorRule(grant,'e'),related=actorRule(grant,'a');
  const [rev,cursorId]=chronologicalCursor(opts.after);
  const predicates=["e.owner_id=?","e.continuity_id=?","e.life='active'","e.kind IN(SELECT value FROM json_each(?))",visible.sql,
    "instr(lower(e.title||' '||coalesce(json_extract(e.data_json,'$.narrative'),'')),lower(?))>0"];
  const values=[...b,JSON.stringify(kinds),...visible.values,opts.query];
  let lineageSql='',lineageBinds=[];
  if(view==='item_history'){
    const ancestor=actorRule(grant,'n'),eventScope=actorRule(grant,'branch');
    lineageSql=`lineage(ancestor_id,until_revision) AS (
      SELECT ?,9007199254740991 UNION
      SELECT origin.to_id,min(lineage.until_revision,rev.revision) FROM lineage
      JOIN cloud_links child ON child.owner_id=? AND child.continuity_id=? AND child.relation='into_stack' AND child.to_id=lineage.ancestor_id
      JOIN cloud_entities branch ON branch.owner_id=child.owner_id AND branch.continuity_id=child.continuity_id AND branch.id=child.from_id
      JOIN cloud_links origin ON origin.owner_id=child.owner_id AND origin.continuity_id=child.continuity_id AND origin.from_id=child.from_id AND origin.relation='from_stack'
      JOIN cloud_entities n ON n.owner_id=child.owner_id AND n.continuity_id=child.continuity_id AND n.id=origin.to_id
      JOIN cloud_changes rev ON rev.owner_id=child.owner_id AND rev.continuity_id=child.continuity_id AND rev.entity_id=child.from_id
      WHERE rev.revision<=lineage.until_revision AND branch.life='active' AND ${eventScope.sql} AND ${ancestor.sql}
       AND ${grant.scopes.includes('lifecycle.read')?'1=1':"n.life='active'"}),`;
    lineageBinds=[opts.id,...b,...eventScope.values,...ancestor.values];
    predicates.push(`EXISTS(SELECT 1 FROM cloud_links j JOIN lineage ON lineage.ancestor_id=j.to_id
       WHERE j.owner_id=e.owner_id AND j.continuity_id=e.continuity_id AND j.from_id=e.id AND j.relation='item_subject'
       AND (SELECT min(r.revision) FROM cloud_changes r WHERE r.owner_id=e.owner_id AND r.continuity_id=e.continuity_id AND r.entity_id=e.id)<=lineage.until_revision)`);
  }else if(opts.id){
    predicates.push(`(EXISTS(SELECT 1 FROM cloud_links j WHERE j.owner_id=e.owner_id AND j.continuity_id=e.continuity_id
      AND j.from_id=e.id AND j.to_id=?) OR EXISTS(SELECT 1 FROM cloud_links payment JOIN cloud_links wallet
      ON payment.owner_id=wallet.owner_id AND payment.continuity_id=wallet.continuity_id AND payment.to_id=wallet.from_id
      WHERE payment.owner_id=e.owner_id AND payment.continuity_id=e.continuity_id AND payment.relation='payment'
        AND payment.from_id=e.id AND wallet.relation='wallet_leg' AND wallet.to_id=?))`);values.push(opts.id,opts.id);
  }
  if(kinds.includes('item_event')&&kinds.includes('coin_transaction')) {
    predicates.push(`(e.kind<>'coin_transaction' OR NOT EXISTS(SELECT 1 FROM cloud_links p JOIN cloud_entities a
      ON a.owner_id=p.owner_id AND a.continuity_id=p.continuity_id AND a.id=p.from_id
      WHERE p.owner_id=e.owner_id AND p.continuity_id=e.continuity_id AND p.to_id=e.id AND p.relation='payment'
      AND a.kind='item_event' AND a.life='active' AND ${related.sql}))`);values.push(...related.values);
  }
  const rows=(await service.s(`WITH RECURSIVE ${lineageSql}events AS (SELECT e.*, (SELECT min(h.revision) FROM cloud_changes h
     WHERE h.owner_id=e.owner_id AND h.continuity_id=e.continuity_id AND h.entity_id=e.id) AS event_revision
     FROM cloud_entities e WHERE ${predicates.join(' AND ')})
     SELECT * FROM events WHERE event_revision<? OR (event_revision=? AND id>?) ORDER BY event_revision DESC,id LIMIT ?`,
     ...lineageBinds,...values,rev,rev,cursorId,opts.limit+1).all()).results;
  const events=rows.slice(0,opts.limit).map(row=>({...cloudEntity(row),eventRevision:row.event_revision}));
  return {events,nextAfter:rows.length>opts.limit?events.at(-1).eventRevision+'~'+events.at(-1).id:null};
}
async function genealogy(service,b,grant,opts) {
  requireScope(grant,'crafting');requireScope(grant,'inventory');
  await subject(service,b,grant,opts.id,'item_instance');
  const ev=actorRule(grant,'e'),donor=actorRule(grant,'d'),out=actorRule(grant,'o');
  const archived=grant.scopes.includes('lifecycle.read');
  const rows=(await service.s(`SELECT e.* FROM cloud_links l
    JOIN cloud_entities e ON e.owner_id=l.owner_id AND e.continuity_id=l.continuity_id AND e.id=l.from_id AND e.kind='component_event'
    JOIN cloud_entities d ON e.owner_id=d.owner_id AND e.continuity_id=d.continuity_id AND d.id=json_extract(e.data_json,'$.donorItemId')
    JOIN cloud_entities o ON e.owner_id=o.owner_id AND e.continuity_id=o.continuity_id AND o.id=json_extract(e.data_json,'$.outputItemId')
    WHERE l.owner_id=? AND l.continuity_id=? AND l.to_id=? AND l.relation IN('source_component','resulting_item')
    AND e.life='active' AND (${archived?'1=1':"d.life='active' AND o.life='active'"})
    AND ${ev.sql} AND ${donor.sql} AND ${out.sql} AND e.id>? ORDER BY e.id LIMIT ?`,
    ...b,opts.id,...ev.values,...donor.values,...out.values,opts.after,opts.limit+1).all()).results;
  const events=rows.slice(0,opts.limit).map(cloudEntity);
  return {itemId:opts.id,events,nextAfter:rows.length>opts.limit?events.at(-1).id:null};
}
export async function cloudRecordView(service,principal,id,view,input={}) {
  if(!views.includes(view))cloudFail(404,'UNKNOWN_RECORD_VIEW');
  const opts=options(input),opened=await service.access(principal,id),{grant,continuity:c}=opened,b=service.key(id);
  if(opts.atRevision!=null&&opts.atRevision!==c.revision||opts.atEpoch!=null&&opts.atEpoch!==c.epoch||
      opts.grantRevision!=null&&opts.grantRevision!==grant.revision)cloudFail(409,'SNAPSHOT_CHANGED');
  let result;
  if(view==='wallets')result=await walletOverview(service,b,grant,opts);
  else if(view==='inventory')result=await inventoryOverview(service,b,grant,opts);
  else if(view==='genealogy')result=await genealogy(service,b,grant,opts);
  else result=await history(service,b,grant,view,opts);
  const again=await service.access(principal,id);
  if(again.grant.revision!==grant.revision||again.continuity.revision!==c.revision||again.continuity.epoch!==c.epoch)cloudFail(409,'SNAPSHOT_CHANGED');
  return {view,continuityId:id,epoch:c.epoch,revision:c.revision,grantRevision:grant.revision,...result};
}
