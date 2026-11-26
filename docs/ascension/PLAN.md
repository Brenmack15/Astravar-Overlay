# Astravar 4.1 implementation ledger

Owner directive dated 2026-11-26, sections 1–34. The private Active Development Backlog, DM Bridge Gameplay Audit and equipment integration tracker are source keys B, A and E. The requirements matrix indexes the frozen B source in document order. The implementation branch is `ascension/4.1-cloud-authority`. No production campaign or owner enrollment is part of this implementation.

## Baseline

| Evidence checked on 2026-11-26 | Finding |
|---|---|
| GitHub PR 40 | Open, code 58 at `883198a3140ae2b707b31f81829c94cd821189b9` |
| GitHub PR 41 | Open draft at `ae5dfc7622da5d225f97279bcdff837f0b2e893f`, 16 commits on top of code 58. Includes native timed form mechanics and optional wake. |
| GitHub Actions 45562139259 | Full, fast and historical upgrade gates passed on the development line. Frozen purchase reproduction was skipped in this run. |
| Main branch | `b4ec3513c93138dbc5e9b2ce2b8a420d10ff4458`. The feature branch descends from PR 41. |
| Public source identity | Permanent package com.yazsras.astravar. PR 41 still declares 4.0.0-convergence5 / 58. Separate physical acceptance in A is for code 58. |
| Fresh host regression | `node --test backend/owner-bridge/*.test.mjs` on unmodified PR 41, 163/163 passed using Node 24 and real SQLite. |
| Issues 36, 38 and 39 | Preserve the reviewed native mechanics and immutable purchase outcome. Lifecycle verification uses a disposable fixture. The known purchase is resolved in the physical audit. |
| Client authority | The active native sheet writes through Reforge/Genesis. A foreground sync loop and background worker can commit pending legacy commands. Device publication is a filtered mirror. |
| Server | Existing private Sites Worker uses scoped authenticated device and connector ingress, D1 and optional Pushy. Its existing command submission/acknowledgement records do not constitute cloud gameplay commits. |
| Backup | Nine Room families. Connected Backup covers the ledger, Reforge and six Genesis families. Independent weapon data, provider-owned media bytes and service receipts require explicit coverage. |

## Architecture decisions

1. Extend the existing owner-private Worker, MCP identity and SQL migration chain. Add typed versioned gameplay endpoints. Both clients execute the same server command planner. Consolidate local calculations into previews or legacy mode after explicit cutover. Cross-check ported rules against native fixtures before accepting parity.
2. Use SQLite-compatible relational tables on the existing D1 model for this implementation. Separate continuity identities, authorities, epochs, scoped grants, canonical entities, dependencies, proposals, receipts and change history. Store validated domain values with stable references. Never treat a mirror row, unpublished client total, or copied Room blob as a cloud-authoritative actor.
3. An absent cloud continuity keeps the existing authority model. Cutover requires an owner-authorized, verified source manifest plus a higher epoch. Both generations of ingress must respect a fence. Reading, upgrading and syncing cannot implicitly enroll a campaign. Independent cloud commits persist before any push or Android acknowledgement.
4. Each command supplies an ID, an epoch and an expected continuity revision. A single database transaction checks the current grant and credential, validates the epoch and revisions, updates all related data and commits immutable evidence. Reuse the canonical JSON digest. Reject a reused ID with different intent. Evaluate event identity before supersession. Scope result visibility, including receipts and relationships.
5. Keep approved entity media in a private object store and only store checked associations, hashes and versions in SQL. Media upload, validation, owner approval of the exact bytes/target, association and cleanup need separate evidence. No unrestricted binary or arbitrary field writes through gameplay.
6. Keep the existing native navigation and mechanics available before a separately authorized migration. Cache only scoped, player-known cloud records. Reads can report staleness. Gameplay disables authoritative writes while offline in cloud mode.
7. Original edition profiles, source-backed overrides and unknown values survive migration. Production staging, publication or cutover are outside the release rehearsal. Public test records and documentation use invented engineering fixtures.

## Provider and operations decision

