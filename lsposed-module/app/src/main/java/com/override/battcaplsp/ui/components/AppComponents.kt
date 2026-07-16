package com.override.battcaplsp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.override.battcaplsp.ui.StatusBadge
import com.override.battcaplsp.ui.UnifiedStatusType
import com.override.battcaplsp.ui.parseStatus
import com.override.battcaplsp.ui.theme.AppAnimations
import com.override.battcaplsp.ui.theme.AppDimensions
import com.override.battcaplsp.ui.theme.ColorRoles
import kotlinx.coroutines.launch

/** 统一卡片：24dp 大圆角、柔和阴影、可选渐变背景、点击涟漪 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    gradient: Brush? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.extraLarge)
    val clickableModifier = if (onClick != null) {
        cardModifier.clickable(onClick = onClick)
    } else cardModifier

    Card(
        modifier = clickableModifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (gradient != null) Color.Transparent
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 4.dp
        )
    ) {
        Box(
            modifier = if (gradient != null) {
                Modifier
                    .fillMaxWidth()
                    .background(gradient)
            } else Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(AppDimensions.SpaceMedium)) {
                content()
            }
        }
    }
}

/** 分组标题：左侧图标 + 标题 + 说明，支持展开/折叠箭头动画 */
@Composable
fun SectionHeader(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    expanded: Boolean? = null,
    onExpandToggle: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpaceSmall)
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .animateContentSize(animationSpec = AppAnimations.appTween())
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
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
        if (expanded != null && onExpandToggle != null) {
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = AppAnimations.appTween(),
                label = "expand_rotation"
            )
            IconButton(onClick = onExpandToggle) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 设置开关行：左侧图标、标题、说明、右侧 Switch */
@Composable
fun PreferenceSwitch(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = AppDimensions.SpaceSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpaceSmall),
            modifier = Modifier.weight(1f)
        ) {
            icon?.let {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column {
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
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** 设置项行：左侧图标、标题、说明、右侧箭头/值，点击涟漪 */
@Composable
fun PreferenceItem(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .then(clickableModifier)
            .padding(vertical = AppDimensions.SpaceSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpaceSmall),
            modifier = Modifier.weight(1f)
        ) {
            icon?.let {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column {
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
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 主/次操作按钮：16dp 圆角、点击缩放动画 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = AppAnimations.appTween(AppAnimations.Fast),
        label = "button_scale"
    )
    val shape = RoundedCornerShape(AppDimensions.ButtonRadius)
    Box(modifier = modifier.scale(scale)) {
        if (secondary) {
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                shape = shape,
                interactionSource = interactionSource
            ) {
                ActionButtonContent(text, icon)
            }
        } else {
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                shape = shape,
                interactionSource = interactionSource
            ) {
                ActionButtonContent(text, icon)
            }
        }
    }
}

@Composable
private fun ActionButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
    }
    Text(text)
}

/** 根据状态类型显示对应 container 色、图标、动画出现 */
@Composable
fun AppStatusCard(
    status: String,
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    val parsed = parseStatus(status)
    val (containerColor, contentColor, icon) = when (parsed.type) {
        UnifiedStatusType.SUCCESS ->
            Triple(
                ColorRoles.successContainer,
                ColorRoles.onSuccessContainer,
                Icons.Default.CheckCircle
            )

        UnifiedStatusType.WARN ->
            Triple(
                ColorRoles.warningContainer,
                ColorRoles.onWarningContainer,
                Icons.Default.Warning
            )

        UnifiedStatusType.ERROR ->
            Triple(
                ColorRoles.errorContainer,
                ColorRoles.onErrorContainer,
                Icons.Default.Error
            )

        UnifiedStatusType.INFO ->
            Triple(
                ColorRoles.infoContainer,
                ColorRoles.onInfoContainer,
                Icons.Default.Info
            )

        UnifiedStatusType.UNKNOWN ->
            Triple(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
                Icons.AutoMirrored.Filled.Help
            )
    }
    AnimatedVisibility(
        visible = visible,
        enter = AppAnimations.itemEnter(),
        exit = AppAnimations.fadeOut()
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Row(
                modifier = Modifier.padding(AppDimensions.SpaceMedium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(AppDimensions.SpaceSmall))
                Text(
                    text = parsed.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 内核参数键值对行，带复制按钮 */
@Composable
fun KeyValueItem(
    key: String,
    value: String?,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable {
                val copyValue = "$key=${value ?: ""}"
                clipboard.setText(AnnotatedString(copyValue))
                onCopy?.invoke()
                copied = true
                scope.launch {
                    kotlinx.coroutines.delay(1200)
                    copied = false
                }
            }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value ?: "<null>",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.55f)
        )
        AnimatedVisibility(visible = copied) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已复制",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 底部导航栏：带图标和动画 */
@Composable
fun AppBottomBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit
) {
    NavigationBar(
        tonalElevation = 2.dp
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = selectedIndex == index
            NavigationBarItem(
                icon = { BottomBarIcon(label, selected) },
                label = {
                    Text(
                        label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                selected = selected,
                onClick = { onSelectedChange(index) },
                alwaysShowLabel = true
            )
        }
    }
}

@Composable
private fun BottomBarIcon(label: String, selected: Boolean) {
    val icon = when (label) {
        "状态" -> Icons.Default.Dashboard
        "电池" -> Icons.Default.BatteryFull
        "充电" -> Icons.Default.ElectricBolt
        "设置" -> Icons.Default.Settings
        "调试" -> Icons.Default.BugReport
        else -> Icons.Default.Circle
    }
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = AppAnimations.appTween(AppAnimations.Fast),
        label = "bottom_bar_icon_scale"
    )
    Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.scale(scale)
    )
}

/** 设置分组卡片 */
@Composable
fun PreferenceGroup(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(modifier = modifier) {
        SectionHeader(title = title, icon = icon)
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        content()
    }
}

/** 圆形加载指示器 + 提示文字 */
@Composable
fun AnimatedLoadingIndicator(
    text: String = "加载中...",
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpaceSmall)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 空状态：图标 + 标题 + 说明 + 操作按钮 */
@Composable
fun EmptyState(
    title: String,
    description: String? = null,
    icon: ImageVector = Icons.Default.Inbox,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppDimensions.SpaceLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpaceSmall)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(AppDimensions.SpaceSmall))
            ActionButton(text = actionText, onClick = onAction, secondary = true)
        }
    }
}

/** 错误状态：图标 + 标题 + 说明 + 重试按钮 */
@Composable
fun ErrorState(
    title: String,
    description: String? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    EmptyState(
        title = title,
        description = description,
        icon = Icons.Default.ErrorOutline,
        actionText = if (onRetry != null) "重试" else null,
        onAction = onRetry,
        modifier = modifier
    )
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
