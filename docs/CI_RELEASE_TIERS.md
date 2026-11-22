# Development, verification and owner release

The development gate produces a QA APK and early regression feedback. A stable owner release requires the full and native gates for the exact source, followed by private owner signing and independent APK verification. A green fast gate alone is never release approval.

## Triggers and required checks

| Tier | Trigger | Required evidence |
|---|---|---|
| A: development | Every PR update; `Astravar tiered verification` → `fast` for an explicit run | Full rules tests; critical bridge/security and Python/source checks; selected Android regressions; debug lint and APK; source SHA and failure summary |
| B: comprehensive | Automatically when canonical stores, schemas, rules, permissions, backend, build/CI configuration, instrumentation or unmapped production files change; PR label `full-verification`; or `Astravar tiered verification` → `full` | Existing complete debug/release unit suites, both lint variants, retained provenance checks, optimized native journeys and frozen historical upgrades |
| C: owner release | Private release procedure after successful Tier B | Permanent package, increasing version, original certificate, signature/alignment, source/checksum/payload identity and preserved-data upgrade proof |

Automatic PR tiers are active on this feature branch. GitHub's **Run workflow** UI normally requires the workflow definition on the default branch; these new manual dispatch controls are not yet exposed there. They become available after a separately authorized adoption of the workflow definitions on the default branch. No main merge or administration change was performed for this work. The `full-verification` label offers an explicit current-PR full run and may need one-time label creation.

Configure branch protection to require **Astravar required verification**. This check always runs and fails if the plan, fast gate or an applicable full/native gate is missing, canceled or unsuccessful. Path selection happens inside the workflow; it does not leave a filtered required workflow permanently pending. Changes to shared test fixtures or removed tests require full verification.

The orchestrator executes the fast gate first. Only after it passes do full regression and native acceptance run concurrently. This stops cheap source/rules/targeted failures before expensive emulator work while preserving parallel release feedback. Standalone full/native dispatch definitions are retained, subject to the default-branch UI availability above. Current-source verification always uses the Genesis historical lane and code53 expectations, regardless of branch name; retained historical branches keep their own workflow files. Current Genesis development no longer starts these twice on push and PR. Explicit native runs use a separate concurrency group and are not canceled by routine PR updates.

PR checkout is the exact head SHA, not an undocumented merge artifact. Retain `SOURCE_COMMIT.txt`, checksums, plans and logs with the corresponding run. Full/native approval must refer to that same source; changing packaged code invalidates an earlier candidate's release evidence.

The code53 hotfix adds an early mandatory native gate before the long release UI
journeys. It installs frozen code52 in the isolated QA package, seeds an older
RSA-OAEP key and encrypted credential sentinel, and updates in place with the
same disposable debug signer. Real Android KeyStore and current Node/WebCrypto
HTTPS fixtures then exercise authorization renewal, rejection and retry. The
public-constructor Notion import test covers complete apply, repeated imports,
backup validation, rollback and restoration before campaign HTTPS publication
and independent readback. Failure stops the gate; it cannot create success scope
evidence. This API37 Android17 emulator evidence is separate from physical
Galaxy S26 acceptance and from the final owner's signing certificate.

The code52 debug fixture is built from the exact source
`f90371c58baed7379862883b2a966ff1113b0cee` using the current runner's debug signer.
Its source, checksum, package, version and certificate are inspected. It does not
replace or invalidate the frozen49/50 release-upgrade cache or its mandatory
native journeys. No owner credential, key, or campaign data enters these fixtures.

## Caching and resource limits

Pinned `gradle/actions/setup-gradle` restores dependencies and reusable Gradle state; Gradle build caching is enabled. Fork PRs are read-only consumers. Same-repository PRs may write only their isolated `refs/pull/N/merge` cache; base/default branches and other PRs cannot restore it. This lets the fast job seed subsequent full/native jobs and warm repeated updates to this long-lived PR without merging to main. Default-branch and explicit dispatch runs retain their own branch-scoped write access. No configuration-cache encryption key, owner signing secret, personal export, live database, or campaign record is cached. Build scans and dependency submissions are not enabled.

The SDK helper uses installed API36, build-tools35 and platform-tools when present. It downloads only absent packages. The historical native suite retains the API36 Google APIs x86_64 image. The focused authorization/import gate uses the stable Android17 API37.0 default x86_64 image, emulator37.3.10 or newer, and4096MiB guest memory. The focused SDK helper installs only missing image packages or an older emulator. A checksum-pinned command-line bootstrap remains available if the runner lacks sdkmanager. Runner images and installed-package metadata are build evidence, not claims of Samsung/Android17 acceptance.

Historical versions49 and50 have fixed source SHAs. A trusted exact-key cache retains each built APK with source/build-input identity, payload hashes, checksum and original CI certificate. Cache keys include the helper and inspector hashes; there is no prefix fallback. A complete cache miss builds the frozen source. Partial or altered caches fail closed. Each use verifies the old signature/package/alignment, re-signs with the current run's disposable key, compares every non-signature ZIP entry and verifies the same certificate as the candidate. Only a successful baseline build/inspection step on same-repository PR, explicit-dispatch or default-branch runs saves caches within its existing GitHub cache scope. Saving happens before emulator journeys: the cache proves immutable build-input validation only, while every native upgrade and release gate remains independently required even if a later journey fails. No signing key is cached. This removes repeated historical compilation on a warm trusted cache without replacing upgrade testing.

