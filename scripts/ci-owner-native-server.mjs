/** Isolated, disposable native-HTTPS acceptance gateway. Never deployed.
 * This simulates the Sites identity boundary for fictional CI records only;
 * it is not actual ChatGPT discovery or a production authorization adapter.
 */
import https from 'node:https';
import dgram from 'node:dgram';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { timingSafeEqual } from 'node:crypto';
import worker from '../backend/owner-bridge/worker.mjs';
import { SqliteAdapter } from '../backend/owner-bridge/sqlite-adapter.mjs';

const directory = resolve(process.env.ASTRAVAR_CI_STATE_DIR || '');
if (!directory || !process.env.ASTRAVAR_CI_STATE_DIR || !process.argv.includes('--synthetic-only')) throw Error('Explicit private synthetic state directory required');
const origin = 'https://hp-bridge.example';
const config = JSON.parse(readFileSync(resolve(directory, 'server-config.json')));
if (config.origin !== origin || config.profileId !== 'hp-native-player' || !config.ownerId.startsWith('ci-fictional-')) throw Error('Only isolated fictional acceptance is permitted');
const db = new SqliteAdapter(resolve(directory, 'bridge.sqlite'));
db.exec(readFileSync(new URL('../backend/owner-bridge/schema.sql', import.meta.url), 'utf8'));
const env = { DB: db, OWNER_SITE_USER_ID: config.ownerId, SITES_SERVICE_ACCESS_BEARER: config.serviceBearer };
let offline = false;
let dropNextDecision = false;
const equal = (a, b) => typeof a === 'string' && typeof b === 'string' && Buffer.byteLength(a) === Buffer.byteLength(b) && timingSafeEqual(Buffer.from(a), Buffer.from(b));
const json = (value, status = 200) => Response.json(value, { status, headers: { 'Cache-Control': 'no-store' } });
async function body(request) {
  const chunks = []; let count = 0;
  for await (const chunk of request) { count += chunk.length; if (count > 65_536) throw Error('Bounded CI body required'); chunks.push(chunk); }
  return Buffer.concat(chunks);
}
async function dispatch(req, bytes) {
  const path = new URL(req.url, origin).pathname;
  if (path.startsWith('/__ci/')) {
    if (!equal(req.headers['x-astravar-ci-control'], config.controlToken)) return json({ error: 'CI_CONTROL_DENIED' }, 403);
    const input = bytes.length ? JSON.parse(bytes) : {};
    if (path === '/__ci/health') return json({ syntheticOnly: true, origin, profileId: config.profileId });
    if (path === '/__ci/fault') {
      if (typeof input.offline === 'boolean') offline = input.offline;
      if (input.dropNextDecision === true) dropNextDecision = true;
      return json({ offline, dropNextDecision });
    }
    if (path === '/__ci/expire') {
      if (typeof input.credentialId !== 'string') return json({ error: 'INVALID_TEST_CREDENTIAL' }, 400);
      await db.prepare('UPDATE bridge_credentials SET expires_at=0 WHERE id=? AND owner_id=?').bind(input.credentialId, config.ownerId).run();
      return json({ expired: true });
    }
    if (path === '/__ci/owner') {
      const allowed = input.path === '/mcp' || input.path === '/owner/bootstrap/bundle' ||
        input.path === '/v1/owner/pairings/approve' || input.path === '/v1/owner/devices' || input.path === '/v1/owner/devices/revoke';
      if (!allowed || !['GET', 'POST'].includes(input.method)) return json({ error: 'CI_ROUTE_DENIED' }, 403);
      const headers = { 'Content-Type': 'application/json', Origin: origin, 'oai-authenticated-user-id': config.ownerId };
      return worker.fetch(new Request(origin + input.path, { method: input.method, headers,
        ...(input.method === 'POST' ? { body: JSON.stringify(input.body) } : {}) }), env);
    }
    return json({ error: 'CI_ROUTE_DENIED' }, 404);
  }
  if (!equal(req.headers['oai-sites-authorization'], 'Bearer ' + config.serviceBearer)) return json({ error: 'CI_TRANSPORT_DENIED' }, 401);
  if (offline) return json({ mode: 'ASTRAVAR_OWNER_HP_V1', error: { code: 'SERVICE_UNAVAILABLE' } }, 503);
  const headers = new Headers();
  for (const [key, value] of Object.entries(req.headers)) {
    // Incoming identity and host headers never become trusted owner identity.
    if (value && !key.startsWith('oai-authenticated-') && !['host', 'connection', 'content-length'].includes(key)) headers.set(key, String(value));
  }
  const response = await worker.fetch(new Request(origin + req.url, { method: req.method, headers,
    ...(['GET', 'HEAD'].includes(req.method) ? {} : { body: bytes }) }), env);
  if (dropNextDecision && req.method === 'POST' && path.endsWith('/decisions') && response.ok) {
    dropNextDecision = false; req.socket.destroy(); return null;
  }
  return response;
}
const server = https.createServer({ key: readFileSync(resolve(directory, 'server.key')), cert: readFileSync(resolve(directory, 'server.crt')) }, async (req, res) => {
  const started = performance.now();
  try {
    const response = await dispatch(req, await body(req));
    if (!response) return;
    res.writeHead(response.status, Object.fromEntries(response.headers));
    res.end(Buffer.from(await response.arrayBuffer()));
    console.log(JSON.stringify({syntheticOnly:true,method:req.method,path:new URL(req.url,origin).pathname,status:response.status,elapsedMs:Number((performance.now()-started).toFixed(2))}));
  } catch (error) {
    // File/line frames are actionable; never log request headers, bodies or tokens.
    console.error(JSON.stringify({syntheticOnly:true,failure:'CI_GATEWAY_FAILURE',type:error?.name,frames:String(error?.stack??'').split('\n').slice(1,5)}));
    res.writeHead(500, { 'Content-Type': 'application/json' }); res.end('{"error":"CI_GATEWAY_FAILURE"}');
  }
});
let tlsReady = false;
let dnsReady = false;
function ready() {
  if (tlsReady && dnsReady) writeFileSync(resolve(directory, 'server-ready.json'), JSON.stringify({ ready: true, syntheticOnly: true, origin }));
}
server.listen(Number(process.env.ASTRAVAR_CI_PORT || 18443), '127.0.0.1', () => { tlsReady = true; ready(); });

