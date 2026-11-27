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
<title>Astravar · Campaign permissions</title><style nonce="${nonce}">body{background:#101724;color:#f0e7d1;font:1rem/1.5 system-ui;max-width:48rem;margin:auto;padding:1rem}label,section{display:block;margin:.8rem 0}button,select,input,textarea{min-height:44px}textarea{display:block;width:95%;min-height:5rem}table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:.35rem}fieldset{border:1px solid #586071}h1{color:#eed18c}pre{white-space:pre-wrap;overflow-wrap:anywhere}a{color:#eed18c}</style>
<a id="back">Owner review</a><h1>Campaign administration</h1><details><summary>Campaign settings</summary><section id="metadata"></section></details><h2>Permissions</h2><p id="status" role="status">Loading…</p><section id="form"></section><section id="review"></section><details><summary>Permission history</summary><div id="history"></div></details>
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
(${ownerSettingsPanel.toString()})(document.getElementById('metadata'),base);
load().catch(e=>status.textContent=e.message);</script></html>`,{headers:{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'none'; script-src 'nonce-"+nonce+"'; style-src 'nonce-"+nonce+"'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"}});
}

/** Executed only in the authenticated owner's same-origin page. Its source is embedded under a fresh CSP nonce. */
export function ownerSettingsPanel(section,base,request=fetch) {
  const doc=section.ownerDocument;
  const element=(parent,tag,text)=>{
    const el=doc.createElement(tag);
    if(text!==undefined)el.textContent=text;
    parent.appendChild(el);
    return el;
  };
  async function read(path,init={}) {
    const response=await request(base+path,{credentials:'same-origin',cache:'no-store',...init});
    const result=await response.json();
    if(!response.ok)throw Error(result.error?.code||'Request could not be verified');
    return result;
  }
  async function display() {
    section.replaceChildren();
    const status=element(section,'p','Loading campaign settings…');
    status.setAttribute('role','status');
    try {
      const prior=await read('/settings');
      const old=prior.profile;
      if(prior.authority!=='cloud' || prior.subject!=='sites-owner')throw Error('Owner authentication required');
      status.textContent=old?'':'No campaign name or notes have been recorded.';
      const name=element(element(section,'label','Campaign name'),'input'); name.maxLength=180;name.value=old?.title??'';
      const notes=element(element(section,'label','Campaign notes'),'textarea');notes.maxLength=4000;notes.value=old?.data.notes??'';
      const unknown=element(element(section,'label','Notes unknown'),'input');unknown.type='checkbox';unknown.checked=old?.data.notes==null;
      notes.disabled=unknown.checked;
      const start=element(section,'button','Review settings');
      const review=element(section,'div');
      function invalidate(){review.replaceChildren();notes.disabled=unknown.checked;}
      name.oninput=invalidate;notes.oninput=invalidate;unknown.onchange=invalidate;
      start.onclick=()=>{
        const values={name:name.value,notes:unknown.checked?null:notes.value};
        invalidate();
        if(!values.name.trim()){status.textContent='Enter a campaign name.';return;}
        const command={version:1,commandId:crypto.randomUUID(),epoch:prior.epoch,expectedRevision:prior.revision,operation:'update_continuity_settings',args:values};
        element(review,'h3','Review campaign settings');
        for(const [label,value] of [['Current name',old?.title],['Proposed name',values.name],['Current notes',old?.data.notes],['Proposed notes',values.notes]]){
          element(review,'p',label);element(review,'pre',value==null?'Unknown':(value===''?'(empty)':value));
        }
        const save=element(review,'button','Save reviewed settings');
        save.onclick=async()=>{
          save.disabled=true;start.disabled=true;name.disabled=true;notes.disabled=true;unknown.disabled=true;
          try{
            const sent=await read('/commands',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(command)});
            const proof=await read('/receipts/'+encodeURIComponent(command.commandId));
            if(sent.status!=='COMMITTED'||proof.receipt?.commandId!==command.commandId||proof.receipt.status!=='COMMITTED'||proof.epoch!==prior.epoch)
              throw Error('Settings change has no confirmed receipt');
            const after=await read('/settings');
            if(after.epoch!==prior.epoch||after.revision<proof.revision||after.profile?.title!==values.name||after.profile?.data.notes!==values.notes)
              throw Error('Settings changed again. Review current values.');
            await display();
            section.querySelector('[role="status"]').textContent='Campaign settings saved at revision '+proof.revision+'.';
          }catch(error){
            status.textContent=error.message;
            save.disabled=false;save.textContent='Retry same reviewed request';
            if(!review.querySelector('[data-reload]')){
              const reload=element(review,'button','Reload campaign settings');
              reload.setAttribute('data-reload','1');reload.onclick=display;
            }
          }
        };
      };
    }catch(error){status.textContent=error.message;const retry=element(section,'button','Reload campaign settings');retry.onclick=display;}
  }
  return display();
}
