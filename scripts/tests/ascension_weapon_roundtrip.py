"""Rehearse private archival recovery of synthetic bytes emitted by the actual native HistoryBackup codec."""
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import ascension_migrate as migration
from ascension_migrate_legacy import encode
from ascension_migrate_reviewed import template

TENANT = ("synthetic-archive-owner", "qa-native-weapon-archive")


def archive_mapping(raw):
    initial = template([raw], *TENANT)
    working = next(d for d in initial["decisions"] if d["family"] == "weapon.working")
    binding = {k: working[k] for k in ("source", "family", "identity", "rowSha256")}
    fact = {"format":"astravar-reviewed-source-facts", "version":1, "owner":TENANT[0], "continuity":TENANT[1],
            "facts":[{"id":"synthetic-archived-source", "sourceBinding":binding,
                      "values":{"sourceRole":"original_armor_archive"}, "reason":"This synthetic copy serves as an original armor archive."}]}
    template_map = template([raw, encode(fact)], *TENANT)
    for decision in template_map["decisions"]:
        decision.update(disposition="historical", reason="Preserve the reviewed synthetic original and every indexed row.")
    return fact, template_map


def rehearse(source, destination):
    with tempfile.TemporaryDirectory(prefix="ascension-native-history-") as directory:
        folder = Path(directory)
        raw = migration.read_limited(source)
        fact, plan = archive_mapping(raw)
        review = folder / "review.json"
        review.write_bytes(encode(plan))
        facts = folder / "classification.json"
        facts.write_bytes(encode(fact))
        package = folder / "history.zip"
        initial = migration.convert_reviewed([source, facts], review, package, TENANT)
        assert initial["originalSources"] == 2 and not any(initial["counts"].values())
        isolated = folder / "private-staging"
        migration.init(isolated, *TENANT)
        receipt = migration.apply(isolated, package, migration.inspect(isolated, package)["approvalSha256"])
        assert receipt["status"] == "STAGED"
        restored = folder / "fresh-export.zip"
        migration.export(isolated, restored)
        state = migration.read_bundle(restored)
        parity = migration.mechanical_parity(source, state)
        assert parity["counts"]["equal"] >= 2 and not parity["holds"] and not parity["counts"]["different"]
        return migration.extract_source(restored, destination, migration.sha(raw))


if __name__ == "__main__":
    rehearse(Path(sys.argv[1]), Path(sys.argv[2]))
