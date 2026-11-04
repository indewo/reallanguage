package com.reallanguage.app.testing

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

/** Each test owns one UUID-named database; application records are never reset. */
class IsolatedLearningDatabaseContext(base: Context, label: String) : ContextWrapper(base) {
    private val isolatedFile = File(base.cacheDir, "$label-${UUID.randomUUID()}.db")
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String): File = isolatedFile
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openDatabase(isolatedFile.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openDatabase(isolatedFile.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(isolatedFile)
}
