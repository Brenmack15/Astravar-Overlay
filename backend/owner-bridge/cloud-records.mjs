import { RECORD_DOMAIN_KINDS } from './cloud-records-contract.mjs';
import { HOLDING_OPERATIONS } from './cloud-holdings.mjs';
import { WORLD_OPERATIONS } from './cloud-world.mjs';
import { CHRONOLOGY_OPERATIONS } from './cloud-chronology.mjs';
import { CRAFT_OPERATIONS } from './cloud-crafting.mjs';

export const RECORD_KINDS = RECORD_DOMAIN_KINDS;
export const RECORD_OPERATIONS = Object.freeze({ ...HOLDING_OPERATIONS, ...WORLD_OPERATIONS, ...CRAFT_OPERATIONS, ...CHRONOLOGY_OPERATIONS });
