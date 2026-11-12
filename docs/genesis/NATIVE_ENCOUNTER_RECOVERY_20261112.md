# Native encounter checkpoint recovery — 12 November 2026

## Status and preservation boundary

This is a technical handoff reconstructed from the working agent's retained execution history after the execution environment became unavailable. It is **not** a recovered source patch, a blob-verified copy of local commit `991dd4a`, or a passing native release. No source file is recreated in this documentation-only checkpoint.

- Repository: `Brenmack15/Astravar-Overlay`.
- New recovery branch: `checkpoint/astravar-genesis-native-recovery-20261112`.
- Verified remote base: `62e182afc0ce58cfec7c26ad2d05f3fd5792606f` (`test(genesis): verify additive encounter schema without changing history`). This publication corresponds to local generated-schema/integrity checkpoint `764cf51`.
- Inaccessible local worktree: `/workspace/scratch/30508fda364e/genesis-encounter-continuation`.
- Local branch: `recovery/astravar-genesis-encounter-20261112`.
- Locally committed native work: `991dd4a`, subject `test(genesis): verify native encounter history and schema nine retention`; observed commit output: 10 files, 642 insertions, 33 deletions. The complete commit SHA and original blob hashes were not retained in accessible history.
- Local HEAD immediately before that commit was `3856bb5` (`test(genesis): make encounter coroutine tests JUnit compatible`). Do not assume this local ancestor was published as part of the remote base above.
- A read-only `git show 991dd4a:...` recovery attempt returned `No usable execution environment is available`. There was no reset, clean, overwrite or reinitialization.
- Some full file text and diffs remain in conversation history, but all ten original blobs cannot currently be recovered or independently hashed. Treat reconstruction as new, unverified work until compared with the original checkout if it returns.
- Existing published D/C branches and PR #15 were not moved or edited by this recovery. No owner data, private exports, keys, credentials or APKs are included. No CI was dispatched, and the documentation commit uses `[skip ci]`.

## What survived on GitHub

The verified base includes generated `app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/9.json` and the additive encounter integrity guard. Reported generated schema9 SHA-256 is `268860d5fc88e9f2a79406649230bc9fc71a59e19aefefcdec9a2a4330286111`; Room identity hash is `bde66989e7d5999afcd1960e94e04758`. All 40 schema8 entities were unchanged; schema9 adds two entities, 42 total. The backend and UI source at that base remain the reconstruction starting point. The native additions and helper updates below belong to local `991dd4a` and are not supplied by this handoff.

## File inventory for local 991dd4a

| File | Local change to recover |
| --- | --- |
| `overlay-target/src/androidTest/java/com/yazsras/astravar/GenesisEncounterInstrumentationTest.kt` | New 148-line real-UI fictional encounter journey |
| `scripts/genesis-encounter-readback.py` | New 177-line independent SQLite and immutable-receipt proof |
| `scripts/tests/test_genesis_encounter_readback.py` | New 218-line synthetic/adversarial host suite, 17 tests |
| `scripts/ci-emulator.sh` | Encounter stage after crafting; explicit schema9 for D gates |
| `scripts/genesis-connected-readback.py` | Explicit expected schema8/9, default8; empty encounter checks on9 |
| `scripts/genesis-crafting-readback.py` | Explicit expected schema8/9, default8; encounter tables unchanged/empty before encounter stage |
| `scripts/genesis-upgrade-readback.py` | Retain schema6 baseline and add explicit target9 without weakening raw row/history checks |
| `scripts/native-campaign-import-readback.py` | Support9 and protect both encounter tables in complete snapshots |
| `scripts/native_recheck.py` | Only extend expected schema CLI choices to include9 |
| `scripts/tests/test_native_campaign_import_readback.py` | Retain schema8 classes; add schema9 subclasses and deletion rejection; unsupported sentinel9 becomes10 |

No app production source, app test source, generated schema, signing configuration or historical code49 fixtures were changed by local `991dd4a`.

## Native journey contract

Class/test: `GenesisEncounterInstrumentationTest.manualOrderRoundsAndReopeningNeverChangeActorMechanics`. It uses `ObservedUi`, `NativeFailureEvidence`, real native controls and a mandatory `getprop ro.kernel.qemu == 1` guard. No DAO injections or owner records.

