# Private original-owner upgrade verification

This is an implemented alternative to the failed software-only local emulator.
It is **disabled and unexecuted**. It changes no app, rules, database, signing
configuration or frozen native assertions. A successful synthetic transport test
is not an owner-key installation result.

The separate receiver workflow uses a fresh accelerated GitHub-hosted Android 36
emulator. The original owner key and its credentials never leave the private
signing environment. Two already-signed APKs are encrypted for a short-lived
receiver transport key. That RSA key is not an APK signing identity. Public Git
contains only authenticated ciphertext chunks and public transport metadata.
Plaintext APKs, copied synthetic databases, raw diagnostics and the ephemeral
private transport key stay in RUNNER_TEMP and are removed after the job.

## Reviewed activation boundary

The application source candidate is a configurable exact commit. The helper
branch began at2798; this does not make2798 a release candidate if its native gate
fails. Before activation root must fill every value in
`scripts/owner-upgrade-config.json`, independently verify the exact successful
full/native runs, and only then set `enabled` to true. Both run IDs, attempts,
workflow paths, non-skipped verification jobs and critical steps are checked live
by the receiver. The native artifact ID, archive digest, exact APK hashes/sizes,
full signing certificates and embedded Git provenance are mandatory.

Actual historical owner49 embeds
`7bb03b5e15d639e94947a491e76f37b1b23a1af5`. The unchanged UI seed baseline is
`3543e83bfca5e589f1bbedb09964431ce94a2319`. Their app tree is
`0da5fc7f0e3ed99862ed709c6723c304dab533f3` and rules tree is
`f6e4d084f0c6b349883eefee2f2aa5669a7d24ac`; the receiver independently checks both
immutable Git trees. It does not mislabel the historical APK's source.

Publish reviewed helper changes to
`checkpoint/astravar-genesis-owner-upgrade-envelope-20261112`. Ordinary checkpoint
pushes do not execute the receiver job. The intentionally exact final subject is
`ci: receive reviewed owner upgrade envelope`; dispatch is also supported once
GitHub makes this workflow dispatchable. Source publishing/activation is owned by
root, not performed by these helpers. `contents:read` and `actions:read` are the
only job permissions. No owner secrets are requested or declared.

## Actual sequence

1. Receiver verifies source gates and the pinned source artifact, then creates a
   fresh RSA-3072 transport key. The public `recipient.json` artifact is named
   `owner-upgrade-recipient-RUN_ID-ATTEMPT` and expires for encryption after30min.
   The private key remains only on that job's runner.
2. Through the authenticated GitHub connector, root freshly obtains the receiver
   **current run**, **exact attempt**, **exact-attempt jobs**, **artifact metadata**
   and the **original recipient ZIP bytes**. These evidence files must come from
   those API responses, not the transfer branch, recipient descriptor, a third
   party or stale screenshots. Root independently verifies the archive digest
   returned by GitHub and the exact reviewed receiver commit. No GitHub token
   needs to be copied into the local sender environment.
3. The local sender validates that bundle, recipient preparation/upload success,
   exact run/attempt/repository/commit/config bindings, public artifact ID/digest
   and lifetime, both owner APK hashes/certificates/package versions and embedded
   provenance. It encrypts only baseline49 and owner50. The receiver independently
   obtains the observer/test pair from the pinned successful native artifact.
4. Root uploads only the generated `manifest.json` and `chunks/NNNNN.bin` to a
   fresh **ciphertext-only** tree on `owner-upgrade-transfer/RUN_ID-ATTEMPT`.
   Default chunk size is512KiB, bounded256KiB–1MiB; total plaintext is at most
   256MiB. Root may use `create_blob` base64 and compare each returned Git blob
   SHA against `sha1(b"blob "+decimal_length+b"\0"+bytes)` before creating the
   tree/commit/ref. Do not commit the application tree, recipient ZIP, local API
   evidence, plaintext APKs or private transport key to that branch. Do not
   overwrite an existing transfer branch; reruns require their new attempt.
5. Receiver resolves that branch once, pins its commit/tree, accepts only fixed
   regular files and retrieves blobs by exact Git object IDs. It verifies chunk
   order/size/digests, RSA-OAEP-SHA256 and both AES-256-GCM tags before writing any
   plaintext APK. AAD binds role, expected APK hash/source/package/cert and full
   recipient/run/attempt/config identity. Every encryption uses a fresh AES key
   and distinct96-bit nonces. Manifest data cannot redefine approved inputs.
