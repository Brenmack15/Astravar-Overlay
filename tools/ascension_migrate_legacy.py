"""Inventory native export families by stable identity before any cloud conversion."""
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from zipfile import ZipFile, is_zipfile

from verify_campaign_archive import inspect_archive, loads

MAX_BYTES = 64 * 1024 * 1024
HASH = re.compile(r"[0-9a-f]{64}")
IDENTITY = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}")
CAMPAIGN = {"items": "id", "events": "commandId"}
RECORDS = dict.fromkeys((
    "projects", "stages", "requirements", "entities", "facts", "milestones", "links",
    "companionResources", "itemProperties", "audit", "consumptions", "characterProfiles",
    "characterAbilities", "wallets", "checkpoints", "externalReferences"), "id")
RECORDS.update(companionStates="companionId", itemDetails="itemId", receipts="commandId",
               crafts="commandId", partySheets="profileId", placements="itemId", characterAbilities=("profileId", "key"))
ARCHIVE = {"references": "id", "relations": "id", "receipts": "packageHash",
           "backups": "packageHash", "sources": "packageHash"}
PLAYABLE = {"characters": "profileId", "changes": "commandId", "rolls": "id", "drafts": "profileId"}
AUTHORITY = {"campaigns": "id", "actors": "actorId", "items": "itemId", "receipts": "commandId",
             "encounters": "id", "encounterParticipants": ("encounterId", "actorId")}
COINS = {"cp": "copper", "sp": "silver", "ep": "electrum", "gp": "gold", "pp": "platinum"}
REVIEW_SCOPE = {"records.entities", "records.facts", "records.projects"}
WORLD_KINDS = {"Person": "person", "Organization": "organization", "Faction": "faction", "Creature": "being", "Location": "place"}


def verify(condition, message):
    if not condition:
        raise ValueError(message)