// Emulator -dns-server forwards its DNS service to this loopback resolver.
// Only the reserved .example name resolves, to guest loopback used by adb reverse.
const dns = dgram.createSocket('udp4');
dns.on('message', (query, peer) => {
  if (query.length < 17 || query.readUInt16BE(4) !== 1) return;
  let at = 12; const labels = [];
  while (at < query.length && query[at] && query[at] < 64) { const n = query[at++]; labels.push(query.subarray(at, at + n).toString('ascii')); at += n; }
  if (at + 5 > query.length) return;
  at++; const type = query.readUInt16BE(at); const question = query.subarray(12, at + 4);
  const valid = ['hp-bridge.example', 'wrong-hp-bridge.example'].includes(labels.join('.').toLowerCase());
  const answer = valid && type === 1 ? Buffer.from([0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 127, 0, 0, 1]) : Buffer.alloc(0);
  const header = Buffer.alloc(12); query.copy(header, 0, 0, 2); header.writeUInt16BE(valid ? 0x8180 : 0x8183, 2); header.writeUInt16BE(1, 4); header.writeUInt16BE(answer.length ? 1 : 0, 6);
  dns.send(Buffer.concat([header, question, answer]), peer.port, peer.address);
});
dns.on('listening', () => { dnsReady = true; ready(); });
dns.bind(Number(process.env.ASTRAVAR_CI_DNS_PORT || 53), '127.0.0.1');
function close() { dns.close(); server.close(() => { db.close(); process.exit(0); }); }
process.on('SIGTERM', close); process.on('SIGINT', close);
