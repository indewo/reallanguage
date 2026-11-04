package com.reallanguage.app.core.designsystem

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import com.reallanguage.app.R

/** Opt-in CJK serif for the redesigned editorial pages. Existing RlTheme is intentionally unchanged. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object EditorialFont {
    val Serif = FontFamily(
        Font(R.font.noto_serif_sc_variable, weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.noto_serif_sc_variable, weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.noto_serif_sc_variable, weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.noto_serif_sc_variable, weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
}
