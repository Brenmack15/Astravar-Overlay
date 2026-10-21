import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {OwnerBridge,sha256} from './core.mjs';
import {CampaignBridge} from './campaign.mjs';
import {CampaignPushTransport} from './campaign-push.mjs';

const ownerId='fictional-push-owner',campaign='fictional-push-campaign',secret='s'.repeat(60);
async function fixture(t){
 const db=new SqliteAdapter();db.exec(await readFile(new URL('./schema.sql',import.meta.url),'utf8'));
 let clock=1792620000000;
 const bridge=new OwnerBridge(db,{ownerId,now:()=>clock}),sync=new CampaignBridge(db,{ownerId,now:()=>clock});
 const pairing=await bridge.requestPairing({campaignId:campaign,profileId:'fictional-player',deviceName:'Disposable mock Android',deviceSecretHash:await sha256(secret),campaignAccess:{readScopes:['inventory'],writeScopes:['inventory']}});
 await bridge.approvePairing({ownerId,role:'owner',scopes:[]},{userCode:pairing.userCode});
 const token=await bridge.pollPairing({deviceCode:pairing.deviceCode,deviceSecret:secret});
 const principal=await bridge.authenticate(token.credentialId,secret);
 const key=crypto.getRandomValues(new Uint8Array(32));
 const pair=await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);
 const pem=btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey('pkcs8',pair.privateKey))));
 const serviceAccountJson=JSON.stringify({client_email:'fixture@example.iam.gserviceaccount.com',private_key:'-----BEGIN PRIVATE KEY-----\n'+pem.match(/.{1,64}/g).join('\n')+'\n-----END PRIVATE KEY-----'});
 const requests=[];
 const fetcher=async(url,options)=>{
  requests.push({url,options});
  if(url==='https://oauth2.googleapis.com/token')return Response.json({access_token:'fixture-oauth-token',expires_in:3600});
  if(url==='https://fcm.googleapis.com/v1/projects/fixture-firebase-project/messages:send')return Response.json({name:'projects/fixture-firebase-project/messages/fixture-only'});
  throw Error('Unexpected outbound fetch');
 };
 const push=new CampaignPushTransport(db,{ownerId,projectId:'fixture-firebase-project',serviceAccountJson,encryptionKeyBase64:btoa(String.fromCharCode(...key)),fetcher,now:()=>clock});
 t.after(()=>db.close());
 return{db,bridge,sync,push,principal,token,requests,advance:milliseconds=>clock+=milliseconds};
}
const fcmToken='mock-FCM-device-token-which-is-intentionally-not-a-secret';

test('push token registration is owner/campaign scoped and encrypted at rest; notification sends only a wake marker',async t=>{
 const f=await fixture(t);
 const outcome=await f.push.register(f.sync,f.principal,campaign,{token:fcmToken,notificationsEnabled:true});
 assert.equal(outcome.pushRegistered,true);
 const row=await f.db.prepare('SELECT token_iv,token_ciphertext,token_hash,notifications_enabled FROM bridge_campaign_push_tokens WHERE owner_id=? AND campaign_id=?').bind(ownerId,campaign).first();
 assert.equal(row.notifications_enabled,1);
 assert.equal(JSON.stringify(row).includes(fcmToken),false,'device token must not persist in plaintext');
 assert.equal(await f.push.decrypt(row.token_iv,row.token_ciphertext,f.push.context(campaign,f.token.credentialId)),fcmToken);
 await assert.rejects(f.push.decrypt(row.token_iv,row.token_ciphertext,f.push.context('foreign-campaign',f.token.credentialId)),undefined,'ciphertext must bind owner/campaign/device');
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),true);
 const sent=f.requests.find(r=>r.url.includes('/messages:send'));
 assert.equal(sent.options.headers.Authorization,'Bearer fixture-oauth-token');
 const message=JSON.parse(sent.options.body).message;
 assert.equal(message.data.type,'ASTRAVAR_CAMPAIGN_SYNC_WAKE');
 assert.equal(message.data.version,'1');
 assert.equal(message.data.campaignId,campaign);
 assert.equal(message.data.credentialId,f.token.credentialId);
 assert.equal(message.data.grantId,f.token.campaignAccess.grantId);
 assert.equal(message.data.grantRevision,'1');
 assert.equal(Number(message.data.expiresAt)-Number(message.data.issuedAt),30000);
 const signingFields=['type','version','campaignId','credentialId','grantId','grantRevision','issuedAt','expiresAt','wakeId'];
 const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(await sha256(secret)),{name:'HMAC',hash:'SHA-256'},false,['verify']);
 const signature=Uint8Array.from(atob(message.data.signature.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
 const signed=new TextEncoder().encode(signingFields.map(k=>message.data[k]).join('\n'));
 assert.equal(await crypto.subtle.verify('HMAC',key,signature,signed),true,'Android-verifiable per-device HMAC');
 const changed=new TextEncoder().encode(signingFields.map(k=>k==='campaignId'?'foreign-campaign':message.data[k]).join('\n'));
 assert.equal(await crypto.subtle.verify('HMAC',key,signature,changed),false,'campaign substitution invalidates wake');
 assert.equal(Object.hasOwn(message.data,'commandId'),false);
 assert.equal(Object.hasOwn(message.data,'token'),false);
 assert.equal(message.android.priority,'HIGH');
 assert.equal(message.token,fcmToken);
 assert.equal(sent.options.body.includes('campaign-v2'),false);
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false,'back-to-back wakes are coalesced');
 f.advance(2000);
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),true);
 await f.push.unregister(f.sync,f.principal,campaign);
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false);
});

