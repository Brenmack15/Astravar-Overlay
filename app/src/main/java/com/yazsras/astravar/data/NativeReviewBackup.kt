package com.yazsras.astravar.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Durable private recovery copy. Complete exports never enter the command or the network. */
internal suspend fun saveNativeReviewBackup(context: Context, db: CampaignLedgerDb): String = withContext(Dispatchers.IO) {
    val snapshot = GenesisAuthorityBackupRepository(db).exportWithProof()
    val directory = File(context.noBackupFilesDir, "reviewed-campaign-backups")
    check(directory.isDirectory || directory.mkdirs())
    val file = AtomicFile(File(directory, snapshot.currentFingerprint + ".json"))
    if (!file.baseFile.exists()) {
        val output = file.startWrite()
        try {
            output.write(snapshot.text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
    val saved = file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    check(genesisAuthorityBackupFingerprint(decodeGenesisAuthorityBackup(saved)) == snapshot.currentFingerprint)
    snapshot.currentFingerprint
}
