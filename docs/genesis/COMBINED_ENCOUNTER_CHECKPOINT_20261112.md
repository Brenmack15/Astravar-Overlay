# Combined encounter verification checkpoint — 12 November 2026

This is a new, untested integration checkpoint, assembled through GitHub while
the original execution environment is unavailable. It is not a signed release.

## Preserved parents

- D product/schema/editor/historical-proof: `89712ae0c47a92c9d75d41a36b866c1d4b835cfa`.
- C crafting/routing/portable-provenance: `8ac2f646ee697bec5ad7b6198c15475c537fb0a2`.
- Reconstructed complete native9 pipeline: `1e1b5a97a0c3dacce3df6e64f099013b033cf1ac`.

All three remain ordered parents of the integration commit. Existing checkpoint
branches and PR15 are preserved. No local checkout or dirty files were changed.

## Integration review

D is the base tree. Eight nonoverlapping C files and twelve native-recovery files
were copied by exact Git blob identity and existing file mode. The sole shared
product file is GenesisAuthorityValidation.kt: its D encounter validation was
retained, and the exact four-line C original-origin/createdAt invariant was
inserted beside the same managed actor lookup. No other source was synthesized.
This document is the only additional file.

The C snapshot race, D saved-editor snapshot race, original Unit correction,
D historical membership semantics and C portable provenance correction are
included. Native PlayerFirst Back and initial Roll dice discovery observations
are included without removing gameplay assertions. The missing seven native
files have complete reconstructed equivalents; this does not recover original
local991dd4a bytes. Read NATIVE_RECONSTRUCTION_20261112.md for exact boundaries.

The local e023321/803668e diagnostic-only edits remain to recover if the original
worktree returns; their functional tests are present, but the additional timeout
semantics/screenshot diagnostics were not invented during this integration.

## Required verification

No Kotlin/Python execution or emulator result exists for this integrated tree
at publication. Source readback and peer review are not test passes. Run the full
debug/release/rules/Python/bridge/lint gates and complete schema9 native pipeline,
including original schema6→9 retention, connected backup, crafting, encounter,
later imports and rollback. Investigate failures without dropping assertions.

Earlier E4ab full results, c66 and C8ac workflow results apply only to their exact
source commits. They cannot establish this D integration's acceptance.

Original owner signing, actual owner-key in-place upgrade, physical Samsung
Galaxy S26/Android17 and remaining whole-project scope are still pending.
No owner keys, private recovery data or campaign records are included here.

## Resume

First read the current central RELEASE_CONTINUATION_20261112.md on
checkpoint/astravar-genesis-release-verification-20261112. Recover existing local
worktrees/commit objects before any reset/clean/checkout/reconstruction. Preserve
and compare any recovered991dd4a/e023321/803668e source. Continue from this
integration and its guarded descendants without replacing earlier history.
