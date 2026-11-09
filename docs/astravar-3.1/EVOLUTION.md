# Astravar evolution log

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
