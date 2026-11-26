import { checkDiceOwner, stats, identifier, named, whole, arrayOf, fields, option, boolean, checkSpec, evidence, must, op, owned, actor, insert, save, sheet, rollPolicy } from './cloud-mechanics-core.mjs';

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
    must(s.name && (s.terms!==undefined ? s.dice===undefined && s.sides===undefined : s.dice!=null && s.sides!=null),'CUSTOM_FORMULA_REQUIRED');
    if(s.terms){
      must(s.mode==='normal' && s.terms.reduce((n,t)=>n+t.count,0)<=200 && (s.terms.length>0||s.flat!=null),'CUSTOM_FORMULA_INVALID');
    }else if(s.mode!=='normal')must(s.dice===1&&s.sides===20,'D20_MODE_UNAVAILABLE');
  }else{
    must(s.dice===undefined&&s.sides===undefined&&s.flat===undefined&&s.name===undefined&&s.terms===undefined,'DERIVED_MODIFIER_REQUIRED');
    if(s.kind==='initiative')must(s.key==='initiative','INVALID_INITIATIVE');
    if(s.kind==='ability'||s.kind==='save')must(stats.includes(s.key),'INVALID_ABILITY');
  }
}
async function validateContext(ctx,actorId,s) {
  // Tags with mechanical benefits must be established by an independently recorded context.
  const a=await actor(ctx,actorId);
  for(const tag of s.contextTags??[])must(a.data.contextTags?.includes(tag),'ROLL_CONTEXT_UNVERIFIED');
}
async function calculate(ctx,actorId,s,e,verifiedTags=[]){
  assertSpecification(s);
  await validateContext(ctx,actorId,s);
  const a=await actor(ctx,actorId),projection=await sheet(ctx,actorId,[...new Set([...(s.contextTags??[]),...verifiedTags])]);
  checkDiceOwner(ctx,a,s.kind,e.origin);
  const restriction=projection.rollRestrictions[s.kind+':'+s.key];must(!restriction,restriction);
  const policy=rollPolicy(projection.effects,s.kind+':'+s.key,s.mode);
  const custom=s.kind==='custom';
  const signed=custom?(s.flat??0):s.kind==='ability'?projection.checks[s.key]?.value:
    s.kind==='save'?projection.saves[s.key]?.value:s.kind==='skill'&&Object.hasOwn(projection.skills,s.key)?
    projection.skills[s.key].value:s.kind==='initiative'?projection.initiative.value:null;
  must(signed!=null,'CHECK_MODIFIER_UNKNOWN');
  if(custom && s.terms){
    const faces=[];let total=signed;
    for(const t of s.terms)for(let i=0;i<t.count;i++){
      const value=e.faces[faces.length];
      must(Number.isInteger(value) && value>=1 && value<=t.sides,'INVALID_RECORDED_FACES');
      const sign=t.sign==='add'?1:-1; faces.push({sides:t.sides,value,kept:true,sign}); total+=sign*value;
    }
    must(e.faces.length===faces.length,'INVALID_RECORDED_FACES');
    const terms=faces.map((f,i)=>(f.sign<0?'− ':i>0?'+ ':'')+'('+f.value+')');
    if(signed||!terms.length)terms.push((signed<0?'− ':terms.length?'+ ':'')+Math.abs(signed));
    return {name:s.name,status:'resolved',origin:e.origin,faces,modifier:signed,total,natural:null,mode:'normal',
      text:s.name+': '+terms.join(' ')+' = '+total,actorId,formId:projection.formId,kind:s.kind,key:s.key,
      requestedMode:s.mode,sourceRevision:a.revision,recordedAt:ctx.now,commandId:ctx.commandId};
  }
  const isCheck=!custom || s.sides===20 && s.dice===1;
  const effective=isCheck?policy:{mode:'normal',count:custom?s.dice:1};
  const keeps=effective.mode==='normal'?'sum':effective.mode==='advantage'?'highest':'lowest';
  const result=observed(custom?s.name:checkName(s.kind,s.key,a),isCheck?effective.count:s.dice,custom?s.sides:20,signed,keeps,e,isCheck);
  return {...result,mode:effective.mode,actorId,formId:projection.formId,kind:s.kind,key:s.key,requestedMode:s.mode,
    sourceRevision:a.revision,recordedAt:ctx.now,commandId:ctx.commandId};
}
async function recordCheck(ctx,x){
  const tags=[];
  if(x.forSenseId){
    const use=await owned(ctx,'action_use',x.forSenseId,x.actorId),sensor=use.data.sensor;
    must(sensor?.eligible && sensor.status==='pending_check' && x.check.kind==='skill'&&x.check.key===sensor.skillId,'SENSOR_CHECK_UNAVAILABLE');
    tags.push(sensor.tag);await save(ctx,use,{...use.data,sensor:{...sensor,status:'check_recorded',rollId:x.rollId}});
  }
  const result=await calculate(ctx,x.actorId,x.check,x.evidence,tags);
  if(x.forSenseId)result.forSenseId=x.forSenseId;
  must([x.forUseId,x.forAttemptId,x.forCastId,x.forConcentrationId,x.forSenseId].filter(Boolean).length<=1,'INCOMPATIBLE_ROLL_REQUESTS');
  if(x.forConcentrationId){
    const pending=await owned(ctx,'concentration_check',x.forConcentrationId,x.actorId),a=await actor(ctx,x.actorId);
    must(pending.data.status==='pending' && a.data.concentrationCastId===pending.data.castId && x.check.kind==='save' && x.check.key==='CON','NO_MATCHING_CONCENTRATION_SAVE');
    result.forConcentrationId=pending.id;
  }
  if(x.forUseId){
    const use=await ctx.get('action_use',x.forUseId);
    must(use?.life==='active' && use.data.targets?.some(t=>t.actorId===x.actorId&&['pending_save','frightened'].includes(t.status)) &&
      x.check.kind==='save' && x.check.key===use.data.save?.ability,'NO_MATCHING_SAVE_REQUEST');
    result.forUseId=x.forUseId;
  }
  if(x.forCastId){
    const cast=await ctx.get('cast_instance',x.forCastId);
    must(cast?.life==='active' && cast.data.resolution==='save' && cast.data.targets.includes(x.actorId) && !cast.data.targetResults?.some(r=>r.targetActorId===x.actorId) &&
      x.check.kind==='save' && cast.data.save.ability===x.check.key,'NO_MATCHING_SAVE_REQUEST');
    result.forCastId=x.forCastId;
  }
  if(x.forAttemptId){
    const attempt=await ctx.get('attack_attempt',x.forAttemptId);
    must(attempt?.life==='active' && attempt.data.resolution==='save' && attempt.data.targets?.includes(x.actorId) && !attempt.data.targetResults?.some(r=>r.targetActorId===x.actorId) && x.check.kind==='save' && attempt.data.save.ability===x.check.key,'NO_MATCHING_SAVE_REQUEST');
    result.forAttemptId=x.forAttemptId;
  }
  await insert(ctx,'roll',x.rollId,x.actorId,result.name,result);
  if(x.forSenseId)await ctx.link(x.rollId,'sensor_observation',x.forSenseId);
  if(x.forConcentrationId)await ctx.link(x.rollId,'for_concentration',x.forConcentrationId);
  if(x.forUseId)await ctx.link(x.rollId,'for_use',x.forUseId);
  if(x.forCastId)await ctx.link(x.rollId,'for_cast',x.forCastId);
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
      const a=await actor(ctx,x.actorId);
      const name=entry.check.kind==='custom'?entry.check.name:checkName(entry.check.kind,entry.check.key,a);
      const pending={actorId:x.actorId,batchId:x.batchId,status:'pending',name,check:entry.check,requestedAt:ctx.now};
      await insert(ctx,'roll',entry.rollId,x.actorId,name,pending);
      results.push({rollId:entry.rollId,...pending});
    }else{
      results.push(await recordCheck(ctx,{actorId:x.actorId,...entry}));
    }
  }
  must(results.length>0,'EMPTY_BATCH');
  const status=results.every(r=>r.status==='resolved')?'resolved':'pending';
  await insert(ctx,'roll_batch',x.batchId,x.actorId,'Roll batch',{actorId:x.actorId,results,status,commandId:ctx.commandId});
  for(const r of results)await ctx.link(x.batchId,'includes_roll',r.rollId);
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
  set_roll_context:{...op('character',fields({actorId:identifier,tag:identifier,active:boolean,source:named(300)},['actorId','tag','active','source']),async(ctx,x)=>{
    const a=await actor(ctx,x.actorId), tags=new Set(a.data.contextTags??[]);
    if(x.active)tags.add(x.tag);else tags.delete(x.tag);
    must(tags.size<=32,'ROLL_CONTEXT_LIMIT');
    await save(ctx,a,{...a.data,contextTags:[...tags].sort()});
    return {actorId:a.id,tag:x.tag,active:x.active,source:x.source};
  }),ownerReview:true},
  complete_pending_check:op('rolls',fields({actorId:identifier,batchId:identifier,rollId:identifier,evidence},['actorId','batchId','rollId','evidence']),async(ctx,x)=>{
    const r=await owned(ctx,'roll',x.rollId,x.actorId),b=await owned(ctx,'roll_batch',x.batchId,x.actorId);
    must(r.data.status==='pending' && r.data.batchId===b.id && b.data.results.some(e=>e.rollId===r.id&&e.status==='pending'),'ROLL_NOT_PENDING');
    const result={...await calculate(ctx,x.actorId,r.data.check,x.evidence),rollId:r.id,batchId:b.id,requestedAt:r.data.requestedAt};
    await save(ctx,r,result);
    const results=b.data.results.map(e=>e.rollId===r.id?result:e),status=results.every(e=>e.status==='resolved')?'resolved':'pending';
    await save(ctx,b,{...b.data,results,status});return {batchId:b.id,status,result};
  }),
  record_check:op('rolls',fields({actorId:identifier,rollId:identifier,check:checkSpec,evidence,forUseId:identifier,forSenseId:identifier,forAttemptId:identifier,forCastId:identifier,forConcentrationId:identifier},['actorId','rollId','check','evidence']),recordCheck),
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
