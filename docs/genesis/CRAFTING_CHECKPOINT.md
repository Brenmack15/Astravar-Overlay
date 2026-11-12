# Connected crafting continuation — 12 November 2026

This checkpoint belongs to `recovery/astravar-genesis-crafting-20261112`. It is
separate from the frozen B release candidate until the coordinated validation and
source publication finish. No signing identity, source pin, schema or accepted
mechanic changed.

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
the next coordinated Gradle run must execute all 16 backend crafting tests.
Do not report this follow-up as tested until that run completes.

Large-current-roster performance remains unverified. Craft review reads current
playable rows and placements to detect ambiguous ownership and container contents.
Existing ownership validation and projection also read the playable roster. This
is linear in current data and may expose wide-row CursorWindow or memory limits;
it does not read unrelated historical archives or export the whole campaign.
Bounded/indexed current-identity lookup needs an end-to-end follow-up, rather than
only replacing the craft preview's scan. Complete backup retains its explicit
64 MiB document limit. Native execution, owner-signed installation and physical
Galaxy S26/Android 17 acceptance remain separate gates.
