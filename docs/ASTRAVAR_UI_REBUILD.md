# Astravar 2.0 UI rebuild

This presentation reconstruction starts from verified preview commit `7c1e42c0f2b3ea639aa675b9109f8d2ddbcee528` on `feat/astravar-2.0-integrated`. Work belongs to `feat/astravar-2.0-ui-rebuild`; PR #3 remains the unmerged engineering baseline.

Default debug metadata is **2.0.1-ui-rebuild**, version code **21**, application ID **com.yazsras.astravar.v2uidebug**, label **Astravar 2.0 UI Rebuild**. The old preview signing key is unavailable. A separate package lets this build coexist with the release, Inventory Beta and original 2.0 Preview without uninstalling or replacing their data. Release signing configuration is preserved.

## Presentation architecture

One bottom navigation bar exposes **Armor / Combat / Campaign / Config**. Armor is the identity and status destination; Combat provides the automatic roll workflow. Campaign opens an explicit hub for Inventory, Inventions, Connections and Eek. Their lists have focused detail/edit overlays and a normal return to the hub. Config holds core management, spell templates, rules/profile, recovery, history, backups, overlay preferences, fictional sync and diagnostics. This replaces the preview's stacked navigation bars and permanently exposed forms.

`PlayerDesign.kt` centralizes the approved navy/gold/blue palette, spacing, cards, entries, fields and overlay containers. `PlayerShell.kt` owns the common header, navigation, hubs and bounded tools. `ApprovedArt.kt` draws original medallion regions while labels, values, controls and interaction states remain native Compose. Inter supplies functional UI and numbers; Cinzel is limited branding. No additional UI dependency is required.

Record drafts use saveable presentation state and frozen revision baselines. Short selections close immediately; editors retain drafts and guard meaningful dismissal. Sheets provide visible close controls, contained scrolling, IME insets and persistent action areas. Unsupported mechanics stay disabled. The separate Android system floating overlay retains its existing service, permissions, lifecycle and shared engine.

## Correspondence to approved components

