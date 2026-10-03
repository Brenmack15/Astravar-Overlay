# Astravar 1.2.0 handoff

This release adds configurable base direct weapon damage dice (1d10–4d10, default 2d10) to the existing automatic combat flow. Historical shot snapshots retain their actual dice; campaign state, audit journal, resources, backups and owner signing identity are preserved.

Use the accompanying signed APK for phone installation. It is package `com.yazsras.astravar`, version 1.2.0, version code 4, and is intended to install over the existing owner-signed version. The `.debug` package and controlled target APKs are for testing and are not the delivered release.

Authoritative automated results, provenance, hashes and remaining coverage boundaries are in [TEST_REPORT.md](TEST_REPORT.md). [ANDROID_INSTALL.md](ANDROID_INSTALL.md) describes the phone flow; [BUILD_AND_SIGNING.md](BUILD_AND_SIGNING.md) documents bounded builds and private signing. No owner key is present in this repository or in public CI artifacts.

The implementation is maintained on a feature branch from the corrected overlay-test branch. Every new build/test command has an explicit time limit and captures failure evidence. The v1.2.0 tag and release are created only after the signed APK has been verified.

Remaining device acceptance is physical Galaxy S26/One UI, D&D Beyond, device-specific battery behavior and any tablet/multi-window configurations. The controlled Android emulator is not evidence that those physical checks passed. It is safe to use the separate demo for acceptance; do not populate or replace a real campaign with demo data.
