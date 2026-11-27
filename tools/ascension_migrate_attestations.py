"""Verify a privately reviewed historical holding or scene against two independent inputs."""
import base64

from ascension_migrate_legacy import IDENTITY, encode, loads, sha, verify
from ascension_migrate_documents import FORMAT as DOCUMENT_FORMAT, FAMILY as DOCUMENT_FAMILY

FORMAT = "astravar-attested-history"
FAMILIES = {"holding": "review.attested_holdings", "checkpoint": "review.attested_checkpoints"}


def index_attestations(doc):
    verify(set(doc) == {"format","version","owner","continuity","records"} and
           type(doc["version"]) is int and doc["version"] == 1 and isinstance(doc["records"],list)
           and 0 < len(doc["records"]) <= 10000, "Invalid attested history envelope")
    rows = {}
    for row in doc["records"]:
        verify(isinstance(row,dict) and set(row) == {"id","category","decision","acceptedPath","accepted","fields","proof"} and
               isinstance(row["id"],str) and IDENTITY.fullmatch(row["id"]) and row["category"] in FAMILIES and
               isinstance(row["accepted"],dict) and isinstance(row["fields"],dict) and isinstance(row["proof"],dict),
               "Invalid attested record")
        key = FAMILIES[row["category"]], (row["id"],)
        verify(key not in rows, "Duplicate historical subject")
        rows[key] = row
    return FORMAT, rows


def verify_attestations(raws, formats, authority, entries, tenant, source_key, path_value):
    inputs = {digest for digest, fmt in formats.items() if fmt == FORMAT}
    expected = authority.get("attestedSources", []) if isinstance(authority,dict) else []
    verify(isinstance(expected,list) and len(expected) == len(set(expected)) and inputs == set(expected),
           "Attested sources lack independent review")
    if not inputs:
        return
    documents = {}
    for digest, fmt in formats.items():
        if fmt == DOCUMENT_FORMAT:
            for doc in loads(raws[digest])["documents"]:
                documents[digest,DOCUMENT_FAMILY,(doc["sourceId"],doc["versionId"])]=doc
    subjects = set()
    for key, row in entries.items():
        if key[0] not in inputs:
            continue
        subject = key[1:]
        verify(subject not in subjects, "Duplicate historical subject across inputs")
        subjects.add(subject)
        decision = authority["decisions"].get(row["decision"])
        verify(isinstance(decision,dict) and decision.get("authority") and decision.get("decision_id") == row["decision"]
               and (decision.get("verbatim") or decision.get("verbatim_messages")) and
               encode(path_value(decision,row["acceptedPath"])) == encode(row["accepted"]),
               "Historical assertion differs from the accepted decision")
        proof = row["proof"]
        original = source_key(proof)
        verify(original in entries and original[0] not in inputs and proof.get("rowSha256") == sha(encode(entries[original])),
               "Historical assertion lacks original source row")
        if original in documents:
            verify(set(proof) == {"source","family","identity","rowSha256","line","lineSha256"} and
                   type(proof["line"]) is int and proof["line"] > 0, "Invalid historical line reference")
            lines = base64.b64decode(documents[original]["base64"],validate=True).splitlines()
            verify(proof["line"] <= len(lines) and sha(lines[proof["line"]-1]) == proof["lineSha256"],
                   "Historical line differs from original bytes")
            line = lines[proof["line"]-1].decode("utf-8").casefold()
            term = row["accepted"].get("title") if row["category"] == "holding" else row["accepted"].get("pending_choice")
            verify(isinstance(term,str) and " ".join(term.split()[:3]).casefold() in line,
                   "Historical line does not attest to the identified subject")
        else:
            verify(set(proof) == {"source","family","identity","rowSha256"} and
                   original[1] in ("reference.records","campaign.items") and
                   entries[original].get("visibility") not in ("hidden","dm","private"),
                   "Historical assertion lacks an eligible player-known original")
        if row["category"] == "holding":
            accepted, data = row["accepted"],row["fields"]
            native_id = accepted.get("physical_identity") if accepted.get("native_present") else accepted.get("item_id")
            original_title = entries[original].get("title") if original[1] == "campaign.items" else None
            title = accepted.get("title", original_title)
            verify(isinstance(title,str) and data.get("title") == title and
                   type(data.get("quantity")) is int and data["quantity"] == accepted.get("quantity") and data["quantity"]>0,
                   "Historical holding title or quantity differs from the decision")
            if original[1] == "campaign.items":
                verify(native_id == original[2][0]
                       and row["id"] == original[2][0], "Historical holding cannot replace a different native item")
            if original[1] == "reference.records":
                verify(row["id"] == original[2][0] and row["id"] in
                       (accepted.get("notion_source_id"),accepted.get("source_page_id")),
                       "Attested holding does not match original page identity")
            if not native_id:
                verify(not any(family == "campaign.items" and r.get("title","").casefold() == title.casefold()
                               for (_,family,_),r in entries.items()), "Historical holding may duplicate an existing native item")
            for field, reviewed in (("locationId","reviewed_location_id"),("equipment","reviewed_equipment"),
                                    ("attuned","reviewed_attuned"),("operating","reviewed_operating"),("holding","reviewed_holding")):
                if reviewed in accepted:
                    verify(encode(data.get(field)) == encode(accepted[reviewed]), "Historical holding state contradicts owner decision")


