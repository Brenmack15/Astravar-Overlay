# Astravar 1.6.0 handoff

Started from clean `13a3afd` (1.5.1/code 8) on `fix/bounded-roll-history`; changes are on `feat/weapon-focused-interface`. No reset, rebuild or rules rollback.

Shared Kotlin engine, Compose app, native View overlay, Room v3 and DataStore architecture are retained. New app navigation is Weapon, Cores & Payloads, History, More. Native overlay standard sizes are 174×52 dp collapsed and 300×364 dp expanded; result/secondary panels scroll within 420 dp. All sizes are scaled and clamped to the available display bounds.

New dice always strip legacy AC input and ordinary outcomes remain UNCONFIRMED; natural 1/20 behavior and costs at firing remain. The legacy RollOptions AC field and TargetSave defenses exist solely for historical compatibility. Validation still accepts untouched old outcome snapshots. DataStore removes only the obsolete armor_class key. Campaign.encounter/turn remain serialized to preserve saved budgets, but now represent optional weapon action enforcement; no general encounter UI or round counter is exposed. Explicit next-weapon-turn confirmation does not advance game time.

The 1.5.1 newest-20 SQL retention, pre-observation streaming cleanup, compact roll snapshots and backup bounds remain unchanged. No Room schema increment or campaign reset. The detailed previous handoff is retained in HANDOFF_1.5.1.md.

Package `com.yazsras.astravar`, versionName 1.6.0, versionCode 9. Owner signing material stays outside git. See TEST_REPORT.md for actual validation and remaining physical-device coverage.
