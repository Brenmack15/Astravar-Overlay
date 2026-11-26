# Astravar 4.1 release traceability

The matrices index the owner's 26 November 2026 directive, complete private release backlog and companion integration specification with a candidate-evidence update against the isolated Ascension implementation. The verified development baseline is `ae5dfc7622da5d225f97279bcdff837f0b2e893f`. Requirements superseded only within a specific scope keep their original anchors. Production reconciliation, campaign staging, record cleanup and real authority cutover belong to a separate owner decision. Validate the engineering requirements against isolated continuities.

| Register | Stable IDs | How to use it |
|---|---|---|
| [Directive](requirements.csv) | `D41-…` | All 34 sections and separately numbered subsections, including the six phases, ten end-to-end suites and eight required deliverables. The source locator gives the authority for each row. |
| [Detailed backlog](requirements-backlog.csv) | `BL26-…` | 503 substantive source paragraphs, including all 344 literal checkbox paragraphs, multi-part acceptance tests, scoped corrections, previous evidence and historical authorization notices. The source is the complete private page in directive §3.1. |
| [Regression specification](requirements-fixtures.csv) | `EQ26-…` | 52 release-relevant companion specification, preservation and correction paragraphs from the private tracker in §3.4. Original mechanics and exact numeric fixture inputs remain in the authorized source. |
| [Authorized DM parity](capabilities.csv) | `CAP41-…` | 63 capabilities spanning the Android surfaces, DM read/author/execute paths, review, native result, independent checks and deliberate owner exceptions. |
| [Candidate evidence](requirements-candidate-evidence.csv) | `EV41-…` | Result level, exact implementation/test path and case, environment, observed behavior and remaining limitations. A `SOURCE` entry is awaiting an executed check. |
| [Historical evidence map](requirements-history.md) | `AV-…` and `AU26` source locators | Earlier native, connector and release evidence, including which historical findings later work superseded. |

`BL26` and `EQ26` anchors are one-based lines in the complete private Notion fetch text captured on 26 November, including the fetch metadata lines at its head. The original paragraphs are the definition of the requirement wherever a short public synopsis omits detailed mechanics. The restricted review copy indexes each paragraph and its sentence units, including multi-clause acceptance and owner corrections. Preserve these IDs when sources change, append new IDs, and record any scoped supersession; do not renumber rows to match a later export.

`implementation_or_baseline_area` supplies the actual repository area to inspect. `existing_related_tests` gives regression starting points. `AT-…` is a planned acceptance case ID, separate from evidence of an executed test. Record the implementation path, commit, environment, exact test or manual interaction, behavior observed, independent receipt/state evidence and remaining limits when updating a row. Redact private identities and payloads in public evidence. Where a baseline component covers only part of a row, leave it open and specify the observed subset. Child source paragraphs and capability rows have their own independent evidence fields.

| Disposition | Meaning |
|---|---|
| `PARTIAL` | An isolated candidate check supports an explicitly stated subset of the source requirement. Additional work or release-environment verification is outstanding. |
| `OPEN` | Approved work or full release acceptance outstanding. |
| `REGRESSION` | Existing work or pinned mechanics require verification on the candidate. |
| `IMPLEMENTED_UNVERIFIED` | Integrated source exists and still needs a functional check. |
| `VERIFIED` | Concrete narrow candidate acceptance with recorded evidence. |
| `INDEPENDENTLY_VERIFIED` | A second authoritative read, fresh session, clean restore or physical acceptance independently proves the intended result in the described environment. |
| `BLOCKED_EXTERNAL` | Identify the actual external dependency and completed isolated checks. |
| `HISTORICAL` / `REFERENCE` | Original observation, prior disposition, reference or constraint. No claim about a fresh 4.1 pass. |
| `PROPOSED` / `OPEN_INTAKE` | Owner has not approved the detailed option, mechanic or future issue. Its approved parent remains tracked. |
| `SUPERSEDED` / `OUTSIDE` | Earlier authorization changed within its scope, or execution belongs to separately authorized production work. |
| `OWNER_EXCEPTION` | Security administration or local device interaction belongs to the owner. Verify denial to a gameplay-only principal. |

The `candidate_evidence_ids` column joins to the candidate evidence register. On an open or historical row, a related test provides context only. For a partial row, use the observed subset and limitations from that check. Capability evidence can cover a real service operation even when the corresponding user interface, special campaign rules, backup path or physical-device verification remain open. A linked test across a single record family is never proof of the entire family.

The capability registry describes required contracts in domain terms. A legacy tool reference identifies an existing foothold. Scoped credentials, current grant, named actors, applicable visibility and a matching authority epoch govern every real command. A family that spans actors or domains requires the corresponding permissions on every affected record. The `AGENCY` and review descriptions preserve player dice and optional expenditures regardless of grant breadth. Each `AT-CAP41` needs a positive flow, an independently authenticated read, a protected-scope negative flow and the relevant Android client flow before parity can be claimed.

Each feature lists its callable `candidate_dm_reads` and matching `related_registered_commands`. The `wire_verified_commands` column derives from actual submissions through both authenticated wire protocols and the harness’s independent state/receipt checks. Remaining command-level gaps appear in `wire_unverified_commands`. Rows covering owner-only administration, read-only projections, clipboard behavior or a transaction envelope may have no gameplay command. A successful protocol check establishes the contract exercised by its fixture. Native interaction and specialized mechanical coverage remain independently tracked. The runnable evidence is in [protocol parity](protocol-parity.json).

Run `python3 tools/ascension_trace_check.py --sync-wire` after regenerating the protocol report. Run `python3 tools/ascension_trace_check.py` from the repository root. An authorized reviewer can add `--backlog /private/path/to/frozen-backlog.md` to confirm every original checkbox is indexed without disclosing its contents. The checker validates coverage, references, unique IDs and required evidence links. It does not execute gameplay tests.
