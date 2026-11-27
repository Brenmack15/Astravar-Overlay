import { actor,owned,must,gearEligible,matchesForm } from './cloud-mechanics-core.mjs';

export async function checkCompanionTrait(ctx,a,x,old){
  const bond=x.infusionBond,prior=old?.data.infusionBond;
  if(prior)must(bond && ['operatorId','knownFeatureId','dedicatedPoolId'].every(k=>prior[k]===bond[k]),'ORIGINAL_COMPANION_BOND_REQUIRED');
  if(bond){
    must(a.data.role==='companion' && x.kind==='companion' && bond.operatorId!==a.id && !x.sourceItemId && !x.formId,'DEDICATED_COMPANION_REQUIRED');
    const source=await owned(ctx,'feature',bond.knownFeatureId,bond.operatorId);
    const pool=await ctx.get('resource_pool',bond.dedicatedPoolId);
    must(['class','homebrew'].includes(source.data.category) && pool?.life==='active' && [a.id,bond.operatorId].includes(pool.actorId) &&
      pool.data.type==='ordinary' && pool.data.maximum===1 && pool.data.current===0 && pool.data.recovery==='none','OCCUPIED_DEDICATED_SLOT_REQUIRED');
    const duplicate=await ctx.service.s(`SELECT 1 FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND kind='feature' AND id!=?
      AND json_type(data_json,'$.infusionBond')='object' AND (actor_id=? OR json_extract(data_json,'$.infusionBond.operatorId')=?
      OR json_extract(data_json,'$.infusionBond.dedicatedPoolId')=?) LIMIT 1`,...ctx.binding,x.featureId,a.id,bond.operatorId,bond.dedicatedPoolId).first();
    must(!duplicate,'DEDICATED_COMPANION_SLOT_OCCUPIED');
  }
  if(x.includedScore){
    const v=x.includedScore,same=old?.data.includedScore;
    must(v.before<v.after && (a.data.abilities[v.ability]===v.after ||
      same?.ability===v.ability && same.before===v.before && same.after===v.after),'INCLUDED_BASE_SCORE_MISMATCH');
  }
}

export async function socialCheckEdge(ctx,a,s,p){
  const v=s.social;
  must(s.kind==='skill' && ['persuasion','performance'].includes(s.key),'SOCIAL_SKILL_REQUIRED');
  const trait=await owned(ctx,'feature',v.featureId,a.id);
  must(trait.data.socialCheck==='nonhostile_goodwill_or_entertainment' && matchesForm(trait.data,p.ruleFormIds) &&
    await gearEligible(ctx,trait.data.sourceItemId,a.id,trait.data.requiresAttunement,trait.data.requiresOperating),'SOCIAL_TRAIT_INELIGIBLE');
  must(v.targetActorId!==a.id,'SOCIAL_AUDIENCE_REQUIRED');await actor(ctx,v.targetActorId);
  const applied=v.attitude!=='hostile' && v.attitude!=='unknown' && v.perceives &&
    (s.key==='performance' && v.purpose==='entertain' || s.key==='persuasion' && v.purpose==='goodwill' && v.harmlessEndearing);
  return {applied,edge:applied?{id:trait.id,data:{target:'roll_edge',key:'skill:'+s.key,operation:'advantage'}}:null};
}

export async function translateMessage(ctx,a,x,p,featureId){
  const reading=x.translation;
  const trait=await owned(ctx,'feature',featureId,a.id);
  const speech=p.communications.find(c=>c.kind==='speech'&&c.featureId===trait.id);
  const out=reading.outputLanguage.toLowerCase(),input=reading.inputLanguage.toLowerCase();
  must(trait.data.speech?.translation===true && speech?.fullSentences && input!==out &&
    [input,out].every(k=>speech.languages.some(l=>l.toLowerCase()===k)),'TRANSLATOR_PACKAGE_UNAVAILABLE');
  must(reading.sourcePerceived && reading.outputHeard && !p.conditions.includes(reading.modality==='speech'?'deafened':'blinded'),'TRANSLATION_SENSE_REQUIRED');
  const audience=await actor(ctx,reading.recipientId);
  must(audience.id!==a.id && !audience.data.conditions.includes('deafened') &&
    audience.data.training?.some(t=>t.category==='language' && t.key.toLowerCase()===out),'TRANSLATION_RECIPIENT_UNAVAILABLE');
  return {featureId,inputLanguage:reading.inputLanguage,outputLanguage:reading.outputLanguage,modality:reading.modality,recipientId:audience.id};
}

export async function preserveDedicatedCapacity(ctx,pool,next){
  const old=pool.data;
  if(old.type!=='ordinary' || old.maximum!==1 || old.current!==0 || old.recovery!=='none'
    || next.maximum===1 && next.current===0 && next.recovery==='none')return;
  const reserved=await ctx.service.s("SELECT 1 FROM cloud_links WHERE owner_id=? AND continuity_id=? AND relation='dedicated_slot' AND to_id=? LIMIT 1",
    ...ctx.binding,pool.id).first();
  must(!reserved,'ORIGINAL_COMPANION_SLOT_IN_USE');
}
