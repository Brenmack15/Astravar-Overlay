# Project Convergence engineering register

## Recovery baseline, 21 November 2026

The starting checkout is the unmerged head of PR 21. Default branch main was b4ec3513. The four open pull requests form a linear ancestry. Continue from the last commit without merging the earlier branches.

| PR | Head | Recovered work | Head CI at recovery |
| --- | --- | --- | --- |
| 18 | f90371c58bae | Campaign publication, authorization, native regression and CI | [success](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43794813994) |
| 19 | b8b61ac902c0 | Player wallet navigation and identity | [failure](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/44764810943) |
| 20 | 718a9e24bf85 | Import rollback, canonical handle, synchronization and upgrade fixes | [success](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/44925340242) |
| 21 | 1dc93395c9c7 | Damage defense JSON discriminator and round-trip regression | [run](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/44942768510) |

The full engineering audit AV-001 through AV-026 and the migration addendum were recovered. AV-006/014 concerns an annotation. AV-021/026 used the service-only test environment. Neither establishes a native equipment effect. The newer owner mirror was stale during recovery. No established gameplay is a disposable test fixture.

## Dependency order and acceptance

| Order | Implementation and authority decision | Required regression and evidence |
| --- | --- | --- |
| 1 | Use the application-scoped CampaignLedgerDb for playable saves, their projections, import, backup and gameplay transactions. Connection outbox and credentials retain their existing storage. Gate work on storage readiness. | Import public entry point, failure after writes, repeat, restart, unchanged grant and original profile. |
| 2 | Use Reforge and CharacterCalculator for approved actors. The existing character-ability projection stores effective values in score with magicModifier zero. It cannot independently establish the raw base. Expose distinct base, permanent and effective values and the native revision. | Base 21 remains 21 through held +1, deactivation, reopen, backup and restore. Propagate unknowns. |
| 3 | Target official skills by canonical key and custom skills by stable ID. Store real EffectDefinition and ItemInstance edits. Use the existing owner grant, CAS and immutable receipt path. | Specific skill, expertise, independent custom skill, stacking, expiry and removal. Verify changed calculation after independent readback. |
| 4 | Preserve Defense.damageType encoding. Exercise every damage type and each defense. Combat uses external resolved inputs and native per-component calculations. | Save/reopen and restore activated defenses. Multi-type bludgeoning/cold/acid, action/resource costs and final health. |
| 5 | Extend remaining operations for campaign-bound independent companions, items and placements, wallets, spells, resources, encounters and engineering. Enforce scope per affected authority. | Merchant, companion, combat, invention and session-resume scenarios A–E, denial, replay/collision, stale CAS, offline and late rollback. |
| 6 | Inventory source pages, linked databases and attachments privately. Keep revisions, provenance, conflicts and visibility. Preserve source-only records explicitly. | Per-source and category counts. Current actor binding, protected backup fingerprint, exact-run preview and reviewed conflicts before live application. |
| 7 | Verify complete Ubuntu/Android build, full release CI, upgrade fixture and owner signing against the prior artifact. Installation and real migration are distinct owner decisions. | SHA, package, version, signer, exact commit, emulator, then independently verified physical receipts and calculations. |

Unit tests, Room tests, service tests, emulator verification, physical verification, device receipts and fresh independent readbacks are distinct evidence. A release gate stays open when one of its required layers is missing. Private campaign source data, fixture exports from the owner's campaign, signing material and authorization are excluded from this repository.
