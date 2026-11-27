package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.CloudCampaignClient
import com.yazsras.astravar.data.CloudSnapshot
import com.yazsras.astravar.data.cloudIdentifier
import com.yazsras.astravar.data.cloudMediaTargets
import com.yazsras.astravar.data.cloudText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

private data class LinkedCampaignRecord(val id: String, val relation: String, val outgoing: Boolean, val title: String)

private fun LinkedCampaignRecord.caption(): String {
    val labels = when (relation) {
        "source_component" -> "Source component" to "Used by integration"
        "resulting_item" -> "Resulting item" to "Integration history"
        "source_definition" -> "Original item definition" to "Definition used by"
        "resolved_stage" -> "Construction stage" to "Integration at this stage"
        "stage_of" -> "Construction project" to "Construction stage"
        "design_for" -> "Blueprint for project" to "Blueprint"
        "supersedes_version" -> "Previous blueprint" to "Later blueprint"
        "research_basis" -> "Research finding" to "Project using this research"
        "research_roll" -> "Recorded research roll" to "Research supported by this roll"
        "adjudicated_roll" -> "Recorded construction roll" to "Construction using this roll"
        "item_studied" -> "Item studied" to "Research about this item"
        "known_about" -> "Known subject" to "Discovered knowledge"
        "completed_output" -> "Completed item" to "Construction project"
        "project" -> "Construction project" to "Project history"
        "paid_stage" -> "Stage payment applies to" to "Recorded payment"
        "requires_project" -> "Prerequisite project" to "Dependent project"
        else -> return cloudLabel(relation)
    }
    return if (outgoing) labels.first else labels.second
}

@Composable
internal fun CloudLinkedRecords(client: CloudCampaignClient, snapshot: CloudSnapshot, recordId: String,
                               online: Boolean, inspect: (String) -> Unit) {
    if (!snapshot.visible) return
    key(snapshot.binding, snapshot.version, recordId, online) {
        Column(Modifier.testTag("record-links-$recordId")) {
            PlayerDisclosure("Linked records") {
                if (!online) {
                    Text("Connect to retrieve the current links.", color = RebuildPalette.Muted)
                    return@PlayerDisclosure
                }
                var cursor by remember { mutableStateOf<String?>(null) }
                var next by remember { mutableStateOf<String?>(null) }
                var rows by remember { mutableStateOf(emptyList<LinkedCampaignRecord>()) }
                var loading by remember { mutableStateOf(true) }
                var issue by remember { mutableStateOf<String?>(null) }
                var retry by remember { mutableIntStateOf(0) }
                LaunchedEffect(cursor, retry) {
                    loading = true
                    issue = null
                    try {
                        val suffix = cursor?.let {
                            "?after=$it&atEpoch=${snapshot.version.epoch}&atRevision=${snapshot.version.revision}&grantRevision=${snapshot.version.grantRevision}"
                        }.orEmpty()
                        val page = client.projection(snapshot, "links/$recordId$suffix")
                        val visible = page.getValue("links").jsonArray.mapNotNull { entry ->
                            val edge = entry.jsonObject
                            val from = edge.cloudText("from_id")
                            val outgoing = from == recordId
                            val target = if (outgoing) edge.cloudText("to_id") else from
                            client.record(target)?.let {
                                val title = it.cloudText("title")
                                LinkedCampaignRecord(target, edge.cloudText("relation"), outgoing,
                                    if (it.cloudText("kind") == "component_event") cloudLabel(title) else title)
                            }
                        }
                        rows = (rows + visible).distinct()
                        next = page["nextAfter"]?.jsonPrimitive?.contentOrNull
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        issue = cloudError(e)
                    } finally {
                        loading = false
                    }
                }
                rows.forEach { link ->
                    PlayerEntry(link.title, link.caption(), "History", { inspect(link.id) })
                }
                if (loading) Text("Loading linked records…", color = RebuildPalette.Muted)
                else if (issue != null) {
                    Text(requireNotNull(issue), color = MaterialTheme.colorScheme.error)
                    PlayerTextButton({ retry++ }) { Text("Retry linked records") }
                } else if (rows.isEmpty()) Text("No visible linked records.", color = RebuildPalette.Muted)
                if (!loading && issue == null && next != null)
                    PlayerTextButton({ cursor = next }) { Text("More linked records") }
            }
        }
    }
}

@Composable
internal fun CloudCanonicalRecordInspector(client: CloudCampaignClient, snapshot: CloudSnapshot,
                                          rootId: String, online: Boolean, show: Boolean, close: () -> Unit,
                                          openReceipt: (String) -> Unit) {
    var trail by rememberSaveable(rootId, snapshot.binding, snapshot.version.grantRevision) {
        mutableStateOf(arrayListOf(rootId))
    }
    if (!show) return
    val currentId = trail.last()
    key(currentId, snapshot.version) {
        var record by remember { mutableStateOf<JsonObject?>(null) }
        var loaded by remember { mutableStateOf(false) }
        LaunchedEffect(client, snapshot) {
            try {
                record = client.record(currentId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                record = null
            } finally {
                loaded = true
            }
        }
        PlayerSheet(record?.cloudText("title") ?: "Record", close) {
            if (trail.size > 1) PlayerTextButton({ trail = ArrayList(trail.dropLast(1)) }) { Text("Back to previous record") }
            val row = record
            if (row != null) {
                val kind = row.cloudText("kind")
                if (kind in cloudMediaTargets) CloudEntityImage(client, snapshot, online, currentId, kind, row.cloudText("title"))
                CloudLinkedRecords(client, snapshot, currentId, online) { next ->
                    val earlier = trail.indexOf(next)
                    trail = ArrayList(if (earlier >= 0) trail.take(earlier + 1) else trail + next)
                }
                val data = row.getValue("data").jsonObject
                RecordFields(data)
                val command = (data["commandId"] as? JsonPrimitive)?.contentOrNull
                    ?: (data["sourceCommandId"] as? JsonPrimitive)?.contentOrNull
                if (command != null && cloudIdentifier.matches(command)) {
                    PlayerTextButton({ openReceipt(command) }, enabled = online) { Text("View transaction receipt") }
                }
                PlayerDisclosure("Record details") {
                    Text(currentId)
                    Text("Revision ${row["revision"]}")
                }
            } else if (loaded) Text("This record is unavailable in your active view.")
        }
    }
}
