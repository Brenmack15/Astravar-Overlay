"""Verify independently pinned supplemental projections against original source identities."""
from ascension_migrate_care import validate_care_projection
from ascension_migrate_spells import verify_grant_origin, verify_item_feature
from ascension_migrate_legacy import encode, sha, verify, IDENTITY

FORMAT = "astravar-reviewed-supplement"
FAMILY = "review.supplement"
KINDS = {"action_definition", "attack", "effect", "form", "feature", "spell", "spell_grant", "invention_project",
         "item_instance", "item_definition", "care_protocol", "actor", "resource_pool", "known_fact"}
LOCKED_FIELDS = {"wallet", "coins", "quantity", "custodianId", "ownerId", "attuned", "current",
                 "maximum", "currentHp", "maxHp", "temporaryHp", "activeFormId", "level", "abilities"}


def index_supplement(doc):
    verify(set(doc) == {"format","version","owner","continuity","records"} and doc["version"] == 1
           and isinstance(doc["records"],list) and 0 < len(doc["records"]) <= 8192, "Invalid supplement")
    rows = {}
    for row in doc["records"]:
        verify(set(row) == {"id","kind","actorId","origin","ruling","values","targetId"} and
               row["kind"] in KINDS and isinstance(row["values"],dict) and row["values"] and
               all(isinstance(row[k],str) and IDENTITY.fullmatch(row[k]) for k in ("id","targetId")) and
               not LOCKED_FIELDS.intersection(row["values"]), "Invalid supplemental assertion")
        index = FAMILY, (row["id"],)
        verify(index not in rows, "Duplicate supplemental assertion")
        rows[index] = row
    return FORMAT, rows


def verify_supplements(plan, entries, targets, decisions, source_key):
    authority = plan.get("reviewAuthority") or {}
    supplied = {key[0] for key in entries if key[1] == FAMILY}
    verify(supplied == set(authority.get("supplements", [])), "Supplement differs from independent authority")
    for key,row in entries.items():
        if key[1] != FAMILY:
            continue
        origin = row["origin"]
        original = source_key(origin)
        verify(original in entries and original[0] not in supplied and origin.get("rowSha256")==sha(encode(entries[original])),
               "Missing original for supplemental ruling")
        validate_care_projection(row, original, entries, targets)
        if row["ruling"]:
            rule=authority["decisions"].get(row["ruling"])
            verify(isinstance(rule,dict) and rule.get("decisionId")==row["ruling"] and rule.get("ownerConfirmation")
                   and rule.get("productionApproved") is False, "Missing owner ruling")
        target=targets.get(row["targetId"])
        verify(target and target["kind"]==row["kind"] and target["actorId"]==row["actorId"] and
               decisions[key]["targets"]==[target["id"]], "Supplement target mismatch")
        verify_grant_origin(target, original, entries[original], targets)
        verify_item_feature(target, original, entries[original], targets)
        for name,value in row["values"].items():
            actual = target[name] if name in ("id","title") else target["data"].get(name)
            verify(encode(actual)==encode(value), "Supplement disagrees with typed record")


def supplemental_witness(key, row, witness):
    if key[1] != FAMILY: return
    tp=witness["targetPath"]
    path=tp[1:] if tp[0]=="data" else tp
    verify(witness["target"]==row["targetId"] and witness["sourcePath"]==["values",*path] and
           witness["transform"]=="exact", "Supplement parity requires exact authorized path")

def historical_projections(authority, entries, source_key):
    allowed = {"older-unknown-condition-projection":"records.companionStates", "stale-fact-history-review":"records.facts",
        "older-party-sheet-projection":"records.partySheets", "source-reference-only":"records.externalReferences",
        "proposal-or-superseded-note":"playable.characters.features"}
    result=set()
    for r in (authority or {}).get("historicalProjections", []):
        b=r["binding"]; k=source_key(b)
        verify(k in entries and k[1]==allowed.get(r["kind"]) and sha(encode(entries[k]))==b["rowSha256"] and
               k not in result and isinstance(r["proof"],dict) and r["proof"], "Invalid historical source projection")
        if k[1]=="records.companionStates":verify(entries[k].get("condition")=="Unknown","Historical condition has an actual value")
        if k[1]=="playable.characters.features":verify(r["proof"].get("scope")==entries[k].get("description"),"Historical note differs from original")
        result.add(k)
    return result
