"""Read narrowly supported printed attack/action clauses from an accepted native feature."""
import re

from ascension_migrate_legacy import verify


def printed_action_terms(row):
    verify(isinstance(row, dict) and row.get("source", {}).get("acceptance") == "ACCEPTED_HOMEBREW"
           and isinstance(row.get("id"), str) and isinstance(row.get("description"), str),
           "Printed action requires an accepted native feature")
    text = row["description"]
    # Only the terminal, unconditional list of two explicitly recorded natural attacks.
    name = r"([A-Za-z][A-Za-z -]{0,30}?)"
    atom = name + r" ([+-]\d{1,2}) (\d{1,2})d(4|6|8|10|12|20)([+-]\d{1,2}) (piercing|slashing|bludgeoning)"
    pair = re.search(r"(?:^|\. )Magical " + atom + r", " + atom + r", one each Multiattack\.$", text)
    if pair:
        verify(text.startswith("Completed ") and row.get("resourceId") and not re.search(
            r"\b(?:proposed|planned|optional|if|unless|instead|hypothetical|negated|example)\b", text, re.I),
            "Conditional or proposed feature is not a completed attack profile")
        attacks = {}
        for attack, hit, dice, sides, flat, damage_type in (pair.groups()[:6],pair.groups()[6:]):
            verify(attack not in attacks and int(dice) > 0, "Duplicate or empty printed attack")
            attacks[attack] = {"name":attack, "fixedHit":int(hit),"dice":int(dice),"sides":int(sides),
                               "flat":int(flat),"damageType":damage_type,"magical":True}
        return {"attacks":attacks,"sequence":list(attacks)}
    fear = re.fullmatch(r"Bonus Action once per deployment, selected hearing creatures (\d{1,3})ft "
                        r"(STR|DEX|CON|INT|WIS|CHA) DC(\d{1,2}) or Frightened up to (\d{1,2})min, "
                        r"repeat at end of turn; allies may be excluded\.",text)
    if fear:
        verify(row.get("actionType") == "Bonus Action" and row.get("resourceId"), "Antagonistic timing or missing charge")
        reach, ability, dc, minutes = fear.groups()
        verify(0 < int(reach) <= 1200 and 0 < int(dc) <= 100 and 0 < int(minutes) <= 99, "Invalid area, duration or DC")
        return {"range":int(reach),"saveAbility":ability,"saveDc":int(dc),
                "durationSeconds":int(minutes)*60,"cost":1}
    replacement = re.search(r"(?:^|\. )Replace at most one with ([A-Za-z][A-Za-z -]{0,40})\.(?: |$)",text)
    if replacement and len(re.findall(r"\breplace\b",text,re.I)) == 1:
        verify(not re.search(r"\b(?:if|unless|instead|proposed|optional|hypothetical|example)\b",text,re.I), "Conditional substitution")
        return {"maximum":1,"replacement":replacement.group(1)}
    raise ValueError("Unrecognized printed action clause")


def validate_printed_witness(source, destination, target, transform):
    """Bind each extracted literal only to the matching typed destination."""
    data = target["data"]
    key = source[1:]
    if target["kind"] == "attack" and key[:2] == ["attacks",target["title"]] and len(key) == 3:
        fields = {"fixedHit":[["data","fixedHit"]],"magical":[["data","magical"]],
                  "damageType":[["data","components",0,"type"]]}
        for number in ("dice","sides","flat"):
            fields[number] = [["data","components",0,number]]
        fields["name"] = [["id"],["title"],["data","components",0,"id"],["data","components",0,"name"]]
        verify(data.get("formId") and destination in fields.get(key[2],[]) and
               transform == ("scopedIdentity" if destination == ["id"] else "exact"),
               "Printed attack field does not match its form-only component")
        return
    if target["kind"] == "action_definition":
        if data.get("type") == "multiattack" and len(key)==2 and key[0]=="sequence":
            verify(data.get("formId") and destination == ["data","attackSequence",key[1]] and transform == "canonicalRef",
                   "Printed multiattack order differs from its recorded sequence")
            return
        if data.get("type") == "multiattack" and key == ["maximum"]:
            verify(data.get("excludedForms") and destination == ["data","substitutions",0,"maximum"] and transform=="exact",
                   "Printed substitution limit belongs to a different action")
            return
        if data.get("type") == "frighten" and len(key)==1 and key[0] in ("cost","range","saveAbility","saveDc","durationSeconds"):
            correct = ["data","cost","amount"] if key == ["cost"] else ["data",*key]
            verify(data.get("formId") and data.get("timing")=="bonus_action" and
                   destination==correct and transform=="exact","Printed fear effect does not match its recorded field")
            return
    raise ValueError("Printed action does not attest to the target field")
