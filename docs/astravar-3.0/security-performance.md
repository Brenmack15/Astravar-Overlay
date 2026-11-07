# Security and performance findings

Status: final selected native regressions, complete CI at `4732d87` and both lint variants passed; original-owner build, isolated upgrade and actual offline gameplay/restart verification passed.

## Verified source boundaries

- The permanent release package remains `com.yazsras.astravar`; QA builds use the
  existing `.v2uidebug` sandbox. The owner-signing configuration is hash-protected.
- Cloudflare/owner connection code encrypts scoped device credentials using
  Android Keystore AES/GCM, binds credentials to their enrolled HTTPS origin,
  disables HTTP redirects/cookies, bounds response reads and enforces expiry.
- Existing backup exclusions disable Android automatic app backup and exclude
  device credentials. Source review found no destructive Room fallback.
- Existing campaign synchronization distinguishes revisions, pending commands,
  conflicts, immutable receipts and independent readback. Reforge does not
  authorize an authoritative backend migration or anonymous real-data access.
- Pushy consent remains separate and defaults off. Recovery uses constrained
  WorkManager work with bounded retry; opening/editing the character does not
  establish a rest or resource recovery.

These are inspected code properties. Live owner grants, phone storage and hosted
service behavior have not been retested by this static inspection.

## Overlay retirement

The audit found `SYSTEM_ALERT_WINDOW`, foreground-service permissions and a
registered overlay service in 2.4.0. Reforge removes their manifest entries and
normal launch controls. Release lint exposed an otherwise unreachable historical
`startForeground` call; that runtime path was removed rather than restoring a
permission or suppressing the error. The retained service class now immediately
stops on creation/start. Its permission watcher, screen-off receiver, notification
channel/updates and five-second network poll were removed. Configuration changes
cannot recreate a window. Only the private historical view builder remains for
archival drawing tests; saved configuration remains intact.

Manifest merging also exposed WorkManager 2.11.0's default foreground-service
component and normal FGS permission. Both unused declarations are explicitly
removed by manifest-merger rules. Its JobScheduler service, receivers,
initializer and network/boot/wake permissions remain intact. The sole expedited
request is gated to API 31+, with ordinary-work fallback; Pushy's foreground
mode is explicitly disabled. Inspection of the pinned AndroidX implementation
and Android's [expedited-work documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
confirmed that this configuration does not use the pre-Android-12 foreground
fallback. Future long-running/foreground workers or pre-31 expedited requests
require this decision to be revisited explicitly; this is a configured-feature
removal, not a claim that WorkManager never uses foreground services.

A native lifecycle regression grants the old overlay permission in the test
sandbox, invokes creation, SHOW/STOP, configuration change and elapsed time, and
checks that no window, receiver or foreground notification starts. Final-source
execution and actual packaged-manifest inspection are recorded in the
verification ledger; an archived drawing is not evidence of an active service.

## Data and markup

Rich-text rendering must use typed native elements and validated links. It must
not execute imported HTML/JavaScript or infer active mechanics from narrative
text. Original imported content, provenance and stable record identities must
remain available for recovery. Imported malformed markup and unsafe links need
actual parser tests. Eight parser cases and native profile/internal-link cases
passed in the focused run. The native NPC case also compares the exact stored
original content and audit count before and after presentation. New-store
backup, stale-command, atomic roll, offline reopen and recovery-draft cases
passed. Historical Room migration and Android synchronization regression suites
passed during broad runs; the exact-head CI full run is recorded separately.
The final 12-class local regression passed 56 cases in each variant, including
connection recovery, real merged components and retired-service lifecycle.

Actual native captures exposed black headings against custom navy surfaces. This
was corrected in the shared reading-color provider and explicit sheet surface
colors; a measured text-layout contrast regression was added. Corrected-source
native captures and the contrast test passed again in both final selected
variants. This is an example of
a defect found by raster review despite successful semantic UI assertions.

## Performance limits of current evidence

The host reports about 10 GB RAM, but this job has an effective 8 GiB cgroup
memory limit and an eight-core CPU quota. It has no attached phone or exposed
KVM device.
JVM suite time, lint and APK assembly can be measured here. Actual Samsung frame
times, startup latency, battery/Doze behavior, screen-off notification delivery,
TalkBack operation and physical Android 17 compatibility are not yet measured.
Robolectric UI captures cannot establish any of those properties. A software-only
AOSP 35 emulator did boot in 706.770 seconds and run the predecessor app; its
launcher displayed an ANR. This constrained environment is useful for package
and database compatibility checks, not representative frame or startup claims.

During verification, software QEMU retained roughly 4 GB RSS while Gradle used
about 3 GB before the native test worker. Running them together caused host
resource pressure. Emulator and JVM/release-build phases are therefore
serialized; elapsed check times include infrastructure overhead.

No performance improvement is claimed solely from refactoring code. Test and
build durations below identify the checks and runtime used.

Earlier local lint reported zero errors: debug 63 warnings/one hint, release 65
warnings/one hint. Final clean CI reported zero errors with 77/79 warnings and
one hint per variant; both report sets are retained. Remaining warnings are disclosed, including historical
KTX/style suggestions, launcher shapes, SharedPreferences apply suggestions,
WorkManager job-ID range configuration and Compose layout/state guidance.
The selected test/lint/debug-assembly command took 10m 22s; JUnit totals were
123.725 seconds for debug and 135.412 seconds for release. These measure the
verification environment, not application speed on the phone.

The actual owner APK was also installed over owner code 41 on the isolated
AOSP 35 device. Original data and three settings files remained exact after
startup and after native equipment, explicit rolls and cold restart. The tested
fixture retained 26 points of missing HP through equipment changes, and typed
roll faces matched immutable receipts after restart. Actual runtime service
inspection found no overlay service or foreground start. The guest System UI
initially showed an ANR and its Bluetooth process aborted; after Wait, Astravar
regained focus and completed the journeys. The native emulator itself faulted
during cleanup after console shutdown. Its log and diagnostic captures remain
separate from application success evidence; no app crash appeared in the
guest crash buffer.

## Verification harness confinement

The complete `acf936c` CI run passed every debug test; one release crafting
case exposed a Compose test scheduler failure. Its actual stack records frame
application on `arch_disk_io_1` after a Room invalidation, while the same
AndroidComposeView had previously been observed on the test UI thread. The
pinned Compose 1.10 test environment defaults to an unconfined dispatcher.
A shared test-only factory supplies a fresh `StandardTestDispatcher` through
the supported `effectContext` overload, queuing composition continuations for
the test clock instead of applying frames inline on a background emitter.

This affects test scaffolding only. Room executors remain asynchronous; the
stale-material update and all transaction/no-consumption assertions remain
intact. No production dispatcher, dependency, APK, schema, signing input or
application control was changed. The twelve-case pilot passed in each variant;
the subsequent 40-suite local run passed 191 debug cases and 189 release
cases/two intentional skips, with zero failures. It includes all 39 UI suites
and an additionally selected 29-case data HP suite. Independent complete
CI run 42414763933 then passed: 163 rules, 580 debug and 566 release cases,
with three and seventeen intentional Android skips respectively, zero failures
or errors. Both lint variants and package inspection passed. The complete
Gradle command took 36m 5s; its Android JUnit totals were 610.848/608.467 seconds.
These remain verification-environment measurements. An independent source audit verified that all 39 individual
test files differ only in the rule factory/import, leaving every body and
assertion intact.
