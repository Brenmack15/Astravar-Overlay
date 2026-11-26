import { CloudPlan } from './cloud-plan.mjs';
import { BridgeError } from './core.mjs';
import { cloudFail, validCloudId } from './cloud-validation.mjs';
import { sheet, gearEligible, rollPolicy, checkTiming, availableUses, matchesForm, reactionRollWindow } from './cloud-mechanics-core.mjs';
import { checkArsenal } from './cloud-mechanics-arsenal.mjs';
import { attackModeFor } from './cloud-mechanics-linked.mjs';
import { offeredCharges } from './cloud-effect-costs.mjs';
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
    if(!matchesForm(d,p.ruleFormIds) || !await gearEligible(ctx,d.sourceItemId,actorId,d.requiresAttunement,d.requiresOperating))continue;
    const reasons=[];
    if(p.exhaustion.status==='fatal')reasons.push('FATAL_EXHAUSTION_REQUIRES_RESOLUTION');
    if(p.exhaustion.status==='adjudication_required')reasons.push('EXHAUSTION_ADJUDICATION_REQUIRED');
    if(p.conditions.some(s=>['incapacitated','unconscious','stunned','paralyzed','petrified'].includes(s)))reasons.push('ACTOR_CANNOT_ACT');
    if(d.resolution==='manual')reasons.push('MANUAL_ADJUDICATION_REQUIRED');
    if(d.cost){const pool=await ctx.get('resource_pool',d.cost.poolId);if(!pool||availableUses(pool.data)==null||availableUses(pool.data)<d.cost.amount)reasons.push('RESOURCE_EMPTY_OR_UNKNOWN');}
    const damageReasons=[...reasons];
    try { await checkTiming(ctx,a,d.timing); }
    catch(e){if(!(e instanceof BridgeError)||e.status===403)throw e;if(!reasons.includes(e.code))reasons.push(e.code);}
    let components=[],hit=null,resolvedSaveDc=null,configuration=d;
    if(entity.kind==='attack'){
      const chosen=Object.fromEntries((d.selections??[]).filter(s=>(s.actorId??entity.actorId)===a.id && s.formId===p.formId).map(s=>[s.componentId,s.type]));
      try { const charge=await checkArsenal(ctx,a,d),mode=await attackModeFor(ctx,a,entity);
        const base=d.components.filter(c=>c.id!==d.payloadRule?.componentId);
        const chargeComponent=charge?[{id:'prepared-bonus',name:'Prepared strike',type:charge.type,dice:0,sides:4,flat:charge.flat,critical:false}]:[];
        components=snapshotComponents([...base,...mode.components,...chargeComponent],p,chosen,0,'attack',entity.id);
        configuration={...d,reach:mode.reach,modeUseIds:mode.useIds};if(d.resolution==='attack')hit=attackModifier(entity,p);
        if(d.resolution==='save')resolvedSaveDc=attackSaveDc(d,p); }
      catch(e){if(!(e instanceof BridgeError))throw e;reasons.push(e.code);damageReasons.push(e.code);}
    }
    rows.push({id:entity.id,title:entity.title,kind:entity.kind,configuration,components,hit,resolvedSaveDc,reasons,damageReasons,
      optionalCharges:offeredCharges(p,'attack',entity.id,entity.id,components,d.resolution),
      modes:Object.fromEntries(['normal','advantage','disadvantage'].map(m=>[m,rollPolicy(p.effects,'attack:'+entity.id,m)]))});
  }
  const reactionWindow=await reactionRollWindow(ctx,a);
  const end=await service.access(principal,id);
  if(end.continuity.epoch!==c.epoch||end.continuity.revision!==c.revision||end.grant.revision!==grant.revision)cloudFail(409,'SNAPSHOT_CHANGED');
  return {epoch:c.epoch,revision:c.revision,grantRevision:grant.revision,actorId,formId:p.formId,reactionWindow,rows,
    nextAfter:candidates.length>24?candidates[23].id:null};
}
