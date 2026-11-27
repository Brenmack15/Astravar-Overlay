/** Validate staged definition fields against the actual service's typed contracts; never call an operation. */
import {readFileSync} from 'node:fs';
import {MECHANIC_KINDS, MECHANIC_OPERATIONS} from '../backend/owner-bridge/cloud-mechanics.mjs';
import {RECORD_KINDS, RECORD_OPERATIONS} from '../backend/owner-bridge/cloud-records.mjs';
import {CONTINUITY_KINDS} from '../backend/owner-bridge/cloud-continuity.mjs';
import {cloudMatches} from '../backend/owner-bridge/cloud-validation.mjs';
import {checkedClassTracks} from '../backend/owner-bridge/cloud-class-progression.mjs';
import {sheet} from '../backend/owner-bridge/cloud-mechanics-core.mjs';

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
  if (row.kind === 'known_fact' && row.data.referenceKind != null &&
      !cloudMatches(row.data.referenceKind,operations.record_known_fact.schema.properties.referenceKind)) {
    invalid(row.kind,'unrecognized player-known reference kind');
  }
  const entry = definitions[row.kind];
  if (!entry) return;
  const [name,id] = entry, schema = operations[name]?.schema;
  if (!schema) invalid(row.kind,'contract unavailable');
  const proposed = {...row.data, actorId:row.actorId, name:row.title, title:row.title, [id]:row.id};
  if (row.kind === 'roll_preset') proposed.update = false;
  if (row.kind === 'feature' && row.data.category != null) {
    if (row.data.kind != null && row.data.kind !== row.data.category) invalid(row.kind,'conflicting feature categories');
    proposed.kind = row.data.category;
  }
  const fields={};
  for (const [key,spec] of Object.entries(schema.properties)) {
    if (Object.hasOwn(proposed,key) && (proposed[key] !== null || schema.required.includes(key) || cloudMatches(null,spec))) {
      fields[key]=proposed[key];
    }
  }
  if (!cloudMatches(fields,schema)) invalid(row.kind,'stored definition fails the shared service contract');
  if (row.kind === 'effect' && fields.requiresWorn && !fields.sourceItemId) invalid(row.kind,'worn effect requires an item source');
  if (row.kind === 'effect' && row.data.wornResistanceUpgrade &&
      !(fields.target==='damage_defense' && fields.operation==='resistance' && fields.sourceItemId && fields.requiresAttunement)) {
    invalid(row.kind,'worn resistance upgrade requires an attuned resistance source');
  }
  if (row.kind === 'actor' && row.data.classTracks != null) checkedClassTracks(row.data.level,row.data.classTracks);
}
/** Check relationships on the imported point-in-time graph without replaying declarations or casts. */
function validateCompanionSources(rows) {
  const records = new Map(rows.map(row => [row.id,row]));
  const companions = new Set(), operators = new Set(), occupied = new Set();
  const aidSchema = operations.define_spell.schema.properties.rollAid;
  for (const row of rows) {
    const d = row.data;
    if (row.kind === 'feature' && d.infusionBond != null) {
      const {operatorId,knownFeatureId,dedicatedPoolId} = d.infusionBond;
      const companion = records.get(row.actorId), operator = records.get(operatorId);
      const known = records.get(knownFeatureId), slot = records.get(dedicatedPoolId);
      if (companion?.data.role !== 'companion' || d.category !== 'companion' || d.sourceItemId || d.formId ||
          operator?.kind !== 'actor' || operatorId === row.actorId || known?.kind !== 'feature' ||
          known.actorId !== operatorId || !['class','homebrew'].includes(known.data.category) ||
          slot?.kind !== 'resource_pool' || ![row.actorId,operatorId].includes(slot.actorId) ||
          slot.data.type !== 'ordinary' || slot.data.maximum !== 1 || slot.data.current !== 0 || slot.data.recovery !== 'none' ||
          (row.life === 'active' && [operator,known,slot].some(record => record.life !== 'active'))) {
        invalid(row.kind,'unproven companion operator, known infusion or occupied dedicated pool');
      }
      if (companions.has(row.actorId) || operators.has(operatorId) || occupied.has(slot.id)) invalid(row.kind,'duplicate original companion bond');
      companions.add(row.actorId); operators.add(operatorId); occupied.add(slot.id);
    }
    if (row.kind === 'feature' && d.includedScore != null && d.includedScore.before >= d.includedScore.after) {
      invalid(row.kind,'invalid historical included ability increase');
    }
    if (['spell','cast_instance'].includes(row.kind) && d.rollAid != null) {
      if (!cloudMatches(d.rollAid,aidSchema) || !d.concentration || d.resolution !== 'automatic' ||
          !d.rollAid.applies.length || new Set(d.rollAid.applies).size !== d.rollAid.applies.length) {
        invalid(row.kind,'invalid concentrated roll aid');
      }
      if (row.kind === 'cast_instance') {
        const spell = records.get(d.spellId), grant = records.get(d.grantId);
        if (spell?.kind !== 'spell' || spell.actorId !== row.actorId || grant?.kind !== 'spell_grant' ||
            grant.actorId !== row.actorId || grant.data.spellId !== d.spellId || !Array.isArray(d.targets) ||
            d.targets.length > 12 || new Set(d.targets).size !== d.targets.length || d.targets.some(id => records.get(id)?.kind !== 'actor')) {
          invalid(row.kind,'unproven roll-aid cast, grant or targets');
        }
      }
    }
    if (row.kind === 'actor' && d.rollAidRefs != null) {
      if (!Array.isArray(d.rollAidRefs) || d.rollAidRefs.length > 16) invalid(row.kind,'invalid actor roll-aid references');
      const casts = new Set();
      for (const ref of d.rollAidRefs) {
        if (!ref || typeof ref !== 'object' || Object.keys(ref).sort().join() !== 'castId,casterId,expiresAt') invalid(row.kind,'invalid actor roll-aid reference');
        const cast = records.get(ref.castId);
        if (records.get(ref.casterId)?.kind !== 'actor' || cast?.kind !== 'cast_instance' || !cast.data.rollAid ||
            cast.actorId !== ref.casterId || !Array.isArray(cast.data.targets) || !cast.data.targets.includes(row.id) ||
            casts.has(ref.castId) || (ref.expiresAt !== null && (!Number.isSafeInteger(ref.expiresAt) || ref.expiresAt < 0))) {
          invalid(row.kind,'roll-aid reference lacks its caster, target or recorded expiry');
        }
        casts.add(ref.castId);
      }
    }
  }
}
/** Above-base current HP requires support from the same effective sheet used during play. */
async function validateEquippedCapacity(rows) {
  const records=new Map(rows.map(row=>[row.id,row]));
  const context={
    get(kind,id){const row=records.get(id);return row?.kind===kind?row:null;},
    current(id){return records.get(id);},
    grant:{scopes:['resources.read'],actors:['*']},
    require(){/* The preceding graph validation bounds reads to the reviewed tenant. */},
  };
  for(const row of rows){
    const {currentHp,maxHp}=row.data;
    if(row.kind!=='actor' || currentHp==null || maxHp==null || currentHp<=maxHp)continue;
    const projected=await sheet(context,row.id);
    if(projected.maxHp==null || currentHp>projected.maxHp)invalid(row.kind,'HP exceeds recorded effective capacity');
  }
}
try {
  const rows = JSON.parse(readFileSync(0, 'utf8'));
  for (const row of rows) validate(row);
  validateCompanionSources(rows);
  await validateEquippedCapacity(rows);
} catch (error) {
  process.stderr.write(error.message+'\n');
  process.exitCode=2;
}