Use the prior connected/crafting fixture's `Genesis Native Campaign` and `Genesis Native Hero` (HP14/20). Create source-only NPC `Genesis Native Encounter Witness` from Campaign > People > Create NPC. Enter only its name; Review & save > Create NPC. Check `NPC saved in campaign records`. Unknown statistics remain unknown.

Open Encounters > Create encounter. Name `Genesis Native Manual Encounter`; notes `Fictional manual tracker without mechanical changes`. Choose Participants; add NPC first, then hero. Enter initiative19 for NPC and7 for hero. Tap Move hero up. Review & save must show hero first, NPC second, despite lower initiative. Save encounter; Review start; confirm both entered initiative values; toggle `I confirm this turn order`; Start encounter.

Assert Round1 and current hero. Open NPC's source modal and its explanatory unknown-mechanics text; no playable action route may appear. Close it. Open hero's actions: actual selected Combat, Turn economy and HP14/20. Android Back must select Sheet with unchanged HP14/20; the next Back returns to the same encounter/round/current hero. Do not bypass a failing product route.

Tap Next turn: Round1 NPC. Tap Next turn: Round2 hero. Capture `genesis-encounter-round-two.png`. End encounter > End encounter? > End encounter. Verify `Ended · round 2`, close, force-stop/relaunch, reopen the same encounter and verify ended state. Capture `genesis-encounter-ended-after-restart.png`.

Explicit Reopen setup > Reopen setup? > Reopen setup resets only tracker status to setup, round0 and null cursor. Verify manual participant order. Close, force-stop/relaunch, reopen again and verify setup with no Current turn label. Capture `genesis-encounter-setup-after-restart.png`. No automatic start/advance may occur.

Finally close, navigate Character, Switch character to `CI Native Hero Revised`, select Sheet and assert HP31/37, restoring the prior native pipeline's selected fixture. No healing, resource use/refill, rolls, mechanical turn resets or inventory changes are authorized by this stage.

## Independent readback contract

CLI: `genesis-encounter-readback.py --before-database <closed after-crafting DB> --database <closed after-encounter DB> --synthetic-emulator --output <result.json>`.

Read both databases without writing. Validate exact actual Room-generated schema9 and foreign keys. Reuse strict JSON, hashing, immutable snapshot and SQLite helpers from connected/upgrade readback; never handwrite substitute schema9 DDL.

Require same fictional campaign, original playable PLAYER hero and one new nonplayable NPC authority row with no owner. NPC level/HP/tempHP/AC remain null, no playable character exists, and its same-ID connection entity is Person. Bind its create-actor receipt to exact request `[name,"NPC",campaignId,null,null]`, fingerprint and outcome.

Require exactly one named encounter and manual participant rows hero initiative7 position0, NPC initiative19 position1. Exactly six parent authority receipts of kind `encounter-v1` must form the immutable chain below:

| Revision | Operation | Status | Round | Current actor |
| --- | --- | --- | --- | --- |
| 1 | SAVE_DRAFT | DRAFT | 0 | null |
| 2 | START | ACTIVE | 1 | hero |
| 3 | NEXT | ACTIVE | 1 | NPC |
| 4 | NEXT | ACTIVE | 2 | hero |
| 5 | END | ENDED | 2 | hero |
| 6 | REOPEN_SETUP | DRAFT | 0 | null |

Verify each request fingerprint, expectedRevision, before/after linkage, exact title/notes/campaign/participants, creation/last command IDs, and strictly increasing timestamps. Creation/start membership proofs must bind to the actual original association receipts and authority revision/role/campaign. SAVE_DRAFT contains exact input order; START's review fingerprint freezes prior snapshot and members. Later operations contain no draft/start review or membership changes. Final SQL tracker must equal the last immutable outcome.

Compare every prior row in every table for exact values and identity. For non-whitelisted tables, require whole-table snapshots identical, including no-PK metadata. Permit append-only additions exclusively for this NPC's profile, connection, actor authority, two source audits/receipts, one tracker, two participants and seven parent authority receipts (NPC creation plus six tracker commands). Reject any extra canonical receipt, turn reset, roll, source mutation, ledger quantity change, unrelated actor or erased history.

