import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {OwnerBridge,sha256} from './core.mjs';
import {CampaignBridge} from './campaign.mjs';
import {CampaignPushTransport} from './campaign-push.mjs';

const ownerId='fictional-push-owner',campaign='fictional-push-campaign',secret='s'.repeat(60);
const pushyToken='a6345d0278adc55d3474f5',apiKey='fixture-pushy-api-key';
const registerBody=(token=pushyToken,notificationsEnabled=false)=>({provider:'pushy',token,notificationsEnabled});
async function fixture(t){
 const db=new SqliteAdapter();db.exec(await readFile(new URL('./schema.sql',import.meta.url),'utf8'));
 let clock=1792620000000;
 const bridge=new OwnerBridge(db,{ownerId,now:()=>clock}),sync=new CampaignBridge(db,{ownerId,now:()=>clock});
 const pairing=await bridge.requestPairing({campaignId:campaign,profileId:'fictional-player',deviceName:'Disposable mock Android',deviceSecretHash:await sha256(secret),campaignAccess:{readScopes:['inventory'],writeScopes:['inventory']}});
 await bridge.approvePairing({ownerId,role:'owner',scopes:[]},{userCode:pairing.userCode});
 const token=await bridge.pollPairing({deviceCode:pairing.deviceCode,deviceSecret:secret});
 const principal=await bridge.authenticate(token.credentialId,secret);
 const encryptionKeyBase64=btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))),requests=[];
 const fetcher=async(url,options)=>{
  requests.push({url,options});
  assert.equal(url,'https://api.pushy.me/push?api_key='+apiKey);
  return Response.json({success:true,id:'fictional-notification',info:{devices:1}});
 };
 const push=new CampaignPushTransport(db,{ownerId,apiKey,encryptionKeyBase64,fetcher,now:()=>clock});
 t.after(()=>db.close());
 return{db,bridge,sync,push,principal,token,requests,encryptionKeyBase64,advance:milliseconds=>clock+=milliseconds};
}
const registrationCount=f=>f.db.prepare('SELECT count(*) AS count FROM bridge_campaign_push_tokens').bind().first().then(r=>r.count);

test('Pushy registration is scoped, encrypted, provider-bound; signed v2 wake discloses no campaign identifiers',async t=>{
 const f=await fixture(t),metrics=[];f.push.observe=m=>metrics.push(m);
 const outcome=await f.push.register(f.sync,f.principal,campaign,registerBody(pushyToken,true));
 assert.equal(outcome.pushRegistered,true);assert.equal(outcome.provider,'pushy');
 const row=await f.db.prepare('SELECT token_iv,token_ciphertext,token_hash,notifications_enabled FROM bridge_campaign_push_tokens WHERE owner_id=? AND campaign_id=?').bind(ownerId,campaign).first();
 assert.equal(row.notifications_enabled,1);
 assert.equal(JSON.stringify(row).includes(pushyToken),false);
 assert.deepEqual(JSON.parse(await f.push.decrypt(row.token_iv,row.token_ciphertext,f.push.context(campaign,f.token.credentialId))),{provider:'pushy',token:pushyToken});
 await assert.rejects(f.push.decrypt(row.token_iv,row.token_ciphertext,f.push.context('foreign-campaign',f.token.credentialId)));
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),true);
 const sent=f.requests[0],message=JSON.parse(sent.options.body),data=message.data;
 assert.equal(sent.options.redirect,'error');assert.ok(sent.options.signal);
 assert.deepEqual(Object.keys(message).sort(),['collapse_key','data','time_to_live','to']);
 assert.equal(message.to,pushyToken);assert.equal(message.time_to_live,30);assert.equal(message.collapse_key,'astravar-wake');
 assert.deepEqual(Object.keys(data).sort(),['expiresAt','issuedAt','signature','type','version','wakeId']);
 assert.equal(data.type,'ASTRAVAR_CAMPAIGN_SYNC_WAKE');assert.equal(data.version,'2');
 assert.equal(Number(data.expiresAt)-Number(data.issuedAt),30000);
 for(const forbidden of [campaign,ownerId,f.token.credentialId,f.token.campaignAccess.grantId,secret])assert.equal(JSON.stringify(data).includes(forbidden),false);
 const signingFields=['type','version','issuedAt','expiresAt','wakeId'];
 const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(await sha256(secret)),{name:'HMAC',hash:'SHA-256'},false,['verify']);
 const signature=Uint8Array.from(atob(data.signature.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
 const bytes=values=>new TextEncoder().encode(values.join('\n'));
 assert.equal(await crypto.subtle.verify('HMAC',key,signature,bytes(signingFields.map(k=>data[k]))),true);
 assert.equal(await crypto.subtle.verify('HMAC',key,signature,bytes(signingFields.map(k=>k==='expiresAt'?'9999999999999':data[k]))),false);
 assert.deepEqual(metrics.map(m=>m.stage),['push_requested','push_accepted_by_pushy']);
 for(const forbidden of [pushyToken,secret,apiKey,campaign,f.token.credentialId])assert.equal(JSON.stringify(metrics).includes(forbidden),false);
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false);
 f.advance(2000);assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),true);
 await f.push.unregister(f.sync,f.principal,campaign);assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false);
});

