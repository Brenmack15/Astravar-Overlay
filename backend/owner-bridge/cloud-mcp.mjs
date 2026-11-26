import { cloudContinuitySettings } from './cloud-continuity.mjs';
import { cloudActions } from './cloud-actions-view.mjs';
import { cloudArchiveView } from './cloud-lifecycle.mjs';
import { cloudSpellbook } from './cloud-spellbook.mjs';
import { CLOUD_MEDIA_MCP_TOOL, stageCloudMcpImage } from './cloud-media.mjs';
import { cloudRecordView } from './cloud-records-query.mjs';
import { CLOUD_ID, CLOUD_ENVELOPE, CLOUD_REVISION, cloudObject, cloudMatches, cloudFail } from './cloud-validation.mjs';
import { cloudContext, cloudChanges, cloudRelationships } from './cloud-query.mjs';
import { cloudSheet } from './cloud-http.mjs';

const page=cloudObject({continuityId:CLOUD_ID,kind:CLOUD_ID,id:CLOUD_ID,after:CLOUD_ID,limit:{type:'integer',minimum:1,maximum:100},query:{type:'string',maxLength:120},atRevision:CLOUD_REVISION,atEpoch:CLOUD_REVISION,grantRevision:CLOUD_REVISION},['continuityId']);
const scope=cloudObject({continuityId:CLOUD_ID});
const entity=cloudObject({continuityId:CLOUD_ID,id:CLOUD_ID});
const cursorPage={after:{type:'string',minLength:1,maxLength:350},limit:{type:'integer',minimum:1,maximum:100},atRevision:CLOUD_REVISION,atEpoch:CLOUD_REVISION,grantRevision:CLOUD_REVISION};
const read=(name,description,inputSchema)=>({name,description,inputSchema,annotations:{readOnlyHint:true,destructiveHint:false,idempotentHint:true,openWorldHint:false}});
export const CLOUD_TOOLS=Object.freeze([
  CLOUD_MEDIA_MCP_TOOL,
  read('get_cloud_capabilities','Inspect authorized operations, exact argument schemas, scope and Auto-Sync settings for a cloud continuity.',scope),
  read('get_cloud_continuity_settings','Read the scoped campaign name, notes, source identity and recorded times.',cloudObject({continuityId:CLOUD_ID})),
  read('get_cloud_state','Read authoritative scoped records. Echo the epoch, revision and grantRevision on every subsequent page.',page),
  read('get_cloud_checkpoint_context','Read the latest known checkpoint, relevant actors, objectives and bounded changes since it.',cloudObject({continuityId:CLOUD_ID,actorId:CLOUD_ID,sinceRevision:CLOUD_REVISION},['continuityId'])),
  read('get_cloud_record_view','Read the scoped party inventory, wallets, timeline, item history or invention genealogy.',cloudObject({continuityId:CLOUD_ID,view:{enum:['inventory','wallets','timeline','item_history','genealogy']},id:CLOUD_ID,query:{type:'string',maxLength:120},after:{type:'string',maxLength:180},limit:{type:'integer',minimum:1,maximum:100},atRevision:CLOUD_REVISION,atEpoch:CLOUD_REVISION,grantRevision:CLOUD_REVISION},['continuityId','view'])),
  read('get_cloud_archive','Inspect the scoped record archive with explicit lifecycle permissions.',page),
  read('get_cloud_record_links','Page through visible canonical relationships for one entity at a fixed revision.',cloudObject({continuityId:CLOUD_ID,id:CLOUD_ID,...cursorPage},['continuityId','id'])),
  read('get_cloud_changes','Page through authorized changes since the checkpoint or a known revision, newest first.',cloudObject({continuityId:CLOUD_ID,actorId:CLOUD_ID,sinceRevision:CLOUD_REVISION,...cursorPage},['continuityId'])),
  read('get_cloud_actions','Inspect eligible source-linked actions, attack families, variants and costs for a scoped actor.',cloudObject({continuityId:CLOUD_ID,id:CLOUD_ID,after:CLOUD_ID,limit:{type:'integer',minimum:1,maximum:50},atRevision:CLOUD_REVISION,atEpoch:CLOUD_REVISION,grantRevision:CLOUD_REVISION},['continuityId','id'])),
  read('get_cloud_spellbook','Inspect casting grants, available slots and per-level previews for a scoped actor.',cloudObject({continuityId:CLOUD_ID,id:CLOUD_ID,after:CLOUD_ID,limit:{type:'integer',minimum:1,maximum:50},atRevision:CLOUD_REVISION,atEpoch:CLOUD_REVISION,grantRevision:CLOUD_REVISION},['continuityId','id'])),
  read('get_cloud_actor_sheet','Inspect calculated form, equipment, abilities, saves and passive modifiers for an authorized actor.',entity),
  read('get_cloud_receipt','Read a permanent command receipt or its current pending disposition. Independently read current state to verify.',cloudObject({continuityId:CLOUD_ID,commandId:CLOUD_ID})),
  {name:'submit_cloud_command',description:'Execute one operation listed by get_cloud_capabilities with its exact args schema. Bind the declared action, command ID, epoch, and expectedRevision. Pending owner review is not a committed result.',
    inputSchema:cloudObject({continuityId:CLOUD_ID,command:cloudObject({...CLOUD_ENVELOPE.properties,args:{type:'object',additionalProperties:true}},CLOUD_ENVELOPE.required)}),
    annotations:{readOnlyHint:false,destructiveHint:false,idempotentHint:true,openWorldHint:false}},
]);
export function cloudMcpArgs(tool,args) {
  if(tool.name!=='submit_cloud_command')return cloudMatches(args,tool.inputSchema);
  if(!args || typeof args!=='object'||Array.isArray(args)||Object.keys(args).length!==2||!cloudMatches(args.continuityId,CLOUD_ID)||!Object.hasOwn(args,'command'))return false;
  // The service resolves the exact schema by operation before reading or writing any entity.
  return args.command && typeof args.command==='object' && !Array.isArray(args.command);
}
export async function cloudMcpCall(tool,args,service,principal){
  const {continuityId,...opts}=args;
  switch(tool.name){
    case 'stage_cloud_image':return stageCloudMcpImage(service,principal,args);
    case 'get_cloud_capabilities':return service.capabilities(principal,continuityId);
    case 'get_cloud_continuity_settings':return cloudContinuitySettings(service,principal,continuityId);
    case 'get_cloud_state':return service.state(principal,continuityId,opts);
    case 'get_cloud_checkpoint_context':return cloudContext(service,principal,continuityId,opts);
    case 'get_cloud_record_view':{const {view,...options}=opts;return cloudRecordView(service,principal,continuityId,view,options);}
    case 'get_cloud_archive':return cloudArchiveView(service,principal,continuityId,opts);
    case 'get_cloud_record_links':return cloudRelationships(service,principal,continuityId,opts.id,opts);
    case 'get_cloud_changes':return cloudChanges(service,principal,continuityId,opts);
    case 'get_cloud_actions':return cloudActions(service,principal,continuityId,opts.id,opts);
    case 'get_cloud_spellbook':return cloudSpellbook(service,principal,continuityId,opts.id,opts);
    case 'get_cloud_actor_sheet':return cloudSheet(service,principal,continuityId,opts.id);
    case 'get_cloud_receipt':return service.receipt(principal,continuityId,opts.commandId);
    case 'submit_cloud_command':return service.submit(principal,continuityId,opts.command);
    default:cloudFail(404,'UNKNOWN_CLOUD_TOOL');
  }
}
