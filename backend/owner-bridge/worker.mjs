/** Sites-only entrypoint. Do not expose this handler behind an unauthenticated
 * proxy: Sites must verify/replace all oai-authenticated-* identity headers.
 */
import { CampaignBridge } from './campaign.mjs';
import { LiveHpBridge } from './live-hp.mjs';
import { OwnerBridge, MODE } from './core.mjs';
import { handleOwnerApi } from './http.mjs';
import { handleMcp } from './mcp.mjs';
import { handleBootstrap } from './bootstrap.mjs';

const headers = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' };
const json = (value, status) => Response.json(value, { status, headers });

function ownerPage() {
  const nonce = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(24))));
  return new Response(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Astravar Owner Connection</title>
<style>body{color:#eee9df;background:#10151e;font:18px system-ui;max-width:32rem;margin:auto;padding:1.2rem}h1{color:#dbc081;font-size:1.7rem}label{display:block;margin:1rem 0 .35rem}input,button{box-sizing:border-box;min-height:52px;width:100%;font:inherit;border-radius:12px;padding:.7rem}input{color:inherit;background:#1e2734;border:1px solid #637085}button{background:#dbc081;border:0;color:#121820;margin:.7rem 0}a{color:#e6cb8e}pre{white-space:pre-wrap;overflow-wrap:anywhere}section{margin:1.5rem 0;padding:1rem;border:1px solid #4c5361;border-radius:16px}.muted{color:#bdc5cf}</style>
<h1>Astravar Owner Connection</h1><p class="muted">Private campaign records and Trusted DM Auto-Sync. Each enrolled device is bound to its selected campaign and owner-approved categories.</p>
<section><h2>Pair this phone</h2><p>Start in Astravar → Config → Owner Connection. Open the encrypted bootstrap link on the same phone, then enter the pairing code shown in Astravar.</p>
<form id="pair"><label for="code">Device pairing code</label><input id="code" name="userCode" autocomplete="off" spellcheck="false" maxlength="100" required><button id="copy-code" type="button" aria-label="Copy displayed pairing code">⧉ Copy pairing code</button><p id="copy-result" role="status" aria-live="polite"></p><button id="review-pair" type="button">Review requested access</button><button id="approve-pair" disabled>Approve this device</button></form><pre id="pair-result" role="status" aria-live="polite"></pre></section>
<section><h2>Revoke a device</h2><p>Revocation stops future server access. A device may retain its existing local records.</p><button id="devices">Refresh enrolled devices</button><pre id="device-list" role="status"></pre>
<form id="revoke"><label for="credential">Device credential ID</label><input id="credential" name="credentialId" autocomplete="off" spellcheck="false" maxlength="128" required><button>Revoke this device</button></form><pre id="revoke-result" role="status" aria-live="polite"></pre></section>
<p>Campaign enrollment grants only the categories shown during review. Trusted DM Auto-Sync starts disabled. Enable selected approved write categories in Astravar to save resolved gameplay without a separate device approval for each command. Manual approval remains available.</p>
<script nonce="${nonce}">
const submit=async(path,value,target)=>{const output=document.getElementById(target);output.textContent='Contacting server…';try{const response=await fetch(path,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify(value),redirect:'error'});const result=await response.json();output.textContent=response.ok?JSON.stringify(result,null,2):'Request failed: '+(result.error?.code||response.status);}catch{output.textContent='Unable to reach the authenticated server. Retry when online.';}};
let reviewedCode=null;
document.getElementById('copy-code').addEventListener('click',async()=>{const code=document.getElementById('code').value;const result=document.getElementById('copy-result');if(!code){result.textContent='Enter the displayed pairing code first.';return;}try{await navigator.clipboard.writeText(code);result.textContent='Copied to clipboard.';}catch{result.textContent='Clipboard unavailable. Select the displayed code to copy it.';}});
document.getElementById('code').addEventListener('input',()=>{reviewedCode=null;document.getElementById('approve-pair').disabled=true;});
document.getElementById('review-pair').addEventListener('click',async()=>{const code=document.getElementById('code').value.trim(),output=document.getElementById('pair-result');reviewedCode=null;document.getElementById('approve-pair').disabled=true;try{const r=await fetch('/v1/owner/pairings/summary',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify({userCode:code}),redirect:'error'});const v=await r.json();output.textContent=r.ok?JSON.stringify(v,null,2):'Request failed: '+(v.error?.code||r.status);if(r.ok){reviewedCode=code;document.getElementById('approve-pair').disabled=false;}}catch{output.textContent='Unable to review device access. Retry when online.';}});
document.getElementById('pair').addEventListener('submit',event=>{event.preventDefault();const code=document.getElementById('code').value.trim();if(code!==reviewedCode)return;document.getElementById('approve-pair').disabled=true;reviewedCode=null;submit('/v1/owner/pairings/approve',{userCode:code},'pair-result');});
document.getElementById('revoke').addEventListener('submit',event=>{event.preventDefault();submit('/v1/owner/devices/revoke',{credentialId:document.getElementById('credential').value.trim()},'revoke-result');});
document.getElementById('devices').addEventListener('click',async()=>{const output=document.getElementById('device-list');try{const response=await fetch('/v1/owner/devices',{credentials:'same-origin',redirect:'error'});const value=await response.json();output.textContent=response.ok?JSON.stringify(value,null,2):'Unable to list devices.';}catch{output.textContent='Unable to reach the server.';}});
</script></html>`, { headers: { ...headers, 'Content-Type': 'text/html; charset=utf-8',
      'Content-Security-Policy': `default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'` } });
}

