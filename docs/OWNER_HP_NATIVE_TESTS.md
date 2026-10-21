# Unattended HP native acceptance

`owner-hp-native.yml` checks out the exact pushed source and runs a disposable
accelerated API 36 emulator. It never connects to the owner's phone or private
Site and never receives the owner signing key. The existing owner workflow still
runs all JVM, rules, lint and optimized APK checks; these native checks add an
independent integration layer.

The workflow builds the published code 34 debug baseline at
`e24d2d2c64fb187a68a2fdbb474fe122cbd6e3f5`, creates only two explicitly fictional
existing profiles, and installs the new code 35 debug app with the same temporary
debug signer. Store hashes must match immediately before/after `install -r`.
This is a debug-key update test, not evidence of an owner-key phone update.

An isolated Node HTTPS/SQLite server imports the tracked Worker, HTTP and MCP
handlers. A process-local ephemeral CA configures only the instrumentation
process; production TLS and hostname verification are unchanged. The verified
fresh AVD uses a session-only writable system image with two reserved `.example`
hosts mapped to guest loopback. Both names must resolve to `127.0.0.1` before any
app credentials are injected; a failed remount or write aborts the job. Android's
[documented writable-system option](https://developer.android.com/studio/run/emulator-commandline)
creates a temporary image destroyed on emulator exit. `adb reverse` connects guest
port 443 to the runner's loopback server. A wrong certificate hostname must still
be rejected. No system-wide CA or production TLS override is installed. The temporary
gateway strips caller identity headers and accepts its privileged controller
only with an ephemeral secret held outside retained evidence.

If a successful plain remount requests a reboot, the harness explicitly reboots
and requires a changed kernel boot ID and completed boot before rechecking AVD
ownership. This avoids treating the intentional `adbd` disconnect from
`remount -R` as a completed remount. Any remount, ownership, hosts write or
resolution failure still aborts before app credentials are installed.

Before injecting credentials, the harness records host/guest epoch differences
and synchronizes only the verified fresh emulator clock, with a measured bounded
two-second setting margin. The isolated test DNS does not provide network time.
Native bootstrap diagnostics retain only binding/window booleans and numeric
age/expiry differences, never challenge values, keys or credentials. Production
expiry, origin, campaign, nonce and replay guards are unchanged.

Native test phases exercise:

1. Real emulator AndroidKeyStore encryption/decryption, tamper rejection and
   one-use import; separate owner-approved device pairing; native publication.
2. Independent HTTP MCP read, unchanged values before approval, actual production
   Compose Approve/Reject controls and immutable receipt/readback checks. A Flow
   collector on a second production-open Room instance must observe approval.
3. Offline local approval, force-stop, gateway/SQLite restart, lost HTTP response,
   idempotent retry and a single saved revision.
4. Validated export/restore into another Room database, preserving the original
   HP, maximum/temporary HP, unrelated profile, ability, audit and receipts.
   Credentials must be absent from the backup and conflicting restore is denied.
5. Stale/reused commands, out-of-range HP, a simultaneous local edit, invalid and
   expired credentials, confirmed device revocation and removed read authority.

The host splits instrumentation invocations across process stops. Temporary
keys, gateway credentials and SQLite state are removed on exit. Retained output
contains fictional checkpoint JSON, test logs, source ID and update hashes.

These are real native HTTPS integration tests against an isolated test gateway.
Their MCP requests are protocol calls, not ChatGPT-host discovery. AndroidKeyStore
on an emulator does not establish Samsung hardware-backed key behavior. API 36
emulation is not Galaxy S26/Android 17 coverage. Actual owner-device, plugin and
release-signature evidence remains separately identified in the readiness report.

The prior code 34 importer failure is retained as historical evidence. The later
exact-e24 legacy workflow `39533545098` completed successfully without weakening
its assertions; it must not be confused with an unattended HP acceptance result.
