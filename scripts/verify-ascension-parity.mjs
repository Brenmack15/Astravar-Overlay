/** Run representative gameplay fixtures in both directions and publish what was actually exercised. */
import { mkdtempSync,readFileSync,readdirSync,rmSync,writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { strict as assert } from 'node:assert';
import { MECHANIC_OPERATIONS } from '../backend/owner-bridge/cloud-mechanics.mjs';
import { RECORD_OPERATIONS } from '../backend/owner-bridge/cloud-records.mjs';
import { CLOUD_MEDIA_OPERATIONS } from '../backend/owner-bridge/cloud-media.mjs';
import { CLOUD_LIFECYCLE_OPERATIONS } from '../backend/owner-bridge/cloud-lifecycle.mjs';
import { CONTINUITY_OPERATIONS } from '../backend/owner-bridge/cloud-continuity.mjs';

const directory=mkdtempSync(join(tmpdir(),'ascension-parity-'));
const root=fileURLToPath(new URL('..',import.meta.url));
const migrationFixtures = readdirSync(join(root,'backend/owner-bridge'))
  .filter(name => /^cloud-migration-.*\.test\.mjs$/.test(name))
  .sort().map(name => 'backend/owner-bridge/' + name);
const declared={...MECHANIC_OPERATIONS,...RECORD_OPERATIONS,...CLOUD_MEDIA_OPERATIONS,...CLOUD_LIFECYCLE_OPERATIONS,...CONTINUITY_OPERATIONS};
try{
  for(const direction of ['device','mcp']){
    const proc=spawnSync(process.execPath,['--test','backend/owner-bridge/cloud-mechanics.test.mjs','backend/owner-bridge/cloud-records.test.mjs',
      'backend/owner-bridge/cloud-structured-effects.test.mjs','backend/owner-bridge/cloud-parity-review.test.mjs',...migrationFixtures],{
      cwd:root,encoding:'utf8',maxBuffer:8000000,env:{...process.env,ASTRAVAR_PARITY_SIDE:direction,ASTRAVAR_PARITY_REPORT_DIR:directory}});
    if(proc.status!==0)throw new Error(direction+' parity suite failed\n'+proc.stdout+'\n'+proc.stderr);
  }
  const operations=Object.fromEntries(Object.entries(declared).sort(([a],[b])=>a.localeCompare(b)).map(([name,spec])=>
    [name,{scope:spec.scope,ownerReview:Boolean(spec.ownerReview),ownerOnly:Boolean(spec.ownerOnly),mcp:{commands:0,readbacks:0},device:{commands:0,readbacks:0}}]));
  for(const file of readdirSync(directory)){
    const shard=JSON.parse(readFileSync(join(directory,file),'utf8'));
    for(const [name,value] of Object.entries(shard.operations)){
      const entry=operations[name][shard.direction];
      entry.commands+=value.commands;entry.readbacks+=value.readbacks;
    }
  }
  const bilateral=Object.entries(operations).filter(([,v])=>v.mcp.commands>0&&v.device.commands>0);
  const summary={declaredOperations:Object.keys(operations).length,bidirectionallyExercised:bilateral.length,
    exactRetryAndIndependentReceiptChecked:true,commandsPerDirection:bilateral.reduce((s,[,v])=>s+v.mcp.commands,0),
    independentRowsPerDirection:bilateral.reduce((s,[,v])=>s+v.mcp.readbacks,0)};
  const missing=Object.entries(operations).filter(([,v])=>!v.ownerOnly&&(!v.mcp.commands||!v.device.commands)).map(([name])=>name);
  assert.deepEqual(missing,[],'Every non-administrative command must have a positive bilateral fixture');
  summary.ownerOnly=Object.entries(operations).filter(([,v])=>v.ownerOnly).map(([name])=>name);
  for(const [name,value] of bilateral)assert.deepEqual(value.mcp,value.device,name);
  if(process.argv[2])writeFileSync(process.argv[2],JSON.stringify({version:1,scope:'Registered MCP and authenticated Android HTTP wire protocols, synthetic SQL fixtures',summary,operations},null,2)+'\n');
  console.log(JSON.stringify(summary));
}finally{rmSync(directory,{recursive:true,force:true});}
