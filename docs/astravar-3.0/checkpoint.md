# Reforge continuation checkpoint

Updated 2026-11-07. A substantial native release candidate is implemented, owner-signed and tested as an isolated package update. Complete D&D Beyond parity, canonical campaign cutover and physical-phone acceptance remain open.

## Exact recovery point

- Repository: `Brenmack15/Astravar-Overlay`; branch `feat/astravar-3.0-reforge`.
- Draft [PR #9](https://github.com/Brenmack15/Astravar-Overlay/pull/9) targets `feat/astravar-dynamic-prompts`, baseline `852bbd48af9b6c70be5ce44d5f898d1224ca0e7c` (2.4.0/code 41). Earlier recovery/connection/conflict/prompt work is retained. `main` is not the usable application baseline.
- Tested APK product source: `30f0b9dda4e558246ecc481a4a97b23acf898cf0`, tree `4afff5814fe16612a93d682a22f13bf2a42fc60d`.
- Current verification source: `4732d87ebd066fd284f860a505bf62fdcb73ebbc`, tree `e757be634f537144f8360a44fe0190a7ed2c667b`. Later changes affect test readiness and queued test rendering; application/rules/schema/resource/build inputs remain byte-identical to the tested APK source.
- APK build/test input SHA-256: `8b2727f33b809e4b26eb9e945a3a45ce91d39f589c4c9e14dbf4e6a115330945`.
- Current verification input SHA-256: `67c785a6bb64a62556ca68d47475af582a83723f4af9efc19d17c5692cfaf920` across 308 files.
- Stable runtime/build input SHA-256: `79358ce8b963563997d775c27078da76c8bdabe7bb9539aa1fff9a5a718d96da` across 198 files. These 198 files were individually compared against the APK source and are unchanged.
- No live campaign records, production backend, original branch, protected merge or physical phone installation changed.

## Implemented and retained

- Pure typed/versioned rules engine: nullable abilities, advancement, explainable projections, all 18 skills and all six saving throws, equipment/attunement/composites, typed damage/defenses, conditions, explicit durations, spell/resource/rest/turn policies and initiated secure/manual dice.
- Separate local Room mechanics workspace: stable identities, revision guards, immutable receipts/history/rolls, guarded source-copy adoption, notes recovery/autosave, verified SAF backup and conflict-rejecting merge-only restore. Original stores/schemas/formats remain intact.
- Native sheet, compact Combat, structured equipment/spells/resources, identity/advancement, companion sheets and reviewed simple-attack conversion. Specialized armor/weapon tools remain accessible.
- Character/Combat/Inventory/Campaign/More navigation; safe rich text and Notion links; structured NPC profiles; global local search; compact ability editing and optional technical provenance.
- Overlay permission/component/normal controls retired. Runtime foreground startup, notification channel, receiver, permission watcher and polling removed. Even direct legacy lifecycle invocation stops immediately. Unused WorkManager foreground fallback is excluded from merged packages, while normal scheduling remains installed.
- Actual public D&D Beyond UI and supplied video studied; 38-capability parity matrix, 35 product and engineering findings, design/rules/migration reports and native before/after evidence documented.

## Executed verification

| Gate | Executed result |
|---|---|
| Pure rules |163 passed, including direct acceptance and independent adversarial cases |
| Python |147 passed after final manifest changes |
| Authenticated bridge backend |134 passed; no production deployment |
| Historical integrity |33 protected-source, 82 foundation, 9 historical-schema, 14 artwork and 10 particle checks passed; owner-signing block unchanged |
| Final selected Android regression |12 suites/56 cases per variant, all passed with no failures/errors/skips; debug 123.725s, release 135.412s; wall 10m 22s |
| Both lint variants |Zero errors; debug 63 warnings/1 hint, release 65 warnings/1 hint |
| Optimized original-owner build |Passed in 2m 44s; 54 tasks, 27 executed |
| Complete CI at APK source `30f0b9d` |Rules 163 passed; Android release 583 cases, 566 passed/17 skipped; debug 583 cases, 579 passed/1 failed/3 skipped. Sole failure was backup-test readiness, not a weakened product guard. |
| Final complete CI |[Run 42414763933](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/42414763933) passed at `4732d87`: rules 163 passed; debug 583 cases/580 passed/3 intentional skips; release 583 cases/566 passed/17 intentional skips; zero failures/errors. Both lints, package inspection and debug assembly passed; Gradle 36m 5s/all 105 tasks executed. |

The preceding full local run is retained as a failed run: debug 582/578 passed/1 failed/3 skipped; release 582/563 passed/2 failed/17 skipped, 36m 21s. Collapsed-source/diagnostic selectors were corrected. Release lint's actual foreground-service failure prompted runtime cleanup, not suppression or restored permissions. A cancelled intermediate focused run never executed tests and has no pass claim.

Gradle retained 73 unselected older XML suites in each filtered output directory. Their hashes were verified against the archived full run and they were moved outside current output before indexing. The selected 56-case results are not mislabeled as a new 583-case local full pass.

The uploaded CI failure at 30f was an independent byte-verification label appearing before the restore preview finished. The merge button correctly stayed disabled. Test b0db waits for the guarded button; test acf also waits for enabled notes input and separate draft cleanup. All independent persistence, conflict and recovery assertions remain.

## Test scheduler hardening

Complete CI at `acf936c` executed 583 debug cases: 580 passed, three skipped and no failures. Release had one inherited stale-craft test failure: 565 passed, one failed and 17 skipped. The uploaded XML identified Compose's test frame clock drawing on Room's IO thread through its default unconfined continuation, not a crafting-transaction or geometry assertion.

Pinned Compose 1.10.0/coroutines 1.10.2 bytecode and official APIs support a supplied queued StandardTestDispatcher. A shared test-only factory creates one fresh dispatcher per rule. All 39 empty-rule UI suites now use it; real asynchronous Room executors, test bodies and every assertion remain unchanged. Independent normalized diff audit verified all 39 bodies exactly. No dependency, product or schema changed.

A pilot of eight crafting, one backup and three notes cases passed 12 per variant (debug 29.109s, release 29.843s; wall 2m 21s). Broader execution passed 40 suites/191 cases per variant: 39 UI suites/162 cases plus 29 data.LiveHp cases selected by the shared basename. Debug passed all 191; release passed 189 with two intentional debug-only skips. There were no failures/errors; complete exact-head CI remains separate.

## Actual owner candidate

- Filename: `Astravar-3.0.0-rc1-code42-owner-signed.apk`.
- Permanent package `com.yazsras.astravar`; version `3.0.0-rc1`; versionCode 42; non-debuggable.
- Size 12,631,215 bytes; SHA-256 `d46907c39f729145d3b1169090812b054296180cdb47802eef7a0f811746679a`.
- Full original certificate SHA-256 `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`. Original private/public key pair and actual code 41 predecessor were independently verified; root repeated actual APK signature/ZIP verification.
- Actual APK checks passed: approved artwork, 16 KB native/ZIP alignment, retired Firebase components/resources/dex, overlay and unused foreground components/permissions.
- R8 mapping SHA-256 `2fd89761bf7c2664ce794ecf753562be76f8132e60f289f27d1977d5b2a91887`; compressed mapping is retained in private candidate evidence. Signing material remains outside Git/CI and distribution files.

## Actual isolated update and offline evidence

- Installed actual original-owner code 41 on persistent isolated AOSP 35, then used install-r with actual owner code 42. No uninstall, clear-data, wipe or downgrade. AppId 10148 is unchanged.
- After code 42 launched offline and was force-stopped, WAL-aware independent readback matched all four original stores, 50 tables and 22 fixture rows exactly, including schema, IDs, NULL/zero, inventory, resources, audit/history and artwork references. All three existing settings XMLs matched byte-for-byte. Only two expected AndroidX ProfileInstaller bookkeeping files changed. Root independently repeated the comparisons.
- Actual original-owner code 42 MainActivity had input focus with no error dialog in the retained clean PNG. Airplane mode enabled, Wi-Fi disabled and ConnectivityService reported no active default network. The image lacks a cellular phone service; mobile-data disabling was not claimed.
- Reviewed/adopted the fictional source through normal UI. Its STR 0 and INT 21 remain distinct from unknown abilities; currentHP remains unknown/max 66. Actual initiated INT check rolled 14+5=19 and was explicitly saved. Stopped DB readback found the exact faces, modifier, formula, ownership and immutable ROLL receipt. Character revision/HP were unchanged by rolling.
- After force-stop/relaunch, the selected actor and exact 19 history result reappeared. This is actual installed optimized-APK offline restart evidence, separate from Robolectric or a physical phone.
- A separate newly created fictional equipment character has known 8/15/14/21/11/12, level 9 and 40/66 HP. Native structured item entry, carried/equipped/unequipped/re-equipped transitions, derived STR roll and cold restart all passed. Effective STR 21/CON 16/AC 20 and 49/75 HP returned after relaunch, with permanent 8/14 retained and 26 lost HP. Exact STR face 19+5=24 and immutable receipt persisted; gameSeconds/turn stayed 0. Originals/settings still matched after every workflow.
- Software guest System UI initially displayed an ANR, then responded to Wait; no Astravar crash was attributed to it. This environment has no KVM or phone and cannot establish Samsung frame times, TalkBack, battery behavior or Android 17 acceptance.

## Candidate handoff

All local broader, readiness, complete CI, build, package, update and offline gates above passed. Preserve their distinct scopes and exact source associations; the emulator is stopped. The final CI artifact is 13591809354, SHA-256 `8d9994865acf049883509c81c99f7fbbb409891d070f9b90bf1138d23305cda8`; its raw JUnit, lint and package evidence are included in the private verification handoff. Original failed runs remain separately labeled.

The owner-signed APK and private verification/mapping archive accompany this candidate. Native UI evidence, reports and exact continuation are reviewable in draft PR #9. Documentation/image-only follow-up commits do not change the 308 verification inputs or 198 runtime inputs above. Production release publication, physical installation and any authoritative backend cutover remain separate owner actions.

## Product continuation after candidate review

Read `known-limitations.md` before claiming a complete replacement. Priorities are source-mechanics reconciliation, one inventory authority, full versioned progression/feature grants, accepted companion-form policies and a versioned private synchronization adapter for new mechanics. Prepare/test protocol work without production writes; any actual canonical cutover/deployment requires the appropriate separate approval. PDF import, richer media/document editing and remaining rules edges have concrete milestones there.

New mechanics/dice/notes remain explicitly LOCAL ONLY. Existing authenticated legacy sync is separate. Existing source inventory and new playable equipment are reviewed copies; do not silently mirror or declare them one authoritative state. Current campaign guidance 01/02/06/07 was consulted, but the owner mirror was stale; no latest HP/resources or transformations were guessed from old records.

## Durable implementation milestones

| Commit | Milestone |
|---|---|
|31535a418a7047b01946d9ffa26609be4193a568 |Research evidence and initial overlay retirement |
|60584828ebdcb772111770b90665f96e627dcc7b |Coordinated engine/data/gameplay/UX implementation |
|64132ff55a737b530769bd28124b0027b4a2641b |Recovery, contrast and session guards |
|4c87f46b5e6869b6a35b30130a1cda9612dff137 |Durable typed drafts and companion attack editing |
|30f0b9dda4e558246ecc481a4a97b23acf898cf0 |Final runtime/merged-permission cleanup; actual signed APK source |
|acf936c58064d6a8fcbfdcefa787c94e2dc37c10 |Final test synchronization; application source unchanged |

Queued UI test rendering is durably published at `4732d87ebd066fd284f860a505bf62fdcb73ebbc`; its 40-file change is test-only and preserves every existing test body/assertion.
