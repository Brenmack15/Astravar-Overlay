"""Validate supplemental catalog prerequisites and source-limited companion care mechanics."""
import re
from ascension_migrate_legacy import verify


def validate_care_projection(row, original_key, entries, targets):
    kind = row["kind"]
    if kind not in {"care_protocol", "item_definition"}:
        return
    source = entries[original_key]
    description = source.get("description", "")
    ref = source.get("source", {})
    values = row["values"]
    verify(original_key[1] == "playable.characters.features" and ref.get("acceptance") == "ACCEPTED_HOMEBREW"
           and not row["ruling"] and values.get("id") == row["targetId"], "Care requires a named original approved feature")
    if kind == "item_definition":
        name = values.get("title", "").removesuffix(" (catalog)")
        expected = {"id","title","category","source","description","rarity","stackable","wearable","handheld","attunement"}
        verify(set(values) == expected and row["actorId"] is None and name and name.lower().endswith("tools")
               and values["title"] == name + " (catalog)" and name.casefold() in description.casefold()
               and values["category"] == "Artisan tools" and values["source"] == description
               and values["rarity"] is None and values["handheld"] is True and values["wearable"] is False
               and values["stackable"] is False and values["attunement"] == "none",
               "Catalog-only care tool differs from its recorded requirement")
        return
    owner = original_key[2][0]
    ids = {name:targets.get(values.get(name), {}) for name in ("operatorId","spellId","toolDefinitionId","repairPoolId","reconstructionPoolId")}
    operator, spell, tool = ids["operatorId"], ids["spellId"], ids["toolDefinitionId"]
    rp, recon = ids["repairPoolId"], ids["reconstructionPoolId"]
    verify(set(values) == {"id","title","operatorId","spellId","toolDefinitionId","repairPoolId","reconstructionPoolId",
                           "repairDice","repairSides","repairSeconds","reconstructionSeconds","dormancySeconds","source"}
           and row["actorId"] == owner and targets.get(owner, {}).get("kind") == "actor"
           and operator.get("kind") == "actor" and operator["id"] != owner and operator["title"].split()[0].casefold() in description.casefold()
           and spell.get("kind") == "spell" and spell["actorId"] == operator["id"] and spell["title"].casefold() in description.casefold()
           and spell["data"].get("resolution") == "automatic" and spell["data"].get("components") == []
           and tool.get("kind") == "item_definition" and tool["title"].removesuffix(" (catalog)").casefold() in description.casefold()
           and rp.get("kind") == "resource_pool" and recon.get("kind") == "resource_pool" and rp["id"] != recon["id"]
           and rp["actorId"] == owner == recon["actorId"] and rp["data"]["recovery"] == "short_rest"
           and recon["data"]["recovery"] == "long_rest" and recon["data"].get("recoveryActorId") == operator["id"],
           "Care dependencies must use the recorded actor, spell, tool and separate recovery sources")
    minutes=re.search(r"\b([1-9][0-9]*) minutes?\b",description,re.I)
    cast_time=re.fullmatch(r"([1-9][0-9]*) minutes?",spell["data"].get("nativeCastingTime",""),re.I)
    same_actor=[r.get("description","") for (digest,family,identity),r in entries.items() if digest==original_key[0]
                and family=="playable.characters.features" and identity[0]==owner and r.get("source",{}).get("acceptance")=="ACCEPTED_HOMEBREW"]
    repairs=[m.groups() for text in same_actor if spell["title"].casefold() in text.casefold()
             for m in re.finditer(r"\brestores (\d+)d(\d+) HP on active\b",text,re.I)]
    verify(minutes and values["reconstructionSeconds"]==60*int(minutes[1]) and cast_time and values["repairSeconds"]==60*int(cast_time[1])
           and len(repairs)==1 and [values["repairDice"],values["repairSides"]]==list(map(int,repairs[0]))
           and values["dormancySeconds"]==60 and any("one minute to fold/unfold" in x.lower() for x in same_actor),
           "Care duration or healing dice lack explicit native support")