export async function handleWorker(request, env) {
  const url = new URL(request.url);
  if (url.protocol !== 'https:') return json({ mode: MODE, error: { code: 'HTTPS_REQUIRED' } }, 400);
  // Setup reveals only the requesting visitor's own Sites-verified identifier.
  // It cannot claim ownership, set configuration, pair, or access records.
  if (request.method === 'GET' && url.pathname === '/owner/setup/identity') {
    const visitorId = request.headers.get('oai-authenticated-user-id');
    if (!visitorId || visitorId.length > 200 || /[\r\n]/.test(visitorId)) return json({ error: { code: 'SITES_SIGN_IN_REQUIRED' } }, 401);
    return json({ siteScopedUserId: visitorId, instruction: 'For this new owner-private Site only: configure OWNER_SITE_USER_ID server-side with this verified ID. This route changes no ownership or records.' }, 200);
  }
  const ownerId = env?.OWNER_SITE_USER_ID;
  const configured = Boolean(env?.DB && typeof ownerId === 'string' && ownerId.trim());
  const bridge = configured ? new OwnerBridge(env.DB, { ownerId }) : null;
  const liveHp = configured ? new LiveHpBridge(env.DB,{ownerId}) : null;
  const campaignBridge=configured?new CampaignBridge(env.DB,{ownerId}):null;
  // Tool schemas contain no private data and are needed for plugin provisioning.
  // Actual tools/call still requires the configured owner and verified identity.
  if (url.pathname === '/mcp') return handleMcp(request, bridge, { ownerId, allowPublicDiscovery: true,liveHp,campaignBridge });
  if (!configured) return json({ mode: MODE, error: { code: 'OWNER_CONNECTION_NOT_CONFIGURED' } }, 503);
  const isOwner = request.headers.get('oai-authenticated-user-id') === ownerId;
  const ownerPrincipal = isOwner ? { ownerId, role: 'owner', credentialId: 'sites-owner', scopes: [] } : null;
  if (url.pathname.startsWith('/owner/bootstrap')) return handleBootstrap(request, env);
  if (request.method === 'GET' && (url.pathname === '/' || url.pathname === '/owner')) return isOwner ? ownerPage() : json({ mode: MODE, error: { code: 'OWNER_AUTHENTICATION_REQUIRED' } }, 401);
  if ((url.pathname.startsWith('/v1/')||url.pathname.startsWith('/v2/'))) return handleOwnerApi(request, bridge, { ownerPrincipal,liveHp,campaignBridge });
  return json({ mode: MODE, error: { code: 'NOT_FOUND' } }, 404);
}

export default { fetch: handleWorker };
