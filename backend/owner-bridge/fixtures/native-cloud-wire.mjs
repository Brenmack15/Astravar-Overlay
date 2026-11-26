// Synthetic stdio transport for the native client contract test. No socket or production credential is used.
import assert from 'node:assert/strict';
import {lstatSync,readFileSync,realpathSync} from 'node:fs';
import {join} from 'node:path';
import {createInterface} from 'node:readline';
import {handleWorker} from '../worker.mjs';
import {SqliteAdapter} from '../sqlite-adapter.mjs';
import {fileBucket} from './media-bucket.mjs';
import {sha256} from '../core.mjs';
import {CLOUD_SCOPES} from '../cloud-validation.mjs';

const origin = 'https://native-wire.example.test';
const continuity = 'qa-native-fixture';
const owner = 'native-test-owner';
function openFixture() {
    if (process.argv[2] !== '--reviewed-legacy') {
        const memory = new SqliteAdapter();
        memory.exec(readFileSync(new URL('../schema.sql', import.meta.url), 'utf8'));
        return memory;
    }
    const dir = realpathSync(process.argv[3]);
    const marker = JSON.parse(readFileSync(join(dir,'rehearsal.json'),'utf8'));
    assert.equal(marker.format,'astravar-isolated-migration-v1');
    assert.deepEqual([marker.owner,marker.continuity],[owner,continuity]);
    assert.match(marker.current,/^[0-9a-f]{32}\.sqlite$/);
    const file = join(dir,marker.current);
    assert.ok(lstatSync(file).isFile());
    const db = new SqliteAdapter(file);
    const state = db.db.prepare('SELECT authority,epoch,revision FROM cloud_continuities WHERE owner_id=? AND continuity_id=?').get(owner,continuity);
    assert.deepEqual([state.authority,state.epoch,state.revision],['cloud',2,0]);
    const actor = db.db.prepare("SELECT id FROM cloud_entities WHERE owner_id=? AND continuity_id=? AND kind='actor'").all(owner,continuity);
    assert.deepEqual(actor.map(r=>r.id),['recovery-actor']);
    assert.equal(db.db.prepare('SELECT count(*) n FROM cloud_receipts').get().n,0);
    return db;
}
const db = openFixture();
const device = 'native-device';
const bearer = crypto.randomUUID().replaceAll('-', '') + crypto.randomUUID().replaceAll('-', '');
const ratePaging = process.argv.includes('--rate-pagination');
const artLifecycle = process.argv.includes('--lifecycle-media');
const gameplayCategories = process.argv.includes('--exercise-gameplay')
    ? CLOUD_SCOPES.filter(scope => scope !== 'campaign')
    : ['character','resources','inventory','economy','combat','chronicle','rolls',...(artLifecycle ? ['media','lifecycle'] : [])];
const recordedDeviceCommands = new Map();
let fixtureClock = Date.now();
let successfulPages = 0, limitedPages = 0;
// This switch exists only in the standalone synthetic stdio process.
if (ratePaging) Date.now = () => fixtureClock;
const cleanup = [];
const media = (artLifecycle || process.argv.includes('--exercise-gameplay')) ? await fileBucket({after: handler=>cleanup.push(handler)}) : undefined;
const expiresAt = Date.now() + 3_600_000;
const path = '/v3/continuities/' + continuity;
const env = {DB: db, ASTRAVAR_MEDIA:media, OWNER_SITE_USER_ID: owner, ASTRAVAR_CLOUD_ENABLED: '1', ASTRAVAR_CLOUD_ENV: 'isolated',
    ...(ratePaging ? {ASTRAVAR_CLOUD_RATE_CAPACITY:'120', ASTRAVAR_CLOUD_RATE_REFILL_MS:'500'} : {})};

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
async function grant(subject, revision, revoked = false, automatic = true, reviewedCategories = [], actors = ['*']) {
    return setup(path + '/grants/' + subject, {
        scopes: gameplayCategories.flatMap(k=>[k+'.read',k+'.write']).concat(subject.startsWith('device:')?['approval.read']:[]),
        actors, auto: automatic?gameplayCategories.filter(k=>!reviewedCategories.includes(k)).map(k=>k+'.write'):[],
        expiresAt, expectedGrantRevision: revision, revoked
    });
}
if (process.argv[2] !== '--reviewed-legacy') await setup(path + '/isolated', {});
await grant('sites-mcp', 0);
await db.prepare("INSERT INTO bridge_credentials(id,owner_id,campaign_id,token_hash,role,scopes_json,device_name,expires_at) VALUES(?,?,?,?,'device','[]','Native fixture',?)")
    .bind(device, owner, continuity, await sha256(bearer), expiresAt).run();
