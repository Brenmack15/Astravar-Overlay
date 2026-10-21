"""Independently inspect an offline campaign package without importing it.

Input/output campaign bytes must stay outside a Git checkout. This validator
does not authenticate a Notion author or authorize campaign cutover.
"""
from __future__ import annotations

import argparse
import base64
import collections
import hashlib
import json
from pathlib import Path
import subprocess
from urllib.parse import urlparse


def sha256(value: str | bytes) -> str:
    return hashlib.sha256(value.encode("utf-8") if isinstance(value, str) else value).hexdigest()


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON member: {key}")
        result[key] = value
    return result


def loads(text):
    return json.loads(text, object_pairs_hook=strict_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Nonfinite JSON number")))


def inspect_archive(raw: bytes):
    if not 0 < len(raw) <= 32 * 1024 * 1024:
        raise ValueError("Archive exceeds the supported byte limit")
    envelope = loads(raw.decode("utf-8", errors="strict"))
    if set(envelope) != {"format", "schema", "payloadSha256", "payload"}:
        raise ValueError("Unsupported archive envelope members")
    if envelope["format"] != "astravar-campaign-archive" or type(envelope["schema"]) is not int or envelope["schema"] != 1:
        raise ValueError("Unsupported archive format/schema")
    payload_text = envelope["payload"]
    if not isinstance(payload_text, str) or sha256(payload_text) != envelope["payloadSha256"]:
        raise ValueError("Payload SHA-256 mismatch")
    payload = loads(payload_text)
    for name in ("campaignId", "title", "sourceRootUrl", "sourceRootRevision"):
        if not isinstance(payload.get(name), str) or not payload[name].strip():
            raise ValueError(f"Missing source metadata: {name}")
    records = payload["records"]
    if not isinstance(records, list) or not 0 < len(records) <= 50000:
        raise ValueError("Invalid source record count")
    ids = [row["externalId"] for row in records]
    if len(set(ids)) != len(ids) or any(not isinstance(key, str) or not key.strip() for key in ids):
        raise ValueError("Duplicate/empty source identity")
    known = set(ids)
    edges = set()
    unresolved_edges = set()
    for row in records:
        if row["visibility"] != "player":
            raise ValueError("A non-player source cannot enter this archive")
        if sha256(row["body"]) != row["contentSha256"]:
            raise ValueError(f"Body hash mismatch: {row['externalId']}")
        if not isinstance(loads(row["propertiesJson"]), dict):
            raise ValueError("Original properties must be a JSON object")
        source = urlparse(row["sourceUrl"])
        if not ((source.scheme == "https" and source.hostname) or
                (source.scheme == "collection" and (source.netloc or source.path))):
            raise ValueError("Source identity must be HTTPS or a retained collection URI")
        relations = row.get("relationIds", [])
        if len(relations) != len(set(relations)):
            raise ValueError("Duplicate relation")
        for other in relations:
            if other not in known:
                unresolved_edges.add((row["externalId"], other))
            edges.add((row["externalId"], other))
    attachments = payload.get("attachments", [])
    embedded = 0
    for attachment in attachments:
        content = attachment.get("base64")
        if content is not None:
            data = base64.b64decode(content, validate=True)
            if sha256(data) != attachment.get("sha256"):
                raise ValueError("Attachment SHA-256 mismatch")
            embedded += 1
    mapped = payload.get("mapped") or {}
    typed = mapped.get("records", {})
    counts = {key: len(value) for key, value in typed.items() if isinstance(value, list)}
    counts.update(items=len(mapped.get("items", [])), events=len(mapped.get("events", [])))
    return {
        "format": "astravar-archive-independent-verification-v1",
        "archiveSha256": sha256(raw), "payloadSha256": envelope["payloadSha256"],
        "archiveBytes": len(raw), "campaignId": payload["campaignId"],
        "sourceRootUrl": payload["sourceRootUrl"], "sourceRootRevision": payload["sourceRootRevision"],
        "referenceRecords": len(records), "relationships": len(edges),
        "unresolvedRelationships": sorted(unresolved_edges),
        "relationshipTargetsComplete": not unresolved_edges,
        "categories": dict(sorted(collections.Counter(row["category"] for row in records).items())),
        "attachments": len(attachments), "embeddedAttachments": embedded,
        "mappedCounts": counts, "issues": payload.get("issues", []),
        "sourceIdentitiesAndHashes": sorted((row["externalId"], row["contentSha256"]) for row in records),
        "importPerformed": False, "cutoverApproved": False,
        "note": "Integrity validates retained bytes, not source authenticity or completeness of inaccessible sources.",
    }


def private_path(path):
    path = Path(path).resolve()
    result = subprocess.run(["git", "-C", str(path.parent), "rev-parse", "--is-inside-work-tree"],
                            capture_output=True, text=True, check=False)
    if result.returncode == 0 and result.stdout.strip() == "true":
        raise ValueError("Real campaign input and reports must remain outside a Git checkout")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=private_path)
    parser.add_argument("--output", type=private_path, required=True)
    args = parser.parse_args()
    report = inspect_archive(args.archive.read_bytes())
    with args.output.open("x", encoding="utf-8") as file:
        json.dump(report, file, ensure_ascii=False, indent=2)
        file.write("\n")
    args.output.chmod(0o600)
    print(f"Validated {report['referenceRecords']} reference identities and {report['relationships']} relations. No import performed.")


if __name__ == "__main__":
    main()
