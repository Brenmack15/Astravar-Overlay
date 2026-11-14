"""Reconstruct reviewed pre-Reforge source for the historical integrity gates.

Old asset/schema/signing pins stay intact. Each authorized code/UI change must
have a pinned new digest and exact reversible replacements proving that its
predecessor is the verified 2.4.0 baseline. Unreviewed edits fail closed.
"""
import hashlib
import json
from pathlib import Path


BASELINE = "852bbd48af9b6c70be5ce44d5f898d1224ca0e7c"
GENESIS_CONDITION_PATH = "rules/src/main/kotlin/com/yazsras/astravar/rules/Model.kt"
GENESIS_CONDITION_BASELINE = "a86ab0fa87ce47a4f99550dcfa8fe01ed0e22928"
GENESIS_CONDITION_PRIOR_HASH = "0c17dde66699844d0a87c32506e9a61eb48f1f4de98b10e3f3355e06c990618d"
# This pins the exact six reviewed edits, independently of the manifest digest.
# An altered default/damage formula cannot become an authorized rule waiver by
# recomputing reviewed_sha256 or replacing a manifest's before/after strings.
GENESIS_CONDITION_CHANGE_HASH = "d7ea3c63c69ae817144ae8aa77878d310675aa71a77e05ab2058bfefb6aeef94"


def restore_reviewed_source(data: bytes, review: dict) -> bytes:
    if hashlib.sha256(data).hexdigest() != review["reviewed_sha256"]:
        raise ValueError("Source differs from reviewed Reforge changes")
    text = data.decode("utf-8")
    for replacement in reversed(review["exact_replacements"]):
        after = replacement["after"]
        if not after or text.count(after) != 1:
            raise ValueError("Reviewed Reforge replacement is ambiguous or changed")
        text = text.replace(after, replacement["before"], 1)
    restored = text.encode("utf-8")
    if hashlib.sha256(restored).hexdigest() != review["previous_pinned_sha256"]:
        raise ValueError("Reforge reverse proof does not reproduce the verified baseline")
    return restored


def baseline_bytes(root: Path, relative: str) -> bytes:
    data = (root / relative).read_bytes()
    condition_path = root / "scripts/genesis_condition_source_review.json"
    if relative == GENESIS_CONDITION_PATH and condition_path.exists():
        condition = json.loads(condition_path.read_text())
        expected_keys = {"verified_source_baseline", "source_path", "authorization", "previous_pinned_sha256",
                         "reviewed_sha256", "exact_replacements"}
        change_hash = hashlib.sha256(json.dumps(condition.get("exact_replacements"), sort_keys=True,
                                               separators=(",", ":")).encode()).hexdigest()
        if (set(condition) != expected_keys or condition["source_path"] != GENESIS_CONDITION_PATH or
                condition["verified_source_baseline"] != GENESIS_CONDITION_BASELINE or
                condition["previous_pinned_sha256"] != GENESIS_CONDITION_PRIOR_HASH or
                change_hash != GENESIS_CONDITION_CHANGE_HASH):
            raise ValueError("Genesis condition review differs from the exact approved accuracy-only change")
        data = restore_reviewed_source(data, condition)
    path = root / "scripts/reforge_source_review.json"
    if not path.exists():
        return data
    review = json.loads(path.read_text())
    if review["verified_source_baseline"] != BASELINE:
        raise ValueError("Unexpected Reforge source baseline")
    entry = review["files"].get(relative)
    if entry is None:
        return data
    # Never grant source-review exceptions to historical schemas, artwork,
    # bundled content, signing inputs or this verifier's own implementation.
    allowed = relative == "app/src/main/AndroidManifest.xml" or (
        relative.startswith("app/src/main/java/com/yazsras/astravar/") and relative.endswith(".kt"))
    if not allowed:
        raise ValueError(f"Reforge review cannot waive this immutable boundary: {relative}")
    return restore_reviewed_source(data, entry)
