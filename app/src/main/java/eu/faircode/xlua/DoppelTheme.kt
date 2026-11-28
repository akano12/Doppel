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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
internal fun rememberNavSpace(): Dp {
    val resources = LocalResources.current
    val density = LocalDensity.current
    return with(density) {
        (resources.getDimensionPixelSize(R.dimen.nav_floating_height) +
                resources.getDimensionPixelSize(R.dimen.nav_floating_margin)).toDp()
    }
}

// The app icon from its resources, rasterized at the size of the row
@Composable
internal fun AppIcon(packageName: String, icon: Int, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pixels = with(density) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, packageName, icon, pixels) {
        value = withContext(Dispatchers.IO) {
            if (icon <= 0) null else runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, pixels, pixels)
                drawable.draw(Canvas(bitmap))
                bitmap
            }.getOrNull()
        }
    }
    val current = bitmap
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics {}
    ) {
        if (current == null)
            Icon(painterResource(R.drawable.ic_apps), null, Modifier.fillMaxSize())
        else
            Image(current.asImageBitmap(), null, Modifier.fillMaxSize())
    }
}

// The insets a screen keeps its content out of: the bars, the cutouts and the keyboard
@Composable
internal fun screenInsets(): WindowInsets = WindowInsets.systemBars
    .union(WindowInsets.displayCutout)
    .union(WindowInsets.ime)

// The shared frame of a compact screen bar; callers provide only its screen-specific row content
@Composable
internal fun CompactBar(
    alpha: Float,
    startPadding: Dp = 20.dp,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
    ) {
        Column(Modifier.height(64.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = startPadding, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

// Fades the compact bar in across the last 40% of the header; scrollOffset is within the first list item
internal fun compactHeaderAlpha(
    scrollOffset: Int,
    headerHeight: Int,
    pastHeader: Boolean = false
): Float = when {
    pastHeader -> 1f
    headerHeight <= 0 -> 0f
    else -> ((scrollOffset - headerHeight * 0.6f) / (headerHeight * 0.4f)).coerceIn(0f, 1f)
}

// The search of a screen, which replaces the title while it is shown: its icon, a way to clear it, and the keyboard
// closed by a search, when the screen has something to do with it
@Composable
internal fun SearchField(
    query: String,
    hint: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSearch: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)),
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        placeholder = { Text(hint, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.title_clear_search)
                    )
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = if (onSearch == null) KeyboardActions.Default
        else KeyboardActions(onSearch = { onSearch() })
    )
}

// The spinner of a screen which is loading, which screen readers announce once
@Composable
internal fun Loading(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.title_loading)
    CircularProgressIndicator(
        modifier.semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        }
    )
}

// What a screen says in the middle of it when it has nothing to show
@Composable
internal fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(32.dp),
        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        textAlign = TextAlign.Center
    )
}

// A small rounded label of an app, like its uid or that it is a system app
@Composable
internal fun AppBadge(text: String, description: String? = null) {
    Box(Modifier.padding(end = 6.dp)) {
        Text(
            text,
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (description == null) Modifier
                    else Modifier.clearAndSetSemantics { contentDescription = description }
                )
                .padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = Mono
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// A switch of a group or a hook: part of a group shows the icon of a partial state on a track of its own
@Composable
internal fun AppSwitch(
    checked: Boolean,
    partial: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.semantics { contentDescription = description },
        thumbContent = if (partial) {
            {
                Icon(
                    painterResource(R.drawable.ic_partial),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        } else null,
        colors = SwitchDefaults.colors(
            checkedTrackColor = if (partial)
                MaterialTheme.colorScheme.primary.copy(alpha = 0x80 / 255f)
            else
                MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
internal fun DoppelTheme(content: @Composable () -> Unit) {
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
    MaterialTheme(colorScheme = scheme, typography = DoppelTypography) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            content()
        }
    }
}
