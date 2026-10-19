# Native harness recheck

This workflow tests the exact immutable code24/code30 product APKs from source-native run `39241681704`. It assembles only the controlled test target and instrumentation. The full regression, rules, lint and optimized-build checks for product source `3197627aaf88420df986063cbe35e34ca058b972` remain a separate required gate; this recheck neither changes the product nor proves owner signing or physical Samsung coverage.

## Immutable product identity

The baseline product commit is `3197627aaf88420df986063cbe35e34ca058b972`, tree `a3a1b632bce71073ed65bf7852c5349a0638be8a`. Artifact `12182178961`, named `Astravar-2.1-migration-native-source`, is exactly 30,221,745 bytes, SHA256 `66c453933e540c6836babbf0c1725a573d4bfafbbf636b4d835cf5e07d7a6d56`.

| Product APK | SHA256 |
| --- | --- |
| `Astravar-2.1-CI-ONLY-code24.apk` | `77293b84946b773d5560a456dd9d5dd743238d48e86cede89a72d7eb0ea5ebd3` |
| `Astravar-2.1-CI-ONLY-code30.apk` | `f2fcb5a0bfc848f1efb4f2eb6f0e76eab1a9210ec21e85c1fae475f5f3b72125` |

Both APKs use package `com.yazsras.astravar`, version `2.1.0` and the same disposable CI certificate `f9e5c97b0b518519b35025d5c6d57a8424270f7935efad06d4f131a94295854f`. They are CI-only and cannot update an owner-signed installation. Never uninstall a player app to bypass a certificate mismatch. No owner key or real campaign data enters this workflow.

The helper checks current API metadata for the artifact and original run/job, the full ZIP size/digest, safe ZIP entries, source commit/tree and data/signing scope, both APK hashes, and freshly inspected package/version/certificate/artwork/16 KiB alignment. Expired, incomplete or mismatched artifacts fail closed. Original job `118024256685` must be complete with its product build/inspection step successful and its sole failure the recorded native step; an artifact from a failed or incomplete product build cannot be used.

## Recorded fixture failure

Original source-native run `39241681704` built and inspected both product APKs successfully, then stopped at `OverlayInstrumentationTest.aAutomaticRollingOutsideTouchKeyboardRotationAndProtection`, before campaign import acceptance. The secure-state button tap occurred at `11:17:15.374`, before Android applied the requested font scale 1.0 configuration at `11:17:15.850` and recreated the controlled target at `11:17:16.013`. The failure image shows the target reset and the healthy collapsed crystal.

The fixture correction waits for the newly resumed controlled Activity with the requested configuration and fresh target geometry stable for one second before dispatching its single touch. The protected-state text and overlay-removal assertions remain required. The original failure remains in the evidence; this document does not assert that the pending recheck passed.

## Source guard and acceptance

The source guard requires a clean committed descendant of the exact pinned product commit. Only these five files may differ:

- `overlay-target/src/androidTest/java/com/yazsras/astravar/OverlayInstrumentationTest.kt`
- `.github/workflows/android-2.1-native-recheck.yml`
- `scripts/native_recheck.py`
- `scripts/tests/test_native_recheck.py`
- `docs/ASTRAVAR_2_1_NATIVE_RECHECK.md`

Every other change, including another instrumentation file, the controlled target Activity, production source/resources, Room schemas, rules, build configuration, readback helpers or the existing native runner, requires fresh product verification. Deletions, mode changes and dirty/untracked source also fail the guard. The helper copies only the two pinned product APKs; old failures and screenshots are not copied into the new acceptance result.

The unchanged runner tests overlay behavior, automatic attacks, keyboard, orientation and font scaling, the populated in-place code24 to code30 upgrade and additive Room schema3 to schema4 migration. It then exercises the roughly 21 MiB fictional archive with 320 references and 2,225 relations through actual DocumentsUI: invalid integrity, interrupted preview, explicit consent, verified backup, import, independent SQL readback, duplicate prevention, a 15 MiB attachment save/hash readback and full rollback.

After that rollback, the typed campaign fixture tests Armor, Party, Inventory/equipment, wallet, proposed inventions/materials, Connections, checkpoints and Reference Library. It preserves unknown quantities/resources and acquisition history without gameplay actions. Supplemental independent SQLite reads compare every supplied typed column, identity, source body/hash and all preexisting table counts/hashes; rollback must restore the baseline while retaining application and rollback receipts.

Capture the typed baseline with `python3 scripts/native_recheck.py snapshot-mapped --database validation/campaign-rolled-back/astravar-campaign-ledger.db --output validation/campaign-mapped-before.json --synthetic-demo`. Verify import and recovery with `verify-mapped`, the copied database, baseline manifest and exported fictional fixture; recovery additionally requires `--rolled-back`.

Evidence identifies immutable product source separately from fresh test source, and records original-run, full-check and native-recheck outcomes separately. Owner signing and any private owner-signer update proof remain separate. The branch push job requires exact subject `ci: run verified native harness recheck`; no main merge, release publication or default-branch change is performed. Later product changes invalidate this reuse path.
