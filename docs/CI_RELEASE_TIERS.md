# Development, verification and owner release

The development gate produces a QA APK and early regression feedback. A stable owner release requires the full and native gates for the exact source, followed by private owner signing and independent APK verification. A green fast gate alone is never release approval.

## Triggers and required checks

| Tier | Trigger | Required evidence |
|---|---|---|
| A: development | Every PR update; `Astravar tiered verification` → `fast` for an explicit run | Full rules tests; critical bridge/security and Python/source checks; selected Android regressions; debug lint and APK; source SHA and failure summary |
| B: comprehensive | Automatically when canonical stores, schemas, rules, permissions, backend, build/CI configuration, instrumentation or unmapped production files change; PR label `full-verification`; or `Astravar tiered verification` → `full` | Existing complete debug/release unit suites, both lint variants, retained provenance checks, optimized native journeys and frozen historical upgrades |
| C: owner release | Private release procedure after successful Tier B | Permanent package, increasing version, original certificate, signature/alignment, source/checksum/payload identity and preserved-data upgrade proof |

Configure branch protection to require **Astravar required verification**. This check always runs and fails if the plan, fast gate or an applicable full/native gate is missing, canceled or unsuccessful. Path selection happens inside the workflow; it does not leave a filtered required workflow permanently pending. Changes to shared test fixtures or removed tests require full verification.

The orchestrator executes the fast gate first. Only after it passes do full regression and native acceptance run concurrently. This stops cheap source/rules/targeted failures before expensive emulator work while preserving parallel release feedback. Standalone full/native workflows remain manually runnable. Current-source verification always uses the Genesis historical lane and code52 expectations, regardless of branch name; retained historical branches keep their own workflow files. Current Genesis development no longer starts these twice on push and PR. Explicit native runs use a separate concurrency group and are not canceled by routine PR updates.

PR checkout is the exact head SHA, not an undocumented merge artifact. Retain `SOURCE_COMMIT.txt`, checksums, plans and logs with the corresponding run. Full/native approval must refer to that same source; changing packaged code invalidates an earlier candidate's release evidence.

## Caching and resource limits

Pinned `gradle/actions/setup-gradle` restores dependencies and reusable Gradle state; Gradle build caching is enabled. Fork PRs are read-only consumers. Same-repository PRs may write only their isolated `refs/pull/N/merge` cache; base/default branches and other PRs cannot restore it. This lets the fast job seed subsequent full/native jobs and warm repeated updates to this long-lived PR without merging to main. Default-branch and explicit dispatch runs retain their own branch-scoped write access. No configuration-cache encryption key, owner signing secret, personal export, live database, or campaign record is cached. Build scans and dependency submissions are not enabled.

The SDK helper uses installed API36, build-tools35 and platform-tools when present. It downloads only absent packages. Native runs additionally require the API36 Google APIs x86_64 system image and emulator. A checksum-pinned command-line bootstrap remains available if the runner lacks sdkmanager. Runner images and installed-package metadata are build evidence, not claims of Samsung/Android17 acceptance.

Historical versions49 and50 have fixed source SHAs. A trusted exact-key cache retains each built APK with source/build-input identity, payload hashes, checksum and original CI certificate. Cache keys include the helper and inspector hashes; there is no prefix fallback. A complete cache miss builds the frozen source. Partial or altered caches fail closed. Each use verifies the old signature/package/alignment, re-signs with the current run's disposable key, compares every non-signature ZIP entry and verifies the same certificate as the candidate. Only successful same-repository PR, explicit-dispatch or default-branch native runs save caches within their existing GitHub cache scope. No signing key is cached. This removes repeated historical compilation on a warm trusted cache without replacing upgrade testing.

Cache-scope and cross-job matching behavior follows the [GitHub cache reference](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache) and [pinned setup-gradle implementation documentation](https://github.com/gradle/actions/blob/0b6dd653ba04f4f93bf581ec31e66cbd7dcb644d/docs/setup-gradle.md#finding-a-matching-cache-entry). The latter saves at job completion and can restore the most recent same-OS cache from a different job, so the existing fast-before-full/native dependency supplies an actual warmup opportunity.

`maxParallelForks=1` and `forkEvery=1` remain the proven defaults because Robolectric retains framework/font state per classloader. Bounded opt-in properties `astravarTestForks` (1–2) and `astravarTestForkEvery` (1–8) support measurement. Run **Astravar test worker benchmark** to compare (1,1), (1,4), (2,1), (2,4), twice each, on identical mixed data/Compose cases. Logs, wall time, JUnit time, process resource reports and source SHA are uploaded. Any failed or missing/different test set aborts. A sample benchmark does not justify promoting defaults: require repeated complete debug/release suites and review peak memory/flake evidence first.

## Measured baseline and acceptance of improvements

Baseline source `099c960dbbd76e0e4537efaaee28c3987e99c2a0`:

| Stage | Full regression run43671448690 | Native run43671448710 |
|---|---:|---:|
| Job duration | 60m44s | 66m30s |
| Runner setup | 2s | 1s |
| SDK preparation | 14s | 60s |
| Bridge regression | 3s | included elsewhere |
| Source/provenance checks | 16s | included elsewhere |
| Debug/rules compile and tests | 27m30s | — |
| Release tests, lint, debug APK | 32m29s | — |
| Candidate build | — | 9m54s |
| Frozen version49 build | — | 4m46s |
| Frozen version50 build | — | 5m12s |
| Emulator acceptance | — | 45m18s |
| Artifact upload | 4s | 5s |

Android test XML reports about1116s debug and1127s release; task log spans were about21m21s and21m16s respectively. These are different measurements: XML test time excludes some process/setup overhead. SDK installation is not the dominant cost on this observed run.

Recent unsuccessful full runs still consumed 22–24minutes while independent native runs continued for61–76minutes. Duplicate push/PR runs were often canceled after4–50seconds; these count as wasted setup/queue work, not useful verification.

The 5–15minute Tier A objective is a target for warm caches, not a measured guarantee. Cold Kotlin compilation can dominate. Compare completed new runs against these recorded baselines before claiming a speedup; include cache hit/miss state, runner image, selected test count, commit and failed/canceled attempts. Full release tests and native journeys remain intentionally comprehensive. This document does not claim a new run passed merely because the workflow was added.

## Owner-compatible release boundary

The owner certificate is `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`; package is `com.yazsras.astravar`. Current candidate defaults are version52 / `4.0.0-unification1`. Keep the original private key in the established owner environment. A CI disposable certificate is never an update workaround.

Use `docs/BUILD_AND_SIGNING.md` and current release evidence to privately sign or re-sign the exact tested optimized payload, prove every non-signature entry is unchanged, inspect metadata/signature/alignment, verify monotonically appropriate version against the owner's installed app, and record final SHA256/source/certificate. Validate upgrade and persistence on isolated fixtures; physical installation/consent remains separately evidenced. Never uninstall or clear data to bypass signing incompatibility. Publishing a production release is a separate authorized action.
