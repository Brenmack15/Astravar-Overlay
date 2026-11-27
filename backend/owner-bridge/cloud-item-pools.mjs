/** The first physical pool keeps its original wire field for existing holdings. */
export function physicalChargePools(data) {
  return data.resourcePoolId ? [data.resourcePoolId, ...(data.additionalResourcePoolIds ?? [])] : [];
}
