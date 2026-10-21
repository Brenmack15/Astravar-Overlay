# Astravar 2.3 — fast campaign synchronization

Continues code36 at `045bfaed33393675c8d8d621bf260636deb04dd8` on an isolated development branch. Package, original owner signing configuration, campaign Room5, weapon stores and accepted rules remain intact. Version defaults are2.3.0/code37. This document describes implemented behavior, not physical-device acceptance.

## Delivery and authority

The private queue accepts an authorized typed transaction. Optional Firebase Cloud Messaging sends only an authenticated wake hint containing transport identities, a grant revision, a nonce and a short expiry. No character fields, gameplay command, source prose or bearer token travels through FCM. Android verifies the sender, HMAC, exact enrollment, expiry and replay identity before scheduling work. It then fetches the original command through the existing authenticated connection. Room commits and immutable receipts remain the authority; enqueue and FCM acceptance are not saved gameplay.

Tokens are encrypted at rest using Android Keystore locally and context-bound AES-GCM in D1. Registration requires an authenticated device and the existing exact campaign grant. Rotation re-registers without expanding scopes. Revocation removes server registration; no data reaches the disposable fictional bridge.

Unique network-constrained coroutine WorkManager jobs coalesce wakes, retain bounded retry/backoff and recover with the original journal/command identity. The periodic fallback remains15minutes, subject to OS deferral. App-open recovery remains. No permanent `dataSync` foreground service or rapid screen-off polling is added. Automatic Pause gates push handling, workers, active UI/overlay synchronization, requests and transaction commits while retaining credentials and queued work. Explicit manual Sync Now remains available.

The opt-in, quiet **Astravar Background Sync** notification reports a verified device timestamp, Syncing, Pending, Offline, Paused or Conflict. Its actions use immutable app-owned PendingIntents. Notification denial or a blocked channel selects normal-priority wake registration; urgent delivery is reserved for visible notifications. A notification does not keep a process alive.

## Compact reads and atomic acquisition

Active-session reads use one authorization and a bounded indexed query. Retained Notion dossiers are excluded from routine session views; provenance, canonical revisions, unknowns and freshness remain explicit. No lossy text compression becomes authority.

An `acquire_item` operation may include `placement:{holderProfileId,expectedHolderRevision,state}` where state is `Carried` or `Equipped`. The holder must already be a same-campaign character profile, including an established companion profile. A companion-state identifier alone does not create or imply a profile. Item and placement are created atomically at revision1, return in the same immutable receipt and retain the same physical item identity on replay. Server queue/receipt triggers and native Room checks reject stale holder dependencies. Existing acquisitions without placement retain their contract.

## Production configuration

Use an owner-approved Firebase Spark project with billing disconnected. FCM is no-cost; Analytics, Firestore, Functions and paid plans are unnecessary. Register Android package `com.yazsras.astravar`. Build-time public client identifiers are `ASTRAVAR_FIREBASE_APP_ID`, `ASTRAVAR_FIREBASE_PROJECT_ID`, `ASTRAVAR_FIREBASE_SENDER_ID` and `ASTRAVAR_FIREBASE_API_KEY`. Empty identifiers leave push visibly unconfigured; they must never be replaced with fabricated values.

The existing owner-private Site requires `FCM_PROJECT_ID`, secret `FCM_SERVICE_ACCOUNT_JSON` and secret `ASTRAVAR_PUSH_KEY_B64` (32 random bytes). Use the Cloud Messaging HTTPv1 sender with its documented OAuth scope and minimum required IAM role. Keep private keys and owner signing recovery material outside GitHub, Android resources, public artifacts and CI. Preserve existing Site secrets, audience and standing grants. Apply additive D1 migrations0004 through0006 in order. Do not enable production push until native controls and private configuration are ready.

FCM uses Firebase installation identifiers for routing. Firebase's privacy documentation explains deletion/retention. The app disables Firebase auto-initialization and analytics collection and initializes messaging only for an enrolled connection. User consent and provider account authorization remain owner actions.

## Measurement and acceptance

Backend timing covers request handling, queue acceptance and FCM send acceptance. Device diagnostics cover accepted push arrival, WorkManager start and Room commit. Warm fixture/read benchmarks are engineering evidence only. Cross-clock stage comparisons require clock-offset checks, and real p50/p95/p99 require repeated actual-device samples, including screen-off, Doze and battery-saver cases. No universal sub-second delivery is promised.

Full acceptance requires a legitimate authorized transaction while the updated Galaxy app is backgrounded: durable queue, authenticated FCM wake, Room commit without a fresh approval within standing scopes, immutable receipt and independent saved-revision readback. Repeat screen-off, Pause/Resume and existing Eek-profile acquire-and-assign. Emulator fixtures do not establish real FCM or Galaxy acceptance. If Firebase/account access or installation is pending, report that state and do not call the upgrade complete.

## Official references

- https://firebase.google.com/pricing
- https://firebase.google.com/docs/projects/billing/firebase-pricing-plans
- https://firebase.google.com/support/privacy
- https://firebase.google.com/docs/cloud-messaging/send/v1-api
- https://firebase.google.com/docs/cloud-messaging/android-message-priority
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/about/versions/17/behavior-changes-all
