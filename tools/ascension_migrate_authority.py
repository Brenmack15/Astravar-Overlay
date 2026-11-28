"""Verify independently pinned, scoped exceptions to original-value migration parity."""
import os
from collections import defaultdict

from ascension_migrate_legacy import COINS, encode, sha, verify
from ascension_migrate_funds import confirmed_funds
from ascension_migrate_provenance import restored_component

BINDING = {"source", "family", "identity", "rowSha256"}
GRANT = BINDING | {"op", "decision", "bindingPath"}
PIN = "ASTRAVAR_REVIEW_AUTHORITY_SHA256"


def allowed_field(kind, family, source, target):
    if kind == "wallet" and family == "records.wallets":
        return len(target) == 3 and target[:2] == ["data", "coins"] and target[2] in COINS and source == [COINS[target[2]]]
    if kind == "resource_pool":
        return (family in ("playable.characters.resources", "playable.characters.items.pools")
                and source == ["current"] and target == ["data", "current"])
    if kind == "item_instance":
        return (family in ("campaign.items", "playable.characters.items")
                and source == ["quantity"] and target == ["data", "quantity"])
    return False


def reviewed_grants(authority, tenant, entries, path_value, source_key):
    if authority is None:
        return {}, {}
    verify(isinstance(authority, dict) and set(authority)-{"supplements","historicalProjections"} in ({"version", "owner", "continuity", "decisions", "grants"},
                   {"version", "owner", "continuity", "decisions", "grants", "attestedSources"})
           and type(authority["version"]) is int and authority["version"] == 1
           and (authority["owner"], authority["continuity"]) == tenant, "Wrong owner, continuity or review authority version")
    verify(sha(encode(authority)) == os.environ.get(PIN), "Independent review authority digest is missing or does not match")
    decisions, grants = authority["decisions"], authority["grants"]
    verify(isinstance(decisions, dict) and isinstance(grants, list) and 0 < len(grants) <= 10000,
           "Invalid reviewed authority grants")
    corrections, exclusions = {}, {}
    for g in grants:
        verify(isinstance(g, dict) and g.get("op") in ("correct", "exclude", "confirmSlotCapacity", "confirmedFunds", "consumeRemainder", "restoreAttackComponent"), "Unsupported reviewed operation")
        fields = GRANT if g["op"] == "exclude" else GRANT | {
            "sourcePath", "target", "targetKind", "targetPath", "before", "after", "acceptedPath"}
        if g["op"] == "consumeRemainder":
            fields.add("lifecyclePath")
        verify(set(g) == fields and isinstance(g["decision"], str) and g["decision"] in decisions,
               "Missing decision or invalid grant shape")
        doc = decisions[g["decision"]]
        verify(isinstance(doc, dict) and doc.get("decision_id") == g["decision"] and
               (doc.get("verbatim") or doc.get("verbatim_messages")) and doc.get("authority"),
               "Missing owner decision evidence")
        key = source_key(g)
        binding = path_value(doc, g["bindingPath"])
        verify(key in entries and isinstance(binding, dict) and all(binding.get(k) == g[k] for k in BINDING)
               and sha(encode(entries[key])) == g["rowSha256"], "Owner decision does not bind the original row")
        if g["op"] == "exclude":
            verify(key not in exclusions, "Duplicate exclusion permission")
            exclusions[key] = g
            continue
        capacity = g["op"] == "confirmSlotCapacity"
        if g["op"] == "restoreAttackComponent":
            restored_component(g, doc, entries, key, path_value, source_key)
        elif g["op"] == "confirmedFunds":
            confirmed_funds(g, doc, entries[key], path_value, tenant)
        else:
            if capacity:
                verify(key[1] == "playable.characters.spellSlots" and g["targetKind"] == "resource_pool" and
                       g["sourcePath"] == ["value"] and g["targetPath"] == ["data","maximum"] and
                       g["acceptedPath"][:-1] == g["bindingPath"][:-1] and g["acceptedPath"][-1:] == ["maximum"],
                       "Slot capacity needs an independently reviewed maximum for the same slot")
            else:
                verify(allowed_field(g["targetKind"], key[1], g["sourcePath"], g["targetPath"]),
                       "Correction is outside allowed mechanical fields")
            verify(isinstance(g["target"], str) and type(g["after"]) is int and g["after"] >= 0 and
                   (g["before"] is None or type(g["before"]) is int) and (capacity or g["after"] != g["before"]) and
                   encode(path_value(entries[key], g["sourcePath"])) == encode(g["before"]) and
                   encode(path_value(doc, g["acceptedPath"])) == encode(g["after"]),
                   "Correction lacks exact original and accepted values")
        if g["op"] == "consumeRemainder":
            lifecycle = path_value(doc, g["lifecyclePath"])
            verify(g["targetKind"] == "item_instance" and g["targetPath"] == ["data","quantity"] and
                   type(g["before"]) is int and g["before"] > 0 and g["after"] == 0 and
                   g["acceptedPath"][:-1] == g["lifecyclePath"][:-1] and isinstance(lifecycle,str)
                   and lifecycle.casefold().split()[:1] == ["consumed"], "Missing approved consumption lifecycle")
        pair = g["target"], tuple(g["targetPath"])
        verify(pair not in corrections, "Duplicate field correction")
        corrections[pair] = g
    return corrections, exclusions


