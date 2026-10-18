# Native harness recheck

`Astravar 2.1 native harness recheck` is a separate GitHub Actions workflow for
repairing native instrumentation after the complete verification run has already
built an unchanged product. It does not replace the full Android workflow or
publish a release.

The workflow reuses artifact **12150294180** from run **39163162978**, whose product
source is **1417dd5e045151c8a16b349ccf2c058371c8be16**, tree
**f6aea137f996a50082f2ca21045d5c69f2f317fc**. The compressed archive SHA256 is
`106b675781ed445d412047302e352b9d104aacc1f33f0d4fbd73976fb63e2a79`.
Its original full run passed Python, rules, Android unit tests, lint and optimized
builds, but failed native instrumentation. No successful native result is implied.

Before downloading an APK, the source guard requires a clean checkout descended
from that exact commit. Only `overlay-target/src/androidTest/**`, this document,
the new workflow, `scripts/native_recheck.py`, its exact Python test file and the
single controlled fixture
`overlay-target/src/main/java/com/yazsras/astravar/target/TargetActivity.kt` may
differ. The activity belongs to the disposable second application used to test
outside taps, keyboard focus and protected windows. Its SDK 36 IME-insets repair
keeps the keyboard field visible; it does not change Astravar's product UI.
No other target main source, manifest, resource or build file is permitted.
Product code, resources, schemas, build configuration and the existing native
runner are forbidden changes. Mixed edits still fail even when they include the
approved fixture. A rejected guard requires a new full run and an independently
reviewed immutable baseline, not a broader allowlist.

The helper verifies the artifact API identity/run/digest, complete downloaded ZIP
checksum, original `SOURCE_COMMIT.txt`, individual APK checksums and actual APK
metadata. The code24 and code25 product APKs must both be `com.yazsras.astravar`,
version `2.1.0`, label `Astravar`, non-debuggable, with the same pinned temporary CI
certificate. Artwork and 16 KiB alignment checks remain enabled.

Only the controlled second-app target and instrumentation are newly assembled;
their actual packages and matching fresh test certificate are checked. Both real
product APKs remain the exact pinned bytes from the original full build. The existing native
runner then runs unchanged, including synthetic demo safeguards, strict overlay
assertions, all-store preservation, historical raw hashes and independent UI
readback. Evidence records `SOURCE_COMMIT.txt` for the reused product and
`NATIVE_TEST_SOURCE_COMMIT.txt` for the new test source, with both trees in
`native-recheck-source.json`. Old screenshots or failed test output are not copied
into the new evidence directory.

The `Astravar-2.1-native-recheck` artifact is test evidence. Its reused product APKs
are **CI-only**, signed with
`e54ceedb4515f44bba2f42a639ef7a18bca5d0578c3b37a9df1d49a7552cd5f0`.
They are not owner-signed permanent releases. Do not install them over an
owner-signed app or uninstall any existing installation to bypass a mismatch.
No owner key or real campaign records are used. The source artifact expires on
2026-11-01; a missing or expired artifact fails closed.

The development branch push trigger covers instrumentation and the four recheck
support files; this fixture repair also changes those guarded support files. Its
job runs only when the final publication commit has the exact subject
`ci: run verified native harness recheck`; partial publication commits skip it.
`workflow_dispatch` is retained for future authorized default-branch adoption;
GitHub requires the workflow file on the default branch for manual dispatch.
No default-branch change is authorized or needed for the branch push trigger.
The workflow uses a separate concurrency group from complete verification.

Keep the ordinary full workflow enabled and report its result separately. Any
subsequent product change requires complete verification and a fresh product
build before release approval.
