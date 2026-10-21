import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { encryptBootstrapBundle, bootstrapAad, handleBootstrap } from './bootstrap.mjs';

const origin = 'https://owner.example';
const campaignId = 'synthetic-owner-acceptance';
const nonce = 'N'.repeat(43);
const env = { OWNER_SITE_USER_ID: 'fictional-site-owner', SITES_SERVICE_ACCESS_BEARER: 'test-transport-only-'.repeat(4) };
const encode = value => Buffer.from(value).toString('base64url');
const decode = value => new Uint8Array(Buffer.from(value, 'base64url'));
async function keys() {
  const pair = await crypto.subtle.generateKey({ name: 'RSA-OAEP', hash: 'SHA-256', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]) }, true, ['encrypt', 'decrypt']);
  return { ...pair, spki: encode(await crypto.subtle.exportKey('spki', pair.publicKey)) };
}
function request(path, method = 'GET', body, headers = {}) {
  return new Request(origin + path, { method, headers: { 'oai-authenticated-user-id': env.OWNER_SITE_USER_ID,
    ...(body ? { 'Content-Type': 'application/json', Origin: origin } : {}), ...headers }, ...(body ? { body: JSON.stringify(body) } : {}) });
}
function formRequest(body, headers = {}, path = '/owner/bootstrap/download') {
  return new Request(origin + path, { method: 'POST', headers: {
    'oai-authenticated-user-id': env.OWNER_SITE_USER_ID, Origin: origin,
    'Content-Type': 'application/x-www-form-urlencoded', ...headers,
  }, body: typeof body === 'string' || body instanceof Uint8Array ? body : new URLSearchParams(body).toString() });
}
async function decrypt(pair, bundle) {
  const raw = await crypto.subtle.decrypt({ name: 'RSA-OAEP' }, pair.privateKey, decode(bundle.wrappedKey));
  const key = await crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['decrypt']);
  const bytes = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: decode(bundle.iv), additionalData: new TextEncoder().encode(bootstrapAad(bundle)), tagLength: 128 }, key, decode(bundle.ciphertext));
  return JSON.parse(new TextDecoder().decode(bytes));
}

test('bootstrap download is encrypted to the installation and contains no plaintext credential', async () => {
  const pair = await keys(), now = 100_000;
  const bundle = await encryptBootstrapBundle(pair.spki, { origin, campaignId, nonce, serviceAccessBearer: env.SITES_SERVICE_ACCESS_BEARER }, now);
  assert.equal(JSON.stringify(bundle).includes(env.SITES_SERVICE_ACCESS_BEARER), false);
  assert.equal(Object.hasOwn(bundle, 'serviceAccessBearer'), false);
  assert.equal(bundle.expiresAt, now + 600_000);
  assert.equal(bundle.algorithm, 'RSA-OAEP-256+A256GCM');
  const secret = await decrypt(pair, bundle);
  assert.equal(secret.serviceAccessBearer, env.SITES_SERVICE_ACCESS_BEARER);
  assert.equal(secret.nonce, nonce);
  assert.equal(secret.origin, origin);
});

test('bootstrap ciphertext cannot be decrypted by another installation or rebound to another scope', async () => {
  const pair = await keys(), different = await keys();
  const bundle = await encryptBootstrapBundle(pair.spki, { origin, campaignId, nonce, serviceAccessBearer: env.SITES_SERVICE_ACCESS_BEARER });
  await assert.rejects(decrypt(different, bundle));
  await assert.rejects(decrypt(pair, { ...bundle, origin: 'https://different.example' }));
  await assert.rejects(decrypt(pair, { ...bundle, nonce: 'D'.repeat(43) }));
  await assert.rejects(decrypt(pair, { ...bundle, expiresAt: bundle.expiresAt + 1 }));
});

test('bootstrap owner identity and same-origin explicit browser submission are mandatory', async () => {
  const pair = await keys();
  const body = { origin, campaignId, nonce, publicKeySpki: pair.spki };
  assert.equal((await handleBootstrap(request('/owner/bootstrap', 'GET', undefined, { 'oai-authenticated-user-id': 'viewer' }), env)).status, 401);
  assert.equal((await handleBootstrap(request('/owner/bootstrap/bundle', 'POST', body, { Origin: 'https://attacker.example' }), env)).status, 403);
  assert.equal((await handleBootstrap(request('/owner/bootstrap/bundle', 'POST', body), { ...env, OWNER_SITE_USER_ID: '' })).status, 401);
  assert.equal((await handleBootstrap(request('/owner/bootstrap/bundle', 'POST', body), { ...env, SITES_SERVICE_ACCESS_BEARER: '' })).status, 503);
  const response = await handleBootstrap(request('/owner/bootstrap/bundle', 'POST', body), env);
  assert.equal(response.status, 200);
  assert.equal((await decrypt(pair, await response.json())).serviceAccessBearer, env.SITES_SERVICE_ACCESS_BEARER);
});

