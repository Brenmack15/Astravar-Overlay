import {fields,identifier,option,whole,arrayOf,named,must,owned,actor,op,save} from './cloud-mechanics-core.mjs';

const part=option('whole','chest','boots','helmet','special_weapon');
const eligibility=fields({itemId:identifier,part,formulas:arrayOf(named(120),50),
  nonmagicalConfirmed:{const:true},source:named(300)},['itemId','part','formulas','nonmagicalConfirmed','source']);
export const infusionRule=fields({knownFormulas:arrayOf(named(120),50),ordinaryLimit:whole(0,30),armorOnlyLimit:whole(0,30),
  armorItemIds:arrayOf(identifier,20),eligibleTargets:arrayOf(eligibility,80)},
  ['knownFormulas','ordinaryLimit','armorOnlyLimit','armorItemIds','eligibleTargets']);

export async function checkInfusionRule(ctx,actorId,rule){
  const known=new Set(rule.knownFormulas);
  must(known.size>0 && known.size===rule.knownFormulas.length,'INFUSION_FORMULA_CONFIGURATION_INVALID');
  for(const id of rule.armorItemIds)await owned(ctx,'item_instance',id,actorId);
  const keys=new Set();
  for(const e of rule.eligibleTargets){
    const key=JSON.stringify([e.itemId,e.part]);await owned(ctx,'item_instance',e.itemId,actorId);
    must(!keys.has(key) && e.formulas.length && e.formulas.every(f=>known.has(f)) &&
      (e.part==='whole'||rule.armorItemIds.includes(e.itemId)),'INFUSION_TARGET_CONFIGURATION_INVALID');
    keys.add(key);
  }
}

const request=fields({actorId:identifier,featureId:identifier,itemId:identifier,part,formula:named(120),allocation:option('ordinary','armor_only'),
  operation:option('assign','end'),restReportId:identifier,touchConfirmed:{const:true},
  existingAssignmentsConfirmed:{const:true},effectAdjudication:named(500)},
  ['actorId','featureId','itemId','part','formula','allocation','operation','restReportId','touchConfirmed','existingAssignmentsConfirmed','effectAdjudication']);
export const infusionAssignmentOperation=op('inventory',request,async(ctx,x)=>{
  const a=await actor(ctx,x.actorId),f=await owned(ctx,'feature',x.featureId,a.id),rule=f.data.infusionRule;
  must(rule && rule.knownFormulas.includes(x.formula),'INFUSION_FORMULA_UNKNOWN');
  const rest=await owned(ctx,'rest_report',x.restReportId,a.id);
  must(rest.data.kind==='long_rest' && rest.data.status==='completed' && Number.isSafeInteger(a.data.gameSeconds) &&
    rest.data.endGameSeconds===a.data.gameSeconds,'INFUSION_REQUIRES_FRESH_LONG_REST');
  const item=await ctx.get('item_instance',x.itemId);
  must(item?.life==='active' && item.data.partyAccess && item.data.quantity===1 && item.data.custodianId===a.id &&
    item.data.holding==='carried','INFUSION_ITEM_NOT_AVAILABLE');
  const rows=f.data.activeInfusions??[], installed=item.data.reviewedInfusions??[];
  const exact=r=>r.itemId===item.id && r.part===x.part && r.formula===x.formula && r.allocation===x.allocation && r.featureId===f.id;
  let after, onItem;
  if(x.operation==='end'){
    must(rows.some(exact) && installed.some(exact),'INFUSION_ASSIGNMENT_MISSING');
    after=rows.filter(r=>!exact(r));onItem=installed.filter(r=>!exact(r));
  }else{
    const eligibility=rule.eligibleTargets.find(e=>e.itemId===item.id && e.part===x.part && e.nonmagicalConfirmed && e.formulas.includes(x.formula));
    const definition=await ctx.get('item_definition',item.data.definitionId);
    must(definition?.life==='active','INFUSION_TARGET_INELIGIBLE');
    const intrinsic=item.data.magical || definition.data.magical || String(definition.data.category).toLowerCase()==='magic item' ||
      ['uncommon','rare','very rare','legendary','artifact'].includes(String(definition.data.rarity).toLowerCase());
    must(eligibility && !(x.part==='whole' && intrinsic) && (x.allocation!=='armor_only'||x.part!=='whole' && rule.armorItemIds.includes(item.id)),
      'INFUSION_TARGET_INELIGIBLE');
    must(!rows.some(r=>r.formula===x.formula) && !installed.some(r=>r.part===x.part || r.part==='whole' || x.part==='whole'),'INFUSION_ALREADY_ASSIGNED');
    must(rows.filter(r=>r.allocation===x.allocation).length<(x.allocation==='ordinary'?rule.ordinaryLimit:rule.armorOnlyLimit),'INFUSION_CAPACITY_EXCEEDED');
    const active={featureId:f.id,itemId:item.id,part:x.part,formula:x.formula,allocation:x.allocation,source:eligibility.source,
      restReportId:rest.id,adjudication:x.effectAdjudication};
    after=[...rows,active];onItem=[...installed,active];
  }
  await save(ctx,f,{...f.data,activeInfusions:after});
  await save(ctx,item,{...item.data,reviewedInfusions:onItem});
  return {featureId:f.id,itemId:item.id,part:x.part,formula:x.formula,allocation:x.allocation,active:after,
    effectAdjudication:x.effectAdjudication,restReportId:rest.id};
});
