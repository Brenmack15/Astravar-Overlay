import {must,owned} from './cloud-mechanics-core.mjs';

export async function validateFittedModel(ctx,form){
 const cfg=form.fittedModel;
 if(!cfg)return;
 const armor=await owned(ctx,'item_instance',form.sourceItemId,form.actorId);
 must(armor.data.fittedArmorOperatorId===form.actorId && armor.data.approvedArmorModel && cfg.model!==armor.data.approvedArmorModel &&
   form.activationSeconds===0 && form.timing==='other' && !form.durationSeconds && !form.deploymentPoolId && !form.integrityPoolId &&
   !form.resetPoolId && !form.suppressedItemIds?.includes(armor.id),'UNSUPPORTED_ARMOR_MODEL');
}
export async function checkFittedModelChange(ctx,a,oldId,next,proof){
 const prior=oldId?await ctx.get('form',oldId):null,rule=next?.data.fittedModel?next:prior?.data.fittedModel?prior:null;
 if(!rule){must(!proof,'UNEXPECTED_ARMOR_MODEL_EVIDENCE');return;}
 must(proof&&!a.data.encounterId && (!next||next.data.fittedModel) && (!prior||prior.data.fittedModel),'ARMOR_MODEL_REST_AND_TOOLS_REQUIRED');
 if(prior&&next)must(prior.data.sourceItemId===next.data.sourceItemId,'INCOMPATIBLE_ARMOR_CHASSIS');
 const armor=await owned(ctx,'item_instance',rule.data.sourceItemId,a.id),rest=await owned(ctx,'rest_report',proof.restReportId,a.id);
 must(armor.data.fittedArmorOperatorId===a.id && armor.data.holding==='carried' && armor.data.equipment==='worn' &&
   armor.data.custodianId===a.id && rest.data.status==='completed' && ['short_rest','long_rest'].includes(rest.data.kind) &&
   a.data.gameSeconds!=null && rest.data.endGameSeconds===a.data.gameSeconds && a.data.lastArmorRestId!==rest.id,'ARMOR_MODEL_REST_OR_CHASSIS_INELIGIBLE');
 const tool=await owned(ctx,'item_instance',proof.toolItemId,a.id),def=await ctx.get('item_definition',tool.data.definitionId);
 must(tool.life==='active'&&tool.data.custodianId===a.id&&tool.data.holding==='carried'&&tool.data.equipment==='held'&&tool.data.quantity===1 &&
   (def?.data.toolSpecialty==='smith' || def?.title.toLowerCase().replace(/[’']/g,'')==='smiths tools'),'SMITH_TOOLS_IN_HAND_REQUIRED');
 return rest.id;
}
