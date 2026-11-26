import { canonical, sha256 } from './core.mjs';
import { cloudFail, validCloudId } from './cloud-validation.mjs';

function uniqueIds(values) { return [...new Set(values.filter(validCloudId))]; }
function referenced(plan,access) {
  return uniqueIds([...(plan?.readIds??[]),...(plan?.changes??[]).flatMap(c=>[c.before?.id,c.after?.id,c.before?.actorId,c.after?.actorId]),
    ...(plan?.edges??[]).flatMap(e=>[e.fromId,e.toId]),...access.map(a=>a.actorId)]);
}

async function segments(service,b,commandId,inputs) {
  const rows=[], references=[], parts={};
  for(const input of inputs){
    const payload=canonical(input.value),digest=await sha256(payload);
    rows.push({part:input.part,digest,payload});
    parts[input.part]=digest;
    for(const id of uniqueIds(input.ids))references.push([input.part,id]);
  }
  const writes=[service.s(`INSERT INTO cloud_evidence_segments(owner_id,continuity_id,command_id,part,digest,payload_json)
    SELECT ?,?,?,json_extract(value,'$.part'),json_extract(value,'$.digest'),json_extract(value,'$.payload') FROM json_each(?)`,
    ...b,commandId,canonical(rows))];
  if(references.length)writes.push(service.s(`INSERT INTO cloud_evidence_subjects(owner_id,continuity_id,command_id,part,entity_id)
    SELECT ?,?,?,json_extract(value,'$[0]'),json_extract(value,'$[1]') FROM json_each(?)`,...b,commandId,canonical(references)));
  return {parts,writes};
}

export async function requestEvidence(service,b,command,grantRevision,plan,direct) {
  const input={command,grantRevision,...(direct?{}:{plan})};
  const {parts,writes}=await segments(service,b,command.commandId,[{part:'request',value:input,ids:referenced(plan,plan.access)}]);
  const {args,...skeleton}=command;
  // Storage cleanup uses these opaque references to protect a pending upload.
  if(command.operation==='assign_primary_image')skeleton.args={
    uploadId:args.uploadId,targetKind:args.targetKind,targetId:args.targetId,sha256:args.sha256,
  };
  return {stored:canonical({evidenceVersion:1,reviewRequired:!direct,command:skeleton,grantRevision,argsHash:await sha256(canonical(args)),parts}),writes};
}

export async function resultEvidence(service,b,commandId,result,access,plan) {
  const {changes=[],...summary}=result;
  const subjects=referenced(plan,access),subjectSet=new Set(subjects);
  const inputs=[{part:'result',value:summary,ids:subjects}];
  for(const [index,change] of changes.entries()){
    // Preserve independent legs of a multi-record command. Remove an entire
    // leg if it embeds a structured reference to a deleted participant.
    const ids=[change.after?.id,change.before?.id,change.after?.actorId,change.before?.actorId];
    const values=[change.before?.data,change.after?.data];
    while(values.length){
      const value=values.pop();
      if(typeof value==='string'&&subjectSet.has(value))ids.push(value);
      else if(value && typeof value==='object')values.push(...Object.values(value));
    }
    inputs.push({part:'c'+index,value:change,ids});
  }
  const {parts,writes}=await segments(service,b,commandId,inputs);
  const header={evidenceVersion:1,parts,changeCount:changes.length,hadChanges:Object.hasOwn(result,'changes')};
  if(result.outcome?.permanentDeletion){
    // Minimal disclosure survives deletion of full playable evidence.
    header.deletedIds=result.outcome.records.map(r=>r.id);
  }
  return {stored:canonical(header),writes};
}

async function readParts(service,b,commandId,parts) {
  const result={}, erased=[];
  const rows=(await service.s(`SELECT part,digest,payload_json FROM cloud_evidence_segments
    WHERE owner_id=? AND continuity_id=? AND command_id=? UNION ALL
    SELECT part,digest,NULL AS payload_json FROM cloud_evidence_erased WHERE owner_id=? AND continuity_id=? AND command_id=?`,
  ...b,commandId,...b,commandId).all()).results;
  const byPart=new Map(rows.map(r=>[r.part,r]));
  for(const [key,expected] of Object.entries(parts)){
    const row=byPart.get(key);
    if(!row || row.digest!==expected)cloudFail(503,'CLOUD_EVIDENCE_INCOMPLETE');
    if(row.payload_json===null)erased.push(key);
    else {
      if(await sha256(row.payload_json)!==expected)cloudFail(503,'CLOUD_EVIDENCE_INTEGRITY');
      result[key]=JSON.parse(row.payload_json);
    }
  }
  return {result,erased};
}

export async function readRequestEvidence(service,b,row) {
  const stored=JSON.parse(row.request_json);
  if(stored.evidenceVersion!==1)return stored;
  const {result}=await readParts(service,b,row.command_id,stored.parts);
  return result.request??{command:stored.command,grantRevision:stored.grantRevision,evidenceErased:true};
}

export async function readResultEvidence(service,b,row) {
  const header=JSON.parse(row.result_json);
  if(header.evidenceVersion!==1){
    // Earlier development revisions stored whole receipts immutably. Even an
    // otherwise authorized gameplay client must not recover a deleted sheet.
    const hidden=await service.s(`SELECT 1 AS found FROM cloud_tombstones t WHERE t.owner_id=? AND t.continuity_id=?
      AND (EXISTS(SELECT 1 FROM json_tree(?) WHERE type='text' AND atom=t.id)
        OR EXISTS(SELECT 1 FROM json_tree(?) WHERE type='text' AND atom=t.id)
        OR EXISTS(SELECT 1 FROM cloud_requests q,json_tree(q.request_json) j WHERE q.owner_id=t.owner_id AND q.continuity_id=t.continuity_id
          AND q.command_id=? AND j.type='text' AND j.atom=t.id)) LIMIT 1`,...b,row.result_json,row.access_json,row.command_id).first();
    return hidden?{evidenceErased:true,legacyRetention:true}:header;
  }
  const {result,erased}=await readParts(service,b,row.command_id,header.parts);
  const value=result.result??{};
  if(header.hadChanges)value.changes=Array.from({length:header.changeCount},(_,i)=>result['c'+i]).filter(Boolean);
  if(erased.length)value.evidenceErased=true;
  if(header.deletedIds)value.deletedIds=header.deletedIds;
  return value;
}

export function eraseEntityEvidence(service,b,id) {
  return service.s(`DELETE FROM cloud_evidence_segments WHERE owner_id=? AND continuity_id=? AND (command_id,part) IN (
    SELECT command_id,part FROM cloud_evidence_subjects WHERE owner_id=? AND continuity_id=? AND entity_id=?
  )`,...b,...b,id);
}
