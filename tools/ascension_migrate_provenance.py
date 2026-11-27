"""Recover previously imported transfer and attack provenance from exact native snapshots."""
from datetime import date
from ascension_migrate_legacy import encode, loads, sha, verify


def historical_transfer(transfer, connected, tenant):
    profile = transfer["character"].get("targetProfileId")
    captured = date.fromisoformat(transfer["capturedDate"])
    stamp = connected.get("exportedAt")
    boundary = (captured - date(1970,1,1)).days * 86400000 + 86400000
    actor_rows = [r for r in connected["authority"]["actors"] if r["actorId"]==profile]
    if (len(actor_rows)!=1 or actor_rows[0].get("campaignId")!=tenant[1] or not actor_rows[0].get("playable")
            or type(stamp) is not int or stamp < boundary):
        return False
    references = connected["campaign"]["records"].get("externalReferences",[])
    source = "Notion private transfer " + transfer["transferId"] + " · " + transfer["character"]["sourceUrl"]
    witnesses = [r for r in references if r.get("namespace")=="notion" and r.get("source")==source and r.get("campaignId")==tenant[1]]
    inventory = {r["sourceId"] for r in transfer["inventory"]}
    return (any(r["profileId"]==profile for r in connected["playable"]["characters"]) and bool(witnesses) and
            inventory <= {r["externalId"] for r in witnesses})


def restored_component(grant, doc, entries, key, path_value, source_key):
    path=grant["sourcePath"]
    verify(key[1]=="playable.characters.attacks" and grant["targetKind"]=="attack" and
           len(path)==3 and path[0]=="damage" and type(path[1]) is int and path[1]>=0 and path[2] in ("type","label") and
           grant["targetPath"]==["data","components",path[1],"type" if path[2]=="type" else "name"] and
           isinstance(grant["before"],str) and isinstance(grant["after"],str) and grant["after"]!=grant["before"],
           "Unsupported component restoration")
    accepted=grant["acceptedPath"]
    verify(isinstance(accepted,list) and len(accepted)==3 and accepted[0]=="field_corrections" and accepted[-1]=="accepted",
           "Unbound accepted component path")
    edit=path_value(doc,accepted[:-1])
    verify(edit.get("path")==path and edit.get("native")==grant["before"] and edit.get("accepted")==grant["after"] and
           path_value(entries[key],path)==grant["before"], "Owner correction disagrees with the original component")
    binding=doc.get("historical_edit_binding")
    old=source_key(binding)
    verify(old in entries and old[0]==key[0] and old[1]=="playable.changes" and
           sha(encode(entries[old]))==binding.get("rowSha256") and entries[old].get("profileId")==key[2][0] and
           entries[key].get("id")==key[2][1], "Component correction lacks the original historical edit")
    for field, expected in (("beforeJson",grant["after"]),("afterJson",grant["before"])):
        character=loads(entries[old][field])["character"]
        matches=[a for a in character["attacks"] if a.get("id")==key[2][1]]
        verify(character.get("id")==key[2][0] and len(matches)==1 and path_value(matches[0],path)==expected and
               matches[0]["damage"][path[1]]["id"]==entries[key]["damage"][path[1]]["id"],
               "Recorded edit belongs to another actor, attack or damage component")
