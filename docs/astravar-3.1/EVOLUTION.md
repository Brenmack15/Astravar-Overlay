# Astravar evolution log

## 3.1.0-rc4 · build 48 · Companion quick play

Baseline: 3.1.0-rc3, build 47, at `45dbbf1` in draft PR #12.

Open a companion in the Companions tab to review its campaign profile for
play or go directly to its playable sheet or Combat. After confirming a
sourced attack, the same page offers a shortcut to roll it in Combat. The
companion view returns to the selected roster entry and preserves the default
actor used by Character and My gear.

The route uses the selected companion's exact ID. A missing local sheet
requires explicit source review, and an archived one requires an explicit
restore. It reuses the existing revision-checked adoption, archive, attack and
gameplay transactions. Source records, Room schemas and accepted mechanics
need no conversion. Alternate forms still require separate mechanical review.

Room-backed UI coverage checks exact targeting, explicit adoption and restore,
an unavailable ID, and preservation of the primary actor. The native journey
reviews a fictional companion, opens Combat, heals its playable copy, then
checks the untouched source HP and the saved playable HP after restart.
GitHub Actions records candidate verification against the exact source commit.

Future opportunities include surfacing turn controls before long attack lists,
adding searchable playable gear, and supporting a complete backup and restore
for campaigns above the current export limit.

## 3.1.0-rc3 · build 47 · Spellbook

Baseline: 3.1.0-rc2, build 46, at `969ba38` in draft PR #11.

The Spells tab now searches the active character's own spells by name. The
All, Prepared and Cantrips filters combine with the search. Prepared includes
both prepared and always-prepared leveled spells. Cantrips have their own view.
Results are grouped by level and sorted by name. Empty search results offer a
one-tap reset. Spell cards show saved duration and ritual information, and slot
management opens from Spell slots & preparation.

The existing prepare, edit, cast review, slot spending, and concentration flows
remain the source of gameplay changes. Search, filtering and ordering only
change the view. This cycle requires no database migration or rules change.

The Room-backed Compose regression covers filtering both preparation types,
ignoring case and surrounding spaces, casting from filtered results, observing
a single saved slot expenditure and concentration, and resetting an empty
search. The native journey creates spells, filters and casts a fictional cantrip,
and checks concentration after restart. GitHub Actions records build and test
outcomes against the exact candidate commit.

Remaining opportunities include complete oversized-campaign backup and
restore, reviewed active companion forms, and faster editing of related spells
during preparation.

## 3.1.0-rc2 · build 46 · Roll flow

Baseline: 3.1.0-rc1, build 45, at `2286e90` in PR #10. That candidate
passed the exact-head hosted regression and native gates. Its original-owner
signature and isolated code43-to-code45 upgrade were verified.

Saving a check, spell attack, or weapon attack used to open History and reset
the character page's scroll. The roll now saves through the existing repository
and returns to the originating section. A confirmation shows the saved total
and offers a direct route to History. History presents the attack total and
rolled damage separately, with an expandable view of signed and discarded dice.
Damage in the history view is the amount rolled. Applying it to a target is a
separate game decision.

A pending roll is bound to its character ID and revision. Another sheet with
the same revision cannot accept it. The roll dialog shows save errors and
prevents a second confirmation while the first write is in progress. The existing
atomic roll, resource and receipt transaction remains the save path for attacks.
This cycle requires no Room migration or campaign data conversion.

Verification tracks the Compose save-and-continue journey, identity mismatch at
the same revision, and multiple damage types with signed and discarded dice.
The native player-first journey also enters a fictional physical die face,
checks that saving stays on the sheet, and opens the persisted breakdown.

Remaining opportunities include a complete oversized campaign backup and
restore, reviewed active companion forms, and a searchable spell list.
