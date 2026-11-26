import { CloudPlan } from './cloud-plan.mjs';
import { BridgeError } from './core.mjs';
import { cloudFail, validCloudId } from './cloud-validation.mjs';
import { sheet, gearEligible, rollPolicy } from './cloud-mechanics-core.mjs';
import { attackModifier, attackSaveDc, snapshotComponents } from './cloud-mechanics-combat.mjs';

export async function cloudActions(service,principal,id,actorId,options={}) {
  if(!validCloudId(actorId))cloudFail(400,'INVALID_ACTOR_ID');
  const {grant,continuity:c}=await service.access(principal,id);
  const ctx=new CloudPlan(service,service.key(id),grant,{commandId:'preview-actions',operation:'inspect_actions'},service.now());
  ctx.require('combat',actorId,false);
  const after=options.after??'';
  if(after && (!validCloudId(after)||options.atEpoch!==c.epoch||options.atRevision!==c.revision||options.grantRevision!==grant.revision))
    cloudFail(409,'SNAPSHOT_CHANGED');
  const a=await ctx.get('actor',actorId);
  if(!a||a.life!=='active')cloudFail(404,'ACTOR_UNAVAILABLE');
  const p=await sheet(ctx,actorId);
  const candidates=(await service.s("SELECT id,kind FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND actor_id=? AND life='active' AND kind IN ('attack','action_definition') AND id>? ORDER BY id LIMIT 25",...service.key(id),actorId,after).all()).results;
  const rows=[];
  for(const candidate of candidates.slice(0,24)) {
    const entity=await ctx.get(candidate.kind,candidate.id);
    if(!entity)cloudFail(409,'SNAPSHOT_CHANGED');
    const d=entity.data;
    if((d.formId && d.formId!==p.formId)||d.excludedForms?.includes(p.formId) || !await gearEligible(ctx,d.sourceItemId,actorId,d.requiresAttunement,d.requiresOperating))continue;
    const reasons=[];
    if(p.conditions.some(s=>['incapacitated','unconscious','stunned','paralyzed','petrified'].includes(s)))reasons.push('ACTOR_CANNOT_ACT');
    if(d.resolution==='manual')reasons.push('MANUAL_ADJUDICATION_REQUIRED');
    if(d.cost){const pool=await ctx.get('resource_pool',d.cost.poolId);if(pool?.data.current==null||pool.data.current<d.cost.amount)reasons.push('RESOURCE_EMPTY_OR_UNKNOWN');}
    if(a.data.encounterId && d.timing!=='other'){
      const e=await ctx.get('encounter',a.data.encounterId),t=e?.data.economy?.[actorId];
      if(!e?.data.active || !t || (d.timing!=='reaction'&&e.data.participants[e.data.index]!==actorId) ||
          (d.timing==='attack'?(!t.action||t.attacks<1):(!t[d.timing]||d.timing==='action'&&t.attacks!==a.data.attacksPerAction)))reasons.push('ACTION_UNAVAILABLE');
    }
    let components=[],hit=null,resolvedSaveDc=null;
    if(entity.kind==='attack'){
      const chosen=Object.fromEntries((d.selections??[]).filter(s=>(s.actorId??entity.actorId)===a.id && s.formId===p.formId).map(s=>[s.componentId,s.type]));
      try { components=snapshotComponents(d.components,p,chosen);if(d.resolution==='attack')hit=attackModifier(entity,p);
        if(d.resolution==='save')resolvedSaveDc=attackSaveDc(d,p); }
      catch(e){if(!(e instanceof BridgeError))throw e;reasons.push(e.code);}
    }
    rows.push({id:entity.id,title:entity.title,kind:entity.kind,configuration:d,components,hit,resolvedSaveDc,reasons,
      modes:Object.fromEntries(['normal','advantage','disadvantage'].map(m=>[m,rollPolicy(p.effects,'attack:'+entity.id,m)]))});
  }
  const end=await service.access(principal,id);
  if(end.continuity.epoch!==c.epoch||end.continuity.revision!==c.revision||end.grant.revision!==grant.revision)cloudFail(409,'SNAPSHOT_CHANGED');
  return {epoch:c.epoch,revision:c.revision,grantRevision:grant.revision,actorId,formId:p.formId,rows,
    nextAfter:candidates.length>24?candidates[23].id:null};
}
