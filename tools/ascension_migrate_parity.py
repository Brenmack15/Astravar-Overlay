"""Compare recorded native mechanics with independently validated, scoped canonical recovery rows."""
from ascension_migrate_legacy import COINS, encode, loads, read_limited, sha, source_families, verify


def auxiliary_source_parity(raw, bundle):
    """Read back archived source bytes and every normalized field attributed to a separate native format."""
    from ascension_migrate_reviewed import indexed, path_value, project
    digest = sha(raw)
    evidence = bundle["sources"].get(digest)
    checks, holds = [], []
    def compare(kind, identity, field, expected, actual, present):
        status = "absent" if not present else "different" if encode(expected)!=encode(actual) else "bothUnknown" if expected is None else "equal"
        checks.append({"kind":kind,"id":identity,"field":field,"native":expected,"cloud":actual,"status":status})
    compare("native_original", digest, "sha256", digest, sha(evidence[0]) if evidence else None, bool(evidence))
    if evidence and "reviewedMapping" in evidence[1]:
        plan = evidence[1]["reviewedMapping"]
        grouped = {key:bundle["sources"][key][0] for key in plan["sources"]}
        _, projected, _, _ = project(grouped, plan, bundle["tenant"])
        def rows(records):
            return {r["id"]:{"id":r["id"],"kind":r["kind"],"title":r["title"],"revision":r["revision"],
                             "data":loads(r["data_json"])} for r in records}
        expected = rows(projected)
        actual = rows(bundle["rows"]["cloud_entities"])
        _, original_rows = indexed(raw)
        for d in plan["decisions"]:
            if d["source"] != digest:
                continue
            family, identity = d["family"], tuple(d["identity"])
            compare(family, list(identity), "originalRowSha256", sha(encode(original_rows[family,identity])), d["rowSha256"], True)
            if d["disposition"] == "retain":
                holds.append({"kind":family,"id":list(identity),"reason":d["reason"]})
        for w in plan["parity"]:
            if w["source"] != digest:
                continue
            ident, path = w["target"], w["targetPath"]
            try:
                value, present = path_value(actual[ident],path), True
            except (KeyError, ValueError):
                value, present = None, False
            compare(expected[ident]["kind"], ident, path, path_value(expected[ident],path), value, present)
    else:
        holds.append({"kind":"source","id":digest,"reason":"A reviewed original is required to compare this source family."})
    counts = {status:sum(c["status"]==status for c in checks) for status in ("equal","bothUnknown","different","absent")}
    return {"sourceSha256":digest,"manifestSha256":bundle["digest"],"continuity":bundle["tenant"][1],
            "counts":counts,"checks":checks,"holds":holds,"coverage":"verified original rows and source-bound canonical fields",
            "comparisonSha256":sha(encode([checks,holds]))}


