/** Optional Pushy wake transport. Campaign data and commands remain exclusively
 * in the authenticated Cloudflare/Android protocol. Provider acceptance is not
 * an Android commit receipt. Missing optional configuration never blocks sync.
 */
import {BridgeError} from './core.mjs';
const fail=(status,code)=>{throw new BridgeError(status,code);};
const textEncoder=new TextEncoder();
const enc=value=>textEncoder.encode(value);
// Pushy does not promise a fixed token length; accept bounded safe token text.
const tokenPattern=/^[A-Za-z0-9:_-]{16,4096}$/;
const b64url=bytes=>btoa(String.fromCharCode(...bytes)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/g,'');
const decodeBase64=value=>Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')),x=>x.charCodeAt(0));
const idPattern=/^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;
const commandPattern=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const jsonBody=async response=>{try{return await response.json();}catch{return null;}};
const wakeFields=['type','version','issuedAt','expiresAt','wakeId'];

export class CampaignPushTransport {
 constructor(db,{ownerId,apiKey=null,encryptionKeyBase64=null,
  fetcher=fetch,now=Date.now,random=()=>crypto.getRandomValues(new Uint8Array(12)),observe=()=>{},sendTimeoutMilliseconds=8000}={}){
  if(!ownerId)throw Error('Verified owner is required');
  this.db=db;this.ownerId=ownerId;this.apiKey=apiKey;
  this.encryptionKeyBase64=encryptionKeyBase64;this.fetcher=fetcher;this.now=now;this.random=random;
  this.keyPromise=null;this.observe=observe;
  this.sendTimeoutMilliseconds=Math.max(100,Math.min(10000,Number(sendTimeoutMilliseconds)||8000));
 }
 get configured(){return Boolean(typeof this.apiKey==='string'&&this.apiKey.trim()&&typeof this.encryptionKeyBase64==='string'&&this.encryptionKeyBase64.trim());}
 async key(){
  if(!this.configured)fail(503,'PUSH_NOT_CONFIGURED');
  if(!this.keyPromise)this.keyPromise=crypto.subtle.importKey('raw',decodeBase64(this.encryptionKeyBase64),{name:'AES-GCM'},false,['encrypt','decrypt']);
  return this.keyPromise;
 }
 async hash(value){return b64url(new Uint8Array(await crypto.subtle.digest('SHA-256',enc(value))));}
 // A new authenticated context makes all legacy provider ciphertext ineligible.
 // No destructive D1 migration or old-token transmission is necessary.
 context(campaignId,credentialId){return ['ASTRAVAR_PUSH_TOKEN_V2','pushy',this.ownerId,campaignId,credentialId].join('\n');}
 async encrypt(value,context){const iv=this.random(),bytes=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:enc(context)},await this.key(),enc(value)));return{iv:b64url(iv),ciphertext:b64url(bytes)};}
 async decrypt(iv,ciphertext,context){return new TextDecoder().decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:decodeBase64(iv),additionalData:enc(context)},await this.key(),decodeBase64(ciphertext)));}
 async signWake(data,deviceSecretHash){
  const key=await crypto.subtle.importKey('raw',enc(deviceSecretHash),{name:'HMAC',hash:'SHA-256'},false,['sign']);
  return b64url(new Uint8Array(await crypto.subtle.sign('HMAC',key,enc(wakeFields.map(k=>data[k]).join('\n')))));
 }
 metric(stage,values={}){try{this.observe({component:'campaign_push',stage,at:this.now(),...values});}catch{/* metrics cannot fail delivery */}}

 async register(bridge,principal,campaign,body){
  if(!this.configured)fail(503,'PUSH_NOT_CONFIGURED');
  if(principal?.role!=='device')fail(403,'DEVICE_SCOPE_DENIED');
  if(!body||Object.keys(body).some(k=>!['provider','token','notificationsEnabled'].includes(k))||body.provider!=='pushy'||typeof body.token!=='string'||!tokenPattern.test(body.token)||typeof body.notificationsEnabled!=='boolean')fail(400,'INVALID_PUSH_REGISTRATION');
  const grant=await bridge.authorize(principal,campaign,['device']);
  const encrypted=await this.encrypt(JSON.stringify({provider:'pushy',token:body.token}),this.context(campaign,principal.credentialId)),digest=await this.hash(body.token);
  // Recheck authorization in the same SQL statement that persists the token:
  // revocation may have completed while encryption/hash work was suspended.
  const at=this.now();
  const result=await this.db.prepare(`INSERT INTO bridge_campaign_push_tokens(owner_id,campaign_id,grant_id,credential_id,token_hash,token_iv,token_ciphertext,notifications_enabled,updated_at,last_push_at)
   SELECT ?,?,?,?,?,?,?,?,?,0 FROM bridge_campaign_grants g JOIN bridge_credentials c ON c.id=g.credential_id
   WHERE g.id=? AND g.owner_id=? AND g.campaign_id=? AND g.credential_id=? AND g.revision=?
   AND g.revoked=0 AND g.expires_at>? AND c.owner_id=? AND c.campaign_id=? AND c.role='device' AND c.revoked=0 AND c.expires_at>?
   ON CONFLICT(owner_id,campaign_id,credential_id) DO UPDATE SET
   grant_id=excluded.grant_id,token_hash=excluded.token_hash,token_iv=excluded.token_iv,token_ciphertext=excluded.token_ciphertext,notifications_enabled=excluded.notifications_enabled,updated_at=excluded.updated_at`)
   .bind(this.ownerId,campaign,grant.id,principal.credentialId,digest,encrypted.iv,encrypted.ciphertext,body.notificationsEnabled?1:0,at,
    grant.id,this.ownerId,campaign,principal.credentialId,grant.revision,at,this.ownerId,campaign,at).run();
  if(!result?.meta?.changes)fail(403,'CAMPAIGN_NOT_AUTHORIZED');
  return{mode:'ASTRAVAR_CAMPAIGN_V2',campaignId:campaign,pushRegistered:true,provider:'pushy',notificationVisible:body.notificationsEnabled};
 }
 async unregister(bridge,principal,campaign){
  if(principal?.role!=='device')fail(403,'DEVICE_SCOPE_DENIED');
  await bridge.authorize(principal,campaign,['device']);
  await this.db.prepare('DELETE FROM bridge_campaign_push_tokens WHERE owner_id=? AND campaign_id=? AND credential_id=?')
   .bind(this.ownerId,campaign,principal.credentialId).run();
  return{mode:'ASTRAVAR_CAMPAIGN_V2',campaignId:campaign,pushRegistered:false};
 }
 async notify({campaignId,credentialId,commandId}){
  if(!this.configured||!idPattern.test(campaignId)||!idPattern.test(credentialId)||(commandId!==undefined&&!commandPattern.test(commandId)))return false;
  // Distinct durable commands each retain wake coverage. Provider collapse and
  // Android unique work coalesce bursts; unkeyed retry hints are throttled.
  const now=this.now(),started=performance.now();
  const row=await this.db.prepare(`SELECT p.*,g.revision AS grant_revision,g.expires_at AS grant_expires_at,c.expires_at AS credential_expires_at,c.token_hash AS credential_secret_hash FROM bridge_campaign_push_tokens p
   JOIN bridge_campaign_grants g ON g.id=p.grant_id AND g.owner_id=p.owner_id AND g.campaign_id=p.campaign_id AND g.credential_id=p.credential_id
   JOIN bridge_credentials c ON c.id=p.credential_id AND c.owner_id=p.owner_id AND c.campaign_id=p.campaign_id
   WHERE p.owner_id=? AND p.campaign_id=? AND p.credential_id=? AND g.revoked=0 AND c.revoked=0 AND c.role='device' AND g.expires_at>? AND c.expires_at>?`)
   .bind(this.ownerId,campaignId,credentialId,now,now).first();
  if(!row||(commandId===undefined&&now-row.last_push_at<1500))return false;
  let registration;
  try{registration=JSON.parse(await this.decrypt(row.token_iv,row.token_ciphertext,this.context(campaignId,credentialId)));}catch{return false;}
  if(registration?.provider!=='pushy'||typeof registration.token!=='string'||!tokenPattern.test(registration.token))return false;
  const claim=commandId===undefined?
   await this.db.prepare('UPDATE bridge_campaign_push_tokens SET last_push_at=? WHERE owner_id=? AND campaign_id=? AND credential_id=? AND token_hash=? AND last_push_at<?')
    .bind(now,this.ownerId,campaignId,credentialId,row.token_hash,now-1500).run():
   await this.db.prepare('UPDATE bridge_campaign_push_tokens SET last_push_at=? WHERE owner_id=? AND campaign_id=? AND credential_id=? AND token_hash=?')
    .bind(now,this.ownerId,campaignId,credentialId,row.token_hash).run();
  if(!claim?.meta?.changes)return false;
  const data={type:'ASTRAVAR_CAMPAIGN_SYNC_WAKE',version:'2',issuedAt:String(now),
   expiresAt:String(Math.min(now+30_000,row.grant_expires_at,row.credential_expires_at)),wakeId:crypto.randomUUID()};
  data.signature=await this.signWake(data,row.credential_secret_hash);
  // No campaign, credential, grant, command, gameplay fields, or notification
  // text leave the service. The SDK only schedules authenticated command fetch.
  this.metric('push_requested',{wakeId:data.wakeId,provider:'pushy'});
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),this.sendTimeoutMilliseconds);
  try{
   // Pushy's documented server API authenticates via this fixed HTTPS query.
   // Never log the URL, request body, API key or raw provider exception.
   const response=await this.fetcher('https://api.pushy.me/push?api_key='+encodeURIComponent(this.apiKey),{
    method:'POST',headers:{'Content-Type':'application/json'},redirect:'error',signal:controller.signal,
    body:JSON.stringify({to:registration.token,data,time_to_live:30,collapse_key:'astravar-wake'})
   });
   const result=await jsonBody(response);
   if(!response.ok||result?.success!==true||typeof result?.id!=='string'||!result.id)throw Error('PUSH_SEND_FAILED');
   // An explicitly failed recipient in an otherwise valid response can only
   // remove that same token; generic failures (including wrong app keys) retain it.
   if(Array.isArray(result?.info?.failed)&&result.info.failed.includes(registration.token)){
    await this.db.prepare('DELETE FROM bridge_campaign_push_tokens WHERE owner_id=? AND campaign_id=? AND credential_id=? AND token_hash=?').bind(this.ownerId,campaignId,credentialId,row.token_hash).run();
    this.metric('push_failed',{wakeId:data.wakeId,status:response.status,durationMilliseconds:performance.now()-started,registrationRemoved:true});
    return false;
   }
   this.metric('push_accepted_by_pushy',{wakeId:data.wakeId,durationMilliseconds:performance.now()-started});
   return true;
  }catch{
   this.metric('push_failed',{wakeId:data.wakeId,durationMilliseconds:performance.now()-started,registrationRemoved:false});
   throw Error('PUSH_SEND_FAILED');
  }finally{clearTimeout(timer);}
 }
}
