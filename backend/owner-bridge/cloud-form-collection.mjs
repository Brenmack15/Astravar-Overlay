import {actor,fields,formSchema,identifier,must,named,op,owned,save} from './cloud-mechanics-core.mjs';

async function duplicate(ctx,x,defineForm){
  const source=await owned(ctx,'form',x.sourceFormId,x.actorId);
  const canonicalArgs=Object.fromEntries(Object.keys(formSchema.properties)
    .filter(key=>Object.hasOwn(source.data,key)).map(key=>[key,source.data[key]]));
  await defineForm(ctx,{...canonicalArgs,actorId:x.actorId,formId:x.formId,name:x.name});
  const copy=await owned(ctx,'form',x.formId,x.actorId);
  const inheritedRuleFormIds=[source.id,...(source.data.inheritedRuleFormIds??[])];
  must(inheritedRuleFormIds.length<=64,'FORM_RULE_ANCESTRY_LIMIT');
  await save(ctx,copy,{...copy.data,inheritedRuleFormIds});
  for(const id of inheritedRuleFormIds)await ctx.link(copy.id,'inherits_form_rules',id);
  // Variant selection is a configuration value. Clone only the actor's selected
  // component values; the attack, its costs and its physical source keep their IDs.
  const attacks=(await ctx.service.s(`SELECT id FROM cloud_entities WHERE owner_id=? AND continuity_id=?
    AND kind='attack' AND actor_id=? AND life='active' AND EXISTS(SELECT 1 FROM json_each(data_json,'$.selections')
      WHERE json_extract(value,'$.formId')=? AND COALESCE(json_extract(value,'$.actorId'),?)=?) ORDER BY id`,
    ...ctx.binding,x.actorId,source.id,x.actorId,x.actorId).all()).results;
  for(const {id} of attacks){
    const attack=await owned(ctx,'attack',id,x.actorId);
    const selected=(attack.data.selections??[]).filter(s=>s.formId===source.id&&(s.actorId??x.actorId)===x.actorId);
    await save(ctx,attack,{...attack.data,selections:attack.data.selections.concat(selected.map(s=>({...s,actorId:x.actorId,formId:copy.id})))});
  }
  return {actorId:x.actorId,formId:copy.id,sourceFormId:source.id,inheritedRuleFormIds};
}
async function move(ctx,x){
  await actor(ctx,x.actorId);
  const row=await owned(ctx,'form',x.formId,x.actorId),peer=await owned(ctx,'form',x.adjacentFormId,x.actorId);
  const key=row.data.orderKey??row.id,other=peer.data.orderKey??peer.id;
  must(key!==other,'FORM_POSITION_UNCHANGED');
  const [lower,upper]=key<other?[key,other]:[other,key];
  const between=await ctx.service.s(`SELECT 1 AS present FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND
    kind='form' AND actor_id=? AND life='active' AND COALESCE(json_extract(data_json,'$.orderKey'),id)>?
    AND COALESCE(json_extract(data_json,'$.orderKey'),id)<? LIMIT 1`,...ctx.binding,x.actorId,lower,upper).first();
  must(!between,'FORMS_MUST_BE_ADJACENT');
  await save(ctx,row,{...row.data,orderKey:other,configurationRevision:row.data.configurationRevision??row.revision});
  await save(ctx,peer,{...peer.data,orderKey:key,configurationRevision:peer.data.configurationRevision??peer.revision});
  return {actorId:x.actorId,formId:row.id,adjacentFormId:peer.id,previousKey:key,orderKey:other};
}
export function formCollectionOperations(defineForm){
  return {
    duplicate_form:op('character',fields({actorId:identifier,sourceFormId:identifier,formId:identifier,name:named(160)},['actorId','sourceFormId','formId','name']),
      (ctx,args)=>duplicate(ctx,args,defineForm)),
    move_form:op('character',fields({actorId:identifier,formId:identifier,adjacentFormId:identifier},['actorId','formId','adjacentFormId']),move)
  };
}