await grant('device:' + device, 0);
if (ratePaging) {
    await db.prepare(`WITH RECURSIVE fixture(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM fixture WHERE n<2450)
      INSERT INTO cloud_entities(owner_id,continuity_id,id,kind,actor_id,title,data_json,revision)
      SELECT ?,?,printf('page-%04d',n),'actor',printf('page-%04d',n),'Pagination fixture','{"role":"companion"}',1 FROM fixture`)
      .bind(owner,continuity).run();
    await db.prepare('UPDATE cloud_continuities SET revision=1 WHERE owner_id=? AND continuity_id=?').bind(owner,continuity).run();
}
const emit = value => process.stdout.write(JSON.stringify(value) + '\n');
emit({origin, continuity, device, bearer, expiresAt});
try {
    for await (const line of createInterface({input: process.stdin})) {
        try {
            const {call, route, body, credentialId, token, name, args} = JSON.parse(line);
            if (call === 'request') {
                assert.ok(route.startsWith(path+'/'));
                if(process.argv.includes('--exercise-gameplay') && route===path+'/commands' && credentialId===device && token===bearer)
                    recordedDeviceCommands.set(body.commandId,structuredClone(body));
                const out = await request(route, body, {Authorization:'Bearer '+token,'X-Astravar-Credential':credentialId});
                if (ratePaging && route.startsWith(path+'/state?')) {
                    if(out.status===200) successfulPages++;
                    if(out.status===429) limitedPages++;
                }
                emit(out);
            } else if (call === 'media' && media) {
                assert.ok(route.startsWith(path+'/media/'));
                const headers = {Authorization:'Bearer '+token,'X-Astravar-Credential':credentialId};
                const bytes = args.bytes ? Buffer.from(args.bytes,'base64') : null;
                if(bytes) Object.assign(headers,{'Content-Type':'image/png','X-Astravar-Content-SHA256':args.sha256});
                const response = await handleWorker(new Request(origin+route,{headers,method:bytes?'PUT':'GET',body:bytes}),env);
                emit({status:response.status,contentType:response.headers.get('content-type'),
                    sha256:response.headers.get('x-astravar-content-sha256'),bytes:Buffer.from(await response.arrayBuffer()).toString('base64')});
            } else if (call === 'dm') {
                const result = await setup('/mcp', {jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId:continuity,...args}}});
                if (result.result?.isError) {
                    const code=JSON.parse(result.result.content[0].text).error;
                    assert.match(code,/^[A-Z0-9_]{1,64}$/);
                    throw Object.assign(new Error(),{fixtureCode:code});
                }
                assert.ok(result.result);
                emit(result.result.structuredContent);
            } else if (call === 'requireReview') emit(await grant('sites-mcp',1,false,false));
            else if (call === 'restrictWorldReader' && process.argv.includes('--exercise-gameplay')) {
                emit(await setup(path+'/grants/sites-mcp',{scopes:['world.read','character.read','chronicle.read','media.read'],
                    actors:['companion'],auto:[],expiresAt,expectedGrantRevision:1,revoked:false}));
            }
            else if (call === 'deviceTradeReview' && process.argv.includes('--exercise-gameplay')) emit(await grant('device:'+device,1,false,true,['economy']));
            else if (call === 'scopeEncounterAudience' && process.argv.includes('--exercise-gameplay')) {
                // Establish the narrow player audience before storing any owner-only fixture actor.
                for (const subject of ['sites-mcp','device:'+device]) await grant(subject,1,false,true,[],['engineer','scout']);
                async function privateCommand(operation,args) {
                    const {json:state}=await request(path+'/state?limit=1',null,ownerHeaders);
                    const result=await setup(path+'/commands',{version:1,epoch:state.epoch,expectedRevision:state.revision,
                        commandId:'owner-restricted-'+operation,operation,args});
                    assert.equal(result.status,'COMMITTED');
                }
                const actorId='unrevealed-witness',rollId='private-initiative';
                await privateCommand('define_actor',{actorId,name:'Unrevealed fixture witness',role:'creature',
                    rulesProfile:'revised_2024',level:1,abilities:{STR:10,DEX:12,CON:10,INT:10,WIS:10,CHA:10},source:'Isolated owner-only record'});
                await privateCommand('record_check',{actorId,rollId,check:{kind:'initiative',key:'initiative',mode:'normal'},evidence:{origin:'dm',faces:[9]}});
                await privateCommand('start_encounter',{encounterId:'restricted-watch',name:'Unrevealed encounter',
                    participants:[{actorId,initiativeRollId:rollId}]});
                emit({restricted:true});
            }
            else if (call === 'observedDeviceCommand' && process.argv.includes('--exercise-gameplay')) {
                assert.match(args.operation,/^[a-z_]{1,80}$/);
                const observed=[...recordedDeviceCommands.values()].filter(c=>c.operation===args.operation).at(-1);
                assert.ok(observed); emit(observed);
            }
            else if (call === 'redeliverDeviceCommand' && process.argv.includes('--exercise-gameplay')) {
                const command=recordedDeviceCommands.get(args.commandId);assert.ok(command);
                const response=await request(path+'/commands',command,{Authorization:'Bearer '+bearer,'X-Astravar-Credential':device});
                assert.equal(response.status,200);emit(response.json);
            } else if (call === 'ownerVerify' || (artLifecycle && call === 'ownerRefuse')) {
                assert.match(args.commandId,/^[A-Za-z0-9-]+$/);
                const disposition=call==='ownerVerify'?'verify':'refuse';
                emit(await setup(path+'/approvals/'+args.commandId+'/'+disposition,{digest:args.digest}));
            } else if (artLifecycle && call === 'sample') {
                const {encodeMediaPng}=await import('../cloud-media-codec.mjs');
                assert.ok([20,180].includes(args.shade));
                const raw=await encodeMediaPng(2,1,new Uint8Array([args.shade,12,8,255,7,args.shade,33,255]));
                emit({bytes:Buffer.from(raw).toString('base64')});
            } else if (artLifecycle && call === 'ownerPreview') {
                assert.match(args.uploadId,/^[A-Za-z0-9_-]+$/);
                const response=await handleWorker(new Request(origin+path+'/media/uploads/'+args.uploadId+'/original',{headers:ownerHeaders}),env);
                const bytes=await response.arrayBuffer();
                emit({status:response.status,bytes:Buffer.from(bytes).toString('base64')});
            } else if (artLifecycle && call === 'sharedFixture') {
                // Reconstruct a declared shared reference from an isolated pre-existing dependency.
                await db.prepare("INSERT INTO cloud_links(owner_id,continuity_id,from_id,relation,to_id) VALUES(?,?,'survivor','primary_image','first-asset')")
                    .bind(owner,continuity).run();
                await db.prepare("INSERT INTO cloud_links(owner_id,continuity_id,from_id,relation,to_id) VALUES(?,?,'detach-target','primary_image','first-asset')")
                    .bind(owner,continuity).run();
                emit({fixtureReady:true});
            } else if (artLifecycle && call === 'mediaProof') {
                emit({objects:env.ASTRAVAR_MEDIA.keys.size,active:db.db.prepare('SELECT id FROM cloud_entities ORDER BY id').all().map(v=>v.id),
                    tombstones:db.db.prepare('SELECT id FROM cloud_tombstones ORDER BY id').all().map(v=>v.id),
                    staged:db.db.prepare('SELECT upload_id FROM cloud_media_uploads ORDER BY upload_id').all().map(v=>v.upload_id)});
            } else if (ratePaging && call === 'advanceRateTime') {
                assert.ok(Number.isSafeInteger(args.milliseconds) && args.milliseconds>=1 && args.milliseconds<=60000);
                fixtureClock += args.milliseconds;
                emit({now:fixtureClock,advancedMs:args.milliseconds});
            } else if (ratePaging && call === 'rateMetrics') emit({successfulPages,limitedPages,seededRecords:2450});
            else if (call === 'revoke') emit(await grant('device:'+device, 1, true));
            else throw new Error('Unknown control');
        } catch (error) { emit({fixtureError: error.fixtureCode ?? error.name + ' in native test driver'}); }
    }
} finally { db.close(); for(const handler of cleanup) await handler(); }
