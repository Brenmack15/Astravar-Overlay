import { BridgeError } from './core.mjs';

export const CLOUD_SCOPES = Object.freeze([
  'character', 'inventory', 'economy', 'resources', 'combat', 'spells',
  'rolls', 'crafting', 'world', 'chronicle', 'media', 'lifecycle', 'campaign',
]);
export const CLOUD_ID = Object.freeze({ type: 'string', minLength: 1, maxLength: 128, pattern: '^[A-Za-z0-9][A-Za-z0-9._:-]*$' });
export const CLOUD_REVISION = Object.freeze({ type: 'integer', minimum: 0, maximum: Number.MAX_SAFE_INTEGER - 1 });
export function cloudObject(properties, required = Object.keys(properties)) {
  return { type: 'object', properties, required, additionalProperties: false };
}
export function cloudFail(status, code) { throw new BridgeError(status, code); }
export function validCloudId(value) { return typeof value === 'string' && value.length <= 128 && /^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(value); }

export function cloudMatches(value, schema, depth = 0) {
  if (!schema || depth > 30) return false;
  if (schema.anyOf && !schema.anyOf.some(s => cloudMatches(value, s, depth + 1))) return false;
  if (schema.oneOf && schema.oneOf.filter(s => cloudMatches(value, s, depth + 1)).length !== 1) return false;
  if (Object.hasOwn(schema, 'const') && value !== schema.const) return false;
  if (schema.enum && !schema.enum.includes(value)) return false;
  if (!schema.type) return Boolean(schema.enum || schema.anyOf || schema.oneOf || Object.hasOwn(schema, 'const'));
  if (Array.isArray(schema.type)) return schema.type.some(t => cloudMatches(value, { ...schema, type: t }, depth + 1));
  switch (schema.type) {
    case 'null': return value === null;
    case 'boolean': return typeof value === 'boolean';
    case 'integer': return Number.isSafeInteger(value) && value >= (schema.minimum ?? -Number.MAX_SAFE_INTEGER) && value <= (schema.maximum ?? Number.MAX_SAFE_INTEGER);
    case 'string': return typeof value === 'string' && value.length >= (schema.minLength ?? 0) && value.length <= (schema.maxLength ?? 32768) && (!schema.pattern || new RegExp(schema.pattern, 'u').test(value));
    case 'array': return Array.isArray(value) && value.length >= (schema.minItems ?? 0) && value.length <= (schema.maxItems ?? 256) && (!schema.uniqueItems || new Set(value.map(v => JSON.stringify(v))).size === value.length) && value.every(v => cloudMatches(v, schema.items, depth + 1));
    case 'object': {
      if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
      const keys = Object.keys(value), props = schema.properties || {};
      return (schema.required || []).every(k => Object.hasOwn(value, k)) && keys.every(k =>
        !['__proto__','constructor','prototype'].includes(k) && (Object.hasOwn(props, k)
          ? cloudMatches(value[k], props[k], depth + 1)
          : typeof schema.additionalProperties === 'object' && cloudMatches(value[k], schema.additionalProperties, depth + 1)));
    }
    default: return false;
  }
}

export const CLOUD_ENVELOPE = cloudObject({
  version: { const: 1 }, commandId: { ...CLOUD_ID, minLength: 8 },
  epoch: { ...CLOUD_REVISION, minimum: 1 }, expectedRevision: CLOUD_REVISION,
  operation: { type: 'string', minLength: 1, maxLength: 80, pattern: '^[a-z][a-z0-9_]+$' },
  args: { type: 'object', properties: {}, additionalProperties: false },
  replacesCommandId: CLOUD_ID, replacesDigest: {type:'string',pattern:'^[a-f0-9]{64}$'},
},['version','commandId','epoch','expectedRevision','operation','args']);
export function validateCloudCommand(command, operations) {
  const definition = command && Object.hasOwn(operations, command.operation) ? operations[command.operation] : null;
  if (!definition) cloudFail(400, 'UNSUPPORTED_OPERATION');
  const envelope = { ...CLOUD_ENVELOPE, properties: { ...CLOUD_ENVELOPE.properties, args: definition.schema } };
  if (Object.hasOwn(command,'replacesCommandId')!==Object.hasOwn(command,'replacesDigest') || command.replacesCommandId===command.commandId)cloudFail(400,'INVALID_REPLACEMENT');
  if (!cloudMatches(command, envelope) || JSON.stringify(command).length > 48000) cloudFail(400, 'INVALID_COMMAND');
  return definition;
}
