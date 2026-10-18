# Astravar 2.1

Native Android tabletop companion by Yazsras Studios, continuing the verified
Astravar 2.0 source. Development branch: `feat/astravar-2.1-player-first`.
PR #4 remains draft and unmerged. This branch is a development candidate, not
an approved public production release.

## During play

Five primary destinations: **Armor · Combat · Party · Campaign · Config**.
Armor provides compact character statistics, source-conflict notices, Active
Element and Core Systems. Combat and the native floating overlay share the same
Kotlin engine, dice snapshots and resource accounting. The interactive history
shows the newest 20 entries; complete ZIP backups retain preserved raw history.

Party provides editable sourced identity/status/abilities, typed character and
companion sheets, existing specialized Eek records, shared-item equipment,
quantity events, currencies and history. Transfers keep the same item ID;
revision checks and original command receipts prevent duplicate spending.
Unknown values remain unknown. Individual damage formulas and sourced melee
configuration live in Rules & profile. Missing accepted melee configuration keeps
the corresponding actions locked; Discharge retains the inherited behavior below.
Full authoritative Party statistics still await approved source reconciliation and
import. Sheets support descriptive creature/spell records without invented level-up
grants. Equipping/installing an item does not automatically apply its bonuses;
transfers move whole recorded stacks, with no split/merge command. Wallets track
copper, silver and gold independently.

Campaign retains inventory, inventions, relationships and player-known facts,
with versioned checkpoints and merge-only reviewed backups. Config includes
an eleven-prompt searchable ChatGPT toolkit, device/build/package/certificate
diagnostics, backup tools and authenticated connection readiness.

## Preservation and authority

The internal nexus is permanent. New Shatter and spare-core installation actions
are rejected by the engine; their old serialized records remain readable.
Discharge remains enabled with inherited formula/accounting and 600 seconds of
recorded in-world recovery: 3× primary Force dice, 60-foot range and 15-foot radius.
The later reference's 12d12 Force, 1-mile range, 20-foot radius and once-per-Long-Rest
limit remain unapplied pending adjudication and implementation. Charged Gauntlet
remains locked until its flat bonus has an accepted damage type and sourced profile.
Advanced elements retain accepted upgrade gates.
No real character values, level-ups, speculative upgrades or private review
sheets are seeded. Original approved artwork and launcher portrait are preserved.

Campaign Room migrates additively 1→2→3. Existing weapon Room migrations and
historical backup compatibility remain intact. The same inventory database
owns items, placements, crafting, audits and receipts. Real Notion records are
not automatically imported or declared canonical.

## Connection boundary

The owner-sync foundation has a scoped wire protocol, AndroidKeystore credential
vault, staging queue, verified receipts and an authenticated synthetic reference
service. Remaining engineering includes typed party/item/currency server commands,
atomic domain/outbox writes and application adapters, owner OAuth/device enrollment,
credential renewal and server revocation, production native connection UX and an
authenticated MCP/tool adapter. The current Config page is a fictional acceptance
harness; its generic replacement command does not synchronize real campaign
transactions. Later gates include authorized hosting, actual native Astravar HTTPS
and native ChatGPT Android access, and a shared-record round trip. Real import and
authority cutover require separate approval after acceptance.
The existing private DM Bridge is preserved. The anonymous Worker remains a
separate fictional experiment and must never receive real campaign data.
HTTPS networking is enabled; cleartext is disabled. No paid service is activated.

## Build and release

JDK17, Gradle8.13, SDK36/build-tools35.0.0. From a configured development host:

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/verify_v2_protected_source.py
python3 scripts/verify_ui_foundation.py
python3 scripts/verify_branding.py
python3 scripts/verify_particle_resources.py
bash gradlew :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The dedicated 2.1 workflow checks the actual development head, both build
variants, optimized packaging, native overlay/accessibility and a same-key
synthetic update. Its public APKs are explicitly **CI ONLY** and use a temporary
signer. Never confuse them with the owner-signed candidate.

Permanent release target: `com.yazsras.astravar`, version2.1.0/code24, existing
owner signing identity. The original private identity was recovered and its
certificate matches available historical owner APKs. Signing material is never
stored here or sent to CI. A production release requires final owner approval.
Debug preview package suffixes remain separate. The `.v2statdebug` experiment
cannot update the permanent package in place; keep existing data and backups.

Read [current development checkpoint](docs/ASTRAVAR_2_1_CHECKPOINT.md),
[Party data model](docs/PARTY_RECORDS_V3.md),
[current rules decisions](docs/PLAYER_FIRST_RULES.md),
[owner-sync contract and gates](docs/OWNER_SYNC.md), and
[release readiness](docs/ASTRAVAR_21_RELEASE_READINESS.md).
Exact build/test/artifact results are recorded for their named source commit;
historical pass counts are not proof of this upgrade.

Unofficial companion; not affiliated with Wizards of the Coast, D&D Beyond or
Samsung. No D&D Beyond scraping, screen capture or accessibility automation.
