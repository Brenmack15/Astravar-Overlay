"""Privately preserve an exact native weapon ZIP alongside a verified canonical recovery package."""
import hashlib
import os
import stat
import uuid
from collections import Counter
from contextlib import contextmanager

from ascension_migrate import LIMIT, check_database, create_private, read_bundle, summary, sync_directory
from ascension_migrate_legacy import HASH, encode, loads, sha, verify
from ascension_migrate_weapon import checked_archive, working_rows

FORMAT = "astravar-detached-native-recovery"
CANONICAL = "campaign.zip"
ORIGINAL = "native-history.zip"
MANIFEST = "manifest.json"
MAX_NATIVE_BYTES = 1 << 30
MAX_MANIFEST_BYTES = 32 << 10
BUFFER_BYTES = 1 << 20


@contextmanager
def regular_source(path, maximum):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as stream:
        before = os.fstat(fd)
        verify(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= maximum,
               "Expected a regular local recovery file within the supported size")
        yield stream
        after = os.fstat(fd)
        verify((before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
               (after.st_size, after.st_mtime_ns, after.st_ctime_ns), "Recovery input changed during verification")


def transfer(source, maximum, destination=None):
    digest = hashlib.sha256()
    length = 0
    while block := source.read(BUFFER_BYTES):
        length += len(block)
        verify(length <= maximum, "Recovery data exceeds its transfer limit")
        digest.update(block)
        if destination is not None:
            destination.write(block)
    verify(length == os.fstat(source.fileno()).st_size, "Recovery input changed during transfer")
    return {"sha256": digest.hexdigest(), "bytes": length}


def native_descriptor(path):
    with regular_source(path, MAX_NATIVE_BYTES) as stream:
        identity = transfer(stream, MAX_NATIVE_BYTES)
        stream.seek(0)
        header, working, _ = checked_archive(stream)
        families = Counter(family for family, _ in working_rows(loads(working)))
    return {**identity, "nativeManifest": header, "workingRows": dict(sorted(families.items()))}


def describe(canonical, native):
    with regular_source(canonical, LIMIT + (2 << 20)) as stream:
        identity = transfer(stream, LIMIT + (2 << 20))
        stream.seek(0)
        # The existing canonical verifier enforces the v1 row, media, byte and transaction limits.
        bundle = read_bundle(stream)
        check_database(bundle)
    return {"format": FORMAT, "version": 1, "owner": bundle["tenant"][0], "continuity": bundle["tenant"][1],
            "canonical": {**identity, **summary(bundle)}, "original": native_descriptor(native),
            "purpose": "native-history-recovery", "gameplayChanges": 0}


def preview(canonical, native):
    manifest = describe(canonical, native)
    return {"status": "PREVIEW", "reviewSha256": sha(encode(manifest)), "manifest": manifest}


def copy_new(source, destination, expected, maximum):
    with regular_source(source, maximum) as input_file, create_private(destination) as output:
        verify(transfer(input_file, maximum, output) == expected, "Copied recovery file differs from review")
        output.flush()
        os.fsync(output.fileno())


def check_set(directory, approved):
    verify(isinstance(approved, str) and HASH.fullmatch(approved), "The exact reviewed recovery-set SHA-256 is required")
    verify(directory.is_dir() and not directory.is_symlink() and
           {p.name for p in directory.iterdir()} == {CANONICAL, ORIGINAL, MANIFEST},
           "Incomplete or unexpected detached recovery files")
    with regular_source(directory / MANIFEST, MAX_MANIFEST_BYTES) as stream:
        raw = stream.read(MAX_MANIFEST_BYTES + 1)
    verify(sha(raw) == approved, "The detached manifest differs from its reviewed digest")
    actual = describe(directory / CANONICAL, directory / ORIGINAL)
    verify(encode(actual) == raw, "The canonical package or original archive differs from the detached manifest")
    return {"status": "VERIFIED_DETACHED", "reviewSha256": approved, "manifest": actual}


def save(canonical, native, directory, approved):
    review = preview(canonical, native)
    verify(review["reviewSha256"] == approved, "Detached recovery inputs differ from the reviewed proposal")
    manifest = review["manifest"]
    directory.mkdir(mode=0o700, exist_ok=False)
    try:
        for name, source, metadata, maximum in ((CANONICAL, canonical, manifest["canonical"], LIMIT + (2 << 20)),
                                                (ORIGINAL, native, manifest["original"], MAX_NATIVE_BYTES)):
            copy_new(source, directory / name, {k: metadata[k] for k in ("sha256", "bytes")}, maximum)
        with create_private(directory / MANIFEST) as out:
            out.write(encode(manifest))
            out.flush()
            os.fsync(out.fileno())
        result = check_set(directory, approved)
        sync_directory(directory)
        sync_directory(directory.parent)
        return {**result, "status": "PRESERVED_DETACHED"}
    except BaseException:
        # The directory was exclusively reserved here. A pre-existing bundle is never replaced.
        for name in (MANIFEST, CANONICAL, ORIGINAL):
            (directory / name).unlink(missing_ok=True)
        directory.rmdir()
        raise


def recover(directory, destination, approved):
    result = check_set(directory, approved)
    original = result["manifest"]["original"]
    temporary = destination.with_name(uuid.uuid4().hex + ".partial")
    try:
        copy_new(directory / ORIGINAL, temporary, {k: original[k] for k in ("sha256", "bytes")}, MAX_NATIVE_BYTES)
        verify(native_descriptor(temporary) == original, "Restored native original failed independent readback")
        os.link(temporary, destination)
        sync_directory(destination.parent)
    finally:
        temporary.unlink(missing_ok=True)
    return {"status": "RECOVERED_DETACHED_ORIGINAL", "reviewSha256": approved,
            "sourceSha256": original["sha256"], "bytes": original["bytes"], "gameplayChanges": 0}
