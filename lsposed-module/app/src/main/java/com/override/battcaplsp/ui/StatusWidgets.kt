package com.override.battcaplsp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.override.battcaplsp.ui.theme.AppAnimations
import com.override.battcaplsp.ui.theme.ColorRoles

/** 统一的状态类型 */
enum class UnifiedStatusType { SUCCESS, WARN, ERROR, INFO, UNKNOWN }

data class ParsedStatus(val type: UnifiedStatusType, val message: String)

fun parseStatus(raw: String): ParsedStatus {
    val idx = raw.indexOf(":")
    if (idx in 1..20) {
        val prefix = raw.substring(0, idx).uppercase()
        val rest = raw.substring(idx + 1).trim().ifEmpty { raw }
        val t = when (prefix) {
            "SUCCESS" -> UnifiedStatusType.SUCCESS
            "WARN" -> UnifiedStatusType.WARN
            "ERROR" -> UnifiedStatusType.ERROR
            "INFO" -> UnifiedStatusType.INFO
            "UNKNOWN" -> UnifiedStatusType.UNKNOWN
            else -> return ParsedStatus(UnifiedStatusType.INFO, raw)
        }
        return ParsedStatus(t, rest)
    }
    return ParsedStatus(UnifiedStatusType.INFO, raw)
}

// 去除前缀（用于 Toast 或日志写入避免重复解析）
fun String.stripStatusPrefix(): String {
    val idx = indexOf(":")
    return if (idx in 1..20) substring(idx + 1).trim() else this
}

@Composable
fun StatusBadge(raw: String, modifier: Modifier = Modifier, showLabel: String? = null) {
    val (type, msg) = parseStatus(raw)
    val (containerColor, contentColor) = when (type) {
        UnifiedStatusType.SUCCESS -> ColorRoles.successContainer to ColorRoles.onSuccessContainer
        UnifiedStatusType.WARN -> ColorRoles.warningContainer to ColorRoles.onWarningContainer
        UnifiedStatusType.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        UnifiedStatusType.INFO -> ColorRoles.infoContainer to ColorRoles.onInfoContainer
        UnifiedStatusType.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (type) {
        UnifiedStatusType.SUCCESS -> Icons.Default.CheckCircle
        UnifiedStatusType.WARN,
        UnifiedStatusType.ERROR -> Icons.Default.Warning
        UnifiedStatusType.INFO,
        UnifiedStatusType.UNKNOWN -> Icons.Default.Info
    }

    // 状态变化时触发轻微缩放动画
    val targetScale = rememberUpdatedState(newValue = if (type == UnifiedStatusType.UNKNOWN) 1f else 1.05f)
    val scale by animateFloatAsState(
        targetValue = targetScale.value,
        animationSpec = AppAnimations.appTween(AppAnimations.Fast),
        label = "status_badge_scale"
    )
    val animatedContainer by animateColorAsState(
        targetValue = containerColor,
        animationSpec = AppAnimations.appTween(),
        label = "status_badge_container"
    )
    val animatedContent by animateColorAsState(
        targetValue = contentColor,
        animationSpec = AppAnimations.appTween(),
        label = "status_badge_content"
    )

    Row(
        modifier = modifier
            .scale(scale)
            .background(animatedContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = animatedContent,
            modifier = Modifier.size(16.dp)
        )
        if (showLabel != null) {
            Text(
                showLabel,
                style = MaterialTheme.typography.labelSmall,
                color = animatedContent.copy(alpha = 0.8f)
            )
        }
        Text(
            msg,
            style = MaterialTheme.typography.labelMedium,
            color = animatedContent
        )
    }
}
