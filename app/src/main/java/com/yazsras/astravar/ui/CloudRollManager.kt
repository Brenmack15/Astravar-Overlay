package com.yazsras.astravar.ui

import androidx.compose.runtime.*
import com.yazsras.astravar.data.CloudCampaignClient
import com.yazsras.astravar.data.CloudSnapshot
import com.yazsras.astravar.data.cloudText
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

private val cloudCheckNames = mapOf(RollKind.SKILL to "skill", RollKind.SAVE to "save", RollKind.ABILITY_CHECK to "ability",
    RollKind.INITIATIVE to "initiative", RollKind.CUSTOM to "custom")
private fun JsonObject.field(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun rollSubjectLabel(kind: RollKind, key: String, s: JsonObject?): String = when (kind) {
    RollKind.INITIATIVE -> "Initiative"
    RollKind.SAVE -> "${Stat.valueOf(key).fullLabel} saving throw"
    RollKind.ABILITY_CHECK -> "${Stat.valueOf(key).fullLabel} check"
    else -> (s?.get("skills") as? JsonObject)?.get(key)?.jsonObject?.field("name") ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
private fun rollValue(s: JsonObject, c: QueuedCheck): Int? {
    val values = when (c.kind) { RollKind.SKILL -> "skills"; RollKind.SAVE -> "saves"; RollKind.ABILITY_CHECK -> "checks"; else -> null }
    val number = if (values == null) s["initiative"] as? JsonObject else (s[values] as? JsonObject)?.get(c.key) as? JsonObject
    return number?.get("value")?.jsonPrimitive?.intOrNull
}
private fun cloudCheckArgs(c: QueuedCheck, workshop: Boolean): JsonObject = buildJsonObject {
    put("kind", cloudCheckNames.getValue(c.kind)); put("key", c.key); put("mode", c.mode.name.lowercase())
    if (workshop) put("contextTags", buildJsonArray { add("pocket-workshop") })
    if (c.kind == RollKind.CUSTOM) {
        val formula = SecureDice.parse(requireNotNull(c.customFormula))
        put("name", c.label); put("flat", formula.flat)
        put("terms", buildJsonArray { formula.dice.forEach { term -> add(buildJsonObject {
            put("count", term.count); put("sides", term.sides); put("sign", if (term.sign < 0) "subtract" else "add")
        }) } })
    }
}
private fun readCloudPreset(row: JsonObject, sheet: JsonObject?): RollPreset {
    var workshop = false
    val existing = mutableSetOf<Pair<RollKind, String>>()
    val entries = row.getValue("data").jsonObject.getValue("entries").jsonArray.map { input ->
        val c = input.jsonObject
        val kind = cloudCheckNames.entries.single { it.value == c.field("kind") }.key
        val key = requireNotNull(c.field("key"))
        val tags = c["contextTags"] as? JsonArray
        workshop = workshop || tags.orEmpty().any { it.jsonPrimitive.content == "pocket-workshop" }
        require(tags.orEmpty().all { it.jsonPrimitive.content == "pocket-workshop" }) { "Preset uses a context this editor cannot represent." }
        val mode = DiceMode.valueOf(requireNotNull(c.field("mode")).uppercase())
        val formula = if (kind == RollKind.CUSTOM) {
            val terms = (c["terms"] as? JsonArray)?.map { raw -> val t = raw.jsonObject
                DiceTerm(t.getValue("count").jsonPrimitive.int, t.getValue("sides").jsonPrimitive.int, if (t.field("sign") == "subtract") -1 else 1)
            } ?: listOf(DiceTerm(c.getValue("dice").jsonPrimitive.int, c.getValue("sides").jsonPrimitive.int))
            SecureDice.format(DiceFormula(terms, c["flat"]?.jsonPrimitive?.int ?: 0))
        } else null
        QueuedCheck(kind, key, c.field("name") ?: rollSubjectLabel(kind, key, sheet), mode, formula, existing.add(kind to key))
    }
    // A per-roll special circumstance cannot silently spread to other entries during an edit.
    require(!workshop || row.getValue("data").jsonObject.getValue("entries").jsonArray.all { c ->
        (c.jsonObject["contextTags"] as? JsonArray).orEmpty().any { it.jsonPrimitive.content == "pocket-workshop" }
    }) { "This preset has individual context restrictions." }
    return RollPreset(requireNotNull(row.field("id")), requireNotNull(row.field("title")), entries, workshop)
}
private suspend fun CloudCampaignClient.rollRecordPages(actorId: String, kind: String): List<JsonObject> {
    val rows = mutableListOf<JsonObject>()
    var after = ""
    do {
        val page = records(after, setOf(kind), actorId)
        rows.addAll(page)
        after = page.lastOrNull()?.field("id") ?: break
    } while (page.size == 50)
    return rows
}
private fun rollStatus(result: JsonObject): String {
    val status = result.field("status") ?: error("Command status unknown. Inspect pending commands.")
    return if (status == "COMMITTED") "Committed and verified" else status.lowercase().replace('_', ' ')
}

private fun confirmedPresetStatus(result: JsonObject): String {
    val message = rollStatus(result)
    check(result.field("status") == "COMMITTED") { message }
    return message
}

private fun checkEvidence(result: DiceResult): JsonObject = buildJsonObject {
    put("origin", if (result.manual) "player_physical" else "app")
    put("faces", buildJsonArray { result.faces.forEach { add(it.face) } })
}

private suspend fun CloudCampaignClient.changePreset(snapshot: CloudSnapshot, operation: String, args: JsonObject): String {
    val prior = pending().firstOrNull { it.field("operation") == operation && it["args"] == args &&
        it["epoch"]?.jsonPrimitive?.longOrNull == snapshot.version.epoch }
    val response = if (prior == null) submit(operation, args, snapshot) else retry(requireNotNull(prior.field("commandId")))
    return confirmedPresetStatus(response)
}

private suspend fun CloudCampaignClient.retirePreset(snapshot: CloudSnapshot, id: String, permanent: Boolean): String {
    val row = requireNotNull(record(id)) { "The preset is no longer active." }
    return changePreset(snapshot, if (permanent) "permanently_delete_records" else "archive_records", buildJsonObject {
        putJsonArray("records") { add(buildJsonObject { put("kind", "roll_preset"); put("id", id); put("revision", row.getValue("revision")) }) }
        if (permanent) { put("confirmation", "PERMANENT_DELETE"); put("acknowledgeExternalBackups", true) }
    })
}

@Composable internal fun CloudRollManager(client: CloudCampaignClient, snapshot: CloudSnapshot, actorId: String,
    online: Boolean, onDismiss: () -> Unit) {
    var sheet by remember(actorId, snapshot.binding) { mutableStateOf<JsonObject?>(null) }
    var presets by remember(actorId, snapshot.binding) { mutableStateOf<List<RollPreset>>(emptyList()) }
    var history by remember(actorId, snapshot.binding) { mutableStateOf<List<String>>(emptyList()) }
    var loadedAt by remember(actorId, snapshot.binding) { mutableStateOf<String?>(null) }
    var loadError by remember(actorId, snapshot.binding) { mutableStateOf("") }
    val revision = "${snapshot.version.epoch}.${snapshot.version.revision}.${snapshot.version.grantRevision}"
    LaunchedEffect(client, actorId, snapshot, online) {
        loadedAt = null; loadError = ""
        try {
            val fresh = if (online) client.projection(snapshot, "sheet/$actorId").getValue("sheet").jsonObject else null
            sheet = fresh
            val configs = client.rollRecordPages(actorId, "roll_preset")
            val decoded = configs.map { runCatching { readCloudPreset(it, fresh) } }
            presets = decoded.mapNotNull { it.getOrNull() }
            val saved = client.actorHistory(actorId, setOf("roll"), limit = 30, reviewed = snapshot)
            history = saved.mapNotNull { it["data"]?.jsonObject?.field("text") }
            if (decoded.any { it.isFailure }) loadError = "Some saved presets use contextual rules that require the structured preset inspector."
            if (online) loadedAt = revision else loadError = "Offline · previously cached roll records"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { sheet = null; presets = emptyList(); history = emptyList(); loadError = failure.message ?: "Unable to read authoritative roll options." }
    }
    val options = remember(sheet) { buildList {
        val s = sheet ?: return@buildList
        fun row(kind: RollKind, key: String) {
            val c = QueuedCheck(kind, key, rollSubjectLabel(kind, key, s)); add(ManagerOption(c, rollValue(s, c)))
        }
        (s["skills"] as? JsonObject)?.keys?.forEach { row(RollKind.SKILL, it) }
        Stat.entries.forEach { row(RollKind.SAVE, it.name); row(RollKind.ABILITY_CHECK, it.name) }
        row(RollKind.INITIATIVE, "initiative")
    } }
    val workshop = (sheet?.get("contextTags") as? JsonArray).orEmpty().any { it.jsonPrimitive.content == "pocket-workshop" }
    RollManagerPanel(actorId, revision, options, presets, history, editable = online && loadedAt == revision,
        workshopEligible = workshop, onDismiss = onDismiss, information = loadError,
        applicableAids = { check ->
            (sheet?.get("rollAids") as? JsonArray).orEmpty().map { it.jsonObject }.filter { aid ->
                aid.getValue("applies").jsonArray.any { it.jsonPrimitive.content == cloudCheckNames[check.kind] }
            }.map { aid -> ManagerAid(aid.cloudText("family"), aid.cloudText("castId"), aid.getValue("sides").jsonPrimitive.int) }
        },
        prepareEvidence = { check, result, physical ->
            castAidEvidence(checkEvidence(result), sheet?.get("rollAids") as? JsonArray, cloudCheckNames.getValue(check.kind), physical)
        },
        resolve = { entry, useWorkshop ->
            if (entry.kind == RollKind.CUSTOM) ManagerFormula(SecureDice.parse(requireNotNull(entry.customFormula)), entry.mode)
            else {
                val normal = requireNotNull(sheet) { "Current calculation unavailable." }
                val s = if (useWorkshop) requireNotNull((normal["rollContexts"] as? JsonObject)?.get("pocket-workshop") as? JsonObject) { "Workshop context unverified." } else normal
                val subject = "${cloudCheckNames.getValue(entry.kind)}:${entry.key}"
                (s["rollRestrictions"] as? JsonObject)?.field(subject)?.let { reason -> error(reason.lowercase().replace('_', ' ')) }
                val modeTable = s.getValue("rollModes").jsonObject.getValue(subject).jsonObject
                val policy = modeTable.getValue(entry.mode.name.lowercase()).jsonObject
                ManagerFormula(DiceFormula(listOf(DiceTerm(1, 20)), requireNotNull(rollValue(s, entry)) { "Current modifier unknown." }),
                    DiceMode.valueOf(requireNotNull(policy.field("mode")).uppercase()), policy.getValue("count").jsonPrimitive.int)
            }
        }, commit = { batch ->
            val already = client.record(batch.id)
            val pending = if (already == null) client.pending().firstOrNull { it.field("operation") in setOf("record_roll_batch", "execute_roll_preset") && (it["args"] as? JsonObject)?.field("batchId") == batch.id } else null
            val result = when {
                already != null -> null
                pending != null -> client.retry(requireNotNull(pending.field("commandId")))
                else -> {
                    require(batch.revision == revision) { "The reviewed revision changed. Inspect the pending command or review a new batch." }
                    val usesPreset = batch.presetId != null
                    client.submit(if (usesPreset) "execute_roll_preset" else "record_roll_batch", buildJsonObject {
                        put("actorId", actorId); put("batchId", batch.id)
                        if (usesPreset) put("presetId", batch.presetId)
                        put(if (usesPreset) "rolls" else "entries", buildJsonArray { batch.rolls.forEach { r -> add(buildJsonObject {
                            put("rollId", r.id)
                            if (!usesPreset) put("check", cloudCheckArgs(r.check, batch.workshop))
                            put("evidence", r.evidence ?: checkEvidence(r.result))
                        }) } })
                    }, snapshot)
                }
            }
            val observed = already ?: if (result?.field("status") == "COMMITTED") requireNotNull(client.record(batch.id)) else null
            if (observed == null) SavedRollBatch(emptyList(), result?.let(::rollStatus) ?: "Status unverified")
            else {
                require(observed.field("kind") == "roll_batch" && observed.field("actorId") == actorId)
                val entries = observed.getValue("data").jsonObject.getValue("results").jsonArray.map { it.jsonObject }
                require(entries.map { it.field("rollId") } == batch.rolls.map { it.id }) { "Independent batch readback differs." }
                SavedRollBatch(entries.map { requireNotNull(it.field("text")) }, "Committed and verified")
            }
        }, savePreset = { p ->
            client.changePreset(snapshot, "save_roll_preset", buildJsonObject {
                put("actorId", actorId); put("presetId", p.id); put("name", p.name); put("update", presets.any { it.id == p.id })
                put("entries", buildJsonArray { p.entries.forEach { add(cloudCheckArgs(it, p.workshop)) } })
            })
        }, archivePreset = { id -> client.retirePreset(snapshot, id, permanent = false) },
        deletePreset = { id -> client.retirePreset(snapshot, id, permanent = true) })
}
