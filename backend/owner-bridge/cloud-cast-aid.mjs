import { cloudFail } from './cloud-validation.mjs';

function requireAid(ok,code){if(!ok)cloudFail(409,code);}

// A target can see the dice affecting its own checks. Caster-private records never leave this projection.
export async function activeCastAids(ctx,target){
  const byFamily=new Map();
  for(const ref of target.data.rollAidRefs??[]){
    const caster=await ctx.current(ref.casterId);
    if(ref.expiresAt!=null && (target.data.gameSeconds==null||target.data.gameSeconds>=ref.expiresAt))continue;
    if(caster?.kind!=='actor'||caster.life!=='active'||caster.data.concentrationCastId!==ref.castId)continue;
    const cast=await ctx.current(ref.castId),rule=cast?.data.rollAid;
    if(!rule||cast.kind!=='cast_instance'||cast.life!=='active'||!cast.data.targets.includes(target.id))continue;
    const applicableFeatures=[];
    for(const id of target.data.sensoryFeatureIds??[]){
      const feature=await ctx.current(id);
      if(feature?.kind==='feature'&&feature.actorId===target.id&&feature.life==='active'&&feature.data.acceptedCastAid?.family===rule.family)
        applicableFeatures.push(feature);
    }
    let sourceFeatureIds=[];
    if(applicableFeatures.length){
      const spell=await ctx.current(cast.data.spellId),grant=await ctx.current(cast.data.grantId);
      if(cast.actorId!==caster.id || spell?.kind!=='spell' || spell.actorId!==caster.id ||
        grant?.kind!=='spell_grant' || grant.actorId!==caster.id || grant.data.spellId!==spell.id ||
        spell.data.rollAid?.family!==rule.family || spell.data.rollAid.sides!==rule.sides ||
        !rule.applies.every(k=>spell.data.rollAid.applies.includes(k)))continue;
      // The recipient feature corroborates the named caster. Other valid casters still apply their own spell normally.
      sourceFeatureIds=applicableFeatures.filter(feature=>{
        const v=feature.data.acceptedCastAid;
        return v.casterId===caster.id&&v.sides===rule.sides&&v.applies.length===rule.applies.length&&v.applies.every(k=>rule.applies.includes(k));
      }).map(feature=>feature.id).sort();
    }
    const prior=byFamily.get(rule.family);
    if(!prior||prior.sides<rule.sides||prior.sides===rule.sides&&prior.revision<cast.revision)
      byFamily.set(rule.family,{family:rule.family,castId:cast.id,sides:rule.sides,applies:rule.applies,...(sourceFeatureIds.length?{sourceFeatureIds}:{}),revision:cast.revision});
  }
  return [...byFamily.values()].sort((a,b)=>a.family.localeCompare(b.family)).map(({revision,...rule})=>rule);
}

export async function registerCastAid(ctx,castId,casterId,targets,durationSeconds){
  for(const targetId of targets){
    const target=await ctx.get('actor',targetId),refs=[];
    requireAid(durationSeconds==null||target.data.gameSeconds!=null,'TIMED_AID_REQUIRES_KNOWN_CLOCK');
    for(const ref of target.data.rollAidRefs??[]){
      if(ref.expiresAt!=null && target.data.gameSeconds>=ref.expiresAt)continue;
      const caster=await ctx.current(ref.casterId);
      if(caster?.life==='active'&&caster.data.concentrationCastId===ref.castId)refs.push(ref);
    }
    requireAid(refs.length<16,'ACTIVE_ROLL_AID_LIMIT');
    await ctx.put({...target,data:{...target.data,rollAidRefs:[...refs,{casterId,castId,expiresAt:durationSeconds==null?null:target.data.gameSeconds+durationSeconds}]}});
  }
}

export function applyCastAid(result,rules,kind,evidence){
  const expected=rules.filter(r=>r.applies.includes(kind)),given=evidence?.aidDice??[];
  requireAid(expected.length===given.length,'CAST_AID_EVIDENCE_REQUIRED');
  const dice=expected.map(rule=>{
    const found=given.find(e=>e.family===rule.family);
    requireAid(found&&Number.isInteger(found.face)&&found.face>=1&&found.face<=rule.sides,'CAST_AID_FACE_INVALID');
    return {family:rule.family,castId:rule.castId,sides:rule.sides,face:found.face,origin:evidence.origin,...(rule.sourceFeatureIds?{sourceFeatureIds:rule.sourceFeatureIds}:{})};
  });
  if(!dice.length)return result;
  const total=dice.reduce((n,d)=>n+d.face,result.total);
  const left=result.text.slice(0,result.text.lastIndexOf(' = '));
  return {...result,beforeAid:result.total,aidDice:dice,total,text:left+dice.map(d=>' + ('+d.face+' on d'+d.sides+')').join('')+' = '+total};
}
