# Astravar 2.3.2 — Cloudflare synchronization and optional Pushy wake

Continues the recovered 2.3.1/code38 source at `e00359f6c9f6ad6ecb116937fbe8471536237615` on `feat/astravar-2.3-ultra-fast-sync`. The version defaults are 2.3.2/code39. The permanent package `com.yazsras.astravar`, original owner signing configuration, campaign Room 5, weapon stores, artwork and accepted rules remain intact. This document states implementation and acceptance boundaries; it is not evidence of physical-device verification.

## Cloudflare-only operation

Cloudflare Workers and D1 remain the authenticated campaign service. The server durably accepts an authorized typed transaction; Android fetches it through the existing paired connection, validates scopes and revisions, commits it through Room, and returns its immutable receipt. Independent saved-revision readback remains the acceptance authority. A queued command or provider send acknowledgement is not a saved gameplay change.

Foreground and app-open recovery, explicit manual Sync Now, network-constrained periodic WorkManager recovery, notification controls and offline retry work without Pushy credentials, registration or delivery. Periodic work has a 15-minute minimum interval and may run later under Android scheduling restrictions. No rapid screen-off polling or permanent foreground service is introduced.

Automatic Pause gates push handling, workers, active UI/overlay synchronization, requests and transaction commits while retaining credentials and queued work. Explicit manual Sync Now remains available. The quiet, opt-in **Astravar Background Sync** notification reports a verified device timestamp, Syncing, Pending, Offline, Paused or Conflict and uses immutable app-owned action PendingIntents. Notification permission does not grant background execution or keep a process alive.

Wake drain identity uses a persisted increasing sequence, so same-millisecond signals and a backward clock correction cannot suppress later recovery. A sync drains up to four bounded pending pages while independently verified receipts show forward progress. A progressing backlog retains its wake generation and uses bounded retry/backoff. A page blocked entirely on manual approval stops without spinning and reports pending work accurately. The server pending contract has no cursor: an entirely blocked first page can defer automatic commands behind it until those approvals or conflicts are resolved.

## Optional Pushy wake

The **Use Pushy wake notifications** control is off by default and requires an enrolled campaign connection. Android uses the pinned official `me.pushy:sdk:1.0.129` SDK, disables its FCM fallback and push-payload logging, and retains TLS hostname verification. Firebase application configuration, Messaging dependencies, initialization and sender authorization are no longer release requirements. Existing Google Cloud and Firebase resources are not deleted or modified.

Registration authenticates the paired device and its exact campaign grant and declares `provider:"pushy"`. Astravar encrypts its token copy with Android Keystore. The Pushy SDK stores its own installation token and authentication key unencrypted in app-private SharedPreferences; the Android application sandbox and disabled backup/transfer protect that SDK state. No campaign data is passed to the SDK. The D1 token copy uses context-bound AES-GCM. The ciphertext envelope binds the Pushy provider; legacy FCM registrations are ineligible for Pushy sends. Rotation, unpairing and revocation preserve the existing grant and registration boundaries.

Notifications are data-only authenticated wake hints. Version 2 contains only `type`, `version`, `issuedAt`, `expiresAt`, `wakeId` and `signature`. No campaign, character, grant or credential IDs, gameplay values, command bodies, source prose or bearer credentials are sent to Pushy in the notification payload. Android validates the exact payload shape, HMAC, expiry, current enrollment and durable replay identity before scheduling unique network-constrained work. Actual commands still travel through authenticated Cloudflare requests.

The existing owner-private backend uses private `PUSHY_API_KEY` and `ASTRAVAR_PUSH_KEY_B64` configuration. Missing provider configuration disables push sending without disabling campaign sync. The API key is used only on the backend; it must not enter Android resources, public source, CI logs or downloadable release files. The sender targets the fixed HTTPS Pushy API, sends only a short-lived wake and coalesces undelivered bursts. Delivery failure cannot remove or roll back a durably queued command. Preserve the existing Site audience, secrets, standing grants and additive D1 migration history.

The official Trial offering is free forever for up to 100 devices and requires no credit card. This implementation does not authorize a Pro subscription, billing activation or account upgrades. Creating the provider app and authorizing its private API credential are separate owner account actions when no authorized provider session is available. Neither action blocks a Cloudflare-only APK.

## Background delivery limits

Pushy's MQTT connection can be delayed by Doze, App Standby, Android's Cached Apps Freezer, battery restrictions and Samsung process management. A notification control or socket does not bypass those restrictions. The app does not request special exact-alarm access or battery-optimization exemption and does not start Pushy's optional permanent foreground service. Its delivery therefore remains best-effort; recovery through foreground/manual sync and scheduled work is retained.

Emulator tests with an injected authenticated wake can verify native validation, screen-off state, WorkManager scheduling, Pause/Resume, battery-saver/Doze gating and Room persistence. They do not establish provider delivery, unrestricted Samsung background execution or a real screen-off delivery latency.

## Compact reads and atomic acquisition

Active-session reads use one authorization and a bounded indexed query. Retained Notion dossiers are excluded from routine session views; provenance, canonical revisions, unknowns and freshness remain explicit. No lossy text compression becomes authority.

An `acquire_item` operation may include `placement:{holderProfileId,expectedHolderRevision,state}` where state is `Carried` or `Equipped`. The holder must already be a same-campaign character profile, including an established companion profile. A companion-state identifier alone does not create or imply a profile. Item and placement are created atomically at revision 1, return in the same immutable receipt and retain the same physical item identity on replay. Server queue/receipt triggers and native Room checks reject stale holder dependencies. Existing acquisitions without placement retain their contract.

## Release and acceptance

The Cloudflare-only release requires source/backend/Android regressions, preserved historical schemas and stores, original artwork, the permanent package, an increasing version code, 16 KB alignment, and an APK verified against the historical owner certificate. A disposable CI signer exercises optimized packaging but cannot update the owner's installation. Install the verified owner-signed APK as an update; do not uninstall Astravar or clear application storage.

Optional Pushy acceptance additionally requires authorized provider configuration and an installed updated Galaxy app. Use a legitimate authorized transaction while the app is backgrounded, then verify the durable queue, actual provider delivery, Room commit, immutable receipt and independent saved-revision readback. Repeat screen-off, Doze/battery restrictions, reconnect and Pause/Resume, and verify the existing Eek-profile acquire-and-assign operation atomically. Never invent gameplay or mutate production records merely to exercise a failure.

Record backend request/queue/send-acceptance timings separately from device wake/worker/Room/receipt timings. Cross-clock stage comparisons require clock-offset checks. Real p50/p95/p99 and reliability claims require repeated actual-device observations. Report missing provider or physical-phone evidence as a specific optional-delivery limit, without blocking or misrepresenting the Cloudflare-only release. No guaranteed instantaneous delivery is claimed.

## Official references

- [Pushy pricing and free Trial](https://pushy.me/pricing)
- [Android SDK installation](https://pushy.me/docs/android/get-sdk)
- [Android SDK APIs](https://pushy.me/docs/sdk/android)
- [Server send API](https://pushy.me/docs/api/send-notifications)
- [Doze delivery restrictions](https://support.pushy.me/hc/en-us/articles/360043423332-How-can-I-send-notifications-to-Android-devices-in-Doze-power-saving-mode)
- [Cached Apps Freezer restrictions](https://support.pushy.me/hc/en-us/articles/44515682336653-How-can-I-receive-notifications-in-the-background-on-devices-with-the-Cached-Apps-Freezer-enabled)
- [Android persistent-work scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Android Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
