"""Bind a previously unrecorded holding component to an authenticated decision and exact actor."""
from ascension_migrate_legacy import COINS, encode, verify


def confirmed_funds(grant, decision, actor, path_value, tenant):
    path = grant["targetPath"]
    verify(isinstance(path, list) and len(path) == 3 and path[:2] == ["data", "coins"] and
           isinstance(path[2], str) and path[2] in COINS, "Invalid confirmed denomination")
    coin = path[2]
    accepted = grant["acceptedPath"]
    verify(isinstance(accepted, list) and len(accepted) >= 2 and accepted[-1] == "confirmed_held_" + coin,
           "Balance needs the accepted holding in its original denomination")
    component = path_value(decision, accepted[:-1])
    verify(isinstance(component, dict) and component.get("actor_id") == actor.get("actorId") and
           actor.get("campaignId") == tenant[1] and grant["family"] == "authority.actors" and
           grant["identity"] == [actor.get("actorId")] and grant["targetKind"] == "wallet" and
           grant["sourcePath"] == ["actorId"] and grant["before"] is None and
           type(grant["after"]) is int and grant["after"] >= 0 and
           encode(grant["after"]) == encode(component.get(accepted[-1])),
           "Accepted fund component lacks the scoped actor, decision or amount")


def validate_fund_target(grant, wallet, scoped_id, entries):
    actor_id = grant["identity"][0]
    data = wallet["data"]
    verify(wallet["id"] == scoped_id and wallet["actorId"] == actor_id and data["holderId"] == actor_id and
           data["shared"] is False and data.get("confirmedComponentOnly") is True,
           "Accepted funds require the actor's distinct component wallet")
    verify(not any(family == "records.wallets" and row.get("holderProfileId") == actor_id and
                   row.get("campaignId") in (None, entries[grant["source"], grant["family"], (actor_id,)]["campaignId"])
                   for (_, family, _), row in entries.items()), "Cannot duplicate a native wallet")
