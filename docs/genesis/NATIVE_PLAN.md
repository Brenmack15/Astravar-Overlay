# Genesis verification and upgrade acceptance

This is an execution plan and evidence contract, not a claim that its workflows
have passed. The two workflows below must succeed for the exact candidate source
commit. A successful compile is insufficient.

## Hosted gates

Both workflows run for pushes to `feat/astravar-project-genesis*`, matching pull
requests, and explicit dispatches. Pull requests check out their exact head SHA.
Automatic push and PR runs share a branch concurrency group, so the newer event
supersedes duplicate work. Existing action revisions remain pinned.

| Gate | Required checks | Evidence artifact |
| --- | --- | --- |
| Astravar Reforge verification | Authenticated bridge protocol tests; Python migration/source/provenance checks; all rules, debug and release unit/Compose/Room tests; debug and release lint; debug assembly and package inspection | `Astravar-3.0-verification` |
| Bounded Android verification | Build optimized candidate, debug/native test APKs and observer; inspect package; build verified historical baseline; execute ordered Android 16/API36 native journeys, gestures, recovery, import, retention and upgrade checks | `android-verification` |

Genesis expects `com.yazsras.astravar`, version `4.0.0-genesis1`, versionCode `50`.
The separate QA package retains its existing `.v2uidebug` identity. Old branch
paths retain their prior expected version and behavior.

The native job does not rerun the complete JVM/Python regression suites; those
are mandatory in the other gate. Genesis also skips the legacy preliminary
smoke subset because the full gate executes those tests. A failed gate remains
a blocker; passing native tests cannot substitute for failing rules or lint.

## Actual code49 to code50 update

The native workflow fetches **exactly**
`3543e83bfca5e589f1bbedb09964431ce94a2319` into a detached temporary worktree and
builds its code49 release. It builds code50 from the checked-out candidate.
Both builds use one newly generated disposable CI certificate, private to that
job. The workflow verifies both APK signatures and requires their certificate
digests to match. It records the frozen baseline commit, package inspection and
APK hashes under `validation/upgrade-baseline/`.

The emulator script consumes `ASTRAVAR_BASELINE_APK`. The current observer's
`GenesisUpgradeInstrumentationTest` drives the historical UI to create distinct
fictional character, item, resource and companion fixtures. The upgrade stage
must record the installed code49 version, Android UID and first-install time,
stop the app, and snapshot persisted files plus every SQLite table before
ordinary `adb install -r` of code50. No uninstall, data clear or downgrade flag
is permitted. Exact file retention is checked before code50 first opens;
native UI readback and independent SQLite checks then establish retained
identities, values and relationships after opening. The fixture remains
separate from later gameplay journey fixtures.

This is an **older-build update**, distinct from the retained later
same-version reinstall test. If the baseline build, fixture creation, signature
comparison, package update or independent readback does not execute and pass,
code49-to-code50 retention is unverified. Neither a same-version reinstall nor
a clean install may be described as that evidence.

Genesis rejects APK-reuse dispatches and automatic overlay-only reuse markers.
A reused product from another run cannot share a newly generated baseline
signer. This keeps both versions and the upgrade result tied to one source/run.

## Signing and device boundaries

CI never receives the original owner's private key, signing recovery bundle,
credentials or actual campaign database. CI APKs cannot update the owner's
installation and are labeled test-only. Only their non-signature payload may
be used for a later privately signed deliverable after all checks pass.

Final delivery still requires original-owner signing, complete historical
certificate comparison, package/version and alignment inspection, payload
equality against the tested candidate, final SHA-256, and a separate isolated
owner-signed code49-to-code50 installation/readback check. Do not call the
temporary-key CI result an owner-key installation test.

API36 emulator acceptance does not establish physical Samsung Galaxy S26 or
Android17 acceptance. No owner device is connected by these workflows.

## Local recovery evidence and limits

The historical code49 owner APK was independently recovered and verified:

- APK SHA-256: `45fcd71a2e1294f8865e700b3e8539d8bfc92c15dffe43c26daaaf2a065b3fe5`.
- Owner certificate SHA-256: `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
- Package/version, non-debuggable release, artwork and 16KB alignment passed.

The local host has no KVM. Its bounded API26 software-emulator attempt booted
after 246 seconds, but baseline installation timed out after 180 seconds; total
attempt 578 seconds. No current baseline UI acceptance is claimed from that
attempt. The recovered historical screenshots retain their original provenance.
The hosted accelerated API36 stage is required for the new interactive evidence.

When reporting results, list exact source SHA, workflow run links, executed and
skipped counts, failed stages, artifact hashes and remaining limitations. Every
entry in the functional coverage matrix that was not exercised remains marked
unverified. Preserve the checkpoint if a gate cannot complete.
