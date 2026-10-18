# Astravar 2.1 release and update gates

Astravar 2.1 continues PR #4 on `feat/astravar-2.1-player-first`. No production
release, merge, device installation, signing-key rotation or real-campaign cutover
is authorized by this document.

## Identity

| Build | Package | Default version | Signing identity |
|---|---|---|---|
| Permanent owner candidate | `com.yazsras.astravar` | `2.1.0`, code 24 | Existing owner PKCS12, recovered privately |
| Ordinary QA debug | `com.yazsras.astravar.v2uidebug` | `2.1.0-ui-rebuild`, code 24 | Environment debug key; not a permanent update series |
| Explicit legacy remote-stat test | `com.yazsras.astravar.v2statdebug` | `2.1.0-remote-stat-test`, code 24 | Environment debug key; disposable test only |
| Optimized CI verification | `com.yazsras.astravar` | `2.1.0`, codes 24 then 25 | One-run disposable test key; never owner release |

The launcher label remains **Astravar** and the approved portrait resources are
checked by the original branding provenance gate. Version codes must be checked
against every previously delivered owner build before signing a permanent
candidate; increase the proposed value if an owner build already used it.

Historical owner certificate SHA-256:
`4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
The original environment-driven `owner` signing configuration remains in place.
All nine available historical permanent APKs were independently inspected with
`aapt` and `apksigner`: versions 1.0.0/code 1, 1.1.0/3, 1.2.0/4, 1.3.0/5,
1.4.0/6, 1.5.0/7, 1.5.1/8, 1.6.0/9 and 1.7.0/10. Each uses the same owner
certificate above, and proposed code 24 exceeds every available owner build.
This establishes historical APK compatibility, not the current phone state.
On 18 October 2026 the existing owner-private recovery artifact was located,
opened only outside the repository, and its actual public certificate was checked
against this fingerprint. The identity matches. No replacement key was created.
Signing credentials remain private and are never uploaded to GitHub CI, source,
chat, logs or build artifacts.

## Verification implementation

`.github/workflows/android-2.1.yml` checks out the actual development head even
for PR runs. It executes Python tests, source/artwork provenance, Kotlin rules,
both debug and release Android regressions, debug/release lint, APK assembly and
controlled target instrumentation assembly. Optimized APKs use only an explicitly
temporary CI signer. `scripts/inspect_release_apk.py` inspects each real APK's
package, version, label, launcher references, signature and 16 KB alignment. Its
`--owner-signed` gate requires the historical owner fingerprint.

`scripts/ci-player-first-emulator.sh` requires a fresh accelerated API 36 emulator.
It exercises native overlay actions, touch pass-through, keyboard, rotation,
notification stop, permission revocation and roll restoration. It creates a
clearly fictional inventory item through the ordinary native editor, retaining
unknown quantity. It then force-stops the product, hashes every persisted file in
its app stores, installs code 25 over code 24 with `adb install -r`, and requires
all those bytes to be unchanged before startup. Independent native readback checks
both saved attacks, their raw dice and the fictional item. Native Armor/Party
captures cover font scales 1.0, 1.3 and 2.0 plus landscape, element-wheel
viewports at logical 400/360 dp, unlock explanations and native toolkit search/Copy. Screenshots require
visual review; creating them alone is not a visual pass.

These scripts are acceptance requirements, not a statement that a workflow has
passed. Use the milestone report and exact Actions run for actual results. Local
Robolectric output is separate from real emulator instrumentation. Neither is a
physical Galaxy S26 test.

## History acceptance and complete backups

The inherited delete-to-20 behavior is superseded by immutable raw preservation
in the existing archives table. Recent display and Undo remain bounded. Complete
weapon backups use the streaming ZIP contract; legacy JSON bytes, malformed
history, original archives and old command identities remain recoverable. See
[the format, limits and regressions](HISTORY_PRESERVATION.md). The final native
fixture injects 62 fictional older rows into a stopped copied database with its
old history triggers removed, then verifies every original hash after an actual
APK update and startup. No fixture may operate on a phone or non-demo data.
Focused cases pass in both variants; final clean-head build and native evidence
remain separate gates.

## Permanent signing and safe delivery

A successful CI test-signing run proves packaging and the tested update procedure
for that temporary key. It cannot establish compatibility with an owner-signed
installation. Artifact filenames explicitly say **CI-ONLY** and include metadata,
certificate, SHA-256 and source commit. Do not recommend installing an optimized
CI-only APK as the permanent product.

The existing key is now available privately in this authorized development
environment. An owner-signed candidate can be prepared after verification; this
is separate from production publication approval. Verify the actual APK with:

```sh
python3 scripts/inspect_release_apk.py path/to/owner-candidate.apk \
  --package com.yazsras.astravar --version-name 2.1.0 --version-code 24 \
  --owner-signed --output owner-candidate-metadata.json
```

Never infer the phone's current identity from an old download. Read its running
package/version and certificate independently. An in-place update requires the
same package, matching trusted signer and increased version code. A different
package is a separate app with separate records. A signature mismatch is a stop
condition, never a reason to uninstall the existing app. Keep validated exports
before a later separately approved migration.

After candidate approval, download the verified owner-signed APK on the Galaxy,
open it from My Files → Downloads, allow installation from that source only when
Android requests it, and choose **Update** for a verified matching installation.
If Android reports a conflict, stop and retain the existing installation. No
physical phone has been connected to this cloud environment, and its installed
package, signer, current records and update behavior remain unverified here.

## Remaining production gates

- Existing owner key recovered: complete; actual candidate fingerprint remains
  subject to inspecting the built APK.
- Exact workflow and visual-review results recorded for the published source.
- Actual installed package/certificate/version confirmed without deleting data.
- Authorized secure service/device/ChatGPT integration independently proven;
  the fictional public Worker remains excluded from real campaign records.
- Synthetic import/restore acceptance and complete real-data conflict review.
- Separate owner approval for real import, authoritative cutover and publication.
