import {CAMPAIGN_TOOLS,CAMPAIGN_READ_KINDS} from './campaign-tools.mjs';
import {canReadKind} from './campaign.mjs';
/** Stateless Sites MCP adapter. Sites dispatch must be the only public entry point.
 * Incoming identity headers are trustworthy only behind that platform boundary.
 * A connector can read and propose; it cannot approve, reject, enroll, or revoke.
 */
export const MCP_VERSION = '2026-07-28';
const CAMPAIGN = 'synthetic-owner-acceptance';
const PROFILE = 'owner-sync-fixture';
const RECORD = 'owner-sync-fixture-int';
const campaign = { type: 'string', const: CAMPAIGN };
const profile = { type: 'string', const: PROFILE };
const scopedId = { type: 'string', minLength: 1, maxLength: 128, pattern: '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' };
const hp = { type: 'integer', minimum: 0, maximum: 1_000_000 };
const revision = { type: 'integer', minimum: 1, maximum: Number.MAX_SAFE_INTEGER - 1 };
const uuid = { type: 'string', pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' };
const object = (properties, required = Object.keys(properties)) => ({ type: 'object', properties, required, additionalProperties: false });
const read = (name, description, inputSchema) => ({ name, description, inputSchema,
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } });

export const TOOLS = Object.freeze([
  ...CAMPAIGN_TOOLS,
  read('get_character', 'Read a scoped device-published owner-authorized character and sheet with source revisions and freshness. The legacy synthetic campaign remains an isolated test.', object({ campaignId: scopedId, profileId: scopedId })),
  read('get_character_abilities', 'Read only owner-authorized character ability scores, sources and revisions. Unknown values remain unknown; synthetic test records stay isolated.', object({ campaignId: scopedId, profileId: scopedId })),
  {
    name: 'propose_record_update',
    description: 'Stage one fictional Intelligence base-score change for native Android approval. This never commits the change. Device approval and independent receipt/readback are required.',
    inputSchema: object({
      campaignId: campaign, commandId: uuid, recordId: { type: 'string', const: RECORD },
      expectedRevision: { type: 'integer', minimum: 1, maximum: Number.MAX_SAFE_INTEGER - 1 },
      fields: object({ score: { type: 'integer', minimum: 1, maximum: 30 } }),
      source: { type: 'string', minLength: 1, maxLength: 1900 },
    }),
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  read('get_pending_updates', 'Read pending fictional changes and explicit current/proposed values. Pending is not approved or committed.', object({ campaignId: campaign })),
  read('get_command_receipt', 'Read the independent immutable receipt for a fictional command. No receipt means no confirmed outcome.', object({ campaignId: campaign, commandId: uuid })),
  read('get_owner_connection', 'Read owner-approved HP and campaign grants, scoped read/write categories, Trusted DM Auto-Sync categories and synchronization freshness. Returns no credentials or database export.', object({})),
  read('get_character_hp', 'Read the latest device-published HP record and revision for one approved owner campaign/profile binding. Unknown HP remains unknown; this is not proof that an offline phone has synced.', object({ campaignId: scopedId, profileId: scopedId })),
  {
    name: 'propose_hp_adjustment',
    description: 'Legacy HP-only manual-approval proposal. For a campaign connection with Trusted DM Auto-Sync, use submit_campaign_transaction with an adjust_hp operation instead, then get_campaign_command_receipt and an independent current HP read. Use this legacy tool only for an explicitly selected legacy HP connection: read HP and revision first; it stages a difference requiring native player approval and never confirms a save.',
    inputSchema: object({ campaignId: scopedId, profileId: scopedId, commandId: uuid,
      expectedRevision: revision, expectedCurrentHp: hp,
      adjustment: { type: 'integer', minimum: -1_000_000, maximum: 1_000_000, not: { const: 0 } },
      source: { type: 'string', minLength: 1, maxLength: 1900 } }),
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  read('get_hp_pending_updates', 'Read pending HP-only differences for owner-approved profiles in one campaign. Pending proposals have not been applied.', object({ campaignId: scopedId })),
  read('get_hp_command_receipt', 'Read the independent immutable HP command result for an approved owner binding. A missing or failed receipt is unverified, not proof of approval or rollback.', object({ campaignId: scopedId, commandId: uuid })),
]);

const HP_TOOLS = new Set(['get_owner_connection', 'get_character_hp', 'propose_hp_adjustment', 'get_hp_pending_updates', 'get_hp_command_receipt']);

function matches(value, schema) {
  if(!schema)return false;
  if(schema.enum&&!schema.enum.includes(value))return false;
  if(schema.type==='array')return Array.isArray(value)&&(schema.minItems===undefined||value.length>=schema.minItems)&&(schema.maxItems===undefined||value.length<=schema.maxItems)&&value.every(x=>matches(x,schema.items));
  if (Object.hasOwn(schema, 'const') && value !== schema.const) return false;
  if (schema.not && Object.hasOwn(schema.not, 'const') && value === schema.not.const) return false;
  if (schema.type === 'object') {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    const keys = Object.keys(value);
    if (schema.additionalProperties === false && keys.some(key => !Object.hasOwn(schema.properties, key))) return false;
    return schema.required.every(key => Object.hasOwn(value, key)) && keys.every(key => Object.hasOwn(schema.properties,key)?matches(value[key], schema.properties[key]):schema.additionalProperties===true);
  }
  if (schema.type === 'string') return typeof value === 'string' &&
    (schema.minLength === undefined || value.trim().length >= schema.minLength) &&
    (schema.maxLength === undefined || value.length <= schema.maxLength) &&
    (schema.pattern === undefined || new RegExp(schema.pattern).test(value));
  if (schema.type === 'integer') return Number.isSafeInteger(value) && (schema.minimum===undefined||value >= schema.minimum) && (schema.maximum===undefined||value <= schema.maximum);
  return false;
}

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: {
  'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
} });
const rpcError = (id, code, message, status = 200) => json({ jsonrpc: '2.0', id: id ?? null, error: { code, message } }, status);
const safeError = error => typeof error?.code === 'string' && /^[A-Z0-9_]{1,64}$/.test(error.code) ? error.code : 'OPERATION_FAILED';

