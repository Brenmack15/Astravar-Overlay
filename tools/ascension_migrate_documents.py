"""Index exact historical documents for private recovery without publishing their contents."""
import base64

from ascension_migrate_legacy import HASH, IDENTITY, sha, verify

FORMAT = "astravar-historical-documents"
FAMILY = "reference.private_documents"


def index_documents(document):
    verify(set(document) == {"format", "version", "owner", "continuity", "documents"} and
           type(document["version"]) is int and document["version"] == 1 and
           all(isinstance(document[k], str) and document[k].strip() for k in ("owner", "continuity")),
           "Invalid historical document envelope")
    records = document["documents"]
    verify(isinstance(records, list) and 0 < len(records) <= 10000, "Invalid historical document count")
    rows = {}
    fields = {"sourceId", "versionId", "filename", "mediaType", "bytes", "sha256", "base64"}
    for record in records:
        verify(isinstance(record, dict) and set(record) == fields, "Invalid historical document record")
        identity = (record["sourceId"], record["versionId"])
        verify(all(isinstance(value, str) and IDENTITY.fullmatch(value) for value in identity)
               and (FAMILY, identity) not in rows, "Duplicate or invalid historical document identity")
        filename = record["filename"]
        verify(isinstance(filename, str) and 0 < len(filename) <= 255 and
               all(ord(ch) >= 32 for ch in filename) and filename not in (".", "..") and
               "/" not in filename and "\\" not in filename, "Invalid historical document filename")
        size = record["bytes"]
        verify(record["mediaType"] in ("text/markdown", "text/plain", "application/json") and
               type(size) is int and 0 < size <= 8 * 1024 * 1024 and isinstance(record["base64"], str)
               and len(record["base64"]) == 4 * ((size + 2) // 3) and
               isinstance(record["sha256"], str) and HASH.fullmatch(record["sha256"]),
               "Invalid historical document metadata")
        raw = base64.b64decode(record["base64"], validate=True)
        verify(len(raw) == size and sha(raw) == record["sha256"] and
               base64.b64encode(raw).decode("ascii") == record["base64"], "Historical document integrity mismatch")
        raw.decode("utf-8", errors="strict")
        # The verified envelope retains exact bytes. Keep the private body out of target witnesses.
        rows[FAMILY, identity] = {k: v for k, v in record.items() if k != "base64"} | {"visibility": "private"}
    return FORMAT, rows
