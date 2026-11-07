# Current-state product and engineering audit

Baseline: `852bbd48af9b6c70be5ce44d5f898d1224ca0e7c`, latest available `feat/astravar-dynamic-prompts` / draft PR #8. `main` is an older repository landing state and is unsuitable as the implementation baseline. Existing PRs #4–#8 form a stack and remain unmerged. This branch carries that history forward.

Audit methods: inspected the actual Kotlin/Compose, Room, rules, test and navigation sources; inspected both supplied screenshots locally. D&D Beyond observations and video evidence are documented separately. This audit distinguishes implemented source changes from verified acceptance; see `verification.md` for executed checks.

## Priority register

Impact is gameplay/data impact; effort is relative implementation size. P0 blocks correct play or release, P1 is high value, P2 improves comprehension or speed. “Implemented” describes current source and still requires the verification report's evidence. “Integration” identifies work owned by another Reforge component and is not a claim of completion.

| # | Priority / impact | Concrete baseline problem and evidence | Effort / dependency | Result or remaining work |
| --- | --- | --- | --- | --- |
| 1 | P0 / release | `main` does not include the latest prompt/schema work; branches and PRs diverge from it | Small / Git history | Isolated Reforge branch based on PR #8; history retained |
| 2 | P1 / navigation | `AppScreen.kt` launches armor controls before the full character | Medium / structured engine | Character launcher and hook implemented |
| 3 | P1 / navigation | Inventory requires Campaign → Inventory despite frequent use (`PlayerShell.kt`) | Small | Direct Inventory destination implemented |
| 4 | P1 / clarity | Five primary labels emphasize Armor and Config rather than play (`PlayerShell.kt`) | Medium / routing | Character, Combat, Inventory, Campaign, More implemented |
| 5 | P0 / correctness | `CharacterAbility.totalModifier` is score modifier plus manually entered magic; no typed item effects (`CampaignRecords.kt`) | Large / rules + persistence | New structured engine integration; old fields retained for reconciliation |
| 6 | P1 / editing | Simple ability edit opens two numeric fields plus a mandatory citation essay (`CharacterOverviewV2.kt`, supplied Strength screenshot) | Small | Compact editor; advanced fields collapsed |
| 7 | P1 / usability | Local character status cannot save without a prose source (`CharacterProfileDialog`) | Small / audit API | Automatic local-edit provenance; explicit Save with revisions retained |
| 8 | P1 / correctness | Manual magic adjustment looks like an equipment-derived score (`CharacterAbilityDialog`) | Medium / engine | Labeled preserved legacy adjustment; not auto-converted |
| 9 | P1 / reading | NPC notes render literal Notion mention tags (`ConnectionsPageV2.kt`, supplied NPC screenshot) | Medium / parser | Safe mention conversion and native rich renderer implemented |
| 10 | P1 / reading | NPC story, external IDs, import source and source URLs have equal emphasis (`ConnectionsPageV2.kt`) | Medium | Structured sections and optional technical details implemented |
| 11 | P1 / continuity | Labeled imported promises/open threads are buried inside one note string | Medium / projection | Read-only Open threads projection implemented |
| 12 | P1 / trust | Every fact, milestone and link repeats source blocks in the main profile | Small | Sources collected behind optional details; history preserved |
| 13 | P2 / comprehension | Milestones rely on flow order without an explicit readable timeline date (`ConnectionsPageV2.kt`) | Small | Chronological dates and separators implemented |
| 14 | P1 / writing | NPC and project text fields provide neither formatting shortcuts nor preview | Medium / renderer | Native writing toolbar and preview implemented; committed autosave remains open |
| 15 | P1 / reading | Project blueprint renders Markdown as plain text (`CampaignPagesV2.kt`) | Small / renderer | Rich blueprint presentation implemented |
| 16 | P1 / friction | New inventions/stages/material entries require manual source prose (`CampaignPagesV2.kt`) | Small / audit | Optional source disclosure and automatic local attribution implemented |
| 17 | P1 / discoverability | No global search joins inventory, people, profiles, projects and archive records | Medium / search index | Header/hub route implemented; search integration tracked separately |
| 18 | P1 / reading | Reference body and inventory detail do not consistently convert imported text (`CampaignReferenceLibraryV21.kt`, `CampaignLedgerPage.kt`) | Medium / renderer | Integration applies shared renderer; verify included files |
| 19 | P1 / unnecessary service | Overlay-era permission and launcher controls remain in main product flow (`MainActivity.kt`, `Management.kt`, manifest) | Medium / retirement | Root retirement work; native launch acceptance required |
| 20 | P1 / clutter | Fictional public connectivity lab is exposed beside real backup/owner tools in release (`RebuildConfigHub`) | Small | Main fictional-lab entry is debug-only |
| 21 | P2 / navigation | More lacks direct companion and preserved armor/source entries | Small | Companions, Armor systems and Character sources entries implemented |
| 22 | P2 / interaction | Search fields lack a quick clear affordance (`PlayerSearch`) | Small | Labeled Clear search control implemented |
| 23 | P2 / accessibility | Disclosures expose text and click but do not announce expanded state (`PlayerDisclosure`) | Small | State semantics and expand/collapse labels implemented |
| 24 | P2 / visual hierarchy | Dark panels have minimal separation and uniform undifferentiated navy (`PlayerDesign.kt`) | Small | Distinct panel/elevation tokens and subtle outlines implemented |
| 25 | P0 / data | Weapon state, source character numbers and structured party sheets are separate authorities (`Store.kt`, `CampaignRecords.kt`, `PartyRecords.kt`) | Large / data adapter | Explicit reviewed adoption needed; no silent canonical cutover |
| 26 | P0 / sync truth | Existing campaign bridge cannot prove new local structured fields synchronize without protocol support (`CampaignConnection.kt`) | Large / scopes + protocol | New store must say local-only until independently verified adapter exists |
| 27 | P1 / scaling | Directory/project screens collect complete lists and use eager `forEach` (`ConnectionsPageV2.kt`, `CampaignPagesV2.kt`) | Medium / query pagination | Directory has 40-entry display bound; true database paging and project paging remain open |
| 28 | P1 / backup | Prompt catalog is device-local and outside campaign/weapon backups (PR #8, `ChatGptPrompts.kt`) | Medium / versioned backup | Remains a known limitation; do not imply all settings restore |
| 29 | P1 / writing | Large local drafts mix `rememberSaveable` with file-backed snapshots (`UiDraftState.kt`) | Medium / state restoration | Existing recovery guards retained; committed autosave/undo needs durable revision design |
| 30 | P0 / release | Original owner signing key is required, not recoverable from a certificate (`app/build.gradle.kts`) | External / secure key | Never substitute a debug signer; release feasibility in `release.md` |
| 31 | P1 / versioning | Device client reports newer Android than current compile/target SDK36 (`app/build.gradle.kts`) | Medium / official SDK availability | Verify actual device/toolchain separately; do not infer installed OS from app metadata |
| 32 | P1 / rich media | No safe embedded network image loader is configured | Medium / permissions + cache | Renderer uses explicit image links; native remote image embed remains open |
| 33 | P1 / editing | Historical companion, currency and checkpoint source forms still require a resolved-event attribution (`PartyTypedSheetV2.kt`, `PartyWalletV2.kt`, `CampaignCheckpointsV21.kt`) | Medium / shared audit contract | Preserved for existing transaction safeguards; optional automatic provenance is still needed in these source editors |
| 34 | P1 / accessibility | Actual native captures exposed black default text on custom navy containers: NPC title, ability dialog heading and skill labels (`Common.kt`, `PlayerDesign.kt`) | Small / shared theme | Root reading color and explicit sheet content colors implemented; rendered text-layout contrast regression added, QA recapture pending |

## Preserved strengths

The existing source has substantial transaction, history and sync safeguards. Room migrations are explicit and never use destructive fallback. The weapon engine has established rule tests, genuine dice, immutable historical decoding and accounting. Record edits use expected revisions and saved receipts. Crafting separates proposed blueprints from explicit material consumption. Source imports have guarded previews and recovery, and the anonymous fictional sandbox is isolated from real campaign data. These are preserved rather than replaced for cosmetic uniformity.

## Integration boundaries

Pure mechanics belong in the rules module, persistence in repositories, and UI projections in Compose. `RecordPresentation` is a read-only projection, so normalizing a mention does not destroy original evidence or silently activate mechanics. Existing records are not overwritten with current prompt examples or old level-2 companion reference statistics. Any new structured local state must be adopted explicitly after review and report its separate synchronization status.

No production database cutover, real campaign mutation, owner installation, protected-branch merge or public release is established by the audit.