test('silent push uses normal priority and cannot expand permissions to register other campaign',async t=>{
 const f=await fixture(t);
 await f.push.register(f.sync,f.principal,campaign,{token:fcmToken,notificationsEnabled:false});
 await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId});
 const sent=f.requests.find(r=>r.url.includes('/messages:send'));
 assert.equal(JSON.parse(sent.options.body).message.android.priority,'NORMAL');
 await assert.rejects(f.push.register(f.sync,{...f.principal,role:'connector'},campaign,{token:fcmToken,notificationsEnabled:true}),/DEVICE_SCOPE_DENIED/);
 await assert.rejects(f.push.register(f.sync,f.principal,'wrong-campaign',{token:fcmToken,notificationsEnabled:true}));
 await assert.rejects(f.push.register(f.sync,f.principal,campaign,{token:'short',notificationsEnabled:true}),/INVALID_PUSH_REGISTRATION/);
});

test('queued write triggers a nonblocking notification hook once, independent of replay',async t=>{
 const f=await fixture(t),notified=[];
 f.sync.onCommandQueued=event=>notified.push(event);
 const operation={type:'acquire_item',kind:'item',recordId:'fixture-push-gift',expectedRevision:0,fields:{title:'Fictional Test Rock',owner:'Mock hero',itemState:'Carried',quantity:1,evidenceStatus:'Confirmed'}};
 const request={commandId:crypto.randomUUID(),expectedGrantRevision:1,source:'Resolved fictional test',operations:[operation]};
 const caller={ownerId,role:'connector',scopes:[]};
 const queued=await f.sync.submit(caller,campaign,request);
 assert.equal(queued.command.status,'PENDING_APPROVAL');
 assert.deepEqual(notified.map(x=>({campaignId:x.campaignId,credentialId:x.credentialId,commandId:x.commandId})),[{campaignId:campaign,credentialId:f.token.credentialId,commandId:request.commandId}]);
 await f.sync.submit(caller,campaign,request);
 assert.equal(notified.length,1,'replay must not send repeated push notifications');
});


test('expired device/grant never receives push and revoked registrations are deleted',async t=>{
 const f=await fixture(t);
 await f.push.register(f.sync,f.principal,campaign,{token:fcmToken,notificationsEnabled:false});
 f.advance(31*24*60*60_000);
 assert.equal(await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),false);
 assert.equal(f.requests.length,0);
 await assert.rejects(f.push.register(f.sync,f.principal,campaign,{token:fcmToken,notificationsEnabled:false}),/CAMPAIGN_NOT_AUTHORIZED/);
 await f.db.prepare('UPDATE bridge_credentials SET revoked=1 WHERE id=?').bind(f.token.credentialId).run();
 assert.equal((await f.db.prepare('SELECT count(*) AS count FROM bridge_campaign_push_tokens').bind().first()).count,0);
});

test('grant revocation removes encrypted registration and rotation replaces token atomically',async t=>{
 const f=await fixture(t);
 await f.push.register(f.sync,f.principal,campaign,{token:fcmToken,notificationsEnabled:false});
 const rotated=fcmToken+'-rotated';
 await f.push.register(f.sync,f.principal,campaign,{token:rotated,notificationsEnabled:true});
 assert.equal((await f.db.prepare('SELECT count(*) AS count FROM bridge_campaign_push_tokens').bind().first()).count,1);
 await f.push.notify({campaignId:campaign,credentialId:f.token.credentialId});
 assert.equal(JSON.parse(f.requests.find(r=>r.url.includes('/messages:send')).options.body).message.token,rotated);
 await f.db.prepare('UPDATE bridge_campaign_grants SET revoked=1 WHERE id=?').bind(f.token.campaignAccess.grantId).run();
 assert.equal((await f.db.prepare('SELECT count(*) AS count FROM bridge_campaign_push_tokens').bind().first()).count,0);
});

test('FCM unregistered-token response deletes only matching stale registration and emits redacted timings',async t=>{
 const f=await fixture(t),metrics=[];
 f.push.observe=metric=>metrics.push(metric);
 await f.push.register(f.sync,f.principal,campaign,{token:fcmToken,notificationsEnabled:false});
 const baseFetcher=f.push.fetcher;
 f.push.fetcher=(url,options)=>url.includes('/messages:send')?Response.json({error:{details:[{errorCode:'UNREGISTERED'}]}},{status:404}):baseFetcher(url,options);
 await assert.rejects(f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),/PUSH_SEND_FAILED/);
 assert.equal((await f.db.prepare('SELECT count(*) AS count FROM bridge_campaign_push_tokens').bind().first()).count,0);
 assert.deepEqual(metrics.map(m=>m.stage),['push_requested','push_failed']);
 assert.equal(metrics.at(-1).registrationRemoved,true);
 assert.equal(metrics.at(-1).durationMilliseconds>=0,true);
 for(const forbidden of [fcmToken,secret,'fixture-oauth-token'])assert.equal(JSON.stringify(metrics).includes(forbidden),false);
});

test('generic FCM failure preserves rotated registration and durable server queue',async t=>{
 const f=await fixture(t);
 await f.push.register(f.sync,f.principal,campaign,{token:fcmToken,notificationsEnabled:false});
 const baseFetcher=f.push.fetcher;
 f.push.fetcher=(url,options)=>url.includes('/messages:send')?Response.json({error:{status:'UNAVAILABLE'}},{status:503}):baseFetcher(url,options);
 await assert.rejects(f.push.notify({campaignId:campaign,credentialId:f.token.credentialId}),/PUSH_SEND_FAILED/);
 assert.equal((await f.db.prepare('SELECT count(*) AS count FROM bridge_campaign_push_tokens').bind().first()).count,1);
});
