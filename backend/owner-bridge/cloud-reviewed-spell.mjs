import {fields,identifier,must,named,option} from './cloud-mechanics-core.mjs';

export const adjudicatedSpellEvidence=fields({
  confirmed:{const:true},componentsAccounted:{const:true},summary:named(500),
  trigger:option('incoming_hit','targeted_by_spell'),triggeringSpellId:identifier
},['confirmed','componentsAccounted','summary']);

export function reviewedSpellEvidence(definition, grant, supplied) {
  if(definition.resolution!=='manual'){
    must(!supplied,'UNEXPECTED_MANUAL_SPELL_EVIDENCE');
    return;
  }
  must(grant.reviewedAdjudication && supplied && supplied.confirmed && supplied.componentsAccounted,
    'MANUAL_ADJUDICATION_REQUIRED');
  if(grant.reactionTriggerSpellId){
    must(definition.timing==='reaction' && (
      supplied.trigger==='incoming_hit' && !supplied.triggeringSpellId ||
      supplied.trigger==='targeted_by_spell' && supplied.triggeringSpellId===grant.reactionTriggerSpellId
    ),'SPELL_REACTION_TRIGGER_UNVERIFIED');
  } else {
    must(!supplied.trigger && !supplied.triggeringSpellId,'UNEXPECTED_MANUAL_SPELL_TRIGGER');
  }
}
