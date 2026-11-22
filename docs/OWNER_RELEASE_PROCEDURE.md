# Repeatable private owner release

The permanent package is `com.yazsras.astravar`. Its only accepted owner
certificate SHA-256 is
`4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
The original key stays in the established owner-private signing environment;
GitHub Actions uses disposable verification keys and never receives that key.

## Current recovered baseline

The previous owner build is `4.0.0-unification2`, code 53, source
`718a9e24bf856cf7e8dee32673ab75270bdd90c4`. The inspected APK SHA-256 is
`f5150b768be0ccbfe2d784cb6fc459503ecec5acefba37ee092cc18f5516a9af`.
Its package, single owner certificate, v2/v3 signatures and 16 KiB alignment
were verified directly from the original APK.

`scripts/owner_release_sign.py` pins this baseline digest and requires the
candidate version code to exceed 53. For each subsequent owner release, verify
the previous APK bytes before advancing the baseline and minimum code.

## Release inputs and gates

1. Finish Tier A and both comprehensive release gates on the exact clean source
   commit. Retain raw results, known skips, native upgrade evidence and failure
   logs. A successful fast PR gate alone does not authorize a stable release.
2. Retrieve the successful Tier B orchestrator run and its completed successful
   **full** and **native** job metadata from GitHub. Both jobs must belong to the
   same source commit and successful run attempt, with distinct job IDs.
   Download the original native artifact archive and verify its checksum against
   GitHub artifact metadata. Record the artifact ID and the run that owns it.
3. Identify the native artifact's exact release APK entry and source marker.
   `validation/product-apk.sha256` must identify that APK. Its embedded Git
   revision must equal the successful runs and clean checkout.
4. Use JDK17 and Android SDK build-tools35.0.0. The source's pinned inspector
   checks exact package/version, branding, transport, signature and alignment.
5. Recover the existing PKCS12 and local signing configuration in a private
   directory outside the checkout. Set directory permissions0700 and secret
   files0600. Do not print their contents or send them to CI. The helper reads
   them only after the public preflight succeeds and verifies the full owner
   certificate before signing. Passwords enter only a scoped subprocess
   environment, never command-line arguments.

Create a small operator-verified JSON receipt from the retrieved metadata. This
receipt is evidence supplied to the helper, **not an independent GitHub query**;
never populate it from assumed statuses. Use schema2 for reusable Tier B jobs:

```json
{
  "schema_version": 2,
  "source_commit": "FULL_40_CHARACTER_COMMIT",
  "artifact_sha256": "VERIFIED_64_CHARACTER_ARCHIVE_DIGEST",
  "artifact": {"run_id": 123, "artifact_id": 456, "name": "android-verification", "sha256": "SAME_ARCHIVE_DIGEST"},
  "run": {"run_id": 123, "run_attempt": 1, "head_sha": "SAME_FULL_COMMIT", "status": "completed", "conclusion": "success", "html_url": "https://github.com/Brenmack15/Astravar-Overlay/actions/runs/123"},
  "jobs": [
    {"kind": "full", "job_id": 501, "run_id": 123, "run_attempt": 1, "head_sha": "SAME_FULL_COMMIT", "name": "Full regression gate / verify", "status": "completed", "conclusion": "success", "html_url": "https://github.com/Brenmack15/Astravar-Overlay/actions/runs/123/job/501"},
    {"kind": "native", "job_id": 502, "run_id": 123, "run_attempt": 1, "head_sha": "SAME_FULL_COMMIT", "name": "Historical upgrade and native acceptance / verify", "status": "completed", "conclusion": "success", "html_url": "https://github.com/Brenmack15/Astravar-Overlay/actions/runs/123/job/502"}
  ]
}
```

Schema1 remains supported for the two independently dispatched legacy full and
native workflows: it uses a `runs` array with distinct run IDs, and each row has
`kind`, `run_id`, `head_sha`, `status`, `conclusion`, and `html_url`; its artifact
has `run_id`, `artifact_id`, and `sha256`. Schema1 cannot treat the same run as two
gates. Schema2 requires the exact named full/native jobs and the native artifact,
so a successful fast-only orchestrator run does not qualify.

These placeholders deliberately cannot pass. Run
`python3 scripts/owner_release_sign.py --help` for the required paths. First use
`--preflight-only` with a new output directory beneath an existing private parent
outside the repository. Then invoke the same pinned inputs with
`--authorize-signing`, `--keystore`, `--private-config`, and another new output
directory. The configuration uses the established `ASTRAVAR_KEY_ALIAS`,
`ASTRAVAR_STORE_PASSWORD`, and `ASTRAVAR_KEY_PASSWORD` fields. No configuration is
rewritten. Existing outputs are refused rather than replaced.

## What completion proves

The helper re-signs the exact tested optimized APK without rebuilding. Every ZIP
payload entry, including all `META-INF` provenance, service and license entries,
must remain byte-for-byte identical. It rejects duplicate or unsafe ZIP entries,
stale source, changed evidence, a CI artifact from a different run, an untested
APK in the same archive, a different owner certificate, and version rollback.
The current CI format has v2 signing without v1; v1 input fails explicitly.
The output requires owner v2/v3 signatures and passes package/alignment inspection.

`COMPLETE`, the clearly named owner APK, `SIGNING_REPORT.json` and `SHA256SUMS`
appear only after the full checks pass. Failure leaves a sanitized phase/error
record and no final APK. No upload, installation, key generation, merge, or public
release is performed by this script. `--authorize-signing` selects the operation;
it does not override user authorization or release gates.

Before delivery, independently inspect the final checksum and report. Deliver
the APK privately with its source commit, version, full certificate and actual
test limitations. Install as an ordinary update; never uninstall, clear storage,
use a downgrade flag, or replace the owner identity to work around a failure.
Claim physical Galaxy S26/Android17 installation only after real device evidence.

## Automated checks

`python3 -m unittest discover -s scripts/tests -p 'test_owner_release_sign.py' -v`
uses isolated fictional files and mocked signer/SDK operations. It covers
provenance, stale evidence, certificate policy, secret redaction, exact payload
preservation, exclusive outputs, version bounds and failure cleanup. Actual Git
fixtures verify dirty/stale checkout detection. These tests are not live signing
or device acceptance evidence.
