"""Normalize supported legacy skill flags and explicit feature clauses for the same actor."""
import re
from ascension_migrate_legacy import verify

FEATURE_NORMALIZERS = ("nativeAttackActionCount", "nativePassiveBonus", "nativeSkillRank", "nativeSingleActionForm")


def native_feature_value(rule, key, row, source_path, value, target, target_path):
    actor_id = key[2][0]
    data = target["data"]
    verify(target["actorId"] == actor_id, "Feature normalization belongs to another actor")
    if rule == "nativeSingleActionForm":
        verify(key[1] == "playable.characters.itemDefinitions" and source_path == ["description"] and
               target["kind"] == "form" and target_path == ["data", "activationSeconds"] and data.get("timing") == "action" and
               data.get("sourceItemId") and isinstance(value, str) and
               re.search(r"\bchanges? form as an action\.", value, re.I) and
               not re.search(r"\b(minutes?|hours?|rounds?|turns?|seconds?)\b", value, re.I),
               "Unsupported form activation duration")
        return 0
    if rule == "nativeSkillRank":
        verify(key[1] == "playable.characters" and len(source_path) == 4 and
               source_path[:3] == ["characterJson", "$json", "skills"] and target["kind"] == "actor" and
               target_path == ["data", "skills", source_path[3].lower(), "rank"] and isinstance(value, dict) and
               isinstance(value.get("proficient"), bool) and isinstance(value.get("expertise"), bool) and
               value.get("halfProficiency", False) is False and (not value["expertise"] or value["proficient"]),
               "Legacy skill rank requires exact non-fractional proficiency flags")
        return 2 if value["expertise"] else int(value["proficient"])
    verify(key[1] == "playable.characters.features" and source_path == ["description"] and
           row.get("source", {}).get("acceptance") in ("OFFICIAL", "ACCEPTED_HOMEBREW") and isinstance(value, str),
           "An accepted native feature statement is required")
    if rule == "nativeAttackActionCount":
        match = re.match(r"^(?:you (?:can |may )?make )?(one|two|three|four|[1-9]) attacks? per attack action\.", value, re.I)
        verify(target["kind"] == "actor" and target_path == ["data", "attacksPerAction"] and row.get("kind") == "CLASS" and match,
               "Action count requires an explicit unconditional class feature")
        token = match.group(1).lower()
        return int(token) if token.isdigit() else ("one", "two", "three", "four").index(token) + 1
    # Only this explicit legacy passive clause is understood. An active bonus, optional bonus,
    # unsupported wording, or a parent feature belonging to somebody else cannot supply the amount.
    subject = r"(?:Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma) \(([A-Za-z ]+)\)"
    pattern = r"(?:^|(?<=[.!?])\s+)\+(\d{1,2}) to passive " + subject + r" and passive " + subject + r" ONLY[.;]"
    matches = re.findall(pattern, value, re.I)
    verify(len(matches) == 1 and row.get("kind") == "FEAT" and target["kind"] == "effect" and
           target_path == ["data", "amount"] and data.get("target") == "passive" and data.get("key") in [skill.lower() for skill in matches[0][1:]] and
           data.get("operation") == "add" and data.get("sourceRecord") == row["source"],
           "Passive bonus requires a matching, player-approved passive skill clause")
    return int(matches[0][0])
