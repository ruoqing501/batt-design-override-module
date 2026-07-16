package com.override.battcaplsp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.override.battcaplsp.ui.StatusBadge
import com.override.battcaplsp.ui.UnifiedStatusType
import com.override.battcaplsp.ui.parseStatus
import com.override.battcaplsp.ui.theme.AppDimensions

/** 统一卡片：圆角、内边距、阴影一致 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(AppDimensions.SpaceMedium)) {
            content()
        }
    }
}

/** Section 标题 + 可选说明 */
@Composable
fun SectionHeader(
    title: String,
    description: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 设置开关行 */
@Composable
fun PreferenceSwitch(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = AppDimensions.SpaceSmall)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 主/次操作按钮 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false
) {
    val shape = RoundedCornerShape(AppDimensions.ButtonRadius)
    if (secondary) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape
        ) {
            Text(text)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape
        ) {
            Text(text)
        }
    }
}

/** 带背景色的状态提示卡片（比 Row 更醒目） */
@Composable
fun AppStatusCard(status: String, modifier: Modifier = Modifier) {
    val parsed = parseStatus(status)
    val (containerColor, contentColor) = when (parsed.type) {
        UnifiedStatusType.SUCCESS ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        UnifiedStatusType.WARN ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        UnifiedStatusType.ERROR ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        UnifiedStatusType.INFO,
        UnifiedStatusType.UNKNOWN ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    val icon = when (parsed.type) {
        UnifiedStatusType.SUCCESS -> Icons.Default.CheckCircle
        UnifiedStatusType.WARN,
        UnifiedStatusType.ERROR -> Icons.Default.Warning
        UnifiedStatusType.INFO,
        UnifiedStatusType.UNKNOWN -> Icons.Default.Info
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(AppDimensions.SpaceMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.padding(end = AppDimensions.SpaceSmall)
            )
            Text(
                text = parsed.message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
    }
}

/** 内核参数键值对行 */
@Composable
fun KeyValueItem(key: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value ?: "<null>",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.55f)
        )
    }
}

/** 底部导航栏 */
@Composable
fun AppBottomBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit
) {
    NavigationBar {
        tabs.forEachIndexed { index, label ->
            NavigationBarItem(
                icon = { BottomBarIcon(label) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                selected = selectedIndex == index,
                onClick = { onSelectedChange(index) }
            )
        }
    }
}

@Composable
private fun BottomBarIcon(label: String) {
    val icon = when (label) {
        "状态" -> Icons.Default.Info
        "电池" -> Icons.Default.CheckCircle
        "充电" -> Icons.Default.Warning
        "设置" -> Icons.Default.Info
        "调试" -> Icons.Default.Warning
        else -> Icons.Default.Info
    }
    Icon(imageVector = icon, contentDescription = label)
}

/** 设置分组卡片 */
@Composable
fun PreferenceGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(modifier = modifier) {
        SectionHeader(title = title)
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        content()
    }
}

/** 统一设置项行（可点击） */
@Composable
fun PreferenceItem(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = AppDimensions.SpaceSmall)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
    Spacer(Modifier.height(AppDimensions.SpaceSmall))
}

/** 重导出旧版 StatusBadge，方便新组件统一引用 */
@Composable
fun StatusBadgeLine(
    raw: String,
    modifier: Modifier = Modifier,
    showLabel: String? = null
) {
    StatusBadge(raw = raw, modifier = modifier, showLabel = showLabel)
}

/** 辅助：水平按钮行间距 */
@Composable
fun ButtonRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(AppDimensions.SpaceSmall),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        content = content
    )
}

/** 水平分隔线（带上下间距） */
@Composable
fun SectionDivider() {
    Spacer(Modifier.height(AppDimensions.SpaceSmall))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(AppDimensions.SpaceSmall))
}
