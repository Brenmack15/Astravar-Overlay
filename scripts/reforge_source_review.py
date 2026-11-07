"""Reconstruct reviewed pre-Reforge source for the historical integrity gates.

Old asset/schema/signing pins stay intact. Each authorized code/UI change must
have a pinned new digest and exact reversible replacements proving that its
predecessor is the verified 2.4.0 baseline. Unreviewed edits fail closed.
"""
import hashlib
import json
from pathlib import Path


BASELINE = "852bbd48af9b6c70be5ce44d5f898d1224ca0e7c"


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
