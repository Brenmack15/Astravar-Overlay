// Synthetic stdio transport for the native client contract test. No socket or production credential is used.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createInterface} from 'node:readline';
import {handleWorker} from '../worker.mjs';
import {SqliteAdapter} from '../sqlite-adapter.mjs';
import {sha256} from '../core.mjs';

const db = new SqliteAdapter();
db.exec(readFileSync(new URL('../schema.sql', import.meta.url), 'utf8'));
const origin = 'https://native-wire.example.test';
const continuity = 'native-fixture';
const owner = 'native-test-owner';
const device = 'native-device';
const bearer = crypto.randomUUID().replaceAll('-', '') + crypto.randomUUID().replaceAll('-', '');
const expiresAt = Date.now() + 3_600_000;
const path = '/v3/continuities/' + continuity;
const env = {DB: db, OWNER_SITE_USER_ID: owner, ASTRAVAR_CLOUD_ENABLED: '1', ASTRAVAR_CLOUD_ENV: 'isolated'};

async function request(route, body, headers) {
    const response = await handleWorker(new Request(origin + route, {
        method: body == null ? 'GET' : 'POST',
        headers: {'Content-Type': 'application/json', ...headers},
        ...(body == null ? {} : {body: JSON.stringify(body)})
    }), env);
    return {status: response.status, json: await response.json()};
}
const ownerHeaders = {'oai-authenticated-user-id': owner, Origin: origin};
async function setup(route, body) {
    const response = await request(route, body, ownerHeaders);
    assert.equal(response.status, 200);
    return response.json;
}
async function grant(subject, revision, revoked = false) {
    return setup(path + '/grants/' + subject, {
        scopes: ['character.read','character.write','combat.read','combat.write','rolls.read','rolls.write'],
        actors: ['*'], auto: ['character.write','combat.write','rolls.write'],
        expiresAt, expectedGrantRevision: revision, revoked
    });
}
await setup(path + '/isolated', {});
await grant('sites-mcp', 0);
await db.prepare("INSERT INTO bridge_credentials(id,owner_id,campaign_id,token_hash,role,scopes_json,device_name,expires_at) VALUES(?,?,?,?,'device','[]','Native fixture',?)")
    .bind(device, owner, continuity, await sha256(bearer), expiresAt).run();
await grant('device:' + device, 0);
const emit = value => process.stdout.write(JSON.stringify(value) + '\n');
emit({origin, continuity, device, bearer, expiresAt});
try {
    for await (const line of createInterface({input: process.stdin})) {
        try {
            const {call, route, body, credentialId, token, name, args} = JSON.parse(line);
            if (call === 'request') {
                assert.ok(route.startsWith(path+'/'));
                emit(await request(route, body, {Authorization:'Bearer '+token,'X-Astravar-Credential':credentialId}));
            } else if (call === 'dm') {
                const result = await setup('/mcp', {jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId:continuity,...args}}});
                assert.ok(result.result && !result.result.isError);
                emit(result.result.structuredContent);
            } else if (call === 'revoke') emit(await grant('device:'+device, 1, true));
            else throw new Error('Unknown control');
        } catch (error) { emit({fixtureError: error.name + ' in native test driver'}); }
    }
} finally { db.close(); }