Latest explicit player corrections take precedence over older composites. The [18 October UI directive](https://app.notion.com/p/3fc4d9aa716c8138a32cec37bd4289a5) permits ordinary native layouts from the [approved identity](https://app.notion.com/p/3f64d9aa716c81a4b876fbf4cc578d5d); it does not authorize new gameplay rules.

| Approved component | Native implementation and boundary |
| --- | --- |
| [Armor arrangement](https://app.notion.com/p/3f64d9aa716c81bbba6dfa7af5cc67c6) | Compact approved armor visual/name/confirmed level, three equal AC/Attack/DC summaries, abilities, matching Active Element/Core Systems entries. Unknown values remain unknown. |
| [Ability cards/editor](https://app.notion.com/p/3f64d9aa716c81858ff6f0c1d3298a59) | Three columns by two rows; score in rectangle, total modifier in lower oval. Exact original emblem artwork; centered native editor. Independent magic modifier survives score changes; manually sourced character values do not silently rewrite the combat profile. |
| [Active Element and wheel](https://app.notion.com/p/3f64d9aa716c813489e6eea13e972854) | Shared persisted state; immediate selection/dismissal. Clockwise from top: Fire, Cold, Lightning, Acid, Poison, Thunder, Necrotic, Psychic, Radiant; Force center. Existing engine availability controls locks. Proposed extra Force damage is not activated. |
| [Selected Attack/chooser](https://app.notion.com/p/3f64d9aa716c8193b94af168a38c0af7) | Original medallions in compact card and six-reference selector. Supported Elemental/Spell-imbued Shot/Discharge choices use existing engine modes; three gauntlet choices remain disabled. Existing Arcane/Shatter modes remain accessible in a disclosure. |
| [Damage/Latest Result](https://app.notion.com/p/3f64d9aa716c81d6b98be7193c1a0c31) | Actual configured formula/components, saved raw dice, attack/save fields as applicable, typed totals, detail overlay and Copy Full Result. No invented target AC or ordinary hit/miss verdict. History stays secondary. |
| [Latest Roll control](https://app.notion.com/p/3f64d9aa716c815c84f2f483a7e7df8a) | One conditional primary action with original selected art. D20 modes reset only after completed execution; failures retain choice. Save-only Discharge has no attack roll or mode selector. Narrow/large-text segments show Adv./Disadv. with full accessible names. |
| Core Systems | Compact Armor entry, then static capability/reference overlays. Verified equipment-reference text supplies maxima/recovery/activation; no invented current charges, cooldowns or automatic resource tracking. |
| Expanded campaign destinations | Identity-consistent native search/lists and focused overlays. Inventory identity/research, project material review, connection milestones, companion records and audits remain repository-owned. |

## Original artwork provenance

The three added resources below are unchanged original owner-supplied PNGs. Hashes identify the committed resource source files. Private Library identifiers, signed URLs and discovery metadata are not published.

| Resource | Source and SHA-256 |
| --- | --- |
| `drawable-nodpi/approved_attack_art.png` | Canonical approved six-attack selector, 1448×1086. `bf4a1a089286d013560c7b2e11855ca1b940bb2f011a7350f99a79ce377073e4` |
| `drawable-nodpi/approved_ability_art.png` | Canonical approved ability-grid reference, 1448×1086. `1eea5564f63fe79d0676e02cb567ac242d8e9e05f176417766ed35f844c7a851` |
| `drawable-nodpi/eek_portrait.png` | Recovered original mechanical owl portrait. `1300fdbcb5ca992579b926de21587b4ebae11fc2032d654bf2b97e51198e68ab` |

The attack reference supplies six **204×198** source regions; the ability reference supplies six **168×158** regions. Runtime sampling preserves aspect ratio and excludes pictured labels, numbers and card frames. The app does not display a flattened screenshot as an interactive control. Original PNGs are not redrawn or re-encoded. Native circular clipping and native containers provide responsive presentation.

The existing armor-front image and ten particle originals are preserved. Particles use a shared native medallion rather than defective composite-icon extractions. Recovered elemental ZIP candidates had clipping/neighbor contamination and were not blindly adopted. Existing bundled Inter/Cinzel files and their OFL notices remain; see [asset licenses](ASSET_LICENSES.md). A licensed Libre Baskerville binary was not recovered, so attack typography uses the existing native font fallback.

Final standalone transparent attack/emblem/frame layers and exact final compact Roll Button PNG identity remain unavailable or unverified. Approved individual component references guide composition; unfinished full-screen mockups are not claimed as final production assets.

## Preserved foundation

`scripts/verify_ui_foundation.py` compares **79 baseline files and the owner signing block** against the preview's recorded SHA-256 values. Its manifest covers the shared rules, data/repositories, schemas/migrations, sandbox implementation, existing overlay foundation and protected assets. This is a preservation gate, not a substitute for behavioral tests.

The rebuild retains the Kotlin dice/combat engine; weapon Room v3 and campaign Room v2 data/migrations; stable IDs; confirmed material-consumption transactions; immutable audits/history; JSON validation/export/merge restore; and offline storage. No schema migration, campaign import, backend rewrite or signing-key replacement is part of this UI work.

The debug-only public sandbox remains `https://astravar-public-disposable.fantasy-layer.workers.dev`, with its separate fictional database, durable command IDs, outbox, receipt recovery, revision checks, cursor/history and idempotent retries. Real character, item, invention, connection and companion records cannot be uploaded by this client. The experimental Sites MCP route is separate and is not repaired or claimed proven here. No paid service is activated.

## Known limits

- Three gauntlet attack families are illustrated but have no executable engine implementation. Their disabled cards do not grant new mechanics.
- The existing weapon engine's configured range/blast formulas remain authoritative. Armor-reference examples and proposed Force Neutral bonuses do not replace saved rules.
- Spell selection uses the existing independently identified templates/payloads. Name grouping is a presentation convenience; a persistent parent-family/variant schema is not implemented by this rebuild.
- Core capability entries are read-only references, not a new persistent editable capability database.
- Character/Eek status is manually sourced. No current HP, resources, rests, recovery, results or campaign state is inferred.
- Currency accounting, authenticated real-campaign sync, Notion import and automatic D&D Beyond synchronization remain outside this change.
- Native host rendering does not establish physical Galaxy S26 installation, keyboard/gesture behavior, D&D Beyond overlay operation or full artwork parity.

## Samsung Galaxy S26 installation

1. Download the verified **UI Rebuild debug APK** from its successful Actions artifact or delivered APK file. Extract the artifact ZIP if necessary; a repository source ZIP is not an APK.
2. Install **Astravar 2.0 UI Rebuild**. If Android requests installation permission for the browser/My Files, grant it for this install and restore the prior setting afterward.
3. Keep the release (`com.yazsras.astravar`), Inventory Beta (`com.yazsras.astravar.debug`) and old Preview (`com.yazsras.astravar.v2debug`) installed. This new `.v2uidebug` package is deliberately separate; no uninstall or shared-data replacement is required.
4. New package storage starts independently. To transfer supported records, export and preserve originals from the source app first. Weapon JSON and campaign JSON are different formats. Use Config's corresponding backup tool, inspect the restore preview and confirm only the intended supported operation. Campaign restore is merge-only and rejects conflicting identities/history.
5. The fictional sandbox outbox is not included in weapon/campaign backups. Keep the old Preview installed to preserve its pending commands and receipts; do not assume pending work transfers to the new package.
6. Test with disposable data before live play: ability Save/Cancel, element persistence, a supported roll, item/project/connection editing, backup/restore, and fictional sync.
7. For D&D Beyond use, open Config → Overlay & appearance and grant **Appear on top** to this new application when requested. Start only the overlay being tested, verify drag/collapse/result/stop, and retain the working apps.
8. Use one consistent APK signing channel for later updates. If Android rejects a future update, preserve backups and the installed app; do not uninstall as a shortcut.

## Verification status

The exact commit’s Actions artifacts and delivery verification report record build/test/lint results, signature/alignment, source commit and PR. This design document describes the implementation and its limits; it does not replace those checks.

The acceptance suites render real Compose/Android windows through Robolectric API35 native graphics at 400×860dp and narrow 360×780dp, including larger text, populated/empty fixtures, major dialogs and simulated IME insets where testable. Dialog captures include actual Android window roots rather than omitting overlays. Host-rendered screenshots and simulated insets do not claim emulator, software-keyboard or physical-device acceptance. Final results belong in the delivery/verification report after checks finish.