def verify_installed_form(form, instance, key, entries, decisions, plan, source_key):
    operator=instance["data"].get("installedOnActorId")
    verify(operator and form["actorId"]==operator==instance["data"]["custodianId"]==instance["data"]["ownerId"] and
           instance["data"]["holding"]=="installed" and form["data"].get("sourceItemId")==instance["id"] and
           form["data"].get("requiresOperating") is True,
           "A sourced installed form needs its own actor's operating physical component")
    ws=[w for w in plan["parity"] if isinstance(w,dict) and w.get("target")==form["id"]]
    attached=[w for w in ws if source_key(w)==key]
    verify(len(attached)==1 and attached[0]["sourcePath"]==["id"] and attached[0]["targetPath"]==["data","sourceItemId"] and
           attached[0]["transform"] in ("canonicalRef","exact"), "Installed evidence may only witness the form's physical source")
    edges=[e for e in plan["links"] if isinstance(e,dict) and e.get("from")==form["id"] and source_key(e)==key]
    verify(len(edges)==1 and edges[0]["relation"]=="requires_item" and edges[0]["to"]==instance["id"] and
           edges[0]["targetPath"]==["id"], "Installed form requires its original physical source edge")
    ids=[w for w in ws if w["targetPath"]==["id"] and w["sourcePath"]==["id"]]
    verify(len(ids)==1 and ids[0]["transform"] in ("exact","scopedIdentity"), "Installed form lacks an independent native feature")
    original=source_key(ids[0])
    native=entries.get(original,{})
    verify(original[1]=="playable.characters.features" and len(original[2])==2 and original[2][0]==operator and
           native.get("name")==form["title"] and instance["title"].casefold() in (
               form["title"].casefold(),form["title"].casefold()+" chassis") and
           native.get("source",{}).get("acceptance") in ("OFFICIAL","ACCEPTED_HOMEBREW") and
           decisions[original]["disposition"]=="map" and any(source_key(w)==original and
               w["sourcePath"]==["source","name"] and w["targetPath"]==["data","source"] and w["transform"]=="exact" for w in ws),
           "The installed component and accepted native form lack matching original mechanics")


def verify_attested_targets(decisions, entries, targets, tenant, scoped_identity, plan, source_key):
    assigned = set()
    for key, d in decisions.items():
        if key[1] not in FAMILIES.values() or d["disposition"] != "map":
            continue
        row = entries[key]
        category = row["category"]
        allowed = {"checkpoint"} if category == "checkpoint" else {"item_definition","item_instance","form"}
        ids = set(d["targets"])
        verify(ids and all(targets[i]["kind"] in allowed for i in ids), "Historical evidence targets unrelated gameplay")
        if category == "checkpoint":
            verify(len(ids) == 1 and ids == {scoped_identity(tenant,key[1],key[2],"checkpoint",row["id"])},
                   "Historical checkpoint needs a unique qualified identity")
            continue
        instances = [targets[i] for i in ids if targets[i]["kind"] == "item_instance"]
        verify(len(instances) == 1, "A historical holding requires exactly one physical instance")
        instance = instances[0]
        native = row["accepted"].get("physical_identity") if row["accepted"].get("native_present") else row["accepted"].get("item_id")
        expected_id = native or scoped_identity(tenant,key[1],key[2],"item_instance",row["id"])
        verify(instance["id"] == expected_id and instance["id"] not in assigned and instance["title"]==row["fields"]["title"]
               and instance["data"]["quantity"] == row["accepted"]["quantity"],
               "A historical holding duplicates or changes its physical identity")
        assigned.add(instance["id"])
        for name in ("ownerId","custodianId","locationId","holding","equipment","attuned","operating","covenantLocked","installedOnActorId","containerSourceId","parentArmorId"):
            verify(encode(instance["data"].get(name)) == encode(row["fields"].get(name)), "Historical holding state differs from review")
        if not native:
            verify(instance["data"]["definitionId"] == scoped_identity(tenant,key[1],key[2],"item_definition",row["id"]),
                   "New historical item refers to another catalog definition")
        forms=[targets[i] for i in ids if targets[i]["kind"]=="form"]
        verify(len(forms)<=1 and ids-{f["id"] for f in forms} <= {instance["id"],instance["data"]["definitionId"]},
               "Historical holding names another definition or unrelated forms")
        for form in forms:
            verify_installed_form(form,instance,key,entries,decisions,plan,source_key)
