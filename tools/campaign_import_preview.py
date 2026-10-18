"""Offline player-known source reconciliation. Produces a review, never writes an app.

Input is a deliberately explicit source envelope, not an arbitrary Notion scrape.
Every record retains its original identity and source. A review is not an import
authorization or a declaration that Astravar is the authoritative campaign store.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path

FORMAT = "astravar-source-review-v1"
KINDS = {"character", "companion", "item", "ability", "checkpoint", "invention", "currency", "relationship", "event"}
EVIDENCE = {"Confirmed", "Proposed", "Disputed", "Unverified"}


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def validate(envelope):
    if not isinstance(envelope, dict) or envelope.get("format") != FORMAT:
        raise ValueError("An explicit versioned player-known source envelope is required")
    if not isinstance(envelope.get("campaignId"), str) or not envelope["campaignId"].strip():
        raise ValueError("Campaign scope is required")
    rows = envelope.get("records")
    if not isinstance(rows, list) or len(rows) > 50_000:
        raise ValueError("Records must be a bounded list")
    for row in rows:
        if not isinstance(row, dict) or row.get("kind") not in KINDS:
            raise ValueError("Unknown record kind")
        if any(not isinstance(row.get(key), str) or not row[key].strip() for key in ("id", "sourceId", "sourceUrl")):
            raise ValueError("Exact original IDs and source references are required")
        if row.get("visibility") != "player-known":
            raise ValueError("Only authorized player-known records can be previewed")
        if row.get("evidence") not in EVIDENCE:
            raise ValueError("An explicit evidence status is required")
        if "revision" not in row or (row["revision"] is not None and (type(row["revision"]) is not int or row["revision"] < 1)):
            raise ValueError("A positive source revision or explicit unknown is required")
        fields = row.get("fields")
        if not isinstance(fields, dict):
            raise ValueError("Typed source fields are required")
        if "quantity" in fields and fields["quantity"] is not None and (type(fields["quantity"]) is not int or fields["quantity"] < 0):
            raise ValueError("Quantity must be a nonnegative integer or explicit unknown")
        if "coins" in fields and fields["coins"] is not None and (type(fields["coins"]) is not int or fields["coins"] < 0):
            raise ValueError("Currency must use nonnegative integer units or explicit unknown")
        if not isinstance(row.get("references", []), list):
            raise ValueError("References must be a list of exact kind/id pairs")
        for ref in row.get("references", []):
            if not isinstance(ref, dict) or ref.get("kind") not in KINDS or not isinstance(ref.get("id"), str):
                raise ValueError("Malformed original reference")
    return rows


def preview(incoming, current):
    new, old = validate(incoming), validate(current)
    if incoming["campaignId"] != current["campaignId"]:
        raise ValueError("Campaign scope mismatch")
    local, duplicates = {}, []
    for row in old:
        key = (row["kind"], row["id"])
        if key in local:
            raise ValueError("Current snapshot contains duplicate identities")
        local[key] = row
    grouped = {}
    for row in new:
        key = (row["kind"], row["id"])
        if row in grouped.get(key, []):
            duplicates.append({"kind": key[0], "id": key[1], "reason": "Identical source row repeated"})
        else:
            grouped.setdefault(key, []).append(row)
    known = set(local) | set(grouped)
    entries = []
    for key, versions in sorted(grouped.items()):
        existing = local.get(key)
        reasons = []
        unresolved = sorted({(r["kind"], r["id"]) for row in versions for r in row.get("references", []) if (r["kind"], r["id"]) not in known})
        if unresolved:
            reasons.append("Unresolved original references")
        if len(versions) > 1:
            reasons.append("Multiple nonidentical sources share this exact identity")
        if any(row["evidence"] != "Confirmed" for row in versions):
            reasons.append("Proposal, theory or disputed source remains separate from confirmed history")
        if any(row["revision"] is None for row in versions) or (existing and existing["revision"] is None):
            reasons.append("Ordered source revision is unknown; verify the source snapshot and acceptance before import")
        if existing and existing["revision"] is not None and any(row["revision"] is not None and row["revision"] < existing["revision"] for row in versions):
            reasons.append("Source revision is older than the retained record")
        changed = sorted({field for row in versions for field in set(row["fields"]) | set(existing["fields"] if existing else {})
                          if existing is None or field not in row["fields"] or field not in existing["fields"] or row["fields"].get(field) != existing["fields"].get(field)})
        if existing and changed:
            reasons.append("Existing values differ; explicit scoped source-priority adjudication required")
        for row in versions:
            correction = row.get("approvedCorrection")
            if correction:
                # A source's claim of approval never authorizes a database write.
                reasons.append("Claimed later correction must be verified against its original acceptance and field scope")
        status = "CONFLICT" if reasons else "UNCHANGED" if existing else "PROPOSED_ADDITION"
        entries.append({"kind": key[0], "id": key[1], "status": status, "changedFields": changed,
                        "reasons": list(dict.fromkeys(reasons)), "unresolvedReferences": [{"kind": k, "id": i} for k, i in unresolved],
                        "retained": existing, "sources": versions})
    return {"format": "astravar-migration-preview-v1", "campaignId": incoming["campaignId"],
            "inputFingerprint": fingerprint(incoming), "currentFingerprint": fingerprint(current),
            "canCommit": False, "cutoverApproved": False,
            "requiredGates": ["Verified authenticated device and ChatGPT connection", "Backup and restore acceptance", "Source conflict adjudication", "Separate owner import and cutover approval"],
            "entries": entries, "duplicateRows": duplicates,
            "summary": {state: sum(e["status"] == state for e in entries) for state in ("CONFLICT", "UNCHANGED", "PROPOSED_ADDITION")}}


def private_output(path):
    target = Path(path).resolve()
    inside = subprocess.run(["git", "-C", str(target.parent), "rev-parse", "--is-inside-work-tree"],
                            capture_output=True, text=True, check=False)
    if inside.returncode == 0 and inside.stdout.strip() == "true":
        raise ValueError("Private migration previews must be saved outside a Git checkout")
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("incoming", type=Path)
    parser.add_argument("current", type=Path)
    parser.add_argument("--output", type=private_output, required=True)
    args = parser.parse_args()
    result = preview(json.loads(args.incoming.read_text()), json.loads(args.current.read_text()))
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump(result, stream, indent=2, ensure_ascii=False)
        stream.write("\n")
    print("Review prepared locally. No app, backend or Notion records changed. Commit remains disabled.")


if __name__ == "__main__":
    main()
