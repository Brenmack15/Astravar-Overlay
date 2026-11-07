# D&D Beyond hands-on UX research

Research date: 2026-11-07. Research mode: live visible-interface interaction plus privately supplied Android video. No private API, credentials, paid compendium extraction, or account mutation was used.

## Evidence and scope

The researcher personally opened D&D Beyond in the interactive cloud browser. The visible session was signed out. The character landing page redirected to <https://www.dndbeyond.com/en/players> and offered **Preview an example sheet**. Following that visible link opened the official public example, **Presto**, at <https://www.dndbeyond.com/characters/133058471>.

This is actual interactive character-sheet research, not a review of marketing screenshots. It is a public example rather than the owner's signed-in sheet. Account-owned editing, actual resource consumption, equipment changes, level advancement, preparation mutations, and saved roll persistence were not verified. No owner character was created or changed.

Two screenshots capture the public example. They contain no owner account or campaign information and are research evidence only; no proprietary artwork or styling is adopted as application assets.

- [Live sheet layout](evidence/dndbeyond-live-sheet.jpg)
- [Item details drawer](evidence/dndbeyond-item-detail.jpg)

A third spell-preview capture was attempted but was not present in the synchronized local evidence directory; it is not claimed as a delivered artifact. The spell interaction itself was observed in the browser's accessibility output and visible screenshot.

The supplied 78.403-second Android video was inspected through sampled frames at five-second intervals. The private original and contact sheet remain outside the public repository. Video observations describe the user's demonstrated workflows; they do not establish current authoritative character or companion values. No numerical campaign state from the video was seeded into the app.

## Actual interaction sequence

| Step | Research action | Visible result | Boundary |
|---|---|---|---|
| 1 | Open Characters | Signed-out player landing page with official example-sheet link | Owner list unavailable |
| 2 | Reject nonessential cookies | Banner closed | No account changes |
| 3 | Follow Preview an example sheet | Presto character sheet opened in a new tab | Public demonstration |
| 4 | Select Intelligence score | No information drawer appeared in this public session | Editing/score inspector unverified |
| 5 | Select Spells tab | Spell modifier, attack, save DC, search, level filters and spell rows appeared | Actual tab interaction |
| 6 | Select CAST beside a first-level spell | Right drawer showed available slot count, level, damage, components, timing, range, duration and higher-slot explanation | Preview observed; cast not committed |
| 7 | Hide sidebar | Full sheet regained content width | Drawer preserves sheet context |
| 8 | Select Inventory | Weight, coin, search, location filters, equipment, containers and attunement areas appeared | Read-only inventory |
| 9 | Select Dagger row | Drawer showed location, proficiency, attack type, reach/range, calculated damage/type, weight, cost and properties | Item detail observed |
| 10 | Search inventory for potion | Matching item remained; nonmatching container groups showed zero matches | Filtering observed |
| 11 | Select Extras | Dedicated empty companion area and search appeared | This sample had no companions |
| 12 | Select Features & Traits | Class/species/feat groups and feature-use counters appeared | Grouping observed |
| 13 | Return to Actions; select Reaction | Content reduced to reaction-related actions and spells | Filter works without navigating away |
| 14 | Select All | Complete action list returned | Filter reversible |

## Supplied video observations

| Approximate video time | Demonstrated behavior | Product implication |
|---|---|---|
| 0–5 s | Ability scores, saving throws, conditional save notes, passive senses, persistent character status | Make score, modifier and conditions readable together |
| 5–20 s | Skill table and tap-initiated animated rolls; roll-result cards show formula and advantage choices | One explicit roll control; preserve individual faces and selected die |
| 20–28 s | Attack rows, attack/damage buttons, bonus/reaction sections and limited-use counters | Put attack and damage together while keeping action costs visible |
| 29–38 s | Inventory locations, quantities, containers and separate attuned-item area | Carrying, equipped and attuned are independent concepts |
| 39–47 s | Spell search, level filters, spell attack/DC, slot counters and higher-level spell views | Keep slot level distinct from the spell's original level |
| 48–52 s | Movement and defenses | Surface actionable defenses in combat as well as on full sheet |
| 53–64 s | Class/species traits and resource uses | Group long references and link counters to recharge rules |
| 65–69 s | Proficiencies, tools and languages | Support tool checks as well as the standard skill list |
| 70–73 s | Background and narrative notes | Narrative content needs reading typography, not numeric-editor chrome |
| 74–78 s | Companions grouped by role, with compact summary statistics | Give companions their own mechanics, forms and recovery |

