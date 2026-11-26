import { stats, identifier, named, whole, arrayOf, fields, option, boolean, checkSpec, evidence, must, op, owned, actor, insert, save, sheet, rollPolicy } from './cloud-mechanics-core.mjs';

export function observed(name,count,sides,flat,keeps,e,eligibleD20=false) {
  must(Number.isSafeInteger(flat) && count>=0 && count<=200 && e.faces.length===count && e.faces.every(v=>Number.isInteger(v)&&v>=1&&v<=sides),'INVALID_RECORDED_FACES');
  let chosen=-1;
  if(keeps!=='sum'){
    const retained=keeps==='highest'?Math.max(...e.faces):Math.min(...e.faces);
    chosen=e.faces.indexOf(retained);
  }
  const faces=e.faces.map((v,i)=>({sides,value:v,kept:keeps==='sum'||i===chosen}));
  const kept=faces.filter(f=>f.kept),total=kept.reduce((s,f)=>s+f.value,flat);
  const natural=eligibleD20&&sides===20&&kept.length===1?kept[0].value:null;
  const printable=faces.map(f=>!f.kept?'['+f.value+']':natural===20||natural===1?'Nat '+f.value:String(f.value));
  return {name,status:'resolved',origin:e.origin,faces,modifier:flat,total,natural,mode:keeps==='sum'?'normal':keeps==='highest'?'advantage':'disadvantage',
    text:name+': ('+printable.join(', ')+') '+(flat<0?'−':'+')+' '+Math.abs(flat)+' = '+total};
}
function checkName(kind,key,a){
  if(kind==='skill')return a.data.skills[key]?.name??key.split('_').map(w=>w[0].toUpperCase()+w.slice(1)).join(' ');
  if(kind==='initiative')return 'Initiative';
  const names={STR:'Strength',DEX:'Dexterity',CON:'Constitution',INT:'Intelligence',WIS:'Wisdom',CHA:'Charisma'};
  return names[key]+(kind==='save'?' Saving Throw':' Check');
}
function assertSpecification(s){
  if(s.kind==='custom'){
    must(s.name && s.dice!=null && s.sides!=null,'CUSTOM_FORMULA_REQUIRED');
    if(s.mode!=='normal')must(s.dice===1&&s.sides===20,'D20_MODE_UNAVAILABLE');
  }else{
    must(s.dice===undefined&&s.sides===undefined&&s.flat===undefined&&s.name===undefined,'DERIVED_MODIFIER_REQUIRED');
    if(s.kind==='initiative')must(s.key==='initiative','INVALID_INITIATIVE');
    if(s.kind==='ability'||s.kind==='save')must(stats.includes(s.key),'INVALID_ABILITY');
  }
}
async function validateContext(ctx,actorId,s) {
  // Tags with mechanical benefits must be established by an independently recorded context.
  const a=await actor(ctx,actorId);
  for(const tag of s.contextTags??[])must(a.data.contextTags?.includes(tag),'ROLL_CONTEXT_UNVERIFIED');
}
async function calculate(ctx,actorId,s,e){
  assertSpecification(s);
  await validateContext(ctx,actorId,s);
  const a=await actor(ctx,actorId),projection=await sheet(ctx,actorId,s.contextTags??[]);
  const policy=rollPolicy(projection.effects,s.kind+':'+s.key,s.mode);
  const custom=s.kind==='custom';
  const signed=custom?(s.flat??0):s.kind==='ability'?projection.checks[s.key]?.value:
    s.kind==='save'?projection.saves[s.key]?.value:s.kind==='skill'&&Object.hasOwn(projection.skills,s.key)?
    projection.skills[s.key].value:s.kind==='initiative'?projection.initiative.value:null;
  must(signed!=null,'CHECK_MODIFIER_UNKNOWN');
  const isCheck=!custom || s.sides===20 && s.dice===1;
  const effective=isCheck?policy:{mode:'normal',count:custom?s.dice:1};
  const keeps=effective.mode==='normal'?'sum':effective.mode==='advantage'?'highest':'lowest';
  const result=observed(custom?s.name:checkName(s.kind,s.key,a),isCheck?effective.count:s.dice,custom?s.sides:20,signed,keeps,e,isCheck);
  return {...result,mode:effective.mode,actorId,formId:projection.formId,kind:s.kind,key:s.key,requestedMode:s.mode,
    sourceRevision:a.revision,recordedAt:ctx.now,commandId:ctx.commandId};
}
async function recordCheck(ctx,x){
  const result=await calculate(ctx,x.actorId,x.check,x.evidence);
  if(x.forUseId){
    const use=await ctx.get('action_use',x.forUseId);
    must(use?.life==='active' && use.data.targets?.some(t=>t.actorId===x.actorId&&['pending_save','frightened'].includes(t.status)) &&
      x.check.kind==='save' && x.check.key===use.data.save?.ability,'NO_MATCHING_SAVE_REQUEST');
    result.forUseId=x.forUseId;
  }
  if(x.forAttemptId){
    const attempt=await ctx.get('attack_attempt',x.forAttemptId);
    must(attempt?.life==='active' && attempt.data.resolution==='save' && x.check.kind==='save' && attempt.data.save.ability===x.check.key,'NO_MATCHING_SAVE_REQUEST');
    result.forAttemptId=x.forAttemptId;
  }
  await insert(ctx,'roll',x.rollId,x.actorId,result.name,result);
  if(x.forUseId)await ctx.link(x.rollId,'for_use',x.forUseId);
  if(x.forAttemptId)await ctx.link(x.rollId,'for_attempt',x.forAttemptId);
  return {rollId:x.rollId,...result};
}
async function batch(ctx,x){
  await actor(ctx,x.actorId);
  const ids=new Set();
  const results=[];
  for(const entry of x.entries){
    must(!ids.has(entry.rollId),'DUPLICATE_ROLL_ID');ids.add(entry.rollId);
    if(!entry.evidence){
      assertSpecification(entry.check);
      results.push({rollId:entry.rollId,status:'pending',check:entry.check});
    }else{
      results.push(await recordCheck(ctx,{actorId:x.actorId,...entry}));
    }
  }
  must(results.length>0,'EMPTY_BATCH');
  const status=results.every(r=>r.status==='resolved')?'resolved':'pending';
  await insert(ctx,'roll_batch',x.batchId,x.actorId,'Roll batch',{actorId:x.actorId,results,status,commandId:ctx.commandId});
  for(const r of results)if(r.status==='resolved')await ctx.link(x.batchId,'includes_roll',r.rollId);
  return {batchId:x.batchId,status,results};
}
async function savePreset(ctx,x){
  await actor(ctx,x.actorId);
  must(x.entries.length>0,'EMPTY_PRESET');
  for(const check of x.entries)assertSpecification(check);
  const existing=await ctx.get('roll_preset',x.presetId);
  const data={entries:x.entries};
  if(x.update){
    must(existing?.life==='active' && existing.actorId===x.actorId,'PRESET_UNAVAILABLE');
    await ctx.put({...existing,title:x.name,data});
  }else await insert(ctx,'roll_preset',x.presetId,x.actorId,x.name,data);
  return {presetId:x.presetId,name:x.name,entries:x.entries};
}
export const rollOperations=Object.freeze({
  record_check:op('rolls',fields({actorId:identifier,rollId:identifier,check:checkSpec,evidence,forUseId:identifier,forAttemptId:identifier},['actorId','rollId','check','evidence']),recordCheck),
  record_roll_batch:op('rolls',fields({actorId:identifier,batchId:identifier,entries:arrayOf(fields({rollId:identifier,check:checkSpec,evidence},['rollId','check']),24)},['actorId','batchId','entries']),batch),
  save_roll_preset:op('rolls',fields({actorId:identifier,presetId:identifier,name:named(120),entries:arrayOf(checkSpec,24),update:boolean},['actorId','presetId','name','entries','update']),savePreset),
  execute_roll_preset:op('rolls',fields({actorId:identifier,presetId:identifier,batchId:identifier,rolls:arrayOf(fields({rollId:identifier,evidence},['rollId']),24)},['actorId','presetId','batchId','rolls']),async(ctx,x)=>{
    const p=await owned(ctx,'roll_preset',x.presetId,x.actorId);
    must(x.rolls.length===p.data.entries.length,'PRESET_EVIDENCE_COUNT');
    const result=await batch(ctx,{...x,entries:p.data.entries.map((check,i)=>({...x.rolls[i],check}))});
    await ctx.link(x.batchId,'used_preset',p.id);
    return result;
  })
});
