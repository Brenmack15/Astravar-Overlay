# Encounter frozen membership proof checkpoint — 12 November 2026

Base: `c018489d369e238081682693cfafcd43bc5dbb09` (isolated encounter/editor checkpoint).

## Correction

Scoped encounter history validation now checks each original membership receipt's request against its original outcome. A matching request hash alone does not establish that these two snapshots agree. Before this correction, a corrupted historical actor request with a recomputed request hash could be accepted by get/next/end/exact retry even though complete backup would reject that same actor receipt.

The pure historical helper applies the frozen creation, registration, acceptance and association invariants already used by complete authority validation, including original playable identity, accepted review fingerprint, modifier resolutions and preserved origin for acceptance. Duplicate JSON members are rejected. It reads no later actor state or campaign association and introduces no archive export or actor mutation.

## Prepared regression coverage

`GenesisEncounterMembershipProofTest` contains five explicitly Unit-returning Room tests. Four create valid encounter history through each supported membership kind, move the actor later, then alter only the old request and recompute its hash while leaving the recorded outcome unchanged. Reads, next/end and exact retry must reject without changing any saved domain or inserting a receipt. The fifth preserves a valid historical association after later reassignment, verifies original retry and turn/end ancestry, and restores the complete backup with both old participation and current association intact.

## Verification and continuation

**Untested source checkpoint.** Prepared through GitHub while the original execution environment was unavailable. No compilation, test execution, APK build, signing, installation or physical-device verification occurred.

Run these five tests with the existing thirteen encounter lifecycle tests, migration/backup regressions and required release gates before integrating the encounter milestone. This isolated child contains no C provenance patch, native scripts or UI changes. The root will deliberately assemble separately verified histories. PR #15 and existing branches/worktrees remain untouched.
