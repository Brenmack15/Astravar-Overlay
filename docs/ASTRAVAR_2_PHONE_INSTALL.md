# Astravar 2.0 UI Rebuild: Samsung installation

Expected build metadata: **2.0.1-ui-rebuild**, code **21**, package **`com.yazsras.astravar.v2uidebug`**, label **Astravar 2.0 UI Rebuild**. Confirm the delivered APK's actual metadata in its accompanying checks before installation. Use the accompanying delivery report for actual build/CI results; host rendering does not verify physical Galaxy S26 behavior.

## Keep the working applications

The UI Rebuild installs as a separate app. It does not update or overwrite:

| Existing app | Package |
| --- | --- |
| Owner-signed release | `com.yazsras.astravar` |
| Inventory Beta | `com.yazsras.astravar.debug` |
| Original 2.0 Preview | `com.yazsras.astravar.v2debug` |

The installed Preview's debug signing key is not available in this workspace. The independent UI Rebuild package avoids an uncertain update/signing path. **Do not uninstall any working app to install or troubleshoot this build.** Existing data and the Preview's fictional pending-command queue remain in their original Android sandboxes.

## Install from the verified artifact

1. Export campaign and weapon backups from any source app whose data you want to transfer. Keep the original files and source app.
2. In GitHub, open a successful **Bounded Android verification** run for **`feat/astravar-2.0-ui-rebuild`**. Prefer the branch's push run when matching the delivered source commit; a PR run may check out GitHub's temporary merge commit. Download **`Astravar-2.0-UI-Rebuild-apk`**. Extract its ZIP in Samsung My Files and open **`Astravar-2.0-UI-Rebuild-debug.apk`**. A source-code ZIP is not the application.
3. If requested, allow installation from the browser or My Files for this installation, then restore that permission to its previous setting. The correct new package ends in **`.v2uidebug`**. Stop if Android offers to replace the release, Beta or original Preview. A future update to the UI Rebuild itself requires the same signing certificate.
4. Launch **Astravar 2.0 UI Rebuild**. Campaign records start empty; no private campaign or Notion data is imported automatically. Review and confirm the weapon profile before combat, or choose the clearly labeled demo for disposable testing.

## Transfer supported records explicitly

Use **Config → Campaign backup** for inventory/campaign JSON and **Config → Weapon backup** for combat/weapon JSON. The formats are separate.

For campaign data, export a current backup in the UI Rebuild, choose the source campaign file, review the preview, then select **Confirm restore**. Only validated missing records are added. Existing unequal IDs are conflicts and block restore; they are not overwritten or automatically reconciled. Inventory Beta schema-1 exports lack later campaign domains. Schema-2 exports can contain later records only when the source app actually exported them.

Weapon restore follows its existing separate validation, backup and explicit replacement flow. It replaces weapon state rather than merging it. Review the preview carefully and retain the original export.

The fictional sync outbox is in neither backup. Keep the old Preview installed to retain its pending commands and receipts. The UI Rebuild has its own local sandbox cache/outbox; reading the same public service does not transfer old local pending operations. No real campaign data may be sent through that service.

## Check on the Galaxy S26

- Test Armor, Combat, Campaign and Config, including large text, keyboard entry, modal Save/Cancel, Back dismissal and app recreation.
- Under **Config → Overlay & appearance**, allow **Appear on top** when Android requests it, then start the overlay session. Test dragging, collapsing, roll mode, automatic dice, results and closing over a second app before live play.
- Use disposable data to verify create/edit, inventory identity, export/restore and any material-consumption confirmation. Do not infer live resources from illustrations or reference entries.
- Under **Config → Fictional sync**, Read checks the existing public sandbox. The review button sends only its fixed fictional template after confirmation. Retry recovers pending command receipts with original IDs. Read errors, conflicts and pending status remain visible.

The owner reported bidirectional fictional sandbox communication on the **previous Preview** installed on the S26. That does not verify this new UI Rebuild APK. Host/Robolectric rendering and CI are distinct from physical installation, keyboard, D&D Beyond and system-overlay acceptance. Use the current delivery report for the actual coverage.

## Later UI Rebuild updates

Use one APK build/signing channel consistently. Local builds and fresh Actions runners can produce different debug certificates. If Android rejects an update, preserve exports and the installed app; a signature error is not authorization to uninstall it. A data-preserving update requires the matching signing key. The owner release signing identity is unchanged.

Expanded armor mechanics, missing approved assets and production/private synchronization remain bounded by the [UI rebuild report](ASTRAVAR_UI_REBUILD.md). The preserved engine remains authoritative.
