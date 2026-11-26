import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFileSync} from 'node:fs';
import {SqliteAdapter} from './sqlite-adapter.mjs';
import {handleWorker} from './worker.mjs';

test('owner updates continuity metadata, scoped DM reads it, actor-only access and grant expansion stay blocked', async t => {
  const db = new SqliteAdapter();
  t.after(()=>db.close());
  db.exec(readFileSync(new URL('schema.sql', import.meta.url), 'utf8'));
  const env = {DB:db, OWNER_SITE_USER_ID:'fixture-owner', ASTRAVAR_CLOUD_ENABLED:'1', ASTRAVAR_CLOUD_ENV:'isolated'};
  const origin = 'https://isolated-continuity.example';
  const id = 'qa-settings-campaign', path = '/v3/continuities/' + id;
  const req = async (route, method='GET', body) => {
    const raw = await handleWorker(new Request(origin+route, {method, headers:{'Content-Type':'application/json',
      'oai-authenticated-user-id':'fixture-owner', Origin:origin}, ...(body ? {body:JSON.stringify(body)} : {})}), env);
    return {code:raw.status, value:await raw.json()};
  };
  const mcp = async (name, args={}) => {
    const response = await req('/mcp', 'POST', {jsonrpc:'2.0', id:1, method:'tools/call',
      params:{name, arguments:{continuityId:id, ...args}}});
    const result=response.value.result;
    return result?.structuredContent ?? JSON.parse(result.content[0].text);
  };
  assert.equal((await req(path+'/isolated','POST',{})).code,200);
  const command={version:1, epoch:1, expectedRevision:0, commandId:'settings-original', operation:'update_continuity_settings',
    args:{name:'Fixture continuity', notes:null}};
  const put = await req(path+'/commands','POST',command);
  assert.equal(put.value.status,'COMMITTED',JSON.stringify(put));
  const first = (await req(path+'/settings')).value.profile;
  assert.equal(first.id,id);
  assert.equal(first.actorId,null);
  assert.equal(first.data.notes,null);
  assert.equal(first.data.sourceCommandId,null);
  const grant = (scopes, actors, expectedGrantRevision) => req(path+'/grants/sites-mcp', 'POST',
    {scopes, actors, auto:[], expectedGrantRevision, expiresAt:Date.now()+3600000, revoked:false});
  assert.equal((await grant(['campaign.read','campaign.write'],['*'],0)).code,400);
  assert.equal((await grant(['campaign.read'], ['one-actor'],0)).code,200);
  assert.equal((await mcp('get_cloud_continuity_settings')).profile,null);
  assert.equal((await grant(['campaign.read'], ['*'],1)).code,200);
  assert.deepEqual((await mcp('get_cloud_continuity_settings')).profile,first);
  assert.ok(!(await mcp('get_cloud_capabilities')).operations.some(x=>x.name==='update_continuity_settings'));
  const denied=await mcp('submit_cloud_command', {command:{...command, expectedRevision:1, commandId:'denied-settings'}});
  assert.equal(denied.error,'OWNER_REQUIRED',JSON.stringify(denied));
  const updated=await req(path+'/commands','POST',{...command, expectedRevision:1, commandId:'settings-revised', args:{name:'Revised',notes:'Reviewed.'}});
  assert.equal(updated.value.status,'COMMITTED');
  const last=(await mcp('get_cloud_continuity_settings')).profile;
  assert.equal(last.data.createdAt,first.data.createdAt);
  assert.equal(last.data.notes,'Reviewed.');
  assert.equal(last.revision,2);
});
