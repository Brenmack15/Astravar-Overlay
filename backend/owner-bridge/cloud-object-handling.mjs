import {boolean, fields, must, option, whole} from './cloud-mechanics-core.mjs';

export const handlingLimits=fields({
  carryPounds:whole(0,100000),pushDragLiftPounds:whole(0,200000),hasHands:boolean
},['carryPounds','pushDragLiftPounds','hasHands']);

export const handlingRequest=fields({
  method:option('carry','push','drag','lift'),totalPounds:whole(0,200000),anatomicallySuitable:boolean,requiresHands:boolean
},['method','totalPounds','anatomicallySuitable','requiresHands']);

export function assessHandling(limits,request){
  must(request && limits && limits.pushDragLiftPounds>=limits.carryPounds,'HANDLING_CONFIGURATION_INVALID');
  const maximum=request.method==='carry'?limits.carryPounds:limits.pushDragLiftPounds;
  const eligible=request.totalPounds<=maximum && request.anatomicallySuitable && (!request.requiresHands||limits.hasHands);
  return {method:request.method,totalPounds:request.totalPounds,maximumPounds:maximum,eligible,transferred:false};
}
