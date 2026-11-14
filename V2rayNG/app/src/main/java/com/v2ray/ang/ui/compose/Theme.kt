package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** FOROUZ v0.1: brand red is separate from verified-connectivity teal. */
internal object ForouzBrand {
    val action = Color(0xFFC62032)
    val onAction = Color(0xFFF4F1E8)
}

private val LightColor = lightColorScheme(
    primary = Color(0xFFC62032),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE0E2),
    onPrimaryContainer = Color(0xFF660615),
    secondary = Color(0xFF4D596B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE6F0),
    onSecondaryContainer = Color(0xFF17202E),
    tertiary = Color(0xFF006B66),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD2F3ED),
    onTertiaryContainer = Color(0xFF004D49),
    error = Color(0xFFA31836),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9E1),
    onErrorContainer = Color(0xFF650018),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1C2026),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C2026),
    surfaceVariant = Color(0xFFE6E9ED),
    onSurfaceVariant = Color(0xFF4D5868),
    outline = Color(0xFF657080),
    outlineVariant = Color(0xFFCDD2DA),
    inverseSurface = Color(0xFF252A32),
    inverseOnSurface = Color(0xFFF4F1E8),
    inversePrimary = Color(0xFFFF9299),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFC62032),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F2F0),
    surfaceContainer = Color(0xFFEDEEF0),
    surfaceContainerHigh = Color(0xFFE6E9ED),
    surfaceContainerHighest = Color(0xFFDFE3E8),
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFFFF6570),
    onPrimary = Color(0xFF300008),
    primaryContainer = Color(0xFF49232B),
    onPrimaryContainer = Color(0xFFFFDADD),
    secondary = Color(0xFFBFC7D4),
    onSecondary = Color(0xFF28313E),
    secondaryContainer = Color(0xFF303A48),
    onSecondaryContainer = Color(0xFFDFE7F4),
    tertiary = Color(0xFF21B6AD),
    onTertiary = Color(0xFF00201D),
    tertiaryContainer = Color(0xFF153C39),
    onTertiaryContainer = Color(0xFF9BF3E8),
    error = Color(0xFFFFB2C0),
    onError = Color(0xFF650018),
    errorContainer = Color(0xFF762139),
    onErrorContainer = Color(0xFFFFD9E1),
    background = Color(0xFF111318),
    onBackground = Color(0xFFF4F1E8),
    surface = Color(0xFF1C2026),
    onSurface = Color(0xFFF4F1E8),
    surfaceVariant = Color(0xFF303640),
    onSurfaceVariant = Color(0xFFAAB1BC),
    outline = Color(0xFF7A8596),
    outlineVariant = Color(0xFF3B4451),
    inverseSurface = Color(0xFFF4F1E8),
    inverseOnSurface = Color(0xFF252A32),
    inversePrimary = Color(0xFFC62032),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFFF6570),
    surfaceContainerLowest = Color(0xFF0D0F13),
    surfaceContainerLow = Color(0xFF191D23),
    surfaceContainer = Color(0xFF1C2026),
    surfaceContainerHigh = Color(0xFF252A32),
    surfaceContainerHighest = Color(0xFF303640),
)

// Semantic Colors
val colorPing = Color(0xFF009966) // Green
val colorPingRed = Color(0xFFFF0099) // Pink Red
val colorConfigType = Color(0xFFf97910) // Orange
val colorFabActive = Color(0xFFf97910) // Orange
val colorFabInactiveLight = Color(0xFF9C9C9C) // Gray
val colorFabInactiveDark = Color(0xFF646464) // Dark Gray
val dividerColorLight = Color(0xFFE0E0E0) // Light Gray
val dividerColorDark = Color(0xFF424242) // Dark Gray

// Toast Colors 85%
val toastNormalBgLight = Color(0xD9353A3E) // Dark Gray
val toastNormalBgDark = Color(0xD94A4F54) // Darker Gray
val toastSuccessBg = Color(0xD9388E3C) // Green
val toastErrorBg = Color(0xD9D50000) // Red
val toastInfoBg = Color(0xD93F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    const val DEFAULT_MODE = "2"
    const val DEFAULT_DYNAMIC_COLOR = false

    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_MODE) ?: DEFAULT_MODE
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_MODE) ?: DEFAULT_MODE
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColor
        else -> LightColor
    }
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FrozTypography,
            shapes = FrozShapes,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
