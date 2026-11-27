#!/usr/bin/env python3
"""Private file interchange for the local D1/R2 adapter and the canonical recovery validator."""
import argparse
import json
import os
import sys
from contextlib import closing
from pathlib import Path

import ascension_migrate as migration


def put(path, value):
    with path.open("xb") as f:
        f.write(migration.encode(value))


def scoped_file(folder, name):
    path = folder / name
    migration.verify(path.resolve().is_relative_to(folder.resolve()), "Invalid local object path")
    return path


def prepare(folder, package):
    bundle = migration.read_bundle(package)
    migration.check_database(bundle)
    locations = migration.media_locations(bundle["rows"], bundle["tenant"])
    owner, continuity = bundle["tenant"]
    put(folder / "snapshot.json", {"owner": owner, "continuity": continuity,
                                  "state": bundle["manifest"]["source"], "tables": bundle["rows"]})
    objects = folder / "objects"
    objects.mkdir(mode=0o700)
    for key, (digest, size, _, _) in locations.items():
        path = scoped_file(objects, key)
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        raw = bundle["objects"][digest]
        migration.verify(len(raw) == size, "Incomplete private asset")
        with path.open("xb") as f:
            f.write(raw)
    return {"objects": sorted(locations), "cutoverHolds": sum(report.get("cutoverHolds", 0) for _, report in bundle["sources"].values()),
            **migration.summary(bundle)}


def process(args):
    if args.operation == "layout":
        with closing(migration.make_database(":memory:")) as db:
            return [{"name": name, "columns": migration.columns(db, name), "order": order}
                    for name, order in migration.TABLES.items()]
    if args.operation == "unpack":
        return prepare(args.folder, args.package)
    source = migration.loads(migration.read_limited(args.folder / "snapshot.json", migration.LIMIT))
    tenant = (source["owner"], source["continuity"])
    locations = migration.media_locations(source["tables"], tenant)
    if args.operation == "objects":
        return [{"key": key, "sha256": row[0], "size": row[1]} for key, row in sorted(locations.items())]
    paired = migration.read_bundle(args.evidence) if args.evidence else None
    if paired:
        migration.verify(paired["tenant"] == tenant, "Source-evidence continuity mismatch")
        migration.check_database(paired)
    raw = migration.read_objects(args.folder, locations)
    data = migration.bundle_contents(tenant, source["state"], source["tables"], paired["sources"] if paired else {}, raw)
    return migration.write_bundle(args.package, data)


if __name__ == "__main__":
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["layout", "objects", "pack", "unpack"])
    parser.add_argument("folder", type=Path, nargs="?")
    parser.add_argument("package", type=Path, nargs="?")
    parser.add_argument("--evidence", type=Path)
    try:
        print(json.dumps(process(parser.parse_args()), sort_keys=True))
    except (ValueError, KeyError, OSError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
