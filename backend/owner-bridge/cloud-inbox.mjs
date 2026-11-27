/** This function is embedded verbatim in the owner page. All campaign text is added to the DOM as text. */
function presentCloudInbox() {
  const main = document.getElementById('inbox');
  const status = document.getElementById('status');
  const openedHash = location.hash;
  const continuity = encodeURIComponent(location.pathname.split('/').pop());
  const base = '/v3/continuities/' + continuity;
  const options = { credentials: 'same-origin', cache: 'no-store' };
  const validId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
  document.getElementById('manage').href = location.pathname + '/access';
  addEventListener('hashchange', () => {
    main.replaceChildren();
    location.reload();
  });

  function element(parent, tag, text) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    parent.appendChild(node);
    return node;
  }
  function label(value) {
    return value.replaceAll('_', ' ').replace(/([a-z])([A-Z])/g, '$1 $2');
  }
  function requester(subject) {
    if (subject === 'sites-mcp') return 'Authorized Dungeon Master';
    if (subject === 'sites-owner') return 'Campaign owner';
    return subject.startsWith('device:') ? 'Paired Android client' : subject;
  }
  function readiness(value) {
    const reasons = {
      READY: 'Ready for owner review',
      REVIEW_BASELINE_CHANGED: 'Campaign state changed since submission',
      SUBMITTER_GRANT_CHANGED: 'The requester’s permissions changed',
      SUBMITTER_GRANT_INACTIVE: 'The requester’s access expired or was revoked',
    };
    return reasons[value] || 'Validation requires review';
  }
  function exactHash(id, digest, intent) {
    if (!validId.test(id || '') || !/^[0-9a-f]{64}$/.test(digest || '') || !['verify', 'refuse', 'replace'].includes(intent))
      throw Error('The exact review link is invalid.');
    return '#' + new URLSearchParams({request: id, digest, intent}).toString();
  }
  function display(value) {
    if (value === undefined) return 'Absent';
    if (value === null) return 'Unknown';
    return typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
  }
  function fields(parent, path, before, current, after) {
    if (JSON.stringify(before) === JSON.stringify(current) && JSON.stringify(current) === JSON.stringify(after)) return;
    const objects = [before, current, after].filter(value => value !== undefined);
    if (objects.length && objects.every(value => value && typeof value === 'object' && !Array.isArray(value))) {
      const keys = new Set(objects.flatMap(value => Object.keys(value)));
      if (keys.size) {
        for (const key of [...keys].sort()) {
          const child = object => object && Object.hasOwn(object, key) ? object[key] : undefined;
          fields(parent, [...path, key], child(before), child(current), child(after));
        }
        return;
      }
    }
    element(parent, 'h4', path.filter((part, i) => i !== 0 || part !== 'data').map(label).join(' · ') || 'Record');
    const changes = element(parent, 'dl');
    function row(name, value) {
      element(changes, 'dt', name);
      element(changes, 'dd', display(value));
    }
    if (JSON.stringify(before) !== JSON.stringify(current)) row('At submission', before);
    row('Current', current);
    row('Proposed', after);
  }
  function metadata(parent, req) {
    element(parent, 'p', requester(req.subject) + ' · ' + new Date(req.submittedAt).toLocaleString());
  }
  async function page(after) {
    const params = new URLSearchParams({limit: '25'});
    if (after) params.set('after', after);
    const response = await fetch(base + '/review-inbox?' + params, options);
    if (!response.ok) throw Error('Owner authorization and a cloud continuity are required.');
    const data = await response.json();
    for (const req of data.requests) {
      const article = element(main, 'article');
      element(article, 'h2', label(req.operation));
      metadata(article, req);
      element(article, 'p', readiness(req.readiness));
      const link = element(article, 'a', 'Open request');
      link.setAttribute('aria-label', 'Open request for ' + label(req.operation));
      link.href = location.pathname + exactHash(req.commandId, req.digest, 'verify');
    }
    status.textContent = data.pendingCount
      ? data.actionableCount + ' ready for approval · ' + (data.pendingCount - data.actionableCount) + ' require reconciliation'
      : 'No pending requests.';
    if (data.nextAfter) {
      const more = element(main, 'button', 'More requests');
      more.type = 'button';
      more.onclick = async () => {
        more.disabled = true;
        try { await page(data.nextAfter); more.remove(); }
        catch (error) { status.textContent = error.message; more.disabled = false; }
      };
    }
  }
  async function renderPending(req, intent) {
    const article = element(main, 'article');
    element(article, 'h2', label(req.operation));
    const actions = element(article, 'div');
    const ready = req.readiness === 'READY';
    const needsImage = ready && req.operation === 'assign_primary_image';
    let imageLoaded = !needsImage;
    let deciding = false;
    const buttons = {};
    const updateButtons = disabled => {
      deciding = disabled;
      buttons.verify.disabled = disabled || !imageLoaded;
      buttons.refuse.disabled = disabled;
    };
    for (const verb of ['verify', 'refuse']) {
      const title = verb === 'refuse' ? 'Refuse' : (ready ? 'Verify' : 'Resolve status');
      const button = element(actions, 'button', title);
      buttons[verb] = button;
      button.type = 'button';
      button.dataset.decision = verb;
      button.onclick = async () => {
        if (deciding || openedHash !== location.hash || (verb === 'verify' && !imageLoaded)) return;
        updateButtons(true);
        try {
          const result = await fetch(base + '/approvals/' + encodeURIComponent(req.commandId) + '/' + verb, {
            ...options, method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({digest: req.digest}),
          });
          const reply = await result.json();
          if (!result.ok) throw Error(reply.error?.code || 'Decision unverified');
          await exact();
          status.textContent = 'Decision recorded · ' + reply.status + ' · revision ' + reply.revision;
        } catch (error) {
          status.textContent = error.message;
          updateButtons(false);
        }
      };
    }
    updateButtons(false);
    metadata(article, req);
    element(article, 'p', readiness(req.readiness));
    element(article, 'p', req.reviewReason === 'OWNER_CONFIRMATION_REQUIRED'
      ? 'This operation requires explicit owner confirmation.'
      : 'The requester’s Auto-Sync settings require review for this change.');
    element(article, 'p', 'Submitted at revision ' + req.command.expectedRevision + ' · current revision ' + req.revision);
    if (needsImage) {
      const candidate = element(article, 'img');
      candidate.alt = 'Exact proposed primary image for ' + req.command.args.targetId;
      candidate.onload = () => { imageLoaded = true; buttons.verify.disabled = deciding; };
      candidate.onerror = () => { imageLoaded = false; buttons.verify.disabled = true; status.textContent = 'Candidate image unavailable for review.'; };
      candidate.src = base + '/media/uploads/' + encodeURIComponent(req.command.args.uploadId) + '/original';
    }
    for (const change of req.plan.changes) {
      const target = change.after || change.before;
      element(article, 'h3', target.title);
      element(article, 'p', label(target.kind));
      if (!change.after) element(article, 'p', 'Proposed permanent deletion');
      const current = Object.hasOwn(req.current, target.id) ? req.current[target.id] : undefined;
      fields(article, [], change.before ?? undefined, current, change.after ?? undefined);
    }
    element(article, 'h3', 'Costs and outcome');
    element(article, 'pre', JSON.stringify(req.plan.result, null, 2));
    if (req.plan.edges.length) {
      element(article, 'h3', 'Affected relationships');
      element(article, 'pre', JSON.stringify(req.plan.edges, null, 2));
    }
    const detail = element(article, 'details');
    element(detail, 'summary', 'Source and request details');
    element(detail, 'pre', JSON.stringify({
      command: req.command, digest: req.digest, requester: req.subject, permissions: req.plan.access, changes: req.plan.changes,
    }, null, 2));
    if (buttons[intent] && !buttons[intent].disabled) buttons[intent].focus();
  }
  async function exact() {
    const params = new URLSearchParams(openedHash.slice(1));
    const id = params.get('request'), digest = params.get('digest'), intent = params.get('intent');
    if ([...params].length !== 3) throw Error('The exact review link is invalid.');
    exactHash(id, digest, intent);
    const response = await fetch(base + '/review-inbox/' + encodeURIComponent(id) + '?digest=' + digest, options);
    if (!response.ok) throw Error('The reviewed request is unavailable in this owner account.');
    const req = await response.json();
    if (req.commandId !== id || req.digest !== digest) throw Error('The reviewed request identity changed.');
    main.replaceChildren();
    if (req.status === 'PENDING_APPROVAL') {
      status.textContent = 'Review the proposal, then confirm your decision.';
      await renderPending(req, intent);
    } else {
      element(main, 'h2', label(req.operation));
      element(main, 'p', req.status + ' · revision ' + req.receipt.revision);
      if (req.reason) element(main, 'p', label(req.reason).toLowerCase());
      status.textContent = 'The owner decision has already been recorded.';
      if (req.canReviewReplacement && req.originalCommand) {
        if (req.replacement) {
          element(main, 'a', 'Open linked replacement').href = location.pathname + exactHash(req.replacement.commandId, req.replacement.digest, 'verify');
        } else {
          element(main, 'p', 'The original request ended at revision ' + req.receipt.revision + '. Review a new proposal against revision ' + req.revision + '.');
          element(main, 'pre', JSON.stringify(req.originalCommand.args, null, 2));
          const generate = element(main, 'button', 'Prepare linked replacement');
          const envelope = {version:1,commandId:crypto.randomUUID(),epoch:req.epoch,expectedRevision:req.revision,
            operation:req.operation,args:req.originalCommand.args,replacesCommandId:req.commandId,replacesDigest:req.digest};
          generate.type = 'button';
          generate.onclick = async () => {
            if(generate.disabled || openedHash!==location.hash)return;
            generate.disabled = true;
            try {
              const response = await fetch(base+'/commands',{...options,method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(envelope)});
              const next = await response.json();
              if (!response.ok || next.commandId!==envelope.commandId || next.status!=='PENDING_APPROVAL')
                throw Error(next.error?.code || 'Replacement could not be verified.');
              location.hash = exactHash(next.commandId,next.digest,'verify');
            } catch(error) {status.textContent=error.message;generate.disabled=false;}
          };
        }
      }
    }
    element(main, 'a', 'All owner requests').href = location.pathname;
  }
  status.textContent = 'Loading requests…';
  (openedHash ? exact() : page()).catch(error => { status.textContent = error.message; });
}

