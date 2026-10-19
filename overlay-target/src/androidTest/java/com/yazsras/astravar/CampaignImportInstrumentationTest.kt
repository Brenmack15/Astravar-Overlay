package com.yazsras.astravar

import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern

/** Disposable emulator only. Every document and source in this suite is explicitly fictional. */
@RunWith(AndroidJUnit4::class)
class CampaignImportInstrumentationTest {
    @get:Rule val failureEvidence = NativeFailureEvidence()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val ui = ObservedUi(device)
    private val product = InstrumentationRegistry.getArguments().getString("astravarPackage") ?: "com.yazsras.astravar"
    private val files = instrumentation.targetContext.getExternalFilesDir(null)!!
    private val fixtureName = "astravar-native-fictional-campaign.json"

    private fun sha(value: String) = sha(value.toByteArray(Charsets.UTF_8))
    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun fixture(): String {
        fun record(id: String, title: String, category: String, body: String, related: List<String>) = JSONObject()
            .put("externalId", id).put("title", title).put("category", category).put("body", body)
            .put("propertiesJson", "{}").put("sourceUrl", "https://example.invalid/$id")
            .put("sourceRevision", "fictional-revision-1").put("contentSha256", sha(body))
            .put("relationIds", JSONArray(related)).put("visibility", "player")
        // A real CursorWindow cannot hold this retained source in one TEXT row.
        val attachment = ByteArray(15 * 1024 * 1024) { (it % 251).toByte() }
        val rows = JSONArray().put(record("fictional-note", "Fictional Archive Note", "Chronicle",
            "Fictional historical note. No resources changed.", listOf("fictional-contact")))
            .put(record("fictional-contact", "Fictional Archive Contact", "People",
                "Fictional contact dossier. No encounter begins.", emptyList()))
        repeat(318) { index ->
            val targets = (1..if (index == 0) 5 else 7).map { offset -> "fictional-bulk-${(index + offset) % 318}" }
            rows.put(record("fictional-bulk-$index", "Fictional Bulk Record ${index.toString().padStart(3, '0')}",
                "Fictional reference", "Fictional source $index; no inventory or gameplay changes.", targets))
        }
        val payload = JSONObject().put("campaignId", "fictional-native-migration")
            .put("title", "Fictional native campaign migration").put("exportedAt", 1)
            .put("sourceRootUrl", "https://example.invalid/fictional-root").put("sourceRootRevision", "fictional-1—Ω")
            .put("records", rows)
            .put("issues", JSONArray()).put("attachments", JSONArray().put(JSONObject()
                .put("id", "fictional-large-attachment").put("fileName", "fictional-large-reference.bin")
                .put("mimeType", "application/octet-stream").put("sourceUrl", "https://example.invalid/fictional-large-reference")
                .put("sha256", sha(attachment)).put("base64", Base64.encodeToString(attachment, Base64.NO_WRAP))
                .put("status", "embedded"))).toString()
        return JSONObject().put("format", "astravar-campaign-archive").put("schema", 1)
            .put("payloadSha256", sha(payload)).put("payload", payload).toString()
    }

    private fun tap(text: String) = ui.tapText(text, product, scrollAttempts = 18, preferLast = true)
    private fun find(text: String) = ui.textBounds(text, product, scrollAttempts = 18, preferLast = true)
    private fun actionEnabled(text: String): Boolean =
        ui.snapshot(By.text(text).pkg(product), "actual clickable action enabled state: $text", refreshCache = true) { label ->
            // Compose labels remain enabled inside a disabled clickable button.
            // Observe the nearest actual action target; never infer from its TextView.
            var current: UiObject2? = label
            repeat(12) {
                val observed = current ?: return@snapshot null
                if (observed.isClickable) {
                    val bounds = observed.visibleBounds
                    return@snapshot observed.isEnabled.takeIf {
                        !bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0 &&
                            bounds.right <= device.displayWidth && bounds.bottom <= device.displayHeight
                    }
                }
                current = observed.parent
            }
            null
        }
    private fun awaitText(text: String) {
        assertTrue("Large fictional document operation must finish: $text",
            device.wait(Until.hasObject(By.text(text).pkg(product)), 180_000))
        find(text)
    }