def fixture_memberships(entries):
    actors, records, item_actors = defaultdict(set), defaultdict(set), defaultdict(set)
    for (digest, family, ids), row in entries.items():
        if family == "playable.characters.items":
            item_actors[digest,ids[-1]].add(ids[0])
        if family == "records.placements" and row.get("holderProfileId"):
            item_actors[digest,ids[0]].add(row["holderProfileId"])
        if family == "authority.items" and row.get("ownerActorId"):
            item_actors[digest,ids[0]].add(row["ownerActorId"])
        campaign = row.get("campaignId")
        if not campaign:
            continue
        if family in ("authority.actors", "records.partySheets"):
            actors[digest, ids[0]].add(campaign)
        if family == "records.externalReferences":
            records[digest, row.get("recordId")].add(campaign)
    return actors, records, item_actors


def fixture_only(key, row, tenant, memberships):
    """A reserved source tag excludes a fixture; it never supplies a foreign tenant ID."""
    digest, family, ids = key
    actors, refs, item_actors = memberships
    row_campaign = row.get("campaignId")
    if tenant[1] in refs.get((digest, ids[0]), ()) or row_campaign == tenant[1]:
        return False
    if family == "playable.characters" or family.startswith("playable.characters."):
        campaigns = actors.get((digest, ids[0]), set())
        return len(campaigns) == 1 and tenant[1] not in campaigns
    if family not in ("campaign.items",) and not family.startswith("records."):
        return False
    # Local inventory or actor ownership takes precedence over a fixture label.
    ids_to_check = {row.get(k) for k in ("profileId","holderProfileId","companionId","ownerActorId")}
    if family == "records.characterProfiles":
        ids_to_check.add(ids[0])
    item = ids[0] if family == "campaign.items" else row.get("itemId")
    ids_to_check.update(item_actors.get((digest,item), ()))
    if any(tenant[1] in actors.get((digest,actor), ()) for actor in ids_to_check):
        return False
    source = row.get("source")
    return isinstance(source, str) and source.partition(":")[0].strip().casefold() == "synthetic_qa_only"


def archived_by(key, old, ref, entries, formats, source_key):
    other = source_key(ref)
    verify(set(ref) == BINDING and other in entries and key[0] != other[0] and
           formats[key[0]] == "astravar-campaign-archive" and formats[other[0]] == "astravar-connected-campaign" and
           key[1] == "reference." + other[1] and key[2] == other[2] and
           other[1].startswith(("records.", "campaign.")), "Historical projection lacks the corresponding native identity")
    new = entries[other]
    verify(sha(encode(new)) == ref["rowSha256"], "Historical projection replacement row changed")
    preserved = all(k in new and encode(new[k]) == encode(v) for k, v in old.items())
    revised = (type(new.get("revision")) is int and type(old.get("revision", 1)) is int
               and new["revision"] > old.get("revision", 1) and type(old.get("updatedAt")) is int
               and type(new.get("updatedAt")) is int and new["updatedAt"] > old["updatedAt"])
    verify(preserved or revised, "Historical projection has unsupported content changes")
    return other

def consumed_state(grants, target, key, source, dest):
    grant = grants.get((target["id"], ("data","quantity")))
    d = target["data"]
    verify(grant is not None and grant["op"] == "consumeRemainder" and grant["source"] == key[0] and
           grant["identity"][-1] == key[2][-1] and target["kind"] == "item_instance" and target["life"] == "archived" and
           d["quantity"] == 0 and d["holding"] == "consumed" and d["equipment"] == "packed" and
           not d["partyAccess"] and not d["attuned"] and not d["operating"] and
           d["custodianId"] is None and d["locationId"] is None, "Unsupported consumed physical holding")
    if (key[1], source, dest) == ("playable.characters.items", ["operating"], ["data","operating"]):
        return False
    verify((key[1],source) in (("playable.characters.items",["state"]),("records.placements",["holderProfileId"])) and
           dest == ["data","custodianId"], "Consumption cannot transform this field")
    return None
