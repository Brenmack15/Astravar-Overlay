/** The trusted Site owns web authentication. Never place record text in HTML/JS source. */
export function cloudInboxPage() {
  const nonce=crypto.randomUUID().replaceAll('-','');
  const html=`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Astravar · Owner review</title><style nonce="${nonce}">*{box-sizing:border-box}body{background:#101724;color:#f0e7d1;font:1rem/1.5 system-ui,sans-serif;max-width:50rem;margin:auto;padding:1.2rem}h1,h2{color:#eed18c}article{border:1px solid #586071;padding:1rem;margin:1rem 0;border-radius:.5rem}button{min-height:44px;margin-right:.6rem;padding:.55rem 1.1rem}img{max-width:100%;max-height:24rem}pre{white-space:pre-wrap;overflow-wrap:anywhere}summary{cursor:pointer}</style>
<h1>Owner review</h1><a id="manage">Campaign permissions</a><p id="status" role="status"></p><main id="inbox"></main>
<script nonce="${nonce}">
const main=document.getElementById('inbox'),status=document.getElementById('status');
document.getElementById('manage').href=location.pathname+'/access';
const base='/v3/continuities/'+encodeURIComponent(location.pathname.split('/').pop())+'/approvals';
const target=new URLSearchParams(location.hash.slice(1)),focused=location.hash.length>1;
async function exact(){
  const id=target.get('request'),digest=target.get('digest'),intent=target.get('intent');
  if([...target].length!==3 || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(id||'') || !/^[0-9a-f]{64}$/.test(digest||'') || !['verify','refuse'].includes(intent))throw Error('The exact review link is invalid.');
  const r=await fetch(base.replace(/approvals$/,'review-inbox/')+encodeURIComponent(id)+'?digest='+digest,{credentials:'same-origin',cache:'no-store'});
  if(!r.ok)throw Error('The reviewed request is unavailable in this owner account.');
  const req=await r.json();if(req.commandId!==id||req.digest!==digest)throw Error('The reviewed request identity changed.');
  if(req.status==='PENDING_APPROVAL'){
    render(req);status.textContent='Review the proposal, then confirm your decision.';
    const button=main.querySelector('[data-decision="'+intent+'"]');if(button&&!button.disabled)button.focus();
  }else{
    el(main,'h2',req.operation.replaceAll('_',' '));el(main,'p',req.status+' · revision '+req.receipt.revision);
    if(req.reason)el(main,'p',req.reason.replaceAll('_',' ').toLowerCase());
    status.textContent='The owner decision has already been recorded.';
  }
}
function el(parent,tag,t){const v=document.createElement(tag);if(t!==undefined)v.textContent=t;parent.appendChild(v);return v}
async function list(){main.replaceChildren();status.textContent='Loading requests…';let after='',count=0,total=0;try{if(focused){await exact();return}do{const r=await fetch(base+'?limit=25'+(after?'&after='+encodeURIComponent(after):''),{credentials:'same-origin',cache:'no-store'});if(!r.ok)throw Error('Owner authorization and a cloud continuity are required.');const page=await r.json();count=page.actionableCount;total=page.pendingCount;page.requests.forEach(render);after=page.nextAfter}while(after);status.textContent=total?count+' ready for approval · '+(total-count)+' require reconciliation':'No pending requests.'}catch(e){status.textContent=e.message}}
function render(req){const a=el(main,'article');el(a,'h2',req.command.operation.replaceAll('_',' '));const actions=el(a,'div');let verifyButton;const ready=req.readiness==='READY';const needsImage=ready&&req.command.operation==='assign_primary_image';
for(const [verb,label] of [['verify',ready?'Verify':'Resolve status'],['refuse','Refuse']]){const button=el(actions,'button',label);button.type='button';button.dataset.decision=verb;if(verb==='verify'){verifyButton=button;if(needsImage)button.disabled=true}button.onclick=async()=>{for(const child of actions.children)child.disabled=true;try{const r=await fetch(base+'/'+encodeURIComponent(req.commandId)+'/'+verb,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify({digest:req.digest})});const v=await r.json();if(!r.ok)throw Error(v.error?.code||'Decision unverified');await list();status.textContent='Decision: '+v.status+' · revision '+v.revision}catch(e){status.textContent=e.message;for(const child of actions.children)child.disabled=false;if(needsImage&&!a.querySelector('img')?.naturalWidth)verifyButton.disabled=true}}}
if(needsImage){const im=el(a,'img');im.alt='Exact proposed primary image for '+req.command.args.targetId;im.onload=()=>verifyButton.disabled=false;im.onerror=()=>{verifyButton.disabled=true;status.textContent='Candidate image unavailable for review.'};im.src=base.replace(/approvals$/,'media/uploads/')+encodeURIComponent(req.command.args.uploadId)+'/original';}
el(a,'p','Requested by '+req.subject+' · submitted '+new Date(req.submittedAt).toLocaleString());el(a,'p','Owner approval is required by the operation or its permission settings. Verified against revision '+req.command.expectedRevision+'.');
if(!ready)el(a,'p','Validation · '+req.readiness.replaceAll('_',' ').toLowerCase()+'. Resolve status records the current disposition.');if(!req.plan){el(a,'p','Original review evidence is unavailable.');verifyButton.disabled=true;return}for(const change of req.plan.changes){const target=change.after||change.before;el(a,'h3',target.title);el(a,'p',target.kind+' · '+target.id);const block=el(a,'div');el(block,'strong',req.current?'Current authoritative record':'Record at submission');el(block,'pre',JSON.stringify(req.current?(req.current[target.id]??null):change.before,null,2));el(block,'strong','Proposed record');el(block,'pre',JSON.stringify(change.after,null,2))}
el(a,'h3','Costs and outcome');el(a,'pre',JSON.stringify(req.plan.result,null,2));if(req.plan.edges.length){el(a,'h3','Affected relationships');el(a,'pre',JSON.stringify(req.plan.edges,null,2))}const detail=el(a,'details');el(detail,'summary','Source and validation details');el(detail,'pre',JSON.stringify({args:req.command.args,permissions:req.plan.access,epoch:req.command.epoch,digest:req.digest},null,2));}
list();</script></html>`;
  return new Response(html,{headers:{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'none'; script-src 'nonce-"+nonce+"'; style-src 'nonce-"+nonce+"'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"}});
}
