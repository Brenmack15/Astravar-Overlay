/* Exercise an actual portable D1/R2 snapshot and a fresh authority epoch using the registered DM protocol. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const recovery = require('../tools/ascension_provider.cjs');
const provider = process.argv[2] ? path.resolve(process.argv[2]) : undefined;
const continuity = 'qa-research-preset';
const route = '/v3/continuities/' + continuity;
const owner = 'isolated-preset-owner';
const origin = 'https://fixture.invalid';
const actorId = 'synthetic-scholar';
async function post(runtime, destination, body) {
    const response = await runtime.dispatch(origin + destination, {method:'POST', headers:{
        Origin:origin, 'Content-Type':'application/json', 'oai-authenticated-user-id':owner
    },body:JSON.stringify(body)});
    const result = await response.json();
    assert.equal(response.status, 200, JSON.stringify(result));
    return result;
}
async function dm(runtime, name, args = {}) {
    const reply = await post(runtime, '/mcp', {jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{continuityId:continuity,...args}}});
    assert.ok(!reply.result.isError, JSON.stringify(reply.result));
    return reply.result.structuredContent;
}
async function grant(runtime) {
    await post(runtime, route+'/grants/sites-mcp', {scopes:['character.read','character.write','rolls.read','rolls.write'],
        actors:['*'],auto:['character.write','rolls.write'],expiresAt:Date.now()+3600000,expectedGrantRevision:0,revoked:false});
}
async function run(runtime, commandId, operation, args) {
    const {epoch,revision} = await dm(runtime, 'get_cloud_state', {limit:1});
    const command = {version:1,commandId,operation,args,epoch,expectedRevision:revision};
    const receipt = await dm(runtime, 'submit_cloud_command', {command});
    assert.equal(receipt.status, 'COMMITTED');
    assert.deepEqual(receipt, await dm(runtime, 'get_cloud_receipt', {commandId}));
    assert.deepEqual(receipt, await dm(runtime, 'submit_cloud_command', {command}));
    return receipt;
}
async function verify() {
    process.umask(0o077);
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ascension-roll-recovery-'));
    const root = path.join(dir,'private-store'), archive = path.join(dir,'snapshot.zip');
    const previous = process.cwd();
    process.chdir(dir);
    const entries = ['investigation','arcana','history'].map(key=>({kind:'skill',key,mode:'normal'}));
    const presetId = 'artifact-standard', batchId = 'restored-batch';
    let original, initialReceipt;
    try {
        await recovery.create(root,owner,continuity,provider);
        await recovery.withGameplay(root, async runtime => {
            await post(runtime,route+'/isolated',{}); await grant(runtime);
            await run(runtime,'create-actor','define_actor',{actorId,name:'Synthetic scholar',role:'player',rulesProfile:'legacy_2014',level:3,abilities:{INT:12}});
            initialReceipt = await run(runtime,'save-preset','save_roll_preset',{actorId,presetId,name:'Artifact Research — Standard',entries,update:false});
            [original] = (await dm(runtime,'get_cloud_state',{id:presetId})).records;
            assert.deepEqual(original.data.entries,entries);
            assert.equal((await dm(runtime,'get_cloud_state',{kind:'roll'})).records.length,0);
        },provider);
        const saved = await recovery.backup(root,archive,provider);
        const review = await recovery.prepare(root,archive,null,provider);
        await recovery.prepare(root,archive,review.approvalSha256,provider);
        const cut = await recovery.cutover(root,null,false,provider);
        assert.equal((await recovery.cutover(root,cut.approvalSha256,false,provider)).epoch,2);
        await recovery.withGameplay(root, async runtime => {
            await grant(runtime);
            assert.deepEqual((await dm(runtime,'get_cloud_state',{id:presetId})).records,[original]);
            assert.deepEqual(await dm(runtime,'get_cloud_receipt',{commandId:'save-preset'}),initialReceipt);
            await run(runtime,'edit-score','set_actor_ability',{actorId,ability:'INT',score:18});
            const rolls = [16,5,20].map((face,i)=>({rollId:'observed-'+i,evidence:{origin:'player_physical',faces:[face]}}));
            const executed = await run(runtime,'execute-restored-preset','execute_roll_preset',{actorId,presetId,batchId,rolls});
            const batch = (await dm(runtime,'get_cloud_state',{id:batchId})).records[0];
            assert.deepEqual(batch.data.results.map(r=>r.total),[20,9,24]);
            assert.deepEqual(batch.data.results.map(r=>r.natural),[16,5,20]);
            assert.equal((await dm(runtime,'get_cloud_state',{kind:'roll'})).records.length,3);
            assert.deepEqual((await dm(runtime,'get_cloud_state',{id:presetId})).records,[original]);
            console.log(JSON.stringify({status:'VERIFIED',provider:'workerd+D1/R2',epoch:executed.epoch,presetId,batchId,
                receiptRevision:executed.revision,recordedFaces:[16,5,20],verifiedTotals:[20,9,24],sourceManifest:saved.manifestSha256}));
        },provider);
    } finally { process.chdir(previous); fs.rmSync(dir,{recursive:true,force:true}); }
}
verify().catch(error=>{ console.error(error); process.exitCode=1; });
