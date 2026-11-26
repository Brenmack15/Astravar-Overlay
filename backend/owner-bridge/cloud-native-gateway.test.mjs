/** Exercise the gated fixture's real TLS service and all host checkpoints independently of Android availability. */
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn,execFileSync} from 'node:child_process';
import {randomBytes,createHash} from 'node:crypto';
import {mkdtempSync,readFileSync,writeFileSync,rmSync,copyFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import https from 'node:https';
import {setTimeout as pause} from 'node:timers/promises';
import {runHost,harness,continuityId} from '../../scripts/ci-ascension-cloud-gateway.mjs';

test('actual reserved TLS gateway verifies setup, remote commit/retry, pending decision and independent device receipt',async t=>{
  const directory=mkdtempSync(join(tmpdir(),'ascension-gateway-')), origin='https://hp-bridge.example';
  const config={origin,profileId:'hp-native-player',ownerId:'ci-fictional-gateway',controlToken:randomBytes(32).toString('hex'),serviceBearer:randomBytes(32).toString('hex')};
  writeFileSync(join(directory,'server-config.json'),JSON.stringify(config),{mode:0o600});
  execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=hp-bridge.example',
    '-addext','subjectAltName=DNS:hp-bridge.example','-keyout',join(directory,'server.key'),'-out',join(directory,'server.crt')],{stdio:'ignore'});
  copyFileSync(join(directory,'server.crt'),join(directory,'ca.crt'));
  const path=fileURLToPath(new URL('../../scripts/ci-owner-native-server.mjs',import.meta.url));
  const server=spawn(process.execPath,[path,'--synthetic-only'],{env:{...process.env,ASTRAVAR_CI_STATE_DIR:directory,ASTRAVAR_CI_PORT:'0',ASTRAVAR_CI_DNS_PORT:'0'},stdio:'ignore'});
  const finished=new Promise(resolve=>server.once('exit',resolve));
  t.after(async()=>{server.kill('SIGTERM');await finished;rmSync(directory,{recursive:true,force:true});});
  let port;
  for(let i=0;i<100&&!port;i++){try{port=JSON.parse(readFileSync(join(directory,'server-ready.json'),'utf8')).port;}catch{}if(!port)await pause(25);}
  assert.ok(port);
  const host=harness(directory,port), base='/v3/continuities/'+continuityId;
  const headers={'Content-Type':'application/json','oai-sites-authorization':'Bearer '+config.serviceBearer};
  async function device(path,body,auth={}) {
    return new Promise((resolve,reject)=>{
      const req=https.request({hostname:'127.0.0.1',servername:'hp-bridge.example',port,ca:readFileSync(join(directory,'ca.crt')),
        headers:{...headers,...auth},method:body?'POST':'GET',path},res=>{
          let chunks=[];res.on('data',b=>chunks.push(b));res.on('end',()=>{try{
            assert.ok(res.statusCode>=200&&res.statusCode<300,'Device HTTPS status '+res.statusCode);
            resolve(JSON.parse(Buffer.concat(chunks)));}catch(e){reject(e);}});
        });req.on('error',reject);req.end(body&&JSON.stringify(body));
    });
  }
  assert.equal((await runHost(directory,'setup',port)).revision,4);
  const secret=randomBytes(32).toString('base64url');
  const paired=await device('/v1/pairings',{campaignId:continuityId,profileId:'native-holder',deviceName:'TLS fixture',
    deviceSecretHash:createHash('sha256').update(secret).digest('hex'),campaignAccess:{readScopes:['character'],writeScopes:[]}});
  await host.control('/v1/owner/pairings/approve',{userCode:paired.userCode});
  const token=await device('/v1/pairings/token',{deviceCode:paired.deviceCode,deviceSecret:secret});
  const auth={Authorization:'Bearer '+secret,'X-Astravar-Credential':token.credentialId};
  await host.control(base+'/grants/device:'+token.credentialId,{scopes:['character.read','character.write','inventory.read','economy.read','resources.read','chronicle.read','world.read','approval.read'],
    auto:['character.write'],actors:['*'],expectedGrantRevision:0,expiresAt:Date.now()+86400000,revoked:false});
  assert.equal((await device(base+'/state?limit=20',null,auth)).revision,4);
  const offline=await runHost(directory,'offline',port);
  assert.equal(offline.revision,8);
  for(const r of offline.receiptHashes)assert.equal((await device(base+'/receipts/'+r.commandId,null,auth)).receipt.transactionId,r.transactionId);
  const pending=await device(base+'/receipts/native-pending-world',null,auth);
  assert.equal(pending.status,'PENDING_APPROVAL');
  await host.control(base+'/approvals/native-pending-world/verify',{digest:pending.digest});
  const edit=await device(base+'/commands',{version:1,epoch:1,expectedRevision:9,operation:'set_actor_ability',
    commandId:'native-return-edit',args:{actorId:'native-holder',ability:'INT',score:14}},auth);
  assert.equal(edit.status,'COMMITTED');
  assert.equal((await runHost(directory,'device-readback',port)).deviceTransactionId,edit.receipt.transactionId);
  await assert.rejects(host.control('/v3/continuities/any-other/isolated',{}));
});
