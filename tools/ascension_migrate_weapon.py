"""Validate the separate native weapon-history transport and index its reviewed source evidence."""
import base64
import hashlib
import io
import uuid
from zipfile import ZipFile

from ascension_migrate_legacy import HASH, encode, loads, sha, verify

FORMAT = "astravar-complete-history"
ORIGINAL = "astravar-history-original-v1:"
PRESERVED = "astravar-preserved-v1:"
RECEIPT = "astravar-history-restore-receipt-v1:"
HEADER = {"format", "version", "workingSha256", "indexSha256", "archiveCount", "archiveBytes",
          "originalFileCount", "originalBytes"}
CHUNK = 65_536


def integer(value, maximum):
    return type(value) is int and 0 <= value <= maximum


def digest(value):
    return isinstance(value, str) and HASH.fullmatch(value)


def entry(key):
    return "archives/" + sha(key.encode("utf-8")) + ".bin"


def canonical_object(body):
    value = loads(body)
    verify(isinstance(value, dict) and encode(value) == body, "Invalid native history manifest encoding")
    return value


def checked_archive(source):
    with ZipFile(source) as archive:
        listing = archive.infolist()
        all_names = {r.filename for r in listing}
        verify(len(listing) <= 100_003 and len(listing) == len(all_names), "Duplicate or excessive archive entries")
        verify(sum(r.file_size for r in listing) <= 1 << 30, "Expanded weapon archive exceeds its limit")
        def read(name, maximum):
            verify(name in all_names and archive.getinfo(name).file_size <= maximum, "Missing or oversized weapon section")
            return archive.read(name)
        def original_chunk(chunk_digest):
            verify(digest(chunk_digest), "Invalid original file chunk identity")
            body = read(entry(ORIGINAL + "chunk:" + chunk_digest), 2 * CHUNK)
            decoded = base64.b64decode(body, validate=True)
            verify(0 < len(decoded) <= CHUNK and sha(decoded) == chunk_digest and base64.b64encode(decoded) == body,
                   "Original weapon history chunk checksum mismatch")
            return decoded
        header = canonical_object(read("manifest.json", 16_384))
        verify(set(header) == HEADER and header["format"] == FORMAT and integer(header["version"], 1) and header["version"] == 1
               and all(integer(header[k], 1 << 30) for k in ("archiveCount", "archiveBytes", "originalFileCount", "originalBytes"))
               and digest(header["workingSha256"]) and digest(header["indexSha256"]), "Invalid native weapon manifest")
        working = read("backup.json", 8 << 20)
        index = read("archives.ndjson", 64 << 20)
        verify(sha(working) == header["workingSha256"] and sha(index) == header["indexSha256"], "Weapon header checksum mismatch")
        remaining = all_names - {"manifest.json", "backup.json", "archives.ndjson"}
        rows = {}
        total = original_count = original_bytes = 0
        for line in index.splitlines():
            verify(0 < len(line) <= 8192, "Weapon index entry exceeds its limit")
            row = canonical_object(line)
            key = row.get("id")
            verify(set(row) == {"id", "entry", "sha256", "bytes"} and isinstance(key, str) and 0 < len(key) <= 2048
                   and key not in rows and digest(row["sha256"]), "Invalid or duplicate weapon history identity")
            path = entry(key)
            verify(path in remaining and row["entry"] == path and integer(row["bytes"], 64 << 20)
                   and row["bytes"] == archive.getinfo(path).file_size, "Invalid weapon history index")
            with archive.open(path) as stream:
                verify(hashlib.file_digest(stream, "sha256").hexdigest() == row["sha256"], "Weapon history digest mismatch")
            verify(not key.startswith("astravar-preserve-pending-v1:"), "Internal uncommitted native history identity")
            if key.startswith(PRESERVED):
                parts = key.removeprefix(PRESERVED).split(":")
                verify(len(parts) == 3 and parts[0] in ("campaign", "events", "rolls", "archives", "imports")
                       and parts[2] == row["sha256"], "Preserved weapon identity mismatch")
                native_key = bytes.fromhex(parts[1]).decode("utf-8")
                verify(native_key.encode("utf-8").hex() == parts[1], "Invalid preserved native identity")
            if key.startswith(RECEIPT):
                receipt = canonical_object(read(path, 16_384))
                verify(set(receipt) == {"format", "operationId", "fingerprint", "resultingRevision"}
                       and receipt["format"] == RECEIPT[:-1] and str(uuid.UUID(receipt["operationId"])) == receipt["operationId"]
                       and key == RECEIPT + receipt["operationId"] and digest(receipt["fingerprint"])
                       and integer(receipt["resultingRevision"], (1 << 63) - 1), "Invalid native weapon restoration receipt")
            if key.startswith(ORIGINAL + "chunk:"):
                original_chunk(key.removeprefix(ORIGINAL + "chunk:"))
            elif key.startswith(ORIGINAL + "file:"):
                descriptor = canonical_object(read(path, 1 << 20))
                verify(set(descriptor) == {"format", "sha256", "bytes", "chunks"} and descriptor["format"] == "astravar-original-json-v1"
                       and digest(descriptor["sha256"]) and key == ORIGINAL + "file:" + descriptor["sha256"]
                       and integer(descriptor["bytes"], 512 << 20) and descriptor["bytes"] and isinstance(descriptor["chunks"], list)
                       and 0 < len(descriptor["chunks"]) <= 8192, "Invalid original weapon file descriptor")
                original_count += 1
                original_bytes += descriptor["bytes"]
                verify(original_bytes <= 1 << 30, "Expanded original history exceeds its limit")
                encoded_hash, length = hashlib.sha256(), 0
                for part in descriptor["chunks"]:
                    data = original_chunk(part)
                    length += len(data)
                    verify(length <= descriptor["bytes"], "Original weapon file exceeds its recorded size")
                    encoded_hash.update(data)
                verify(length == descriptor["bytes"] and encoded_hash.hexdigest() == descriptor["sha256"],
                       "Original weapon history file checksum mismatch")
            else:
                verify(not key.startswith(ORIGINAL), "Unrecognized original history record")
            rows[("weapon.archive_rows", (key,))] = row
            remaining.remove(path)
            total += row["bytes"]
        verify(not remaining and (len(rows), total) == (header["archiveCount"], header["archiveBytes"]),
               "Unexpected weapon entries or counts")
        verify((original_count, original_bytes) == (header["originalFileCount"], header["originalBytes"]),
               "Original weapon file count or byte totals differ")
        return header, working, rows