Strengthened final checks bind each source child command to `genesis:` + SHA256(parentCommandId + `:identity`/`:npc`). Audit record types must be character-profile/connection, kind CREATE, prior0/result1, null beforeJson and exact original afterJson. Source receipt kind/result revision/time and outcomeJson must match. Two added adversarial tests reject corrupted original proof and unrelated source child command.

Positive result reports schema9, exact IDs, six operations, final draft revision6, retained historical round2, manually ordered initiative, all prior mechanics/history unchanged and physical_device_acceptance=false.

## Schema9 integration details

- Preserve helper defaults8 and explicit choices8/9, keeping historical schema8 fixture behavior intact.
- Upgrade baseline remains schema6. Explicit D target9 adds only the existing Genesis additions plus the two new encounter tables. Verify zero encounters during upgrade; retain exact raw rows/receipts and the independent later canonical UI heal checks. `verify_write` requires matching schema/identity hashes.
- Connected backup occurs before encounter creation. On9 require both tables empty and exact backup state. Empty `encounters`/`encounterParticipants` lists can be omitted because backend uses `@EncodeDefault(NEVER)`; interpret missing lists as empty here.
- Crafting snapshots precede encounter creation; both new tables must remain empty and unchanged.
- All later import, mapped import and rollback snapshots must preserve both new tables including existing encounter rows/history. Add schema9 test subclasses using actual generated schema definitions; retain the schema8 test classes.
- The unsupported native-import version sentinel changes9 to10 only because9 becomes supported; rejection assertions stay intact. `native_recheck.py` only gains CLI choice9.
- In `ci-emulator.sh`, explicit current D expected schema becomes9. Run the new stage after crafting/readback and before UpdateFixture: `run_stage GenesisEncounterInstrumentationTest 1 900 native-genesis-encounter.txt`; stop product; copy DB to `validation/genesis-encounter`; compare against `validation/genesis-crafting/astravar-campaign-ledger.db`; write `validation/genesis-encounter-readback.json`. Prior connected/crafting snapshots must not be mutated or repurposed.

## Verification already observed, and limits

Before the last two proof regressions, one local Python batch passed **222 tests** across encounter, crafting, connected, upgrade, native campaign import and native recheck suites. The final encounter-only run passed **17 tests** after adding two regressions. Therefore the checkpoint covers **224 unique tests with observed passes across those runs**, not one observed final combined224 run. A duplicate ZIP-name warning was intentional adversarial test output. `bash -n scripts/ci-emulator.sh` and `git diff --check` passed. A final diff check after strengthening also passed.

The new external native Kotlin class had not been compiled or executed when the environment was lost. There is no encounter emulator acceptance, no physical-device acceptance and no owner-signed release claim. Parent-owned app/rules verification is separate and must not be inferred from these host checks.

Peer read-only reviews found native labels/routes matched the current UI and receipt/source shapes matched backend commands. A separate D player-action route host failure remained under investigation. Later C native E4 evidence showed Inventory > Open hero's item rejected with `That saved sheet is unavailable or archived. No other character was opened.` despite the saved hero/item being visible, then failed correctly at Edit (GenesisJourney line220). Candidate cause: a LaunchedEffect captures composition-time active rows but rereads live delegated loaded booleans, allowing loaded=true with stale empty rows. Freeze a consistent composition snapshot for effects and preserve exact absent/archived/detail assertions; this diagnosis is not a verified fix. Do not weaken native assertions to bypass it.

## Resume directive

1. First attempt to recover the original worktree and `991dd4a` without reset/clean/overwrite. Inspect status and commit objects before any reconstruction. If recovered, preserve/publish exact blobs and compare this handoff with actual source.
2. If unavailable, branch from the verified remote D base or its explicitly reviewed successor. Reconstruct only the ten listed files, label them reconstructed, retain current product/backend ownership and historical fixtures. Do not pretend the new commit equals991dd4a.
3. Resolve the independently identified product route failure with focused product regression coverage; preserve both the item-edit and encounter actor-return assertions.
4. Run the listed six Python suites, shell syntax and diff checks. Compile external native test Kotlin through the coordinator-owned Gradle slot. Then run the complete exact-source full and native gates, including connected/crafting/encounter independent readbacks and upgrade/import retention.
5. Preserve C release candidate branches, PR #15, app ID, owner signing identity, all source/canonical data and backups. Publishing a recovery document does not authorize owner-data migration, signing, installation, merges or further CI dispatch.
