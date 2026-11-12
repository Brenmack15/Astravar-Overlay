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
corruption scenarios remain. Native picker and large-text product fixes are
being investigated separately. No passing corrected build or signed APK is
claimed by this checkpoint. Original owner signing and installation remain gates.
