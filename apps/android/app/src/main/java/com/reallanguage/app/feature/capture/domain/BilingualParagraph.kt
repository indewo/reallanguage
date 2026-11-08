package com.reallanguage.app.feature.capture.domain

data class BilingualParagraph(val textZh: String, val textEn: String, val sentences: List<BilingualSentence>)
data class BilingualSentence(val id: String, val textEn: String, val textZh: String)
