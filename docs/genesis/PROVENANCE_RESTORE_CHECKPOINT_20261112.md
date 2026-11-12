# Managed provenance restore checkpoint — 12 November 2026

Base: `c66f2738adc354c73911b08712d5161fd55b3684` (isolated crafting/route checkpoint).

## Correction

Complete connected backup validation now checks a managed playable character's current origin and creation timestamp against its original activation outcome, matching the existing live affected-actor validation. Ordinary character saves already preserve both values. This closes an inherited altered-document gap: changing the current saved envelope and latest ordinary CHARACTER outcome together could previously preserve the revision chain while disagreeing with the original activation proof.

The check reads the frozen activation outcome already validated by the complete-backup path. It changes no source records, gameplay, identity, schema, signing material, migration, or ordinary save behavior. Unmanaged legacy provenance remains preserved without invented reconciliation.

## Prepared regression coverage

`GenesisAuthorityProvenanceBackupTest` adds three fictional Room journeys:

- Alter only origin in the latest ordinary outcome and current saved row; retain the exact activation, campaign data, prior snapshot, character and revision. Confirm the standalone mechanical history remains structurally valid, but complete restore rejects specifically for activation provenance and writes nothing.
- Repeat for an altered creation timestamp that remains within the row's valid timestamp range.
- Restore a legitimate later edit, verify original provenance and mechanics, execute a live association command, and export the complete result successfully.

## Verification and continuation

**Untested source checkpoint.** Prepared through GitHub while the execution environment was unavailable. No compilation, unit/native testing, APK build, signing, installation, or physical-device verification ran for this change.

Run the new three tests plus existing complete backup and authority tests, then the required release gates before shipping. The separate encounter milestone should inherit this verified patch when its histories are deliberately assembled; this checkpoint does not modify its branch or PR #15. No existing worktree or branch was reset or overwritten.
