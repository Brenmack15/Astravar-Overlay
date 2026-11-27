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
  const {fileBucket}=await import(pathToFileURL(path.join(root,'fixtures/media-bucket.mjs')));
  const {encodeMediaPng,mediaDigest}=await import(pathToFileURL(path.join(root,'cloud-media-codec.mjs')));
  const cleanup=[];const bucket=await fileBucket({after:callback=>cleanup.push(callback)});
  const db=new SqliteAdapter(path.join(tmp,'isolated.db'));db.exec(fs.readFileSync(path.join(root,'schema.sql'),'utf8'));
  const owner='browser-test-owner',id='qa-owner-browser',base='/v3/continuities/'+id,session=randomUUID();
  const env={DB:db,ASTRAVAR_MEDIA:bucket,OWNER_SITE_USER_ID:owner,ASTRAVAR_CLOUD_ENABLED:'1',ASTRAVAR_CLOUD_ENV:'isolated'};
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
    await post(base+'/isolated',{});await post(base+'/grants/sites-mcp',{scopes:['character.read','character.write','media.read','media.write'],auto:[],actors:['*'],
      expiresAt:Date.now()+86400000,expectedGrantRevision:0,revoked:false});
    await post(base+'/commands',{version:1,commandId:'browser-create',epoch:1,expectedRevision:0,operation:'define_actor',
      args:{actorId:'record-a',name:'Synthetic actor',role:'player',rulesProfile:'legacy_2014',level:1,abilities:{INT:12}}});
    const make=async(commandId,score)=>{
      const r=await post('/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'submit_cloud_command',arguments:{continuityId:id,
        command:{version:1,commandId,epoch:1,expectedRevision:1,operation:'set_actor_ability',args:{actorId:'record-a',ability:'INT',score}}}}});
      const p=r.result.structuredContent;assert.equal(p.status,'PENDING_APPROVAL');return p;
    };
    const first=await make('refuse-this-one',15),second=await make('verify-this-one',16);
    browser=await chromium.launch({executablePath:process.env.CHROMIUM_EXECUTABLE||process.env.CHROME_BIN||(fs.existsSync('/usr/bin/chromium')?'/usr/bin/chromium':undefined),headless:true,args:['--disable-dev-shm-usage','--no-sandbox','--disable-background-networking']});
    const ownerContext=await browser.newContext({ignoreHTTPSErrors:true,extraHTTPHeaders:{'x-private-test-session':session},viewport:{width:392,height:780}});
    const page=await ownerContext.newPage();
    const href=(p,verb)=>origin+'/owner/ascension/'+id+'#request='+p.commandId+'&digest='+p.digest+'&intent='+verb;
    await page.goto(origin+'/owner/ascension/'+id);
    await page.getByText('2 ready for approval · 0 require reconciliation',{exact:true}).waitFor();
    assert.equal(await page.getByRole('link',{name:'Open request for set actor ability'}).count(),2);
    assert.equal(await page.getByRole('button',{name:'Verify',exact:true}).count(),0);
    await page.getByRole('link',{name:'Open request for set actor ability'}).first().click();
    await page.getByRole('button',{name:'Verify',exact:true}).waitFor();
    assert.ok(await page.getByText('Current',{exact:true}).count()>=1);
    assert.equal(await page.getByText('12',{exact:true}).count(),1);
    assert.equal(await page.getByText('15',{exact:true}).count(),1);
    await page.goto(href(first,'refuse'));await page.getByRole('button',{name:'Refuse',exact:true}).waitFor();
    assert.equal(await page.locator('article').count(),1);
    assert.equal(await page.getByRole('button',{name:'Refuse',exact:true}).evaluate(el=>el===document.activeElement),true);
    assert.equal(decisions.length,0);
    await page.getByRole('button',{name:'Refuse',exact:true}).click();
    await page.getByText('REFUSED · revision 1',{exact:true}).waitFor();
    await page.reload();await page.getByText('REFUSED · revision 1',{exact:true}).waitFor();
    assert.deepEqual(decisions,[[base+'/approvals/'+first.commandId+'/refuse',first.digest]]);
    // Native deep links can reuse a tab and navigate only the fragment.
    const verifyPage=page;
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
    const image=await encodeMediaPng(2,1,new Uint8Array([10,20,30,255,200,220,240,255]));
    const hash=await mediaDigest(image);
    const media={uploadId:'approved-candidate',targetKind:'actor',targetId:'record-a',sha256:hash};
    const call=async(name,args)=>{
      const v=await post('/mcp',{jsonrpc:'2.0',id:4,method:'tools/call',params:{name,arguments:{continuityId:id,...args}}});
      assert.equal(v.result.isError,false,JSON.stringify(v.result));return v.result.structuredContent;
    };
    await call('stage_cloud_image',{...media,bytesBase64:Buffer.from(image).toString('base64')});
    const assign=await call('submit_cloud_command',{command:{version:1,commandId:'assign-reviewed-image',epoch:1,expectedRevision:2,
      operation:'assign_primary_image',args:{...media,assetId:'displayed-asset'}}});
    assert.equal(assign.status,'PENDING_APPROVAL');
    const art=await ownerContext.newPage();
    let sendCandidate,originalRequested;
    const delivery=new Promise(resolve=>{sendCandidate=resolve;});
    const requested=new Promise(resolve=>{originalRequested=resolve;});
    await art.route('**/media/uploads/approved-candidate/original',async route=>{
      originalRequested();await delivery;await route.continue();
    });
    await art.goto(href(assign,'verify'),{waitUntil:'domcontentloaded'});
    await art.getByRole('button',{name:'Verify',exact:true}).waitFor();
    await requested;
    assert.equal(await art.getByRole('button',{name:'Verify',exact:true}).isDisabled(),true);
    assert.equal(decisions.length,2);
    sendCandidate();
    await art.waitForFunction(()=>document.querySelector('img')?.naturalWidth===2 && !document.querySelector('[data-decision=verify]').disabled);
    await art.getByRole('button',{name:'Verify',exact:true}).click();
    await art.getByText('COMMITTED · revision 3',{exact:true}).waitFor();
    assert.deepEqual(decisions[2],[base+'/approvals/'+assign.commandId+'/verify',assign.digest]);
    const primary=await handleWorker(new Request(origin+base+'/media/primary/record-a/original',{headers:{'oai-authenticated-user-id':owner}}),env);
    assert.equal(primary.status,200);
    assert.deepEqual(Buffer.from(await primary.arrayBuffer()),Buffer.from(image));
    const current=db.db.prepare("SELECT data_json FROM cloud_entities WHERE id='record-a'").get();
    assert.equal(JSON.parse(current.data_json).abilities.INT,16);
    const original={version:1,commandId:'changed-while-reviewed',epoch:1,expectedRevision:3,
      operation:'set_actor_ability',args:{actorId:'record-a',ability:'INT',score:17}};
    const delayed=await call('submit_cloud_command',{command:original});
    const change=async(revision,score)=>post(base+'/commands',{...original,commandId:randomUUID(),expectedRevision:revision,args:{...original.args,score}});
    await change(3,18);
    const resolution=await ownerContext.newPage();
    await resolution.goto(href(delayed,'verify'));
    await resolution.getByRole('button',{name:'Resolve status',exact:true}).click();
    await resolution.getByText('CONFLICT · revision 4',{exact:true}).waitFor();
    const prepare=resolution.getByRole('button',{name:'Prepare linked replacement',exact:true});
    const oldProof=await call('get_cloud_receipt',{commandId:original.commandId});
    await prepare.click();
    await resolution.getByRole('button',{name:'Verify',exact:true}).waitFor();
    const nextId=new URL(resolution.url()).hash.match(/request=([^&]+)/)[1];
    assert.notEqual(nextId,original.commandId);
    assert.equal((await call('get_cloud_receipt',{commandId:nextId})).status,'PENDING_APPROVAL');
    await change(4,19);
    await resolution.getByRole('button',{name:'Verify',exact:true}).click();
    await resolution.getByText('CONFLICT · revision 5',{exact:true}).waitFor();
    await prepare.click();
    await resolution.getByRole('button',{name:'Verify',exact:true}).waitFor();
    const finalId=new URL(resolution.url()).hash.match(/request=([^&]+)/)[1];
    assert.notEqual(nextId,finalId);
    assert.equal(JSON.parse(db.db.prepare("SELECT data_json FROM cloud_entities WHERE id='record-a'").get().data_json).abilities.INT,19);
    await resolution.getByRole('button',{name:'Verify',exact:true}).click();
    await resolution.getByText('COMMITTED · revision 6',{exact:true}).waitFor();
    assert.equal(JSON.parse(db.db.prepare("SELECT data_json FROM cloud_entities WHERE id='record-a'").get().data_json).abilities.INT,17);
    assert.equal((await call('get_cloud_receipt',{commandId:finalId})).status,'COMMITTED');
    assert.deepEqual(await call('get_cloud_receipt',{commandId:original.commandId}),oldProof);
    await resolution.goto(href(delayed,'replace'));
    // The immediate replacement also conflicted. Preparing a second live sibling of the committed descendant is prohibited.
    const rootLink=resolution.getByRole('link',{name:'Open linked replacement'});
    await rootLink.waitFor();
    await rootLink.click();
    await resolution.getByRole('link',{name:'Open linked replacement'}).click();
    await resolution.getByText('COMMITTED · revision 6',{exact:true}).waitFor();
    console.log(JSON.stringify({status:'VERIFIED',runtime:'Chromium',mobileViewport:true,zeroAutomaticDecisions:true,sameTabRetarget:true,
      reviewedReplacementConflicts:2,linkedReplacements:2,originalReceiptPreserved:true,exactRefusals:1,exactVerifications:3,exactImagePreviewRequired:true,wrongDigestBlocked:true,ownerSessionRequired:true}));
  }finally{if(browser)await browser.close();if(server.listening)await new Promise(ok=>server.close(ok));db.close();for(const action of cleanup)await action();fs.rmSync(tmp,{recursive:true,force:true});}
}
main().catch(error=>{console.error(error.stack);process.exitCode=1;});
