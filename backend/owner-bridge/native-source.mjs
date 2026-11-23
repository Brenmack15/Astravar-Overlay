import {BridgeError} from './core.mjs';

export const nativeSourceTypes = ['define_native_item','define_native_actor'];
const profileKeys = new Set(('name species className subclass rules level baseAbilities baseMaxHp baselineConstitutionModifier currentHp temporaryHp baseAc armorUsesDexterity armorDexterityCap speed movement senses reviewedProficiency languages toolProficiencies saveProficiencies skills customSkills features effects conditions resources attacks spells spellcastingAbility spellcastingClassLevel slotMaximaOverride spellSlots').split(' '));
const reject = () => { throw new BridgeError(400,'INVALID_NATIVE_SOURCE'); };
function shape(value, required, optional = []) {
 if (!value || typeof value !== 'object' || Array.isArray(value) ||
     required.some(k => !Object.hasOwn(value,k)) || Object.keys(value).some(k => !required.includes(k) && !optional.includes(k))) reject();
}
function identifier(value) {
 if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/.test(value)) reject();
}
function revision(value, min) {
 if (!Number.isSafeInteger(value) || value < min) reject();
}
export function nativeSourcePlan(op, campaign, prior) {
 const f = op.fields;
 const item = op.type === 'define_native_item';
 if (!nativeSourceTypes.includes(op.type) || op.kind !== 'reference') reject();
 shape(f,['version','mode','purpose','evidence','anchorId','anchorRevision','actorId','actorRole','actorRevision'],
       item ? ['definition','instance'] : ['profile','ownerId','attacksPerAction']);
 if (f.version !== 1 || !['GAMEPLAY','MIGRATION'].includes(f.purpose) || !['CREATE','EDIT'].includes(f.mode) ||
     !['PLAYER','COMPANION'].includes(f.actorRole)) reject();
 identifier(f.anchorId); identifier(f.actorId); identifier(op.recordId);
 revision(f.anchorRevision,1); revision(f.actorRevision,item || f.mode === 'EDIT' ? 1 : 0);
 const e = f.evidence;
 shape(e,['namespace','externalId','url','revision']);
 if (!/^[a-z0-9][a-z0-9-]{0,59}$/.test(e.namespace) || e.namespace === 'campaign-sync-membership' ||
     typeof e.externalId !== 'string' || !e.externalId.trim() || e.externalId.length > 180 ||
     typeof e.url !== 'string' || !e.url.startsWith('https://') || e.url.length > 2000 ||
     typeof e.revision !== 'string' || !e.revision.trim() || e.revision.length > 90) reject();
 const scopes = new Set(['continuity','character']);
 if (f.actorRole === 'COMPANION') scopes.add('companions');
 let target = f.actorId;
 let manual = f.purpose === 'MIGRATION' || f.mode === 'EDIT' || !item;
 if (item) {
  shape(f.definition,['id','name'],['effects','requiresAttunement','attunementCost','equipmentSlot','minimumLevel','minimumAbilities','weight','description','forms','activations']);
  shape(f.instance,['id','definitionId'],['state','attuned','quantity','operating','favorite','formId','formKnown','pools','donorInstanceIds']);
  identifier(f.definition.id); identifier(f.instance.id);
  if (f.instance.definitionId !== f.definition.id || !f.definition.name?.trim() ||
      !['STORED','CARRIED','WORN','HELD'].includes(f.instance.state ?? 'CARRIED')) reject();
  if (Object.hasOwn(f.instance,'formKnown') && typeof f.instance.formKnown !== 'boolean') reject();
  if ((f.instance.formId != null || f.instance.formKnown === false) && f.purpose !== 'MIGRATION') reject();
  if (f.instance.donorInstanceIds != null && (!Array.isArray(f.instance.donorInstanceIds) || (f.instance.donorInstanceIds.length && (f.purpose !== 'MIGRATION' || f.mode !== 'CREATE')))) reject();
  if ((f.instance.pools ?? []).length || (f.definition.activations ?? []).length) for (const key of ['resources','combat','hp']) scopes.add(key);
  target = f.instance.id;
  manual ||= (f.instance.state ?? 'CARRIED') !== 'CARRIED' || f.instance.attuned === true;
  scopes.add('inventory');
  // An edit may remove an old effect in any of these domains.
  if (f.mode === 'EDIT') for (const scope of ['hp','resources','combat']) scopes.add(scope);
  const effects = f.definition.effects ?? [];
  if (!Array.isArray(effects) || effects.length > 128) reject();
  for (const effect of effects) {
   shape(effect,['id','name','source','payload'],['conditions','enabled','priority','stackingKey','requiresAttunement','expiresAtGameSeconds','expiresOn','chargeResourceId','chargeCost']);
   identifier(effect.id);
   shape(effect.source,['id','name','acceptance'],['revision']);
   if (!['ACCEPTED_HOMEBREW','OFFICIAL'].includes(effect.source.acceptance)) reject();
   const prefix = 'com.yazsras.astravar.rules.reforge.EffectPayload.';
   const kind = effect.payload?.type;
   if (typeof kind !== 'string' || !kind.startsWith(prefix)) reject();
   switch (kind.slice(prefix.length)) {
    case 'Number': {
     shape(effect.payload,['type','target','operation','amount'],['key']);
     if (!['ABILITY','ABILITY_CAP','SAVE','SKILL','INITIATIVE','ARMOR_CLASS','MAX_HP','SPEED','SPELL_ATTACK','SPELL_DC','ATTACK','DAMAGE','RESOURCE_CAPACITY'].includes(effect.payload.target) ||
         !['ADD','SET','MINIMUM','MAXIMUM'].includes(effect.payload.operation) || !Number.isSafeInteger(effect.payload.amount)) reject();
     if (['ABILITY','ABILITY_CAP'].includes(effect.payload.target) && effect.payload.key === 'CON' || effect.payload.target === 'MAX_HP') scopes.add('hp');
     if (effect.payload.target === 'RESOURCE_CAPACITY') scopes.add('resources');
     if (['ATTACK','DAMAGE'].includes(effect.payload.target)) scopes.add('combat');
     break;
    }
    case 'Proficiency': break;
    case 'Defense': case 'DamageBonus': case 'DamageConversion': case 'MagicalAttack': case 'ConditionImmunity': case 'Edge': scopes.add('combat'); break;
    default: reject();
   }
   if (effect.chargeResourceId != null) scopes.add('resources');
  }
 } else {
  const p = f.profile;
  if (!p || Array.isArray(p) || typeof p !== 'object' || !Object.keys(p).length || Object.keys(p).some(k => !profileKeys.has(k))) reject();
  if (f.mode === 'CREATE' && (f.actorRevision !== 0 || !p.name?.trim() || !Object.hasOwn(p,'level') || !p.rules ||
      ((f.actorRole === 'COMPANION') !== (f.ownerId != null)))) reject();
  if (f.mode === 'EDIT' && f.ownerId != null) reject();
  if (f.ownerId != null) identifier(f.ownerId);
  if(f.attacksPerAction != null){ if(f.mode !== 'CREATE' || !Number.isInteger(f.attacksPerAction) || f.attacksPerAction < 1 || f.attacksPerAction > 100) reject(); scopes.add('combat'); }
  const has = names => names.some(k => Object.hasOwn(p,k));
  if (has(['baseMaxHp','currentHp','temporaryHp','baselineConstitutionModifier','baseAbilities','level','conditions','effects'])) scopes.add('hp');
  if (has(['resources','spellSlots','slotMaximaOverride','spells','level','effects','features','spellcastingClassLevel'])) scopes.add('resources');
  if (has(['attacks','effects'])) scopes.add('combat');
 }
 const fields = {id:op.recordId,namespace:e.namespace,externalId:e.externalId,recordType:item?'item':'character-profile',recordId:target,url:e.url,campaignId:campaign};
 if (prior && (f.mode === 'CREATE' || ['namespace','externalId','recordType','recordId','campaignId'].some(k => prior[k] !== fields[k]))) reject();
 return {manual,scopes:[...scopes],fields};
}
