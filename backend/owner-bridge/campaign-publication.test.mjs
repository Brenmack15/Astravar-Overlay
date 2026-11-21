import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {OwnerBridge,sha256,canonical} from './core.mjs';
import {CampaignBridge,CAMPAIGN_SCOPES} from './campaign.mjs';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {handleWorker} from './worker.mjs';
const campaign='publication-fixture',ownerId='publication-owner';
const bytes=value=>Buffer.byteLength(canonical(value),'utf8');
const checkpoint=(id,description='Synthetic history',revision=1)=>({kind:'checkpoint',recordId:id,revision,
 fields:{id,campaignId:campaign,title:'Synthetic checkpoint',scene:description,revision,source:'Synthetic fixture',updatedAt:1}});
async function setup(t,readScopes=CAMPAIGN_SCOPES){
 const db=new SqliteAdapter();db.exec(await readFile(new URL('./schema.sql',import.meta.url),'utf8'));t.after(()=>db.close());
 let at=Date.now();const now=()=>++at,bridge=new OwnerBridge(db,{ownerId,now}),sync=new CampaignBridge(db,{ownerId,now});
 const secret='s'.repeat(60),pair=await bridge.requestPairing({campaignId:campaign,profileId:'synthetic-player',deviceName:'Synthetic publication test',deviceSecretHash:await sha256(secret),campaignAccess:{readScopes,writeScopes:[]}});
 await bridge.approvePairing({ownerId,role:'owner',scopes:[]},{userCode:pair.userCode});
 const token=await bridge.pollPairing({deviceCode:pair.deviceCode,deviceSecret:secret}),principal=await bridge.authenticate(token.credentialId,secret);
 const env={DB:db,OWNER_SITE_USER_ID:ownerId};
 const request=async(path,body)=>{const response=await handleWorker(new Request('https://publication.example'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+secret,'X-Astravar-Credential':token.credentialId},...(body===undefined?{}:{body:typeof body==='string'?body:JSON.stringify(body)})}),env);return {status:response.status,data:await response.json()};};
 return{db,sync,principal,request,publish:records=>request('/v2/campaigns/'+campaign+'/records',{commandId:crypto.randomUUID(),records}),connector:{ownerId,role:'connector',scopes:[]}};
}
function sizedRecord(size,unit='a'){
 const result=checkpoint('sized','');const target=size-bytes(result),unitBytes=Buffer.byteLength(JSON.stringify(unit))-2;
 result.fields.scene=unit.repeat(Math.floor(target/unitBytes))+'a'.repeat(target%unitBytes);
 assert.equal(bytes(result),size);return result;
}
for(const unit of ['a','😀','"']) test('whole canonical UTF-8 record bound includes '+JSON.stringify(unit),async t=>{
 const f=await setup(t);assert.equal((await f.publish([sizedRecord(48000,unit)])).status,200);
 const rejected=await f.publish([sizedRecord(48001,unit)]);assert.equal(rejected.status,400);assert.equal(rejected.data.error.code,'INVALID_RECORD_FIELDS');
});
test('17 actual Worker publications have exact immutable acknowledgement and independent current readback',async t=>{
 const f=await setup(t),records=Array.from({length:17},(_,i)=>checkpoint('checkpoint-'+String(i).padStart(2,'0')));
 const body={commandId:crypto.randomUUID(),records};const first=await f.request('/v2/campaigns/'+campaign+'/records',body);assert.equal(first.status,200);assert.equal(first.data.published.length,17);
 const retry=await f.request('/v2/campaigns/'+campaign+'/records',body);assert.equal(retry.status,200);assert.equal(retry.data.publishedAt,first.data.publishedAt);assert.deepEqual(retry.data.published,first.data.published);
 const readback=await f.request('/v2/campaigns/'+campaign+'/publications/'+body.commandId);assert.equal(readback.status,200);assert.equal(readback.data.source,'INDEPENDENT_CURRENT_ANDROID_MIRROR_READBACK');assert.deepEqual(readback.data.records,records);
 const client=await f.sync.read(f.connector,campaign,{kinds:['checkpoint'],limit:100});assert.equal(client.records.length,17);
 assert.equal((await f.db.prepare('SELECT COUNT(*) AS n FROM bridge_campaign_publications').bind().first()).n,1);
 assert.throws(()=>f.db.exec('DELETE FROM bridge_campaign_publications'),/IMMUTABLE/);
});
test('partial batch failure preserves earlier committed batch and rolls back every failed-batch record',async t=>{
 const f=await setup(t);assert.equal((await f.publish([checkpoint('existing','revision2',2)])).status,200);
 const failed=await f.publish([checkpoint('new-record'),checkpoint('existing','stale',1)]);assert.equal(failed.status,409);
 const rows=(await f.sync.read(f.connector,campaign,{kinds:['checkpoint']})).records;assert.equal(rows.length,1);assert.equal(rows[0].revision,2);
});
test('publication remains constrained by grant and campaign, with unchanged HTTP body bounds',async t=>{
 const f=await setup(t,['inventory']);assert.equal((await f.publish([checkpoint('denied')])).status,403);
 const full=await setup(t);assert.equal((await full.publish([{...checkpoint('wrong'),fields:{...checkpoint('wrong').fields,campaignId:'other'}}])).status,403);
 const body=JSON.stringify({commandId:crypto.randomUUID(),records:[checkpoint('bounded')]});
 assert.equal((await full.request('/v2/campaigns/'+campaign+'/records',body+' '.repeat(65536-Buffer.byteLength(body)))).status,200);
 assert.equal((await full.request('/v2/campaigns/'+campaign+'/records',body+' '.repeat(65537-Buffer.byteLength(body)))).status,413);
});