test('registration rejects other providers, foreign campaigns, missing provider and unexpected/private fields',async t=>{
 const f=await fixture(t);
 await assert.rejects(f.push.register(f.sync,{...f.principal,role:'connector'},campaign,registerBody()),/DEVICE_SCOPE_DENIED/);
 await assert.rejects(f.push.register(f.sync,f.principal,'wrong-campaign',registerBody()));
 for(const body of [{token:pushyToken,notificationsEnabled:true},{...registerBody(),provider:'fcm'},registerBody('short'),{...registerBody(),campaignData:'private'}])
  await assert.rejects(f.push.register(f.sync,f.principal,campaign,body),/INVALID_PUSH_REGISTRATION/);
 assert.equal(await registrationCount(f),0);
});

test('missing Pushy configuration disables only optional wake transport; queued commands remain durable',async t=>{
 const f=await fixture(t);
 const push=new CampaignPushTransport(f.db,{ownerId,encryptionKeyBase64:f.encryptionKeyBase64,fetcher:()=>{throw Error('must not fetch');}});
 assert.equal(push.configured,false);assert.equal(await push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false);
 await assert.rejects(push.register(f.sync,f.principal,campaign,registerBody()),/PUSH_NOT_CONFIGURED/);
 const request={commandId:crypto.randomUUID(),expectedGrantRevision:1,source:'Resolved fictional test',operations:[{type:'acquire_item',kind:'item',recordId:'fixture-cloud-only-gift',expectedRevision:0,fields:{title:'Fictional Test Rock',quantity:1,evidenceStatus:'Confirmed'}}]};
 f.sync.onCommandQueued=event=>push.notify(event);
 const queued=await f.sync.submit({ownerId,role:'connector',scopes:[]},campaign,request);
 assert.equal(queued.command.status,'PENDING_APPROVAL');
 const replay=await f.sync.submit({ownerId,role:'connector',scopes:[]},campaign,request);
 assert.equal(replay.command.commandId,queued.command.commandId);
});

test('legacy encrypted tokens never leave the service and are replaced only by explicit Pushy registration',async t=>{
 const f=await fixture(t);await f.push.register(f.sync,f.principal,campaign,registerBody());
 const legacy='legacy-fcm-token-which-must-never-reach-pushy';
 const ciphertext=await f.push.encrypt(legacy,['ASTRAVAR_PUSH_TOKEN_V1',ownerId,campaign,f.token.credentialId].join('\n'));
 await f.db.prepare('UPDATE bridge_campaign_push_tokens SET token_iv=?,token_ciphertext=? WHERE credential_id=?').bind(ciphertext.iv,ciphertext.ciphertext,f.token.credentialId).run();
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false);
 assert.equal(f.requests.length,0);assert.equal(await registrationCount(f),1);
 await f.push.register(f.sync,f.principal,campaign,registerBody());
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),true);
 assert.equal(JSON.stringify(f.requests).includes(legacy),false);
});

test('distinct durable commands within coalescing window each retain wake coverage while replay does not send',async t=>{
 const f=await fixture(t),deliveries=[];await f.push.register(f.sync,f.principal,campaign,registerBody());
 f.sync.onCommandQueued=event=>deliveries.push(f.push.notify(event));
 const caller={ownerId,role:'connector',scopes:[]};
 const request=recordId=>({commandId:crypto.randomUUID(),expectedGrantRevision:1,source:'Resolved fictional test',operations:[{type:'acquire_item',kind:'item',recordId,expectedRevision:0,fields:{title:'Fictional Test Rock',quantity:1,evidenceStatus:'Confirmed'}}]});
 const first=request('fixture-rapid-first'),second=request('fixture-rapid-second');
 await f.sync.submit(caller,campaign,first);assert.equal(await deliveries[0],true);
 f.advance(500);await f.sync.submit(caller,campaign,second);assert.equal(await deliveries[1],true);
 await f.sync.submit(caller,campaign,first);await f.sync.submit(caller,campaign,second);assert.equal(deliveries.length,2);
 const messages=f.requests.map(r=>JSON.parse(r.options.body));
 assert.equal(new Set(messages.map(m=>m.data.wakeId)).size,2);assert.equal(new Set(messages.map(m=>m.collapse_key)).size,1);
 assert.equal(messages.some(m=>Object.hasOwn(m.data,'commandId')),false);
});

