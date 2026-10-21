# Owner connection acceptance and release checklist

This document defines evidence required for the direct character read/update loop.
It does not record a passing handset or ChatGPT acceptance result. Use fictional
records until the owner separately approves live-data activation.

## Evidence boundaries

The verified starting source for this change is
`833bc580fce83a503d09f2e85a711c950dc2d897` on
`feat/astravar-2.1-player-first`, draft PR #5. Its successful Actions run
[39310154756](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/39310154756)
was a code33 immutable-product native harness recheck. It predates the new owner
connection and cannot verify these product changes.

The new `owner-connection.yml` workflow tests its exact pushed source and uses
version code34. Its optimized APK has a disposable CI signer. It must never be
delivered as an update to an owner-signed installation. Unit tests, Robolectric,
backend HTTP/MCP protocol tests, deployed authenticated tool invocation, real
Android HTTPS, native player approval and physical Galaxy use are separate
acceptance layers. A successful layer must not stand in for another.

## Fictional shared-record acceptance

Capture a non-secret evidence log with timestamps, command and record IDs,
expected/resulting revisions and receipt IDs. Exclude bearer tokens, pairing
secrets and real campaign records.

1. On the approved service, enroll a test device and narrowly scoped ChatGPT
   connection belonging to the same authenticated owner and fictional campaign.
2. In native Astravar Config → Owner connection, expose only the explicitly
   labeled fictional character. Verify record ID and initial ability revision.
3. In the actual ChatGPT host, discover and invoke `get_character` or
   `get_character_abilities`. Compare the value, record ID and revision with
   Astravar. A REST client or MCP test harness is not ChatGPT discovery evidence.
4. Invoke `propose_record_update` for one ability score with the exact existing
   value and revision, unique command ID and source. Read back the pending
   proposal. Verify the stored character remains unchanged.
5. Astravar refreshes over authenticated HTTPS and displays current and proposed
   values. The player taps Approve in Astravar. Verify exactly one local revision,
   audit entry and durable outcome are committed.
6. Read the immutable receipt independently through ChatGPT. Independently read
   the resulting character and verify the same saved value/revision. A queued
   acknowledgment alone is not confirmation that the service accepted it.
7. Force-stop/reopen Astravar and repeat the read. Export a user-selected campaign
   backup, inspect its preview and restore it on an isolated acceptance device.
   Verify value/revision/audit persistence. Device credentials must not travel in
   a campaign backup; re-enrollment is required on another installation.
8. Repeat with rejection and verify no character revision changes. Repeat an
   identical command and verify the original outcome, then change content under
   the same ID and require rejection.
9. Stage two proposals at the same revision. Apply one, then require a visible
   conflict for the stale proposal. Do not auto-rebase or overwrite a local edit.
10. Disconnect the network before approval/acknowledgment, restart, reconnect and
    retry. Verify one local commit and one immutable server result, including a
    lost-response retry. Verify offline status reflects an actual request error.
11. Test wrong scope, invalid token, expired enrollment, revoked device and
    removed ChatGPT authorization. Authorization must precede receipt lookup and
    all reads/writes. Revoked devices must fail actual server requests.

A complete pass requires the actual native application plus the actual ChatGPT
host against the same service. Until that evidence exists, report the loop as
pending, even if backend and app repository tests pass.

## Permanent candidate identity

- Package: `com.yazsras.astravar`.
- New candidate code:34, greater than the known source default33. The current
  Galaxy's installed version remains unobserved. If it is34 or newer, choose a
  higher candidate code before signing; never downgrade or uninstall to bypass
  a conflict.
- Owner certificate SHA256:
  `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
- Reuse the private recovered owner key. Do not place that key, recovery ZIP,
  passwords or credentials in GitHub, CI artifacts or the release bundle.
- Freeze the tested source and record its commit and full APK checksum. Verify
  package, version, certificate, non-debuggable status, original artwork,
  native-library/ZIP alignment and Room migration preservation.
- If re-signing a tested CI APK, compare every decompressed non-signature entry
  with the owner APK and report the result separately from signer acceptance.
- Prove a same-owner-key in-place update with fictional persisted data before
  claiming update preservation. A temporary CI-key update does not prove an
  owner-key update. Physical phone compatibility is an additional gate.

## Phone-only installation and pairing

The final delivered file must be explicitly identified as the owner-signed
candidate with its source commit and SHA256. Open it from My Files → Downloads
on the Galaxy S26. Grant the file source installation permission only when
Android asks. A compatible existing permanent installation should offer Update.
Stop if Android reports a signer/package/version conflict. Keep the installed
app and its records; do not clear data or uninstall.

Pair only with the reviewed private HTTPS owner service and requested fictional
read/propose/device-approval scopes. Complete owner authorization in the supported
browser or account connection flow, then approve the identified device and
scopes. Do not paste signing keys or ChatGPT cookies. Verify connection health
with a real scoped request. Keep live campaign synchronization off until the
fictional acceptance sequence and separate live-data approval are complete.
