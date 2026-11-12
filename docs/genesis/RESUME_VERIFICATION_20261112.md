# Genesis resumed verification — 12 November 2026

Base: `52831db4963639eca07956c3e485f45cfe673431`.

The execution environment is available again. Read-only searches of the recorded
workspace and accessible workspace/tmp/home/mount paths did not find the previous
Astravar worktrees, runtime, private recovery directory or local commit objects.
No existing worktree was reset, cleaned, overwritten or reinitialized. All
published checkpoints remain on GitHub, including the combined source and its
three parents. PR15 remains draft/unmerged at `7e229a43` on its original base.

A new checkout, `Astravar-Genesis-Resume-20261112`, restores the published source
without replacing an old directory. The original local-only diagnostics and
signing helper are not recovered. Do not recreate missing feature work already
present in the combined source.

## Actual failed gates

- Full run43152318166 passed320 Python,134 bridge and166 rules tests, then failed
  test compilation: GenesisEncounterMembershipProofTest treated saveProfile's
  CommandReceipt as a CharacterProfile. App tests never executed in this run.
- Native run43152318129 built the exact52831 release and passed nine native tests
  across upgrade, overlay, large/recovery and PlayerFirst stages. VisualRebirth
  failed because its Campaign marker was clipped at200% text. Genesis failed
  clicking a stale Downloads object during real SAF export. Later connected,
  crafting and encounter verification was not reached.
- Native artifact13915553395 size76035828, SHA256
  `af6f06d3841e1585984952061a08314dc311079b92949aab66f60d61e7025e43`,
  source marker and embedded APK source independently verified as52831.
- Independently rerun captured-database checks passed additive schema6→9 retention,
  later canonical heal with original source preserved, and test-signer49→50
  package/UID/first-install-time retention. These use fictional emulator data.

The initial fixture correction reads the actual persisted profile through
recordsDao.characterProfile after saveProfile. All five existing assertions and
corruption scenarios remain.

## Corrections preserved before runtime verification

- Locala059c17 / publishedd3449e214bf414a3186b90f48079402298c1ee9b fix the
  authoritative profile fixture. Their complete tree matches exactly.
- Local3ee6a17 / published79525a8a42ec3bd02ba0398e55756331f6fb366d scope Downloads
  to the actual document-picker drawer, observe fresh enabled bounds and dispatch
  each touch once. Filename entry is read back exactly; the real export, verified
  backup prerequisite, restore and restart assertions remain. No sleep or retry
  of a write was introduced. Their complete tree also matches exactly.
- Campaign context now shares the active section's scroll surface. People/Journal
  tabs remain fixed; the duplicate Campaign heading is removed. Independent saved
  positions and disclosures survive section changes, detail/Back and recreation.
  A new actual-shell200% text regression verifies full text bounds and all routes.
  The failing native visual assertion remains unchanged.
- Source/signing guards passed82 foundation files,33 protected files and all12
  immutable schema pins plus additive schema9 checks. No guard was weakened.
- Original owner key and trusted code49 APK were recovered privately. Full
  certificate and original APK checksum match their recorded values. No signing
  or installation has been performed.

The resumed build runtime is available. Focused backend verification at locala059
passed all60 app cases and166 rules cases with zero failures/errors/skips. App
production and test Kotlin compilation passed. XML is preserved separately before
subsequent tests. The clean verification worktree advanced to locala4c to test the
actual200% layout, navigation, lint, QA APK and native-test compilation.

Native visual capture now also reveals a static label clipped at its observed
ScrollView edge before the unchanged stable-pixel checks. It reacquires bounds
and uses at most five restrained gestures in30seconds, failing if the text cannot
fit. No font,48dp, pixel-ink or stable-frame threshold changed. This complements
the product layout correction; it does not bypass it. Final UI/native acceptance
remains pending; no signed APK is claimed.
Direct git push lacked credentials, so the authenticated GitHub connector
published matching trees without moving PR15 or overwriting old checkpoints.

Local focused UI verification at local56d3729 / publishedb724e3b passed29 of30
cases. The new responsive test passed full-text visibility but incorrectly
expected a minimal bring-into-view scroll to hide the campaign selector. Its
correction measures actual selector displacement against viewport scrolling,
retains the offscreen check after deeper scrolling, and adds a selected long
campaign name. All existing route, bounds and restoration assertions remain.
This follow-up still requires execution. Backend60/rules166 results above remain
valid for their recorded source; no broad release acceptance is implied.

## Reading-position regression found during resumed QA

Local lintDebug and QA assembleDebug passed (zero lint errors,69 warnings/1hint).
Native test Kotlin compilation exposed a nested implicit-lambda shadow in the
file-picker helper; naming the observed node explicitly fixes it. Native observer
and test APKs then compiled and assembled successfully.

The long-campaign fixture correctly rejected the old partial export; the test
now compares Complete connected backup fingerprints before/after navigation.
All full-text, geometry, route, section-position, detail/Back and initial Activity
recreation checks passed, then the final expanded-library assertion found a real
product defect: returning People→Journal shifted scroll4521→4398. Journal content
re-subscribed with empty initial lists, briefly measuring a shorter page and
clamping the saved position before its real records arrived.

The fix retains a combined loaded journal snapshot above the section boundary and
mounts restored scrolling content only after its campaign context and relevant
records have emitted. Empty saved lists remain valid; loading is distinct. No
delay or corrective scrolling is used. The unchanged exact-position assertion
is extended through primary navigation and a second parcelled Activity recreation.
Standalone label tests wait for the real journal instead of its loading heading.
This product correction requires fresh execution; oldb8ce full/native runs
43220288546/43220288629 predate it and cannot establish release acceptance.
