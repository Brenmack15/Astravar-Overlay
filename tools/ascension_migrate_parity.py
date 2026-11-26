"""Compare recorded native mechanics with independently validated, scoped canonical recovery rows."""
from ascension_migrate_legacy import COINS, encode, loads, read_limited, sha, source_families, verify


def mechanical_parity(source_path, bundle):
    raw = read_limited(source_path)
    native = loads(raw)
    families = {name: records for name, _, records in source_families(native)}
    continuity = bundle["tenant"][1]
    entities = {r["id"]: (r, loads(r["data_json"])) for r in bundle["rows"]["cloud_entities"]}
    source_digest=sha(raw)
    aliases={}
    evidence=bundle["sources"].get(source_digest)
    if evidence and "reviewedMapping" in evidence[1]:
        plan=evidence[1]["reviewedMapping"]
        from ascension_migrate_reviewed import indexed as all_native_rows, path_value
        _, indexed = all_native_rows(raw)
        for w in plan["parity"]:
            if w["source"]==source_digest and w["targetPath"]==["id"] and w["transform"]=="scopedIdentity":
                original=path_value(indexed[(w["family"],tuple(w["identity"]))],w["sourcePath"])
                row,_=entities[w["target"]]
                aliases.setdefault((row["kind"],original),set()).add(w["target"])
    actors = {a["actorId"]: a for _, a in families.get("authority.actors", [])}
    checks, holds = [], []
    def check(kind, identity, field, expected, actual, available=True):
        state = "absent" if not available else "different" if expected != actual else "bothUnknown" if expected is None else "equal"
        checks.append({"kind":kind, "id":identity, "field":field, "native":expected, "cloud":actual, "status":state})
    def target(kind, identity, actor_id=None):
        proposed=aliases.get((kind,identity),set())
        canonical=next(iter(proposed)) if len(proposed)==1 else identity
        row, data = entities.get(canonical, ({}, {}))
        present = row.get("kind") == kind and (actor_id is None or row.get("actor_id") == actor_id)
        return data if present else {}, present
    def compare_fields(kind, identity, expected, data, present):
        for key, expected_value in expected.items():
            check(kind, identity, key, expected_value, data.get(key), present and key in data)
    for _, wallet in families.get("records.wallets", []):
        identity = wallet["id"]
        if wallet.get("campaignId") != continuity:
            holds.append({"kind":"wallet","id":identity,"reason":"Unproven continuity membership"})
            continue
        data, present = target("wallet", identity)
        coins = data.get("coins", {})
        compare_fields("wallet",identity,{k:wallet.get(v) for k,v in COINS.items()},coins,present)
        if wallet.get("holderProfileId") is not None:
            compare_fields("wallet",identity,{"holderId":wallet["holderProfileId"]},data,present)
    for _, actor_row in families.get("playable.characters", []):
        identity = actor_row["profileId"]
        binding = actors.get(identity,{})
        if binding.get("campaignId") != continuity or not binding.get("playable"):
            holds.append({"kind":"actor","id":identity,"reason":"A playable Genesis binding to this continuity is required"})
            continue
        char = loads(actor_row["characterJson"])
        if char.get("id") != identity or char.get("revision") != actor_row.get("revision"):
            holds.append({"kind":"actor","id":identity,"reason":"Stored actor identity or revision disagrees with envelope"})
            continue
        data, present = target("actor", identity, identity)
        expected={k:char[k] for k in ("currentHp","temporaryHp","gameSeconds","level","baseAc","baselineConstitutionModifier") if k in char}
        if "baseMaxHp" in char:
            expected["maxHp"]=char["baseMaxHp"]
        if isinstance(char.get("reviewedProficiency"),dict) and "bonus" in char["reviewedProficiency"]:
            expected["proficiency"]=char["reviewedProficiency"]["bonus"]
        if char.get("rules",{}).get("core") in ("LEGACY_2014","REVISED_2024"):
            expected["rulesProfile"] = char["rules"]["core"].lower()
        if binding.get("role") in ("PLAYER","COMPANION","NPC"):
            expected["role"] = binding["role"].lower()
        compare_fields("actor",identity,expected,data,present)
        if char.get("permanentAdvancements"):
            holds.append({"kind":"actor","id":identity,"reason":"Permanent advancement history requires separate base and progression comparison"})
        else:
            compare_fields("base_ability", identity, char.get("baseAbilities",{}), data.get("abilities",{}), present)
        for pool in char.get("resources",[]):
            pool_id=pool["id"]
            values, found=target("resource_pool",pool_id,identity)
            compare_fields("resource_pool",pool_id,{k:pool.get(k) for k in ("current","maximum")},values,found)
        scoped_slots=[d for row,d in entities.values() if row["kind"]=="resource_pool" and row["actor_id"]==identity and d.get("type")=="spell_slot"]
        for level, balance in char.get("spellSlots",{}).items():
            eligible=[slot for slot in scoped_slots if str(slot.get("slotLevel"))==level]
            if len(eligible)>1:
                holds.append({"kind":"spell_slot","id":identity,"level":level,"reason":"Multiple canonical pools require source/grant mapping"})
                continue
            actual=eligible[0] if eligible else {}
            check("spell_slot",identity,"level"+level,balance,actual.get("current"),len(eligible)==1 and "current" in actual)
        if any(char.get(k) for k in ("items","effects","attacks","spells","armor","features","conditions","activeStances")):
            holds.append({"kind":"actor","id":identity,"reason":"Effective effects, form, cast, attack and feature projections require their own rules parity review"})
    for _, ownership in families.get("authority.items", []):
        identity = ownership["itemId"]
        owner = ownership.get("ownerActorId")
        if actors.get(owner,{}).get("campaignId") != continuity:
            holds.append({"kind":"item_instance","id":identity,"reason":"Unproven owner or continuity"})
            continue
        instance = loads(ownership["instanceJson"])
        if instance.get("id") != identity:
            holds.append({"kind":"item_instance","id":identity,"reason":"Native physical identity conflicts with its authority record"})
            continue
        data,present=target("item_instance",identity)
        expected={"ownerId":owner}
        expected.update({k:instance[k] for k in ("quantity","definitionId","attuned") if k in instance})
        compare_fields("item_instance",identity,expected,data,present)
    counts={status:sum(c["status"]==status for c in checks) for status in ("equal","bothUnknown","different","absent")}
    return {"sourceSha256":sha(raw), "manifestSha256":bundle["digest"],"continuity":continuity,
            "counts":counts,"checks":checks,"holds":holds,"coverage":"recorded values in selected native families",
            "comparisonSha256":sha(encode([checks,holds]))}
