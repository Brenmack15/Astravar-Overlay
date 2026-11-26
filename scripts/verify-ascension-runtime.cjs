/* Run the fully bundled service against isolated local workerd with D1 and R2.
 * Usage: node scripts/verify-ascension-runtime.cjs /path/to/miniflare-package
 * The runtime package may be installed outside the repository. No account, deployment or device is needed.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
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
    async function request(route,body) {
      const response=await runtime.dispatchFetch(origin+route,{method:body?'POST':'GET',
        headers:{'Content-Type':'application/json','oai-authenticated-user-id':owner,Origin:origin},...(body?{body:JSON.stringify(body)}:{})});
      const value=await response.json();assert(response.ok,JSON.stringify(value));return value;
    }
    async function mcp(name,args) {
      const value=await request('/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId:id,...args}}});
      assert(!value.result?.isError,JSON.stringify(value));return value.result.structuredContent;
    }
    await request(base+'/isolated',{});
    await request(base+'/grants/sites-mcp',{scopes:['character.read','character.write'],auto:['character.write'],actors:['*'],
      expiresAt:Date.now()+86400000,expectedGrantRevision:0,revoked:false});
    const command={version:1,epoch:1,expectedRevision:0,commandId:crypto.randomUUID(),operation:'define_actor',
      args:{actorId:'fixture-actor',name:'Fixture Actor',role:'player',rulesProfile:'legacy_2014',level:1}};
    const committed=await mcp('submit_cloud_command',{command});
    assert.equal(committed.status,'COMMITTED');
    assert.deepEqual(await mcp('submit_cloud_command',{command}),committed);
    const state=await request(base+'/state');
    assert.deepEqual(state.records.map(r=>r.id),['fixture-actor']);
    assert.deepEqual(await mcp('get_cloud_receipt',{commandId:command.commandId}),committed);
    const counts=await db.prepare('SELECT (SELECT count(*) FROM cloud_receipts) AS receipts,(SELECT count(*) FROM bridge_credentials) AS devices').first();
    assert.equal(counts.receipts,1);assert.equal(counts.devices,0);
    console.log(JSON.stringify({runtime:'workerd',bindings:['D1','R2'],status:committed.status,revision:state.revision,...counts}));
  } finally {
    if(runtime)await runtime.dispose();
    process.chdir(originalDirectory);
    fs.rmSync(work,{recursive:true,force:true});
  }
}
verify().catch(e=>{console.error(e.message);process.exitCode=1;});
