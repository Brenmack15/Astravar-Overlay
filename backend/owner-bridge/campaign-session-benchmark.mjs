/** Reproducible, isolated local comparison. This never calls a hosted service or Android. */
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdtemp,rm} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {performance} from 'node:perf_hooks';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {OwnerBridge,sha256,canonical} from './core.mjs';
import {CampaignBridge,CAMPAIGN_SCOPES} from './campaign.mjs';

const baseline=process.argv[2]??'045bfaed33393675c8d8d621bf260636deb04dd8';
if(!/^[a-f0-9]{40}$/.test(baseline))throw Error('Pass an exact baseline commit SHA.');
const directory=await mkdtemp(join(tmpdir(),'astravar-session-baseline-'));
const root=fileURLToPath(new URL('../../',import.meta.url));
const source=path=>execFileSync('git',['show',baseline+':backend/owner-bridge/'+path],{cwd:root,encoding:'utf8'});
await writeFile(join(directory,'campaign.mjs'),source('campaign.mjs'));
await writeFile(join(directory,'core.mjs'),source('core.mjs'));
const BaselineBridge=(await import(pathToFileURL(join(directory,'campaign.mjs')))).CampaignBridge;
const ownerId='session-benchmark-owner',campaign='session-benchmark-campaign',profile='session-benchmark-player';
const connector={ownerId,role:'connector'},secret='b'.repeat(60),now=()=>1792628000000;
const rows=[];
for(let n=0;n<300;n++){
 const id='fixture-'+String(n).padStart(4,'0'),common={source:'Fictional benchmark evidence',updatedAt:n};
 rows.push({kind:'character-profile',recordId:id,revision:1,fields:{...common,id,name:id,currentHp:10,maximumHp:20}});
 rows.push({kind:'party-sheet',recordId:id,revision:1,fields:{...common,profileId:id,campaignId:campaign,contentJson:canonical({resources:[],conditions:''})}});
 rows.push({kind:'wallet',recordId:id,revision:1,fields:{...common,id,holderProfileId:id,campaignId:campaign,name:id,gold:10}});
 rows.push({kind:'project',recordId:id,revision:1,fields:{...common,id,title:id,status:'Proposed'}});
 rows.push({kind:'checkpoint',recordId:id,revision:1,fields:{...common,id,campaignId:campaign,title:id}});
 // Archive-sized text must not enter active-session responses.
 rows.push({kind:'reference',recordId:'archive:'+id,revision:1,fields:{id,title:'Fictional archive',body:'fixture '.repeat(1000)}});
}
const summarize=values=>{
 const sorted=[...values].sort((a,b)=>a-b),percentile=p=>Number(sorted[Math.min(sorted.length-1,Math.ceil(sorted.length*p)-1)].toFixed(3));
 return{count:sorted.length,p50Milliseconds:percentile(.5),p95Milliseconds:percentile(.95),p99Milliseconds:percentile(.99)};
};
async function measure(Bridge,schema){
 const db=new SqliteAdapter();db.exec(schema);
 try{
  const owner=new OwnerBridge(db,{ownerId,now}),bridge=new Bridge(db,{ownerId,now});
  const request=await owner.requestPairing({campaignId:campaign,profileId:profile,deviceName:'Disposable local benchmark',deviceSecretHash:await sha256(secret),campaignAccess:{readScopes:CAMPAIGN_SCOPES,writeScopes:CAMPAIGN_SCOPES}});
  await owner.approvePairing({ownerId,role:'owner'},{userCode:request.userCode});
  const token=await owner.pollPairing({deviceCode:request.deviceCode,deviceSecret:secret}),device=await owner.authenticate(token.credentialId,secret);
  for(let n=0;n<rows.length;n+=100)await bridge.publish(device,campaign,{commandId:crypto.randomUUID(),records:rows.slice(n,n+100)});
  let queries=0;
  const prepare=db.prepare.bind(db);
  db.prepare=(...args)=>{queries++;return prepare(...args);};
  for(let n=0;n<10;n++)await bridge.session(connector,campaign);
  const times=[],counts=[];let result;
  for(let n=0;n<100;n++){
   queries=0;const start=performance.now();result=await bridge.session(connector,campaign);
   times.push(performance.now()-start);counts.push(queries);
  }
  return{timings:summarize(times),databaseQueriesPerCall:[...new Set(counts)],responseBytes:Buffer.byteLength(JSON.stringify(result)),recordCount:result.records.length,integrity:canonical(result.records.map(({kind,recordId,revision,fields})=>({kind,recordId,revision,fields})).sort((a,b)=>(a.kind+'|'+a.recordId).localeCompare(b.kind+'|'+b.recordId)))};
 }finally{db.close();}
}
try{
 const before=await measure(BaselineBridge,source('schema.sql'));
 const after=await measure(CampaignBridge,await readFile(new URL('./schema.sql',import.meta.url),'utf8'));
 assert.equal(after.integrity,before.integrity,'Record identities, revisions and projected fields must match baseline.');
 delete before.integrity;delete after.integrity;
 console.log(JSON.stringify({evidence:'LOCAL_NODE_SQLITE_FICTIONAL_FIXTURES_ONLY',baseline,recordsInDatabase:rows.length,samplesPerVersion:100,warmupCalls:10,hostedHttpsMeasured:false,androidCommitMeasured:false,physicalPhoneMeasured:false,identicalProjectedRecords:true,before,after},null,2));
}finally{await rm(directory,{recursive:true,force:true});}
