import {BridgeError,MODE,CAMPAIGN} from './core.mjs';
const MAX_BODY=65_536;
function response(status,value) {
 return Response.json(value,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'}});
}
async function readBody(request) {
 if(request.headers.get('content-type')?.split(';')[0].trim()!=='application/json') throw new BridgeError(415,'JSON_REQUIRED');
 const length=request.headers.get('content-length');
 if(length!==null&&(!/^\d+$/.test(length)||Number(length)>MAX_BODY)) throw new BridgeError(413,'BODY_TOO_LARGE');
 // Stream bounds matter even when no Content-Length exists.
 const reader=request.body?.getReader(); if(!reader) throw new BridgeError(400,'BODY_REQUIRED');
 let size=0; const chunks=[];
 while(true) { const {done,value}=await reader.read(); if(done) break; size+=value.length; if(size>MAX_BODY){await reader.cancel();throw new BridgeError(413,'BODY_TOO_LARGE');} chunks.push(value); }
 const bytes=new Uint8Array(size); let offset=0; for(const c of chunks){bytes.set(c,offset);offset+=c.length;}
 try{return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));}catch{throw new BridgeError(400,'INVALID_JSON');}
}
/** Outer host must separately enforce its authenticated Site/service gateway.
 * ownerPrincipal must originate from verified host identity, never request JSON/header supplied by a device.
 */
