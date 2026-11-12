package com.yazsras.astravar.data

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** Android's default corruption handler deletes files, even for a read-only open. Never use it here. */
internal val genesisOriginalDatabaseErrorHandler = DatabaseErrorHandler {
    throw SQLiteDatabaseCorruptException("The original playable database needs recovery. Its files have not been deleted or replaced.")
}

/**
 * Preserve Room's complete lifecycle/migration callbacks while replacing only destructive recovery.
 * Otherwise a corrupted destination could lose its cutover receipt and replay a stale original.
 */
internal class GenesisNonDestructiveOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                // Do not delegate to Callback.onCorruption: its default implementation deletes
                // the database and attached files. Let the caller surface a recovery failure.
                throw SQLiteDatabaseCorruptException("Campaign storage needs recovery. Its database and storage authority have not been reset.")
            }
        }
        val protected = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(callback)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(protected)
    }
}
