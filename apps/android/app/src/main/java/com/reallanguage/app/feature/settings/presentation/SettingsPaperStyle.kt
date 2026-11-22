package com.reallanguage.app.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.reallanguage.app.core.designsystem.editorialPaper

internal val SettingsOlive = Color(0xFF6B7044)
internal val SettingsSelected = Color(0xFF8B8D54)
internal val SettingsPaper = Color(0xFFF3E7D8)
@Composable
internal fun Modifier.settingsPaper(color: Color = SettingsPaper): Modifier = editorialPaper(color)
