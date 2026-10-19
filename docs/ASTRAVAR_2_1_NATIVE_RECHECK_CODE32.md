# Astravar 2.1 code32 immutable native recheck

This recheck repairs native test observation of offscreen backup status, receipt controls and the archive picker control. It does not rebuild or change the application. The original code32 run produced a complete 22,272,693-byte second backup, equal to the stored snapshot, with SHA-256 `33ff09cf48c8c1389575969009e61217d1cff18931c7ef8e7ec0c563fdcefaf7`. At the failed observation the app was idle, the Save button was enabled, and the exact status was below the visible scroll area. These observations do not constitute completed native acceptance.

The test retains its exact text, package and visible-bounds assertions and original 180,000 ms absolute deadline. Fresh accessibility observations and bounded, read-only scrolling locate the status within that deadline. No fixture, app action, memory limit, timeout or acceptance assertion is removed.

The first corrected run observed the second verified backup and completed the separately confirmed secondary import. It stopped before a rollback click because the receipt title was visible at the bottom of the page while its rollback control remained below the viewport. A raw parent walk incorrectly reported that the receipt card was missing. The receipt helper now observes only the unique card with the requested title as a direct accessibility child, scrolls within its original 30-second and 18-per-direction observation limits, and requires one enabled, fully visible rollback control with stable geometry before one click. It never substitutes another receipt's control. Separate rollback consent, actual recovery success and independent database readback remain required.

The following run completed the separately confirmed secondary rollback and observed its recovery status. It then stopped before reopening the archive picker for the duplicate-import check: the page was at its bottom, while the chooser was above the viewport. The generic observer spent its original deadline scrolling down after reaching the bottom, without reversing. The chooser observation now refreshes accessibility state and reverses at a scroll edge within its original 30-second and 18-per-direction limits. It requires the unique enabled, visible chooser before one click; the real DocumentsUI selection and duplicate-import assertions remain unchanged. No import or rollback action is retried.

The chooser's initial empty page can fit without scrolling. Its actual accessibility `android.widget.ScrollView` then reports `scrollable=false`, despite containing a visible enabled 48-dp chooser. The observer identifies the unique product ScrollView by its class and package to measure that real viewport, and sends scroll gestures only when it is scrollable. It does not substitute screen-wide bounds. The same unique control, enabled state, minimum size, stable geometry, original deadline and single-click checks remain in force.

A subsequent baseline-code24 setup stopped before upgrade or import: Android explicitly rejected its single `ACTION_SET_TEXT` during an IME/window-add transition. The exact text readback correctly failed on the still-empty field. The failed artifact is retained. This fresh-emulator retry changes only this validation note; the fixture, shared observer, chooser, workflow, application and all eleven required native checks remain unchanged. No failed input is retried within a test and no assertion is waived.

The next unchanged-runtime run stopped during SDK setup, before product preparation or any native test: the official emulator download could not be opened as a ZIP. SDK package installation now permits at most two identical attempts within its original single 360-second timeout. Between failed and final attempts it removes only the incomplete `.temp` download cache inside that fresh job’s SDK, after verifying the exact SDK root. Command-line-tool checksum verification, SDK versions, step limits and all eleven native checks remain unchanged. Attempt diagnostics are retained after preparation or failure; no product or test action is retried.

After SDK setup passed on its first attempt, the next run passed the baseline overlay, original-editor fixture and update-preservation checks. Both fictional archive imports and their verified backups were intact in independent stopped-database readback. It stopped before the secondary rollback click because the exact receipt card continued below the viewport. Both read-only scroll calls returned false after a timeout with no accessibility scroll event; that result does not prove a scroll edge or that the gesture failed. The helper had incorrectly abandoned each direction after that single result. Receipt discovery now treats that Boolean only as diagnostics, compares freshly observed title/card geometry and allows up to the original 18 read-only gestures in each direction. Downward discovery uses the first half of the original 30-second global deadline, reserving the remainder for upward discovery if needed; an observed valid control can stabilize within the same global deadline. Gesture distance/speed, exact receipt identity and direct-card scope, title/action visibility, enabled 48-dp control, one-second stability and single review click remain required. Actual separate consent, recovery and independent readback are unchanged. The failed attempt is retained and supplies no complete native-acceptance claim.

