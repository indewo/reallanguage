package com.reallanguage.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R

object RlFont {
    val Brand = FontFamily(
        Font(R.font.source_serif_4_variable, weight = FontWeight.SemiBold),
        Font(R.font.source_serif_4_variable, weight = FontWeight.Bold),
    )
}

object RlColor {
    // Measured against the 72 approved source screens: retain warmth without the
    // stronger yellow cast seen on device.
    val Background = Color(0xFFF8F5EF)
    val Surface = Color(0xFFFCFAF6)
    val TextPrimary = Color(0xFF253038)
    val TextSecondary = Color(0xFF505C63)
    val TextMuted = Color(0xFF626D74)
    val Border = Color(0xFFC9C0B4)
    val BorderStrong = Color(0xFF72706B)
    val Primary = Color(0xFF58758F)
    val PrimaryPressed = Color(0xFF445D73)
    val OnPrimary = Color.White
    val PrimaryContainer = Color(0xFFE7EDF0)
    val PrimaryContainerStrong = Color(0xFFD4DFE5)
    val Secondary = Color(0xFF607961)
    val SecondaryPressed = Color(0xFF485C49)
    val OnSecondary = Color.White
    val SecondaryContainer = Color(0xFFE6ECE5)
    val ActionVivid = Color(0xFFB95A47)
    val ActionVividPressed = Color(0xFF934738)
    val OnActionVivid = Color.White
    val ActionVividContainer = Color(0xFFF1E4DF)
    val NoteAccent = Color(0xFFB89A62)
    val NoteContainer = Color(0xFFF1E9D8)
    // Original 24 non-ink highlight band median; this solid color does not reproduce its texture.
    val ReadingWordHighlight = Color(0xFFEBD7B8)
    val Error = Color(0xFFB42318)
    val ErrorContainer = Color(0xFFFFF1F0)
    val Recording = Color(0xFFC83232)
    val DisabledContainer = Color(0xFFE4DED5)
    val Divider = Color(0xFFE2DBD0)
    val Ink = Color(0xFF071A33)
    val PaperWarm = Color(0xFFF8F3EB)
}

object RlSpace {
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space20 = 20.dp
    val Space24 = 24.dp
    val Space32 = 32.dp
    val Space40 = 40.dp
    val Space48 = 48.dp
    val Space64 = 64.dp
}

object RlShape {
    val Small = RoundedCornerShape(4.dp)
    val Input = RoundedCornerShape(8.dp)
    val Panel = RoundedCornerShape(6.dp)
    val Feature = RoundedCornerShape(2.dp)
}

private val RlLightColorScheme = lightColorScheme(
    primary = RlColor.Primary,
    onPrimary = RlColor.OnPrimary,
    primaryContainer = RlColor.PrimaryContainer,
    secondary = RlColor.Secondary,
    onSecondary = RlColor.OnSecondary,
    secondaryContainer = RlColor.SecondaryContainer,
    tertiary = RlColor.ActionVivid,
    onTertiary = RlColor.OnActionVivid,
    tertiaryContainer = RlColor.ActionVividContainer,
    background = RlColor.Background,
    onBackground = RlColor.TextPrimary,
    surface = RlColor.Surface,
    onSurface = RlColor.TextPrimary,
    onSurfaceVariant = RlColor.TextSecondary,
    outline = RlColor.BorderStrong,
    error = RlColor.Error,
    errorContainer = RlColor.ErrorContainer,
    surfaceContainer = RlColor.PaperWarm,
    surfaceContainerHigh = RlColor.NoteContainer,
    surfaceVariant = RlColor.PrimaryContainer,
    outlineVariant = RlColor.Border,
)

private val RlShapes = Shapes(
    extraSmall = RlShape.Small,
    small = RlShape.Input,
    medium = RlShape.Panel,
    large = RlShape.Feature,
    extraLarge = RlShape.Feature,
)

private val RlTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 25.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
)

@Composable
fun RlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RlLightColorScheme,
        typography = RlTypography,
        shapes = RlShapes,
        content = content,
    )
}
