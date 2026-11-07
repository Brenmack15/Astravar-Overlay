#!/usr/bin/env python3
"""Summarize actual local verification artifacts without inventing missing runs.

This is an evidence index, not a substitute for running Gradle or a certificate
compatibility check. CI ties it to its exact checked-out head; dirty local runs
remain explicitly marked. Test XML, screenshots and APKs are hashed for review.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def summarize_results(root: Path) -> dict:
    groups = {}
    for module in ("rules", "app"):
        base = root / module / "build/test-results"
        for file in sorted(base.glob("*/TEST-*.xml")):
            suite = ET.parse(file).getroot()
            if suite.tag != "testsuite":
                raise ValueError(f"Unexpected test report: {file}")
            counts = {name: int(suite.attrib.get(name, "0"))
                      for name in ("tests", "failures", "errors", "skipped")}
            if min(counts.values()) < 0 or sum(counts[n] for n in ("failures", "errors", "skipped")) > counts["tests"]:
                raise ValueError(f"Inconsistent test counts: {file}")
            cases = suite.findall("testcase")
            if len(cases) != counts["tests"]:
                raise ValueError(f"Test count does not match recorded cases: {file}")
            group = groups.setdefault(f"{module}:{file.parent.name}", {
                "suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0,
                "seconds": 0.0, "reports": []})
            group["suites"] += 1
            for name, value in counts.items():
                group[name] += value
            group["seconds"] += float(suite.attrib.get("time", "0"))
            group["reports"].append({"path": str(file.relative_to(root)),
                                     "sha256": hashlib.sha256(file.read_bytes()).hexdigest()})
    for group in groups.values():
        group["passed"] = group["tests"] - group["failures"] - group["errors"] - group["skipped"]
        group["seconds"] = round(group["seconds"], 3)
        group["status"] = "failed" if group["failures"] or group["errors"] else "passed"
    return groups


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    def git(*values):
        return subprocess.check_output(["git", "-C", str(root), *values], text=True).strip()
    # A dirty local implementation can still be compared exactly with a later
    # committed source tree. Include source/build/schema inputs, not generated
    # reports, so documentation/report writes cannot change this digest.
    source_names = set(git("ls-files").splitlines()) | set(git("ls-files", "--others", "--exclude-standard").splitlines())
    source_inputs = []
    for name in sorted(source_names):
        if not (name.startswith(("app/src/", "app/schemas/", "rules/src/", "gradle/")) or
                name in {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "app/build.gradle.kts", "rules/build.gradle.kts"}):
            continue
        file = root / name
        if file.is_file():
            source_inputs.append({"path": name, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()})
    source_digest = hashlib.sha256(json.dumps(source_inputs, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    paths = [*root.glob("app/build/outputs/apk/**/*.apk"), *root.glob("validation/reforge-screenshots/*.png")]
    index = {
        "source_commit": git("rev-parse", "HEAD"),
        "source_tree": git("rev-parse", "HEAD^{tree}"),
        "dirty_worktree": bool(git("status", "--porcelain")),
        "source_input_sha256": source_digest,
        "source_inputs": source_inputs,
        "tests": summarize_results(root),
        "artifacts": [{"path": str(p.relative_to(root)), "bytes": p.stat().st_size,
                       "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(paths)],
        "physical_device_installation": "not performed by this evidence index",
        "owner_signing": "requires independent APK certificate verification",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(index, indent=2) + "\n")
    print(json.dumps({"source_commit": index["source_commit"], "dirty_worktree": index["dirty_worktree"],
                      "test_groups": {key: {k: v for k, v in value.items() if k != "reports"}
                                      for key, value in index["tests"].items()},
                      "artifact_count": len(index["artifacts"])}))


if __name__ == "__main__":
    main()
