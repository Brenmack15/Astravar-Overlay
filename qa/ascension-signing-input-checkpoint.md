# Astravar 4.1 code 59 signing-input checkpoint

The optimized CI input for package `com.yazsras.astravar` version 4.1.0 (59) is bound to source `9ded49ee06b22f389c4d5f5553f975cb02fa762f`. The required verification concluded success on attempt 1. The test record includes 23 ordered Android 16 stages and two Android 17 observations, the 58-to-59 package upgrade and force-stopped cloud reconnect. These observations used isolated emulators. [Official release verification](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/45962475021)

| Verified input | Artifact or member | Bytes | SHA-256 | Actions expiry, UTC |
| --- | --- | ---: | --- | --- |
| Original native QA ZIP | [15185466555](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/45962475021/artifacts/15185466555) | 195398797 | `f34d8562f6c13fe775bd4ae79ed79f8d837227cfa07091c39332ab622accf309` | 2026-12-11 09:12:50 |
| Verified 90-day wrapper | [15185767457](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/45972156517/artifacts/15185767457) | 195403250 | `2a1716ebc5b3f6d9d087a211a42e951b6db73f28bbb1e01588f5fce446567829` | 2027-02-25 09:15:14 |
| Compact verification receipts | [15184464592](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/45972156517/artifacts/15184464592) | 2338 | `c5dbfda6fef54062e77bdfa2df043a9a6bbbaf17d834b49b3d8a4e7e5a8ad2ff` | 2027-02-25 09:15:14 |

The independent verifier re-downloaded the five-member wrapper and checked every manifest entry. Its `android-verification-original.zip` member matches the official ZIP byte-for-byte. The private release procedure accepts that inner ZIP and the matching `ci-evidence.json`. [Preservation and independent readback](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/45972156517/job/140415008237)

The CI APK SHA-256 is `a45cb267e6a5e906aad7653f2120c29a2970dd6701d6086c93de7f7969afaef7`. Its ordered ZIP payload manifest covers 212 entries and has SHA-256 `f98acc4e936853a5b5aaf822592a506cdc798a716fd80a1aa24cec2efd87bd1a`. SDK inspection recorded minimum 26, target 36, and verified 16 KiB alignment. [Measured manifest and native scope](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/45972579101/job/140416345032)

This checkpoint records QA input only. The optimized APK carries the disposable CI signature. Owner signing, independent verification of the resulting APK, upgrade of the original-owner-signed test installation, and any physical handset observations require their own evidence. All retained content in these Actions artifacts belongs to isolated QA.
