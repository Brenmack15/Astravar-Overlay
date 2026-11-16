# Private Notion → playable character transfer

This opt-in first pass imports a **user-selected, private JSON file** into an **existing explicitly selected** Astravar playable character. The file is not bundled into the APK, checked into Git, or uploaded by the app.

## Player workflow

1. Open **Inventory → Import character & inventory from Notion file**, or the same action on the intended character's **Equipment** page. Inventory routes to the exact active character; an archived or missing character cannot receive an import.
2. Choose the private `astravar-notion-character-transfer-v1` JSON document saved on your phone.
3. Review current character identity, proposed level/abilities/HP baseline/AC, additions, duplicates, and excluded records. Conflicting values are retained unless the player selects **Explicitly replace conflicting stored stats**.
4. Optionally choose to create a **provisional GP-equivalent wallet**. This is not a verified purse or exact coin denomination. An existing wallet is never replaced and blocks that choice.
5. Save and independently verify the **complete current Astravar backup** to a document before applying. The backup fingerprint must still match at commit.
6. Confirm the exact selected character and the reviewed categories. The merge and optional wallet creation commit in one local Room transaction. The repository checks the saved backup fingerprint under that same transaction; a stale backup, revision or conflict aborts every child write.

## Current bounds

- Confirmed, positive-quantity, subject-owned **Carried**, **Equipped**, and **Stored** physical items can be added. Original Notion item IDs become stable imported IDs. Existing IDs and likely same-name items are never cloned or overwritten. An existing campaign mapping for the Notion source blocks duplicate creation and directs the player to review campaign inventory. The original source URL, properties and rules are retained as item descriptions; connected, assigned imports also retain an explicit Notion external reference.
- **Installed** components are not added as separately usable carried objects. **Consumed, sold, lost, proposed, other-actor-owned, or unknown-location** rows are deferred; their source remains in the private file and the local immutable import request for future reconciliation. The import request is not a campaign-sync publication. This prevents re-materializing armor donors, moving companion equipment to the player, or assuming where masks are carried.
- Only *explicitly reviewed numeric effects* in the transfer file can affect calculated equipment stats. An already existing suit stays unchanged; review its actual effects separately. No powers, charges, spells, attack formulas, HP healing or rest effects are invented by import.
- Permanent abilities are kept separate from temporary magical setting effects. Current HP, lost HP, temporary HP, current spell slots, spent abilities, concentration, game clock, previous rolls and existing items survive unmodified.
- The currency file includes source transactions and a **ledger-derived, potentially incomplete GP-equivalent** figure. Choosing the provisional wallet writes the labeled estimate only (not an assertion of exact coins); it does not replay historical transactions, add their amounts to a prior wallet, or rewrite an existing wallet. Wallet creation requires explicit membership in an existing active Genesis campaign and uses that exact campaign ID, never a hard-coded fallback.
- An optional `character.targetProfileId` binds prepared files to an exact existing sheet. Older v1 files remain supported through explicit sheet selection and subject matching; an import never creates a character from a matching name.
- Connected items use the existing audited Genesis creation commands. A parent immutable receipt binds the complete selected sheet, transfer, choices and backup proof; an identical command retry returns its original outcome after later edits, while changed input with the same command ID is rejected. Reimporting with a new reviewed command retains existing physical IDs and balances.
- The import is *local and manual*, not a standing Notion integration or source-of-truth cutover. The original public app repository must never contain owner campaign exports.

## Engineering verification and release boundary

Pure rules validation, destination binding, duplicate prevention, resource preservation and provisional-ledger checks are in `NotionTransferTest`. `NotionTransferRepositoryTest` covers managed identity creation, backup/restore, stale-proof rejection, rollback, replay/collision, source-identity collisions, wallet campaign binding and current-HP preservation during accepted capacity changes. Inventory UI tests cover the exact-character importer route. Follow the project's prescribed full rules/app/lint/build and native journeys before release. Importing on the owner's Galaxy S26 requires a newer **original-owner-signed APK** and an explicit file selection/confirmation. No signed APK, physical-device commit, or accepted restoration is inferred from a source change alone.
