# Private Notion → playable character transfer

This opt-in first pass imports a **user-selected, private JSON file** into an **existing explicitly selected** Astravar playable character. The file is not bundled into the APK, checked into Git, or uploaded by the app.

## Player workflow

1. Open the intended saved character, then **Equipment → Import character & inventory from Notion file**.
2. Choose the private `astravar-notion-character-transfer-v1` JSON document saved on your phone.
3. Review current character identity, proposed level/abilities/HP baseline/AC, additions, duplicates, and excluded records. Conflicting values are retained unless the player selects **Explicitly replace conflicting stored stats**.
4. Optionally choose to create a **provisional GP-equivalent wallet**. This is not a verified purse or exact coin denomination. An existing wallet is never replaced and blocks that choice.
5. Save and independently verify the **complete current Astravar backup** to a document before applying. The backup fingerprint must still match at commit.
6. Confirm the exact selected character and the reviewed categories. The merge and optional wallet creation commit in one local Room transaction. A stale revision or conflict aborts both.

## Current bounds

- Confirmed, positive-quantity, subject-owned **Carried**, **Equipped**, and **Stored** physical items can be added. Original Notion item IDs become stable imported IDs. Existing IDs and likely same-name items are never cloned or overwritten. The original source URL, properties and rules are retained as item descriptions.
- **Installed** components are not added as separately usable carried objects. **Consumed, sold, lost, proposed, other-actor-owned, or unknown-location** rows are deferred; they stay in the private source file for future reconciliation. This prevents re-materializing armor donors, moving companion equipment to the player, or assuming where masks are carried.
- Only *explicitly reviewed numeric effects* in the transfer file can affect calculated equipment stats. An already existing suit stays unchanged; review its actual effects separately. No powers, charges, spells, attack formulas, HP healing or rest effects are invented by import.
- Permanent abilities are kept separate from temporary magical setting effects. Current HP, lost HP, temporary HP, current spell slots, spent abilities, concentration, game clock, previous rolls and existing items survive unmodified.
- The currency file includes source transactions and a **ledger-derived, potentially incomplete GP-equivalent** figure. Choosing the provisional wallet writes the labeled estimate only (not an assertion of exact coins); it does not replay historical transactions, add their amounts to a prior wallet, or rewrite an existing wallet.
- The import is *local and manual*, not a standing Notion integration or source-of-truth cutover. The original public app repository must never contain owner campaign exports.

## Engineering verification and release boundary

Pure rules validation, duplicate prevention, resource preservation and provisional-ledger checks are in `NotionTransferTest`. Follow the project's prescribed full rules/app/lint/build and native journeys before release. Importing on the owner's Galaxy S26 requires a newer **original-owner-signed APK** and an explicit file selection/confirmation. No signed APK, physical-device commit, or accepted restoration is inferred from a source change alone.
