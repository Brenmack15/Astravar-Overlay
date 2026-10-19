# Astravar 2.1 code33 immutable native recheck

The original fresh code33 run passed all nine original native scenarios. Its typed scenario imported the fictional records and rendered Armor, Party identity, Equipment and Currency. After one successful companion navigation, header discovery searched downward while the companion title was above the viewport; it exhausted the original 30-second observation deadline. The captured companion body is correct, but the remaining typed views and typed rollback were not reached. These partial results do not establish complete native acceptance.

This correction adds only a read-only observer for the exact fictional companion header and replaces that single post-navigation observation. It refreshes accessibility state, requires one exact title inside the actual unique product ScrollView, and checks full viewport/display bounds and one second of stable geometry. Upward discovery uses the first half of the original shared 30-second deadline and downward discovery uses the remainder, with at most 18 gestures per direction and the original 0.6/default-speed gestures. A false scroll result is diagnostic. The observer never clicks, retries navigation or changes gameplay. Existing Equipment/Currency discovery, exact role and record assertions, fixtures, consent, import, backup and recovery checks remain unchanged.

The first immutable recheck, run `39307599371`, again passed all nine original scenarios and reached a verified companion frame. It then failed while discovering `Party source dossiers`: this source-entry control precedes the companion content, while the generic observer spent its original 30-second budget searching downward. No source-entry click was dispatched. The captured frame and accessibility hierarchy show the correct companion role and unknown statistics; the failure does not establish a product data defect or complete typed acceptance.

The next correction adds a dedicated opener restricted to `Armor source references`, `Party source dossiers` and `Inventory source archive`, and replaces only those three typed-scenario calls. Each opener uses the original shared 30-second budget, first 15 seconds upward and the remainder downward, at most 18 gestures per direction and the same 0.6/default speed. Fresh unique product/ScrollView observations must place an enabled clickable ancestor of at least 48 dp fully inside the viewport and display for one stable second before exactly one click. False scroll results are diagnostic. Existing companion and Equipment/Currency observers, other navigation, all exact content assertions, fixtures, import and rollback consent and gameplay restrictions remain unchanged. The failed recheck remains historical evidence; all eleven scenarios must pass together in the next run.

## Immutable inputs

| Input | Exact value |
| --- | --- |
| Product source | `f35e18c83b07cdb5dd4f73925d110abd68812390` |
| Product tree | `337f10402b53b08e87c7748da8f2b103e3e280f7` |
| Source run / job | `39301833455` / `118228773043` |
| Artifact ID | `12212558254` |
| Artifact size | `48,981,256` bytes |
| Artifact SHA-256 | `f41d163eb7597b267a2109b25d0981c3c4fbaf98d4760ea575d444b24e16d8cf` |
| CI code24 APK SHA-256 | `defb8d3b2680dfc261ee3d950fb2ecf6f91298116f402ecf5b5ef4caee6c83b2` |
| CI code33 APK SHA-256 | `0c2d9fa2ec613653ae428a1a3cddabaf961b3307c74b18936791e203241b2bac` |
| Disposable CI certificate SHA-256 | `3509b880d0214fc87aacbd3b78b796cccf7283395a93aacd32c7265c09aa1f54` |
| R8 mapping SHA-256 | `9ce9d379b3cc694a30ba5c6a77cf5d00579a8fdd4cf7a31cba64865c3439b903` |

The source run must be completed, its fresh baseline/current build and inspection step must have succeeded, and only its recorded native step may have failed. Artifact identity, source, tree, size, digest, expiry, both APK hashes, actual package/version/signature/artwork/alignment inspections and exact mapping are checked before reuse. An incomplete, expired or different artifact is rejected.

## Exact scope

The explicit `--profile code33` requires a clean committed descendant of the pinned source. Its final tree must differ in exactly these six paths, including additions, removals and modes:

- `overlay-target/src/androidTest/java/com/yazsras/astravar/CampaignImportInstrumentationTest.kt`
- `scripts/native_recheck.py`
- `scripts/tests/test_native_recheck.py`
- `.github/workflows/android-2.1-native-recheck33.yml`
- `.github/workflows/android-2.1.yml`
- `docs/ASTRAVAR_2_1_NATIVE_RECHECK_CODE33.md`

Every other application, rule, build, resource, schema, target, unit-test and runner source must remain identical to the product tree. The historical code31 default and code32 profile/workflow retain their original pins and scope. No code32 APK is reused for code33.

The exact subject `ci: run verified native code33 harness recheck`, without an extended body, triggers the new workflow. It reuses only the pinned code24/code33 product APKs and mapping, builds fresh target/instrumentation APKs, and runs all nine original scenarios plus both typed scenarios and independent SQL checks in one fresh emulator. Existing fixtures, 15 MiB attachment, record/relation counts, heap, order, limits and failure diagnostics remain in force. SDK attempts share the reviewed single 360-second installation budget.

The full workflow skips only the exact historical code32 and new code33 harness subjects, with separate concurrency groups for each. This protects an active product verification run from a skipped harness push. Normal development pushes and manual runs retain all rules, Android regressions, lint, optimized APK and native gates.

## Release boundary

The pinned product's full rules/unit/lint/optimized-build gate remains required. Compare its code24 and code33 APKs with the pinned source-native APKs: every non-signature ZIP entry, including provenance metadata, and the exact release R8 mapping must match. The new recheck must pass all eleven native scenarios and independent SQL/visual review; the original failed run remains historical evidence.

Only then may an owner candidate be signed. It must retain every non-signature entry of the pinned code33 APK, the original owner certificate and `com.yazsras.astravar` identity, version name `2.1.0` and code33. CI uses fictional data and a disposable signer. Owner keys, owner APKs and real campaign archives stay private and are never uploaded to CI. CI APKs are not phone deliverables.

Original-owner direct emulator update and physical Samsung S26 tests have not been executed. Their scope must not be inferred from CI-signer update tests. Real campaign import and authoritative cutover remain separate player decisions; Notion stays recoverable.