This was observational playback, not a live verification of the video's application build or current campaign accuracy.

## What works well

1. Numeric totals sit next to the action that uses them. Attack bonuses and damage have separate tap targets.
2. The desktop sheet retains abilities, defenses and HP while tab contents change.
3. Actions are filtered by actual turn economy, reducing search time during combat.
4. Spell rows make timing and range scannable; longer explanations live in a detail drawer.
5. Inventory separates location, activity, quantities, weight and attunement.
6. Detail drawers preserve the sheet's context instead of replacing it with an editor.
7. Roll feedback identifies the originating action and formula rather than showing an unexplained total.
8. The video distinguishes advantage's rolled faces from the selected result.
9. Spell-level filters support seeing the effect at the chosen casting level.
10. Counters show both available uses and the relevant recovery trigger.

## Friction and Astravar design decisions

These are research interpretations, not assertions about every D&D Beyond device layout.

| Observed friction | Astravar response |
|---|---|
| Dense desktop columns require small text and many repeated visual frames | Native single-column cards with a compact combat mode; scale text rather than shrinking it |
| Public example's ability-score button gave no actionable response | Inspector and edit actions must explain availability and have explicit labels |
| Spell-detail drawer overlaps the main sheet | Use a mobile bottom sheet with pinned resource preview and clear dismissal |
| Inventory filter leaves empty containers with “No equipment has been added” despite an active query | Distinguish “No matching items” from a genuinely empty inventory |
| Inventory filtered count and total carried weight refer to different populations | Label carried totals separately from match counts |
| Multiple containers may share the same visible title | Include path or a short distinguishing label; retain stable IDs internally |
| A tiny checkbox carries equipment meaning | Use a labeled Equip/Hold/Store action and separate attunement control |
| Long rule descriptions can dominate the action list | Keep effect summary and resource button visible; expand reference text on demand |
| Artwork and decorative borders compete with small numbers | Preserve Astravar artwork in purposeful identity areas; prioritize gameplay contrast |
| Source annotations are valuable but often secondary | Keep source/history in an optional inspector without requiring citation entry |

## Owner-workflow limitation and minimum continuation

Public-sheet research is complete for the interactions above. To extend this into owned-sheet write workflows, the minimum assistance is a supported user sign-in handoff to D&D Beyond in the surfaced research browser, followed by permission to use a clearly disposable test character. Passwords, session cookies and authentication tokens must never be requested in chat or captured. That handoff has not been initiated because it is unnecessary for the completed public walkthrough and core Astravar implementation can proceed independently.

A disposable test character is necessary for live cast, rest, equipment and leveling verification to avoid changing the owner's actual campaign sheet. Until that research occurs those workflows remain marked video/documentation-only or untested in [functional-parity.md](functional-parity.md).

## Interoperability

See [official-interoperability.md](official-interoperability.md). No supported public character API contract was verified. A user-selected file or official PDF export with explicit local review is the viable baseline. A browser link is navigation, not synchronization.

## Implementation priorities from research

- One computed character projection powers sheet, equipment preview, attacks and spells.
- Give each roll an explicit initiation, mode preview and durable breakdown.
- Present reversible equipment changes with a computed before/after explanation.
- Tie a resource counter to its actual recharge rule; never refill on navigation.
- Retain unknown values as unknown, with a useful setup action.
- Favor four primary gameplay destinations and nest infrequent administration.
- Keep original imported text for recovery while rendering readable structured content.
- Treat companion forms as accepted structured records rather than generic monster substitutions.