test('bootstrap rejects live campaigns, malformed keys, foreign origins, extra fields and oversized input', async () => {
  const pair = await keys();
  const base = { origin, campaignId, nonce, publicKeySpki: pair.spki };
  for (const body of [{ ...base, campaignId: 'live-zerg' }, { ...base, nonce: 'bad' }, { ...base, origin: 'https://foreign.example' },
    { ...base, publicKeySpki: 'invalid' }, { ...base, serviceAccessBearer: 'caller-provided' }]) {
    assert.equal((await handleBootstrap(request('/owner/bootstrap/bundle', 'POST', body), env)).status, 400);
  }
  assert.equal((await handleBootstrap(request('/owner/bootstrap/bundle', 'POST', { ...base, publicKeySpki: 'x'.repeat(5000) }), env)).status, 413);
  const html = await handleBootstrap(request('/owner/bootstrap'), env);
  assert.equal(html.headers.get('cache-control'), 'no-store');
  assert.equal(html.headers.get('referrer-policy'), 'same-origin');
  assert.equal((await html.text()).includes(env.SITES_SERVICE_ACCESS_BEARER), false);
});

test('native form download returns only an attachment encrypted in the exact existing Android envelope', async () => {
  const pair = await keys(), different = await keys();
  const startedAt = Date.now();
  const response = await handleBootstrap(formRequest({ origin, campaignId, nonce, publicKeySpki: pair.spki }, {
    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
  }), env);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-disposition'), 'attachment; filename="Astravar-encrypted-authorization.json"');
  assert.equal(response.headers.get('content-type'), 'application/json; charset=utf-8');
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(response.headers.get('referrer-policy'), 'no-referrer');
  const text = await response.text();
  assert.equal(text.includes(env.SITES_SERVICE_ACCESS_BEARER), false);
  const bundle = JSON.parse(text);
  assert.deepEqual(Object.keys(bundle).sort(), ['v','algorithm','origin','campaignId','nonce','expiresAt','wrappedKey','iv','ciphertext'].sort());
  assert.equal(bundle.v, 1);
  assert.equal(bundle.algorithm, 'RSA-OAEP-256+A256GCM');
  assert.ok(bundle.expiresAt >= startedAt + 600_000 && bundle.expiresAt <= Date.now() + 600_000);
  const secret = await decrypt(pair, bundle);
  assert.deepEqual(Object.keys(secret).sort(), ['v','algorithm','origin','campaignId','nonce','expiresAt','serviceAccessBearer'].sort());
  for (const key of ['v','algorithm','origin','campaignId','nonce','expiresAt']) assert.equal(secret[key], bundle[key]);
  assert.equal(secret.serviceAccessBearer, env.SITES_SERVICE_ACCESS_BEARER);
  await assert.rejects(decrypt(different, bundle));
  for (const changed of [{ nonce: 'X'.repeat(43) }, { origin: 'https://foreign.example' }, { expiresAt: bundle.expiresAt + 1 }]) {
    await assert.rejects(decrypt(pair, { ...bundle, ...changed }));
  }
  const api = await handleBootstrap(request('/owner/bootstrap/bundle', 'POST', { origin, campaignId, nonce, publicKeySpki: pair.spki }), env);
  assert.equal(api.status, 200);
  assert.equal(api.headers.get('content-disposition'), null);
  assert.equal((await decrypt(pair, await api.json())).serviceAccessBearer, env.SITES_SERVICE_ACCESS_BEARER);
});

