"""Exercise the offline CLI above the canonical 64 MiB entry limit using two synthetic weapon records."""
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from ascension_migrate_legacy import MAX_BYTES, encode, file_hash, sha
from ascension_migrate_weapon import entry


def synthetic_archive(path):
    block = bytes(range(256)) * 4096
    record_bytes = 34 * len(block)
    digest = hashlib.sha256()
    for _ in range(34):
        digest.update(block)
    rows = [{"id": tag, "entry": entry(tag), "sha256": digest.hexdigest(), "bytes": record_bytes}
            for tag in ("opaque-synthetic-a", "opaque-synthetic-b")]
    index = b"".join(encode(row) + b"\n" for row in rows)
    working = encode({"format": "astravar-backup", "schema": 1, "campaign": {"slots": {"2": 1}}, "history": []})
    header = {"format": "astravar-complete-history", "version": 1, "archiveCount": len(rows),
              "archiveBytes": len(rows) * record_bytes, "originalFileCount": 0, "originalBytes": 0,
              "workingSha256": sha(working), "indexSha256": sha(index)}
    with ZipFile(path, "x", compression=ZIP_STORED) as out:
        for name, raw in (("manifest.json", encode(header)), ("backup.json", working), ("archives.ndjson", index)):
            out.writestr(ZipInfo(name), raw)
        for row in rows:
            with out.open(ZipInfo(row["entry"]), "w") as stream:
                for _ in range(34):
                    stream.write(block)


def run():
    with tempfile.TemporaryDirectory(prefix="ascension-synthetic-archive-") as tmp:
        root = Path(tmp)
        def cli(*args):
            out = subprocess.run([sys.executable, ROOT / "tools/ascension_migrate.py", *args], cwd=root,
                                 capture_output=True, timeout=180)
            if out.returncode:
                raise RuntimeError(out.stderr.decode(errors="replace")[-1200:])
            return json.loads(out.stdout)

        original, canonical, directory, restored = [root / name for name in ("original.zip", "campaign.zip", "set", "restored.zip")]
        synthetic_archive(original)
        cli("init", root / "isolated", "--owner", "synthetic-owner", "--continuity", "qa-large-original")
        cli("export", root / "isolated", canonical)
        preview = cli("detached-preview", canonical, original)
        pin = preview["reviewSha256"]
        cli("detached-save", canonical, original, directory, "--review-sha256", pin)
        checked = cli("detached-verify", directory, "--review-sha256", pin)["manifest"]
        proof = cli("detached-recover", directory, restored, "--review-sha256", pin)
        assert original.stat().st_size > MAX_BYTES
        assert checked == preview["manifest"] and checked["original"]["nativeManifest"]["archiveCount"] == 2
        assert proof["sourceSha256"] == file_hash(restored) == file_hash(original)
        assert proof["gameplayChanges"] == 0 and file_hash(canonical) == file_hash(directory / "campaign.zip")
        return {"verified": True, "sourceBytes": proof["bytes"], "sourceSha256": proof["sourceSha256"],
                "verifiedArchives": 2, "gameplayChanges": proof["gameplayChanges"], "aboveCanonicalEntryLimit": True}


if __name__ == "__main__":
    print(json.dumps(run(), sort_keys=True))
