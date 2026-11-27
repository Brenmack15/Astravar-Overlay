// Whole-form recovery on two independent local D1/R2 roots. Accepts no campaign input.
const assert = require('node:assert/strict');
const {createHash, randomBytes} = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {createInterface} = require('node:readline');
const provider = require('../tools/ascension_provider.cjs');

const mod = process.argv[2] ? path.resolve(process.argv[2]) : undefined;
const serve = process.argv.includes('--serve');
const origin = 'https://form-recovery.example.test', owner = 'form-fixture-owner';
const continuity = 'qa-whole-form-recovery', route = '/v3/continuities/' + continuity;
const ownerHeaders = {'oai-authenticated-user-id':owner,Origin:origin};
const frame = 'frame-unit', shape = 'shape-unit';
const profiles = [
  {id:frame,title:'Fixture frame',currentHp:55,maxHp:60,activeFormId:'rig-form',formIds:['light-frame','rig-form'],
    pool:{id:'rig-integrity',current:56},item:{id:'shell-rig',title:'Fixture shell-rig'}},
  {id:shape,title:'Fixture shifter',currentHp:144,maxHp:154,activeFormId:'winged-hybrid',formIds:['original','civilian','winged-hybrid'],
    pool:{id:'shared-breath',current:0},item:{id:'primary-focus',title:'Fixture primary-focus'}}
];
const categories = ['character','combat','resources','inventory','media','chronicle','spells','rolls'];
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

