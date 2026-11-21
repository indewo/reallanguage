package com.reallanguage.app.feature.learning.data
import android.content.Context
import java.util.UUID

data class ExplanationRevision(val id: String, val pending: Boolean)
interface ExplanationRevisionStore {
    fun read(scope: String): ExplanationRevision?
    fun begin(scope: String): ExplanationRevision
    fun complete(scope: String, id: String)
}
/** Request identities only. Unknown metadata fails closed; no content or credentials. */
class AndroidExplanationRevisionStore(context: Context) : ExplanationRevisionStore {
    private val preferences = context.applicationContext.getSharedPreferences("explanation-request-revisions", Context.MODE_PRIVATE)
    private fun key(scope: String) = UUID.nameUUIDFromBytes(scope.toByteArray(Charsets.UTF_8)).toString()
    override fun read(scope: String): ExplanationRevision? = synchronized(lock) {
        preferences.getString(key(scope), null)?.let { value ->
            val parts = value.split(":")
            require(parts.size == 2 && parts[0] in setOf("pending", "complete"))
            ExplanationRevision(UUID.fromString(parts[1]).toString(), parts[0] == "pending")
        }
    }
    override fun begin(scope: String): ExplanationRevision = synchronized(lock) {
        read(scope)?.takeIf { it.pending } ?: ExplanationRevision(UUID.randomUUID().toString(), true).also {
            check(preferences.edit().putString(key(scope), "pending:" + it.id).commit())
        }
    }
    override fun complete(scope: String, id: String) = synchronized(lock) {
        check(read(scope)?.id == id)
        check(preferences.edit().putString(key(scope), "complete:" + id).commit())
    }
    private companion object { val lock = Any() }
}
