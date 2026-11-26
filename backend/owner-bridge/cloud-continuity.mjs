import { CLOUD_ID, cloudFail, cloudObject } from './cloud-validation.mjs';

export const CONTINUITY_KINDS = Object.freeze({continuity_profile:'campaign'});
const metadataSchema = cloudObject({
  name: {type:'string', minLength:1, maxLength:180, pattern:'\\S'},
  notes: {anyOf:[{type:'string', maxLength:4000}, {type:'null'}]},
});

// Pre-roster characters keep their established default until an explicit membership decision.
export function actorMembership(data) {
  return data.membership ?? {participation:'active',inParty:['player','companion'].includes(data.role),worldTime:null,source:null};
}
export const partyMembershipSql = "COALESCE(json_extract(data_json,'$.membership.participation'),'active')='active' AND COALESCE(json_extract(data_json,'$.membership.inParty'),json_extract(data_json,'$.role') IN('player','companion'))=1";

export const CONTINUITY_OPERATIONS = Object.freeze({
  set_actor_membership: {
    scope:'character',ownerReview:true,schema:cloudObject({
      actorId:CLOUD_ID, participation:{enum:['active','historical']},inParty:{type:'boolean'},
      worldTime:{anyOf:[{type:'string',minLength:1,maxLength:160},{type:'null'}]},
      source:{type:'string',minLength:1,maxLength:300,pattern:'\\S'}
    }),
    async plan(ctx,args) {
      const row=await ctx.get('actor',args.actorId);
      if(!row || row.life!=='active' || row.actorId!==args.actorId)cloudFail(404,'ACTOR_UNAVAILABLE');
      if(args.participation==='historical' && (args.inParty || row.data.encounterId))cloudFail(409,'ACTIVE_MEMBERSHIP_DEPENDENCY');
      const previous=actorMembership(row.data);
      const membership={participation:args.participation,inParty:args.inParty,worldTime:args.worldTime,source:args.source};
      await ctx.put({...row,data:{...row.data,membership}});
      return {actorId:row.id,previous,membership};
    }
  },
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