The service already deploys a single stateless Worker and D1. D1 executes prepared statements in transactional batches and rolls a failed batch back. Use a database assertion inside the batch for comparisons where a zero-row SQL update would otherwise be a success. Independent post-commit reads must go through the primary or a session with appropriate consistency. One database serializes queries. A single private campaign with bounded indexed queries is a plausible first workload. Measure query rows, latency, storage, payload bounds and concurrent mutations at representative volumes before provisioning. [D1 API](https://developers.cloudflare.com/d1/worker-api/d1-database/) · [D1 limits](https://developers.cloudflare.com/d1/platform/limits/)

Managed PostgreSQL also fits foreign keys, strict concurrency and point-in-time recovery. It adds a separate database service, credentials, connectivity and another recovery system to the existing Worker. Revisit if measured write contention, migration size or regional requirements exceed the supported D1 design. [PostgreSQL recovery](https://www.postgresql.org/docs/current/continuous-archiving.html)

Keep test SQL and media isolated. Production region and residency, provider/account, media capacity, billable allowances, export retention and encrypted backup custody await the owner. D1 charges by rows read/written and storage subject to plan allowances. R2 Standard includes a free allowance and charges for stored bytes and operation counts beyond it. D1 Time Travel retention is seven days on Free and thirty on Paid on supported databases. SQL recovery needs a matching private asset manifest and application-level post-restore checks. The existing Site has no configured private media binding in its example. No resources or billing were provisioned. [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/) · [Time Travel](https://developers.cloudflare.com/d1/reference/time-travel/) · [R2 pricing](https://developers.cloudflare.com/r2/pricing/) · [Data location](https://developers.cloudflare.com/d1/configuration/data-location/)

## Phase dependencies and evidence

| Phase | Implementation gate | Independent evidence required for acceptance |
|---|---|---|
| 1 | Source and feature audit, traceable decisions, recovered base | Current PR tips, complete linked specifications, code inspection and baseline tests |
| 2 | Canonical schema and operation contracts | Fresh and upgrade schema tests, cross-continuity foreign keys, atomic failures, stable identity and invariants |
| 3 | Authoritative service and DM integration | Actual Worker ingress against durable SQL, separate authorized readback, retries/races, approvals and revocation with Android absent |
| 4 | Four native destinations and connected gameplay | UI/gesture instrumentation, replay-safe submission, read-only stale mode, correct-epoch reconnect and both client directions |
| 5 | Preservation and migration tooling | Independent counts/digests, real schema-version fixtures, recovery/rollback and refusal to discard later writes |
| 6 | Release | All per-requirement gates, owner-signed APK identity, alignment, digest, source correspondence and upgrade evidence |
| 7 | Outside this assignment | Separate owner-authorized production migration |

Each feature needs scoped retrieval, authoring/execution where applicable, permission handling, a durable receipt and independent readback before claiming functional parity. Features unavailable to the new backend remain identified in the trace and capability matrices. Git history records implementation checkpoints. Update this ledger after verification and before any release designation.

## Isolated engineering checkpoint

The release baseline and source trace register are preserved on the feature branch. The first authoritative Worker verification used a file-backed SQL database with zero registered Android credentials. Authenticated MCP changed an actor, made an exact-denomination purchase, spent a finite resource and wrote a resolved timeline event. Each commit produced an independently read receipt. A fresh Worker/database handle and subsequently paired synthetic device read the resulting records once, then the DM independently observed a device-originated edit. This exercised the real Worker and device wire protocol. The native APK, Android UI and physical handset have separate gates.

The approval fixture survived restart, bound the reviewed digest, and exercised refuse and revoked-grant behavior. The equivalent absolute ability fixture links a distinct superseded receipt to the satisfying commit. The database fixture fences old device writers, checks foreign keys, and rolls back failed multi-record commands. The trace checker indexed 175 directive entries, 503 primary backlog paragraphs, 52 companion fixture paragraphs and 63 AI capabilities. These counts measure coverage of the register. Individual feature acceptance remains tracked independently.

## Integrated development checkpoint

PR [42](https://github.com/Brenmack15/Astravar-Overlay/pull/42) targets the preserved PR 41 branch. Remote commit `416a9bc921ddba12b8d7da632acc374c9746e874` integrated the first native service client, game operations, migration tools, private images and lifecycle. A first CI run identified a missing classification for the newly introduced grant-audit table in the earlier recovery mapper. The subsequent implementation adds an explicit retained-grant-history mapping and checks that a cold restore conveys no active prior grant.

The tested provider smoke used the bundled Worker and its real local D1 and R2 bindings. The SQL primary reported one committed receipt, one actor revision and zero registered devices after a DM command and exact redelivery. Additional worker-wire fixtures cover atomic purchases, scoped pending approvals surviving restart, an equipment supersession without an extra equipment event, and rejection of a separate same-price purchase. Isolated synthetic tests cover archive erasure, protected recovery, independent receipts, armor Integrity and overflow, spell and resource accounting, and player dice ownership. Native and physical evidence belongs to a separate verification gate.

Version 59 is reserved for the development candidate after auditing prior branches, tags and release inputs. Historical signed code 58 is the predecessor for update and signing checks. See [operations](OPERATIONS.md) and [recovery](RECOVERY.md) for operator setup, recovery limits and deployment boundaries.

The expanded provider integration made eight gameplay commits with zero registered devices, including a purchase with exact change in one wallet, resource expenditure and Chronicle. A ninth request stayed pending. A newly connected scoped client retrieved eight unique records over multiple pages. Subsequent owner approval and a client-originated edit produced final revision 10, verified by MCP. A D1-specific error surfaced because audit triggers affect the provider's aggregate changed-row count. Grant management now reads the row's actual returned revision. The same gate runs in CI using an isolated runtime.

The 99-command registry now has a two-direction semantic protocol suite. It exercises 98 gameplay commands through the registered MCP ingress with readback over a scoped Android credential, then reverses the roles. Each direction submitted 383 synthetic commands and independently read 624 changed records. Exact retries preserve the immutable receipt. The single owner-only continuity configuration command has its own owner-positive and client-denial tests. This coverage measures command contracts across both protocols. UI interaction, physical device acceptance and legacy conversion have their own evidence rows.


At the next integration checkpoint the package version inputs are 4.1.0, code 59. The registered service has 104 verified bilateral gameplay operations and one independently protected owner-only operation. Each direction exercised 534 commands and 887 changed-record readbacks. A clean generation restored immutable outcomes, exact asset bytes and unresolved owner review in workerd with real local D1 and R2. The recoverer detected grant drift and refused rollback after subsequent committed gameplay. The reviewed native importer also staged a five-record, four-relationship fixture from two sources without replaying the three retained historical events. The release remains subject to the unfiltered native suite, exact-commit CI and original owner-signing checks. Resident campaign data stays device-authoritative.
