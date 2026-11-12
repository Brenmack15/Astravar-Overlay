# Connected creation and campaign UI recovery

Reconstructed from the durable schema-8 foundation after the original uncommitted worktree was unavailable. These are replacement source bytes, not a claim that the missing worktree was recovered.

## Implemented surfaces

- Campaign now opens a membership hub with an explicit persistent campaign choice, guided campaign creation, NPC creation, and existing actor membership. The prior journal and its Connections, Inventions, Checkpoints, library and import workflows remain in the Journal tab.
- Character creation uses the established guided character editor with explicit role, campaign and companion owner fields. One authority transaction writes the playable character, source identity, role and membership. Unknown statistics remain unknown and names do not grant class mechanics.
- Companion creation from the roster writes a source-only companion, profile, explicit owner and campaign together. It does not silently create a playable character. Existing source adoption opens shared-value review after actor selection settles.
- Item creation freezes the original owner and revision. For a managed actor it uses the authority batch creation transaction, including engine-split equipped stacks and immutable proofs for each physical identity. Existing unmanaged sheets retain their established save path until explicitly reviewed.
- Membership edits are distinct from mechanical enrollment. Shared-value review is frozen, requires explicit acknowledgement, and binds the exact source/playable state. Legacy modifier explanations require the separate checked per-effect proof; unknown or unsupported modifiers remain unresolved.
- Explicit roles override retained companion-history rows in the roster, inventory routing and search. History is retained. Owner selectors use role, campaign and known level, revealing an exact identity only when context remains ambiguous.
- Complete backup is reachable from Tools → Records, backups & connection and from storage recovery tools. Its document handling is implemented in GenesisUnifiedBackupPage.

## Validation scope

GenesisCampaignUiTest adds actual Compose interactions for campaign creation, source-only NPC and companion creation, connected character → item → complete backup → file-database reopen, stale frozen review rejection, contextual owner labels, and conditional role visibility. GenesisLegacyModifierUiTest covers the modifier decision UI separately. These tests were prepared during reconstruction; the root verification record provides their actual execution status. Source compilation, CI/native journeys and physical-device acceptance must not be inferred from this document.

Encounter creation, full progression modernization and remaining historical editors still require subsequent work. This checkpoint is not the completed Genesis release.
