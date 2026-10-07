# Astravar 1.5.0 handoff

This update starts from clean v1.4.0 commit c4149d1 on a new feature branch, preserving both independent count sliders and existing architecture. Current Primary Force is Nd12+INT+calibration; Discharge is 3N d12 and Shatter is 7× Discharge = 21N d12. Each active element adds Md8 once to either explosion, with independent typed save halving. Neutral adds none. Spell shots still replace free selector dice. Cantrip imbuing is absent.

`baseDamageDiceCount` and `elementalSelectorDiceCount` remain independent persisted integers from 1 through 10. `PrimaryForceRules.DIE_SIDES` is the single current Force-size authority; profile `forceRulesVersion` distinguishes current d12 rules from immutable legacy d10 shot snapshots. Old level-based damage exists only in pre-v1.3 history migration. Room remains v2 and DataStore remains unchanged.

The signed APK uses `com.yazsras.astravar`, version 1.5.0, versionCode 7, with the existing owner key. See [TEST_REPORT.md](TEST_REPORT.md) for actual executed validation, hashes and coverage limits, [ANDROID_INSTALL.md](ANDROID_INSTALL.md) for phone instructions, and [BUILD_AND_SIGNING.md](BUILD_AND_SIGNING.md) for bounded builds and private signing. No signing secret is tracked in the repo.

Physical Galaxy S26/D&D Beyond and real upgrade coverage must be distinguished from host tests. Do not uninstall or replace a real campaign with demo data to perform a check.
