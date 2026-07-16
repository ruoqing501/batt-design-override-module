package com.override.battcaplsp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 扩展语义容器色
 * 从 MaterialTheme.colorScheme 派生主题亮度，并回退到固定色，
 * 用于 AppStatusCard 等组件表达 success / warning / error / info 状态。
 */
object ColorRoles {
    val successContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = if (!isSystemInDarkTheme()) Color(0xFFD1E8D3) else Color(0xFF1B5E20)

    val onSuccessContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = if (!isSystemInDarkTheme()) Color(0xFF1B5E20) else Color(0xFFD1E8D3)

    val warningContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = if (!isSystemInDarkTheme()) Color(0xFFFFF4D6) else Color(0xFF5C4813)

    val onWarningContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = if (!isSystemInDarkTheme()) Color(0xFF5C4813) else Color(0xFFFFF4D6)

    val errorContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer

    val onErrorContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer

    val infoContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = if (!isSystemInDarkTheme()) Color(0xFFE3F2FD) else Color(0xFF0D47A1)

    val onInfoContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = if (!isSystemInDarkTheme()) Color(0xFF0D47A1) else Color(0xFFE3F2FD)
}
