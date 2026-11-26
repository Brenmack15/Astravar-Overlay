import { BridgeError, sha256 } from './core.mjs';
import { cloudMatches } from './cloud-validation.mjs';

export function recordsFail(code, status = 400) { throw new BridgeError(status, code); }
export function recordObject(properties, required = Object.keys(properties)) {
  return { type: 'object', properties, required, additionalProperties: false };
}
export function recordString(maxLength = 500, minLength = 1) { return { type: 'string', minLength, maxLength, ...(minLength ? { pattern: '\\S' } : {}) }; }
export const recordId = { type: 'string', minLength: 1, maxLength: 120, pattern: '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}$' };
export const recordText = recordString(4000);
export const recordSource = recordString(1000);
export const recordTitle = recordString(180);
export function recordChoice(...values) { return { type: 'string', enum: values }; }
export function recordMaybe(schema) { return { anyOf: [schema, { type: 'null' }] }; }
export function recordInt(minimum, maximum) { return { type: 'integer', minimum, maximum }; }
export function recordArray(items, maxItems, minItems = 0) { return { type: 'array', items, minItems, maxItems }; }
export const recordBool = { type: 'boolean' };

export function validateRecordValue(spec, value) {
  if (!cloudMatches(value, spec)) recordsFail('INVALID_RECORD_FIELDS');
}
export async function historyRecordId(kind, commandId) {
  return kind + '-' + (await sha256(commandId)).slice(0,48);
}

export function recordsOperation(scope, schema, fn) {
  return { scope, schema, async plan(ctx, args) { validateRecordValue(schema, args); return fn(ctx, args); } };
}
export async function recordNew(ctx, kind, id) {
  if (await ctx.get(kind, id)) recordsFail('RECORD_ID_ALREADY_IN_USE', 409);
}
export async function recordRead(ctx, kind, id, includeArchived = false) {
  const row = await ctx.get(kind, id);
  if (!row || (!includeArchived && row.life !== 'active')) recordsFail('RECORD_UNAVAILABLE', 409);
  if (row.life === 'archived') await ctx.require('lifecycle',row.actorId,false);
  return row;
}
export async function partyActor(ctx, id) {
  const actor = await recordRead(ctx, 'actor', id);
  if (!['player','companion'].includes(actor.data.role)) recordsFail('PARTY_ACTOR_REQUIRED', 409);
  return actor;
}
export function recordEdit(row, data, change = {}) {
  return { id: row.id, kind: row.kind, actorId: row.actorId, title: row.title, data, life: row.life, ...change };
}
export function uniqueRecordIds(values) {
  if (new Set(values).size !== values.length) recordsFail('DUPLICATE_REFERENCE');
}
export const RECORD_DOMAIN_KINDS = Object.freeze({
  wallet: 'economy', coin_transaction: 'economy', item_definition: 'inventory', item_instance: 'inventory',
  item_event: 'inventory', world_entity: 'world', relationship: 'world', known_fact: 'world',
  timeline_event: 'chronicle', objective: 'chronicle', checkpoint: 'chronicle', research_note: 'crafting',
  invention_project: 'crafting', blueprint_version: 'crafting', project_stage: 'crafting', component_event: 'crafting',
});