def mechanical_parity(source_path, bundle):
    raw = read_limited(source_path)
    if raw.startswith(b"PK\x03\x04"):
        return auxiliary_source_parity(raw, bundle)
    native = loads(raw)
    if native.get("format") not in ("astravar-connected-campaign", "astravar-campaign-items", "astravar-reforge"):
        return auxiliary_source_parity(raw, bundle)
    families = {name: records for name, _, records in source_families(native)}
    continuity = bundle["tenant"][1]
    entities = {r["id"]: (r, loads(r["data_json"])) for r in bundle["rows"]["cloud_entities"]}
    source_digest=sha(raw)
    aliases={}
    reviewed_actors=set()
    dispositions={}
    approved={}
    reviewed_holdings={}
    evidence=bundle["sources"].get(source_digest)
    if evidence and "reviewedMapping" in evidence[1]:
        plan=evidence[1]["reviewedMapping"]
        from ascension_migrate_reviewed import indexed as all_native_rows, path_value
        from ascension_migrate_attestations import FORMAT as ATTESTED_FORMAT
        _, indexed = all_native_rows(raw)
        attestations = {digest:all_native_rows(bundle["sources"][digest][0])[1]
                        for digest,fmt in plan["sources"].items() if fmt==ATTESTED_FORMAT}
        # check_database has already verified the independent pin and every grant.
        for grant in plan.get("reviewAuthority", {}).get("grants", []):
            if grant["op"] in ("correct","consumeRemainder") and grant["source"]==source_digest:
                approved[(grant["target"],grant["targetPath"][-1])] = grant
        for decision in plan["decisions"]:
            if decision["source"] == source_digest:
                dispositions[decision["family"],tuple(decision["identity"])]=decision
            if decision["disposition"]=="map" and decision["family"]=="review.attested_holdings":
                assertion=attestations[decision["source"]][decision["family"],tuple(decision["identity"])]
                original_id=assertion["accepted"].get("physical_identity") if assertion["accepted"].get("native_present") else assertion["accepted"].get("item_id")
                if original_id and original_id in decision["targets"]:
                    reviewed_holdings[original_id] = (decision, assertion)
            if decision["source"] == source_digest and decision["family"] == "playable.characters" and decision["disposition"] == "map" and decision["playerKnown"]:
                reviewed_actors.update(t for t in decision["targets"] if entities.get(t, ({},))[0].get("kind") == "actor")
        for w in plan["parity"]:
            if w["source"]==source_digest and w["targetPath"]==["id"] and w["transform"]=="scopedIdentity":
                original=path_value(indexed[(w["family"],tuple(w["identity"]))],w["sourcePath"])
                row,_=entities[w["target"]]
                aliases.setdefault((row["kind"],original),set()).add(w["target"])
            if w["source"]==source_digest and w["transform"]=="consumedLifecycle":
                grant=approved.get((w["target"],"quantity"))
                if grant and grant["op"]=="consumeRemainder":
                    original=indexed[w["family"],tuple(w["identity"])]
                    field=w["targetPath"][-1]
                    approved[w["target"],field]={**grant,"sourcePath":w["sourcePath"],"rowSha256":sha(encode(original)),
                        "acceptedPath":grant["lifecyclePath"],"before":path_value(original,w["sourcePath"]),
                        "after":False if field=="operating" else None}
    actors = {a["actorId"]: a for _, a in families.get("authority.actors", [])}
    checks, holds, reviewed_scope = [], [], []
    def excluded(family, identity):
        d=dispositions.get((family,tuple(identity)),{})
        return d if d.get("disposition") in ("excluded","foreign","fixture") else None
    def check(kind, identity, field, expected, actual, available=True, omitted=None):
        state = "absent" if not available else "different" if expected != actual else "bothUnknown" if expected is None else "equal"
        row = {"kind":kind, "id":identity, "field":field, "native":expected, "cloud":actual, "status":state}
        if omitted and not available:
            row.update(status="reviewedExclusion",disposition=omitted["disposition"],rowSha256=omitted["rowSha256"])
        candidates = {identity, *aliases.get(("resource_pool" if kind=="item_resource" else kind,identity),set())}
        for target_id in candidates:
            grant = approved.get((target_id,field))
            if (state=="different" and grant and grant["targetKind"]==("resource_pool" if kind=="item_resource" else kind)
                    and encode(expected)==encode(grant["before"]) and encode(actual)==encode(grant["after"])):
                row.update(status="approvedCorrection", decision=grant["decision"], rowSha256=grant["rowSha256"],
                           sourcePath=grant["sourcePath"], acceptedPath=grant["acceptedPath"])
                break
        if row["status"]=="different" and kind=="item_instance" and field=="definitionId":
            if actual in aliases.get(("item_definition",expected),set()) and entities.get(actual,({},{}))[0].get("kind")=="item_definition":
                row.update(status="scopedAlias",originalDefinitionId=expected,canonicalDefinitionId=actual)
        if row["status"]=="different" and kind=="item_instance" and identity in reviewed_holdings and field in (
                "ownerId","custodianId","locationId","holding","equipment","attuned","operating"):
            d, assertion = reviewed_holdings[identity]
            if field in assertion["fields"] and encode(assertion["fields"][field])==encode(actual):
                row.update(status="reviewedHolding",decision=assertion["decision"],attestationSha256=d["source"],
                           rowSha256=d["rowSha256"],sourcePath=["fields",field],acceptedPath=assertion["acceptedPath"])
        checks.append(row)
    def target(kind, identity, actor_id=None):
        proposed=aliases.get((kind,identity),set())
        canonical=next(iter(proposed)) if len(proposed)==1 else identity
        row, data = entities.get(canonical, ({}, {}))
        present = row.get("kind") == kind and (actor_id is None or row.get("actor_id") == actor_id)
        return data if present else {}, present
    def compare_fields(kind, identity, expected, data, present, omitted=None):
        for key, expected_value in expected.items():
            check(kind, identity, key, expected_value, data.get(key), present and key in data,omitted if not present else None)
    for _, wallet in families.get("records.wallets", []):
        identity = wallet["id"]
        if wallet.get("campaignId") != continuity:
            if excluded("records.wallets",[identity]):
                reviewed_scope.append({"family":"records.wallets","id":identity,"disposition":excluded("records.wallets",[identity])["disposition"]})
                continue
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
        if binding and (binding.get("campaignId") != continuity or not binding.get("playable")) or not binding and identity not in reviewed_actors:
            if excluded("playable.characters",[identity]):
                reviewed_scope.append({"family":"playable.characters","id":identity,"disposition":excluded("playable.characters",[identity])["disposition"]})
                continue
            holds.append({"kind":"actor","id":identity,"reason":"A playable Genesis binding or exact owner-reviewed actor mapping is required"})
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
        for item in char.get("items", []):
            item_id = item["id"]
            holding, found = target("item_instance", item_id)
            expected = {key:item[key] for key in ("attuned", "operating", "quantity", "definitionId") if key in item}
            if item.get("state") in ("WORN", "HELD", "CARRIED", "STORED"):
                expected["equipment"] = {"WORN":"worn", "HELD":"held", "CARRIED":"packed", "STORED":"packed"}[item["state"]]
            compare_fields("item_instance",item_id,expected,holding,found,excluded("playable.characters.items",[identity,item_id]))
            for pool in item.get("pools", []):
                candidates = {pool["id"], *aliases.get(("resource_pool",pool["id"]),set())}
                bound = {holding.get("resourcePoolId"), *holding.get("additionalResourcePoolIds", [])}
                matches = candidates & bound
                if len(matches) != 1:
                    holds.append({"kind":"item_resource","id":item_id,"pool":pool["id"],
                                  "reason":"The physical item does not identify this independently configured pool."})
                    continue
                chosen = next(iter(matches))
                scoped, pool_data = entities.get(chosen, ({},{}))
                compare_fields("item_resource",chosen, {key:pool.get(key) for key in ("current","maximum")},
                               pool_data, scoped.get("kind")=="resource_pool" and scoped.get("actor_id")==identity)
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
            if excluded("authority.items",[identity]):
                reviewed_scope.append({"family":"authority.items","id":identity,"disposition":excluded("authority.items",[identity])["disposition"]})
                continue
            holds.append({"kind":"item_instance","id":identity,"reason":"Unproven owner or continuity"})
            continue
        instance = loads(ownership["instanceJson"])
        if instance.get("id") != identity:
            holds.append({"kind":"item_instance","id":identity,"reason":"Native physical identity conflicts with its authority record"})
            continue
        data,present=target("item_instance",identity)
        expected={"ownerId":owner}
        expected.update({k:instance[k] for k in ("quantity","definitionId","attuned") if k in instance})
        compare_fields("item_instance",identity,expected,data,present,excluded("authority.items",[identity]))
    counts={status:sum(c["status"]==status for c in checks) for status in ("equal","bothUnknown","different","absent","approvedCorrection","reviewedHolding","scopedAlias","reviewedExclusion")}
    return {"sourceSha256":sha(raw), "manifestSha256":bundle["digest"],"continuity":continuity,
            "counts":counts,"checks":checks,"holds":holds,"reviewedScope":reviewed_scope,"coverage":"recorded values in selected native families",
            "comparisonSha256":sha(encode([checks,holds,reviewed_scope]))}
