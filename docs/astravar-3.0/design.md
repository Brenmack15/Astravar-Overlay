# Astravar 3.0 native design specification

Status: implemented source; build verification and captures are tracked in `verification.md`.
The visual design is original. The supplied Astravar screenshots were inspected locally; no private screenshot or campaign export is added to this public repository.

## Product hierarchy

| Primary destination | Purpose | Frequent actions |
| --- | --- | --- |
| Character | Structured playable character, calculations and session controls | Roll, inspect, equip, manage resources |
| Combat | Native playable-character combat view | HP, conditions, calculated attacks and action availability |
| Inventory | Existing owned item ledger and equipment history | Search, inspect, create and edit |
| Campaign | People, workshop, checkpoints and reference library | Read, link and search |
| More | Companion sheets, preserved armor systems, source reconciliation and settings | Backup, connection status, appearance |

Character is the launcher destination. Character and Combat share the same playable character and calculations. Inventory no longer requires going through Campaign. More → Armor weapon preserves the accepted specialized weapon engine and its setup; Armor systems links to that engine. The specialized companion and historical character editors remain accessible from More. Character backup exports playable records and history; Campaign backup preserves the separate source ledger. Existing saved navigation keys are mapped without changing character data. Search is available in the app header and Campaign hub. Administrative and fictional diagnostics are outside the main gameplay hierarchy; the fictional connectivity lab is debug-only in the main hub.

## Visual system

| Token | Value / behavior |
| --- | --- |
| Canvas | `#080E1B`, deep navy |
| Panel | `#101A2D` |
| Elevated | `#142038` |
| Active | `#20304D` |
| Outline | `#2A3952`, subtle card separation |
| Gold | Existing Astravar gold family, primary actions and key values |
| Silver | `#E3E8F1` |
| Purple | `#B7A5DF`, available secondary accent |
| Secondary text | Existing muted blue-gray `#A4B3CC` |
| Identity face | Existing Cinzel, header only |
| Reading face | Existing Inter; body 14/21 sp, supporting 13/19 sp |
| Spacing | 4 dp base; 8–12 dp within groups; 16 dp screen gutter |
| Card radius | 18 dp |
| Touch target | At least 48 dp |
| Content width | Existing 900 dp page maximum, 600 dp editing sheet maximum |

`PlayerCard`, `PlayerEntry`, `PlayerSheet`, `PlayerDisclosure`, `ReforgeBadgeRow` and `ReforgeRichText` provide the common component vocabulary. Existing original artwork is preserved. Information gets stronger visual emphasis than ornament. Components inherit font scaling. Navigation measures actual scaled labels and uses multiple balanced rows only when the compact row cannot fit.

## Ability interaction

The preserved source editor opens a compact sheet: score tile, modifier, one numeric field, optional Advanced section, and pinned Save/Cancel. Unknown remains blank; zero remains a valid distinct score. Existing legacy modifier adjustments and attribution remain stored and appear only under Advanced. A nonzero recorded modifier is summarized visibly; it is never silently converted to an equipment effect.

No citation is required for an ordinary local edit. Existing source text is retained; new source-less edits receive a local-edit audit attribution. Optimistic revision checks and unsaved-discard guards remain in place. The structured Character sheet is responsible for equipment-derived calculations; the preserved source editor clearly identifies its limited role.

## NPC and document interaction

An NPC opens with portrait/type/aliases, relationship, readable status/era/evidence badges, Overview, Known Information, Open threads, milestones and related records. Existing labeled imports are projected into these sections without rewriting their source. Unknown prose remains visible. Proposed or disputed evidence is not presented as confirmed. Milestones appear chronologically, with recorded timestamps when available.

Sources, external IDs, original record URLs, revisions and raw imported content sit behind optional Sources & record details. Typed source records, source bytes, history and links remain intact. Opening or reading a profile makes no write.

The native renderer supports headings, paragraphs, bold/italic, inline code, unordered/numbered/check lists, quotes, separators, code blocks, tables and links. Known Notion mentions become readable links. Internal links accept the scoped `astravar://record/type/id` syntax and expose a callback. HTTP(S) links require a real host and cannot contain credentials; executable schemes are rejected. No WebView, HTML execution or JavaScript is introduced. Images are user-initiated safe links, not silently fetched remote assets. Embedded remote image rendering and a visual rich-text AST editor remain gaps.

The writing component provides bounded multiline input, Heading/Bold/List/Checklist controls, and a rendered preview. Existing save and transaction semantics remain explicit. It does not claim background autosaving of committed campaign records.

## Accessibility and verification

Dialogs respect safe drawing and IME insets; content scrolls while actions remain outside the scroll region. Buttons use minimum touch bounds. Disclosures expose expanded/collapsed state and explicit actions. Search exposes a labeled Clear search control. Tables scroll horizontally with fixed readable cells. Major record sections retain semantic headings where the component supports them. Error/save messages retain their live-region behavior.

The theme explicitly provides the on-surface reading color for custom navy containers, and shared sheets declare their content color. Native captures caught the previous black default text on those surfaces; a rendered text-layout contrast assertion now requires at least 4.5:1 for ordinary reading text. Native link annotations retain link interaction rather than using offset-only click handling. Documents parse large sources off the main thread, associate each result with its source, and expose bounded sections and text expansion while retaining the complete original content.

At 360 dp and doubled font scale, the full Character and Inventory labels require three navigation rows. Tests preserve their requested font size, single-line complete labels, 48 dp touch targets and selected-state behavior. Font size and labels are not reduced to fit the previous two-row arrangement.

New `ReforgeContentUiTest` exercises the actual native profile and compact editor with synthetic records. It captures `reforge-npc-profile` and `reforge-compact-ability-editor` through the established native-window helper when screenshots are enabled. `RecordPresentationTest` verifies safe conversion, source preservation, malformed markup, supported Markdown blocks and link schemes. Existing navigation/editor tests are updated to the changed product requirements rather than retaining obsolete mandatory-citation assertions.

Measured physical-device accessibility, TalkBack navigation, real Galaxy insets, performance traces and large-font end-to-end gameplay must be reported separately from Robolectric drawing. No such result is inferred from this specification.

## Before / after evidence

| Supplied screenshot problem | Implemented behavior |
| --- | --- |
| Ability source essay fills normal editor | One numeric field; advanced provenance collapsed |
| Manual magical modifier is indistinguishable from active equipment | Preserved legacy adjustment is explicitly labeled under Advanced |
| NPC metadata and story share one block | Story sections, compact state badges and optional technical details |
| Raw Notion mention tags occupy reading area | Human link label rendered natively |
| Long NPC note hides actionable unfinished matters | Open threads have a separate section |

Build screenshots use fictional fixtures and are distinct from the owner's supplied before-images. They are evidence of native rendering, not physical-device installation.

## Reviewed companion actions

Existing Party sheets expose Review playable attacks. Only Confirmed source attacks with an explicit independent attack bonus and a parseable single-type damage formula become candidates. The player selects candidates and confirms their displayed interpretation. Charged attacks, custom critical rules, multiattack sequences, unknown bonuses and proposed entries remain blocked for manual structured setup. The playable companion must already have undergone explicit Character adoption. Applying candidates rechecks both source fingerprint and local revision, retains stable attack IDs, preserves HP/resources and never modifies the source sheet. Exact command retries return their original outcome.

Companion form switching remains a concrete gap. A form requires an accepted policy for separate versus shared HP, persistence of sustained damage, resource ownership and equipment compatibility. The app does not model Eek's Warform as ordinary equipment or infer the active form from narrative text.
