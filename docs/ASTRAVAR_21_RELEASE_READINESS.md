# Astravar 2.1 release and update gates

Astravar 2.1 continues PR #4 on `feat/astravar-2.1-player-first`; draft PR #5 targets
the existing development branch. This is not a completed upgrade or an approved
production release. No merge, phone installation, key rotation or real-data cutover
is authorized by this document.

## Identity

| Build | Package | Default version | Signing identity |
|---|---|---|---|
| Permanent owner candidate | `com.yazsras.astravar` | `2.1.0`, code 24 | Existing owner identity, recovered privately |
| Ordinary QA debug | `com.yazsras.astravar.v2uidebug` | `2.1.0-ui-rebuild`, code 24 | Environment debug key |
| Explicit legacy remote-stat test | `com.yazsras.astravar.v2statdebug` | `2.1.0-remote-stat-test`, code 24 | Environment debug key |
| Optimized CI verification | `com.yazsras.astravar` | `2.1.0`, codes 24 then 25 | One-run disposable test key; never owner release |

The launcher remains **Astravar** with its approved portrait. The original owner
signing configuration is retained. Public certificate SHA-256:
`4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
Nine available historical owner APKs (codes 1, 3, 4, 5, 6, 7, 8, 9 and 10) match
this identity. Proposed code 24 exceeds those available builds; check all actually
delivered owner versions before signing. The existing private recovery artifact was
recovered again after workspace loss and its identity reverified. No key replacement
was made and signing secrets are not stored in source, chat, CI or artifacts.
Historical APK compatibility does not establish the current handset's identity.

## Verification results by exact source

| Result | Evidence | Limit |
|---|---|---|
| Full Gradle verification passed | Run 39163162978, product `1417dd5e045151c8a16b349ccf2c058371c8be16`: 124 rules tests; 264 Android tests per variant; both lints and optimized builds | Overall workflow failed its original native harness |
| Android suite breakdown | Debug 262 passed / 2 gated skips; release 248 passed / 16 gated skips; zero failures/errors | Historical product, not a later source claim |
| Seven native invocations passed, five distinct methods | Run 39172843472, test source `414ee61b2696412d77183e8d719b513bc1597789`, immutable product `1417dd5e045151c8a16b349ccf2c058371c8be16` | Temporary CI signer; later product edits excluded |
| Raw history independently verified | 76 original rows: 43 archives + 33 unchanged working; 26 preservation triggers; recent view 20 events / 20 rolls; current campaign unchanged | Fictional emulator fixture only |
| Current local source checks passed | 60 Python checks, preservation gates and focused application compilation after system-bar correction | Fresh final full CI and native visuals still required |

The [successful native recheck](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/39172843472)
uses test tree `6f3b51d8f3daceb511547c9911d303f5db655171` and unchanged product tree
`f6aea137f996a50082f2ca21045d5c69f2f317fc`. Its
[artifact ZIP](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/39172843472/artifacts/12153247985)
is 37,962,839 bytes, SHA-256
`4046da827b8dc8d6c88359fbdd6046cf60aefa5ae9403f1ac11bae42ec29e3b1`.

Actual instrumentation passes overlay drag/outside touch, keyboard input while the
field remains visible above the IME, rotation, protection, notification stop,
saved-roll restoration, main History and permission denial/regrant/restart. The
same-key code 24→25 install preserves all seven persisted-store file hashes before startup;
independent native readback checks both exact saved attacks and the same fictional
unknown-quantity item. Native toolkit search/Copy also passes.
The seven invocations are three original overlay methods, one fixture method, one
readback method and two repeated overlay methods on the updated package. The full
product lints report zero errors, 48 debug warnings / 50 release warnings and one
hint each. These are named-source results, not a fresh final-source pass.

Armor/Party captures cover font scales 1.0, 1.3 and 2.0, landscape, element-wheel
400/360-dp viewports and unlock explanations. Fresh XML confirms selected routes.
Direct PNG inspection and independent OCR of the artifact's
`emulator-evidence/armor-font-1.0.png` show the full Armor body. Its earlier
blank-looking viewer preview was a display discrepancy, not an established missing
capture. Mid-word primary-navigation wrapping at 1.3×/2.0× text remains a confirmed
visual defect. The capture-readiness guard and responsive navigation corrections
require fresh final-source evidence; a
matching accessibility tree or two identical frames is not sufficient by itself.
No Galaxy S26 physical-device test has occurred.

Product commit `69e7eadb97d3dafdbaf989dc95903e5e3a39ad7d` corrects system-bar icon
contrast after this immutable-product recheck. Its focused application compilation
and source checks pass; it still needs fresh full CI, matching native visual proof
and a final owner-signed candidate. Do not relabel the older APK as this source.

## Verification implementation and preservation

The full workflow checks the actual development head, Python/source/artwork gates,
Kotlin rules, both Android regression variants, both lints, optimized APKs and target
instrumentation. CI packages have an explicit temporary signer. APK inspection checks
package/version/label/launcher resources/signature/16-KiB alignment; `--owner-signed`
requires the historical owner fingerprint. Fast native rechecks may reuse a verified
immutable product while rebuilding only an explicitly guarded test harness/fixture.
They cannot verify a changed product. Robolectric tests, native emulator evidence,
manual visual review and physical Samsung use are separate forms of evidence.

Workspace source recovery cloned and verified published `5d9a5f7`, tree
`e199397cd5bed1da05cf47ee893a7a7c0638ba9e`, preserving history. Source gates check
33 recovered files, 79 foundation files, 14 launcher resources, ten original particle
files and five frozen historical schema JSON files. Real records and signing secrets
remain outside the repository and synthetic CI fixtures.

Immutable raw archives supersede inherited delete-to-20 behavior. Complete streaming
weapon ZIPs preserve raw histories, malformed legacy bodies, original legacy JSON
bytes and prior command identities; the recent display and Undo remain bounded at
20. Confirmed restore is backup/stale-preview guarded. See
[the preservation contract](HISTORY_PRESERVATION.md). Focused Android/Robolectric
verification passes 50 unique affected cases per variant, overlapping the full suite.
The post-Android database was independently checked against the original fictional
raw fixture, not merely against screenshots or a successful install command.

## Unfinished functionality and authority gates

Editable sourced Party records and ID-preserving equipment transactions are present.
Equipment/progression changes are manually sourced: placing an item does not infer
bonuses or automatically change the separate weapon profile. Unknown quantities,
source conflicts and unapproved mechanics remain unresolved. Navigation tests do not
prove every editor or tabletop workflow complete.
Full authoritative Party statistics await approved reconciliation and import.
Creature/spell fields are descriptive records, not automatic level-up or slot grants.
Transfers move whole stacks without split/merge; wallets support copper, silver and
gold independently, with no inferred quantities or currency conversion.

Discharge remains enabled with inherited 3× primary Force dice, 60-foot targeting,
15-foot radius and 600 recorded in-world seconds of recovery. The later Armor Mk.I
reference's 12d12 Force, 1-mile range, 20-foot radius and once-per-Long-Rest limit are
unapplied. Resolving that conflict needs adjudication and implementation, including
resource accounting; it is not simply a missing configuration field. Charged
Gauntlet instead stays locked until a sourced current melee profile and accepted
flat-bonus damage type are configured. The reference omits that type.
No real-time countdown, guessed ruling or unapproved enhanced Neutral Force is used.

Owner-sync provides protocol, credential vault, staging queue, receipts and an
authenticated synthetic reference service. Remaining engineering includes typed
party/item/currency server commands and app adapters, atomic domain/outbox writes,
owner OAuth/device enrollment, credential renewal and server-enforced revocation,
production native connection UX and an authenticated MCP/tool adapter. The present
fictional acceptance harness is not live campaign synchronization. Local disconnect
is not proof of server revocation. See [the connection contract](OWNER_SYNC.md).

Later acceptance must separately prove authorized hosting, real native Astravar HTTPS,
native ChatGPT Android tools and shared-record read→approved write→receipt→device
refresh→independent readback, including offline/conflict/replay/denial/revocation.
The private DM Bridge is preserved. The anonymous Worker is excluded from real data.
No paid infrastructure has been activated. Real import, authoritative cutover and
production publication each require separate approval after their acceptance gates.
The existing Notion ledger must remain available during transition.

## Permanent signing and safe delivery

CI-only temporary-key APKs are not permanent owner updates. Their install/update
proof applies only to that temporary signer. The owner identity is securely available
for an authorized candidate; final source metadata, signature, artifact hashes and
owner-key synthetic update preservation must be verified before delivery approval.
The earlier local owner APK identified by prefix `5208` and its proof files were
lost with the transient workspace. It is not available for download and must never
be offered as the final candidate. The recovered original key allows a fresh build
without changing signing identity.
Inspect the actual APK, for example:

```sh
python3 scripts/inspect_release_apk.py path/to/owner-candidate.apk \
  --package com.yazsras.astravar --version-name 2.1.0 --version-code 24 \
  --owner-signed --output owner-candidate-metadata.json
```

The phone's installed package/version/certificate and data are unobserved. An in-place
update requires the same package, identical trusted signer and increased version code.
The `.v2statdebug` package is a separate application with separate storage; it cannot
be updated in place by the permanent package. A signature/package conflict is a stop
condition, never a reason to uninstall an existing app. Keep validated exports.

After final candidate approval, open the verified owner-signed APK from My Files →
Downloads on the Galaxy, allow that source only when Android requests it, and choose
**Update** only for a verified compatible installation. Stop on any conflict and keep
the existing installation. No phone installation or uninstall was performed here.

Remaining release work: repair and verify the identified visuals, freeze and publish
an exact source tree, run fresh full CI/native acceptance, inspect the final owner APK,
prove its synthetic update preservation, independently establish the actual handset
identity, and obtain final production approval. Continue connection engineering and
reviewed migration preparation without importing real data or changing its authority.