    private fun campaignEntry(name: String) {
        ui.ensurePrimaryTab(product, "Campaign")
        if (device.hasObject(By.text("‹ Campaign").pkg(product))) tap("‹ Campaign")
        tap(name)
    }

    private fun launchImportPage() {
        assertEquals("This fixture must run only on an emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        device.executeShellCommand("am start -W -n $product/com.yazsras.astravar.MainActivity")
        ui.awaitMainAfterSavedDialogs(product)
        campaignEntry("Campaign import")
        find("Bring your campaign with you")
    }

    private fun writeDownload(name: String, text: String) {
        // MediaStore stages an owned fictional download without a multi-megabyte
        // shell/Binder argument or broad storage permissions.
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = instrumentation.targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Uses the actual system document picker; no product intent or database bypass. */
    private fun chooseDocument(name: String) {
        tap("Choose campaign archive")
        val picker = device.wait(Until.findObject(By.text(name)), 3_000)
        if (picker == null) {
            val roots = device.wait(Until.findObject(By.desc(Pattern.compile("(?i)show (roots|navigation drawer)"))), 10_000)
            assertNotNull("System document picker must expose roots", roots)
            roots.click()
            val downloads = device.wait(Until.findObject(By.text("Downloads")), 10_000)
            assertNotNull("Downloads must be available in the document picker", downloads)
            downloads.click()
        }
        val document = device.wait(Until.findObject(By.text(name)), 20_000)
        assertNotNull("Fictional archive must be selected in DocumentsUI", document)
        document.click()
        awaitText("Review campaign archive")
    }

    private fun saveBackup(fileName: String = "astravar-native-preimport-backup.json") {
        tap("Save verified pre-import backup")
        val name = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15_000)
        assertNotNull("System CreateDocument must expose the backup filename", name)
        name.text = fileName
        val save = device.wait(Until.findObject(By.text(Pattern.compile("(?i)save"))), 10_000)
        assertNotNull("System document picker must expose Save", save)
        save.click()
        awaitText("Backup verified for this review.")
    }

    private fun secondaryFixture(): String {
        val body = "Fictional secondary import tests recovery of a large existing source."
        val row = JSONObject().put("externalId", "fictional-secondary-note").put("title", "Fictional secondary note")
            .put("category", "Chronicle").put("body", body).put("propertiesJson", "{}")
            .put("sourceUrl", "https://example.invalid/secondary").put("sourceRevision", "fictional-1")
            .put("contentSha256", sha(body)).put("relationIds", JSONArray()).put("visibility", "player")
        val payload = JSONObject().put("campaignId", "fictional-native-secondary")
            .put("title", "Fictional secondary recovery test").put("exportedAt", 2)
            .put("sourceRootUrl", "https://example.invalid/secondary-root").put("sourceRootRevision", "fictional-2")
            .put("records", JSONArray().put(row)).put("issues", JSONArray()).put("attachments", JSONArray()).toString()
        return JSONObject().put("format", "astravar-campaign-archive").put("schema", 1)
            .put("payloadSha256", sha(payload)).put("payload", payload).toString()
    }

    private fun reviewRollback(title: String) {
        find(title)
        var current = requireNotNull(device.findObject(By.text(title).pkg(product))).parent
        repeat(10) {
            val container = current ?: error("Import receipt card is missing")
            val button = container.findObject(By.text("Review rollback").pkg(product))
            if (button != null) { button.click(); return }
            current = container.parent
        }
        error("The observed import receipt has no rollback control")
    }

    @Test fun aDocumentPickerPreviewConfirmationAndLibrary() {
        val text = fixture()
        File(files, "native-campaign-fixture.json").writeText(text)
        writeDownload(fixtureName, text)
        writeDownload("astravar-native-secondary-campaign.json", secondaryFixture())
        // Corrupt the small archive to exercise the same integrity rejection
        // without retaining another parsed and serialized copy of the stress fixture.
        val invalid = JSONObject(secondaryFixture()).put("payloadSha256", "0".repeat(64)).toString()
        writeDownload("astravar-native-invalid-campaign.json", invalid)
        launchImportPage()
        chooseDocument("astravar-native-invalid-campaign.json")
        assertTrue(device.wait(Until.hasObject(By.textContains("Archive integrity check failed").pkg(product)), 10_000))
        assertFalse("Invalid archive cannot be confirmed", actionEnabled("Confirm local import"))
        tap("Close")

        chooseDocument(fixtureName)
        assertFalse("Preview alone cannot authorize an import", actionEnabled("Confirm local import"))
        // Terminate a read-only preview, then recover through ordinary navigation. Nothing was committed.
        device.executeShellCommand("am force-stop $product")
        launchImportPage()
        find("No campaign archives have been imported on this device.")
        chooseDocument(fixtureName)
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("320 reference records, 2225 relationships").pkg(product)), 10_000))
        saveBackup()
        tap("I reviewed the archive and want to add approved records locally.")
        tap("Confirm local import")
        awaitText("Import saved. Independent database readback passed.")
        assertTrue(device.takeScreenshot(File(files, "native-campaign-import-receipt.png")))
        tap("Verify saved import")
        assertTrue(device.wait(Until.hasObject(By.text("Database readback matches every imported identity, relationship and content hash.").pkg(product)), 180_000))

        campaignEntry("Reference Library")
        find("320 of 320 references")
        tap("Fictional Archive Note")
        find("Fictional historical note. No resources changed.")
        find("Relationships (1)")
        assertTrue(device.takeScreenshot(File(files, "native-campaign-original-source.png")))
        tap("Fictional Archive Contact")
        find("Fictional contact dossier. No encounter begins.")
        tap("Close")
        campaignEntry("Campaign import")
        // The second pre-import snapshot contains the >2MiB first source, so
        // backup-row reads and guarded recovery exercise real CursorWindow limits.
        chooseDocument("astravar-native-secondary-campaign.json")
        saveBackup("astravar-native-secondary-preimport-backup.json")
        tap("I reviewed the archive and want to add approved records locally.")
        tap("Confirm local import")
        awaitText("Import saved. Independent database readback passed.")
        reviewRollback("Fictional secondary recovery test")
        tap("I understand this restores the verified pre-import campaign backup.")
        tap("Confirm rollback")
        awaitText("Recovered the verified pre-import campaign snapshot. Original import and recovery receipts are retained.")
        chooseDocument(fixtureName)
        assertTrue(device.wait(Until.hasObject(By.textContains("Already imported on this device").pkg(product)), 10_000))
        assertFalse("Duplicate archives cannot add the same records again", actionEnabled("Confirm local import"))
        tap("Close")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: fictional archive selected through DocumentsUI; invalid integrity and interrupted preview preserved state; backup and explicit confirmation imported 320 fictional records/2225 relations; library, original text, relations and duplicate guard verified\n") })
    }

    @Test fun bGuardedRollbackRestoresVerifiedSnapshot() {
        launchImportPage()
        reviewRollback("Fictional native campaign migration")
        assertFalse("Rollback requires separate consent", actionEnabled("Confirm rollback"))
        tap("I understand this restores the verified pre-import campaign backup.")
        tap("Confirm rollback")
        awaitText("Recovered the verified pre-import campaign snapshot. Original import and recovery receipts are retained.")
        assertTrue(device.takeScreenshot(File(files, "native-campaign-rollback-receipt.png")))
        campaignEntry("Reference Library")
        find("0 of 0 references")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: separate player consent recovered the verified pre-import snapshot; imported references removed and immutable receipts retained\n") })
    }

    /** Supplemental test-only fixture. Run after the large archive rollback; never replaces an existing profile. */
    @Test fun cMappedCampaignRecordsReachPlayerViews() {
        assertEquals("This fixture must run only on an emulator", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        val text = instrumentation.context.assets.open("fictional-mapped-campaign.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        require(text.toByteArray(Charsets.UTF_8).size < 256 * 1024) { "The supplemental fixture must remain small and wholly fictional." }
        val envelope = JSONObject(text)
        val payload = JSONObject(envelope.getString("payload"))
        assertEquals("fictional-native-typed", payload.getString("campaignId"))
        assertEquals(sha(envelope.getString("payload")), envelope.getString("payloadSha256"))
        File(files, "native-mapped-fixture.json").writeText(text)
        writeDownload("astravar-native-mapped-campaign.json", text)
        val observations = JSONArray()
        val screenshots = JSONArray()
        fun captureMapped(name: String, tab: String, vararg visibleMarkers: String, dialogTitle: String? = null) {
            visibleMarkers.forEach { find(it) }
            val fileName = "native-mapped-$name.png"
            val selectors = visibleMarkers.map { By.text(it).pkg(product) }
            if (dialogTitle == null) ui.captureStablePage(File(files, fileName), product, tab, selectors)
            else ui.captureStableDialog(File(files, fileName), product, dialogTitle, selectors)
            observations.put(JSONObject().put("route", name).put("primaryTab", tab)
                .put("visibleMarkers", JSONArray(visibleMarkers.toList())).put("stableRenderedFrame", true)
                .put("dialogTitle", dialogTitle ?: JSONObject.NULL).put("primaryTabVerifiedBeforeOpening", dialogTitle != null))
            screenshots.put(fileName)
        }

        launchImportPage()
        chooseDocument("astravar-native-mapped-campaign.json")
        assertTrue("The mapped fixture must preview one source without replacing local records",
            device.wait(Until.hasObject(By.textStartsWith("1 reference records, 0 relationships").pkg(product)), 10_000))
        assertFalse("A mapped preview is not import authorization", actionEnabled("Confirm local import"))
        saveBackup("astravar-native-mapped-preimport-backup.json")
        tap("I reviewed the archive and want to add approved records locally.")
        assertTrue("The reviewed conflict-free mapped fixture can be confirmed after its verified backup",
            actionEnabled("Confirm local import"))
        tap("Confirm local import")
        awaitText("Import saved. Independent database readback passed.")

        ui.ensurePrimaryTab(product, "Armor")
        find("Fictional Mapped Operator")
        assertTrue("Armor must display the imported manual ability independently of weapon mechanics",
            device.wait(Until.hasObject(By.descContains("Intelligence score 14").pkg(product)), 10_000))
        captureMapped("armor", "Armor", "Fictional Mapped Operator", "Ability scores")
        tap("Armor source references")
        find("1 of 1 references")
        observations.put(JSONObject().put("route", "armor-source-entry").put("primaryTab", "Campaign")
            .put("visibleMarker", "1 of 1 references"))

        ui.ensurePrimaryTab(product, "Party")
        tap("Fictional Mapped Operator")
        find("Fictional Mapped Operator")
        if (!device.hasObject(By.text("Fictional archival operator").pkg(product))) tap("Identity & progression")
        captureMapped("party", "Party", "Class / role", "Fictional archival operator")
        tap("Equipment")
        captureMapped("equipment", "Party", "Fictional Mapped Lens", "Carried · quantity unknown")
        tap("Currency")
        captureMapped("currency", "Party", "Fictional Mapped Purse", "? cp · 2 sp · 7 gp")
        tap("‹ Party")
        tap("Fictional Mapped Owl")
        find("Fictional Mapped Owl")
        if (!device.hasObject(By.text("Fictional archival owl").pkg(product))) tap("Identity & progression")
        captureMapped("companion", "Party", "Class / role", "Fictional archival owl")
        tap("Party source dossiers")
        find("1 of 1 references")
        observations.put(JSONObject().put("route", "party-source-entry").put("primaryTab", "Campaign")
            .put("visibleMarker", "1 of 1 references"))

        campaignEntry("Inventory")
        tap("Fictional Mapped Lens")
        captureMapped("inventory", "Campaign", "Unknown quantity · Carried", "Fictional lens description preserved.", dialogTitle = "Fictional Mapped Lens")
        tap("Properties & Mechanics")
        find("Fictional optical property")
        find("Fictional optical note; no automatic bonus.")
        tap("Close")
        tap("Inventory source archive")
        find("1 of 1 references")
        observations.put(JSONObject().put("route", "inventory-source-entry").put("primaryTab", "Campaign")
            .put("visibleMarker", "1 of 1 references"))

        campaignEntry("Inventions")
        tap("Fictional Proposed Device")
        captureMapped("invention", "Campaign", "Concept & Blueprint", "Fictional proposal only; no equipment was constructed.", dialogTitle = "Fictional Proposed Device")
        tap("Physical output")
        find("No linked physical output or equipment")
        tap("Close")

        campaignEntry("Connections")
        tap("Fictional Mapped Contact")
        captureMapped("connection", "Campaign", "Fictional contact notes preserved.", "Known Information", dialogTitle = "Fictional Mapped Contact")
        find("Fictional historical fact preserved.")
        tap("Close")

        campaignEntry("Checkpoints")
        captureMapped("checkpoint", "Campaign", "Fictional Mapped Checkpoint", "Fictional room; no time advances.")

        campaignEntry("Reference Library")
        find("1 of 1 references")
        tap("Fictional Typed Source")
        captureMapped("reference", "Campaign", "Original source text", "Fictional typed snapshot source. No gameplay is executed.", dialogTitle = "Fictional Typed Source")
        tap("Close")

        // Every view above is read-only. The original receipt must still verify after the entire sweep.
        campaignEntry("Campaign import")
        tap("Verify saved import")
        awaitText("Database readback matches every imported identity, relationship and content hash.")
        File(files, "native-mapped-ui-observations.json").writeText(JSONObject()
            .put("marker", "ASTRAVAR_FICTIONAL_TYPED_NATIVE_UI_V1").put("synthetic_only", true)
            .put("package", product).put("packageSha256", envelope.getString("payloadSha256"))
            .put("observations", observations).put("screenshots", screenshots)
            .put("postViewDatabaseReadbackPassed", true).put("gameplayActionsInvoked", false).toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: fictional typed archive selected with SAF and separately confirmed; Armor, Party, companion sheet, equipment, currency, Inventory, Inventions, Connections, Checkpoints, Reference Library and all three source entry points rendered imported records; post-view database readback passed without gameplay mutations\n") })
    }

    /** Invoke only after the host has independently copied and verified the applied mapped database. */
    @Test fun dMappedCampaignRecordsRollbackAfterReadback() {
        launchImportPage()
        reviewRollback("Fictional typed migration")
        assertFalse("Typed recovery requires its own player confirmation", actionEnabled("Confirm rollback"))
        tap("I understand this restores the verified pre-import campaign backup.")
        tap("Confirm rollback")
        awaitText("Recovered the verified pre-import campaign snapshot. Original import and recovery receipts are retained.")
        campaignEntry("Reference Library")
        find("0 of 0 references")
        ui.captureStablePage(File(files, "native-mapped-rollback.png"), product, "Campaign", listOf(By.text("0 of 0 references").pkg(product)))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: separately confirmed typed archive recovery restored the pre-import campaign; immutable receipts retained and Reference Library read back empty\n") })
    }
}
