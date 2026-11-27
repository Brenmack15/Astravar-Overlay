package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import kotlinx.serialization.json.*
import java.util.UUID

@Composable
internal fun CloudFormDeploymentControls(
    actorId: String,
    formId: String,
    preview: JsonObject?,
    online: Boolean,
    open: (String, JsonObject) -> Unit,
    commit: (String, JsonObject) -> Unit
) {
    val required = preview?.get("activationSeconds")?.jsonPrimitive?.intOrNull
    val pending = preview?.get("deployment") as? JsonObject
    val pendingId = pending?.get("deploymentId")?.jsonPrimitive?.contentOrNull
    val pendingFormId = pending?.get("formId")?.jsonPrimitive?.contentOrNull
    val active = preview?.get("activeFormId")?.jsonPrimitive?.contentOrNull == formId
    val available = online && preview != null
    val eligible = available && !active && preview?.get("sourceEligible")?.jsonPrimitive?.booleanOrNull == true
    val willingRequired = preview?.get("requiresWilling")?.jsonPrimitive?.booleanOrNull == true
    var willing by rememberSaveable(actorId, formId, pendingId) { mutableStateOf(false) }
    var reason by rememberSaveable(actorId, formId, pendingId) { mutableStateOf("") }
    var interrupting by rememberSaveable(actorId, formId, pendingId) { mutableStateOf(false) }
    val declaration = rememberSaveable(actorId, formId, pendingId) { UUID.randomUUID().toString() }
    fun body(block: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("actorId", actorId); block() }

    if (pendingId != null && pendingFormId != formId) {
        Text("Deployment in progress for " + pending?.get("formTitle")?.jsonPrimitive?.content.orEmpty() + ".")
        return
    }
    if (required == null || required <= 6) {
        PlayerButton({ open("change_form", body { put("formId", formId) }) }, enabled = eligible) { Text("Review transformation") }
        return
    }
    if (pendingId != null) {
        val state = requireNotNull(pending)
        val elapsed = state.getValue("elapsedSeconds").jsonPrimitive.int
        val ready = state.getValue("ready").jsonPrimitive.boolean
        val tracked = state["basis"]?.jsonPrimitive?.content == "encounter_rounds"
        Text("$elapsed of $required seconds recorded", style = MaterialTheme.typography.titleMedium)
        if (tracked) Text("Encounter round " + state["round"]?.jsonPrimitive?.content.orEmpty())
        state["blockedReason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
            Text(when (reason) {
                "DEPLOYMENT_FORM_CHANGED" -> "The form changed during deployment. Cancel and review the updated form."
                "RESOURCE_EMPTY_OR_UNKNOWN" -> "The deployment use is spent or its balance is unknown."
                "FORM_INTEGRITY_UNAVAILABLE" -> "This form requires usable Integrity."
                "FORM_SOURCE_INELIGIBLE" -> "The required equipment is unavailable."
                else -> cloudLabel(reason.lowercase())
            }, color = MaterialTheme.colorScheme.error)
        }
        if (ready && willingRequired) WillingFormActor(willing) { willing = it }
        PlayerButton({
            commit("change_form", body {
                put("deploymentId", pendingId); put("formId", formId)
                put("elapsedSeconds", 0); put("uninterrupted", true)
                if (willingRequired) put("willing", willing)
            })
        }, enabled = eligible && ready && (!willingRequired || willing)) { Text("Confirm uninterrupted deployment") }
        PlayerTextButton({
            commit("interrupt_form_deployment", body {
                put("deploymentId", pendingId); put("status", "cancelled"); put("reason", "Player cancelled deployment.")
            })
        }, enabled = available) { Text("Cancel deployment") }
        if (interrupting) {
            OutlinedTextField(value = reason, onValueChange = { reason = it.take(300) }, label = { Text("Interruption reason") })
            PlayerOutlinedButton({
                commit("interrupt_form_deployment", body {
                    put("deploymentId", pendingId); put("status", "interrupted"); put("reason", reason.trim())
                })
            }, enabled = available && reason.isNotBlank()) { Text("Save interruption") }
        } else PlayerTextButton({ interrupting = true }, enabled = available) { Text("Record interruption") }
        return
    }

    if (willingRequired) WillingFormActor(willing) { willing = it }
    PlayerButton({
        commit("begin_form_deployment", body {
            put("formId", formId); put("deploymentId", declaration); put("declared", true)
            if (willingRequired) put("willing", willing)
        })
    }, enabled = eligible && (!willingRequired || willing)) { Text("Begin deployment") }
    if (preview?.get("trackedEncounter")?.jsonPrimitive?.booleanOrNull == false) {
        PlayerTextButton({ open("change_form", body { put("formId", formId) }) }, enabled = eligible) {
            Text("Review previously resolved transformation")
        }
    }
}

@Composable
private fun WillingFormActor(checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = update),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text("This actor is willing to transform")
    }
}
