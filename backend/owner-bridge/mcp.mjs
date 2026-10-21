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
const uuid = { type: 'string', pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' };
const object = (properties, required = Object.keys(properties)) => ({ type: 'object', properties, required, additionalProperties: false });
const read = (name, description, inputSchema) => ({ name, description, inputSchema,
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } });

export const TOOLS = Object.freeze([
  read('get_character', 'Read the latest device-published fictional character and revision. No real campaign access.', object({ campaignId: campaign, profileId: profile })),
  read('get_character_abilities', 'Read only the fictional character ability scores, sources and revisions. Unknown values remain unknown.', object({ campaignId: campaign, profileId: profile })),
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
]);

function matches(value, schema) {
  if (Object.hasOwn(schema, 'const') && value !== schema.const) return false;
  if (schema.type === 'object') {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    const keys = Object.keys(value);
    if (schema.additionalProperties === false && keys.some(key => !Object.hasOwn(schema.properties, key))) return false;
    return schema.required.every(key => Object.hasOwn(value, key)) && keys.every(key => matches(value[key], schema.properties[key]));
  }
  if (schema.type === 'string') return typeof value === 'string' &&
    (schema.minLength === undefined || value.trim().length >= schema.minLength) &&
    (schema.maxLength === undefined || value.length <= schema.maxLength) &&
    (schema.pattern === undefined || new RegExp(schema.pattern).test(value));
  if (schema.type === 'integer') return Number.isSafeInteger(value) && value >= schema.minimum && value <= schema.maximum;
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
export async function handleMcp(request, bridge, { ownerId, allowPublicDiscovery = false, connectorScopes = ['character:read', 'proposal:create', 'proposal:read'] } = {}) {
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
  if (method === 'initialize') return result({ protocolVersion: MCP_VERSION, capabilities: { tools: {} }, serverInfo: { name: 'Astravar Owner Connection', version: '1.0.0' }, instructions: 'Fictional acceptance only. Proposals require native player approval; verify receipt and independent readback.' });
  if (method === 'tools/list') return result({ tools: TOOLS });
  if (method !== 'tools/call') return rpcError(id, -32601, 'METHOD_NOT_FOUND');
  if (!params || typeof params !== 'object' || Array.isArray(params) || Object.keys(params).some(key => !['name', 'arguments', '_meta'].includes(key))) return rpcError(id, -32602, 'INVALID_PARAMS');
  const tool = TOOLS.find(value => value.name === params.name);
  if (!tool || !matches(params.arguments, tool.inputSchema)) return rpcError(id, -32602, 'INVALID_TOOL_ARGUMENTS');
  const args = params.arguments;
  const principal = { ownerId, role: 'connector', credentialId: 'sites-mcp', scopes: [...connectorScopes] };
  try {
    let value;
    switch (tool.name) {
      case 'get_character': value = await bridge.getCharacter(principal, args.campaignId, args.profileId); break;
      case 'get_character_abilities': value = await bridge.getCharacterAbilities(principal, args.campaignId, args.profileId); break;
      case 'propose_record_update': {
        const { campaignId, ...proposal } = args;
        value = await bridge.proposeRecordUpdate(principal, campaignId, proposal); break;
      }
      case 'get_pending_updates': value = await bridge.getPendingUpdates(principal, args.campaignId); break;
      case 'get_command_receipt': value = await bridge.getCommandReceipt(principal, args.campaignId, args.commandId); break;
      default: throw Object.assign(new Error(), { code: 'UNKNOWN_TOOL' });
    }
    return result({ content: [{ type: 'text', text: JSON.stringify(value) }], structuredContent: value, isError: false });
  } catch (error) {
    return result({ content: [{ type: 'text', text: JSON.stringify({ error: safeError(error), confirmation: 'UNVERIFIED' }) }], isError: true });
  }
}
