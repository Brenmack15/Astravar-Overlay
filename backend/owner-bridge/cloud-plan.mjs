import { canonical } from './core.mjs';
import { cloudFail, validCloudId } from './cloud-validation.mjs';

export function cloudEntity(row) {
  return { id: row.id, kind: row.kind, actorId: row.actor_id, title: row.title, data: JSON.parse(row.data_json), life: row.life, revision: row.revision };
}
export function cloudAllowed(grant, scope, actorId, write = false) {
  return grant.scopes.includes(scope + (write ? '.write' : '.read')) &&
    (grant.actors.includes('*') || (actorId !== null && grant.actors.includes(actorId)));
}

/** A pure transaction plan. Reads come from the tenant; changes remain in memory until the SQL revision guard succeeds. */
export class CloudPlan {
  constructor(service, binding, grant, command, now) {
    this.service = service;
    this.binding = binding;
    this.grant = grant;
    this.commandId = command.commandId;
    this.now = now;
    this.changes = new Map();
    this.edges = new Map();
    this.access = new Map();
    this.reads = new Map();
    this.operation = command.operation;
    this.confirmedAccess = new Set();
  }
  require(scope, actorId, write = true) {
    if (!(actorId === null || validCloudId(actorId)) || !cloudAllowed(this.grant, scope, actorId, write)) cloudFail(403, 'CLOUD_GRANT_DENIED');
    this.access.set(JSON.stringify([scope, actorId, write]), {scope, actorId, write});
    this.confirmedAccess.add(scope);
  }
  async current(id) {
    if (!validCloudId(id)) cloudFail(400, 'INVALID_ENTITY_ID');
    if (this.changes.has(id)) return this.changes.get(id).after;
    if (!this.reads.has(id)) {
      const row = await this.service.s('SELECT * FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id=?',...this.binding,id).first();
      this.reads.set(id,row ? cloudEntity(row) : null);
    }
    return this.reads.get(id);
  }
  async get(kind, id) {
    if (!Object.hasOwn(this.service.kinds,kind)) cloudFail(400, 'INVALID_ENTITY_KIND');
    const record = await this.current(id);
    if (!record || record.kind !== kind) return null;
    this.require(this.service.kinds[kind],record.actorId,false);
    return structuredClone(record);
  }
  async list(kind) {
    if (!Object.hasOwn(this.service.kinds,kind)) cloudFail(400, 'INVALID_ENTITY_KIND');
    const scope=this.service.kinds[kind];
    if (!this.grant.scopes.includes(scope+'.read')) cloudFail(403,'CLOUD_GRANT_DENIED');
    const restriction=this.grant.actors.includes('*') ? '' : ' AND actor_id IN (SELECT value FROM json_each(?))';
    const bindings=this.grant.actors.includes('*') ? [] : [JSON.stringify(this.grant.actors)];
    const rows=(await this.service.s('SELECT * FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND kind=?'+restriction+' ORDER BY id LIMIT 513',...this.binding,kind,...bindings).all()).results;
    if(rows.length>512) cloudFail(409,'BOUNDED_QUERY_EXCEEDED');
    const projected=new Map(rows.map(r=>[r.id,cloudEntity(r)]));
    for(const [id,row] of projected)if(!this.reads.has(id))this.reads.set(id,row);
    for(const [id,change] of this.changes){if(change.before?.kind===kind)projected.delete(id);if(change.after?.kind===kind && cloudAllowed(this.grant,scope,change.after.actorId))projected.set(id,change.after);}
    for(const r of projected.values())this.require(scope,r.actorId,false);
    return [...projected.values()].map(r=>structuredClone(r));
  }
  async put(input) {
    const {id,kind,actorId,title,data}=input, life=input.life??'active';
    if (!validCloudId(id) || !Object.hasOwn(this.service.kinds,kind) || typeof title!=='string' || !title.trim() || title.length>320 ||
        !data || typeof data!=='object' || Array.isArray(data) || !['active','archived'].includes(life)) cloudFail(400,'INVALID_ENTITY');
    const scope=this.service.kinds[kind];
    this.require(scope,actorId); this.require(scope,actorId,false);
    if (canonical(data).length>49152 || this.changes.size>128) cloudFail(413,'PLAN_TOO_LARGE');
    const before=await this.current(id);
    if(before) {
      if(before.kind!==kind) cloudFail(409,'ENTITY_ID_IN_USE');
      this.require(scope,before.actorId); this.require(scope,before.actorId,false);
    }
    if(kind==='actor' && actorId!==id) cloudFail(400,'INVALID_ACTOR_IDENTITY');
    if(actorId!==null && actorId!==id && !await this.get('actor',actorId)) cloudFail(409,'ACTOR_REQUIRED');
    const original=this.changes.has(id) ? this.changes.get(id).before : before;
    const after={id,kind,actorId,title,data:structuredClone(data),life,revision:(original?.revision??0)+1};
    this.changes.set(id,{before:original,after});
    return structuredClone(after);
  }
  async edge(fromId,relation,toId,remove) {
    if (!validCloudId(fromId)||!validCloudId(toId)||!validCloudId(relation)||this.edges.size>=256) cloudFail(400,'INVALID_RELATIONSHIP');
    const from=await this.current(fromId),to=await this.current(toId);
    if(!from||!to)cloudFail(409,'MISSING_LINK_TARGET');
    this.require(this.service.kinds[from.kind],from.actorId);
    this.require(this.service.kinds[to.kind],to.actorId,false);
    const key=JSON.stringify([fromId,relation,toId]);
    this.edges.set(key,{fromId,relation,toId,remove});
  }
  async link(fromId,relation,toId){return this.edge(fromId,relation,toId,false);}
  async unlink(fromId,relation,toId){return this.edge(fromId,relation,toId,true);}
  /** Splitting personally visible stock can inherit only its existing catalog
   * edge. Neither the catalog nor a caller-supplied definition is enumerable. */
  async linkInheritedItemDefinition(childId,parentId) {
    const child=await this.get('item_instance',childId),parent=await this.get('item_instance',parentId);
    const created=this.changes.get(childId),split=this.edges.get(JSON.stringify([childId,'split_from',parentId]));
    if(!child || !parent || !created || created.before!==null || !split || split.remove ||
        child.life!=='active' || parent.life!=='active' || child.actorId!==parent.actorId ||
        !child.data.partyAccess || !parent.data.partyAccess || child.data.definitionId!==parent.data.definitionId ||
        child.data.ownerId!==parent.data.ownerId || child.data.custodianId!==parent.data.custodianId)
      cloudFail(409,'HOLDING_DEFINITION_PROOF_REQUIRED');
    const def=await this.current(parent.data.definitionId);
    const sourceEdge=await this.service.s(`SELECT 1 AS present FROM cloud_links WHERE owner_id=? AND continuity_id=?
      AND from_id=? AND relation='definition' AND to_id=?`,...this.binding,parentId,parent.data.definitionId).first();
    if(!sourceEdge || !def || def.kind!=='item_definition' || def.life!=='active' || def.actorId!==null)
      cloudFail(409,'HOLDING_DEFINITION_PROOF_REQUIRED');
    this.require(this.service.kinds.item_instance,child.actorId,true);
    if(this.edges.size>=256)cloudFail(400,'INVALID_RELATIONSHIP');
    const edge={fromId:childId,relation:'definition',toId:def.id,remove:false};
    this.edges.set(JSON.stringify([childId,'definition',def.id]),edge);
  }
  freeze(result) {
    const plan={result,readIds:[...this.reads.keys()],changes:[...this.changes.values()],edges:[...this.edges.values()],access:[...this.access.values()]};
    if(canonical(plan).length>240000) cloudFail(413,'PLAN_TOO_LARGE');
    return plan;
  }
}
