# Native harness recheck

This workflow tests the exact immutable code24/code31 product APKs from source-native run `39254633105`. It assembles only the controlled test target and instrumentation. Full rules, Android regressions, lint and optimized-build checks remain a separate required gate. This recheck does not change the product, prove owner signing or establish physical Samsung coverage.

## Immutable product identity

The product source is commit `55a6b2db8065152606a421ca6e3f7a5b46da55c1`, tree `cfa8022812ba104db55baeaa725e0c9024d93e5e`. Artifact `12187774293`, named `Astravar-2.1-migration-native-source`, is exactly 43,312,032 bytes, SHA256 `39a5b21c1610ab18393dba0eaa0571cdc656e2aa2df4dc14e41d3e0649cfab18`.

| Product evidence | SHA256 |
| --- | --- |
| `Astravar-2.1-CI-ONLY-code24.apk` | `9894fc4a262d0fb77ad785b0ba2de952be8d59c46bd56348b8ecf192af9c9493` |
| `Astravar-2.1-CI-ONLY-code31.apk` | `00f3203df318a38b520bc41e58a728f8f262961d5ba2e2fb1ac325ff08c45b8d` |
| `app/build/outputs/mapping/release/mapping.txt` | `d01ace9fee7d2307779af4b24c1cce3e1e6346b707f36b59a9f8ad5079ab637f` |

Both APKs use package `com.yazsras.astravar`, version `2.1.0` and the same disposable CI certificate `d20336a85953ee13a5f8566755dc1a0653440a55d04d56e6aee8051bfd18d77e`. They cannot update an owner-signed installation. Never uninstall a player app to bypass a certificate mismatch. No owner key or real campaign data enters this workflow.

The helper checks current API metadata for the artifact and original run/job, the full ZIP size/digest, safe ZIP entries, source commit/tree and data/signing scope, both APK hashes, the exact R8 mapping, and freshly inspected package/version/certificate/artwork/16 KiB alignment. Expired, incomplete or mismatched artifacts fail closed. Original job `118066694474` must be complete with its product build/inspection step successful and its sole failure the recorded native step. A failed or incomplete product build cannot be reused.

## Recorded fixture failures

Original source-native run `39254633105` built and inspected both APKs successfully. Baseline overlay behavior, the populated code24 to code31 upgrade, native update readback and the updated notification stop passed. The following updated overlay recovery fixture stopped while searching for the enabled `Stop session` control on a long More page, before campaign import acceptance. Its screenshots, accessibility dump and logcat show the running app and an enabled offscreen control; repeated global lookup consumed the scroll budget with clipped or cached geometry. No product crash or memory failure appears in that run.

The fixture correction clears accessibility caches, observes fresh panel-scoped geometry and scrolls within the existing bound before one touch. The minimum target size, elapsed-time and scroll limits, permission recovery checks and resulting stop-state assertions remain required.

The separate full run `39254639530` stopped at a unit fixture assertion: its own coroutine persisted an empty element selection and then Arcane mode, while its wait observed only the first write. The test now waits for both fields in one persisted preference snapshot before retaining the original exact assertions. This document records the failures; it does not assert that the pending recheck or full rerun passed.

The following immutable harness run `39258925397` passed its baseline overlay and update fixture but stopped before campaign import at the ChatGPT prompt-search check. Its focused native field contained `Resum` after the fixture dispatched `input text Resume` once; the exact `Resume` readback correctly failed. The app remained alive and its resume prompt was visible. The one-line test correction uses the existing focused-field accessibility `SET_TEXT` helper, retaining the exact query, fresh equality assertion, copy/navigation checks and separate native keyboard-under-overlay tests. No product input behavior changes.

## Source guard and acceptance

The source guard requires a clean committed descendant of the pinned product source. Only these eight files may differ:

- `overlay-target/src/androidTest/java/com/yazsras/astravar/OverlayInstrumentationTest.kt`
- `overlay-target/src/androidTest/java/com/yazsras/astravar/UpdatePreservationInstrumentationTest.kt`
- `app/src/test/java/com/yazsras/astravar/ui/RebuildVisualAcceptanceTest.kt`
- `scripts/ci-player-first-emulator.sh`
- `.github/workflows/android-2.1-native-recheck.yml`
- `scripts/native_recheck.py`
- `scripts/tests/test_native_recheck.py`
- `docs/ASTRAVAR_2_1_NATIVE_RECHECK.md`

