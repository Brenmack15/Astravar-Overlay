/** Headless Chromium owner-review acceptance using the real bundled page and
 * Worker handlers. A random local test session represents the private gateway. */
const https=require('node:https'),{execFileSync}=require('node:child_process'),fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const {randomUUID}=require('node:crypto');
const {chromium}=require('playwright');
async function main(){
  const root=path.resolve(__dirname,'../backend/owner-bridge'),tmp=fs.mkdtempSync(path.join(os.tmpdir(),'astravar-inbox-page-'));
  const {handleWorker}=await import(pathToFileURL(path.join(root,'worker.mjs')));
  const {SqliteAdapter}=await import(pathToFileURL(path.join(root,'sqlite-adapter.mjs')));
  const db=new SqliteAdapter(path.join(tmp,'isolated.db'));db.exec(fs.readFileSync(path.join(root,'schema.sql'),'utf8'));
  const owner='browser-test-owner',id='qa-owner-browser',base='/v3/continuities/'+id,session=randomUUID();
  const env={DB:db,OWNER_SITE_USER_ID:owner,ASTRAVAR_CLOUD_ENABLED:'1',ASTRAVAR_CLOUD_ENV:'isolated'};
  let origin,browser;const decisions=[];
  const key=path.join(tmp,'local.key'),cert=path.join(tmp,'local.crt');
  execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',key,'-out',cert,'-days','1','-subj','/CN=localhost',
    '-addext','subjectAltName=IP:127.0.0.1'],{stdio:'ignore'});
  const server=https.createServer({key:fs.readFileSync(key),cert:fs.readFileSync(cert)},async(req,res)=>{
    try{
      const chunks=[];for await(const part of req)chunks.push(part);
      const headers=new Headers(req.headers);headers.delete('oai-authenticated-user-id');
      if(headers.get('x-private-test-session')===session)headers.set('oai-authenticated-user-id',owner);
      if(req.method==='POST' && req.url.includes('/approvals/'))decisions.push([req.url,JSON.parse(Buffer.concat(chunks)).digest]);
      const options={method:req.method,headers,...(chunks.length?{body:Buffer.concat(chunks)}:{})};
      const response=await handleWorker(new Request(origin+req.url,options),env);
      res.writeHead(response.status,Object.fromEntries(response.headers));res.end(Buffer.from(await response.arrayBuffer()));
    }catch{res.writeHead(500);res.end();}
  });
  try{
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));origin='https://127.0.0.1:'+server.address().port;
    const post=async(path,body)=>{
      const r=await handleWorker(new Request(origin+path,{method:'POST',headers:{Origin:origin,'Content-Type':'application/json','oai-authenticated-user-id':owner},body:JSON.stringify(body)}),env);
      const v=await r.json();assert.ok(r.ok,JSON.stringify(v));return v;
    };
    await post(base+'/isolated',{});await post(base+'/grants/sites-mcp',{scopes:['character.read','character.write'],auto:[],actors:['*'],
      expiresAt:Date.now()+86400000,expectedGrantRevision:0,revoked:false});
    await post(base+'/commands',{version:1,commandId:'browser-create',epoch:1,expectedRevision:0,operation:'define_actor',
      args:{actorId:'record-a',name:'Synthetic actor',role:'player',rulesProfile:'legacy_2014',level:1,abilities:{INT:12}}});
    const make=async(commandId,score)=>{
      const r=await post('/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'submit_cloud_command',arguments:{continuityId:id,
        command:{version:1,commandId,epoch:1,expectedRevision:1,operation:'set_actor_ability',args:{actorId:'record-a',ability:'INT',score}}}}});
      const p=r.result.structuredContent;assert.equal(p.status,'PENDING_APPROVAL');return p;
    };
    const first=await make('refuse-this-one',15),second=await make('verify-this-one',16);
    browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/chromium',headless:true,args:['--disable-dev-shm-usage','--no-sandbox','--disable-background-networking']});
    const ownerContext=await browser.newContext({ignoreHTTPSErrors:true,extraHTTPHeaders:{'x-private-test-session':session},viewport:{width:392,height:780}});
    const page=await ownerContext.newPage();
    const href=(p,verb)=>origin+'/owner/ascension/'+id+'#request='+p.commandId+'&digest='+p.digest+'&intent='+verb;
    await page.goto(href(first,'refuse'));await page.getByRole('button',{name:'Refuse',exact:true}).waitFor();
    assert.equal(await page.locator('article').count(),1);
    assert.equal(await page.getByRole('button',{name:'Refuse',exact:true}).evaluate(el=>el===document.activeElement),true);
    assert.equal(decisions.length,0);
    await page.getByRole('button',{name:'Refuse',exact:true}).click();
    await page.getByText('REFUSED · revision 1',{exact:true}).waitFor();
    await page.reload();await page.getByText('REFUSED · revision 1',{exact:true}).waitFor();
    assert.deepEqual(decisions,[[base+'/approvals/'+first.commandId+'/refuse',first.digest]]);
    const verifyPage=await ownerContext.newPage();
    await verifyPage.goto(href(second,'verify'));await verifyPage.getByRole('button',{name:'Verify',exact:true}).waitFor();
    assert.equal(decisions.length,1);await verifyPage.getByRole('button',{name:'Verify',exact:true}).click();
    await verifyPage.getByText('COMMITTED · revision 2',{exact:true}).waitFor();
    assert.deepEqual(decisions[1],[base+'/approvals/'+second.commandId+'/verify',second.digest]);
    const bad=await ownerContext.newPage();
    await bad.goto(href({...first,digest:'0'.repeat(64)},'verify'));
    await bad.getByText('The reviewed request is unavailable in this owner account.',{exact:true}).waitFor();
    assert.equal(await bad.getByRole('button').count(),0);
    const anonymous=await browser.newPage({ignoreHTTPSErrors:true});
    assert.equal((await anonymous.goto(href(second,'verify'))).status(),401);
    assert.equal(decisions.length,2);
    const current=db.db.prepare("SELECT data_json FROM cloud_entities WHERE id='record-a'").get();
    assert.equal(JSON.parse(current.data_json).abilities.INT,16);
    console.log(JSON.stringify({status:'VERIFIED',runtime:'Chromium',mobileViewport:true,zeroAutomaticDecisions:true,
      exactRefusals:1,exactVerifications:1,wrongDigestBlocked:true,ownerSessionRequired:true}));
  }finally{if(browser)await browser.close();if(server.listening)await new Promise(ok=>server.close(ok));db.close();fs.rmSync(tmp,{recursive:true,force:true});}
}
main().catch(error=>{console.error(error.stack);process.exitCode=1;});
