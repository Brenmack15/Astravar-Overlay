# Reforge release readiness

Status: NOT READY FOR OWNER INSTALLATION; implementation and validation in progress.

The verified source baseline is 2.4.0/code 41. Reforge reserves
`3.0.0-rc1`/code 42, preserving the permanent release package
`com.yazsras.astravar`. The existing debug variant remains a separate QA package.
Neither version metadata nor a successful debug build proves a safe in-place
owner update.

## Android toolchain compatibility

The repository retains compile/target SDK 36, minimum SDK 26, AGP 8.13.2,
Gradle 8.13 and Kotlin 2.2.21. Google's current [AGP 8.13 compatibility
table](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
specifies support through API 36.1 with Gradle 8.13, JDK 17 and build-tools
35.0.0. Android 17's [SDK setup
documentation](https://developer.android.com/about/versions/17/setup-sdk)
identifies API 37. Reforge does not silently opt into its new target behaviors.
Google's [build configuration guide](https://developer.android.com/build)
explains that higher Android versions run lower-target apps using compatibility
behavior. That supports retaining this tested toolchain; it is not evidence of
a successful Android 17 or Samsung installation. Sources inspected 2026-11-07.

The full historical owner certificate fingerprint pinned by existing release
inspection code is:

`4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`

The original private owner-signing recovery material has been recovered outside
the repository. Its private/public key pair and full certificate fingerprint
were checked independently; the fingerprint matches the historical pin above.
No replacement signing identity or CI signing secret was created. Final release
assembly must still compare the actual APK certificate to this fingerprint and
the preceding owner APK before distribution.

The recovered **actual** 2.4.0/code 41 APK was inspected with Android's
`apksigner`, `aapt` and `zipalign`. It is the permanent package, non-debuggable,
and its full certificate matches the pin. Its SHA-256 is
`9b7006a4f2dfbd56a4d9f20e55a17a882fa7468c926a411f4c1b2a39e5baceaa`.
Packaged supplied artwork, retired-provider checks and 16 KB library/alignment
checks passed. This verifies the predecessor artifact; it does not prove what
is currently installed on the physical phone.

## Remaining release gates

1. Pass integrated rules, Room migration, native UI, offline persistence, parser,
   synchronization-regression and overlay-retirement verification.
2. Complete debug/release lint and native APK assembly; inspect the packaged
   identity, permissions, components, supplied artwork and 16 KB alignment.
3. Build with the independently verified original owner signer and inspect the
   final APK's full certificate. No signing material enters source control or CI
   logs.
4. Demonstrate database-preserving package-manager update in a safe test
   environment and disclose limits of physical-phone evidence.
5. Obtain the separate authorization required for publishing/installing a
   production release. Draft source review does not perform either action.

Existing app data must never be cleared or uninstalled to bypass signature or
migration checks. The separately signed debug QA package is not an in-place
owner update.
