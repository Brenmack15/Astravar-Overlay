# Astravar 2.0 asset provenance

Reviewed 18 October 2026 UTC against the development/design hierarchy and the recovered Inventory Beta source baseline `c5733f55cb4bc5eeb549758d96beed74730253df`.

This is a sanitized public source inventory. It lists development specifications and artwork provenance only. No live campaign pages, campaign exports, private attachment URLs, credentials, claim links, raw search results, or signing material are reproduced. Source page titles and last-edited times are the values returned during review; a recent timestamp alone does not establish approval or supersession.

## Incorporated originals

The following 11 PNGs are stored under `app/src/main/res/drawable-nodpi/`. Their source bytes were copied unchanged; the committed-resource SHA-256 values were independently compared with the recovered originals. No replacement artwork, image generation, redraw, UI screenshot crop, or background removal was used for these files.

| Use | Original Library filename | Android resource filename | Approved design page | SHA-256 of original and resource |
| --- | --- | --- | --- | --- |
| Armor front | `image-gen-1(20261010-192020).png` | `astravar_armor_front.png` | [Design source](https://app.notion.com/p/3f64d9aa716c81d2861cf35a7f309b61?pvs=204) | `25d225393ba934260690d184a5993805add290bdfdb76e05bcb7ff8486b355b3` |
| Acid | `249.png` | `particle_acid.png` | [Design source](https://app.notion.com/p/3f64d9aa716c815d8f18cd73cb25368e?pvs=204) | `a31bdc674fc52626d5167d2674abaa944534be7eeedd46f7a1b63b92a8151363` |
| Cold | `254.png` | `particle_cold.png` | [Design source](https://app.notion.com/p/3f64d9aa716c812a8f49c0466b2b5c3b?pvs=204) | `e5d6a8fbc2ea576f40e6c8e6b88d6b3d429c17b90c2b18581be4bee6f4dceceb` |
| Fire | `246.png` | `particle_fire.png` | [Design source](https://app.notion.com/p/3f64d9aa716c817f860cdca43c388ad2?pvs=204) | `c4444ef869009767e1b38d6913c0be0309c31367c98865ec6256793cfe502cd2` |
| Lightning | `251.png` | `particle_lightning.png` | [Design source](https://app.notion.com/p/3f64d9aa716c81e994fce1e4b9042326?pvs=204) | `45bc6141226624a29b2d9096f54909e03bcbaa05262e944c8d47fc64651cd80e` |
| Poison | `248.png` | `particle_poison.png` | [Design source](https://app.notion.com/p/3f64d9aa716c8130ac61c72ccf9cca9c?pvs=204) | `81941c3dd25b5b6835db7f486cd70c09f0010cf43e3978004c2df33387bbe873` |
| Thunder | `250.png` | `particle_thunder.png` | [Design source](https://app.notion.com/p/3f64d9aa716c811ab4a8c23a61b7d2a1?pvs=204) | `a92f130a454c0b6669a0aab07cf2a3adc6a91cbf4a2acd8af95cedd9f5ef3159` |
| Necrotic | `245.png` | `particle_necrotic.png` | [Design source](https://app.notion.com/p/3f64d9aa716c810b8cb7f25c71dd3933?pvs=204) | `39e6d047f51ee95d41e6b11c0b00ab28c2447f1dc58e2df3cdc08fc19ec28384` |
| Psychic | `252.png` | `particle_psychic.png` | [Design source](https://app.notion.com/p/3f64d9aa716c811badc5c4658be47045?pvs=204) | `78932b5e61c913fb5e1a68c98b7a5f27cdbba708d36c095a3f807d839ae9f9b1` |
| Radiant | `247.png` | `particle_radiant.png` | [Design source](https://app.notion.com/p/3f64d9aa716c812bbcc4d24a0eaabe4e?pvs=204) | `471c55a567a9f6f5357aa5bc5199aa8b2e9c06efbcde84d181f093a33fe387e0` |
| Force | `253.png` | `particle_force.png` | [Design source](https://app.notion.com/p/3f64d9aa716c8105bd72c63e80863ebc?pvs=204) | `4e77e9843004519ae07e925ca57ec5d31dc90c3061cf620e0db4d9c8999b6a41` |

The front armor illustration is 1024 × 1536 RGB and contains the suit on a dark studio floor, with no UI labels. Its exact original filename was recovered from the prior owner-approved five-view armor conversation. The linked root page establishes the approved armor art direction; a dedicated Notion image block for this exact front-view binary was not independently located. This distinction is intentional: the source is the existing original, not a claim of a verified Notion download.

Each particle source is 1536 × 1536 RGB with an opaque black background. The mapping matches the filename attached on its individual Notion damage-type page and was visually inspected. No Notion-provided hash exists for these particle files, so their identity with Notion-hosted bytes is not claimed as cryptographically proven. The hashes above do prove that the Android copies equal the recovered Library originals. Keep the art inside shared native medallions and keep damage-type names and accessibility labels native. These files are not transparent sprites or particle animations.

The presence of Necrotic, Psychic, Radiant and Force artwork does not unlock an ability, change a damage formula, or approve homebrew mechanics.

## Additional approved reference evidence

Three recovered flattened reference masters exactly matched the SHA-256 values recorded in their Notion specifications. They were used as review references, not copied into Android as entire interactive screens:

| Reference master | Approved source | Verified SHA-256 |
| --- | --- | --- |
| `astravar_canonical_attack_selection.png` | [Visual Identity & Design Tokens](https://app.notion.com/p/3f64d9aa716c81a4b876fbf4cc578d5d?pvs=204) | `bf4a1a089286d013560c7b2e11855ca1b940bb2f011a7350f99a79ce377073e4` |
| `astravar_ability_scores_main_approved.png` | [Character Stat Cards & Edit Overlay](https://app.notion.com/p/3f64d9aa716c81858ff6f0c1d3298a59?pvs=204) | `1eea5564f63fe79d0676e02cb567ac242d8e9e05f176417766ed35f844c7a851` |
| `astravar_ability_score_edit_overlay_approved.png` | [Character Stat Cards & Edit Overlay](https://app.notion.com/p/3f64d9aa716c81858ff6f0c1d3298a59?pvs=204) | `d0101af0684e240ef9ec250f8a19f869ddd3064d02e5bff232ab789c8a30fb93` |

The six original Selected Attack card reference filenames recorded by Visual Identity were also recovered, as were the Active Element reference `438.png`, element-wheel reference `383.png`, and stat references `374.png`/`376.png`. These are flattened references, not standalone icon/frame layers. The older `astravar_armor_dashboard.png` is a historical layout candidate expressly superseded by the final component arrangement; it is not the approved full-screen visual master.

## Approved appearance contract

Source: [01 — Visual Identity & Design Tokens](https://app.notion.com/p/3f64d9aa716c81a4b876fbf4cc578d5d?pvs=204). The explicit approvals in the page supersede earlier provisional descriptions. The latest referenced token attachment is v1.5; its original JSON binary was not recovered, so the following values are transcribed from approved prose rather than presented as a downloaded token file.

| Token family | Approved values |
| --- | --- |
| Backgrounds | Deep `#000510`; app `#000C21`; panel `#000F28`; elevated `#011534`; active `#011D49` |
| Quiet border | `#294769` |
| Gold | Shadow `#6E481E`; burnished `#BF7B1A`; primary `#F29E11`; bright `#FAC71E`; glint `#F9E14E`; pale `#F7EA8C` |
| Arcane blue | Deep `#012F74`; vivid `#034FBF`; accent `#0771E7`; cyan `#05D6F6`; ice `#A1E8FB` |
| Text | Primary `#F7F9FA`; secondary `#DAEBF4`; muted `#8E9FB7`; role `#05D6F6` |
| Status | Ready `#35D5A3`; warning `#F7B955`; offline `#FF6578`; locked `#8293AC` |

Typography: Cinzel for brand 24 sp and section headings 16 sp; Libre Baskerville for attack names 24 sp; Inter for functional labels, body 14 sp, classification 16 sp, buttons 15 sp, numeric values 24 sp, and secondary labels 12–13 sp. These are approved target values; the existing packaged fonts and actual implementation must be reported separately. The original typefaces cannot be identified from flattened art, and the specification does not supply font binaries.

Geometry: 4 dp base spacing, 16 dp section padding, 12 dp section gaps, 8 dp heading gaps, 20–24 dp modal padding, and minimum 48 × 48 dp touch targets. Gold outer border 1.5 dp, quiet inner border 1 dp, selected blue border 2 dp, highlighted gold action border 2 dp, and 16–20 dp angular ornamental corners. Selected glow is blue 8 dp / 30%; highlighted action glow is gold 6 dp / 25%. Gold uses layered metallic treatment rather than a flat yellow outline. Attack medallions are 64 dp, damage icons 32 dp, navigation icons 24 dp, and resource icons 20 dp, independently of their larger touch targets.

The approved native Armor arrangement is compact overview, six ability cards in a 3 × 2 grid, then matching Active Element/Core Systems squares. Ability score belongs in the main rectangle and total modifier in the lower oval. New campaign sections reuse the established native visual system. Dynamic values, controls, semantics and interactions must never be baked into flattened artwork. See [design implementation limits](ASTRAVAR_2_DESIGN_LIMITS.md) for remaining work and gameplay boundaries.

## Unavailable or unverified production assets

- Six independent transparent attack icons remain unattached on their dedicated Notion pages. Full attack-card references do not replace them.
- Independent scalable ornamental frames, complete component-state artwork, font binaries, and the final compact Roll Button PNG remain unavailable or unverified.
- Final approved full-screen Armor, Combat, Systems and Config composites are not complete production assets. Explicit component layouts and behavior can guide native composition without pretending those images exist.
- The token attachment binaries and final Notion Markdown/media ZIP integrity remain unverified.
- Direct connector retrieval returned private file-block references rather than signed downloadable binary URLs; the browser required sign-in. Existing exact Library originals supplied the recoverable art. No credentials or attachment URLs are published here.
- Rear armor illustrations predate or do not satisfy the later correction about hand crystals visible from behind. Only the front illustration is incorporated.

## Gameplay preservation and specification conflicts

Visual approval is not mechanics approval. Preserve the existing pure Kotlin engine, historical roll snapshots, saved profile values, weapon Room schema and migrations unless a later explicit gameplay instruction authorizes a change.

- Force Neutral's proposed additional `3d8 Force` remains pending campaign approval; do not apply it as a default upgrade because a selector design displays it.
- A Notion Discharge reference describes 20 ft / 12d12 / DC 15, while the existing engine derives blast dice from its saved profile and uses a 15 ft Discharge radius. Keep current engine behavior and surface the conflict instead of silently replacing rules.
- The six armor attack designs include gauntlet profiles absent from the recovered engine's ARCANE/ELEMENTAL/SPELL/DISCHARGE/SHATTER model. Art or labels do not establish new attack semantics; retain existing Shatter behavior and do not invent mappings.
- Mockup ability scores, AC, attack bonuses, save DCs and resources are illustrative. They must not overwrite existing saved data or initialize unverified campaign state.
- Later UI approval supersedes older descriptions of Force as base-only and reversed score/modifier placement. It does not approve new damage, automatic time passage, resource restoration, or target HIT/MISS adjudication.
- Real Notion campaign import and source-of-truth cutover remain outside this asset recovery. Development specifications are not campaign-data import authorization.

## Reviewed development pages

Exactly 51 unique development/design pages were reviewed recursively. This list contains no live campaign ledger, character record, inventory item, private claim URL or unrelated Library result. Page access may require the owner's existing Notion authorization; links do not grant access.

| # | Exact reviewed page title | URL | Last edited at review (UTC) |
| --- | --- | --- | --- |
| 1 | ⚙️ Astravar Overlay 2.0 — Asset Pack & Production Checklist | [Open](https://app.notion.com/p/3f64d9aa716c81d2861cf35a7f309b61?pvs=204) | `2026-10-17T19:55:17.001Z` |
| 2 | 🎨 01 — Visual Identity & Design Tokens | [Open](https://app.notion.com/p/3f64d9aa716c81a4b876fbf4cc578d5d?pvs=204) | `2026-10-15T00:15:17.033Z` |
| 3 | 📱 02 — Screen References | [Open](https://app.notion.com/p/3f64d9aa716c81d39175cf077b55ee3a?pvs=204) | `2026-10-17T17:05:37.974Z` |
| 4 | 🖼️ Armor — Full Screen Reference | [Open](https://app.notion.com/p/3f64d9aa716c81bbba6dfa7af5cc67c6?pvs=204) | `2026-10-15T01:41:19.002Z` |
| 5 | 🖼️ Combat — Full Screen Reference | [Open](https://app.notion.com/p/3f64d9aa716c815c975ee67fc3bdb06a?pvs=204) | `2026-10-11T22:26:52.114Z` |
| 6 | 🖼️ Systems — Full Screen Reference | [Open](https://app.notion.com/p/3f64d9aa716c81608b6ac8b8b4cc1e95?pvs=204) | `2026-10-11T22:26:52.114Z` |
| 7 | 🖼️ Config — Full Screen Reference | [Open](https://app.notion.com/p/3f64d9aa716c81cba7a3d015d78f99e9?pvs=204) | `2026-10-11T22:26:52.114Z` |
| 8 | ⚔️ 03 — Attack Icons & Selection | [Open](https://app.notion.com/p/3f64d9aa716c8193b94af168a38c0af7?pvs=204) | `2026-10-15T11:14:03.063Z` |
| 9 | ⚔️ Elemental Shot — Icon Asset | [Open](https://app.notion.com/p/3f64d9aa716c81eb8c46c3ae4435056c?pvs=204) | `2026-10-11T22:26:39.697Z` |
| 10 | ⚔️ Gauntlet Strike — Icon Asset | [Open](https://app.notion.com/p/3f64d9aa716c81c7aae8cbb1d411d7f4?pvs=204) | `2026-10-11T22:26:39.697Z` |
| 11 | ⚔️ Charged Gauntlet Strike — Icon Asset | [Open](https://app.notion.com/p/3f64d9aa716c81f1b858fc9308637aa5?pvs=204) | `2026-10-11T22:26:39.697Z` |
| 12 | ⚔️ Spell-Imbued Shot — Icon Asset | [Open](https://app.notion.com/p/3f64d9aa716c818e96fbd1beccc1cbeb?pvs=204) | `2026-10-11T22:26:39.697Z` |
| 13 | ⚔️ Spell-Imbued Gauntlet Strike — Icon Asset | [Open](https://app.notion.com/p/3f64d9aa716c81449de9e8ee734df3e1?pvs=204) | `2026-10-11T22:26:39.697Z` |
| 14 | ⚔️ Discharge — Icon Asset | [Open](https://app.notion.com/p/3f64d9aa716c8169bb72c9559aca3260?pvs=204) | `2026-10-11T22:26:39.697Z` |
| 15 | ❄️ 04 — Damage Types & Elemental Controls | [Open](https://app.notion.com/p/3f64d9aa716c813489e6eea13e972854?pvs=204) | `2026-10-17T15:52:39.971Z` |
| 16 | 🖼️ Acid — Damage Icon | [Open](https://app.notion.com/p/3f64d9aa716c815d8f18cd73cb25368e?pvs=204) | `2026-10-17T15:51:58.331Z` |
| 17 | 🖼️ Cold — Damage Icon | [Open](https://app.notion.com/p/3f64d9aa716c812a8f49c0466b2b5c3b?pvs=204) | `2026-10-17T15:52:00.636Z` |
| 18 | 🖼️ Fire — Damage Icon | [Open](https://app.notion.com/p/3f64d9aa716c817f860cdca43c388ad2?pvs=204) | `2026-10-17T15:52:02.749Z` |
| 19 | 🖼️ Lightning — Damage Icon | [Open](https://app.notion.com/p/3f64d9aa716c81e994fce1e4b9042326?pvs=204) | `2026-10-17T15:52:04.683Z` |
| 20 | 🖼️ Poison — Damage Icon | [Open](https://app.notion.com/p/3f64d9aa716c8130ac61c72ccf9cca9c?pvs=204) | `2026-10-17T15:52:06.731Z` |
| 21 | 🖼️ Thunder — Damage Icon | [Open](https://app.notion.com/p/3f64d9aa716c811ab4a8c23a61b7d2a1?pvs=204) | `2026-10-17T15:52:20.768Z` |
| 22 | 🖼️ Necrotic — Damage Icon | [Open](https://app.notion.com/p/3f64d9aa716c810b8cb7f25c71dd3933?pvs=204) | `2026-10-17T15:52:23.180Z` |
| 23 | 🖼️ Psychic — Damage Icon | [Open](https://app.notion.com/p/3f64d9aa716c811badc5c4658be47045?pvs=204) | `2026-10-17T15:52:25.279Z` |
| 24 | 🖼️ Radiant — Damage Icon | [Open](https://app.notion.com/p/3f64d9aa716c812bbcc4d24a0eaabe4e?pvs=204) | `2026-10-17T15:52:27.070Z` |
| 25 | 🖼️ Force — Damage Icon | [Open](https://app.notion.com/p/3f64d9aa716c8105bd72c63e80863ebc?pvs=204) | `2026-10-17T15:52:28.931Z` |
| 26 | 🛡️ 05 — Systems, Resource & History Assets | [Open](https://app.notion.com/p/3f64d9aa716c81d6b98be7193c1a0c31?pvs=204) | `2026-10-15T20:38:04.993Z` |
| 27 | 🖼️ Null Ward & Defensive Field | [Open](https://app.notion.com/p/3f64d9aa716c8129b6f4e99607ae602b?pvs=204) | `2026-10-11T22:27:00.207Z` |
| 28 | 🖼️ Hunter Lock & Spell Reservoir | [Open](https://app.notion.com/p/3f64d9aa716c81b2aa84fff5ca95022b?pvs=204) | `2026-10-11T22:27:00.207Z` |
| 29 | 🖼️ Discharge & Overload | [Open](https://app.notion.com/p/3f64d9aa716c8184b240eb6931773a27?pvs=204) | `2026-10-11T22:27:00.207Z` |
| 30 | 🖼️ Roll History & Resource Counters | [Open](https://app.notion.com/p/3f64d9aa716c81d1ae75ff11da05f112?pvs=204) | `2026-10-11T22:27:00.207Z` |
| 31 | 🧩 06 — Reusable UI Components & Frames | [Open](https://app.notion.com/p/3f64d9aa716c81fda112df84a6b4e0a5?pvs=204) | `2026-10-11T22:26:20.633Z` |
| 32 | 🖼️ Selected Attack Card — Empty Frame | [Open](https://app.notion.com/p/3f64d9aa716c810c9794d97ae78593c7?pvs=204) | `2026-10-11T22:26:57.711Z` |
| 33 | 🖼️ Attack Selection — Modal Frame | [Open](https://app.notion.com/p/3f64d9aa716c81a7aacff9b5ced14497?pvs=204) | `2026-10-11T22:26:57.711Z` |
| 34 | 🖼️ Stat Card and Edit Modal — Frames | [Open](https://app.notion.com/p/3f64d9aa716c81f4b0a2d2cb6c4f2f4f?pvs=204) | `2026-10-11T22:26:57.711Z` |
| 35 | 🖼️ Primary & Secondary Buttons — States | [Open](https://app.notion.com/p/3f64d9aa716c815c84f2f483a7e7df8a?pvs=204) | `2026-10-17T15:51:03.443Z` |
| 36 | 🖼️ Element Selector & Roll Mode — Controls | [Open](https://app.notion.com/p/3f64d9aa716c81f8a778da293a05403a?pvs=204) | `2026-10-11T22:26:57.711Z` |
| 37 | 🖼️ Navigation Header/Footer — Frames | [Open](https://app.notion.com/p/3f64d9aa716c8173b18de89d36d8e2f0?pvs=204) | `2026-10-11T22:26:57.711Z` |
| 38 | 🧙 07 — Character Profile & Config Assets | [Open](https://app.notion.com/p/3f64d9aa716c81dba2c8e3ab1f6a53d3?pvs=204) | `2026-10-17T17:05:39.776Z` |
| 39 | 🖼️ Character Stat Cards & Edit Overlay | [Open](https://app.notion.com/p/3f64d9aa716c81858ff6f0c1d3298a59?pvs=204) | `2026-10-14T22:09:05.279Z` |
| 40 | 🖼️ Damage Dice Progression Editor | [Open](https://app.notion.com/p/3f64d9aa716c8194995ee97e6db65e01?pvs=204) | `2026-10-11T22:27:02.523Z` |
| 41 | 🖼️ Attack Blueprint & Modifier Editor | [Open](https://app.notion.com/p/3f64d9aa716c8124a52ac98c4c1a88eb?pvs=204) | `2026-10-11T22:27:02.523Z` |
| 42 | 🖼️ Saved Builds & Resource Configuration | [Open](https://app.notion.com/p/3f64d9aa716c812c869ee2e364c24abd?pvs=204) | `2026-10-11T22:27:02.523Z` |
| 43 | ✅ 08 — Visual States, QA & Accessibility | [Open](https://app.notion.com/p/3f64d9aa716c81c6943bc807b00e52f1?pvs=204) | `2026-10-11T22:26:20.633Z` |
| 44 | 📦 09 — Codex Handoff, Manifest & Export | [Open](https://app.notion.com/p/3f64d9aa716c817c8a11f27fd0ec9f94?pvs=204) | `2026-10-11T22:26:20.633Z` |
| 45 | 🧠 10 — Build-Ready Architecture & AI Connection | [Open](https://app.notion.com/p/3fc4d9aa716c8138a32cec37bd4289a5?pvs=204) | `2026-10-17T23:21:14.974Z` |
| 46 | 🧪 11 — Dummy Sync Lab: Local Prototype & Test Evidence | [Open](https://app.notion.com/p/3fc4d9aa716c81108bd5def076c3e16b?pvs=204) | `2026-10-17T19:54:30.364Z` |
| 47 | 🎒 12 — Campaign Inventory Capture MVP (Play-First) | [Open](https://app.notion.com/p/3fc4d9aa716c81e6a1cdcc72ce1c8cc8?pvs=204) | `2026-10-17T22:22:48.860Z` |
| 48 | 🗃️ 13 — Campaign Data Model v2 (Draft for Review) | [Open](https://app.notion.com/p/3fc4d9aa716c813cb832f5a1c2e611dc?pvs=204) | `2026-10-17T23:22:05.850Z` |
| 49 | 🧪 14 — Codex Technical Feasibility Review & Safe Implementation Gates | [Open](https://app.notion.com/p/3fc4d9aa716c8148aea7cb22d5c4941b?pvs=204) | `2026-10-17T23:21:13.130Z` |
| 50 | 🤝 15 — Connections Tab: Campaign Relationships & Memory | [Open](https://app.notion.com/p/3fc4d9aa716c81b68c87fc7b19d58744?pvs=204) | `2026-10-17T23:19:40.998Z` |
| 51 | 🔌 16 — ChatGPT ↔ Astravar Connectivity-First Feasibility Gate | [Open](https://app.notion.com/p/3fc4d9aa716c819ba0cfe68f4e2fc71b?pvs=204) | `2026-10-18T02:58:10.160Z` |

## Provided campaign-reference review

The seven provided Markdown reference attachments below were fully read during the implementation review on 18 October 2026 UTC. They supplement the 51 development/design pages listed above; they are not additional Notion pages or imported campaign records. Only filenames and integrity hashes are recorded here. Their private contents, historical examples and live campaign values are not reproduced.

All seven files identify themselves as **Version 1.0, prepared 5 September 2026**. Their presence in the current upload does not establish that they contain the latest accepted amendment or current resource checkpoint. No newer complete version history or authoritative live-state reconciliation was verified. Later explicit accepted rulings take precedence; historical examples must not initialize current resources. This review added no campaign import and made no numerical changes to the preserved weapon engine.

| Exact provided filename | SHA-256 of reviewed bytes |
| --- | --- |
| `01_DM_CORE(2).md` | `a1a93a4177be2f03e9224d8c6f0029df87c6331b2127eee371c30424aefe8ace` |
| `02_RULES_AND_DICE(1).md` | `b6542c5131dd0aab918badc41ea8065e3bfc4529a1350ce08bc6c6c2a0408c79` |
| `03_SOLO_COMBAT(1).md` | `90461d35219720a5f260207303d274738c6b75efb21f008b9180559ee09f90a4` |
| `04_WORLD_AND_EXPLORATION(1).md` | `8574b6adead4fc1405298cb26dddab41c1efc02cd276f27aa9768af4ba8c8073` |
| `05_CRAFTING_AND_RESOURCES(1).md` | `cf8b797717132c0399f85da6b57ab02ee196e3f3cdb51ce726be93a27c74eb1c` |
| `06_HOMEBREW_AND_EEK(1).md` | `7849151030aea654d1387d01d2bc5505bfa95f90463a05813a354bff18183237` |
| `07_CONTINUITY_AND_CHECKPOINTS(1).md` | `47edbc0a4a8f9d182dedc44aa8fa2bb236787c534a42b310f1546e3fa83f7527` |
