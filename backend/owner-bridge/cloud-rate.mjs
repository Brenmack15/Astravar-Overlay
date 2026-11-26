import { BridgeError } from './core.mjs';
import { cloudFail } from './cloud-validation.mjs';

const COST = Object.freeze({read:1, view:3, write:4, mediaRead:4, upload:16});
const views = new Set(['access','capabilities','records','archive','context','spellbook','actions','forms','sheet','links','pool-history','review-status','review-inbox','approvals']);
const mcpReads = new Set(['get_cloud_state','get_cloud_changes','get_cloud_receipt','get_cloud_continuity_settings']);

export function httpRateCost(method, action) {
  if (action === 'media') return method === 'GET' ? COST.mediaRead : COST.upload;
  if (method !== 'GET') return COST.write;
  return views.has(action) ? COST.view : COST.read;
}
export function mcpRateCost(name) {
  if (name === 'get_cloud_primary_image') return COST.mediaRead;
  if (name === 'stage_cloud_image') return COST.upload;
  if (name === 'submit_cloud_command') return COST.write;
  return mcpReads.has(name) ? COST.read : COST.view;
}
function setting(value, fallback, min, max) {
  if (value === undefined) return fallback;
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,5}$/.test(value)) cloudFail(503,'CLOUD_RATE_CONFIGURATION_INVALID');
  const parsed=Number(value);
  if (parsed < min || parsed > max) cloudFail(503,'CLOUD_RATE_CONFIGURATION_INVALID');
  return parsed;
}

// An indexed, conditional upsert is the admission decision. The theoretical
// arrival time represents spent credit. Idle time can refill at most one burst.
export async function chargeCloudRequest(service, principal, id, cost, enrolling=false) {
  if (!Object.values(COST).includes(cost)) cloudFail(503,'CLOUD_RATE_CONFIGURATION_INVALID');
  const capacity=setting(service.rateCapacity, service.isolated ? 4096 : 120, COST.upload, 4096);
  const interval=setting(service.rateRefillMs, service.isolated ? 25 : 500, 1, 60000);
  let subject;
  if (enrolling) {
    service.owner(principal,id);
    if (!service.isolated || !id.startsWith('qa-')) cloudFail(403,'ISOLATED_CONTINUITY_REQUIRED');
    id='*';
    subject='sites-owner';
  } else {
    // Unknown/foreign campaigns and inactive credentials cannot allocate buckets.
    const {grant}=await service.access(principal,id);
    subject=grant.subject;
  }
  const now=service.now(), charge=cost*interval, horizon=now+capacity*interval;
  if (!Number.isSafeInteger(now) || now<0 || !Number.isSafeInteger(horizon)) cloudFail(503,'CLOUD_CLOCK_UNAVAILABLE');
  const key=[service.ownerId,id,subject];
  const allowed=await service.s(`INSERT INTO cloud_rate_buckets(owner_id,continuity_id,subject,next_ms)
    VALUES(?,?,?,?) ON CONFLICT(owner_id,continuity_id,subject) DO UPDATE SET next_ms=max(next_ms,?)+?
    WHERE max(next_ms,?)+? <= ? RETURNING next_ms`,...key,now+charge,now,charge,now,charge,horizon).first();
  if (allowed) return;
  const row=await service.s('SELECT next_ms FROM cloud_rate_buckets WHERE owner_id=? AND continuity_id=? AND subject=?',...key).first();
  if (!row) cloudFail(503,'CLOUD_RATE_UNAVAILABLE');
  const retryAfterMs=Math.max(1,(row.next_ms-horizon)+charge);
  const error=new BridgeError(429,'CLOUD_RATE_LIMITED');
  error.retryAfterMs=retryAfterMs;
  throw error;
}
export function cloudRateResponse(error) {
  if (!(error instanceof BridgeError) || error.code !== 'CLOUD_RATE_LIMITED') return null;
  return {code:error.code,retryAfterMs:error.retryAfterMs,retryAfterSeconds:Math.ceil(error.retryAfterMs/1000),submission:'NOT_EXECUTED'};
}