6. Receiver rechecks gates, downloads and hashes the exact native artifact,
   validates observer/test identities, and compares **every non-signature ZIP
   entry** of owner50 with the source artifact's release APK. The embedded AGP
   `META-INF/version-control-info.textproto` source remains part of this payload.
7. Fresh KVM emulator installs original owner49 and observer APKs, executes the
   unchanged `seedCode49`, then normal `adb install -r` owner50, unchanged
   `verifyAfterUpgrade` and `canonicalWriteSurvivesRestart`. Existing independent
   SQLite/schema9 and package helpers verify stopped-store byte preservation,
   stable Android UID/firstInstallTime, additive cutover, canonical HP write and
   source retention. No uninstall, clear-data, data injection or assertion
   weakening occurs. A fresh private emulator is required; no device is reused.
8. Only explicit filtered synthetic results, public hash/provenance metadata and
   four canonical metadata-free synthetic PNGs are eligible for upload. The
   separate publication guard rejects unknown paths, raw logs, APKs, keys,
   database files, unknown nested metadata and disguised PNG payloads. The
   workflow has no broad directory upload, cache or plaintext artifact path.

## Sender invocation (root supplies all concrete paths and pins)

Install `scripts/owner-upgrade-requirements.txt` in an isolated CPython3.12 Linux
x86_64 environment with `pip --only-binary=:all: --require-hashes`. The dependency
is cryptography50.0.0, with pinned official PyPI wheel hashes and pinned cffi /
pycparser dependencies. No application build is needed for the sender.

```sh
python3 scripts/owner_upgrade_transfer.py send \
  --config scripts/owner-upgrade-config.json \
  --run-id RECEIVER_RUN --attempt RECEIVER_ATTEMPT \
  --receiver-commit EXACT_REVIEWED_RECEIVER_SHA \
  --recipient-archive /private/recipient.zip \
  --recipient-artifact-info /private/artifact-api.json \
  --recipient-run-info /private/current-run-api.json \
  --recipient-attempt-info /private/exact-attempt-api.json \
  --recipient-jobs-info /private/exact-attempt-jobs-api.json \
  --recipient-artifact-id RECIPIENT_ARTIFACT_ID \
  --recipient-artifact-sha256 RECIPIENT_ARCHIVE_SHA256 \
  --baseline /private/original-owner-code49.apk \
  --candidate /private/verified-owner-code50.apk \
  --sdk /isolated/android-sdk --output /private/fresh-ciphertext-directory
```

The sender never publishes anything. The receiver never signs anything. If an
attempt expires, fails, is cancelled or is rerun, preserve its public evidence and
use a new reviewed run/attempt, fresh recipient and new encryption. Do not reuse
the old ciphertext or bypass a failed source gate. Ciphertext is not a backup of
the private owner release; retain original APKs in their authorized private store.

## Verification and remaining limits

Synthetic tests cover encryption/decryption, multiple chunks, digest/tag/key
tampering, wrong run/attempt/commit/config, expiry, duplicate JSON, roles, traversal,
symlinks, publication leakage, source job/step skipping, artifact identity and
attempt timestamps, owner/source/package pins, ZIP payload comparison, and strict
PNG structure. Run:

```sh
python3 -m unittest discover -s scripts/tests -p 'test_owner_upgrade*.py' -v
bash -n scripts/ci-owner-upgrade.sh
```

No actual owner APK was encrypted or installed during implementation. No workflow
was launched. No transport key was used as an app signer. Accelerated CI success,
owner-certificate compatibility, actual owner49→50 emulator installation and
physical Samsung installation remain distinct claims. Hosting plaintext briefly
inside the GitHub runner is necessary for Android installation; this mechanism
keeps it out of public Git/artifacts and never sends the owner signing key.

Cryptographic construction references: [RSA-OAEP](https://cryptography.io/en/stable/hazmat/primitives/asymmetric/rsa/)
and [AES-GCM associated data and nonce requirements](https://cryptography.io/en/stable/hazmat/primitives/aead/).
