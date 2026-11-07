# Data and synchronization audit

Inspection baseline: `852bbd48af9b6c70be5ce44d5f898d1224ca0e7c` on `feat/astravar-dynamic-prompts`.

The existing code has substantial safety foundations: `CampaignLedgerDb` v6 with explicit migrations; profile/ability/item/party rows with stable IDs and revisions; transactional command receipts and append-only audit; merge-only guarded campaign restore; rich source/reference archives; a private authenticated synchronization domain separate from the fictional anonymous lab. These remain the recovery and source boundary.

| Finding | Impact / severity | Effort / dependency | Reforge action |
| --- | --- | --- | --- |
| Manual `CharacterAbility.magicModifier` adjusts only displayed modifiers | High / correctness | High / typed engine | Preserve original; native typed effects replace interpretation after explicit review |
| `CharacterProfile` is a manually sourced display snapshot | High / correctness | High / engine and new aggregate | Add explainable derived native aggregate without treating old values as permanent facts |
| Ability score source and notes combine attribution with editor input | Medium / usability | Medium / UI | Preserve source snapshot outside normal gameplay controls |
| Party attacks store free-form damage formulas and source strings | High / correctness | High / typed attack contracts | New typed native attacks/damage components; no automatic textual conversion |
| Party spellcasting is a single narrative string | High / gameplay | High / spell/resource contracts | New structured spell and slot state; preserve narrative for review |
| Legacy inventory quantity can be unknown | High / integrity | Medium / instance mapping | Do not fabricate a one-item stack during adoption |
| Ledger equipped state does not itself execute mechanical effects | High / gameplay | High / engine activation | Separate native instances and definitions plus explicit effect activation |
| Imported effective AC/maximum HP has no structured equipment baseline | High / correctness | Medium / reviewed reconciliation | Warn at adoption; preserve recorded values and require baseline review before equivalent effects |
| Equipment and companion descriptive sheets are not full executable rules | High / gameplay | High / engine and accepted-source review | Retain records; do not substitute an unverified monster/homebrew interpretation |
| Existing full campaign backup knows nothing about new native aggregates | High / recovery | Medium / versioned backup | Keep old contract unchanged; add complete separate Reforge backup/restore API and SAF file interface with independently verified current-backup guard |
| Old imports have independent immutable source snapshots and receipt proofs | High / integrity | High / archive compatibility | Avoid old schema/receipt rewrites or silent cutover |
| Original profile changes and new local native edits are independent domains | High / conflict | High / explicit sync adapter | Label local-only; preserve reviewed source hash, no last-write-wins bridge |
| Remote mirrors can be stale while device has newer offline state | High / correctness | High / scoped receipt/readback verification | No remote seeding; source local reviewed snapshot only |
| Opening or rebuilding UI can accidentally encourage resource initialization | High / correctness | Low / lifecycle discipline | Empty store; no implicit initialization, rests or refills |
| Roll feedback needs immutable raw faces and bound stats | High / trust | Medium / dice contracts | Store actual signs/kept flags, original revision and manual status |
| Retrying a changed action after stale state can double-consume resources | High / accounting | Medium / receipts | Exact command fingerprints; atomic optional roll+state; reject changed retry |
| Backup files can contain duplicate members or broken snapshot chains | High / security | Medium / strict decoder | Duplicate rejection, bounded sizes and chain/revision validation |
| Cross-database adoption cannot promise a global atomic source lock | Medium / consistency | High / future single-store migration | Recheck selected source, preserve precise snapshot, disclose copy boundary |
| Deep nested item containers can produce cycles | Medium / integrity | Low / validation | Native save rejects missing containers/self/cycles |
| Large revision declarations can make naive backup validation allocate enormous ranges | High / availability | Low / defensive bounds | Bound revision by actual history count before range validation |

No production backend migration, scope escalation, private campaign write or anonymous endpoint change is performed by this work. Public documentation intentionally omits the contents of private campaign checkpoints.