test('expired credentials/grants never receive push and credential revocation removes registrations',async t=>{
 const f=await fixture(t);await f.push.register(f.sync,f.principal,campaign,registerBody());
 f.advance(31*24*60*60_000);assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false);assert.equal(f.requests.length,0);
 await assert.rejects(f.push.register(f.sync,f.principal,campaign,registerBody()),/CAMPAIGN_NOT_AUTHORIZED/);
 await f.db.prepare('UPDATE bridge_credentials SET revoked=1 WHERE id=?').bind(f.token.credentialId).run();assert.equal(await registrationCount(f),0);
});

test('grant revocation removes encrypted registration and rotation replaces token atomically',async t=>{
 const f=await fixture(t);await f.push.register(f.sync,f.principal,campaign,registerBody());
 const rotated=pushyToken+'-rotated';await f.push.register(f.sync,f.principal,campaign,registerBody(rotated,true));assert.equal(await registrationCount(f),1);
 await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId});assert.equal(JSON.parse(f.requests[0].options.body).to,rotated);
 await f.db.prepare('UPDATE bridge_campaign_grants SET revoked=1 WHERE id=?').bind(f.token.campaignAccess.grantId).run();assert.equal(await registrationCount(f),0);
});

test('explicit failed recipient removes only matching stale token; generic provider errors preserve registration',async t=>{
 const f=await fixture(t);await f.push.register(f.sync,f.principal,campaign,registerBody());
 f.push.fetcher=()=>Response.json({success:true,id:'fictional-failed',info:{devices:0,failed:[pushyToken]}});
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false);assert.equal(await registrationCount(f),0);
 await f.push.register(f.sync,f.principal,campaign,registerBody());f.advance(2000);
 f.push.fetcher=()=>Response.json({success:false,error:'NO_RECIPIENTS'},{status:400});
 await assert.rejects(f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),/PUSH_SEND_FAILED/);assert.equal(await registrationCount(f),1);
});

test('a failed old recipient cannot delete a concurrently rotated registration',async t=>{
 const f=await fixture(t);await f.push.register(f.sync,f.principal,campaign,registerBody());
 f.push.fetcher=async()=>{await f.push.register(f.sync,f.principal,campaign,registerBody(pushyToken+'-rotated'));return Response.json({success:true,id:'fictional-failed',info:{failed:[pushyToken]}});};
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false);assert.equal(await registrationCount(f),1);
});

test('bounded provider timeout and invalid success response fail redacted without deleting token',async t=>{
 const f=await fixture(t),metrics=[];await f.push.register(f.sync,f.principal,campaign,registerBody());f.push.observe=m=>metrics.push(m);f.push.sendTimeoutMilliseconds=100;
 f.push.fetcher=(_url,options)=>new Promise((_resolve,reject)=>{options.signal.addEventListener('abort',()=>reject(Error('private-url?api_key='+apiKey)));});
 await assert.rejects(f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),/^Error: PUSH_SEND_FAILED$/);assert.equal(await registrationCount(f),1);
 assert.equal(JSON.stringify(metrics).includes(apiKey),false);assert.equal(metrics.at(-1).stage,'push_failed');
 f.advance(2000);f.push.fetcher=()=>Response.json({success:true});
 await assert.rejects(f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),/PUSH_SEND_FAILED/);assert.equal(await registrationCount(f),1);
});

for(const {stage,table,target} of [
 {stage:'encrypt',table:'bridge_credentials',target:'credential'},
 {stage:'hash',table:'bridge_campaign_grants',target:'grant'}
])test(`registration cannot recreate a token after ${target} revocation during delayed ${stage}`,async t=>{
 const f=await fixture(t);await f.push.register(f.sync,f.principal,campaign,registerBody());
 let entered,release;const suspended=new Promise(resolve=>{entered=resolve;}),resume=new Promise(resolve=>{release=resolve;});
 const original=f.push[stage].bind(f.push);f.push[stage]=async(...args)=>{const value=await original(...args);entered();await resume;return value;};
 const pending=f.push.register(f.sync,f.principal,campaign,registerBody(pushyToken+'-rotated',true));
 const denied=assert.rejects(pending,e=>e.status===403&&e.code==='CAMPAIGN_NOT_AUTHORIZED');await suspended;
 await f.db.prepare(`UPDATE ${table} SET revoked=1 WHERE id=?`).bind(target==='credential'?f.token.credentialId:f.token.campaignAccess.grantId).run();assert.equal(await registrationCount(f),0);
 release();await denied;assert.equal(await registrationCount(f),0);assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false);assert.equal(f.requests.length,0);
});
