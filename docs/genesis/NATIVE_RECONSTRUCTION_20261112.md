# Native checkpoint reconstruction — 12 November 2026

This branch reconstructs the seven missing files described by [the earlier recovery handoff](NATIVE_ENCOUNTER_RECOVERY_20261112.md). These are **new, unverified equivalents**, not recovered original `991dd4a` blobs. Complete text and diffs for the original seven files were unavailable. The implementation follows retained task/history details and the current generated Room schema9.

- Branch: `checkpoint/astravar-genesis-native-reconstructed-20261112`.
- Exact parent: `a224a9d691fe97e871dc36395ad3f27e778e59ec`, the separate partial native recovery branch.
- Code checkpoint before this document: `5c3eda2782f1a8b9bc932783e8be5604050a6e77`.
- Inherited complete recovered files: native encounter class, encounter SQLite verifier, and `ci-emulator.sh`. Their provenance remains documented in the earlier handoff.
- Existing D/C publication branches, PR #15 and owner data/signing material were not modified.
- No local commands, Python execution, Gradle, CI dispatch, Android actions or signing occurred during this reconstruction. All recovery commit messages include `[skip ci]`.

## Reconstructed files

| File | Read-back GitHub blob |
| --- | --- |
| `scripts/genesis-connected-readback.py` | `fcf3c1c62cd63eaa3366e9073f11a206ebc9119e` |
| `scripts/genesis-crafting-readback.py` | `e6972b712d75ec3ef2a948a839ed425e369b0d5d` |
| `scripts/genesis-upgrade-readback.py` | `9baf3a50f40dc54099653b1efc86d0a61589051d` |
| `scripts/native-campaign-import-readback.py` | `43dd71e1f9521082a618d313ec99b105cc57540a` |
| `scripts/native_recheck.py` | `d03a1be1d2dd0cc65daa5d06a27bd0dbf2bfc1e7` |
| `scripts/tests/test_genesis_encounter_readback.py` | `b1bf4ddb31b45c163b9b0fa9fb43c10b54886d54` |
| `scripts/tests/test_native_campaign_import_readback.py` | `9f6e320cc5f0850fe51467478dec36436cfb0d24` |

GitHub file readbacks matched all seven submitted texts exactly. GitHub comparison against the parent shows exactly these seven code files; this confirms remote preservation and scope only. It does not validate Python syntax or behavior and does not establish equality with original991dd4a.

## Preserved behavior

Connected, crafting and upgrade helpers accept explicit schema8 or9 while retaining their historical default8. Upgrade baseline stays schema6. The unchanged raw-row, original-file, receipt/history and separate canonical-heal assertions still run. Schema9 upgrade must contain no invented encounters. Connected/crafting snapshots occur before encounter creation and require the new tables empty. Empty encounter lists may be omitted from a streamed complete backup, matching backend serialization.

Import helpers add the two encounter tables to canonical storage validation only for schema9. Existing normalized complete-table comparisons also protect their retained rows during imports and rollback. Campaign mapped export schemas remain4/5, historical native code31/32/33 pins remain unchanged, and native-recheck default stays4.

The new 17-case encounter host suite recreates the documented fictional hero/NPC six-operation history using actual exported schema9. It checks exact source child receipts/outcomes, manual initiative order, absence of duplicate turns, retained ended history, unchanged mechanics/ledger/history, schema6→9 cutover and canonical heal, omitted empty backup fields and both new table shapes. It is a reconstruction of intended coverage, with new formatting and implementation details; original test bytes are not claimed.

Schema9 native-import subclasses retain all existing schema8 classes and inherit their import/rollback assertions. Additional tests reject missing/malformed encounter tables, lost rows and conversion of unknown initiative to zero. Their table definitions come from generated9.json. The opaque retained fixture uses DRAFT/round0/null current actor and nullable initiative. These tests establish neither approved gameplay nor full backend receipt validity.

Unsupported-version sentinels move9→10 because9 is newly supported. The inherited mapped-schema8 assertion uses its fixture's actual `room_schema`, preserving schema8 behavior while allowing the schema9 subclass.

## Evidence boundary and next verification

The older handoff's222-test batch and final17-test run were observed against inaccessible local991 work, **not this reconstructed source**. Their historical result must not be reused as a pass for this branch. All checks below remain pending:

```sh
python3 -m unittest scripts/tests/test_genesis_encounter_readback.py scripts/tests/test_genesis_crafting_readback.py scripts/tests/test_genesis_connected_readback.py scripts/tests/test_genesis_upgrade_readback.py scripts/tests/test_native_campaign_import_readback.py scripts/tests/test_native_recheck.py -q
bash -n scripts/ci-emulator.sh
git diff --check
```

The import table-shape validator checks columns, affinities, nullability and primary keys; it does not itself validate foreign keys/indexes. Full generated-schema/migration checks and actual native upgrade/readback remain separate gates.

Before any CI dispatch, the coordinator must review and integrate the pending D product/test fixes, including the separately recovered route-readiness fix. The local ancestor `3856bb5` and other unpublished app/UI work are not recreated by these seven scripts. Compile the external native test, then run full exact-source regressions and the ordered native connected/crafting/encounter/upgrade/import journeys. Physical device and owner-compatible release acceptance remain pending.

If the original execution environment becomes available, recover and compare its commit and blobs before replacing any new equivalents. Do not reset, clean, overwrite or presume the old worktree was deleted. The user's standing implementation/testing/compatible-signing authorization persists under the original preservation and release boundaries.
