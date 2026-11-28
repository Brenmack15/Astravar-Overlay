# Offline package for an unrouted provider generation

The exporter creates private files for one dedicated D1 database and one private R2 bucket. It has no network code, provider credentials, grant writer, deployment command or promotion operation. The recovery ZIP and the independently supplied review-authority pin remain separate custody items. Keep the export and every readback out of Git and CI. CI uses invented records and artwork.

## Prepare the candidate

Verify the source ZIP against its independently recorded SHA-256. For a reviewed package, set `ASTRAVAR_REVIEW_AUTHORITY_SHA256` from the separate trusted authority record. Allocate a generation UUID. Obtain the prospective database UUID and private bucket name from the provisioning authority. Use an unused output path on private storage.

```sh
python3 tools/ascension_migrate_generation.py export "$REVIEWED_ZIP" "$PRIVATE_CANDIDATE" \
  --purpose isolated --package-sha "$APPROVED_PACKAGE_SHA256" \
  --generation "$GENERATION_UUID" --database "$D1_UUID" --bucket "$PRIVATE_R2_BUCKET"
```

Secure the emitted manifest SHA-256 separately. A production candidate uses `--purpose production-candidate` and requires zero source review holds. An isolated export cannot serve as a production approval. The kit contains the staged continuity, ordered portable SQL rows and exact private object bytes. Source history and review decisions remain in the separately preserved canonical ZIP.

## Rehearse without an account

The focused CI workflow exercises real local workerd D1 and R2. It restores a synthetic generation, checks both object copies and reopens the stores through independent bindings. It also verifies refusal of wrong binding identity, occupied D1 and occupied R2.

```sh
npm install --prefix "$PRIVATE_DEPS" --no-audit --no-fund --no-save miniflare@5.20261123.1-beta
node scripts/verify-ascension-provider-recovery.cjs "$PRIVATE_DEPS/node_modules/miniflare"
```

## Conditions for a separately approved hosted run

1. Independently confirm owner, tenant, generation, exact package and manifest fingerprints, active writer epoch and revision, and a recoverable backup of the active generation. Verify the private D1/R2 identifiers against the provisioning control plane. Require exclusive administrator access to the new generation, no routing, no public R2 access and no standing campaign grants. Protect the existing active generation throughout the operation.
2. Provision the repository's pinned schema on the dedicated D1. Verify every application table, index and trigger against the manifest. Reject unknown tables and any existing application rows. List the entire private bucket through all result pages and require it is empty. Verify each object size and SHA-256, the SQL batch digest and the recorded final staged authority before applying anything.
3. With a separately authorized provider-side binding adapter, place each exact object at its recorded key using its SHA-256. Prepare and bind every statement from `d1-batch.json` and execute them in their original order in **one transactional D1 batch**. The continuity starts staged with its reviewed epoch and revision. Never rewrite existing rows. If any object upload or the transaction fails, leave that generation unrouted, quarantine it and start a fresh generation.
4. Reopen independent read credentials. Re-export the continuity's actual ordered SQL table rows and complete object bytes into a canonical recovery ZIP. Count every application table, verify a single scoped continuity, zero live grants and credentials, no foreign data, and list every object key. Independently confirm provider identifiers, nonrouting, private access and schema. Repeat the checks while exclusive access is held. The local verifier expects the census shape demonstrated in `scripts/verify-ascension-generation.cjs`.
5. Run the offline comparison. It checks the freshly captured tenant, authority, epoch, revision, all portable table digests and object inventory against the pinned generation.

```sh
python3 tools/ascension_migrate_generation.py verify-readback "$PRIVATE_CANDIDATE" \
  "$INDEPENDENT_RECOVERY_ZIP" "$INDEPENDENT_BINDING_CENSUS" --manifest-sha "$GENERATION_MANIFEST_SHA256"
```

The local harness has no hosted binding-administration transport. A hosted execution requires independently provisioned access, identity observations, an approved private runner and the provider-side exclusivity controls above. Preserve the binding census, independently read package and signed-off comparison with the original package. Staging verification grants no authority to route traffic, enroll a device, change grants or switch the live writer.