def working_rows(value):
    verify(isinstance(value, dict) and set(value) <= {"format", "schema", "campaign", "history", "archives"}
           and value.get("format") == "astravar-backup" and type(value.get("schema")) is int and value["schema"] == 1
           and isinstance(value.get("campaign"), dict), "Unsupported native weapon working backup")
    campaign = value["campaign"]
    families = {"weapon.working": {("campaign",): campaign}}

    def collect(family, values, key, location=()):
        verify(isinstance(values, list), "Invalid native weapon collection")
        result = families.setdefault(family, {})
        for row in values:
            verify(isinstance(row, dict), "Invalid native weapon row")
            ident = key(row)
            verify(isinstance(ident, str) and ident and (*location, ident) not in result, "Invalid or repeated native weapon identity")
            result[*location, ident] = row

    for field in ("cores", "templates", "scrolls", "projects"):
        collect("weapon.working." + field, campaign.get(field, []), lambda row: row.get("id"))
    for core in campaign.get("cores", []):
        collect("weapon.working.payloads", core.get("payloads", []), lambda row: row.get("id"), (core["id"],))
    collect("weapon.working.pending", campaign.get("pending", []), lambda row: row.get("plan", {}).get("id"))
    slots = campaign.get("slots", {})
    verify(isinstance(slots, dict), "Invalid native weapon slot balances")
    families["weapon.working.slots"] = {(k,): {"level": k, "current": v} for k, v in slots.items()}
    collect("weapon.recent_history", value.get("history", []), lambda row: row.get("id"))
    collect("weapon.working_archives", value.get("archives", []), lambda row: row.get("id"))
    return {(family, ids): row for family, content in families.items() for ids, row in content.items()}


def archive_rows(raw):
    header, working, rows = checked_archive(io.BytesIO(raw))
    rows["weapon.manifest", ("manifest",)] = header
    rows.update(working_rows(loads(working)))
    return FORMAT, rows