/** ownerId is a pinned server-side Site-scoped ID, never chosen by a request.
 * This adapter must not be directly exposed on a host where callers can spoof
 * oai-authenticated-user-id. Ordinary bearer credentials are not MCP identity.
 */
export async function handleMcp(request, bridge, { ownerId, allowPublicDiscovery = false, connectorScopes = ['character:read', 'proposal:create', 'proposal:read'], liveHp = null, hpConnectorScopes = ['hp:read', 'hp:propose', 'hp:receipt'], campaignBridge=null } = {}) {
  if (request.method !== 'POST') return rpcError(null, -32600, 'POST_REQUIRED', 405);
  if (request.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') return rpcError(null, -32600, 'JSON_REQUIRED', 415);
  let payload;
  try {
    const reader = request.body?.getReader();
    if (!reader) return rpcError(null, -32700, 'INVALID_JSON', 400);
    const chunks = []; let length = 0;
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > 65_536) { await reader.cancel(); return rpcError(null, -32600, 'REQUEST_TOO_LARGE', 413); }
      chunks.push(value);
    }
    const bytes = new Uint8Array(length); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    payload = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch { return rpcError(null, -32700, 'INVALID_JSON', 400); }
  if (!payload || typeof payload !== 'object' || Array.isArray(payload) || payload.jsonrpc !== '2.0' || typeof payload.method !== 'string' ||
      Object.keys(payload).some(key => !['jsonrpc', 'id', 'method', 'params'].includes(key)) ||
      (Object.hasOwn(payload, 'id') && typeof payload.id !== 'string' && !Number.isSafeInteger(payload.id))) {
    return rpcError(null, -32600, 'INVALID_REQUEST', 400);
  }
  const { id, method, params } = payload;
  const result = value => json({ jsonrpc: '2.0', id: id ?? null, result: value });
  const discovery = ['server/discover', 'initialize', 'tools/list', 'notifications/initialized'].includes(method);
  if (!(discovery && allowPublicDiscovery)) {
    if (typeof ownerId !== 'string' || !ownerId.trim() || !bridge) return rpcError(id, -32001, 'OWNER_BINDING_REQUIRED', 503);
    if (request.headers.get('oai-authenticated-user-id') !== ownerId) return rpcError(id, -32001, 'AUTHENTICATION_REQUIRED', 401);
  }
  if (method === 'notifications/initialized' && id === undefined) return new Response(null, { status: 202 });
  if (id === undefined) return rpcError(null, -32600, 'REQUEST_ID_REQUIRED', 400);
  if (method === 'server/discover') return result({ supportedVersions: [MCP_VERSION], capabilities: { tools: {} } });
  if (method === 'initialize') return result({ protocolVersion: MCP_VERSION, capabilities: { tools: {} }, serverInfo: { name: 'Astravar Owner Connection', version: '2.2.0' }, instructions: 'Owner-private campaign records with per-category grants and Trusted DM Auto-Sync. Use submit_campaign_transaction for campaign gameplay updates, including adjust_hp for damage or healing already resolved in play; then get_campaign_command_receipt and a separate current record read. propose_hp_adjustment is the legacy HP-only manual-approval flow, not the campaign Auto-Sync write path. Read current values and revisions before submitting only resolved player-authorized actions. A queued command is not a saved result. Independently verify its immutable Android receipt and saved record readback; preserve unknowns and stale/offline status. Legacy fictional and HP approval protocols remain isolated.' });
  if (method === 'tools/list') return result({ tools: TOOLS });
  if (method !== 'tools/call') return rpcError(id, -32601, 'METHOD_NOT_FOUND');
  if (!params || typeof params !== 'object' || Array.isArray(params) || Object.keys(params).some(key => !['name', 'arguments', '_meta'].includes(key))) return rpcError(id, -32602, 'INVALID_PARAMS');
  const tool = TOOLS.find(value => value.name === params.name);
  const args = Object.hasOwn(params, 'arguments') ? params.arguments : {};
  if (!tool || !matches(args, tool.inputSchema)) return rpcError(id, -32602, 'INVALID_TOOL_ARGUMENTS');
  const isHpTool = HP_TOOLS.has(tool.name);
  const principal = { ownerId, role: 'connector', credentialId: 'sites-mcp', scopes: [...(isHpTool ? hpConnectorScopes : connectorScopes)] };
  try {
    const hpBridge = liveHp ?? bridge.liveHp;
    if (isHpTool && !hpBridge) throw Object.assign(new Error(), { code: 'HP_CONNECTION_NOT_CONFIGURED' });
    let value;
    const campaignSync=campaignBridge??bridge.campaignBridge;
    if(CAMPAIGN_TOOLS.some(t=>t.name===tool.name)){
      if(!campaignSync)throw Object.assign(new Error(),{code:'CAMPAIGN_CONNECTION_NOT_CONFIGURED'});
      const {campaignId,...body}=args;
      if(tool.name==='submit_campaign_transaction')value=await campaignSync.submit(principal,campaignId,body);
      else if(tool.name==='get_campaign_command_receipt')value=await campaignSync.getReceipt(principal,campaignId,args.commandId);
      else if(tool.name==='get_campaign_reference')value=await campaignSync.referencePart(principal,campaignId,{referenceId:args.referenceId,part:args.part??'metadata',index:args.index??0});
      else if(tool.name==='get_campaign_state')value=await campaignSync.session(principal,campaignId);
      else if(tool.name==='get_campaign_checkpoint')value=await campaignSync.latestCheckpoint(principal,campaignId);
      else if(tool.name==='get_campaign_record')value=await campaignSync.read(principal,campaignId,{kinds:[args.kind],recordId:args.recordId});
      else {const grant=await campaignSync.authorize(principal,campaignId);const scopes=JSON.parse(grant.read_scopes_json);const requested=CAMPAIGN_READ_KINDS[tool.name];const kinds=requested===null?[]:requested.filter(k=>canReadKind(k,scopes));if(requested!==null&&!kinds.length)throw Object.assign(new Error(),{code:'READ_SCOPE_DENIED'});value=await campaignSync.read(principal,campaignId,{kinds,profileId:args.profileId??null,recordId:tool.name==='get_item'?null:args.recordId??null,itemId:tool.name==='get_item'?args.recordId:null,metadataOnly:['get_campaign_history','get_research'].includes(tool.name),after:args.after??'',limit:args.limit??25});}
    }else switch (tool.name) {
      case 'get_character': value = args.campaignId===CAMPAIGN?await bridge.getCharacter(principal,args.campaignId,args.profileId):await campaignSync.read(principal,args.campaignId,{kinds:['character-profile','party-sheet'],profileId:args.profileId});break;
      case 'get_character_abilities': value = args.campaignId===CAMPAIGN?await bridge.getCharacterAbilities(principal,args.campaignId,args.profileId):await campaignSync.read(principal,args.campaignId,{kinds:['character-ability'],profileId:args.profileId});break;
      case 'propose_record_update': {
        const { campaignId, ...proposal } = args;
        value = await bridge.proposeRecordUpdate(principal, campaignId, proposal); break;
      }
      case 'get_pending_updates': value = await bridge.getPendingUpdates(principal, args.campaignId); break;
      case 'get_command_receipt': value = await bridge.getCommandReceipt(principal, args.campaignId, args.commandId); break;
      case 'get_owner_connection': value = await hpBridge.getConnection(principal);if(campaignSync)value.campaignSync=await campaignSync.getConnection(principal);break;
      case 'get_character_hp': if(campaignSync){let hasCampaign=false;try{await campaignSync.authorize(principal,args.campaignId);hasCampaign=true;}catch(e){if(e.code!=='CAMPAIGN_NOT_AUTHORIZED')throw e;}value=hasCampaign?await campaignSync.getHp(principal,args.campaignId,args.profileId):await hpBridge.getCharacterHp(principal,args.campaignId,args.profileId);}else value=await hpBridge.getCharacterHp(principal,args.campaignId,args.profileId);break;
      case 'propose_hp_adjustment': {
        const { campaignId, ...proposal } = args;
        value = await hpBridge.proposeHpUpdate(principal, campaignId, proposal); break;
      }
      case 'get_hp_pending_updates': value = await hpBridge.getPendingUpdates(principal, args.campaignId); break;
      case 'get_hp_command_receipt': value = await hpBridge.getCommandReceipt(principal, args.campaignId, args.commandId); break;
      default: throw Object.assign(new Error(), { code: 'UNKNOWN_TOOL' });
    }
    return result({ content: [{ type: 'text', text: JSON.stringify(value) }], structuredContent: value, isError: false });
  } catch (error) {
    return result({ content: [{ type: 'text', text: JSON.stringify({ error: safeError(error), confirmation: 'UNVERIFIED' }) }], isError: true });
  }
}
