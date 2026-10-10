# Astravar 1.5.1 — bounded recent-roll history

- Fixed eager, unlimited history decoding and repeated copies of every previous roll inside accounting snapshots.
- Keep only the newest 20 combat entries in persistent storage; older rolls are automatically deleted. History remains available, newest first, with expandable individual dice.
- Room schema v3 streams legacy campaign JSON and prunes the event journal in SQL before observation. Inventory, payloads, resources, settings and both damage counts are preserved.
- Compact roll snapshots live once in the indexed `rolls` table. Insert triggers and atomic repository transactions enforce retention. Accounting observation reads metadata only; its Undo window is also bounded.
- Backup export contains at most 20 rolls; streaming import handles oversized legacy histories without building the whole historical object graph. Duplicate legacy accounting archives are discarded under the retention policy.
- Large 84d12, 105d12 and 210d12 results retain all individual faces and typed totals. Current combat results and the shared overlay state remain available.
- No combat rules changed: Primary Force remains d12, Discharge 3N, Shatter 21N, and each active d8 selector is added once to explosions.
- Existing package and owner signing identity retained; versionCode increases from 7 to 8.

Older history removed by retention is permanent, including through Undo. The newest retained dice expressions/results are not recalculated.
