"""Recognize unqualified feet distances on native weapon attacks."""
import re
from ascension_migrate_legacy import verify

def native_attack_distance(key, source_path, target_path, row, target):
    verify(key[1] == "playable.characters.attacks" and source_path == ["range"] and
           target["kind"] == "attack" and key[2][0] == target["actorId"] and
           target_path in (["data", "reach"], ["data", "range"], ["data", "longRange"]),
           "Distance witness requires the source actor's native attack range")
    value = row.get("range")
    match = re.fullmatch(r"([1-9][0-9]{0,4})(?:/([1-9][0-9]{0,4}))? ft\.", value) if isinstance(value, str) else None
    verify(match is not None, "Native attack range has additional conditions or unsupported units")
    first = int(match[1])
    second = int(match[2]) if match[2] else None
    field = target_path[-1]
    data = target["data"]
    verify(first <= 10000 and (second is None or first <= second <= 10000) and
           ((field == "reach" and data.get("delivery") == "melee" and second is None) or
            (field in ("range", "longRange") and data.get("delivery") == "ranged" and
             ((second is None and field == "range" and data.get("longRange") is None)
              or (second is not None and data.get("range") == first and data.get("longRange") == second)))),
           "Native distance does not match the reviewed attack delivery and complete range band")
    return second if field == "longRange" else first


def native_conditional_distance(key, source_path, target_path, row, target):
    """Recover ordinary bands without discarding an exceptional range restriction."""
    original = row.get("range")
    first, separator, qualifier = original.partition("; ") if isinstance(original, str) else ("", "", "")
    data = target["data"]
    verify(separator and qualifier.strip() and data.get("nativeRange") == original and data.get("resolution") == "manual"
           and target_path in (["data","range"],["data","longRange"]) and data.get("delivery") == "ranged",
           "A conditional attack range needs the exact manual targeting rule")
    return native_attack_distance(key, source_path, target_path, {"range":first}, target)
