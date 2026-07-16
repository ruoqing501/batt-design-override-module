package com.override.battcaplsp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
        UnifiedStatusType.SUCCESS -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        UnifiedStatusType.WARN -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        UnifiedStatusType.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        UnifiedStatusType.INFO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        UnifiedStatusType.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (type) {
        UnifiedStatusType.SUCCESS -> Icons.Default.CheckCircle
        UnifiedStatusType.WARN,
        UnifiedStatusType.ERROR -> Icons.Default.Warning
        UnifiedStatusType.INFO,
        UnifiedStatusType.UNKNOWN -> Icons.Default.Info
    }
    SuggestionChip(
        onClick = { },
        modifier = modifier,
        icon = {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showLabel != null) {
                    Text(
                        showLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                Text(
                    msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor
                )
            }
        },
        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = containerColor)
    )
}
