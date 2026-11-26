/*
    This file is part of Doppel.

    Doppel is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Doppel is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2026 akano12
 */

package eu.faircode.xlua

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Colors, fonts and text styles of the views, which the Compose screens use too:
// the palette and the black background are overlays of the theme, see ApplicationEx.applyPalette
private val Sans = FontFamily(Font(R.font.plex_sans))
private val SansTitle = FontFamily(Font(R.font.plex_sans_title))
internal val Mono = FontFamily(Font(R.font.plex_mono))

private val BaseTypography = Typography()
private val DoppelTypography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = SansTitle),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = SansTitle),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = SansTitle),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = SansTitle, letterSpacing = (-0.01).sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = SansTitle, letterSpacing = (-0.01).sp),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = SansTitle),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = SansTitle),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = SansTitle),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = SansTitle),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = Sans),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = Sans),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = Sans),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = SansTitle),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = SansTitle),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = SansTitle)
)

private fun resolveColor(context: Context, @AttrRes attr: Int): Color {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(attr, value, true))
        return Color.Unspecified
    return Color(value.data)
}

// A color of the theme, like ?attr/colorError, for the Compose screens
@Composable
internal fun themeColor(@AttrRes attr: Int): Color {
    val context = LocalContext.current
    return remember(context, attr) { resolveColor(context, attr) }
}

@Composable
internal fun DoppelTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Read as Compose state, so the theme follows a change of the night mode
    val configuration = LocalConfiguration.current
    val night = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val base: ColorScheme = if (night) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = themeColor(androidx.appcompat.R.attr.colorPrimary),
        onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary),
        primaryContainer = themeColor(com.google.android.material.R.attr.colorPrimaryContainer),
        onPrimaryContainer = themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer),
        inversePrimary = themeColor(com.google.android.material.R.attr.colorPrimaryInverse),
        secondary = themeColor(com.google.android.material.R.attr.colorSecondary),
        onSecondary = themeColor(com.google.android.material.R.attr.colorOnSecondary),
        secondaryContainer = themeColor(com.google.android.material.R.attr.colorSecondaryContainer),
        onSecondaryContainer = themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
        tertiary = themeColor(com.google.android.material.R.attr.colorTertiary),
        onTertiary = themeColor(com.google.android.material.R.attr.colorOnTertiary),
        tertiaryContainer = themeColor(com.google.android.material.R.attr.colorTertiaryContainer),
        onTertiaryContainer = themeColor(com.google.android.material.R.attr.colorOnTertiaryContainer),
        background = themeColor(com.google.android.material.R.attr.colorSurface),
        onBackground = themeColor(com.google.android.material.R.attr.colorOnSurface),
        surface = themeColor(com.google.android.material.R.attr.colorSurface),
        onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface),
        surfaceVariant = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest),
        onSurfaceVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
        surfaceTint = themeColor(androidx.appcompat.R.attr.colorPrimary),
        inverseSurface = themeColor(com.google.android.material.R.attr.colorSurfaceInverse),
        inverseOnSurface = themeColor(com.google.android.material.R.attr.colorOnSurfaceInverse),
        outline = themeColor(com.google.android.material.R.attr.colorOutline),
        outlineVariant = themeColor(com.google.android.material.R.attr.colorOutlineVariant),
        surfaceBright = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh),
        surfaceDim = themeColor(com.google.android.material.R.attr.colorSurfaceContainerLow),
        surfaceContainer = themeColor(com.google.android.material.R.attr.colorSurfaceContainer),
        surfaceContainerHigh = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh),
        surfaceContainerHighest = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest),
        surfaceContainerLow = themeColor(com.google.android.material.R.attr.colorSurfaceContainerLow),
        surfaceContainerLowest = themeColor(com.google.android.material.R.attr.colorSurfaceContainerLowest),
        error = themeColor(androidx.appcompat.R.attr.colorError),
        onError = themeColor(com.google.android.material.R.attr.colorOnError),
        errorContainer = themeColor(com.google.android.material.R.attr.colorErrorContainer),
        onErrorContainer = themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
    )
    MaterialTheme(colorScheme = scheme, typography = DoppelTypography, content = content)
}
