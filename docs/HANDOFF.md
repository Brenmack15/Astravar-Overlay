# Astravar 1.1.0 handoff

The candidate adds automatic dice and the simplified main/overlay combat flow. The existing campaign model, audit journal, explicit migration, resource accounting, backups, settings and owner signing identity are preserved.

Use the accompanying signed APK for phone installation. It is package `com.yazsras.astravar`, version 1.1.0, version code 3, and is intended to install over the existing owner-signed version. Export a current campaign backup first. The `.debug` package and controlled target APKs are for testing and are not the delivered candidate.

Authoritative automated results, provenance, hashes and remaining coverage boundaries are in [TEST_REPORT.md](TEST_REPORT.md). [ANDROID_INSTALL.md](ANDROID_INSTALL.md) describes the phone flow; [BUILD_AND_SIGNING.md](BUILD_AND_SIGNING.md) documents bounded builds and private signing. No owner key is present in this repository or in public CI artifacts.

The recovery work used the existing `feat/astravar-android` branch. Main was not merged and no public Release was created. Runtime rechecks can reuse a completed workflow's APK only after validating that its product/build inputs are unchanged. Every new build/test command has an explicit time limit and captures failure evidence.

Remaining device acceptance is physical Galaxy S26/One UI, D&D Beyond, device-specific battery behavior and any tablet/multi-window configurations. The controlled Android emulator is not evidence that those physical checks passed. It is safe to use the separate demo for acceptance; do not populate or replace a real campaign with demo data.
