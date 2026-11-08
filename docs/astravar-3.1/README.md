# Astravar 3.1 — player-first rescue

## Product decision

The owner reported that the 3.0 native Android app was unpleasant to use: too many
unrelated destinations, oversized forms, inconsistent source/playable records,
unusable companion flows, dated visuals and insufficient whole-app interaction
testing. The goal is an enjoyable, complete daily-use character and campaign
workspace. Preserve existing purchases, characters, lore, provenance, identity,
revision history and signing identity. Refactor presentation aggressively; never
silently destroy user records or convert unresolved mechanics.

## Primary journeys — acceptance gates

Every journey must be exercised through the **native Android UI**, not just a
repository unit test. A screenshot alone is not a success criterion.

| Journey | Current implementation | Release gate |
|---|---|---|
| Open Zerg from saved profiles | Character-scoped Room source snapshot from 3.0 fix branch; explicit review/adoption | Exercise on a database above the old 32-million-character export ceiling and on the owner's real phone, without data loss |
| Create/open/edit a playable character | Character tab; simplified name/level entry; ability and baseline disclosures | Create, close/restart, reopen, edit, verify stable ID and each calculated field |
| Add an ordinary item | Local equipment form with optional advanced effect builder | Create, equip, change quantity/attunement, restart, ensure calculations and HP damage persist |
| Use original inventory as gear | Review one eligible item, retain original ID, verify source revision, copy into playable sheet | Test stale edits, quantity unknown, sold/lost items, duplicate IDs and source immutability |
| Create a new companion | Companion roster creation adds linked profile and state transactionally | Reopen, edit source and sheet, move equipment, transfer currency and confirm every name renders correctly |
| Add a connection | Campaign → Connections, with original entity/fact/milestone handling | Create, edit, link to saved companion or profile, reopen and search; verify removal/conflict behavior |
| Find a tool without navigating a maze | Four primary destinations, Tools in header, advanced features collapsed | Test screen-reader focus, large text, 360dp width, back/dismiss behavior and deep links |
| Preserve original campaign | No automatic Reforge↔legacy sync, no deletion, no schema reset | Migration + backup/restore tests and owner-authenticated readback |

## What 3.1 alpha changes

- Replaces the crowded five-item daily navigation with four stable tasks:
  Character, Inventory, Companions, Campaign. Combat is on the Character sheet.
- Keeps diagnostic, engineering and complex magic interfaces in a single Tools
  location instead of advertising them as everyday destinations.
- Adds original celestial/companion banner artwork and a warmer, more coherent
  color and card system. These two new banners were generated specifically for
  Astravar 3.1 and compressed to WebP; they are not cropped third-party D&D
  illustrations. Native text remains real text for accessibility.
- Exposes playable gear and the original campaign ledger as two clearly labeled
  inventories. The owner can explicitly copy a single eligible ledger item into
  the playable sheet with original identity, optimistic revision validation and
  no implied magical mechanics.
- Allows custom companions rather than only the original pinned cast. Creates
  profile and companion state together; profile details and inventory links use
  current recorded names rather than static placeholder names.
- Allows the Connections directory to link people and groups to existing
  companions, characters, or other connections, in addition to inventory and
  inventions. Opening a link shows the actual target record without copying it.
  The link picker searches and bounds long lists instead of opening thousands
  of choices at once.
- Simplifies initial character and ordinary item setup while preserving optional
  full mechanical authoring and the explicit review requirement for magic.
- Leaves established campaign source, item history and synchronization intact.

## Honest limitations

- This is **not** a new production release or proof of Samsung Galaxy S26
  acceptance. CI builds use disposable debug signatures. Never ask the owner to
  uninstall the permanent app or clear its data to test them.
- The playable Reforge store and legacy campaign ledger still have distinct
  authority. Copies are **not** bidirectionally synchronized. No canonical
  migration/cutover is attempted here.
- A source item with unknown quantity or disputed provenance is not silently
  accepted as active equipment. Imported descriptions do not grant effects.
- A new companion starts with unknown statistics and does not acquire invented
  HP, armor or abilities. Existing pinned Eek, Korr and Veyrath rules are retained.
- Some historical tools and deeply nested editors still need simplification;
  no claim of complete app parity or whole-app acceptance is made.

## Verification instructions

Local structure checks:

```bash
python3 -m unittest discover -s scripts/tests -q
python3 scripts/verify_ui_foundation.py
python3 scripts/verify_upgrade_integrity.py
git diff --check
```

Android CI must run pinned Gradle and capture actual failure logs for:

```bash
./gradlew :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Only label a build installable after validating package ID, versionCode,
certificate identity, artifact contents, safety of in-place update and signed
release provenance. On-device experience and data preservation remain a separate
acceptance gate even after CI success.

## Immediate follow-on work

1. Run and fix compilation, repository, Compose UI and lint failures; review
   generated 360/400dp and large-font screen captures rather than assuming fit.
2. Walk each primary journey with synthetic records, checking every save,
   cancellation, duplicate, stale revision and restart path.
3. Reduce the remaining multi-screen forms to progressive disclosure, consistent
   action placement, searchable selection and useful, actionable errors.
4. Unify relationships and item identity across companion and campaign views via
   guarded source-aware adapters before considering canonical synchronization.
5. Prepare an original-owner-signed update **only** with the existing private
   release signing workflow, after real-device backup/readback verification.
