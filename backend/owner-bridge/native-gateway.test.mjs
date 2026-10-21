/** Actual disposable gateway subprocess restarts, never production/device records. */
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {tmpdir} from 'node:os';
import {setTimeout as pause} from 'node:timers/promises';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {OwnerBridge,sha256} from './core.mjs';
import {LiveHpBridge} from './live-hp.mjs';
const execute=promisify(execFile);

test('actual isolated HTTPS gateway restarts with persistent HP/receipt and rejects a mismatched existing schema',async t=>{
 const directory=await mkdtemp(join(tmpdir(),'astravar-gateway-restart-'));t.after(()=>rm(directory,{recursive:true,force:true}));
 const ownerId='ci-fictional-restart-owner',campaign='gateway-restart-fixture',profileId='hp-native-player',secret='s'.repeat(60);
 await writeFile(join(directory,'server-config.json'),JSON.stringify({origin:'https://hp-bridge.example',profileId,ownerId,serviceBearer:'fixture-transport-'.repeat(4),controlToken:'fixture-control-'.repeat(4)}));
 await execute('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',join(directory,'server.key'),'-out',join(directory,'server.crt'),'-days','1','-subj','/CN=hp-bridge.example'],{maxBuffer:1024*1024});
 const script=fileURLToPath(new URL('../../scripts/ci-owner-native-server.mjs',import.meta.url));
 async function start({expectFailure=false}={}){
  await rm(join(directory,'server-ready.json'),{force:true});
  const child=spawn(process.execPath,[script,'--synthetic-only'],{env:{...process.env,ASTRAVAR_CI_STATE_DIR:directory,ASTRAVAR_CI_PORT:'0',ASTRAVAR_CI_DNS_PORT:'0'},stdio:['ignore','pipe','pipe']});
  let output='',finished=null;child.stdout.on('data',bytes=>{output+=bytes;});child.stderr.on('data',bytes=>{output+=bytes;});
  const exited=new Promise(resolve=>child.once('exit',(code,signal)=>{finished={code,signal};resolve(finished);}));
  t.after(()=>{if(!finished)child.kill('SIGKILL');});
  if(expectFailure){const result=await exited;assert.notEqual(result.code,0);assert.match(output,/CI_FIXTURE_SCHEMA_MISMATCH/);await assert.rejects(readFile(join(directory,'server-ready.json')),{code:'ENOENT'});return;}
  const deadline=Date.now()+10000;let ready=false;
  while(Date.now()<deadline){if(finished)assert.fail('Synthetic gateway exited before readiness: '+output);try{const value=JSON.parse(await readFile(join(directory,'server-ready.json'),'utf8'));ready=value.ready===true&&value.syntheticOnly===true;if(ready)break;}catch{}await pause(20);}
  assert.equal(ready,true,'Synthetic gateway must become ready');
  return{output:()=>output,stop:async()=>{child.kill('SIGTERM');assert.deepEqual(await exited,{code:0,signal:null});}};
 }
 const first=await start();assert.match(first.output(),/"databaseInitialization":"FRESH"/);await first.stop();
 const path=join(directory,'bridge.sqlite'),db=new SqliteAdapter(path),bridge=new OwnerBridge(db,{ownerId}),hp=new LiveHpBridge(db,{ownerId});
 const pairing=await bridge.requestPairing({campaignId:campaign,profileId,deviceName:'Fictional restart fixture',deviceSecretHash:await sha256(secret)});
 await bridge.approvePairing({ownerId,role:'owner'},{userCode:pairing.userCode});const token=await bridge.pollPairing({deviceCode:pairing.deviceCode,deviceSecret:secret}),principal=await bridge.authenticate(token.credentialId,secret);
 const publication={commandId:crypto.randomUUID(),expectedRevision:0,profileId,revision:5,fields:{name:'Fictional restart player',currentHp:17,maximumHp:40,temporaryHp:null,source:'Isolated durable restart fixture',updatedAt:Date.now()}};
 const saved=await hp.publishCharacter(principal,campaign,publication);db.close();
 const second=await start();assert.match(second.output(),/"databaseInitialization":"VERIFIED_EXISTING"/);await second.stop();
 const restored=new SqliteAdapter(path),restoredBridge=new OwnerBridge(restored,{ownerId}),restoredHp=new LiveHpBridge(restored,{ownerId}),device=await restoredBridge.authenticate(token.credentialId,secret);
 assert.equal((await restoredHp.getCharacterHp(device,campaign,profileId)).fields.currentHp,17);
 assert.deepEqual((await restoredHp.getCommandReceipt(device,campaign,publication.commandId,profileId)).receipt,saved.receipt);
 assert.equal((await restored.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type='table' AND name LIKE 'bridge_%'").bind().first()).count,18);
 // A real schema guard must reject a partially altered fixture, not merely skip
 // master initialization when any one old table happens to exist.
 restored.db.exec('DROP INDEX bridge_campaign_records_grant');restored.close();
 await start({expectFailure:true});
 const preserved=new SqliteAdapter(path);assert.equal((await preserved.prepare('SELECT COUNT(*) AS count FROM bridge_hp_receipts WHERE command_id=?').bind(publication.commandId).first()).count,1);preserved.close();
});
