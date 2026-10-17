# Astravar v1.4.0 — independent elemental damage

- Elemental Selector Damage now has a discrete 1d8–10d8 slider alongside Base Weapon Damage in More → Rules, upgrades & provisional choices → Weapon → Upgrades.
- The canonical selector value is 2d8. Existing Force selections remain unchanged; the repository's canonical Force profile is 2d10+5.
- Ordinary Elemental Shot rolls the configured independent d8s, preserving each face and damage type. Criticals double eligible selector dice. Confirmed Dual Resonance applies the configured count to each selected basic element.
- Both sliders update the combined damage preview while dragging and save independently on release. The full app and floating overlay read the same durable profile; selection survives restart and is included in backup/restore.
- Old profiles missing the selector setting migrate to 2d8. Old pending and historical shots retain their original 1d8 selector expressions, raw faces and results. Campaign state and Force upgrades remain intact.
- Neutral Force, Spell-Imbued Shot, Direct Impact, Discharge and Shatter receive no free selector dice. Payloads, overcharge, element unlocks and level/Force-driven emergency scaling are unchanged. Cantrip imbuing is not implemented.
- Same package and existing signing identity; version code increases from 5 to 6. See TEST_REPORT.md for measured build results and device coverage limitations.
