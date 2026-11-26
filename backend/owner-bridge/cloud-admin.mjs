import { CLOUD_SCOPES } from './cloud-validation.mjs';

export async function cloudAccessAdmin(service,principal,id) {
  service.owner(principal,id);const c=await service.continuity(id),b=service.key(id);
  const rows=(await service.s('SELECT * FROM cloud_grants WHERE owner_id=? AND continuity_id=? ORDER BY subject LIMIT 251',...b).all()).results;
  const devices=(await service.s('SELECT id,device_name,expires_at,revoked FROM bridge_credentials WHERE owner_id=? AND campaign_id=? ORDER BY device_name,id LIMIT 251',...b).all()).results;
  const audit=(await service.s('SELECT event_id,epoch,subject,grant_revision,expires_at,revoked,recorded_at FROM cloud_grant_audit WHERE owner_id=? AND continuity_id=? ORDER BY recorded_at DESC,event_id DESC LIMIT 30',...b).all()).results;
  return {continuityId:id,epoch:c.epoch,categories:CLOUD_SCOPES,grants:rows.slice(0,250).map(g=>({subject:g.subject,scopes:JSON.parse(g.scopes_json),actors:JSON.parse(g.actors_json),auto:JSON.parse(g.auto_json),expiresAt:g.expires_at,revoked:Boolean(g.revoked),revision:g.revision})),
    devices:devices.slice(0,250).map(d=>({subject:'device:'+d.id,name:d.device_name,active:!d.revoked&&d.expires_at>service.now()})),truncated:rows.length>250||devices.length>250,audit};
}

export function cloudAccessPage(){
  const nonce=crypto.randomUUID().replaceAll('-','');
  return new Response(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Astravar · Campaign permissions</title><style nonce="${nonce}">body{background:#101724;color:#f0e7d1;font:1rem/1.5 system-ui;max-width:48rem;margin:auto;padding:1rem}label,section{display:block;margin:.8rem 0}button,select,input{min-height:42px}table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:.35rem}fieldset{border:1px solid #586071}h1{color:#eed18c}pre{white-space:pre-wrap;overflow-wrap:anywhere}a{color:#eed18c}</style>
<a id="back">Owner review</a><h1>Campaign permissions</h1><p id="status" role="status">Loading…</p><section id="form"></section><section id="review"></section><details><summary>Permission history</summary><div id="history"></div></details>
<script nonce="${nonce}">
const continuity=location.pathname.split('/').at(-2),base='/v3/continuities/'+encodeURIComponent(continuity),status=document.getElementById('status'),form=document.getElementById('form'),review=document.getElementById('review');let model;
document.getElementById('back').href=location.pathname.slice(0,-7);
function el(p,k,t){const e=document.createElement(k);if(t!==undefined)e.textContent=t;p.appendChild(e);return e}
function localdate(m){const d=new Date(m);return new Date(m-d.getTimezoneOffset()*60000).toISOString().slice(0,16)}
async function load(){const r=await fetch(base+'/access',{credentials:'same-origin',cache:'no-store'});if(!r.ok)throw Error('Owner access unavailable.');model=await r.json();status.textContent=model.truncated?'The account list reached its display limit.':'';view('sites-mcp');const h=document.getElementById('history');h.replaceChildren();for(const x of model.audit)el(h,'p',new Date(x.recorded_at).toLocaleString()+' · '+x.subject+' · permission revision '+x.grant_revision+(x.revoked?' · revoked':''))}
function view(subject){form.replaceChildren();review.replaceChildren();const g=model.grants.find(x=>x.subject===subject);const label=el(form,'label','Authorized client '),select=el(label,'select');const items=[{subject:'sites-mcp',name:'Authorized DM'},...model.devices];for(const x of items){const o=el(select,'option',x.name);o.value=x.subject;o.selected=x.subject===subject}select.onchange=()=>view(select.value);
const a=el(form,'label','Allowed actor IDs, separated by commas '),actors=el(a,'input');actors.value=(g?.actors||[]).join(', ');actors.placeholder='Enter actor IDs or * for the whole continuity';
const x=el(form,'label','Grant expires at '),expiry=el(x,'input');expiry.type='datetime-local';expiry.value=g?localdate(g.expiresAt):'';
const table=el(form,'table'),head=el(table,'tr');['Category','Read','Write','Auto-commit'].forEach(name=>el(head,'th',name));const fields=[];
for(const cat of model.categories){const row=el(table,'tr');el(row,'td',cat);const checks=['read','write','auto'].map(kind=>{const box=el(el(row,'td'),'input');box.type='checkbox';box.setAttribute('aria-label',cat+' '+kind);box.checked=kind==='auto'?g?.auto.includes(cat+'.write')??false:g?.scopes.includes(cat+'.'+kind)??false;return box;});fields.push([cat,...checks]);}
const inbox=el(form,'label'),inboxCheck=el(inbox,'input');inboxCheck.type='checkbox';inboxCheck.checked=g?.scopes.includes('approval.read')??false;el(inbox,'span',' Device may see actionable review count');
for(const revoked of [false,true]){if(revoked&&!g)continue;const b=el(form,'button',revoked?'Review revocation':'Review permission changes');b.onclick=()=>{const scopes=[],auto=[];for(const [cat,r,w,c] of fields){if(r.checked)scopes.push(cat+'.read');if(w.checked)scopes.push(cat+'.write');if(c.checked)auto.push(cat+'.write');}if(inboxCheck.checked)scopes.push('approval.read');const values=actors.value.split(',').map(x=>x.trim()).filter(Boolean);const proposed={scopes,actors:values,auto,expiresAt:new Date(expiry.value).getTime(),revoked,expectedGrantRevision:g?.revision??0};
review.replaceChildren();el(review,'h2',revoked?'Revoke access':'Verify permissions');el(review,'p','Client: '+select.selectedOptions[0].textContent);el(review,'pre',JSON.stringify(proposed,null,2));const commit=el(review,'button',revoked?'Confirm revocation':'Save reviewed permissions');commit.onclick=async()=>{commit.disabled=true;try{const r=await fetch(base+'/grants/'+encodeURIComponent(subject),{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify(proposed)});const v=await r.json();if(!r.ok)throw Error(v.error?.code||'Permission update unverified');await load();view(subject);status.textContent='Saved permission revision '+v.revision}catch(e){status.textContent=e.message;commit.disabled=false}}}}
}
load().catch(e=>status.textContent=e.message);</script></html>`,{headers:{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'none'; script-src 'nonce-"+nonce+"'; style-src 'nonce-"+nonce+"'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"}});
}
