# Native UI evidence

## Actual installed release APK

[Owner-signed code42 running offline](owner-apk-api35-offline.png) comes from the
actual optimized APK after a PackageManager update over original owner code41
on an isolated software AOSP35 emulator. MainActivity had input focus, no error
dialog was present, and the saved fictional profile was visible. Airplane mode
was enabled, Wi-Fi disabled and ConnectivityService reported no active default
network. This is separate from the host-rendered captures below and is not a
physical Samsung or performance measurement. A preceding System UI ANR was
attributed to the software guest and remains documented in verification.

[Actual release equipment preview](owner-apk-equipment-preview.png) shows the
separate fictional equipment fixture: STR8→21, CON14→16, AC10→20 and maximum
HP66→75. Current HP40→49 preserves26 lost HP. Native confirmation, unequip back
to40/66 and re-equip to49/75 were actually exercised; no rest or cumulative
bonus was introduced. This is an explicitly authored test item, not a change
to the owner's live campaign records.

After force-stop and cold relaunch, the same installed APK reopened the
[equipped sheet](owner-apk-equipped-after-restart.png) with HP49/75, AC20,
STR21 and CON16. The [reopened native history](owner-apk-saved-roll-after-restart.png)
retained the genuine STR face19+derived5=24 and its immutable saved receipt.
Independent disk readback retained permanent STR8/CON14 and26 damage. The game
clock and turn stayed0; all original stores/settings still matched. These are
actual offline/restart workflow results, not claimed phone acceptance.

## Native working previews

These images come from the final focused debug run's actual Android drawing
with Compose under Robolectric API35. They are working application views, not
generated mockups. They do not
establish physical Samsung behavior, GPU performance or screen-reader acceptance.
All application fixtures are fictional. Original owner screenshots, video and
private campaign exports are excluded from this public repository.

## Before and after

| Workflow | Before: exact 2.4.0/code41 baseline | Reforge: product source `30f0b9d` | Observed improvement |
|---|---|---|---|
| Ability editing | [Baseline editor](before-ability-editor.png) | [Compact editor](reforge-compact-ability-editor.png) | Single score input, visible calculated modifier, optional preserved recorded bonuses/sources; no mandatory attribution essay. |
| NPC reading | [Baseline profile](before-npc-profile.png) | [Structured profile](reforge-npc-profile.png) | Overview, known information, open threads and milestones have distinct sections; technical details and source markup are outside the primary story view. |

The baseline captures use the same synthetic source fixtures from isolated
baseline commit `852bbd48af9b6c70be5ce44d5f898d1224ca0e7c` and the retained
[baseline capture harness](ReforgeBeforeCaptureTest.kt). The compact ability
editor is the preserved source-record editor; automatic equipment mechanics live
in the new Character workspace. A copied legacy modifier is not inferred from
item prose.

## New native gameplay views

- [Calculated character sheet](reforge-native-character-sheet.png): configured
  character, source boundaries, actual ability projections and common rolls.
- [Equipment change preview](reforge-native-equipment-preview.png): reversible
  Strength/Constitution changes and retained outstanding damage. In this fixture
  maximum HP66→75 carries current HP40→49, preserving 26 lost HP; it is not a rest.
- [Explicit dice result](reforge-native-dice-result.png): actual initiated random
  faces and breakdown. This is a fictional test roll, not a campaign event.

The final product/test source is `30f0b9d`; its exact input digest is recorded with the captures. Both selected native suites passed 56 cases without failures or skips.
[Capture hashes and dimensions](native-captures.json) identify the exact public
PNG bytes. The broader native suite checks small layouts, large font scaling,
complete navigation labels and the corrected text contrast. Those checks do not
substitute for TalkBack or Android17 acceptance on a physical device.

The two D&D Beyond JPGs are permissible public Presto example-sheet research
evidence; their interaction observations and limits belong in
[the research report](../beyond-research.md). They are not Astravar runtime views.
Historical overlay captures elsewhere in test output are archived regression
fixtures and are not evidence of a registered Reforge overlay service.