Cache-scope and cross-job matching behavior follows the [GitHub cache reference](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache) and [pinned setup-gradle implementation documentation](https://github.com/gradle/actions/blob/0b6dd653ba04f4f93bf581ec31e66cbd7dcb644d/docs/setup-gradle.md#finding-a-matching-cache-entry). The latter saves at job completion and can restore the most recent same-OS cache from a different job, so the existing fast-before-full/native dependency supplies an actual warmup opportunity.

`maxParallelForks=2` and `forkEvery=4` are now the defaults after repeated mixed-suite measurements and complete debug/release validation with screenshots. Batching remains bounded because Robolectric retains framework/font state per classloader; unlimited JVM reuse is not enabled. Override both properties to1 for isolation diagnostics. Bounded opt-in properties `astravarTestForks` (1–2) and `astravarTestForkEvery` (1–8) support measurement. Run **Astravar test worker benchmark** to compare (1,1), (1,4), (2,1), (2,4), twice each, on identical mixed data/Compose cases. Logs, wall time, JUnit time, process resource reports and source SHA are uploaded. Any failed or missing/different test set aborts. Future tuning must retain complete debug/release gates and review peak memory and any timing-sensitive failures; a sample benchmark alone does not justify another promotion.

## Measured worker alternatives

Source `a5b14ce220f1859db65ce20a1778508d4bda08d0` ran the same110 data/Compose cases twice per configuration, with warm compilation outside the timer and the test task forced to execute (`--rerun`). All880 case executions passed with no skips. App/rules/build inputs were checked unchanged throughout; concurrent documentation and independent native-harness edits were outside these inputs.

| Parallel test JVMs | Classes per JVM | Actual run seconds | Mean | Faster than1/1 | Maximum sampled aggregate Java RSS |
|---|---:|---:|---:|---:|---:|
| 1 | 1 | 85.746 / 85.317 | 85.532s | baseline | 2.203GiB |
| 1 | 4 | 36.612 / 37.100 | 36.856s | 56.91% | 2.390GiB |
| 2 | 1 | 54.324 / 54.365 | 54.345s | 36.46% | 2.495GiB |
| 2 | 4 | 30.104 / 31.097 | 30.601s | 64.22% | 3.104GiB |

The measured host had9 CPUs and about9.7GiB RAM. Memory was sampled from `/proc` every0.5seconds, including the Gradle daemon and test workers; this is not an exact kernel peak. The fastest candidate was2forks/every4. Subsequent full-suite evidence below supports its adoption; the subset alone does not establish hosted wall-clock savings. The2/1 override retains per-class isolation if later evidence exposes cross-class state. The [machine-readable record](verification/test-worker-benchmark-20261116.json) binds environment, method, cases and results to source. Raw XML and execution logs remain outside the public source tree.

Complete local calibration used the same2/4 settings and enabled screenshot capture:

| Variant/source | Cases / suites | Failures / skips | Wall time | Maximum sampled aggregate Java RSS |
|---|---:|---:|---:|---:|
| Debug / `468030c334f9abd5e1f2bbf485974a2e3e844720` | 1014 / 151 | 0 / 3 | 498.729s (includes compilation) | 4.457GiB |
| Release / `52ed850393d1cb420af17fde9170fd1f14308601` | 1014 / 151 | 0 / 17 | 344.200s (warm retry) | 2.586GiB |

The first release run exposed two UI tests reading before Room-backed editor/reservoir rendering completed. Bounded waits for the actual expected UI state were added; all original value/disabled-state assertions remain. The two affected classes passed with2/4, then the entire release suite passed. Product app/rules code did not change between these full debug/release validations. The initial failed run and raw evidence are retained; its peak sampled Java RSS was5.383GiB. No test was excluded to obtain these results. The native instrumentation APK also compiled after a fixture-only name-shadowing correction. Native runtime acceptance and final hosted repetition remain separate required evidence.

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

The first new cold PR gate on source `3ef625d125d6f519045fdb2a90ba978eca506db0` reported the now-corrected readiness regression in445seconds (7m25s), run43786417767. Rules/selected Android work took403seconds, source/protocol checks26seconds, and installed SDK reuse0seconds. Both expensive full/native jobs were correctly skipped. This measures **failure feedback**, not a successful gate: debug lint/APK were not executed on that failed run.

Current fast/full/native Gradle invocations emit timestamped local `--profile` HTML reports under `build/reports/profile/`, retained in workflow artifacts. They separate configuration, dependency resolution and task costs (including Kotlin compilation, tests, lint and packaging) without publishing a Build Scan or sending build data to another service. See the [Gradle profile documentation](https://docs.gradle.org/8.13/userguide/inspect.html#sec:profiling_build). Historical baseline helper provenance separately records cold-build or warm re-sign durations.

The 5–15minute Tier A objective is a target for warm caches, not a measured guarantee. Cold Kotlin compilation can dominate. Compare completed new runs against these recorded baselines before claiming a speedup; include cache hit/miss state, runner image, selected test count, commit and failed/canceled attempts. Full release tests and native journeys remain intentionally comprehensive. This document does not claim a new run passed merely because the workflow was added.

## Owner-compatible release boundary

The owner certificate is `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`; package is `com.yazsras.astravar`. Current candidate defaults are version54 / `4.0.0-convergence1`. Keep the original private key in the established owner environment. A CI disposable certificate is never an update workaround.

Use `docs/BUILD_AND_SIGNING.md` and current release evidence to privately sign or re-sign the exact tested optimized payload, prove every non-signature entry is unchanged, inspect metadata/signature/alignment, verify monotonically appropriate version against the owner's installed app, and record final SHA256/source/certificate. Validate upgrade and persistence on isolated fixtures; physical installation/consent remains separately evidenced. Never uninstall or clear data to bypass signing incompatibility. Publishing a production release is a separate authorized action.
