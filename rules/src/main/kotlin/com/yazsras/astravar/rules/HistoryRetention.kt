package com.yazsras.astravar.rules

import kotlinx.serialization.Serializable

/** One limit for persisted rolls, the bounded undo journal, queries, migration and backups. */
object HistoryRetention {
    const val MAX_HISTORY_ENTRIES = 20
    fun campaign(s: Campaign) = s.copy(pending=s.pending.takeLast(MAX_HISTORY_ENTRIES))
    fun compact(e: HistoryEntry): HistoryEntry {
        if(e.compact) return e.copy(before=e.before.copy(pending=emptyList()),after=e.after.copy(pending=emptyList()))
        val old=e.before.pending.associateBy {it.plan.id}
        val undo=e.after.pending.mapNotNull { shot ->
            val prior=old[shot.plan.id]
            when {
                prior==null -> RollUndo(shot.plan.id,false)
                prior!=shot -> RollUndo(shot.plan.id,true,prior.result,prior.rolled!=null)
                else -> null
            }
        }
        return e.copy(before=e.before.copy(pending=emptyList()),after=e.after.copy(pending=emptyList()),rollUndo=undo,compact=true)
    }
    fun restoreRolls(current: List<PendingShot>,e: HistoryEntry): List<PendingShot> {
        val changes=e.rollUndo.associateBy {it.id}
        return current.mapNotNull { shot ->
            val undo=changes[shot.plan.id]
            when {
                undo==null -> shot
                !undo.existed -> null
                else -> shot.copy(result=undo.result,rolled=if(undo.hadRoll) shot.rolled else null)
            }
        }.filter {shot->e.before.cores.any {it.id==shot.plan.coreId}}.takeLast(MAX_HISTORY_ENTRIES)
    }
    fun backup(b: Backup)=b.copy(campaign=campaign(b.campaign),
        history=b.history.sortedWith(compareBy<HistoryEntry> {it.after.revision}.thenBy {it.id})
            .takeLast(MAX_HISTORY_ENTRIES).map(::compact),archives=emptyList())
}

/** Only the state needed to reverse a retained roll; never a second copy of its dice. */
@Serializable data class RollUndo(val id: String,val existed: Boolean,val result: Resolution?=null,val hadRoll: Boolean=false)
data class HistorySummary(val id: String,val label: String,val gameSeconds: Long,val origin: String,val undone: Boolean)
