# Genesis consolidation verification — November 14, 2026

This report belongs to the continued Project Genesis milestone. It preserves
source-specific results and does not replace the code50 release record or PR15.
It is maintained on a separate documentation branch so evidence updates do not
change the candidate under verification.

## Verified release and preservation boundary

- Verified release source: `65978f76f59a842657b16b5ce39ad8f9702ec5bb`.
- Equivalent local source: `998b1f2ff4073dc99df0c1d7c22b6795dd6a0233`.
- Identical Git tree: `1278449b533d5b21ba8bd1e9fdabf2a296fa99ad`.
- Product branch: `feat/astravar-project-genesis-consolidation-20261114`.
- Delivered baseline: `a86ab0fa87ce47a4f99550dcfa8fe01ed0e22928`, code50.
- Verified update artifact: `com.yazsras.astravar`, code51; original owner certificate
  SHA-256 `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
- Documentation branch: `docs/astravar-genesis2-verified-handoff-r8-20261114`, created from the current published source with only the four handoff documents copied onto it.
- PR15 remains open/draft at `7e229a43e4d66e91fa09f35ea39e1453387f38fe`.

Tests use fictional records; the owner campaign is not disposable test data. No key rotation, destructive
migration, uninstall, reset, force-push or original-PR modification is part of
this milestone. The original armor store remains retained evidence; reviewed
adoption gives current character gameplay one active transactional authority.

## Source publication and preservation

Direct Git push credentials were unavailable. The authenticated GitHub API
published all 51 logical checkpoints in order (34 initial, seventeen follow-ups) on the new continuation branch,
using each original tree and recording the corresponding local SHA in its
commit footer. The existing code50 baseline, repository history, old branches
and PR15 were retained. No force-push occurred. The original local commit graph
is retained in a Git bundle; the published history has an explicit
[local-to-published source map](CONSOLIDATION_SOURCE_MAP_20261114.json).

Every mapped local/published pair was independently checked with `git rev-parse`
and has the same tree. The final pair also passes `git diff --exit-code` with no
changes. This establishes source-tree equivalence, not APK byte equivalence;
compiled source metadata can differ and the delivered APK must be matched to the
published candidate actually exercised by CI.

- [Full CI43596209710](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43596209710): passed at the earlier `ecbab964` candidate; not a pass for the later header correction.
- [Native CI43596209715](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43596209715): failed; exact failures and corrective work are retained below.

The second candidate `38fa42b3` used these gates:

- [Full CI43600716673](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43600716673): cancelled by the superseding push after rules/debug passed; not a completed full gate.
- [Native CI43600716674](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43600716674): failed in the code49 seed fixture; observed cause retained below.

The historical 38-checkpoint bundle is
`Astravar-Genesis2-Original-Checkpoints-d464bf3.bundle`, SHA-256
`577b93680532f8a9066a88925c6f94547a3461091cacc2644a79e17c159f46d3`.
It accompanies the source map in the release evidence; it contains the original
local graph, not signing material or owner campaign data.

The third candidate `de2aac5c` used these gates:

- [Full CI43603173273](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43603173273): cancelled by a superseding source; not a completed full gate.
- [Native CI43603173246](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43603173246): failed in the frozen code49 options/rotation observation; exact failure retained below.
- Observer compilation at local `d464bf3` passed (`ime-postcondition-compile.log`).

The fourth candidate `364b6a24` used these gates:

- [Full CI43605731113](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43605731113): cancelled by the f597 source push after rules/debug passed; release/lint had been running, so this is not a completed full gate.
- [Native CI43605731117](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43605731117): cancelled by the f597 source push during Player First. Nine earlier cases passed: three baseline legacy cases, seed49, verify50, verify51, canonical HP write and two native SQLite cases. Cancellation is neither final acceptance nor an observed failure.

The cancelled fourth native artifact is ID14119625409, SHA-256
`0edb3e58c76de5291151d01b4e0ac062f35136f658d5ef8f2113bfc8ae2822e0`;
its log is retained as `native-fourth-cancelled-job.log`.

The fifth candidate `f5971e3b` used these exact-source gates:

- [Full CI43608137979](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43608137979): passed on the fifth candidate `f5971e3b`; exact artifact XML and lint reports were independently recounted below. This is not the current 659 full-gate result.
- [Native CI43608138002](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43608138002): failed at two bounded discovery observations; exact screenshots and corrective work are recorded below. Signing did not proceed on that candidate.
- All 45 local/published commit trees were independently rechecked after that publication.
- The retained 45-checkpoint bundle is `Astravar-Genesis2-Original-Checkpoints-9eee925.bundle`, SHA-256 `f7e8e16f56c7d1134db37a6d0e74e1d067194f45bb317dd5cc15950cf1d1659e`. It requires the pinned code50 baseline and accompanies the source map in the release evidence.

The sixth candidate `3770e669` adds only the three native observer files from
`03ae955`; its product tree is unchanged from f597. It required complete
exact-source gates, but was superseded:

- [Full CI43613049290](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43613049290): cancelled during the build by the final observer-batch publication; no completed gate.
- [Native CI43613049294](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43613049294): cancelled during the build by the final observer-batch publication; no new native acceptance.
- All 46 local/published trees were independently verified.
- The 46-checkpoint bundle is `Astravar-Genesis2-Original-Checkpoints-03ae955.bundle`, SHA-256 `1342f30aa08f3ad7bc4a0fbedd043e6a639b575453596795a1c1d0307b08a61e`.

The seventh candidate `48f7575e` includes the complete bounded observer batch:

- [Full CI43613698288](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43613698288): passed on 48f; raw artifact XML, source metadata and lint were independently checked.
- [Native CI43613698316](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43613698316): failed late in the final update/large-font capture after 16 native cases passed; exact evidence and correction are recorded below. Imports did not run.
- All 47 local/published trees were independently verified. The working source is frozen; documentation updates are separate.
- The original 47-checkpoint bundle is `Astravar-Genesis2-Original-Checkpoints-23511a3.bundle`, SHA-256 `e337ae366a7be0298f0883224d8068aacccdd83a0c9aecfb62fcc79d3147d48d`.

The eighth candidate `65978f76` includes the responsive element summary and
full-marker native observation:

- [Full CI43620647220](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43620647220): passed on 65978f; raw artifact XML, source metadata and lint were independently verified.
- [Native CI43620647219](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43620647219): passed: 21 native cases across 18 successful invocations. Raw artifact, database receipts, source metadata and screenshots were independently verified.
- All 51 local/published checkpoint trees were independently verified; the final pair has zero diff.
- The current 51-checkpoint recovery bundle is `Astravar-Genesis2-Original-Checkpoints-998b1f2.bundle`, SHA-256 `4718ac8d3bff0b8cdb407c99c5b9ff676f92794274030013ffb3832f70dfe030`.

## Current 659 full regression

Full CI43620647220 passed on source
`65978f76f59a842657b16b5ce39ad8f9702ec5bb`, tree
`1278449b533d5b21ba8bd1e9fdabf2a296fa99ad`, with `dirty_worktree=false`.
Artifact14125885867 has verified SHA-256
`708c36d41ee23eb4366e82d5cfffdd3950c656c49762923d160ee918d8faa644`.
Raw JUnit XML and source metadata were independently checked.

| Gate on 659 | Actual result |
| --- | --- |
| Rules | 186 passed; zero skips, failures or errors |
| Debug application | 975 passed, 3 skipped; 978 total, zero failures/errors |
| Release application | 961 passed, 17 skipped; 978 total, zero failures/errors |
| Python / bridge | 328 / 134 passed |
| Debug lint | Zero errors; 81 warnings and 1 hint |
| Release lint | Zero errors; 83 warnings and 1 hint |
| QA APK assembly and inspection | Passed; this QA APK is not the owner delivery |

The three shared skips require optional live remote-stat HTTPS, sandbox HTTPS
or a private campaign-package fixture. Release adds fourteen debug-only tests,
all of which passed in debug. Full regression is complete on 659. Native CI43620647219 and original-owner artifact verification also passed,
with the installation boundary recorded below.

## Current 659 native acceptance

Native CI43620647219 passed all 21 cases across 18 successful invocations.
Artifact14126147168 has verified SHA-256
`2c14dc06707b7b7126507825bef62d39645c00c404f495f99e6861f29b0ac0f8`.
Independent review confirmed the stage ledger, source identity, database and
receipt proofs, and native screenshots.

- Test-signed49→50→51 upgrades preserve UID and first-install identity. Three
  install/reinstall difference files are empty. All three stores, source bytes,
  character revisions and prior history/receipts survive the verified cutover.
  A later canonical write survives restart while original source records remain
  unchanged.
- Consolidation exercises armor adoption, real automatic rolls, one-shot
  advantage, element/reservoir restart, Features, equipment favorites, custom
  skill CRUD, alphabetical pins, physical-dice input, retained roll history and
  same-key update readback.
- Genesis creation/navigation, companion identity, drag/tap alternatives,
  rotation, 200-percent text, actual SAF protected complete-backup merge, crafting
  and manual encounter workflows pass. Independent backup/crafting/encounter
  readbacks verify saved outcomes and retained prior mechanics.
- All four archive import/recovery cases pass. Final screenshots show the full
  Active element label/action at 200-percent text and the companion name in
  large-text landscape; the pixel and viewport checks remain strict.

These are fictional API 36 emulator records. They do not certify current Zerg
phone data, owner-key installation or Galaxy S26/Android 17 physical behavior.
The source-attack import feature has focused disk-reopen/playable-backup and
Compose coverage; successful native source-attack import is not claimed.

## Seventh-candidate48f full regression

Full CI43613698288 passed on source
`48f7575e58cdf14654f5083104a57b09a99a8b68`, tree
`592c49c67d35e05c7432ab45e3035f14a251e288`, with `dirty_worktree=false`.
Artifact14122539554 has independently verified SHA-256
`b6d8c2ee55e8eeb3cb8f9ee0d09fa46a3cfcec1e96905b30441a1e275022d41d`.
Raw XML and lint reports were independently recounted, and the job log confirms
Python and bridge execution.

| Gate on 48f | Actual result |
| --- | --- |
| Rules | 186 passed; zero skips, failures or errors |
| Debug application | 972 passed, 3 skipped; 975 total, zero failures/errors |
| Release application | 958 passed, 17 skipped; 975 total, zero failures/errors |
| Python / bridge | 328 / 134 passed |
| Debug lint | Zero errors; 81 warnings and 1 hint |
| Release lint | Zero errors; 83 warnings and 1 hint |
| QA APK assembly and inspection | Passed; this QA APK is not the owner delivery |

The three shared skips require optional live remote-stat HTTPS, sandbox HTTPS
or a private campaign-package fixture. Release additionally skips fourteen
debug-only remote-stat tests, all of which passed in debug. Host regression is complete on 48f; its late native failure is retained below.
The later 659 full/native gates and owner-artifact proof passed independently, as recorded above and below.

## Fifth-candidate full regression

Full run43608137979 passed on `f5971e3b421629b2a51c1f052893d56fc1b64199`.
Its artifact14120208284 has SHA-256
`d93a758229f1f5cc6b64b2546cd70fed2f5dd519742d74a83ec6fcf0632f01b3`.
The downloaded artifact checksum was verified and its raw JUnit XML was
independently recounted.

| Gate on f597 | Actual result |
| --- | --- |
| Rules | 186 passed; zero skips, failures or errors |
| Debug application | 972 passed, 3 skipped, zero failures/errors; 975 total |
| Release application | 958 passed, 17 skipped, zero failures/errors; 975 total |
| Python | 328 passed |
| Bridge | 134 passed |
| Debug lint | Zero errors; 81 warnings and 1 hint |
| Release lint | Zero errors; 83 warnings and 1 hint |
| QA APK assembly and inspection | Passed in the full gate; not the original-owner delivery artifact |

The three shared skips are opt-in live remote-stat HTTPS, sandbox HTTPS and
private campaign-package preview fixtures. Release adds fourteen debug-only
remote-stat tests; all fourteen passed in debug. The exact skip names, raw XML,
`full-final-summary.json`, `reforge-evidence.json` and `full-final-job.log` remain
in the verification evidence. These are host results for f597. Later 48f and 659 gates
and original-owner artifact verification are recorded separately; these historical
results are not relabeled as current.

## Historical local verification

Complete local tests below belong to `e00cb749` (published `ecbab964`, tree
`ecd17459190d04443dc6282fe67ed2e2b83ffc5a`). The current candidate adds the portrait
header and native fixture corrections, including observed keyboard-dismissal
acceptance after the second native failure. Its targeted header/navigation batch
passes23/23 and native observer compilation passes. The fifth-candidate f597
full CI above also passed. Earlier local results are retained, not relabeled as
current 659 verification.

| Gate | Result | Scope |
| --- | --- | --- |
| Rules before header correction | 186 passed, zero skipped/failures/errors | JUnit XML independently counted; includes retained engine tests and new skill/armor adapter cases |
| Armor-focused rerun before header correction | 21 passed, zero skipped/failures/errors | Ownership2, canonical sync6, armor Features UI4, armor Combat UI2, reservoir repository2, reservoir UI5 |
| Python checks | 328 passed | `release-python.log`, five static/preservation guards also passed |
| Native observer compilation | Passed | Kotlin compilation only; does not prove emulator interactions |
| Earlier broad focused application pass | 64 of65 passed before final correction | Evolving-worktree result; the remaining real revision-order defect is fixed and covered by the six final sync cases |
| Local complete debug regression | 962 passed, 3 gated skips, zero failures (965 total) | Skips: live remote-stat HTTPS, live sandbox HTTPS and private campaign-package preview |
| Local complete release regression | 948 passed, 17 gated skips, zero failures (965 total) | Three live/private opt-ins plus14 debug-only remote-stat cases; those14 passed debug |
| Local lint and QA APK assembly | Passed; zero lint errors | Debug67 warnings +1 hint; release71 warnings +1 hint. QA package/alignment/branding/overlay-retirement inspection passed; QA APK is not the owner delivery |
| Corrected header/navigation local batch | 23 passed, zero skips/failures | Local source `2fd11f6`; native observer Kotlin also compiled |
| Current exact-source CI | Complete: full and native passed on 659 | Earlier candidate results remain historical |
| First native Android run | Failed at two UI reachability checks | Earlier baseline/upgrade/SQLite/player/visual stages passed; dependent crafting/encounter/final-update stages did not run |
| Original-owner artifact proof | Complete | Production package/version and original owner certificate verified; all 210 CI payload entries match, checksum recorded below; no owner-key installation is claimed |
| Empirical upgrade proof | Complete within tested scope | Actual49→50→51 replacement, retained records and later canonical write passed using a disposable test signer; separate from owner signature proof |

The first native compile failed on four observer API arguments, then passed
after correction. The first focused rules/application runs retained their
failures in [the audit](CONSOLIDATION_AUDIT_20261114.md). The second pass exposed
a genuine revision-order defect: preserved plans from armor revision200 were
higher than a reset runtime revision2. The fix keeps a monotonic revision offset
from the original source; it never rewrites old plans or relaxes `BackupCodec`.
Further tests preserve known100-slot balances and a configured third attack
without violating the old engine's smaller transient bounds. Unknown and known
zero slot counts remain distinct in scoped canonical readback.

Local XML/log counts were independently read. The complete local command is
`python3 <runtime>/run-gradle.py --continue :rules:test :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug -PastravarScreenshots=<runtime>/screenshots`.
The runtime prefix is the local absolute workspace path, abbreviated here.
`release-full.log` and copied raw XML under `evidence/full-local` retain the
complete debug/release results without overwriting one variant with the other.
 Those logs live in the transient
`astravar-runtime` directory; publish exact CI/artifact evidence below when it
exists. Do not infer release acceptance from the local compilation or focused
subset alone.

## First native run: observed failures and correction

The artifact for native run43596209715 is `android-verification`, ID14115992019,
92,267,722 bytes, SHA-256
`ac307ae6fbdc8ff7adc78ac45698c642d629a26f614256ed5b0ec2d072a3aa09`.
The archive hash, raw stage logs and failure accessibility trees were inspected.
This is retained failed evidence, not native acceptance.

- Consolidation reached real armor adoption/rolls, element and reservoir restart,
  Features and Equipment favorites. After saving a custom skill, Sheet restored
  its reading position with the Skills heading clipped at the bottom. The test
  attempted a pin without first scrolling to the new row. The correction
  discovers Alchemy and Arcana before touching their real pin controls and
  compares alphabetical positions from the same fresh viewport.
- The normal portrait character header had no Character controls trigger after
  the prominent quick-action row was removed. The trigger existed only in the
  compact header. This is a genuine product reachability omission; a48dp normal
  header trigger and focused regression were added in `a018da8` and the focused batch passes. The native test remains
  in normal portrait and retains quick-action customization coverage.
- Earlier code49 legacy rolls, code49→50→51 retained-record checks, subsequent
  canonical write, native SQLite checks, Player First and Visual Rebirth stages
  passed. Crafting, encounter and final update/backup-dependent acceptance did
  not run; they must not inherit green status from the earlier stages.

A separate historical d12 investigation reproduced the recorded IME fixture
failure from artifact13966251242 (SHA-256
`801aa5253f6aad3fc18f16e8cacd97414d31480e47006fc48df20689d7f343f4`).
Its XML contains exact text `Fictional Manual tracker without mechanical changes`
while the test entered lowercase `manual`; the error occurred in immediate
readback before keyboard dismissal. Corrective test checkpoint `cf492e6` uses
one accessible SET_TEXT edit only after the native IME is closed and field
text/bounds are stable, then requires exact text and reopens the keyboard for
unchanged dismissal/geometry assertions. Dedicated native keyboard-input tests
remain unchanged. No text is normalized and all exact persistence assertions
remain. A Kotlin lambda-shadowing compile error was corrected in `2fd11f6`; native observer compilation now passes. A new native run must verify the runtime behavior.

## Second native run: keyboard outcome versus acknowledgment

Native run43600716674 passed the three retained code49 legacy weapon tests, then
stopped during the code49 seed fixture. The new input helper asserted the Boolean
return from `UiDevice.pressBack()`. That return was false, but the captured XML
and screenshot prove the keyboard had dismissed and the intended HP field was
still focused in the same editor. No Gboard window/package remained. The
artifact SHA-256 is
`b0101793297699dafa6ca32429ad6f9b4f9f0d6e4aef0b41429ff37b79e6a44c`.

Isolated fixture correction `d464bf3` dispatches Back once and lets the existing
mandatory observation of closed IME and stable focused field determine success.
It removes no exact text, UI, persistence or database assertion, does not retry
the key, and changes no product code. Native observer compilation passes; a fresh
full/native exact-source run must still validate runtime behavior. The fresh49/50/51 build-and-test
sequence remains required; no earlier APK or signature provenance is reused.
The second failed run never reached candidate acceptance and is not an upgrade
pass.

## Third native run: committed options before rotation

The third native run43603173246 failed while looking for the exact saved normal
attack formula after rotating the frozen code49 screen. Inspection of the
pre-cleanup screenshot and XML shows the real Attack options modal still open,
with Distance60 and Save visible; it was covering the saved result. The natural20
face was incidental: the subsequent force-stop/history test found the same exact
raw roll, and the other two legacy cases passed. Artifact14117544216 has SHA-256
`2ac6451254c02fb91aaaf708926b4b0deb65117ec01789f7a36d97c470e11503`.

Correction `bc687f8` requires exact typed distance and settled closed-IME geometry,
then taps Save once and waits for the actual options modal to close and expose
the same raw formula before rotation. The exact post-rotation, restart and
history checks remain. Follow-ups `36c9ae6` and `4bbe76c` apply the same observed
layout-readiness requirement to the shared post-Back exact-value assertion and
the two direct search/document-picker call sites. They introduce no new gestures,
fixed sleeps, action retries, case normalization or dice expectations. Dedicated
native keyboard input remains active; source mechanics and the frozen release
are unchanged by these test corrections. Fresh exact-source verification is
required; earlier acceptance is not inherited.

## Fifth native run: discover known upper content before lower-page traversal

Native run43608138002 passed the earlier upgrade and player journeys but failed
at two observations. Artifact14119744325 has SHA-256
`e6194dc0c18d292bf8e7a46e1021eab7bfb06f369681e8b5d6bca37ae4260658`.
Its failure screenshots and accessibility trees were independently inspected.

- After pinning skills, the default downward search for `Pinned · A–Z` traversed
  the lower skill list before reversing. Its 30-second budget expired after only
  one upward swipe; the failure screenshot shows Perception through Survival,
  below the expected upper pinned group.
- After creating `Genesis Native Familiar`, the test similarly searched down
  before reversing and exhausted its budget after two upward swipes. The failure
  screenshot and XML already contain the exact saved name at
  `[284,793][1038,956]` on its companion sheet, with no creation error or open
  editor. The save succeeded; the bounded discovery observation failed.

Test-only correction `03ae955` discovers the known upper pinned heading upward
first and then reads its rows. It also verifies each actual Unpin control after
its single pin tap, and uses the same group anchor for alphabetical-order,
rename, restart and update assertions. Companion creation waits for the exact
visible saved name through a fresh read-only snapshot before any discovery
scroll. No product code or generic observer changed; all existing exact-value,
order, restart, database and dice assertions remain, with unchanged 30-second
budgets and no repeated product actions. Observer Kotlin compilation and all five preservation guards passed at
`03ae955`; new exact-source full/native gates must still pass before acceptance. The f597 full result remains
valid for that recorded source and is not relabeled as the corrective source.

A bounded read-only inspection of the remaining native journeys found the same
specific observation pattern at other known upper controls and asynchronous
replacement headers. Follow-up `23511a3` reveals Add skill and Encounters upward,
observes saved encounter phases and the saved craft receipt without scrolling,
and requires the actual companion-sheet context rather than a matching name in
its creation preview. Skill removal also waits for the editor to close after
`local.save` before force-stop. Existing exact assertions and 30-second budgets
remain; no generic observer or product code changed. Campaign Import already
uses stationary/directional observations and was left unchanged. The follow-up compiled and passed all five preservation guards; fresh
exact-source verification remains required. This follow-up was proactive verification hardening from concrete call-site review, not a
claimed additional runtime failure.

## Seventh native run: real200-percent layout defect and clipped capture

Native run43613698316 passed 16 cases and failed one late update/visual case.
Artifact14123265855 has SHA-256
`40f94c8e17cc2891c9789f8a25ebabf25a96ebe366984212bf9ba8096bb2b2b9`.
The full consolidation, Genesis creation/navigation, crafting and encounter
journeys passed, as did the earlier upgrade, large-campaign and player flows.
Independent connected-backup, crafting and encounter database/receipt readbacks
also report PASS. The typed import stages did not run after the failure.

The failed update test had already read back retained armor rolls, pins,
reservoir, equipment and companion records after reinstall. At200-percent font
scale, its Combat capture found `Change element` with a seven-pixel-high clipped
rectangle at the viewport bottom and zero foreground pixels. The unchanged
pixel gate correctly refused that screenshot. The actual PNG also shows a real
responsive defect: the fixed medallion and wide inline button squeezed the
`Active element` label into a narrow column that wrapped fragments vertically.

Product correction `7dcb955` places Change element on a separate full-width
row when space/font scale requires it, retaining the artwork and full label.
Test-only correction `2fef3cc` uses fresh viewport/marker rectangles and bounded
quarter-viewport scrolls before capture, requiring the full armor summary/action
or companion marker inside the visible viewport. It retains both armor markers,
selected navigation, exact text, foreground-pixel validation, one-second frame
stability and the30-second budget; no product action or saved value is retried.
The first focused batch passed the two existing Combat UI cases and failed all
three new responsive assertions; a three-case diagnostic rerun also failed.
The retained XML/PNGs showed complete visible labels: the Compose paragraph's
allocated width differed from the visible text node, making the coarse
`hasVisualOverflow` assertion unsuitable. Correction `998b1f2` validates actual
rendered line left/right/top/bottom against the node, the complete text end,
one line, no ellipsis and explicit font scale; it retains artwork,48dp,
callback and layout checks. The corrected three-case run passed with no skips at `998b1f2`; the two
adjacent Combat UI cases had already passed on identical production code.
Observer Kotlin compilation and all five preservation guards also passed. Fresh
exact-source full/native release gates remain required. This late failure is not a completed native release
or an owner-signed artifact claim.

## Milestone acceptance

“Complete” below means the described consolidation slice is implemented and
verified. It does not mark the entire original Genesis roadmap complete.

| Owner request / roadmap slice | Status | Verified behavior and limits |
| --- | --- | --- |
| Legacy navigation and shortcuts | Complete | Redundant ordinary Tools entries and the primary yellow shortcut row are removed; tabs, swipe, Back and optional Character controls remain accessible |
| Combat and Active Element | Complete within configured scope | Accepted engine, current character modifiers/resources, real dice, atomic costs/history, element selection and retained configuration work inline; current Zerg phone configuration is not certified |
| Existing source attacks | Complete for supported reviewed imports | Fixed recorded totals and provenance, duplicate/stale safeguards, disk reopen and playable backup restore verified; unresolved costs, mixed formulas and custom critical mechanics remain visibly review-required |
| Spells and reservoir | Complete within migrated scope | Shared slots, loaded capacity, payload search/create/edit, explicit spell-template relationships and source consumption; no automatic name-based merging or new complete class compendium |
| Features and armor notes | Complete | Reviewed armor upgrades/configuration, original artwork and editable armor notes retained; no invented upgraded Discharge formula or charged damage type |
| Resources and History | Complete within existing mechanics | Canonical HP/slots/actions, rests, elapsed-time/nexus recovery and historical/current activity; no new universal accounting-undo UI |
| Custom and pinned skills | Complete | CRUD, training/expertise/bonus/notes/override, stable identities, pinned A–Z then unpinned A–Z, rolls, restart, backup and update checks pass |
| Deferred slice C: Equipment favorites | Complete | Accessible favorite toggle, filter, unfavorite/refavorite, restart and update verified; broader containers/reorder remain partial |
| Canonical armor connection readback | Complete within existing scope | Selected-campaign ownership, current character config/recent rolls, monotonic revisions and explicit unknown/unavailable values; no backend deployment or expanded write scope |
| Backup/source preservation | Complete for migrated character fields; broader coverage partial | New skills/armor state and complete connected backup workflows verified; original snapshots/history retained. Settings, connection setup, external portraits and some drafts remain outside the format;64MiB cap remains |
| Code51 artifact | Complete within verification boundary | Original-owner signature and unchanged tested payload verified; disposable-signer upgrade passed. No owner-key or physical-device installation claim |
| Remaining Genesis breadth | Partial/unimplemented | Guided class/subclass/multiclass progression, general containers, broad crafting/encounter automation and remaining historical-editor parity stay in the recovered roadmap |

The full original backlog remains in
[POST_APK_ROADMAP.md](POST_APK_ROADMAP.md); successful existing implementations
are preserved rather than restarted.

### Source-attack fixture boundary

A read-only audit of the native fixtures found no existing source attacks to
import. The code49 seed does not create attacks; the Player First companion
adoption has an empty source attack list; both decoded mapped campaign sheets
contain `attacks=[]`; and the large-campaign native database test creates
profiles and abilities without a source sheet. The typed import journey also
intentionally verifies source-only reads. Therefore its unchanged passage does
not prove a successful source-attack import. Checkpoint `59829c5` supplies five focused fictional production repository cases
and one Compose review case. The first focused execution passed all 186 rules,
the review UI case and four of the five source-attack repository cases (24 of
25 cases in the full application subset). The remaining fixture attempted to
insert duplicate attack IDs through `PartyRecords`, whose existing validation
correctly rejected them before the import under test. Test correction `d4aec7f` retains that protection and the subsequent five
repository cases plus one UI case all passed. Logs from the initial result are
retained as `source-attacks-focused.log`. Follow-up `9eee925` strengthens the
persistence case to a named Room database that is closed and reopened; its
five-case repository rerun passed with no skips. Coverage is disk reopen and
playable backup restore, not an Android process restart. The source-attack roundtrip uses the playable
backup format, not the complete connected campaign backup. Their
persistence, retry, backup and retained-evidence coverage must be reported
separately from the existing native journeys. Unsupported costs, multiattack and
custom critical behavior remain visibly unresolved; current structured effects
that would duplicate fixed source totals block import until reconciled. No
original source rows are changed. Final local source for this focused evidence is
`9eee925dc09478efcf15a802f7925a4225baa552`; raw logs and XML are retained under
`evidence/source-attacks-focused`, `source-attacks-recheck` and
`source-attacks-disk-recheck`. Exact-source full and native release gates subsequently passed on 659; they do
not extend the successful source-import coverage beyond the scope stated here.

## Signing and upgrade evidence boundary

Owner-artifact verification checks the production package, version, original
certificate, checksum and source payload. It is separate from empirical native
upgrade testing: the API 36 sequence installs test-signed49→50→51 APKs over one
another, verifies retained records and exercises later writes. The test
certificate is disposable. An owner certificate match and successful test-signed
migration establish complementary compatibility evidence; they are not an
actual owner-key installation receipt.

This verification work has not installed an owner-signed code51 APK on an
emulator or the Galaxy S26. The final report must retain that limitation,
even after the artifact signature and disposable-signer migration checks pass.

## Final artifacts and acceptance

`Astravar-4.0.0-genesis2-code51-owner-signed.apk` is 13,184,678 bytes, SHA-256:

`81c4ab63e4aba980bc7881919d6e90333e2f72f3031d43db2fbbe1fa17d5e644`

- Package `com.yazsras.astravar`; version `4.0.0-genesis2`; versionCode51.
- Original owner certificate SHA-256
  `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`;
  APK signatures v2/v3 verified. The actual original owner code50 APK was
  independently checked to match this package and certificate.
- Minimum SDK 26, target SDK 36, non-debuggable, 16 KiB native alignment verified.
- All 210 ZIP payload entries are byte-identical to the exact tested CI release
  APK, with zero excluded or changed entries. Signing did not rebuild the app.
- Original armor, ability and attack artwork retain exact dimensions and decoded
  RGBA pixels in the APK. The source assets remain preserved.

`SIGNING_REPORT.json` and `artwork-preservation.json` record the detailed proof.
The delivery also includes `Astravar-Genesis2-Verification.md`,
`Astravar-Genesis2-Verification-Evidence.zip` and
`Astravar-Genesis2-SHA256SUMS.txt`, with source/checkpoint recovery and raw test
receipts. Their filenames are recorded here without private delivery URLs. The
evidence ZIP's checksum belongs in the external checksum manifest, not a report
contained inside that ZIP.

The artifact has the original package/certificate and a versionCode above 50,
supporting an in-place update. Empirical data-preservation tests used the
separate disposable test signer. This verification work did not install the
owner-signed code51 APK on an emulator or the Galaxy S26. Current Zerg phone
configuration and physical Android 17 behavior remain unverified.