async function response(r,endpoint,body,headers=ownerHeaders) {
  return r.dispatch(origin+endpoint,{method:body==null?'GET':'POST',headers:{'Content-Type':'application/json',...headers},
    ...(body==null?{}:{body:JSON.stringify(body)})});
}
async function required(r,endpoint,body=null,headers=ownerHeaders) {
  const reply=await response(r,endpoint,body,headers), result=await reply.json();
  assert.ok(reply.ok,endpoint+' '+JSON.stringify(result));return result;
}
async function rpc(r,name,args={}) {
  const v=await required(r,'/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId:continuity,...args}}});
  assert.ok(v.result&&!v.result.isError,name+' '+JSON.stringify(v.result?.content||v.error));return v.result.structuredContent;
}
async function grant(r,subject,expiresAt) {
  const writes=categories.map(c=>c+'.write');
  await required(r,route+'/grants/'+subject,{scopes:categories.flatMap(c=>[c+'.read',c+'.write']),actors:['*'],auto:writes,
    expiresAt,expectedGrantRevision:0,revoked:false});
}
async function png(r,endpoint,headers=ownerHeaders) {
  const reply=await response(r,endpoint,null,headers);
  assert.equal(reply.status,200,endpoint);assert.match(reply.headers.get('content-type'),/^image\/png/);
  return Buffer.from(await reply.arrayBuffer());
}
async function seed(r) {
  const {defineFrameFixture,defineMorphFixture}=await import('../backend/owner-bridge/fixtures/representative-forms.mjs');
  const {encodeMediaPng}=await import('../backend/owner-bridge/cloud-media-codec.mjs');
  await required(r,route+'/isolated',{});
  await grant(r,'sites-mcp',Date.now()+3_600_000);
  let revision=0;
  const receipts=[];
  const command=async (operation,args) => {
    const commandId='form-cold-'+String(revision+1).padStart(4,'0');
    let result=await rpc(r,'submit_cloud_command',{command:{commandId,version:1,operation,args,expectedRevision:revision,epoch:1}});
    if(operation==='assign_primary_image'){
      assert.equal(result.status,'PENDING_APPROVAL');const exact=await png(r,route+'/media/uploads/'+args.uploadId+'/original');
      assert.equal(digest(exact),args.sha256);
      result=await required(r,route+'/approvals/'+commandId+'/verify',{digest:result.digest});
    }
    assert.equal(result.status,'COMMITTED',operation+' '+JSON.stringify(result));
    assert.equal(result.revision,++revision);
    assert.equal((await rpc(r,'get_cloud_receipt',{commandId})).digest,result.digest);
    receipts.push(result);
  };
  const fixture=(actorId,name)=>({
    command,
    defined:(values)=>command('define_actor',{actorId,name,role:'player',rulesProfile:'revised_2024',level:12,
      abilities:{STR:8,DEX:14,CON:14,INT:20,WIS:11,CHA:10},baseAc:16,maxHp:60,currentHp:42,temporaryHp:0,gameSeconds:100,attacksPerAction:2,...values}),
    pool:(poolId,type,current,maximum,recovery,more={})=>command('define_resource',{actorId,poolId,name:poolId,type,current,maximum,recovery,source:'fixture',...more}),
    effect:(effectId,target,key,operation,more={})=>command('define_effect',{actorId,effectId,name:effectId,target,key,operation,source:'fixture',...more})
  });
  await defineFrameFixture(fixture(frame,profiles[0].title),frame);
  await command('define_form',{actorId:frame,formId:'light-frame',name:'Light frame',source:'Synthetic base configuration',
    size:'tiny',creatureType:'construct',baseAc:16,speeds:{walk:5,fly:60},timing:'other',activationSeconds:0});
  await defineMorphFixture(fixture(shape,profiles[1].title),shape);
  await command('apply_hp_change',{actorId:frame,kind:'damage',amount:5});
  const destination={label:'Synthetic surface',distanceFeet:30,unoccupied:true,visible:true,grounded:true,availableSize:'large',passengers:0,permittedByBarriers:true};
  const shift=useId=>command('use_action',{actorId:frame,actionId:'shift',useId,declared:true,destination});
  await shift('before-frame-shift');
  await command('change_form',{actorId:frame,formId:'rig-form',elapsedSeconds:60,uninterrupted:true,willing:true});
  await shift('after-frame-shift');
  await command('apply_hp_change',{actorId:frame,kind:'damage',amount:19});
  await command('use_action',{actorId:frame,actionId:'cry',useId:'one-deployment-cry',declared:true,
    targets:[{actorId:'listener',hears:true,distanceFeet:30}]});
  await command('apply_hp_change',{actorId:shape,kind:'damage',amount:10});
  for(const formId of ['original','civilian','winged-hybrid'])await command('change_form',{actorId:shape,formId,elapsedSeconds:0,willing:true});
  await command('use_action',{actorId:shape,actionId:'extended',useId:'source-enhancement',declared:true});
  await command('resolve_attack',{actorId:shape,attackId:'focused',attemptId:'resolved-route',mode:'normal',includeDamage:false});
  const pictures={};
  const colors=[[42,72,130],[74,188,155],[193,70,55],[153,173,86],[89,62,157],[170,103,47],[59,130,154]];
  for(const actor of profiles) for(const targetId of [actor.id,...actor.formIds]){
    const color=colors[Object.keys(pictures).length], width=172,height=12;
    const pixels=Buffer.alloc(width*height*4);for(let i=0;i<pixels.length;i+=4)pixels.set([...color,255],i);
    const original=Buffer.from(await encodeMediaPng(width,height,pixels)),targetKind=targetId===actor.id?'actor':'form';
    const args={targetKind,targetId,sha256:digest(original),uploadId:'upload-'+targetId};
    const staged=await rpc(r,'stage_cloud_image',{...args,bytesBase64:original.toString('base64')});
    assert.equal(staged.sha256,args.sha256);
    await command('assign_primary_image',{...args,assetId:'image-'+targetId});
    const thumbnail=await png(r,route+'/media/primary/'+targetId+'/thumbnail');
    pictures[targetId]={sha256:digest(original),thumbnailSha256:digest(thumbnail)};
    assert.notEqual(pictures[targetId].sha256,pictures[targetId].thumbnailSha256);
  }
  assert.equal(new Set(Object.values(pictures).flatMap(p=>[p.sha256,p.thumbnailSha256])).size,14);
  return {receipts,pictures,revision};
}
async function capture(r,expected) {
  const records=[];let page,after=null;
  do {
    page=await rpc(r,'get_cloud_state',after?{limit:50,after,atEpoch:page.epoch,atRevision:page.revision,grantRevision:page.grantRevision}:{limit:50});
    assert.equal(page.revision,expected.revision);records.push(...page.records);after=page.nextAfter;
  }while(after);
  const byId=new Map(records.map(v=>[v.id,v]));assert.equal(records.length,byId.size);
  const projections=[];
  for(const profile of profiles){
    const sheet=(await rpc(r,'get_cloud_actor_sheet',{id:profile.id})).sheet;
    const actions=(await rpc(r,'get_cloud_actions',{id:profile.id})).rows;
    const equipment=(await rpc(r,'get_cloud_record_view',{view:'inventory',id:profile.id})).items;
    assert.deepEqual([sheet.formId,sheet.currentHp,sheet.maxHp],[profile.activeFormId,profile.currentHp,profile.maxHp]);
    assert.equal(byId.get(profile.id).title,profile.title);
    assert.equal(byId.get(profile.pool.id).data.current,profile.pool.current);
    assert.equal(equipment.find(x=>x.id===profile.item.id).title,profile.item.title);
    const forms={};
    for(const formId of profile.formIds){
      const preview=(await rpc(r,'preview_cloud_form',{id:profile.id,formId})).preview;
      assert.equal(preview.formId,formId);assert.equal(preview.status,'preview');
      assert.deepEqual([preview.activeFormId,preview.projection.currentHp],[profile.activeFormId,profile.currentHp]);
      forms[formId]=preview;
    }
    const itemIds=equipment.map(x=>x.id).sort();
    if(profile.id===frame){
      assert.deepEqual(itemIds,['dial','keepsake','optic','shell-rig','stabilizer','step','vocoder','ward']);
      assert.deepEqual(equipment.filter(x=>x.data.attuned).map(x=>x.id),['ward']);
      assert.deepEqual(equipment.filter(x=>x.data.equipment==='packed').map(x=>x.id),['keepsake']);
      assert.deepEqual(['blink','deploy','roar','rig-integrity','dial-pool'].map(k=>byId.get(k).data.current),[1,0,0,56,1]);
      assert.deepEqual([sheet.abilities.STR.effective,sheet.ac.value,sheet.movement.fly,sheet.movement.walk],[20,18,0,40]);
      assert.deepEqual([forms['light-frame'].projection.abilities.STR.effective,forms['light-frame'].projection.ac.value,forms['light-frame'].projection.movement.fly],[8,16,60]);
      assert.ok(actions.some(a=>a.id==='large-bite'&&a.hit===9));assert.ok(!actions.some(a=>a.id==='rake'));
    }else{
      assert.deepEqual(itemIds,['harness','primary-focus','reserve-focus','tattoo']);
      assert.deepEqual(equipment.filter(x=>x.data.attuned).map(x=>x.id),['tattoo']);
      assert.equal(byId.get('primary-focus').data.operating,true);assert.equal(byId.get('reserve-focus').data.equipment,'packed');
      assert.deepEqual(['shared-breath','shared-mode'].map(k=>byId.get(k).data.current),[0,0]);
      assert.deepEqual([sheet.ac.value,sheet.movement.fly,sheet.movement.swim,forms.original.projection.movement.swim,forms.civilian.projection.movement.fly],[19,40,null,40,0]);
      assert.ok(actions.some(a=>a.id==='claw-hybrid'));assert.ok(!actions.some(a=>a.id==='frost'));
    }
    projections.push({sheet,actions,equipment,forms});
  }
  for(const [id,hashes] of Object.entries(expected.pictures)){
    for(const [variant,key] of [['original','sha256'],['thumbnail','thumbnailSha256']]){
      assert.equal(byId.get('image-'+id).data[key],hashes[key]);
      assert.equal(digest(await png(r,route+'/media/primary/'+id+'/'+variant)),hashes[key]);
    }
  }
  for(const receipt of expected.receipts) assert.deepEqual(await rpc(r,'get_cloud_receipt',{commandId:receipt.commandId}),receipt);
  return {records,projections};
}
async function pairedClient(r) {
  const device='native-recovery-client',bearer=randomBytes(32).toString('hex'),expiresAt=Date.now()+3_600_000;
  await r.db.prepare("INSERT INTO bridge_credentials(id,owner_id,campaign_id,token_hash,role,scopes_json,device_name,expires_at) VALUES(?,?,?,?,'device','[]','Synthetic recovery client',?)")
    .bind(device,owner,continuity,digest(bearer),expiresAt).run();
  await grant(r,'device:'+device,expiresAt);return {origin,continuity,device,bearer,expiresAt};
}
async function serveNative(r,expected,captureResult) {
  const auth=await pairedClient(r),byId=new Map(captureResult.records.map(v=>[v.id,v]));
  const actors=profiles.map(({formIds,...a})=>({...a,thumbnailSha256:expected.pictures[a.id].thumbnailSha256,
    forms:formIds.map(id=>({id,title:byId.get(id).title,...expected.pictures[id]}))}));
  const emit=v=>process.stdout.write(JSON.stringify(v)+'\n');emit({...auth,fixture:{actors}});
  for await(const line of createInterface({input:process.stdin})) {
    try {
      const {call,route:endpoint,body,credentialId,token,name,args}=JSON.parse(line);
      if(call==='dm'){assert.match(name,/^(get_cloud_|preview_cloud_form$)/);emit(await rpc(r,name,args));continue;}
      assert.ok(typeof endpoint==='string'&&endpoint.startsWith(route+'/'));
      assert.ok(body==null,'Recovery readback is read only');
      const reply=await response(r,endpoint,null,{'X-Astravar-Credential':credentialId,Authorization:'Bearer '+token});
      if(call==='media')emit({status:reply.status,bytesBase64:Buffer.from(await reply.arrayBuffer()).toString('base64')});
      else{assert.equal(call,'request');emit({status:reply.status,json:await reply.json()});}
    }catch(error){emit({fixtureError:error.message});}
  }
}
async function verify() {
  process.umask(0o077);
  const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'astravar-synthetic-form-cold-')),source=path.join(tmp,'source'),destination=path.join(tmp,'destination');
  const old=process.cwd();process.chdir(tmp);
  try{
    await provider.create(source,owner,continuity,mod);
    const expected=await provider.withGameplay(source,seed,mod);
    const before=await provider.withGameplay(source,r=>capture(r,expected),mod);
    const archive=path.join(tmp,'verified-source.zip');
    const portable=await provider.backup(source,archive,mod);
    assert.equal(portable.source.revision,expected.revision);
    // Dispose the only writer and its entire temporary root before restoring into fresh bindings.
    fs.rmSync(source,{recursive:true});
    await provider.create(destination,owner,continuity,mod);
    assert.equal((await provider.inspect(destination,mod)).current,null);
    const preview=await provider.prepare(destination,archive,null,mod);
    assert.equal((await provider.prepare(destination,archive,preview.approvalSha256,mod)).status,'STAGED');
    const cut=await provider.cutover(destination,null,false,mod);
    const outcome=await provider.cutover(destination,cut.approvalSha256,false,mod);
    assert.equal(outcome.epoch,2);assert.equal(outcome.revision,expected.revision);
    await provider.withGameplay(destination,async r=>{
      assert.equal((await r.db.prepare('SELECT count(*) AS n FROM cloud_grants').first()).n,0);
      assert.equal((await r.db.prepare('SELECT count(*) AS n FROM bridge_credentials').first()).n,0);
      await grant(r,'sites-mcp',Date.now()+3_600_000);
      const after=await capture(r,expected);assert.deepEqual(after,before);
      if(serve)await serveNative(r,expected,after);
      else console.log(JSON.stringify({status:'VERIFIED_PROVIDER_FORM_RECOVERY',epoch:outcome.epoch,revision:expected.revision,
        mechanicsReceipts:expected.receipts.length,records:after.records.length,forms:5,physicalHoldings:12,portraits:7,verifiedMediaVariants:14}));
    },mod);
  }finally{process.chdir(old);fs.rmSync(tmp,{recursive:true,force:true});}
}
verify().catch(e=>{console.error(e);process.exitCode=1;});
