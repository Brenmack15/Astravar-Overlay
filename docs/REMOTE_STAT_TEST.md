# Astravar 2.0: confirmed remote ability test

This experiment changes **one ability on the disposable `remote-stat-test-v1` profile**, only after the player taps **Apply**. It does not target `local-player`, Zerg, the combat profile, inventory, Eek or Notion. Enabling it creates a named disposable profile with **no ability scores**. Unknown values remain unknown until an explicit confirmed edit. Existing gameplay calculations are unchanged.

## Install without disturbing existing apps

Build this test with:

```bash
./gradlew :app:assembleDebug -PastravarRemoteStatTest=true
```

Expected metadata: **Astravar 2.0 Stat Test**, **2.0.2-remote-stat-test**, code **22**, package **`com.yazsras.astravar.v2statdebug`**. Verify the delivered APK metadata/signature report. The release signing block is unchanged. Without this property, the existing UI Rebuild debug package remains `.v2uidebug`.

The previous Actions-built UI Rebuild uses a different, unavailable debug private key. This test therefore installs alongside it. It does not update or transfer the previous UI Rebuild's local database. **Keep the release, Inventory Beta, Preview and UI Rebuild installed. Do not uninstall an app to resolve a signature error.** Future updates to this Stat Test package require its matching debug certificate. No backup restore or real campaign import is needed for this test.

## On the Galaxy S26

1. Download the verified **Stat Test debug APK**, extract it if delivered inside a ZIP, and open the APK in Samsung My Files. Temporarily allow this installation source if Android requests it. Stop if Android offers to replace an existing Astravar application.
2. Launch **Astravar 2.0 Stat Test**. Open **Config → Remote Updates** and tap **Enable remote test**. Remote updates default to off. Use **Copy test details** to copy the displayed **session ID**, fixed disposable profile ID and all six **local revisions**; an absent ability has revision **0**. **Copy test session** copies only the session UUID. Share only this fictional test metadata with ChatGPT.
3. Ask ChatGPT to send the prompt below. A transport receipt means the fictional command reached Cloudflare; it does **not** mean the phone received or applied it.
4. Keep the app open and return to **Remote Updates** and tap **Check for updates**. This is foreground polling/manual checking, not an Android background push service. Open the proposal's native overlay. Confirm it names the disposable profile, the intended ability, its actual current value (initially Unknown), the proposed value and the public source label.
5. First tap **Close** or Android Back, or tap **Reject** to verify nothing changes. A dismissal preserves the pending proposal; Reject records a durable decision. Ask ChatGPT to read the matching tiny outcome. Use a **new command ID** for a later, separately reviewed proposal.
6. For a fresh proposal, tap **Apply**. The Armor test-profile ability tile should update immediately. Existing magical modifiers and notes remain intact. A stale local revision prevents application and requires rejection/review of a new proposal. A new session is not authentication.
7. Check the decision's delivery status. If offline or uncertain, reconnect and check again; the durable receipt outbox keeps the original attempt UUID/body. Ask ChatGPT to retrieve the corresponding public result. When finished, tap **Disable remote test** and return to the normal profile view. This stops incoming checks and preserves decisions/data.

The phone must perform these steps before claiming an installed-app end-to-end success. A host or CI test cannot establish Galaxy S26 installation, lifecycle, keyboard or physical display compatibility.

For the requested phone regressions, stay on this disposable profile:

- **Restart:** receive a proposal, close the app before deciding, reopen it and review the same command. Its score must remain unknown/unchanged until Apply. After applying, restart again; the value and single decision must persist.
- **Offline delivery:** enable airplane mode after copying the session. Have ChatGPT post a proposal, then reconnect and check in the foreground. For an already cached proposal, Apply while offline; the local tile changes once and confirmation remains pending. Restart offline, reconnect and check; the confirmation should deliver without a second ability revision.
- **Conflict:** request two distinct commands for the same ability and local revision (read a fresh public transport revision for each POST). Apply the first. The second must show a conflict with Apply disabled; Reject it. Do not replace its expected local revision silently.
- **Duplicate:** have ChatGPT retry the original saved UUID/body and check again on the app. It must keep the original decision and ability revision. An existing receipt can be recovered even after another command advances the public revision.

## Copyable ChatGPT request

Replace the placeholders with the **disposable** values shown by this app. The proposed 16 below is a deliberate fictional test value, not a claim about a real character.

> Send one Astravar **fictional remote ability test**, using the existing public disposable Cloudflare sandbox only. Session ID: `<copied UUID>`. Ability: `INT`. Expected local ability revision: `<copied revision; 0 if absent>`. Proposed fictional score: `16`. Fixed profile: `remote-stat-test-v1`. Fixed source label: `ChatGPT fictional test`. Use strict canonical `astravar.remote-ability` v1 JSON, a fresh canonical UUID, `new_fact`, and the SAME UUID in the outer transport command and nested payload. Read the public connection revision first. Preserve the exact UUID/body after an uncertain response and look up its receipt before retrying. Do not claim the phone has applied anything. After I confirm Apply or Reject on my phone, read the matching `astravar.remote-ability-result` for this command/session. Do not upload or request my real character sheet.

ChatGPT needs a working HTTPS tool or the experimental public bridge tool. The private synthetic bridge (`astravar-sandbox-only` / `demo-merchant-0001`) is a different database and must not be substituted. No credentials or paid services are involved.

