import {boolean,fields,identifier,named,whole,option,arrayOf,must,actor,owned,save} from './cloud-mechanics-core.mjs';

export const workshopTypes=['facility_rate','equipment_spectrum','equipment_diagnostic','crafting_allowance','fitted_armor'];
export const workshopDefinition={facilityProjectId:identifier,allowedRates:arrayOf(fields({inside:whole(1,100),outside:whole(1,100)},['inside','outside']),12),researchActorIds:arrayOf(identifier,12)};
export const workshopArguments={
 fittedArmor:option('don','doff'),
 facilityRate:fields({inside:whole(1,100),outside:whole(1,100),atControls:{const:true},otherPortalsClosed:{const:true}},['inside','outside','atControls','otherPortalsClosed']),
 spectrum:option('normal','arcane_analysis','revelation_light'),
 diagnostic:fields({signal:named(200),baseline:named(300),evidence:named(500),assessment:named(500)},['signal','baseline','evidence','assessment']),
 craftQuote:fields({rarity:option('common','uncommon'),ordinaryCopper:whole(0,100000000),ordinaryWorkSeconds:whole(1,100000000),basis:named(400)},['rarity','ordinaryCopper','ordinaryWorkSeconds','basis'])
};

export async function validateWorkshop(ctx,x){
 if(x.type==='facility_rate'){
   must(x.facilityProjectId&&x.allowedRates?.length&&x.researchActorIds?.length&&x.timing==='action','FACILITY_CONFIGURATION_INVALID');
   const facility=await ctx.get('invention_project',x.facilityProjectId);
   must(facility?.life==='active'&&facility.data.status==='completed','COMPLETED_FACILITY_REQUIRED');
   for(const id of x.researchActorIds)await actor(ctx,id);
 } else must(!x.allowedRates&&!x.researchActorIds&&!x.facilityProjectId,'UNEXPECTED_FACILITY_CONFIGURATION');
 if(x.type==='fitted_armor')must(x.sourceItemId&&x.timing==='action','FITTED_ARMOR_REQUIRED');
 if(x.type==='equipment_spectrum'||x.type==='equipment_diagnostic')must(x.sourceItemId && x.requiresOperating,'OPERATING_EQUIPMENT_REQUIRED');
}
export async function useWorkshop(ctx,a,action,x,result){
 const d=action.data;
 for(const [input,type] of [['fittedArmor','fitted_armor'],['facilityRate','facility_rate'],['spectrum','equipment_spectrum'],['diagnostic','equipment_diagnostic'],['craftQuote','crafting_allowance']])
   must((x[input]!==undefined)===(d.type===type),'WORKSHOP_ARGUMENT_MISMATCH');
 if(x.fittedArmor){
   const item=await owned(ctx,'item_instance',d.sourceItemId,a.id);
   must(item.life==='active' && item.data.ownerId===a.id && item.data.custodianId===a.id && item.data.holding==='carried' && item.data.partyAccess && item.data.quantity===1,'FITTED_ARMOR_UNAVAILABLE');
   const don=x.fittedArmor==='don';must(don?item.data.equipment==='packed':item.data.equipment==='worn','ARMOR_CONFIGURATION_CHANGED');
   await save(ctx,item,{...item.data,equipment:don?'worn':'packed'});result.equipment=don?'worn':'packed';
 }
 if(x.facilityRate){
   const f=await ctx.get('invention_project',d.facilityProjectId),r=x.facilityRate;
   must(f?.life==='active'&&f.data.status==='completed'&&r.atControls&&r.otherPortalsClosed,'FACILITY_UNAVAILABLE');
   must(d.allowedRates.some(v=>v.inside===r.inside&&v.outside===r.outside),'UNSUPPORTED_FACILITY_RATE');
   for(const id of d.researchActorIds)must(!(await actor(ctx,id)).data.openGate,'FACILITY_GATE_OPEN');
   await save(ctx,f,{...f.data,selectedTimeRatio:{inside:r.inside,outside:r.outside}});result.selectedTimeRatio={inside:r.inside,outside:r.outside};
 }
 if(x.spectrum||x.diagnostic){
   const item=await owned(ctx,'item_instance',d.sourceItemId,a.id);
   if(x.spectrum){
     const projection={mode:x.spectrum,visibleLight:x.spectrum==='revelation_light',revealsWithinIllumination:x.spectrum==='revelation_light',oilSpent:0};
     await save(ctx,item,{...item.data,spectralSetting:projection});result.spectrum=projection;
   } else {const finding={...x.diagnostic,atGameSeconds:a.data.gameSeconds??null};await save(ctx,item,{...item.data,lastDiagnostic:finding});result.diagnostic=finding;}
 }
 if(x.craftQuote){
   const q=x.craftQuote;
   result.craftingAllowance={rarity:q.rarity,costCopper:{numerator:q.ordinaryCopper,denominator:2},
     workSeconds:{numerator:q.ordinaryWorkSeconds,denominator:4},basis:q.basis};
 }
}
