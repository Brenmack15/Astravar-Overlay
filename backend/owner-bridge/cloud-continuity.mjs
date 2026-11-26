import { cloudFail, cloudObject } from './cloud-validation.mjs';

export const CONTINUITY_KINDS = Object.freeze({continuity_profile:'campaign'});
const metadataSchema = cloudObject({
  name: {type:'string', minLength:1, maxLength:180, pattern:'\\S'},
  notes: {anyOf:[{type:'string', maxLength:4000}, {type:'null'}]},
});

export const CONTINUITY_OPERATIONS = Object.freeze({
  update_continuity_settings: {
    scope:'campaign', ownerOnly:true, schema:metadataSchema,
    async plan(ctx, args) {
      if (ctx.grant.subject !== 'sites-owner') cloudFail(403, 'OWNER_REQUIRED');
      const continuityId = ctx.binding[1];
      const prior = await ctx.get('continuity_profile', continuityId);
      if (prior && prior.life !== 'active') cloudFail(409, 'CONTINUITY_ARCHIVED');
      const origin = prior?.data ?? {createdAt:ctx.now, sourceUpdatedAt:null, sourceArchived:null, source:'owner', sourceCommandId:null};
      await ctx.put({id:continuityId, kind:'continuity_profile', actorId:null, title:args.name,
        data:{...origin, notes:args.notes, editedAt:ctx.now}});
      return {continuityId};
    }
  },
});

export async function cloudContinuitySettings(service, principal, id) {
  const state = await service.state(principal, id, {id, kind:'continuity_profile', activeOnly:false});
  const {records, nextAfter, ...snapshot} = state;
  return {...snapshot, profile:records[0] ?? null};
}
