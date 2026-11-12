# Connected crafting continuation

This checkpoint belongs to `recovery/astravar-genesis-crafting-20261112`. It is
separate from the frozen B release candidate until the coordinated validation and
source publication finish. No signing identity, source pin, schema or accepted
mechanic changed. B's provenance regression and native tab-observation fix are
now merged into this worktree; the only conflict was the explanatory comment on
the identical null-proof guard, and that comment was retained.

## Current verified checkpoint

The second C run passed **106/106 tests**, with no skipped cases: campaign27,
party25, authority backup20, item creation4, crafting16, preferences7, and
crafting UI7. It compiled the new native journey, built the QA APK, and passed
debug lint with zero errors, 68 warnings and one hint. Evidence is
`genesis-crafting-focused2.log`, `genesis-crafting-second-results`, and
`genesis-crafting-reviewed-screenshots` outside Git. The first104-case results
remain preserved separately; do not add overlapping reruns into a unique count.

The fixes retain the direct-invention source-only route, wait for asynchronous
receipt acknowledgment, render historical destination names instead of internal
IDs, and enforce frozen material/container eligibility during restore. The
native crafting journey compiles and its independent SQLite helper passed13
adversarial host tests (plus17 existing connected-readback tests). Native
execution of that new journey remains pending.

B's separate source `9f64619` completed full run `43132381262`: debug868 passed
and3 skipped (871 total), release854 passed and17 skipped (871 total), rules166,
Python259 and bridge134 passed; lint had no errors. The skips are the absent
private archive, two opt-in live-network cases, and release-only exclusions for
debug sandbox tests. Native run `43132380982` reached the upgraded character but
failed because the observer selected the quick-action strip. That helper is now
fixed without changing labels or preservation assertions. The separate46-case
provenance regression batch and native Kotlin compilation passed. Fresh B runs
`43137497126` and `43137497151` on PR15 head `7e229a43` are in progress.

Publish this combined tree to its recovery checkpoint and an isolated
`feat/astravar-project-genesis-crafting-verification` branch, so the full and
native gates can run without canceling B's useful evidence. Advance PR15 only
to the combined source that actually passes. The owner signing material is
outside Git/CI; no Genesis owner-signed APK has been created.

Connected crafting freezes the project, each source material and placement,
affected playable sheets and authority revisions, explicit output owner, and
optional container. Acceptance uses one Room transaction: the existing confirmed
craft consumes source quantities once, each affected actor receives one aggregate
canonical change, and its projections retain the same physical output ID.
Original project, consumption, creation, relationship and canonical child receipts
provide the output's immutable provenance. Later transfer, edits and archival do
not rewrite that proof. Pending recovery only reads a proven exact project,
review fingerprint and command ID; it never retries an unacknowledged operation.

The initial scope is ordinary carried or stored unattuned materials without
effects, contents, composite links or attack dependencies, plus a plain output.
There are no inferred recipes, effects, time, costs or resources. Unmanaged source
materials remain supported; an unconnected playable copy must be reconciled
first. The historical source-only workflow and its managed-material guard remain.

The first C focused run compiled production and test Kotlin. Root reported
101/104 tests passed, including 13/14 backend crafting cases. The crafting failure
occurred in a test's intervening move, which omitted its existing placement
revision; the intended stale-review rejection had not yet executed. This
checkpoint supplies the observed revision and retains the rejection assertions.
Two unrelated crafting UI failures are handled in the UI follow-up.

The historical validator now enforces the same original material and container
eligibility as review. Two added regressions rebuild valid enclosing fingerprints
and child snapshots around invalid frozen states. One exercises complete backup
preview with source-only partial consumption; the other tests typed managed
material and output-container states. They must fail on eligibility rather than
an incidental checksum mismatch. These changes have passed `git diff --check`;
the completed second run above executed all16 backend crafting tests.

Large-current-roster performance remains unverified. Craft review reads current
playable rows and placements to detect ambiguous ownership and container contents.
Existing ownership validation and projection also read the playable roster. This
is linear in current data and may expose wide-row CursorWindow or memory limits;
it does not read unrelated historical archives or export the whole campaign.
Bounded/indexed current-identity lookup needs an end-to-end follow-up, rather than
only replacing the craft preview's scan. Complete backup retains its explicit
64 MiB document limit. Native execution, owner-signed installation and physical
Galaxy S26/Android 17 acceptance remain separate gates.