## Immutable inputs

| Input | Exact value |
| --- | --- |
| Product source | `80e5dd2dc6645338ece59a7bb2c11cba0a3f7c06` |
| Product tree | `f957c7ac94f68d611cec02eaa85a3d8ed6456661` |
| Source workflow run / job | `39270984985` / `118122894843` |
| Artifact ID | `12196741246` |
| Artifact size | `44,824,691` bytes |
| Artifact SHA-256 | `6810561146a964a905b842c9cf64d5d2c85e8d20d47f7ee0559edae25a6d6132` |
| CI code24 APK SHA-256 | `978182dbc1c3938b05aaa6247b88ae4b0a6947b0f64b7fecf62a479c2fcde75e` |
| CI code32 APK SHA-256 | `7afff785ed1b722f37426ea1d9b9a753493b7acb5844b1add6074b9d0a6be7d4` |
| Disposable CI certificate SHA-256 | `50ea42fd9bf86bc444039ea8ff35aeb6ed1e75e4410fd65658dbaa4d5624717f` |
| R8 mapping SHA-256 | `942fd19dd90b10e1cd79c42722af858fe50e4b908403c9662aa4414e961caa1b` |

The source run must be completed, its baseline/current product build and inspection step must have succeeded, and its only failed step must be the recorded native acceptance step. Artifact ID, run, source, tree, size, digest, expiry, both APK hashes, actual APK inspection and exact R8 mapping are verified independently before reuse. An expired, incomplete or different build cannot be substituted.

## Exact scope and execution

The code32 profile requires a clean committed checkout descending from the product source. Its final tree must differ in exactly these six paths, including additions, removals and modes:

- `overlay-target/src/androidTest/java/com/yazsras/astravar/CampaignImportInstrumentationTest.kt`
- `scripts/native_recheck.py`
- `scripts/tests/test_native_recheck.py`
- `.github/workflows/android-2.1-native-recheck32.yml`
- `.github/workflows/android-2.1.yml`
- `docs/ASTRAVAR_2_1_NATIVE_RECHECK_CODE32.md`

All other production, build, resource, schema, rules, unit-test and runner sources must remain identical to the product tree. The historical code31 workflow and default profile retain their original pins and scope. Invoke this profile explicitly with `--profile code32`.

The exact commit subject `ci: run verified native code32 harness recheck`, with no extended body, triggers the new workflow. It downloads and reuses the exact original code24/code32 APKs and mapping, builds only the target and instrumentation APKs, and runs the unchanged emulator runner. All nine full native scenarios, both additional typed-library scenarios and independent SQL/readback checks remain required. The original large fixtures, heap, ordering, limits and failure diagnostics remain in force.

The full verification workflow skips only this exact harness subject. Its concurrency group is isolated for that subject so publication cannot cancel an active product verification run. Normal development pushes and manual verification retain the full rules, Android regression, lint, optimized APK and native gates.

## Release boundary

The original product-source full workflow's rules, unit, lint and optimized build evidence remains a separate required gate. Before signing, compare its code32 APK with the immutable source-native code32 APK: every non-signature ZIP entry, including provenance metadata, and the R8 mapping must match exactly. Independently inspect the actual package, version, artwork, alignment and signatures. The new recheck must pass the full native and typed acceptance suite; the original failed observation is preserved as historical evidence.

Owner signing is permitted only after those gates pass. The owner-signed candidate must preserve every non-signature ZIP entry of the immutable source-native APK and use the original owner certificate, package identity and code32. CI uses only fictional data and a disposable signer. No owner key, owner APK or real campaign archive is uploaded to CI. CI APKs are verification artifacts and must not be installed over the owner's application.

Native acceptance, original-owner signing and phone import remain pending until their corresponding evidence is complete. Notion remains recoverable and final authoritative cutover requires the player's confirmation.