Every other change, including another instrumentation file, the controlled target Activity, application source/resources, Room schemas, rules, build configuration or readback helpers, invalidates this reuse path and requires fresh product verification. Unapproved deletions and mode changes, and all dirty/untracked source, fail the guard. Only the pinned APKs and mapping are copied; old failures and screenshots are excluded from the new acceptance result.

The runner preserves the original baseline overlay tests and populated code24 to code31 in-place upgrade with additive Room schema3 to schema4 migration. It then exercises the unchanged roughly 21 MiB fictional archive with 320 references and 2,225 relations through actual DocumentsUI: invalid integrity, interrupted preview, explicit consent, verified backup, import, independent SQL readback, duplicate prevention, a 15 MiB attachment save/hash readback, second backup with the retained archive, and full rollback. Its heap, fixture sizes, timeouts and assertions remain unchanged.

The two legacy postupdate overlay checks and their combat-history readback run after importer rollback. A failure there therefore cannot hide the primary backup/import/recovery result. All checks remain mandatory, including every seeded raw historical hash, old Shatter records and malformed history. Failure of any gate fails the workflow.

After full recovery, the typed campaign fixture tests Armor, Party, Inventory/equipment, wallet, proposed inventions/materials, Connections, checkpoints and Reference Library. It preserves unknown quantities/resources and acquisition history without gameplay actions. Supplemental independent SQLite reads compare every supplied typed column, identity, source body/hash and all preexisting table counts/hashes; rollback must restore the baseline while retaining application and rollback receipts.

Capture the typed baseline with `python3 scripts/native_recheck.py snapshot-mapped --database validation/campaign-rolled-back/astravar-campaign-ledger.db --output validation/campaign-mapped-before.json --synthetic-demo`. Verify import and recovery with `verify-mapped`, the copied database, baseline manifest and exported fictional fixture; recovery additionally requires `--rolled-back`.

Evidence identifies immutable product source separately from fresh test source and records original-run, full-check and native-recheck outcomes separately. Owner signing remains separate. The final branch push requires exact subject `ci: run verified native harness recheck`; no main merge, release publication or default-branch change is performed. The accepted full rules/regression/lint/build gate is run `39258933934` at test source `45ef89d6814c13802f138dd0f1cd890d2aa2e6a4`, completed successfully at `2026-10-19T13:47:05Z`; its whole workflow was canceled after that gate. Actual native acceptance remains a separate required result.

Full-verification code31 and immutable-product code31 have 162 non-signature APK entries. All 161 entries other than `META-INF/version-control-info.textproto` are byte-identical, including every DEX, manifest, resource and native library. The sole named exception is that 120-byte provenance text: replacing only immutable product revision `55a6b2db8065152606a421ca6e3f7a5b46da55c1` with full-verification test revision `45ef89d6814c13802f138dd0f1cd890d2aa2e6a4` produces the exact other textproto bytes. All remaining metadata is identical. Both builds have the exact R8 mapping SHA256 `d01ace9fee7d2307779af4b24c1cce3e1e6346b707f36b59a9f8ad5079ab637f`. The code24 baseline comparison has all 162 non-signature entries identical.

A private owner-signed code31 candidate must preserve all 162 non-signature entries of the exact immutable product APK from source `55a6b2db8065152606a421ca6e3f7a5b46da55c1`, including its original provenance text. Only signing information may change. The named full-build provenance exception permits no change to code, resources, manifests, native libraries or source, and relaxes no native acceptance gate. Any other payload difference fails verification. Later product changes invalidate reuse.


## Failed-run observability

On a failed run, the existing runner records diagnostics only after rechecking its captured `emulator-[0-9]+` serial and the device's emulator property. It resolves the exact product process before Java SIGQUIT and native-stack collection, then stops only that disposable product before copying its stable database/WAL/SHM files and the two fictional pre-import backup documents. ADB diagnostic commands have individual timeouts and a shared 90-second deadline, with a two-second forced-termination grace; final local file hashing is capped at ten seconds. Diagnostic errors cannot change the original exit status or waive any test. No owner phone, real campaign, network transfer, product source, fixture size, heap, assertion or test budget is changed.
