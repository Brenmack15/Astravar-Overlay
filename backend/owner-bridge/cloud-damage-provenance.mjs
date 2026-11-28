// Receipts written before damage-source classification intentionally keep unknown provenance.
export function groupDamage(damage, defenses, applies, must) {
  const groups = new Map();
  for (const part of damage) {
    const qualified = defenses.some(d => d.type === part.type && d.nonmagicalOnly);
    if (applies && qualified) must(typeof part.magical === 'boolean','DAMAGE_PROVENANCE_REQUIRED');
    const category = qualified ? part.magical : undefined;
    const key = JSON.stringify([part.type, category]);
    const group = groups.get(key) ?? {type:part.type, magical:category, rolled:0};
    group.rolled += Math.max(0,part.total);
    groups.set(key, group);
  }
  return [...groups.values()];
}
