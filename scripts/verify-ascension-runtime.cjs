/* Run the fully bundled service against isolated local workerd with D1 and R2.
 * Usage: node scripts/verify-ascension-runtime.cjs /path/to/miniflare-package
 * The runtime package may be installed outside the repository. No account, deployment or device is needed.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {createHash,randomBytes}=require('node:crypto');
const { execFileSync } = require('node:child_process');

async function verify() {
  const { Miniflare } = require(process.argv[2] ? path.resolve(process.argv[2]) : 'miniflare');
  const root = path.resolve(__dirname, '../backend/owner-bridge');
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'ascension-runtime-'));
  const originalDirectory=process.cwd();
  let runtime;
  try {
    process.chdir(work);
    execFileSync(process.execPath, [path.join(root, 'build-worker.mjs'), work], {stdio:'pipe'});
    const owner = 'loopback-fixture-owner';
    const text = value => ({type:'text', value});
    runtime = new Miniflare({
      host:'127.0.0.1', port:0,
      workers:[{config:{
        name:'ascension-acceptance', compatibilityDate:'2025-09-15',
        manifest:{mainModule:'index.js', modules:{'index.js':{type:'esm',contents:fs.readFileSync(path.join(work,'server/index.js'),'utf8')}}},
        env:{OWNER_SITE_USER_ID:text(owner),ASTRAVAR_CLOUD_ENABLED:text('1'),ASTRAVAR_CLOUD_ENV:text('isolated'),
          DB:{type:'d1',id:'00000000-0000-4000-8000-000000000001'},ASTRAVAR_MEDIA:{type:'r2',name:'fixture-bucket'}}
      }}]
    });
    const db = await runtime.getD1Database('DB','ascension-acceptance');
    const migrations = path.join(work,'.openai/drizzle');
    for(const file of fs.readdirSync(migrations).filter(f=>/^\d+.*\.sql$/.test(f)).sort()) {
      const statements = fs.readFileSync(path.join(migrations,file),'utf8').split(/^--> statement-breakpoint\s*$/m);
      for(const statement of statements.filter(s=>s.trim()))await db.prepare(statement).run();
    }
    const origin='https://fixture.invalid',id='qa-runtime',base='/v3/continuities/'+id;
    const credential='synthetic-client',token=randomBytes(32).toString('hex');
    async function request(route,body,device=false) {
      const identity=device ? {Authorization:'Bearer '+token,'X-Astravar-Credential':credential} : {'oai-authenticated-user-id':owner,Origin:origin};
      const response=await runtime.dispatchFetch(origin+route,{method:body?'POST':'GET',
        headers:{'Content-Type':'application/json',...identity},...(body?{body:JSON.stringify(body)}:{})});
      const value=await response.json();assert(response.ok,route+' '+JSON.stringify(value));return value;
    }
    async function mcp(name,args) {
      const value=await request('/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId:id,...args}}});
      assert(!value.result?.isError,JSON.stringify(value));return value.result.structuredContent;
    }
    await request(base+'/isolated',{});
    const scopes=['character','resources','economy','inventory','chronicle'].flatMap(s=>[s+'.read',s+'.write']);
    const grant={scopes,auto:scopes.filter(s=>s.endsWith('.write')),actors:['*'],expiresAt:Date.now()+86400000,expectedGrantRevision:0,revoked:false};
    await request(base+'/grants/sites-mcp',grant);
    let revision=0;
    const proofs=[];
    function command(operation,args) {
      return {version:1,epoch:1,expectedRevision:revision,commandId:crypto.randomUUID(),operation,args};
    }
    async function commit(operation,args) {
      const intent=command(operation,args);
      const value=await mcp('submit_cloud_command',{command:intent});
      assert.equal(value.status,'COMMITTED');assert.equal(value.revision,++revision);
      proofs.push({intent,value});return value;
    }
    const actor='fixture-actor',coins=gp=>({cp:0,sp:0,ep:0,gp,pp:0});
    await commit('define_actor',{actorId:actor,name:'Fixture Actor',role:'player',rulesProfile:'legacy_2014',level:1});
    await commit('set_actor_ability',{actorId:actor,ability:'INT',score:15});
    await commit('define_wallet',{walletId:'wallet',title:'Fixture purse',holderId:actor,shared:false,coins:coins(20),source:'isolated'});
    await commit('define_item_definition',{definitionId:'lamp-type',name:'Fixture Lamp',category:'tool',description:'An inspection lamp',rarity:null,stackable:false,wearable:false,handheld:true,attunement:'none',source:'isolated'});
    await commit('purchase_item',{itemId:'lamp',definitionId:'lamp-type',ownerId:actor,quantity:1,source:'fixture transaction',walletId:'wallet',price:coins(7),sellerId:null});
    await commit('define_resource',{actorId:actor,poolId:'charge',name:'Fixture charge',type:'charge',maximum:3,current:3,recovery:'none',source:'isolated'});
    await commit('spend_resource',{actorId:actor,poolId:'charge',amount:1});
    await commit('record_chronicle_event',{eventId:'visit',title:'Fixture visit',narrative:'The workshop reopened.',category:'conversation',status:'confirmed',worldTime:null,subjects:[{kind:'actor',id:actor}],correctsId:null,source:'isolated'});
    for(const {intent,value} of proofs){
      assert.deepEqual(await mcp('submit_cloud_command',{command:intent}),value);
      assert.deepEqual(await mcp('get_cloud_receipt',{commandId:intent.commandId}),value);
    }
    await request(base+'/grants/sites-mcp',{...grant,auto:[],expectedGrantRevision:1});
    const queued=command('set_actor_ability',{actorId:actor,ability:'INT',score:17});
    const pending=await mcp('submit_cloud_command',{command:queued});
    assert.equal(pending.status,'PENDING_APPROVAL');
    const counts=await db.prepare('SELECT (SELECT count(*) FROM cloud_receipts) AS receipts,(SELECT count(*) FROM bridge_credentials) AS devices').first();
    assert.deepEqual(counts,{receipts:8,devices:0});
    const before=await mcp('get_cloud_state',{id:actor});
    assert.equal(before.records[0].data.abilities.INT,15);
    const holder=await db.prepare("SELECT json_extract(data_json,'$.coins.gp') AS balance FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND id='wallet'").bind(owner,id).first();
    assert.equal(holder.balance,13);
    // Simulate an already-paired client reconnecting after every gameplay commit.
    await db.prepare("INSERT INTO bridge_credentials(id,owner_id,campaign_id,token_hash,role,scopes_json,device_name,expires_at) VALUES(?,?,?,?,'device','[]','Synthetic Android',?)")
      .bind(credential,owner,id,createHash('sha256').update(token).digest('hex'),grant.expiresAt).run();
    await request(base+'/grants/device:'+credential,grant);
    const records=[];
    let after=null;
    do{
      const next=after?'&atEpoch=1&atRevision='+revision+'&grantRevision=1&after='+encodeURIComponent(after):'';
      const page=await request(base+'/state?limit=3'+next,undefined,true);
      records.push(...page.records);after=page.nextAfter;
    }while(after);
    assert.equal(records.length,new Set(records.map(r=>r.id)).size);
    assert.equal(records.find(r=>r.id==='charge').data.current,2);
    assert.equal(records.filter(r=>r.id==='lamp').length,1);
    assert.equal(records.filter(r=>r.id==='visit').length,1);
    const decision=await request(base+'/approvals/'+queued.commandId+'/verify',{digest:pending.digest});
    assert.equal(decision.status,'COMMITTED');revision++;
    const updated=command('set_actor_ability',{actorId:actor,ability:'INT',score:16});
    const android=await request(base+'/commands',updated,true);
    assert.equal(android.status,'COMMITTED');revision++;
    assert.equal((await mcp('get_cloud_state',{id:actor})).records[0].data.abilities.INT,16);
    console.log(JSON.stringify({runtime:'workerd',bindings:['D1','R2'],phoneAbsentReceipts:counts.receipts,devicesAtCommit:counts.devices,
      pendingBeforeOwnerReview:true,independentWalletGP:holder.balance,uniqueReconnectedRecords:records.length,bidirectional:true,finalRevision:revision}));
  } finally {
    if(runtime)await runtime.dispose();
    process.chdir(originalDirectory);
    fs.rmSync(work,{recursive:true,force:true});
  }
}
verify().catch(e=>{console.error(e.message);process.exitCode=1;});
