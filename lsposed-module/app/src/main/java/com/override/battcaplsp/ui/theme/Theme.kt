package com.override.battcaplsp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 统一设计系统尺寸规范 */
object AppDimensions {
    val SpaceSmall = 8.dp
    val SpaceMedium = 16.dp
    val SpaceLarge = 24.dp
    val CardRadius = 16.dp
    val ButtonRadius = 12.dp
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(AppDimensions.CardRadius),
    large = RoundedCornerShape(AppDimensions.CardRadius),
    extraLarge = RoundedCornerShape(AppDimensions.CardRadius)
)

@Composable
fun AppTheme(useDynamic: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colorScheme = if (useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content
    )
}
