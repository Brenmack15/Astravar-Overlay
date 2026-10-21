/** Private Sites transport enrollment. A verified owner explicitly encrypts the
 * outer transport credential to this installation; device enrollment remains a
 * separate, narrower server authorization. No plaintext credential file exists.
 */
const BOOTSTRAP_CAMPAIGN_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const BOOTSTRAP_ALGORITHM = 'RSA-OAEP-256+A256GCM';
const bootstrapEncoder = new TextEncoder();
const BOOTSTRAP_TICKET_TTL_MS = 2 * 60_000;
async function bootstrapTicketHash(value) {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', bootstrapEncoder.encode(value)))].map(byte => byte.toString(16).padStart(2, '0')).join('');
}
function bootstrapJson(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: {
    'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
  } });
}
function bootstrapBase64(value) {
  return btoa(String.fromCharCode(...new Uint8Array(value))).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}
function bootstrapBytes(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value) || value.length > 1200) throw new Error('INVALID_PUBLIC_KEY');
  return Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - value.length % 4) % 4)), value => value.charCodeAt(0));
}
export function bootstrapAad(value) {
  return `ASTRAVAR_SERVICE_BOOTSTRAP_V1\n${value.origin}\n${value.campaignId}\n${value.nonce}\n${value.expiresAt}`;
}
export async function encryptBootstrapBundle(publicKeySpki, payload, now = Date.now()) {
  if (!payload || typeof payload.campaignId!=='string' || !BOOTSTRAP_CAMPAIGN_ID.test(payload.campaignId) || !/^[A-Za-z0-9_-]{43}$/.test(payload.nonce) ||
      typeof payload.serviceAccessBearer !== 'string' || payload.serviceAccessBearer.length < 32 || payload.serviceAccessBearer.length > 8192 ||
      /[\r\n]/.test(payload.serviceAccessBearer)) throw new Error('INVALID_BOOTSTRAP');
  const origin = new URL(payload.origin);
  if (origin.protocol !== 'https:' || origin.origin !== payload.origin || origin.port || origin.username || origin.password) throw new Error('INVALID_ORIGIN');
  const rsa = await crypto.subtle.importKey('spki', bootstrapBytes(publicKeySpki), { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt']);
  if (rsa.algorithm.modulusLength < 2048 || rsa.algorithm.modulusLength > 4096) throw new Error('INVALID_PUBLIC_KEY');
  const aes = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const header = { v: 1, algorithm: BOOTSTRAP_ALGORITHM, origin: payload.origin, campaignId: payload.campaignId,
    nonce: payload.nonce, expiresAt: now + 10 * 60_000 };
  const secret = bootstrapEncoder.encode(JSON.stringify({ ...header, serviceAccessBearer: payload.serviceAccessBearer }));
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: bootstrapEncoder.encode(bootstrapAad(header)), tagLength: 128 }, aes, secret);
  secret.fill(0);
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', aes));
  const wrappedKey = await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, rsa, raw);
  raw.fill(0);
  return { ...header, wrappedKey: bootstrapBase64(wrappedKey), iv: bootstrapBase64(iv), ciphertext: bootstrapBase64(ciphertext) };
}
const BOOTSTRAP_HTML = `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Astravar private service authorization</title><style>body{background:#101622;color:#edf1f7;font:17px system-ui;max-width:34rem;margin:3rem auto;padding:1.3rem;line-height:1.5}h1{color:#d9b66d;font-size:1.6rem}button{min-height:52px;background:#d9b66d;color:#101622;font:inherit;border:0;border-radius:12px;padding:.8rem 1.2rem;width:100%}code{overflow-wrap:anywhere}p{margin:1.2rem 0}</style><h1>Authorize this Astravar installation</h1><p>Continue only if you opened this page from Astravar on your phone. The download is encrypted for that installation and expires in 10 minutes.</p><p>Request code: <button type="button" id="copy-request-code" aria-label="Copy displayed request code" disabled><span aria-hidden="true">⧉ </span><code id="code">Invalid request</code></button><span id="copy-code-status" role="status" aria-live="polite"></span></p><p>This grants access through the private service transport. Your device must still be paired separately before it can access its explicitly approved character scope. This file alone does not authorize character uploads or changes.</p><form id="authorization" method="post" action="/owner/bootstrap/download" enctype="application/x-www-form-urlencoded"><input type="hidden" name="origin"><input type="hidden" name="campaignId"><input type="hidden" name="nonce"><input type="hidden" name="publicKeySpki"><button id="download" type="submit" disabled>Download encrypted authorization</button></form><p id="status" role="status">Your browser handles the download. Check Downloads for Astravar-encrypted-authorization.json, then import it into Astravar. If no file appears, return here and retry. This page cannot confirm that a file was saved.</p><script nonce="BOOTSTRAP_NONCE">
const q=new URLSearchParams(location.hash.slice(1));const key=q.get('publicKeySpki'),nonce=q.get('nonce'),campaignId=q.get('campaignId');
const form=document.getElementById('authorization'),button=document.getElementById('download'),status=document.getElementById('status');
const names=[...q.keys()];const valid=names.length===3&&new Set(names).size===3&&names.every(name=>['campaignId','nonce','publicKeySpki'].includes(name))&&/^[A-Za-z0-9_-]{1,1200}$/.test(key||'')&&/^[A-Za-z0-9_-]{43}$/.test(nonce||'')&&/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(campaignId||'');
if(valid){document.getElementById('code').textContent=nonce.slice(0,12);for(const [name,value]of Object.entries({origin:location.origin,campaignId,nonce,publicKeySpki:key})){form.elements.namedItem(name).value=value}button.disabled=false;document.getElementById('copy-request-code').disabled=false}else{status.textContent='This authorization request is invalid. Return to Astravar and start private service authorization again.'}
document.getElementById('copy-request-code').addEventListener('click',async()=>{if(!valid)return;const displayed=document.getElementById('code').textContent;try{await navigator.clipboard.writeText(displayed);document.getElementById('copy-code-status').textContent='Copied to clipboard.';}catch{document.getElementById('copy-code-status').textContent='Clipboard unavailable. Select the displayed code to copy it.';}});
form.addEventListener('submit',event=>{if(!valid){event.preventDefault();return}status.textContent='Download requested. Check your browser Downloads for Astravar-encrypted-authorization.json, then import it into Astravar. If no file appears, return here and retry. This page cannot confirm that a file was saved.'});
</script></html>`;
export async function handleBootstrap(request, env) {
  if (!env.OWNER_SITE_USER_ID || request.headers.get('oai-authenticated-user-id') !== env.OWNER_SITE_USER_ID) return bootstrapJson({ error: 'OWNER_AUTHENTICATION_REQUIRED' }, 401);
  const url = new URL(request.url);
  if (url.protocol !== 'https:' || url.port) return bootstrapJson({ error: 'HTTPS_REQUIRED' }, 400);
  const ticketMatch = url.pathname.match(/^\/owner\/bootstrap\/download\/([A-Za-z0-9_-]{43})$/);
  if (ticketMatch && (request.method === 'GET' || request.method === 'HEAD')) {
    if (!env.DB) return bootstrapJson({ error: 'DOWNLOAD_STORAGE_NOT_PROVISIONED' }, 503);
    try {
      const now = Date.now();
      await env.DB.prepare('DELETE FROM bridge_bootstrap_downloads WHERE expires_at<=?').bind(now).run();
      const ticket = await env.DB.prepare('SELECT envelope_json FROM bridge_bootstrap_downloads WHERE ticket_hash=? AND owner_id=? AND expires_at>?')
        .bind(await bootstrapTicketHash(ticketMatch[1]), env.OWNER_SITE_USER_ID, now).first();
      if (!ticket) return bootstrapJson({ error: 'DOWNLOAD_EXPIRED_OR_UNKNOWN' }, 404);
      // Android download managers may retry with GET, HEAD or Range. Keep the same
      // ciphertext available until expiry; a Range request deliberately gets full 200.
      return new Response(request.method === 'HEAD' ? null : ticket.envelope_json, { status: 200, headers: {
        'Content-Type': 'application/json; charset=utf-8', 'Content-Disposition': 'attachment; filename="Astravar-encrypted-authorization.json"',
        'Content-Length': String(bootstrapEncoder.encode(ticket.envelope_json).byteLength), 'Accept-Ranges': 'none',
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
      } });
    } catch { return bootstrapJson({ error: 'DOWNLOAD_STORAGE_UNAVAILABLE' }, 503); }
  }
  if (url.pathname === '/owner/bootstrap' && request.method === 'GET') {
    const scriptNonce = bootstrapBase64(crypto.getRandomValues(new Uint8Array(24)));
    return new Response(BOOTSTRAP_HTML.replace('BOOTSTRAP_NONCE', scriptNonce), { headers: {
    'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
    // A native POST needs its same-origin Origin header; no-referrer can serialize it as null.
    // Cross-origin referrers stay suppressed and URL fragments are never sent as referrers.
    'Referrer-Policy': 'same-origin', 'Content-Security-Policy': `default-src 'none'; script-src 'nonce-${scriptNonce}'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`,
    } });
  }
  const download = url.pathname === '/owner/bootstrap/download';
  if ((!download && url.pathname !== '/owner/bootstrap/bundle') || request.method !== 'POST') return bootstrapJson({ error: 'NOT_FOUND' }, 404);
  const contentType = download ? 'application/x-www-form-urlencoded' : 'application/json';
  if (request.headers.get('origin') !== url.origin || request.headers.get('content-type')?.split(';')[0].trim() !== contentType) return bootstrapJson({ error: download ? 'SAME_ORIGIN_FORM_REQUIRED' : 'SAME_ORIGIN_JSON_REQUIRED' }, 403);
  if (!env.SITES_SERVICE_ACCESS_BEARER) return bootstrapJson({ error: 'PRIVATE_TRANSPORT_NOT_PROVISIONED' }, 503);
  let body;
  try {
    const declaredLength = request.headers.get('content-length');
    if (declaredLength !== null && (!/^\d+$/.test(declaredLength) || Number(declaredLength) > 4096)) return bootstrapJson({ error: 'REQUEST_TOO_LARGE' }, 413);
    const reader = request.body?.getReader(); if (!reader) throw new Error();
    const chunks = []; let length = 0;
    for (;;) { const { value, done } = await reader.read(); if (done) break; length += value.length;
      if (length > 4096) { await reader.cancel(); return bootstrapJson({ error: 'REQUEST_TOO_LARGE' }, 413); } chunks.push(value); }
    const bytes = new Uint8Array(length); let at = 0; for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length; }
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    if (download) {
      if (/%(?![A-Fa-f0-9]{2})/.test(text)) throw new Error();
      const entries = [...new URLSearchParams(text).entries()];
      if (entries.length !== 4 || new Set(entries.map(([key]) => key)).size !== 4) throw new Error();
      body = Object.fromEntries(entries);
    } else body = JSON.parse(text);
    if (!body || Array.isArray(body) || Object.keys(body).sort().join(',') !== 'campaignId,nonce,origin,publicKeySpki' || body.origin !== url.origin) throw new Error();
    const bundle = await encryptBootstrapBundle(body.publicKeySpki, { ...body, serviceAccessBearer: env.SITES_SERVICE_ACCESS_BEARER });
    if (!download) return bootstrapJson(bundle);
    if (!env.DB) return bootstrapJson({ error: 'DOWNLOAD_STORAGE_NOT_PROVISIONED' }, 503);
    const ticket = bootstrapBase64(crypto.getRandomValues(new Uint8Array(32))), now = Date.now();
    try {
      await env.DB.batch([
        env.DB.prepare('DELETE FROM bridge_bootstrap_downloads WHERE expires_at<=?').bind(now),
        env.DB.prepare('INSERT INTO bridge_bootstrap_downloads(ticket_hash,owner_id,envelope_json,expires_at,created_at) VALUES(?,?,?,?,?)')
          .bind(await bootstrapTicketHash(ticket), env.OWNER_SITE_USER_ID, JSON.stringify(bundle), Math.min(now + BOOTSTRAP_TICKET_TTL_MS, bundle.expiresAt), now),
      ]);
    } catch (error) {
      return bootstrapJson({ error: String(error).includes('BOOTSTRAP_DOWNLOAD_LIMIT') ? 'DOWNLOAD_LIMIT' : 'DOWNLOAD_STORAGE_UNAVAILABLE' }, String(error).includes('BOOTSTRAP_DOWNLOAD_LIMIT') ? 429 : 503);
    }
    return new Response(null, { status: 303, headers: {
      Location: '/owner/bootstrap/download/' + ticket, 'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
    } });
  } catch { return bootstrapJson({ error: 'INVALID_BOOTSTRAP_REQUEST' }, 400); }
}
