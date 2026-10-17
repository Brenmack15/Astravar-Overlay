# Inventory MVP v1.7.0 verification — 2026-10-17

The supplied offline inventory patch was applied with its guarded adapter on a clean feature worktree at exactly `b4f539bca7965b40153bc5e237f66b458ee1578e`. The original ZIP SHA-256 is `a0c307286c1f508a9ed0acbff631ca8bd47931c00aa147452ed741dbc85872e7`. Every payload file matched its supplied checksum; the checksum manifest's own self-entry is invalid. The original ZIP was preserved unchanged outside the repository.

## Scope and integration repairs

- Added the supplied local item/discovery ledger, debug-only More entry, embedded thumbnails and JSON export. No server, account, network permission, cloud service or ChatGPT integration was added.
- Added `tools:replace="android:label"` to the debug manifest after the first build exposed a label merge conflict. The release manifest and signing configuration are unchanged.
- Encoded fingerprint fields as a JSON array so discovery detail/source separators cannot collide; added regression coverage. Bounded the separate edit-event source field consistently.
- Fixed New item to clear an already-new unsaved form, and disabled item/image switching during pending operations; added a UI regression test.
- Added a file-backed ledger reopen/schema-isolation test. The seven supplied Room tests remain, for nine ledger tests total, plus one new Compose interaction test.
- Preserved the adapter and seven Python safety tests in the repository. Exported the new Room v1 schema. Existing AstravarDb v2/v3 schemas, explicit migrations, persistence sources, rules, artwork/resources and overlay sources are byte-identical to the recovery base.
- CI recognizes this feature branch and runs the requested source tests, rules, Android debug tests, lint and debug packaging with a bounded timeout. It does not generate a release key or build/install a release for this beta. Other branches retain their existing release/emulator path.

## Measured checks

Actual Android validation ran in the existing cloud workspace using JDK 17, Gradle 8.13 and Android SDK 36:

```sh
python3 -m unittest discover -s scripts/tests -v
bash ./gradlew --no-daemon --console=plain :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- Python safety tests: **7 passed**.
- Rules tests: **104 passed**, zero failures/errors/skips.
- Android debug unit tests: **53 passed**, zero failures/errors/skips, including all nine ledger Room tests and the new UI test.
- Debug lint: **zero errors**, 27 warnings and one hint. The additional inventory warning is a `UseKtx` suggestion for bitmap scaling; it is not suppressed.
- Debug APK: **built successfully**. The full build reported BUILD SUCCESSFUL in 7m 58s. A noninteractive verification completed with exit 0 in 26s using the unchanged successful outputs. Background terminal input had paused the earlier Gradle client after task completion; redirecting standard input resolved that workspace issue.
- The restricted local container could not bootstrap Gradle because its download network was unreachable; that was an environment failure before compilation. The cloud build and XML results above are independent of it.

The verified workspace debug APK is 14,953,508 bytes, SHA-256 `c72b590885a3d3771cce2a0ebdb808abfff3c4b9c242a0db5b645fcc88c1b417`. Android build-tools verified its APK v2 signature and ZIP/page alignment. Its manifest was independently parsed and confirms:

- Package: `com.yazsras.astravar.debug`
- Version: `1.7.0-inventory-beta`, code `10`
- Launcher label: **Astravar Inventory Beta**
- Minimum SDK 26; target SDK 36; debuggable true; no Internet permission.

It uses an Android Debug certificate, SHA-256 `5b41c29144971c9ae4c27ab79a870087128ec00389600537299f0083f5b2fa73`. This is separate from the preserved owner release identity. CI builds may use a different debug test certificate and produce different APK hashes; their own logs/artifacts are authoritative for those bytes.

## Remaining boundaries

No APK was installed on the owner's phone, and physical S26 coexistence, permissions, overlay interaction and upgrade behavior remain untested. The distinct debug package is intended for installation alongside `com.yazsras.astravar`; release data was not accessed or copied. Release signing, optimized release packaging and emulator overlay checks were not requested for this beta and are not claimed.

Cloud/Notion/ChatGPT synchronization is absent and has not been tested end to end. The separate ledger exports JSON with images/history, but has no import/restore UI. The old weapon backup does not contain this ledger. Keep live campaign records and backups separately; this beta is not production-ready or the sole authoritative campaign store.

The recovery branch, main and PR #1 remain unchanged. The feature PR must stay draft and unmerged.