def encode(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"),
                      sort_keys=True).encode("utf-8")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_hash(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_limited(path, maximum=MAX_BYTES):
    path = Path(path)
    verify(path.is_file() and not path.is_symlink(), "A regular local source file is required")
    with path.open("rb") as stream:
        body = stream.read(maximum + 1)
    verify(0 < len(body) <= maximum, "Source exceeds the supported size or is empty")
    return body


def source_families(value):
    """Return every format-declared row type, including omitted older optional collections."""
    fmt = value.get("format")
    version = value.get("schema")
    verify(type(version) is int, "Native input requires an integer schema")
    families = []

    def group(prefix, section, keys, extra=()):
        verify(isinstance(section, dict), "Invalid native domain")
        verify(not set(section).difference(keys, extra), "Unrecognized native domain or collection")
        for field, key in keys.items():
            rows = section.get(field, [])
            verify(isinstance(rows, list), "Native collection is not an array")
            seen = set()
            entries = []
            for row in rows:
                verify(isinstance(row, dict), "Invalid native record")
                ids = key if isinstance(key, tuple) else (key,)
                identity = tuple(row.get(k) for k in ids)
                verify(all(isinstance(x, str) and x for x in identity), "Missing native record identity")
                verify(identity not in seen, "Repeated native identity within a source family")
                seen.add(identity)
                entries.append((identity, row))
            families.append((prefix + "." + field, field in section, entries))

    if fmt == "astravar-connected-campaign":
        verify(version == 1 and set(value) == {"format", "schema", "exportedAt", "campaign", "playable", "authority"},
               "Incomplete or unsupported native connected envelope")
        verify(value["campaign"].get("format") == "astravar-campaign-items" and
               value["playable"].get("format") == "astravar-reforge", "Invalid nested native formats")
        families.extend(source_families(value["campaign"]))
        families.extend(source_families(value["playable"]))
        group("authority", value["authority"], AUTHORITY)
    elif fmt == "astravar-campaign-items":
        verify(version in range(1, 6) and {"items", "events"} <= set(value), "Unsupported ledger schema or missing rows")
        group("campaign", value, CAMPAIGN, ("format", "schema", "exportedAt", "records", "archive"))
        group("records", value.get("records", {}), RECORDS)
        group("archive", value.get("archive", {}), ARCHIVE)
        rec = value.get("records", {})
        verify(version >= 2 or not any(rec.values()), "Unsupported schema 1 gameplay collections")
        verify(version >= 3 or not any(rec.get(k) for k in
               ("partySheets", "placements", "wallets", "checkpoints", "externalReferences")), "Schema predates party records")
        verify(version >= 4 or not any(value.get("archive", {}).values()), "Schema predates source archives")
        verify(version >= 5 or not any(w.get(k) is not None for w in rec.get("wallets", []) for k in
               ("electrum", "platinum")), "Schema predates EP/PP")
    elif fmt == "astravar-reforge":
        verify(version in (1, 2) and {"characters", "changes", "rolls"} <= set(value), "Unsupported playable export")
        group("playable", value, PLAYABLE, ("format", "schema", "exportedAt"))
    else:
        raise ValueError("Unrecognized native export format")
    verify(type(value.get("exportedAt")) is int, "Missing native snapshot recording time")
    return families


def convert_row(family, row, owner, continuity, shared_ids, reviewed):
    """Only convert rows whose full gameplay meaning the native source evidence resolves."""
    if family == "authority.campaigns":
        if row["id"] != continuity:
            return None, "campaign administration record belongs to another continuity"
        allowed = {"id", "name", "notes", "revision", "archived", "createdAt", "updatedAt", "commandId"}
        if set(row) - allowed or not isinstance(row.get("commandId"), str) or not row["commandId"].strip():
            return None, "campaign metadata requires stable native command provenance"
        title, revision, archived = row.get("name"), row.get("revision"), row.get("archived")
        verify(isinstance(title, str) and 0 < len(title.strip()) <= 180 and type(revision) is int and revision >= 1,
               "Invalid native campaign identity, title or revision")
        verify(type(archived) is bool and (row.get("notes") is None or isinstance(row["notes"], str) and len(row["notes"]) <= 4000),
               "Unknown campaign archive status or invalid notes")
        for key in ("createdAt", "updatedAt"):
            verify(row.get(key) is None or type(row[key]) is int and row[key] >= 0, "Invalid native campaign timestamp")
        data = {"notes": row.get("notes"), "source": "Genesis campaign record", "sourceCommandId": row["commandId"],
                "sourceArchived": archived, "createdAt": row.get("createdAt"),
                "sourceUpdatedAt": row.get("updatedAt"), "editedAt": None}
        return {"owner_id": owner, "continuity_id": continuity, "id": continuity, "kind": "continuity_profile", "actor_id": None,
                "title": title, "data_json": encode(data).decode(), "life": "archived" if archived else "active", "revision": revision}, None
    if family in REVIEW_SCOPE and not reviewed:
        return None, "record needs explicit player-known continuity membership review"
    if family not in REVIEW_SCOPE and family not in ("records.wallets", "records.checkpoints"):
        return None, "requires a reviewed mapper for this native family"
    if family not in REVIEW_SCOPE and row.get("campaignId") != continuity:
        return None, "continuity membership is foreign or unresolved"
    identity = row["id"]
    verify(IDENTITY.fullmatch(identity), "Unsupported canonical identity")
    revision = row.get("revision")
    verify(type(revision) is int and revision >= 1, "Expected an explicit positive native revision")
    source = row.get("source")
    if not isinstance(source, str) or not source.strip() or len(source) > 1000:
        return None, "missing source provenance"
    if family == "records.wallets":
        allowed = {"id", "holderProfileId", "name", "revision", "source", "updatedAt", "campaignId", *COINS.values()}
        if set(row) - allowed or row.get("holderProfileId") is not None or identity not in shared_ids:
            return None, "wallet requires reviewed holder or explicit shared-wallet designation"
        coins = {key: row.get(column) for key, column in COINS.items()}
        verify(all(n is None or type(n) is int and 0 <= n <= 1_000_000_000 for n in coins.values()),
               "Invalid or unsupported denomination balance")
        kind, title = "wallet", row.get("name")
        data = {"holderId": None, "shared": True, "coins": coins, "source": source}
    elif family == "records.checkpoints":
        allowed = {"id", "title", "scene", "lastConfirmedAction", "unresolvedChoice", "objective",
                   "gameTime", "recordReferences", "unknowns", "revision", "source", "updatedAt", "campaignId"}
        if set(row) - allowed or any(row.get(k) for k in ("lastConfirmedAction", "objective", "recordReferences", "unknowns")):
            return None, "checkpoint requires reviewed event, objective, uncertainty or reference mapping"
        kind, title = "checkpoint", row.get("title")
        if not isinstance(row.get("scene"), str) or not row["scene"].strip() or type(row.get("updatedAt")) is not int or row["updatedAt"] <= 0:
            return None, "checkpoint lacks explicit scene or recording time"
        data = {"scene": row["scene"], "worldTime": row.get("gameTime") or None,
                "unresolved": row.get("unresolvedChoice") or None, "pendingRollPrompt": None,
                "source": source, "recordedAt": row["updatedAt"], "commandId": None}
    elif family == "records.entities":
        allowed = {"id", "name", "kind", "notes", "aliases", "relationship", "imageBase64", "revision", "source", "updatedAt"}
        if set(row) - allowed or row.get("kind") not in WORLD_KINDS or any(row.get(k) for k in ("aliases", "relationship", "imageBase64")):
            return None, "world record requires reviewed type, alias, relationship or media mapping"
        description = row.get("notes") or None
        verify(description is None or isinstance(description, str) and len(description) <= 4000, "Invalid world description")
        kind, title = "world_entity", row.get("name")
        data = {"category": WORLD_KINDS[row["kind"]], "knownDescription": description, "partyStorage": False, "source": source}
    elif family == "records.facts":
        allowed = {"id", "entityId", "detail", "category", "evidenceStatus", "revision", "source", "updatedAt"}
        evidence = {"Confirmed": "confirmed", "Disputed": "disputed"}.get(row.get("evidenceStatus"))
        if set(row) - allowed or evidence is None or not row.get("entityId"):
            return None, "known fact requires reviewed subject, visibility and evidence status"
        kind, title = "known_fact", row.get("category")
        detail, recorded = row.get("detail"), row.get("updatedAt")
        verify(isinstance(detail, str) and detail.strip() and len(detail) <= 4000, "Invalid known fact detail")
        data = {"detail": detail, "evidence": evidence, "learnedAt": None, "originalWorldTime": None,
                "recordedAt": recorded if type(recorded) is int and recorded > 0 else None, "source": source}
    else:
        allowed = {"id", "title", "description", "status", "revision", "source", "updatedAt"}
        if set(row) - allowed or row.get("status") != "Proposed":
            return None, "invention progress needs stage, cost, roll or output reconciliation"
        kind, title = "invention_project", row.get("title")
        intent, recorded = row.get("description"), row.get("updatedAt")
        if not isinstance(intent, str) or not intent.strip() or len(intent) > 4000:
            return None, "project proposal requires source-backed intent"
        data = {"intent": intent, "status": "proposed", "requirements": [],
                "recordedAt": recorded if type(recorded) is int and recorded > 0 else None, "source": source}
    verify(isinstance(title, str) and 0 < len(title.strip()) <= 180, "Unsupported canonical title")
    return {"owner_id": owner, "continuity_id": continuity, "id": identity, "kind": kind, "actor_id": None,
            "title": title, "data_json": encode(data).decode(), "life": "active", "revision": revision}, None


def legacy_review(path, owner, continuity, shared_ids=(), known_records=()):
    """Report a disposition for every native row. Never invoke gameplay during conversion."""
    return review_bytes(read_limited(path), owner, continuity, shared_ids, known_records)


def inspect_notion_transfer(value, raw):
    fields = {"format", "transferId", "capturedDate", "character", "inventory", "currency",
              "provisionalGpEquivalent", "walletVerified", "reviewedSpellbook"}
    verify(not set(value) - fields and isinstance(value.get("character"), dict) and
           isinstance(value.get("transferId"), str) and value["transferId"], "Invalid Notion transfer envelope")
    rows, counts = [], {}
    def include(family, records, identity):
        seen = set()
        verify(isinstance(records, list), "Invalid source transfer collection")
        for row in records:
            verify(isinstance(row, dict), "Invalid source transfer row")
            key = tuple(row.get(k) for k in identity)
            verify(all(isinstance(k, str) and k for k in key) and key not in seen, "Duplicate or missing source transfer identity")
            rows.append({"family": family, "identity": list(key), "sha256": sha(encode(row))})
            seen.add(key)
        counts[family] = len(records)
    include("inventory", value.get("inventory"), ("sourceId",))
    include("currency", value.get("currency"), ("eventId",))
    book = value.get("reviewedSpellbook")
    if book is not None:
        verify(isinstance(book, dict) and not set(book) - {"classVersion", "castingClassLevel", "spells", "castingLevelSourceSpellId"},
               "Unknown spellbook source fields")
        include("spells", book.get("spells"), ("sourceId", "spellKey"))
    return {"format": value["format"], "sha256": sha(raw), "transferId": value["transferId"],
            "characterSha256": sha(encode(value["character"])), "counts": counts, "records": rows,
            "blockers": ["Native reviewed transfer requires an exact existing actor, item identity and source validation. Historical currency evidence cannot establish a current wallet."]}


def review_bytes(raw, owner, continuity, shared_ids=(), known_records=()):
    value = loads(raw.decode("utf-8"))
    verify(isinstance(value, dict), "Expected a native document")
    fmt = value.get("format")
    if fmt in ("astravar-notion-character-transfer-v1", "astravar-notion-character-transfer-v2"):
        return inspect_notion_transfer(value, raw), [], raw
    if fmt == "astravar-campaign-archive":
        result = inspect_archive(raw)
        return {"format": fmt, "schema": value["schema"], "sha256": sha(raw), "archive": result,
                "blockers": ["Reference archive requires reviewed source-to-entity and attachment mapping"]}, [], raw
    families = source_families(value)
    known_shared = {row["id"] for key, _, rows in families if key == "records.wallets"
                    for _, row in rows if row.get("holderProfileId") is None and row.get("campaignId") == continuity}
    verify(set(shared_ids) <= known_shared, "Unknown or ineligible shared-wallet identity")
    mapped = []
    sources = []
    counts = {}
    blockers = Counter()
    ids = set()
    digest = sha(raw)
    eligible_reviews = set()
    links = []
    for family, declared, rows in families:
        counts[family] = {"declared": declared, "source": len(rows), "mapped": 0, "held": 0}
        for identity, row in rows:
            record = {"family": family, "identity": list(identity), "sha256": sha(encode(row))}
            if type(row.get("revision")) is int:
                record["sourceRevision"] = row["revision"]
            if type(row.get("updatedAt")) is int:
                record["sourceUpdatedAt"] = row["updatedAt"]
            if family in REVIEW_SCOPE:
                # Membership and visibility approval applies to this exact row, source and destination.
                token = sha(encode([owner, continuity, digest, family, list(identity), record["sha256"]]))
                record["scopeReviewSha256"] = token
                eligible_reviews.add(token)
            entity, reason = convert_row(family, row, owner, continuity, shared_ids,
                                         record.get("scopeReviewSha256") in known_records)
            if entity and family == "records.facts":
                links.append({"owner_id": owner, "continuity_id": continuity,
                              "from_id": entity["id"], "relation": "known_about", "to_id": row["entityId"]})
            if reason:
                counts[family]["held"] += 1
                blockers[reason] += 1
                record["hold"] = reason
            else:
                verify(entity["id"] not in ids, "Conflicting cross-family target identity")
                ids.add(entity["id"])
                mapped.append(entity)
                counts[family]["mapped"] += 1
                record["target"] = entity["id"]
            sources.append(record)
    verify(set(known_records) <= eligible_reviews and len(set(known_records)) == len(known_records),
           "Stale, duplicate or foreign player-known scope review")
    kinds = {r["id"]: r["kind"] for r in mapped}
    for link in links:
        if kinds.get(link["to_id"]) != "world_entity":
            blockers["known fact subject is absent or unresolved in this source"] += 1
    return {"format": fmt, "schema": value["schema"], "sha256": digest, "owner": owner,
            "continuity": continuity, "sharedWallets": sorted(shared_ids), "knownRecords": sorted(known_records),
            "links": sorted(links, key=lambda r: (r["from_id"], r["relation"], r["to_id"])), "families": counts, "records": sources,
            "blockers": dict(sorted(blockers.items()))}, mapped, raw


def compare_legacy(before, after, owner, continuity):
    """Reconcile identifiers, revisions, and revision evidence without choosing a winner or replaying an event."""
    left = legacy_review(before, owner, continuity)[0]
    right = legacy_review(after, owner, continuity)[0]
    verify("records" in left and "records" in right,
           "This source format requires its dedicated archive comparison")
    def index(report):
        return {(r["family"], tuple(r["identity"])): r for r in report["records"]}
    a, b = index(left), index(right)
    counts = Counter()
    differences = []
    for key in sorted(a.keys() | b.keys()):
        old, new = a.get(key), b.get(key)
        if old and new and old["sha256"] == new["sha256"]:
            counts["identical"] += 1
            continue
        status = "changed" if old and new else "onlyEarlier" if old else "onlyLater"
        if old and new and old.get("sourceRevision") is not None and old.get("sourceRevision") == new.get("sourceRevision"):
            status = "conflictingSameRevision"
        counts[status] += 1
        def evidence(row):
            if row is None:
                return None
            return {"sha256":row["sha256"], "revision":row.get("sourceRevision"), "sourceUpdatedAt":row.get("sourceUpdatedAt")}
        differences.append({"family":key[0], "identity":list(key[1]), "disposition":status,
                            "earlier":evidence(old), "later":evidence(new)})
    return {"owner":owner, "continuity":continuity, "earlierSource":left["sha256"], "laterSource":right["sha256"],
            "counts":dict(sorted(counts.items())), "differences":differences,
            "sourceCoverage":{"earlier":left.get("families",left.get("counts")), "later":right.get("families",right.get("counts"))}}


def weapon_review(path):
    """Verify checksums and the exact indexed entry set of the separate native history transport."""
    verify(Path(path).is_file() and not Path(path).is_symlink() and is_zipfile(path), "Expected a native history ZIP")
    verify(Path(path).stat().st_size <= 1 << 30, "Weapon archive exceeds its input limit")
    with ZipFile(path) as z:
        listing = z.infolist()
        names = {i.filename for i in listing}
        verify(len(listing) <= 100_003 and len(listing) == len(names), "Duplicate or excessive archive entries")
        verify(sum(i.file_size for i in listing) <= 1 << 30, "Expanded weapon archive exceeds its limit")
        for key, limit in (("manifest.json", 16_384), ("backup.json", 8 << 20), ("archives.ndjson", 64 << 20)):
            verify(key in names and z.getinfo(key).file_size <= limit, "Missing or oversized weapon section")
        header = loads(z.read("manifest.json"))
        verify(header.get("format") == "astravar-complete-history" and header.get("version") == 1,
               "Unsupported weapon archive")
        index = z.read("archives.ndjson")
        verify(sha(z.read("backup.json")) == header["workingSha256"] and sha(index) == header["indexSha256"],
               "Weapon header checksum mismatch")
        remaining = names - {"manifest.json", "backup.json", "archives.ndjson"}
        total = 0
        count = 0
        for line in index.splitlines():
            verify(len(line) <= 8192, "Weapon index entry exceeds its limit")
            row = loads(line)
            key = "archives/" + sha(row["id"].encode("utf-8")) + ".bin"
            verify(key in remaining and row["entry"] == key and
                   0 <= row["bytes"] == z.getinfo(key).file_size <= 64 << 20, "Invalid weapon history index")
            with z.open(key) as obj:
                verify(hashlib.file_digest(obj, "sha256").hexdigest() == row["sha256"], "Weapon history digest mismatch")
            remaining.remove(key)
            count += 1
            total += row["bytes"]
        verify(not remaining and (count, total) == (header["archiveCount"], header["archiveBytes"]),
               "Unexpected weapon entries or counts")
    return {"format": header["format"], "version": 1, "sha256": file_hash(path),
            "archiveCount": count, "archiveBytes": total,
            "blockers": ["Raw weapon history requires its separate native restore and reviewed mechanical mapping"]}