export function cloudInboxPage() {
  const nonce = crypto.randomUUID().replaceAll('-', '');
  const html = `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Astravar · Owner review</title><style nonce="${nonce}">
*{box-sizing:border-box}body{background:#101724;color:#f0e7d1;font:1rem/1.5 system-ui,sans-serif;max-width:50rem;margin:auto;padding:1.2rem}
h1,h2,a{color:#eed18c}h4{margin-bottom:.3rem}article{border:1px solid #586071;padding:1rem;margin:1rem 0;border-radius:.5rem}
button{min-height:44px;margin-right:.6rem;padding:.55rem 1.1rem}a{display:inline-block;padding:.5rem 0}img{max-width:100%;max-height:24rem}
pre,dd{white-space:pre-wrap;overflow-wrap:anywhere}dl{display:grid;grid-template-columns:minmax(6rem,30%) 1fr;gap:.2rem .8rem;margin:.2rem 0 .6rem}
dt{color:#d7c7a4}dd{margin:0}summary{cursor:pointer}</style>
<h1>Owner review</h1><a id="manage">Campaign permissions</a><p id="status" role="status"></p><main id="inbox"></main>
<script nonce="${nonce}">(${presentCloudInbox.toString()})();</script></html>`;
  return new Response(html, { headers: {
    'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'none'; script-src 'nonce-" + nonce + "'; style-src 'nonce-" + nonce +
      "'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
  }});
}
