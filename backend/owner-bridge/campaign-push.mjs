/** Astravar Campaign V2 — optional owner-private Android push *wakeup* transport.
 * FCM messages carry no campaign record, pending command, credential or
 * private event. Real writes still require Android to fetch a typed command
 * and return its immutable device receipt.
 *
 * No module-level secrets, raw token logging, or push-dependent acceptance.
 */
import {BridgeError} from './core.mjs';
const fail=(status,code)=>{throw new BridgeError(status,code);};
const textEncoder=new TextEncoder();
const enc=value=>textEncoder.encode(value);
const tokenPattern=/^[A-Za-z0-9:_-]{40,4096}$/;
const b64url=bytes=>btoa(String.fromCharCode(...bytes)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/g,'');
const decodeBase64=value=>Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')),x=>x.charCodeAt(0));
const idPattern=/^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;
const jsonBody=async response=>{try{return await response.json();}catch{return null;}};

export class CampaignPushTransport {
 constructor(db,{ownerId,projectId=null,serviceAccountJson=null,encryptionKeyBase64=null,
  fetcher=fetch,now=Date.now,random=()=>crypto.getRandomValues(new Uint8Array(12)),observe=()=>{}}={}){
  if(!ownerId)throw Error('Verified owner is required');
  this.db=db;this.ownerId=ownerId;this.projectId=projectId;
  this.serviceAccount=null;
  // A missing or invalid optional push configuration must never bring down
  // the owner-private campaign read/write service.
  try{if(typeof serviceAccountJson==='string')this.serviceAccount=JSON.parse(serviceAccountJson);}catch{}
  this.encryptionKeyBase64=encryptionKeyBase64;this.fetcher=fetcher;this.now=now;this.random=random;
  this.cachedAuth=null;this.keyPromise=null;this.observe=observe;
 }
 get configured(){return Boolean(this.projectId&&this.serviceAccount?.client_email&&this.serviceAccount?.private_key&&this.encryptionKeyBase64);}
 async key(){
  if(!this.configured)fail(503,'PUSH_NOT_CONFIGURED');
  if(!this.keyPromise)this.keyPromise=crypto.subtle.importKey('raw',decodeBase64(this.encryptionKeyBase64),{name:'AES-GCM'},false,['encrypt','decrypt']);
  return this.keyPromise;
 }
 async hash(value){return b64url(new Uint8Array(await crypto.subtle.digest('SHA-256',enc(value))));}
 context(campaignId,credentialId){return ['ASTRAVAR_PUSH_TOKEN_V1',this.ownerId,campaignId,credentialId].join('\n');}
 async encrypt(value,context){const iv=this.random(),bytes=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:enc(context)},await this.key(),enc(value)));return{iv:b64url(iv),ciphertext:b64url(bytes)};}
 async decrypt(iv,ciphertext,context){return new TextDecoder().decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:decodeBase64(iv),additionalData:enc(context)},await this.key(),decodeBase64(ciphertext)));}
 async signWake(data,deviceSecretHash){
  const key=await crypto.subtle.importKey('raw',enc(deviceSecretHash),{name:'HMAC',hash:'SHA-256'},false,['sign']);
  const payload=['type','version','campaignId','credentialId','grantId','grantRevision','issuedAt','expiresAt','wakeId'].map(k=>data[k]).join('\n');
  return b64url(new Uint8Array(await crypto.subtle.sign('HMAC',key,enc(payload))));
 }
 metric(stage,values={}){try{this.observe({component:'campaign_push',stage,at:this.now(),...values});}catch{/* metrics cannot fail delivery */}}

 async register(bridge,principal,campaign,body){
  if(!this.configured)fail(503,'PUSH_NOT_CONFIGURED');
  if(principal?.role!=='device')fail(403,'DEVICE_SCOPE_DENIED');
  if(!body||Object.keys(body).some(k=>!['token','notificationsEnabled'].includes(k))||typeof body.token!=='string'||!tokenPattern.test(body.token)||typeof body.notificationsEnabled!=='boolean')fail(400,'INVALID_PUSH_REGISTRATION');
  const grant=await bridge.authorize(principal,campaign,['device']);
  const encrypted=await this.encrypt(body.token,this.context(campaign,principal.credentialId)),digest=await this.hash(body.token);
  await this.db.prepare(`INSERT INTO bridge_campaign_push_tokens(owner_id,campaign_id,grant_id,credential_id,token_hash,token_iv,token_ciphertext,notifications_enabled,updated_at,last_push_at)
   VALUES(?,?,?,?,?,?,?,?,?,0) ON CONFLICT(owner_id,campaign_id,credential_id) DO UPDATE SET
   grant_id=excluded.grant_id,token_hash=excluded.token_hash,token_iv=excluded.token_iv,token_ciphertext=excluded.token_ciphertext,notifications_enabled=excluded.notifications_enabled,updated_at=excluded.updated_at`)
   .bind(this.ownerId,campaign,grant.id,principal.credentialId,digest,encrypted.iv,encrypted.ciphertext,body.notificationsEnabled?1:0,this.now()).run();
  return{mode:'ASTRAVAR_CAMPAIGN_V2',campaignId:campaign,pushRegistered:true,notificationVisible:body.notificationsEnabled};
 }
 async unregister(bridge,principal,campaign){
  if(principal?.role!=='device')fail(403,'DEVICE_SCOPE_DENIED');
  await bridge.authorize(principal,campaign,['device']);
  await this.db.prepare('DELETE FROM bridge_campaign_push_tokens WHERE owner_id=? AND campaign_id=? AND credential_id=?')
   .bind(this.ownerId,campaign,principal.credentialId).run();
  return{mode:'ASTRAVAR_CAMPAIGN_V2',campaignId:campaign,pushRegistered:false};
 }
 async accessToken(){
  if(!this.configured)fail(503,'PUSH_NOT_CONFIGURED');
  const now=this.now();if(this.cachedAuth?.expiresAt>now+120_000)return this.cachedAuth.token;
  const issuer=this.serviceAccount.client_email,keyString=this.serviceAccount.private_key;
  const pkcs8=decodeBase64(keyString.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,''));
  const key=await crypto.subtle.importKey('pkcs8',pkcs8,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
  const epoch=Math.floor(now/1000),header=b64url(enc(JSON.stringify({alg:'RS256',typ:'JWT'}))),claims=b64url(enc(JSON.stringify({iss:issuer,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:epoch,exp:epoch+3500})));
  const bytes=enc(header+'.'+claims),signature=b64url(new Uint8Array(await crypto.subtle.sign({name:'RSASSA-PKCS1-v1_5'},key,bytes)));
  const response=await this.fetcher('https://oauth2.googleapis.com/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:header+'.'+claims+'.'+signature}).toString()});
  if(!response.ok)throw Error('PUSH_OAUTH_FAILED');
  const result=await jsonBody(response);if(typeof result?.access_token!=='string'||!Number.isInteger(result.expires_in))throw Error('PUSH_OAUTH_INVALID_RESPONSE');
  this.cachedAuth={token:result.access_token,expiresAt:now+Math.min(3500,result.expires_in)*1000};
  return this.cachedAuth.token;
 }
 async notify({campaignId,credentialId}){
  if(!this.configured||!idPattern.test(campaignId)||!idPattern.test(credentialId))return false;
  // Coalesce bursts; a single wake fetches all waiting commands. A missing
  // or revoked registration is a normal state and cannot fail the queue.
  const now=this.now(),started=performance.now();
  const row=await this.db.prepare(`SELECT p.*,g.revision AS grant_revision,g.expires_at AS grant_expires_at,c.expires_at AS credential_expires_at,c.token_hash AS credential_secret_hash FROM bridge_campaign_push_tokens p
   JOIN bridge_campaign_grants g ON g.id=p.grant_id AND g.owner_id=p.owner_id AND g.campaign_id=p.campaign_id AND g.credential_id=p.credential_id
   JOIN bridge_credentials c ON c.id=p.credential_id AND c.owner_id=p.owner_id AND c.campaign_id=p.campaign_id
   WHERE p.owner_id=? AND p.campaign_id=? AND p.credential_id=? AND g.revoked=0 AND c.revoked=0 AND c.role='device' AND g.expires_at>? AND c.expires_at>?`)
   .bind(this.ownerId,campaignId,credentialId,now,now).first();
  if(!row||now-row.last_push_at<1500)return false;
  const claim=await this.db.prepare('UPDATE bridge_campaign_push_tokens SET last_push_at=? WHERE owner_id=? AND campaign_id=? AND credential_id=? AND last_push_at<?')
   .bind(now,this.ownerId,campaignId,credentialId,now-1500).run();
  if(!claim?.meta?.changes)return false; // another wake already claimed the throttle window
  const token=await this.decrypt(row.token_iv,row.token_ciphertext,this.context(campaignId,credentialId)),accessToken=await this.accessToken();
  const data={type:'ASTRAVAR_CAMPAIGN_SYNC_WAKE',version:'1',campaignId,credentialId,
   grantId:row.grant_id,grantRevision:String(row.grant_revision),issuedAt:String(now),
   expiresAt:String(Math.min(now+30_000,row.grant_expires_at,row.credential_expires_at)),wakeId:crypto.randomUUID()};
  data.signature=await this.signWake(data,row.credential_secret_hash);
  this.metric('push_requested',{wakeId:data.wakeId,priority:row.notifications_enabled?'HIGH':'NORMAL'});
  // Data-only payload: Android's FirebaseMessagingService schedules a short
  // WorkManager sync and shows a user-visible notification if opted in.
  // HIGH priority is reserved for visible notifications, never silent polls.
  const priority=row.notifications_enabled?'HIGH':'NORMAL';
  const response=await this.fetcher(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(this.projectId)}/messages:send`,{
   method:'POST',headers:{Authorization:'Bearer '+accessToken,'Content-Type':'application/json'},
   body:JSON.stringify({message:{token,data,android:{priority,ttl:'30s',collapse_key:'astravar_campaign_sync'}}})
  });
  if(!response.ok){
   // Expired/rotated registrations must not be retried forever. Do not delete
   // on generic INVALID_ARGUMENT or project/auth errors: those may be sender bugs.
   const failure=await jsonBody(response),unregistered=failure?.error?.details?.some(x=>x.errorCode==='UNREGISTERED');
   if(unregistered)await this.db.prepare('DELETE FROM bridge_campaign_push_tokens WHERE owner_id=? AND campaign_id=? AND credential_id=? AND token_hash=?').bind(this.ownerId,campaignId,credentialId,row.token_hash).run();
   this.metric('push_failed',{wakeId:data.wakeId,status:response.status,durationMilliseconds:performance.now()-started,registrationRemoved:!!unregistered});
   throw Error('PUSH_SEND_FAILED');
  }
  this.metric('push_accepted_by_fcm',{wakeId:data.wakeId,durationMilliseconds:performance.now()-started});
  return true;
 }
}
