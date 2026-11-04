package com.reallanguage.app.feature.learning.domain

/** Local, account-bound ordinary-learning working state; never a mastery result. */
interface LearningProgressStore {
    /** null means no saved progress; failures must throw, not pretend to be an empty chapter. */
    suspend fun read(): Map<String, String>?
    suspend fun write(values: Map<String, String>): Boolean
}