export async function handleOwnerApi(request,bridge,{ownerPrincipal=null,liveHp=null,campaignBridge=null}={}) {
 try {
  const url=new URL(request.url),path=url.pathname,method=request.method;
  // No browser-origin bypass: owner routes require a verified host principal and same-origin fetch.
  const origin=request.headers.get('origin');
  if(origin&&origin!==url.origin) throw new BridgeError(403,'CROSS_ORIGIN_DENIED');
  if(method==='POST'&&path==='/v1/pairings') return response(200,{...await bridge.requestPairing(await readBody(request)),verificationUri:new URL('/owner',url).href});
  if(method==='POST'&&path==='/v1/pairings/token') {
   const result=await bridge.pollPairing(await readBody(request)); return response(result.status==='PENDING'?202:200,result);
  }
  if(method==='POST'&&path==='/v1/owner/pairings/summary') {
   if(!ownerPrincipal) throw new BridgeError(401,'OWNER_AUTHENTICATION_REQUIRED');
   return response(200,await bridge.pairingSummary(ownerPrincipal,await readBody(request)));
  }
  if(method==='POST'&&path==='/v1/owner/pairings/approve') {
   if(!ownerPrincipal) throw new BridgeError(401,'OWNER_AUTHENTICATION_REQUIRED');
   return response(200,await bridge.approvePairing(ownerPrincipal,await readBody(request)));
  }
  if(method==='GET'&&path==='/v1/owner/devices') {
   if(!ownerPrincipal) throw new BridgeError(401,'OWNER_AUTHENTICATION_REQUIRED');
   return response(200,await bridge.listDevices(ownerPrincipal));
  }
  if(method==='POST'&&path==='/v1/owner/devices/revoke') {
   if(!ownerPrincipal) throw new BridgeError(401,'OWNER_AUTHENTICATION_REQUIRED');
   const b=await readBody(request);
   if(!b||Object.keys(b).length!==1||typeof b.credentialId!=='string') throw new BridgeError(400,'INVALID_REQUEST');
   const c=await bridge.s('SELECT campaign_id FROM bridge_credentials WHERE id=? AND owner_id=?',b.credentialId,bridge.ownerId).first();
   if(!c) throw new BridgeError(404,'DEVICE_UNKNOWN');
   return response(200,await bridge.revokeDevice(ownerPrincipal,c.campaign_id,b.credentialId));
  }
  const v2=path.match(/^\/v2\/campaigns\/([A-Za-z0-9._-]+)\/(.+)$/);
  if(v2){
   if(!campaignBridge)throw new BridgeError(503,'CAMPAIGN_CONNECTION_NOT_CONFIGURED');
   const [,campaign,action]=v2,authorization=request.headers.get('authorization')||'';
   if(!authorization.startsWith('Bearer '))throw new BridgeError(401,'AUTHENTICATION_REQUIRED');
   const principal=await bridge.authenticate(request.headers.get('X-Astravar-Credential')||'',authorization.slice(7),{forSelfRevocation:method==='POST'&&action==='devices/revoke'});let value;
   if(method==='GET'&&action==='grant')value=await campaignBridge.getGrant(principal,campaign);
   else if(method==='POST'&&action==='grant')value=await campaignBridge.configureGrant(principal,campaign,await readBody(request));
   else if(method==='POST'&&action==='records')value=await campaignBridge.publish(principal,campaign,await readBody(request));
   else if(method==='GET'&&action==='records')value=await campaignBridge.read(principal,campaign,{kinds:url.searchParams.has('kind')?[url.searchParams.get('kind')]:[],recordId:url.searchParams.get('recordId'),after:url.searchParams.get('after')??'',limit:Number(url.searchParams.get('limit')??25)});
   else if(method==='GET'&&action.startsWith('records/')){const bits=action.split('/');if(bits.length!==3)throw new BridgeError(404,'NOT_FOUND');const result=await campaignBridge.read(principal,campaign,{kinds:[decodeURIComponent(bits[1])],recordId:decodeURIComponent(bits[2])});if(!result.records.length)throw new BridgeError(404,'RECORD_NOT_PUBLISHED');const row=result.records[0];value={...result,record:{recordId:row.recordId,kind:row.kind,revision:row.revision,fields:row.fields}};}
   else if(method==='GET'&&/^publications\/[0-9a-f-]+$/.test(action))value=await campaignBridge.publicationReadback(principal,campaign,action.split('/')[1]);
   else if(method==='GET'&&action==='pending')value=await campaignBridge.pending(principal,campaign);
   else if(method==='POST'&&action==='decisions')value=await campaignBridge.decide(principal,campaign,await readBody(request));
   else if(method==='GET'&&/^receipts\/[0-9a-f-]+$/.test(action))value=await campaignBridge.getReceipt(principal,campaign,action.split('/')[1]);
   else if(method==='POST'&&action==='devices/revoke'){const b=await readBody(request);if(!b||Object.keys(b).length!==0)throw new BridgeError(400,'INVALID_REQUEST');value={...await bridge.revokeDevice(principal,campaign,principal.credentialId),mode:'ASTRAVAR_CAMPAIGN_V2'};}
   else throw new BridgeError(404,'NOT_FOUND');return response(200,value);
  }
  const liveMatch=path.match(/^\/v1\/hp\/campaigns\/([A-Za-z0-9._-]+)\/(.+)$/);
  const match=liveMatch||path.match(/^\/v1\/campaigns\/([A-Za-z0-9._-]+)\/(.+)$/);
  if(!match) throw new BridgeError(404,'NOT_FOUND');
  const [,campaign,action]=match,authorization=request.headers.get('authorization')||'';
  if(!authorization.startsWith('Bearer ')) throw new BridgeError(401,'AUTHENTICATION_REQUIRED');
  const principal=await bridge.authenticate(request.headers.get('X-Astravar-Credential')||'',authorization.slice(7),{forSelfRevocation:method==='POST'&&action==='devices/revoke'});
  let value;
  if(liveMatch){
   if(!liveHp) throw new BridgeError(503,'HP_CONNECTION_NOT_CONFIGURED');
   if(method==='POST'&&action==='heartbeat') value=await liveHp.heartbeat(principal,campaign,await readBody(request));
   else if(method==='POST'&&action==='characters') value=await liveHp.publishCharacter(principal,campaign,await readBody(request));
   else if(method==='GET'&&/^characters\/[A-Za-z0-9._-]+$/.test(action)) value=await liveHp.getCharacterHp(principal,campaign,action.split('/')[1]);
   else if(method==='GET'&&action==='pending') value=await liveHp.getPendingUpdates(principal,campaign,principal.profileId);
   else if(method==='POST'&&action==='decisions') value=await liveHp.decide(principal,campaign,await readBody(request));
   else if(method==='GET'&&/^receipts\/[0-9a-f-]+$/.test(action)) value=await liveHp.getCommandReceipt(principal,campaign,action.split('/')[1],principal.profileId);
   else if(method==='GET'&&action==='history') value=await liveHp.history(principal,campaign,Number(url.searchParams.get('after')??0));
   else if(method==='POST'&&action==='devices/revoke'){const b=await readBody(request);if(!b||Object.keys(b).length!==0)throw new BridgeError(400,'INVALID_REQUEST');value=await bridge.revokeDevice(principal,campaign,principal.credentialId);}
   else throw new BridgeError(404,'NOT_FOUND');
   return response(200,{...value,mode:'ASTRAVAR_OWNER_HP_V1'});
  }
  if(method==='POST'&&action==='characters') value=await bridge.publishCharacter(principal,campaign,await readBody(request));
  else if(method==='GET'&&/^characters\/[A-Za-z0-9._-]+$/.test(action)) value=await bridge.getCharacter(principal,campaign,action.split('/')[1]);
  else if(method==='GET'&&action==='pending') value=await bridge.getPendingUpdates(principal,campaign);
  else if(method==='POST'&&action==='decisions') value=await bridge.decide(principal,campaign,await readBody(request));
  else if(method==='GET'&&/^receipts\/[0-9a-f-]+$/.test(action)) value=await bridge.getCommandReceipt(principal,campaign,action.split('/')[1]);
  else if(method==='GET'&&action==='history') value=await bridge.history(principal,campaign,Number(url.searchParams.get('after')??0));
  else if(method==='POST'&&action==='devices/revoke') { const b=await readBody(request); if(!b||Object.keys(b).length!==0) throw new BridgeError(400,'INVALID_REQUEST'); value=await bridge.revokeDevice(principal,campaign,principal.credentialId); }
  else throw new BridgeError(404,'NOT_FOUND');
  return response(200,value);
 } catch(error) {
  if(error instanceof BridgeError) return response(error.status,{mode:new URL(request.url).pathname.startsWith('/v2/')?'ASTRAVAR_CAMPAIGN_V2':new URL(request.url).pathname.startsWith('/v1/hp/')?'ASTRAVAR_OWNER_HP_V1':MODE,error:{code:error.code}});
  // Never return SQL, credentials, source records or exception strings to callers.
  return response(503,{mode:MODE,error:{code:'SERVICE_UNAVAILABLE'}});
 }
}
