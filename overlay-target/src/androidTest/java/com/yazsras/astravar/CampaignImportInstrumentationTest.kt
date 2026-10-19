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
        val invalid = JSONObject(text).put("payloadSha256", "0".repeat(64)).toString()
        writeDownload("astravar-native-invalid-campaign.json", invalid)
        launchImportPage()
        chooseDocument("astravar-native-invalid-campaign.json")
        assertTrue(device.wait(Until.hasObject(By.textContains("Archive integrity check failed").pkg(product)), 10_000))
        assertFalse("Invalid archive cannot be confirmed", device.findObject(By.text("Confirm local import").pkg(product)).isEnabled)
        tap("Close")

        chooseDocument(fixtureName)
        assertFalse("Preview alone cannot authorize an import", device.findObject(By.text("Confirm local import").pkg(product)).isEnabled)
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
        assertFalse("Duplicate archives cannot add the same records again", device.findObject(By.text("Confirm local import").pkg(product)).isEnabled)
        tap("Close")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: fictional archive selected through DocumentsUI; invalid integrity and interrupted preview preserved state; backup and explicit confirmation imported 320 fictional records/2225 relations; library, original text, relations and duplicate guard verified\n") })
    }

    @Test fun bGuardedRollbackRestoresVerifiedSnapshot() {
        launchImportPage()
        reviewRollback("Fictional native campaign migration")
        assertFalse("Rollback requires separate consent", device.findObject(By.text("Confirm rollback").pkg(product)).isEnabled)
        tap("I understand this restores the verified pre-import campaign backup.")
        tap("Confirm rollback")
        awaitText("Recovered the verified pre-import campaign snapshot. Original import and recovery receipts are retained.")
        assertTrue(device.takeScreenshot(File(files, "native-campaign-rollback-receipt.png")))
        campaignEntry("Reference Library")
        find("0 of 0 references")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCHECKPOINT: separate player consent recovered the verified pre-import snapshot; imported references removed and immutable receipts retained\n") })
    }
}
