import { test } from 'node:test';
import assert from 'node:assert/strict';
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
  assert.equal(html.headers.get('referrer-policy'), 'no-referrer');
  assert.equal((await html.text()).includes(env.SITES_SERVICE_ACCESS_BEARER), false);
});