At implementation time, this workspace's direct HTTPS route returned **403**, and `experimental_get_public_connection` returned **`PUBLIC_SANDBOX_UNAVAILABLE`**. The experimental plugin is not evidence that the existing native Worker client is broken. If a route is unavailable, report it and retain the command; do not invent successful delivery or introduce another backend. Consult the final build report for actual CI HTTPS regression results.

## Supported host sender

The repository includes a standard-library Python sender for ChatGPT/tool-enabled execution. The operator only supplies fictional session/ability metadata. It cannot choose an origin, profile, source text or arbitrary payload.

```bash
# Read the current PUBLIC transport revision and save an immutable request; no POST yet.
python3 scripts/remote_stat_test.py prepare \
  --session-id 'COPIED-LOWERCASE-UUID' \
  --ability INT --expected-ability-revision 0 --score 16 \
  --state /safe/local/path/astravar-fictional-command.json

# Look up receipt first, then POST only if definitely not committed.
python3 scripts/remote_stat_test.py send \
  --state /safe/local/path/astravar-fictional-command.json

# After a phone decision and connected check, read its tiny public result.
python3 scripts/remote_stat_test.py status \
  --state /safe/local/path/astravar-fictional-command.json
```

Use a real writable local path, retain the state file and receipt companion, and never commit them. After a lost response, repeat **send using the same state file**. It will recover the receipt without posting a second event. A definitive public transport `409 REVISION_CONFLICT` does not alter that request; retain it, read/review the latest state and prepare a separately reviewed new command with a new state filename. The local ability revision is independent of Cloudflare's connection revision. Never replace a local ability revision with the public transport revision.

A `status` result is an unauthenticated public client's report. Compare it with the phone's local decision; do not treat a source label or public receipt as sender authentication.

## Exact wire contract

All strings below are encoded without extra whitespace, extra/duplicate keys, reordered keys or loose number types. UUIDs use lowercase canonical spelling. The local ability revision is **0 for an absent row**, otherwise the existing revision. The accepted score range is the existing `CampaignRecords.saveAbility` range, 0–1000; this experiment adds no gameplay rule.

```json
{"type":"astravar.remote-ability","v":1,"commandId":"05469a20-d289-4d8c-8522-bce258afbb2b","sessionId":"a56f6040-4ae8-4015-9cdc-735ea010d363","profileId":"remote-stat-test-v1","ability":"INT","expectedRevision":0,"score":16,"source":"ChatGPT fictional test"}
```

The example IDs are documentation fixtures: use the app's actual session and a **fresh command UUID**, not these fixtures. Put that exact string in the existing milestone's `detail` field. The outer body remains the existing seven fields: `campaignId`, `connectionId`, `kind`, `detail`, `commandId`, `expectedRevision`, `confirmed`. Use campaign `astravar-public-disposable`, connection `sandbox-merchant-0001`, kind `new_fact`, outer `commandId` equal to nested `commandId`, outer `expectedRevision` equal to the freshly read Cloudflare revision and `confirmed:true` for posting the **fictional proposal**, not for applying it on Android.

The only application outcome body is:

```json
{"type":"astravar.remote-ability-result","v":1,"commandId":"05469a20-d289-4d8c-8522-bce258afbb2b","sessionId":"a56f6040-4ae8-4015-9cdc-735ea010d363","status":"APPLIED"}
```

`status` is `APPLIED` or `REJECTED`. It contains **no score, magical modifier, notes, profile name, other abilities or sheet data**. The outcome uses a distinct durable outer transport UUID. Uncertain attempts retain their UUID/body; only a definitive 409 allows a new outcome transport attempt after another read. Conflicting proposals stay local until explicitly rejected; they do not silently overwrite or rebase.

## Acceptance checklist and evidence boundaries

| Scenario | Expected behavior |
| --- | --- |
| Feature off / release build | No remote stat application or public stat upload; debug gate enforced |
| Opt in with unknown abilities | Creates only disposable profile; all scores remain unknown |
| Valid delivery | Durable pending proposal; no score written before Apply |
| Dismiss / Android Back | Proposal remains pending; no implicit rejection/application |
| Apply | Existing `saveAbility` transaction, deterministic local receipt, immutable audit event |
| Reject | No ability write; tiny rejection queued durably |
| Same command delivered/retried | Same decision, one ability audit event, no second application |
| Restart before decision | Session and pending proposal survive |
| Restart after save before inbox completion | Saved approval/receipt recover without a second write |
| Offline | Cached proposals remain reviewable; decisions/receipt attempts retained until retry |
| Local revision changed | Apply blocked; current data, notes and magical modifiers preserved |
| Wrong session/profile/source/schema | No usable proposal and no character change |
| Public transport revision conflict | No blind rebase or replacement of an uncertain command |
| Existing game/release data | Separate Android package; existing database schemas and migrations retained |

Run `python3 -m unittest discover -s scripts/tests -v` and the repository's Android/rules/lint/build gates. `RemoteStatLiveTest` is opt-in with `ASTRAVAR_LIVE_REMOTE_STATS=true`; it uses the actual Kotlin HTTPS client, file-backed Room close/reopen, one public fictional proposal, a **simulated explicit player approval**, a tiny public acknowledgment and exact replay. Ordinary test runs skip it. A passing live host test demonstrates that transport/repository path, not physical S26 completion or authenticated/private campaign synchronization.
