#!/usr/bin/env node
/** Cold, isolated recovery via real local workerd D1/R2 bindings. The private
 * runtime generation becomes reachable only after verified atomic publication.
 * No provider account, URL, credentials or deployment API is accepted. */
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const {createHash,randomUUID}=require('node:crypto');
const {execFileSync}=require('node:child_process');
const rootProject=path.resolve(__dirname,'..');
const bridge=path.join(rootProject,'backend/owner-bridge');
const packageBridge=path.join(__dirname,'ascension_provider_package.py');
const shape=/^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$/;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const rowLimit=25000,tableBudget=32*1024*1024,snapshotBudget=128*1024*1024;
function check(condition,message){if(!condition)throw Error(message);}
function stable(value){
  if(Array.isArray(value))return '['+value.map(stable).join(',')+']';
  if(value!==null && typeof value==='object')return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+stable(value[k])).join(',')+'}';
  return JSON.stringify(value);
}
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const fingerprint=value=>sha(stable(value));
function packageDigest(file){
  const fd=fs.openSync(file,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW),hash=createHash('sha256'),buffer=Buffer.alloc(65536);
  try{
    const st=fs.fstatSync(fd);
    check(st.isFile() && st.size<=258*1024*1024,'Expected a bounded regular recovery package');
    let size=0,count;
    while((count=fs.readSync(fd,buffer))>0){
      size+=count;check(size<=st.size,'Package changed while reading');hash.update(buffer.subarray(0,count));
    }
    check(size===st.size,'Package size changed while reading');
    return hash.digest('hex');
  }finally{fs.closeSync(fd);}
}
function python(operation,...args){
  const bytes=execFileSync('python3',[packageBridge,operation,...args],{encoding:'utf8',maxBuffer:16<<20,stdio:['ignore','pipe','pipe']});
  return JSON.parse(bytes);
}
function scratch(fn){
  const folder=fs.mkdtempSync(path.join(os.tmpdir(),'astravar-provider-'));
  return Promise.resolve().then(()=>fn(folder)).finally(()=>fs.rmSync(folder,{recursive:true,force:true}));
}
function readPrivate(file){
  const status=fs.lstatSync(file);
  check(status.isFile() && (status.mode & 0o077)===0,'Expected a private regular file');
  return JSON.parse(fs.readFileSync(file,'utf8'));
}
function manifest(folder){
  const stat=fs.lstatSync(folder);
  check(stat.isDirectory() && (stat.mode & 0o077)===0,'Expected a private local provider root');
  const value=readPrivate(path.join(folder,'provider.json'));
  check(value.format==='astravar-local-bound-recovery-v1' && shape.test(value.owner) && /^qa-/.test(value.continuity) &&
    shape.test(value.continuity) && uuid.test(value.active) && uuid.test(value.id),'Unrecognized provider root');
  return value;
}
function save(folder,obj){
  const file=path.join(folder,'.'+randomUUID()+'.partial'),fd=fs.openSync(file,'wx',0o600);
  try{fs.writeFileSync(fd,stable(obj));fs.fsyncSync(fd);}finally{fs.closeSync(fd);}
  fs.renameSync(file,path.join(folder,'provider.json'));
  const dir=fs.openSync(folder,'r');try{fs.fsyncSync(dir);}finally{fs.closeSync(dir);}
}
function genPath(root,id){check(uuid.test(id),'Invalid provider generation');return path.join(root,'generations',id);}
function withLock(root,fn){
  const fd=fs.openSync(path.join(root,'admin.lock'),fs.constants.O_RDWR|fs.constants.O_NOFOLLOW);
  try{execFileSync('flock',['--exclusive','--nonblock','3'],{stdio:['ignore','ignore','ignore',fd]});}
  catch{fs.closeSync(fd);throw Error('Another local provider operation holds the maintenance lock');}
  return Promise.resolve().then(()=>fn(manifest(root))).finally(()=>fs.closeSync(fd));
}
function resource(root,id,worker,fn,modulePath){
  return scratch(async temp=>{
    const {Miniflare}=require(modulePath?path.resolve(modulePath):'miniflare');
    const dir=genPath(root,id);
    check(fs.lstatSync(dir).isDirectory(),'Unknown provider generation');
    let contents='export default {fetch(){return new Response(null,{status:404})}}';
    const capability=randomUUID();
    if(worker){
      execFileSync(process.execPath,[path.join(bridge,'build-worker.mjs'),temp],{stdio:'pipe'});
      contents=fs.readFileSync(path.join(temp,'server/index.js'),'utf8');
      const entry='export default ENTRY.default;';
      check(contents.includes(entry),'Unknown private Worker entry');
      contents=contents.replace(entry,`export default {fetch(request,env,ctx){
        if(request.headers.get('x-local-rehearsal')!==env.LOCAL_CAPABILITY)return new Response(null,{status:404});
        return ENTRY.default.fetch(request,env,ctx)}};`);
    }
    const text=value=>({type:'text',value});
    const opts={host:'127.0.0.1',port:0,resourcePersistencePath:dir,unsafeEnableSharedStorage:false,
      workers:[{config:{name:'ascension-local-recovery',compatibilityDate:'2025-09-15',
        manifest:{mainModule:'index.js',modules:{'index.js':{type:'esm',contents}}},
        env:{DB:{type:'d1',id:'00000000-0000-4000-8000-000000000042'},ASTRAVAR_MEDIA:{type:'r2',name:'private-ascension-recovery'},
          OWNER_SITE_USER_ID:text(worker?.owner??'unused'),ASTRAVAR_CLOUD_ENABLED:text('1'),
          ASTRAVAR_CLOUD_ENV:text('isolated'),LOCAL_CAPABILITY:text(capability),
          ...(worker?.rate ? {ASTRAVAR_CLOUD_RATE_CAPACITY:text(String(worker.rate.capacity)),
            ASTRAVAR_CLOUD_RATE_REFILL_MS:text(String(worker.rate.refillMs))} : {})}}}]};
    const mf=new Miniflare(opts);
    try{
      const db=await mf.getD1Database('DB','ascension-local-recovery'),bucket=await mf.getR2Bucket('ASTRAVAR_MEDIA','ascension-local-recovery');
      const dispatch=async(url,init={})=>{
        check(worker,'Gameplay is disabled in maintenance mode');
        return mf.dispatchFetch(url,{...init,headers:{...init.headers,'x-local-rehearsal':capability}});
      };
      return await fn({db,bucket,dispatch});
    }finally{await mf.dispose();}
  });
}
async function initSchema(db){
  const migrations=path.join(bridge,'drizzle'),journal=JSON.parse(fs.readFileSync(path.join(migrations,'meta/_journal.json')));
  for(const entry of journal.entries){
    check(/^\d+_[a-zA-Z0-9_-]+$/.test(entry.tag),'Invalid migration name');
    const sql=fs.readFileSync(path.join(migrations,entry.tag+'.sql'),'utf8').split(/^--> statement-breakpoint\s*$/m).filter(v=>v.trim());
    await db.batch(sql.map(s=>db.prepare(s)));
  }
}
async function create(root,owner,continuity,mod){
  check(typeof owner==='string'&&typeof continuity==='string'&&shape.test(owner)&&shape.test(continuity)&&continuity.startsWith('qa-'),'A synthetic qa- continuity and local owner are required');
  fs.mkdirSync(root,{mode:0o700});
  const m={format:'astravar-local-bound-recovery-v1',id:randomUUID(),owner,continuity,active:randomUUID(),pending:null,intent:null,last:null};
  try{
    fs.mkdirSync(path.join(root,'generations'),{mode:0o700});fs.mkdirSync(genPath(root,m.active),{mode:0o700});
    fs.writeFileSync(path.join(root,'admin.lock'),'',{flag:'wx',mode:0o600});
    await resource(root,m.active,null,({db})=>initSchema(db),mod);
    save(root,m);return {status:'EMPTY_LOCAL_PROVIDER',production:false};
  }catch(error){fs.rmSync(root,{recursive:true,force:true});throw error;}
}
const scoped=' WHERE owner_id=? AND continuity_id=?';
const scope=m=>[m.owner,m.continuity];
async function snapshot(db,m,layout){
  const bind=(sql)=>db.prepare(sql).bind(...scope(m));
  const inspect=db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name GLOB 'cloud_*' ORDER BY name");
  const batch=[inspect,bind('SELECT * FROM cloud_continuities'+scoped),bind('SELECT * FROM cloud_grants'+scoped+' ORDER BY subject LIMIT '+(rowLimit+1))];
  for(const {name,columns,order} of layout)batch.push(bind('SELECT '+columns.join(',')+' FROM '+name+scoped+' ORDER BY '+order.join(',')+' LIMIT '+(rowLimit+1)));
  batch.push(...layout.map(t=>db.prepare('PRAGMA table_info('+t.name+')')));
  const [names,state,grants,...pages]=await db.batch(batch);
  const expected=[...layout.map(t=>t.name),'cloud_continuities','cloud_guards','cloud_grants'].sort();
  check(stable(names.results.map(r=>r.name).filter(name=>name!=='cloud_rate_buckets'))===stable(expected),'Unclassified D1 schema');
  check(state.results.length<=1 && grants.results.length<=rowLimit,'Invalid or oversized scoped state');
  const tables={};let total=0;
  for(const [i,t] of layout.entries()){
    check(stable(pages[layout.length+i].results.map(c=>c.name))===stable(t.columns),'Unclassified or missing D1 column');
    const rows=pages[i].results,n=Buffer.byteLength(JSON.stringify(rows));
    check(rows.length<=rowLimit && n<=tableBudget,'Provider snapshot table exceeds the local recovery budget');
    total+=n;tables[t.name]=rows;
  }
  check(total<=snapshotBudget,'Provider snapshot exceeds the local recovery budget');
  const row=state.results[0],origin=row?{authority:row.authority,epoch:row.epoch,revision:row.revision,schema:row.schema_version}:null;
  return {portable:{owner:m.owner,continuity:m.continuity,state:origin,tables},guard:fingerprint(grants.results)};
}
async function readPackageObjects(bucket,folder,snapshot){
  fs.writeFileSync(path.join(folder,'snapshot.json'),JSON.stringify(snapshot),{flag:'wx',mode:0o600});
  const directory=path.join(folder,'objects');fs.mkdirSync(directory,{mode:0o700});
  const objects=[];
  for(const expected of python('objects',folder)){
    const object=await bucket.get(expected.key);
    check(object && object.size===expected.size,'Missing or differently sized R2 object');
    const bytes=Buffer.from(await object.arrayBuffer());
    check(bytes.length===expected.size && sha(bytes)===expected.sha256,'R2 checksum mismatch');
    const target=path.join(directory,expected.key);
    fs.mkdirSync(path.dirname(target),{recursive:true,mode:0o700});fs.writeFileSync(target,bytes,{flag:'wx',mode:0o600});
    objects.push({key:expected.key,version:object.version,etag:object.etag,size:object.size});
  }
  return objects;
}
async function capture(runtime,m,layout,folder){
  const before=await snapshot(runtime.db,m,layout);
  check(before.portable.state,'No enrolled continuity to back up');
  const objects=await readPackageObjects(runtime.bucket,folder,before.portable);
  for(const obj of objects){
    const found=await runtime.bucket.head(obj.key);
    check(found && found.version===obj.version && found.etag===obj.etag && found.size===obj.size,'R2 changed during capture');
  }
  const after=await snapshot(runtime.db,m,layout);
  check(fingerprint(after)===fingerprint(before),'D1 changed during R2 capture');
  return before;
}
function sidecar(root,gen){
  const filename=path.join(genPath(root,gen),'source.zip');
  return fs.existsSync(filename)?['--evidence',filename]:[];
}
// A private local administrator may use already-open Miniflare bindings. This
// function issues read-only queries and exports one explicitly named QA scope.
async function backupBindings(runtime,owner,continuity,output,evidenceFile){
  check(typeof owner==='string' && shape.test(owner) && typeof continuity==='string' && shape.test(continuity) &&
    continuity.startsWith('qa-'),'A synthetic local scope is required');
  return scratch(async dir=>{
    await capture(runtime,{owner,continuity},python('layout'),dir);
    return python('pack',dir,output,...(evidenceFile?['--evidence',evidenceFile]:[]));
  });
}
async function backup(root,output,mod){
  return withLock(root,m=>{
    check(!m.intent,'Recover the pending cutover first');
    const evidence=sidecar(root,m.active);
    return resource(root,m.active,null,r=>backupBindings(r,m.owner,m.continuity,output,evidence[1]),mod);
  });
}
function covered(v){return {owner:v.owner,continuity:v.continuity,tables:v.tables,revision:v.state?.revision??null};}
async function checkSingleScope(runtime,m,portable){
  const {db,bucket}=runtime;
  const siblings=await db.prepare('SELECT 1 AS found FROM cloud_continuities WHERE owner_id<>? OR continuity_id<>? LIMIT 1').bind(...scope(m)).first();
  check(!siblings,'A shared provider root requires a separate tenant-preserving deployment');
  const unknown=await db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'cloud_*' AND name NOT GLOB 'bridge_*' AND name NOT GLOB 'sqlite_*' AND name<>'_cf_METADATA' LIMIT 1").first();
  if(unknown)throw Error('Unclassified local provider storage requires separate preservation ('+(/^[A-Za-z0-9_]+$/.test(unknown.name)?unknown.name:'unnamed')+')');
  const legacy=(await db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name GLOB 'bridge_*' ORDER BY name").all()).results;
  const statements=legacy.map(t=>{
    check(/^bridge_[a-z_]+$/.test(t.name),'Unclassified legacy provider table');
    if(t.name==='bridge_credentials')return db.prepare('SELECT 1 FROM bridge_credentials WHERE owner_id<>? OR campaign_id<>? LIMIT 1').bind(...scope(m));
    return db.prepare('SELECT 1 FROM '+t.name+' LIMIT 1');
  });
  check((await db.batch(statements)).every(r=>!r.results.length),'Legacy provider state requires a separate source-preserving recovery');
  await scratch(async dir=>{
    fs.writeFileSync(path.join(dir,'snapshot.json'),JSON.stringify(portable),{mode:0o600});
    const keys=new Set(python('objects',dir).map(o=>o.key));let cursor;
    do{
      const page=await bucket.list({limit:1000,...(cursor?{cursor}:{})});
      check(page.objects.every(o=>keys.has(o.key)),'Private bucket contains unrelated or unresolved objects');
      cursor=page.truncated?page.cursor:undefined;
    }while(cursor);
  });
}
async function baseline(root,m,mod,normalizeState){
  return resource(root,m.active,null,async runtime=>{
    const snap=await snapshot(runtime.db,m,python('layout'));
    await checkSingleScope(runtime,m,snap.portable);
    if(snap.portable.state){
      await scratch(async dir=>{const full=await capture(runtime,m,python('layout'),dir);
        check(fingerprint(full)===fingerprint(snap),'Source changed during review');});
    }
    const actualState=snap.portable.state;
    if(normalizeState){
      check(actualState && actualState.revision===normalizeState.revision &&
        ((actualState.authority===normalizeState.authority && actualState.epoch===normalizeState.epoch)||
         (actualState.authority==='staged' && actualState.epoch===normalizeState.epoch+1)),'Original authority changed during resumption');
      snap.portable.state=normalizeState;
    }
    return {digest:fingerprint(snap),content:fingerprint(covered(snap.portable)),state:actualState};
  },mod);
}
async function insertReviewed(runtime,m,dir,portable,layout){
  const {db,bucket}=runtime;
  for(const {key,size,sha256:digest} of python('objects',dir)){
    const bytes=fs.readFileSync(path.join(dir,'objects',key));
    check(bytes.length===size && sha(bytes)===digest,'Reviewed R2 bytes changed');
    await bucket.put(key,bytes,{sha256:digest,httpMetadata:{contentType:'image/png'}});
  }
  const rows=[db.prepare("INSERT INTO cloud_continuities(owner_id,continuity_id,authority,epoch,revision,schema_version) VALUES(?,?,'staged',?,?,?)")
    .bind(...scope(m),portable.state.epoch,portable.state.revision,portable.state.schema)];
  for(const t of layout){
    const statement='INSERT INTO '+t.name+' ('+t.columns.join(',')+') SELECT '+t.columns.map(k=>"json_extract(value,'$."+k+"')").join(',')+' FROM json_each(?)';
    let part=[],bytes=0;
    const flush=()=>{if(part.length)rows.push(db.prepare(statement).bind(JSON.stringify(part)));part=[];bytes=0;};
    for(const row of portable.tables[t.name]){
      const n=Buffer.byteLength(JSON.stringify(row))+1;
      check(n<1_750_000,'Single record exceeds the local D1 bind limit');
      if(bytes+n>128000)flush();
      part.push(row);bytes+=n;
    }
    flush();
  }
  // A single transactional batch preserves the deferred request/receipt and
  // cross-entity foreign keys. Oversized fixtures keep the old generation.
  check(rows.length<=500,'Staged data exceeds the local atomic batch budget');
  await db.batch(rows);
  check(!(await db.prepare('PRAGMA foreign_key_check').all()).results.length,'Provider foreign-key verification failed');
}
async function verifiedGen(root,m,gen,expected,mod,allowedAuthorities){
  return scratch(dir=>resource(root,gen,null,async runtime=>{
    const actual=await capture(runtime,m,python('layout'),dir),v=actual.portable;
    check(actual.guard===fingerprint([]) && !await runtime.db.prepare('SELECT 1 FROM bridge_credentials LIMIT 1').first(),
      'Restored generation must begin without standing grants or credentials');
    await checkSingleScope(runtime,m,v);
    check(fingerprint(covered(v))===fingerprint(covered(expected)) && allowedAuthorities.includes(v.state.authority) &&
      v.state.schema===expected.state.schema,'Restored D1 state differs from reviewed source');
    return v.state;
  },mod));
}
async function prepare(root,file,approval,mod){
  return withLock(root,m=>scratch(async dir=>{
    check(!m.intent,'Recover the pending cutover first');
    const fileDigest=packageDigest(file),verified=python('unpack',dir,file),p=readPrivate(path.join(dir,'snapshot.json')),source=await baseline(root,m,mod);
    check(p.owner===m.owner && p.continuity===m.continuity,'Package owner or continuity differs');
    if(source.state){
      check(source.state.authority==='cloud' && source.state.epoch===p.state.epoch &&
        source.content===fingerprint(covered(p)),'Preserve newer or divergent gameplay; obtain a fresh cold backup');
    }
    const token=fingerprint(['stage',m.id,m.active,source.digest,verified.manifestSha256]);
    if(!approval)return {status:'PREVIEW',approvalSha256:token,sourceRevision:p.state.revision,media:verified.media};
    check(token===approval,'Approval differs from the reviewed stage');
    if(m.pending){
      check(m.pending.approval===approval,'Another generation is awaiting review');
      return {status:'ALREADY_STAGED',manifestSha256:m.pending.digest};
    }
    const gen=randomUUID(),where=genPath(root,gen);fs.mkdirSync(where,{mode:0o700});
    try{
      await resource(root,gen,null,async runtime=>{await initSchema(runtime.db);await insertReviewed(runtime,m,dir,p,python('layout'));},mod);
      // Shut down workerd, then independently reopen the persisted bindings.
      const state=await verifiedGen(root,m,gen,p,mod,['staged']);
      check(state.epoch===p.state.epoch,'Staged epoch mismatch');
      fs.copyFileSync(file,path.join(where,'source.zip'),fs.constants.COPYFILE_EXCL);
      fs.chmodSync(path.join(where,'source.zip'),0o600);
      check(packageDigest(path.join(where,'source.zip'))===fileDigest,'Independent package copy mismatch');
      save(root,{...m,pending:{gen,approval,digest:verified.manifestSha256,source:source.digest,sourceState:source.state}});
      return {status:'STAGED',manifestSha256:verified.manifestSha256,revision:state.revision,media:verified.media};
    }catch(error){fs.rmSync(where,{recursive:true,force:true});throw error;}
  }));
}
async function fence(root,m,gen,beforeAuthority,beforeEpoch,afterAuthority,revision,mod){
  return resource(root,gen,null,async ({db})=>{
    const read=()=>db.prepare('SELECT authority,epoch,revision FROM cloud_continuities'+scoped).bind(...scope(m)).first();
    let row=await read();
    if(row?.authority===afterAuthority && row.epoch===beforeEpoch+1 && row.revision===revision)return;
    check(row?.authority===beforeAuthority && row.epoch===beforeEpoch && row.revision===revision,'Authority changed during cutover');
    await db.prepare("UPDATE cloud_continuities SET authority=?,epoch=epoch+1"+scoped+' AND authority=? AND epoch=? AND revision=?')
      .bind(afterAuthority,...scope(m),beforeAuthority,beforeEpoch,revision).run();
    row=await read();
    check(row?.authority===afterAuthority && row.epoch===beforeEpoch+1 && row.revision===revision,'Authority fence unverified');
  },mod);
}
async function cutover(root,approval,carry,mod,testHook){
  return withLock(root,m=>scratch(async dir=>{
    if(!m.pending){
      if(approval && m.last?.action==='cutover' && m.last.approval===approval)return {status:'ALREADY_COMMITTED',epoch:m.last.epoch};
      throw Error('No verified provider generation is staged');
    }
    const p=m.pending,sourceFile=path.join(genPath(root,p.gen),'source.zip');
    const reviewed=python('unpack',dir,sourceFile),contents=readPrivate(path.join(dir,'snapshot.json'));
    check(reviewed.manifestSha256===p.digest,'Packaged source evidence changed');
    check(reviewed.cutoverHolds===0,'Source records still require reviewed reconciliation before cutover');
    const candidates=contents.tables.cloud_requests.filter(r=>r.status==='PENDING_APPROVAL').map(r=>r.command_id);
    check(!candidates.length || (carry && contents.state.authority==='cloud'),'Pending work requires explicit prior-epoch review carry');
    check(!carry || candidates.length>0,'A carry decision requires existing pending requests');
    const token=fingerprint(['cutover',m.id,m.active,p.gen,p.source,p.digest,carry,candidates]);
    if(m.intent)check(approval===m.intent.approval && token===approval,'Resume the frozen administrative decision');
    const current=await baseline(root,m,mod,m.intent?p.sourceState:null);
    check(current.digest===p.source,'Source changed after staging; preserve existing gameplay');
    const restored=await verifiedGen(root,m,p.gen,contents,mod,m.intent?['staged','cloud']:['staged']);
    check(restored.epoch===contents.state.epoch+(restored.authority==='cloud'?1:0),'Unexpected destination authority epoch');
    if(!approval)return {status:'PREVIEW',approvalSha256:token,epoch:restored.epoch,pending:candidates.length};
    check(token===approval,'Approval differs from reviewed cutover');
    if(!m.intent){m={...m,intent:{action:'cutover',approval:token}};save(root,m);}
    if(p.sourceState)await fence(root,m,m.active,'cloud',p.sourceState.epoch,'staged',p.sourceState.revision,mod);
    if(testHook)await testHook('old-fenced');
    await fence(root,m,p.gen,'staged',contents.state.epoch,'cloud',contents.state.revision,mod);
    if(testHook)await testHook('new-promoted');
    const receipt={action:'cutover',approval:token,digest:p.digest,epoch:contents.state.epoch+1,revision:contents.state.revision,previous:m.active,generation:p.gen};
    save(root,{...m,active:p.gen,pending:null,intent:null,last:receipt});
    return {status:'ISOLATED_CLOUD',epoch:receipt.epoch,revision:receipt.revision,pending:candidates.length,production:false};
  }));
}
async function rollbackStage(root,approval){
  return withLock(root,m=>{
    if(!m.pending && approval && m.last?.action==='rollback-stage' && m.last.approval===approval)return {status:'ALREADY_ROLLED_BACK'};
    check(m.pending && !m.intent,'Only an unpublished stage can be rolled back');
    const p=m.pending,token=fingerprint(['rollback-stage',m.id,m.active,p.gen,p.digest]);
    if(!approval)return {status:'PREVIEW',approvalSha256:token};
    check(approval===token,'Approval differs from reviewed stage rollback');
    save(root,{...m,pending:null,last:{action:'rollback-stage',approval:token,retained:p.gen,digest:p.digest}});
    return {status:'ROLLED_BACK',retained:p.gen};
  });
}
async function withGameplay(root,fn,mod,rate){
  return withLock(root,m=>{
    check(!m.intent,'Resume the interrupted cutover before opening gameplay');
    return resource(root,m.active,{...m,rate},r=>fn(r,m),mod);
  });
}
async function inspect(root,mod){
  return withLock(root,async m=>{
    const read=gen=>resource(root,gen,null,({db})=>db.prepare('SELECT authority,epoch,revision FROM cloud_continuities'+scoped).bind(...scope(m)).first(),mod);
    const current=await read(m.active),stage=m.pending?await read(m.pending.gen):null;
    const previous=m.last?.action==='cutover'?await read(m.last.previous):null;
    return {status:m.intent?'CUTOVER_IN_PROGRESS':m.pending?'STAGED':current?'READY':'EMPTY',current,stage,previous,production:false};
  });
}
// Open maintenance-only bindings for one private local QA generation. No gameplay is routed.
async function withOfflineBindings(root,fn,mod){
  return withLock(root,m=>{
    check(!m.intent && !m.pending && !m.last,'A fresh unpublished local generation is required');
    return resource(root,m.active,null,r=>fn(r,{generation:m.active,d1DatabaseId:'00000000-0000-4000-8000-000000000042',
      privateBucket:'private-ascension-recovery',routed:false,private:true}),mod);
  });
}
module.exports={create,backup,backupBindings,prepare,cutover,rollbackStage,withGameplay,withOfflineBindings,inspect};
if(require.main===module){
  process.umask(0o077);
  const argv=process.argv.slice(2),[op,target,...args]=argv;
  const flag=name=>{const i=args.indexOf(name);return i<0?undefined:args[i+1];};
  const mod=flag('--miniflare'),approval=flag('--approve');
  const usage='init DIR --owner ID --continuity qa-ID | status DIR | backup DIR ZIP | stage DIR ZIP [--approve DIGEST] | cutover DIR [--approve DIGEST] [--carry-pending] | rollback-stage DIR [--approve DIGEST]. Optional --miniflare ABS_PACKAGE.';
  async function run(){
    check(target && !target.startsWith('-'),usage);const root=path.resolve(target);
    if(op==='init')return create(root,flag('--owner'),flag('--continuity'),mod);
    if(op==='status')return inspect(root,mod);
    if(op==='backup')return backup(root,path.resolve(args[0]),mod);
    if(op==='stage')return prepare(root,path.resolve(args[0]),approval,mod);
    if(op==='cutover')return cutover(root,approval,args.includes('--carry-pending'),mod);
    if(op==='rollback-stage')return rollbackStage(root,approval);
    throw Error(usage);
  }
  if(op==='--help'||op==='-h')console.log(usage);
  else run().then(value=>console.log(JSON.stringify(value))).catch(error=>{
    console.error(error.stderr?.toString().trim()||error.message);process.exitCode=1;
  });
}
