/** Validate staged definition fields against the actual service's typed contracts; never call an operation. */
import {readFileSync} from 'node:fs';
import {MECHANIC_KINDS, MECHANIC_OPERATIONS} from '../backend/owner-bridge/cloud-mechanics.mjs';
import {RECORD_KINDS, RECORD_OPERATIONS} from '../backend/owner-bridge/cloud-records.mjs';
import {CONTINUITY_KINDS} from '../backend/owner-bridge/cloud-continuity.mjs';
import {cloudMatches} from '../backend/owner-bridge/cloud-validation.mjs';

const kinds = {...MECHANIC_KINDS, ...RECORD_KINDS, ...CONTINUITY_KINDS};
const operations = {...MECHANIC_OPERATIONS, ...RECORD_OPERATIONS};
const definitions = {
  story_arc:['define_story_arc','arcId'], campaign_session:['record_campaign_session','sessionId'],
  actor:['define_actor','actorId'], resource_pool:['define_resource','poolId'], form:['define_form','formId'],
  feature:['define_feature','featureId'], world_entity:['define_connection','entityId'], effect:['define_effect','effectId'], attack:['define_attack','attackId'], spell:['define_spell','spellId'],
  spell_grant:['grant_spell','grantId'], action_definition:['define_action','actionId'],
  care_protocol:['define_care_protocol','protocolId'], item_definition:['define_item_definition','definitionId'],
  wallet:['define_wallet','walletId'], roll_preset:['save_roll_preset','presetId'],
};
function invalid(kind, reason) {
  throw new Error(kind + ': ' + reason);
}
function validate(row) {
  if (!Object.hasOwn(kinds,row.kind)) invalid(row.kind,'unregistered gameplay family');
  const entry = definitions[row.kind];
  if (!entry) return;
  const [name,id] = entry, schema = operations[name]?.schema;
  if (!schema) invalid(row.kind,'contract unavailable');
  const proposed = {...row.data, actorId:row.actorId, name:row.title, title:row.title, [id]:row.id};
  if (row.kind === 'roll_preset') proposed.update = false;
  const fields={};
  for (const [key,spec] of Object.entries(schema.properties)) {
    if (Object.hasOwn(proposed,key) && (proposed[key] !== null || schema.required.includes(key) || cloudMatches(null,spec))) {
      fields[key]=proposed[key];
    }
  }
  if (!cloudMatches(fields,schema)) invalid(row.kind,'stored definition fails the shared service contract');
}
try {
  for (const row of JSON.parse(readFileSync(0, 'utf8'))) validate(row);
} catch (error) {
  process.stderr.write(error.message+'\n');
  process.exitCode=2;
}