test('attachment download requires verified owner, HTTPS, strict same-origin form submission and provisioned transport', async () => {
  const pair = await keys(), body = { origin, campaignId, nonce, publicKeySpki: pair.spki };
  const cases = [
    [formRequest(body, { 'oai-authenticated-user-id': 'viewer' }), env, 401],
    [formRequest(body), { ...env, OWNER_SITE_USER_ID: '' }, 401],
    [new Request('http://owner.example/owner/bootstrap/download', { method: 'POST', headers: { 'oai-authenticated-user-id': env.OWNER_SITE_USER_ID, Origin: 'http://owner.example', 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(body).toString() }), env, 400],
    [formRequest(body, { Origin: 'https://attacker.example' }), env, 403],
    [formRequest(body, { Origin: 'null' }), env, 403],
    [new Request(origin + '/owner/bootstrap/download', { method: 'POST', headers: { 'oai-authenticated-user-id': env.OWNER_SITE_USER_ID, 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(body).toString() }), env, 403],
    [formRequest(body), { ...env, SITES_SERVICE_ACCESS_BEARER: '' }, 503],
    [request('/owner/bootstrap/download'), env, 404],
  ];
  for (const type of ['application/json','multipart/form-data; boundary=fixture','text/plain']) cases.push([formRequest(body, { 'Content-Type': type }), env, 403]);
  for (const [input, configuration, status] of cases) {
    const response = await handleBootstrap(input, configuration);
    assert.equal(response.status, status);
    assert.equal(response.headers.get('content-disposition'), null);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal((await response.text()).includes(env.SITES_SERVICE_ACCESS_BEARER), false);
  }
});

test('attachment form parser rejects duplicate, extra, missing, malformed and oversized public fields', async () => {
  const pair = await keys(), body = { origin, campaignId, nonce, publicKeySpki: pair.spki };
  const encoded = new URLSearchParams(body).toString();
  const missing = { ...body }; delete missing.nonce;
  const cases = [
    encoded + '&nonce=' + nonce,
    encoded + '&%6frigin=' + encodeURIComponent(origin),
    encoded + '&extra=caller-input',
    encoded + '&__proto__=caller-input',
    new URLSearchParams(missing).toString(),
    encoded.replace('nonce=' + nonce, 'nonce=%QQ'),
    new Uint8Array([0xc3, 0x28]),
    ...[{ ...body, campaignId: 'live-zerg' }, { ...body, nonce: 'bad' },
      { ...body, origin: 'https://foreign.example' }, { ...body, publicKeySpki: 'invalid' }]
      .map(value => new URLSearchParams(value).toString()),
  ];
  for (const value of cases) {
    const response = await handleBootstrap(formRequest(value), env);
    assert.equal(response.status, 400);
    assert.equal(response.headers.get('content-disposition'), null);
    assert.equal((await response.text()).includes(env.SITES_SERVICE_ACCESS_BEARER), false);
  }
  for (const input of [formRequest('x'.repeat(4097)), formRequest(encoded, { 'Content-Length': '4097' })]) {
    const response = await handleBootstrap(input, env);
    assert.equal(response.status, 413);
    assert.equal(response.headers.get('content-disposition'), null);
  }
});

test('bootstrap page submits a native same-origin form without Blob downloads or disabling retries', async () => {
  const pair = await keys();
  const response = await handleBootstrap(request('/owner/bootstrap'), env);
  const html = await response.text();
  assert.equal(response.headers.get('referrer-policy'), 'same-origin');
  assert.match(response.headers.get('content-security-policy'), /form-action 'self'/);
  assert.match(html, /<form[^>]*method="post"[^>]*action="\/owner\/bootstrap\/download"[^>]*enctype="application\/x-www-form-urlencoded"/);
  assert.deepEqual([...html.matchAll(/<input type="hidden" name="([^"]+)"/g)].map(match => match[1]).sort(), ['origin','campaignId','nonce','publicKeySpki'].sort());
  assert.doesNotMatch(html, /\bfetch\s*\(|\bBlob\s*\(|createObjectURL|revokeObjectURL|localStorage|sessionStorage/);
  assert.equal(html.includes(env.SITES_SERVICE_ACCESS_BEARER), false);
  const script = html.match(/<script nonce="[^"]+">([\s\S]*?)<\/script>/)[1];
  function executePage(fragment) {
    const inputs = Object.fromEntries(['origin','campaignId','nonce','publicKeySpki'].map(name => [name, { value: '' }]));
    const elements = { authorization: { elements: { namedItem: name => inputs[name] }, addEventListener: (name, callback) => { assert.equal(name, 'submit'); elements.authorization.submit = callback; } }, download: { disabled: true }, status: { textContent: '' }, code: { textContent: 'Invalid request' } };
    runInNewContext(script, { URLSearchParams, location: { origin, hash: '#' + fragment }, document: { getElementById: id => elements[id] } });
    return { elements, inputs };
  }
  const encoded = new URLSearchParams({ campaignId, nonce, publicKeySpki: pair.spki }).toString();
  const page = executePage(encoded);
  assert.equal(page.elements.download.disabled, false);
  assert.equal(page.inputs.origin.value, origin);
  assert.equal(page.inputs.publicKeySpki.value, pair.spki);
  assert.equal(page.elements.code.textContent, nonce.slice(0, 12));
  let prevented = false;
  page.elements.authorization.submit({ preventDefault: () => { prevented = true; } });
  assert.equal(prevented, false);
  assert.equal(page.elements.download.disabled, false);
  assert.match(page.elements.status.textContent, /cannot confirm that a file was saved/);
  page.elements.authorization.submit({ preventDefault: () => { prevented = true; } });
  assert.equal(prevented, false);
  for (const invalid of [encoded + '&nonce=' + nonce, encoded + '&extra=1', encoded.replace(nonce, 'bad'), encoded.replace(pair.spki, 'invalid%25key')]) {
    const page = executePage(invalid);
    assert.equal(page.elements.download.disabled, true);
    let prevented = false;
    page.elements.authorization.submit({ preventDefault: () => { prevented = true; } });
    assert.equal(prevented, true);
    assert.equal(page.inputs.origin.value, '');
  }
});
