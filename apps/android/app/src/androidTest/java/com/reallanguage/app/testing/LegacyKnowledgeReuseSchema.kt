package com.reallanguage.app.testing

import android.database.sqlite.SQLiteDatabase

/** Only for UUID-isolated migration fixtures before setting a historical version. */
fun removeKnowledgeReuseSchemaForLegacyFixture(db: SQLiteDatabase) {
    db.execSQL("alter table fixed_sentences drop column reused_knowledge_refs")
    db.execSQL("alter table target_selections drop column known_knowledge_ref")
    db.execSQL("drop table english_generation_knowledge_snapshots")
}
