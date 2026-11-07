# Security and performance findings

Status: investigation and integrated verification in progress.

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

The current audit found `SYSTEM_ALERT_WINDOW`, special-use foreground-service
permissions and a declared overlay service in 2.4.0. Reforge removes the normal
overlay entry points and manifest registration. Historical overlay drawing code
and saved preferences can remain as archival data without an Android service
entry point. The final packaged manifest must be inspected to confirm retirement.

## Data and markup

Rich-text rendering must use typed native elements and validated links. It must
not execute imported HTML/JavaScript or infer active mechanics from narrative
text. Original imported content, provenance and stable record identities must
remain available for recovery. Imported malformed markup and unsafe links need
actual parser tests; those results will be recorded after integration.

## Performance limits of current evidence

The runtime has about 10 GB RAM, no attached phone and no exposed KVM device.
JVM suite time, lint and APK assembly can be measured here. Actual Samsung frame
times, startup latency, battery/Doze behavior, screen-off notification delivery,
TalkBack operation and physical Android 17 compatibility are not yet measured.
Robolectric UI captures cannot establish any of those properties.

No performance improvement is claimed solely from refactoring code. Final test
and build durations will identify the checks and runtime used.
