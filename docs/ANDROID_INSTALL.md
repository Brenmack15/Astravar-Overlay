# Phone-only installation — signed 1.2.0 release

This is the signed 1.2.0 release. It keeps the existing package ID and owner signing identity and is intended to update an existing installation. The accompanying test report distinguishes automated emulator coverage from outstanding physical-phone checks. Start with the explicitly labeled demo for device checks; its resources do not describe your real campaign.

1. Download the delivered **signed release APK** to your phone and tap it in the download notification or Files app. Follow Android's installer prompt. If Android asks, grant installation permission only to the app opening this APK, then return to the installer. No desktop, Android Studio, terminal, ADB, root, or Play developer account is needed.
2. Open **Astravar Overlay**. Confirm the starter profile or import your own backup. Record only resources you actually have, confirm attunement, and install a recorded completed core. Demo mode is clearly labeled and optional.
3. Tap **Floating controls** on Combat. If overlay permission is missing, follow the app's permission flow. In Android settings, select Astravar Overlay if a list is shown and grant display-over-other-apps permission, then return.
4. Tap **Floating controls** again if needed. Approve notifications if requested, then start the session again. The ongoing Astravar notification provides Show and Stop.
5. Open D&D Beyond. Drag the crystal to an edge and tap it to expand. Choose Normal, Adv or Dis, select a core/payload, and tap **Roll attack**. Astravar saves the attack and damage, then offers **Roll again**. Target AC is optional. Discharge/Shatter require confirmation; creature saving throws remain your input. Nothing reads or changes D&D Beyond.
6. Use **Collapse**, **Hide**, or **Stop session** as needed. Hiding does not fire or purge anything. Resume a hidden panel with the notification's Show action. A locked screen hides the panel; it does not advance the game clock.

To change Astravar's base direct weapon damage, open **More → Rules, upgrades & provisional choices → Weapon → Upgrades** and select 1d10, 2d10, 3d10, or 4d10. The current profile starts at 2d10. The floating overlay follows this shared profile value automatically; selector, spell payload, overcharge, and Discharge/Shatter blast dice keep their existing rules.

Some apps and protected screens intentionally suppress overlays. The app respects this. If Samsung blocks installation with a device-specific security message, use the exact message to identify the setting; this guide does not assume your One UI version or instruct you to disable protection globally.

If the overlay is repeatedly stopped, first reopen Astravar and restart the session. Only then inspect app-specific battery settings if necessary. There is no boot autostart. Revoked permission is recovered by the same explicit permission button, not a request loop.

## Short Galaxy S26 check

- Read Help diagnostics and record Android/API/firmware and app version.
- Confirm the overlay appears above D&D Beyond and outside-panel taps still work.
- Drag to each edge; rotate; expand/collapse; open a keyboard; try your normal font size and any tablet/multi-window layout.
- Confirm notification Stop removes it. Deny/revoke overlay permission and verify safe recovery.
- Roll a demo shot, force-stop/reopen Astravar, and confirm identical raw dice in Combat and History. Check that a stored spell is spent exactly once.
- Export a backup, preview/reimport it, and check inventory/history.

Emulator results do not establish these physical Samsung/D&D Beyond checks. Keep a backup before updates; install the next release over the existing app with the same signing identity. Do not uninstall a real campaign merely to fix an update error.
