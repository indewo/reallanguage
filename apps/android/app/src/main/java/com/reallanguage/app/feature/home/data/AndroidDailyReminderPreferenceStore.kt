package com.reallanguage.app.feature.home.data

import android.content.Context

/** Account-keyed reminder metadata only; no learning text, credentials, or SQLite migration. */
class AndroidDailyReminderPreferenceStore(
    context: Context,
    preferenceFileName: String = "real-language-daily-reminders",
) : DailyReminderPreferenceStore by
    PersistentDailyReminderPreferenceStore(object : ReminderStringPreferences {
        private val preferences = context.applicationContext.getSharedPreferences(preferenceFileName, Context.MODE_PRIVATE)
        override fun read(key: String): String? = preferences.getString(key, null)
        override fun write(key: String, value: String?): Boolean {
            val editor = preferences.edit()
            if (value == null) editor.remove(key) else editor.putString(key, value)
            return editor.commit()
        }
    })
