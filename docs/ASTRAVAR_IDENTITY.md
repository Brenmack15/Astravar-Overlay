# Astravar identity and permanent updates

## Approved branding

The owner-supplied `Astravar_App_Identity_Icon_Handoff.zip` targets draft PR #4,
branch `feat/astravar-2.0-ui-rebuild`, reference commit
`f44612e5756daa2b2cfa94a51c26156cc63547fc`. The branch still matched that commit
when this handoff was applied. Its short-context patch required
`git apply --unidiff-zero`; no newer product changes needed reconciliation.

- Launcher label is **Astravar** for release, ordinary UI debug and remote-stat debug.
- All 14 supplied Android resource files are copied byte-for-byte: ten legacy
  square/round icons, two adaptive icon definitions, padded foreground and background color.
- Original approved portrait SHA-256:
  `95f3c0cb6f56a5f8ca53682609cf7672e4b38e8631a11c2cdce3f27afac4b5a7`.
  `scripts/branding_assets_sha256.json` records hashes of the supplied runtime resources.
- Global header is **ASTRAVAR**; the armor model's `Mk.I` is removed only from that header.
- Config → Help & diagnostics → App version reads `BuildConfig.VERSION_NAME`
  and `BuildConfig.VERSION_CODE`. Package, device and permission diagnostics remain.

No package, version-code default, version-name suffix, signing block, mechanics,
campaign transaction, remote-stat implementation or Room schema was changed.
Only the main manifest's label/icon/roundIcon attributes differ from its protected
baseline. Its one preservation hash was explicitly advanced, with its original
hash retained as provenance; the other 78 foundation hashes, all 33 recovered
source hashes and the owner signing-block hash are unchanged.

## Current identities

All these packages have separate local data. Identical launcher names do not merge them.
Use Help & diagnostics to distinguish installed variants.

| Channel | Application ID | Default visible version/build |
|---|---|---|
| Owner release | `com.yazsras.astravar` | `2.0.1` / 21; secure release environment can override |
| UI debug | `com.yazsras.astravar.v2uidebug` | `2.0.1-ui-rebuild` / 21 |
| Remote-stat debug, current CI | `com.yazsras.astravar.v2statdebug` | `2.0.2-remote-stat-test` / 22 |

The older Preview (`.v2debug`) and Inventory Beta (`.debug`) also remain separate.
The existing CI creates ephemeral debug certificates. This branding build is not
a guaranteed update for any existing debug installation. Version/code defaults
are intentionally unchanged by this branding handoff. Do not uninstall, clear
storage, change package IDs or replace keys to work around a rejected installation.

## One permanent application

Recommended permanent channel: **`com.yazsras.astravar` signed with the existing
owner RSA-4096 PKCS12 key**. Historical public certificate SHA-256:
`4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
That certificate is documented historical evidence; compare it with an actual
owner APK before approving a new signed candidate. The private key is not in
this repository and was not accessed or changed for branding.

Before producing an approved permanent candidate:

1. Inventory the actual installed package/version/certificate with read-only
   device inspection or an APK exported by the owner. Keep every installed app.
   Make and validate user-selected campaign and weapon backups independently.
2. Recover the existing owner keystore in an owner-controlled secure build
   environment. Reuse `ASTRAVAR_KEYSTORE`, store/key passwords and alias already
   supported by `app/build.gradle.kts`; never put them in source, chat, logs or
   public artifacts. Verify the public certificate fingerprint. A missing key
   blocks owner signing; creating a replacement would break the update chain.
3. Assign a monotonically increasing `ASTRAVAR_VERSION_CODE`, higher than every
   delivered owner release; set `ASTRAVAR_VERSION_NAME`. Keep the same package
   and exact signer for each future release. Keep secure independent key backups.
4. Resolve and verify the pre-existing release resource crunching failure first:
   earlier release probes reported seven legacy particle `.png` failures; current
   byte inspection confirms all ten particle `.png` files contain JPEG bytes. This branding change preserves those protected assets. Repair is
   a separate reviewed change; no release APK is claimed here.
5. Run rules, Android, release, migration and lint checks; verify signature,
   package, version, icon and alignment on the actual owner-signed candidate.
   On a disposable emulator, install a previous owner-signed build, seed fictional
   campaign/weapon data and approved remote-stat receipts where supported, then
   update with the candidate. Compare records, revisions, modifiers, audit,
   deduplication, settings and roll history without clearing storage.
6. Deliver through one owner-approved distribution path. A manual signed APK
   update needs Android's installation confirmation; an update checker alone
   does not establish silent updates. Store publication or a new distribution
   channel needs separate approval. No such channel is created by this work.

An owner-signed release can update an existing owner release with matching
identity. It cannot update the differently named/signed debug apps. Data from
those apps requires explicit, validated export/import into the permanent app,
leaving the originals installed. The existing merge-only campaign restore can
reject conflicting IDs, and weapon restore is a separate replacement flow;
neither is automatic migration. The separate fictional outbox and remote-stat
approval/receipt databases are not transferred by those backups. Confirm any
necessary migration before switching daily use. Real campaign/Notion records
must never be used as a test fixture.

[Android's official update requirements](https://developer.android.com/google/play/app-updates)
allow equal-or-higher version codes, matching application ID and certificate
(or valid signing lineage). This project's delivery policy uses a strictly
higher code for each release and preserves the existing key; no rotation is planned.

## Verification

`python3 scripts/verify_branding.py` checks the exact supplied resource hashes
and main manifest. CI additionally passes the assembled APK's `aapt dump badging`
output to verify actual label, icons, package and version. Existing source gates,
Python tests, Kotlin rules tests, Android/Room/remote-stat/UI regressions, lint,
debug assembly, signature and 16 KB alignment checks remain in the workflow.
Live Cloudflare writes are opt-in and are not needed for branding verification.

The work neither installs an APK on the owner's devices nor establishes physical
Samsung coverage or permanent in-place update compatibility. PR #4 stays draft
and unmerged. Current run links and exact results are recorded in its description.
