package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.DiceMode
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class AscensionBatchPersistenceTest {
    @Test fun invalidSecondRollRollsBackAndRetryPreservesMembershipAcrossReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "synthetic-batch-retention.db"
        context.deleteDatabase(file)
        fun open() = Room.databaseBuilder(context, ReforgeDb::class.java, file).allowMainThreadQueries().build()
        var db = open()
        try {
            var repo = ReforgeRepository(db)
            val a = repo.create(ReforgeCharacter("actor-one", "Fictional actor"), "create-fixture")
            fun row(id: String, face: Int, members: List<String> = emptyList()) = ReforgeRollRecord(id, a.character.id, a.revision,
                "Skill", "1d20+4", listOf(ReforgeRollComponent("Skill", listOf(ReforgeRollDie(20, face)), 4,
                    mode = DiceMode.NORMAL, batchId = "batch-one", subjectId = "Arcana", checkKind = "SKILL", batchMemberIds = members)), manual = true)
            val first = row("batch-one", 11, listOf("batch-one", "second-die"))
            val second = row("second-die", 19)
            assertTrue(runCatching { repo.appendRollBatch("batch-one", listOf(first, row("second-die", 21))) }.isFailure)
            assertTrue(repo.rollHistory(a.character.id).first().isEmpty())
            val stored = repo.appendRollBatch("batch-one", listOf(first,second))
            db.close(); db = open(); repo = ReforgeRepository(db)
            assertEquals(stored, repo.appendRollBatch("batch-one", listOf(first,second)))
            val different = row("batch-one", 11, listOf("batch-one", "third-die"))
            assertTrue(runCatching { repo.appendRollBatch("batch-one", listOf(different, row("third-die", 3))) }.isFailure)
            val history = repo.rollHistory(a.character.id).first()
            assertEquals(setOf("batch-one", "second-die"), history.map { it.id }.toSet())
            assertTrue(repo.previewRestore(repo.export()).valid)
        } finally { db.close(); context.deleteDatabase(file) }
    }
}
