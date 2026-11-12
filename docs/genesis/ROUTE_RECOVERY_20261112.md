# Character route recovery — 12 November 2026

Base: `e4ab3e6895300aed66e87cf64fce669252d934b6` (crafting verification source).
Prepared and preserved through GitHub while the existing execution environment was unavailable. No worktree was reset, cleaned, overwritten or recreated.

## Observed failure and correction

The existing native Genesis journey reached the correct hero's Equipment page after Inventory → Open hero's item, but displayed “That saved sheet is unavailable or archived. No other character was opened.” instead of the item dialog. The native Edit assertion correctly failed.

In `ReforgeCharacterSheet`, asynchronous effects read readiness from delegated Compose State but used an actor list captured during the preceding composition. An IO emission between composition and effect execution could therefore combine “loaded” with the previous empty loading list, reject a valid route, and consume its token.

Both loaded/records and loaded/selected-actor pairs now use one immutable value per composition. All existing missing/archived guards, actor-change cleanup, explicit route tokens, pager state and main-actor ownership rules remain in place. This changes no saved records, rules, migrations, signing configuration or application identity.

## Regression to execute

`GenesisInventoryNavigationUiTest` exercises the actual application shell and shared Room repositories:

- Create equipment from Inventory for an explicitly connected fictional hero.
- Return to Inventory and open that exact physical item through its owner action.
- Open its real Edit form without modifying it, close, then repeat the item route.
- Recreate the Activity from parcelled saved state with the detail dialog open.
- Close the detail and use Android Back to return Equipment → Sheet.
- Verify the item and owner identities in a complete backup, no duplicate item, unchanged unrelated actor and weapon state, and unchanged selected hero.

Existing `GenesisCharacterRoutingTest` missing-actor, missing-item, exact-item and companion-isolation assertions remain unchanged. The failing native `GenesisJourneyInstrumentationTest` journey remains the required device/emulator regression; it has not been weakened or bypassed.

## Verification status

**Untested checkpoint.** No compilation, unit tests, native tests, APK build, signing or installation ran for this change because the execution environment was unavailable. Source inspection is not runtime verification.

When the original environment is available, preserve its worktrees first, integrate this checkpoint without resets, run the new inventory test plus all existing character-routing tests, then rerun the original native Genesis journey and the required release gates. The separate encounter milestone must receive this shared fix before its pending hero-action route and saved-setup tests are evaluated.

This checkpoint does not replace PR #15, advance the crafting verification branch, merge main or claim a completed release.
