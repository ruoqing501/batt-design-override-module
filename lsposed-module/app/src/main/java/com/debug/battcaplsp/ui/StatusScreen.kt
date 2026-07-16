package com.debug.battcaplsp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.debug.battcaplsp.core.OpEvents
import com.override.battcaplsp.core.ModuleManager
import com.override.battcaplsp.core.RootShell
import com.override.battcaplsp.ui.components.*
import com.override.battcaplsp.ui.theme.AppDimensions
import kotlinx.coroutines.launch

object StatusStateHolder {
    var rootStatus: String = "(查询中)"
    var kernelVersion: String = "(查询中)"
    var loaded: Boolean = false
    var candidates: List<String> = emptyList()
    var lastUpdate: Long = 0

    fun isFresh(): Boolean = System.currentTimeMillis() - lastUpdate < 30000
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StatusScreen(moduleManager: ModuleManager) {
    val scope = rememberCoroutineScope()
    var kernelVersion by remember { mutableStateOf(StatusStateHolder.kernelVersion) }
    var rootStatus by remember { mutableStateOf(StatusStateHolder.rootStatus) }
    var loaded by remember { mutableStateOf(StatusStateHolder.loaded) }
    var candidates by remember { mutableStateOf(StatusStateHolder.candidates) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (StatusStateHolder.isFresh() && StatusStateHolder.rootStatus != "(查询中)") return@LaunchedEffect
        loading = true
        try {
            val rs = RootShell.getRootStatus(forceRefresh = false)
            rootStatus = rs.message
            loaded = moduleManager.isLoaded()
            val kv = moduleManager.getKernelVersion()
            kernelVersion = kv.full
            candidates = moduleManager.listCandidateModuleNames()

            StatusStateHolder.rootStatus = rootStatus
            StatusStateHolder.kernelVersion = kernelVersion
            StatusStateHolder.loaded = loaded
            StatusStateHolder.candidates = candidates
            StatusStateHolder.lastUpdate = System.currentTimeMillis()
            OpEvents.info("状态刷新完成")
        } catch (t: Throwable) {
            OpEvents.error("状态初始化失败: ${t.message}")
        } finally { loading = false }
    }

    val events = OpEvents.events

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = AppDimensions.SpaceMedium, vertical = AppDimensions.SpaceSmall)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpaceMedium)
    ) {
        AppCard {
            SectionHeader(
                title = "系统与模块状态",
                icon = Icons.Default.Dashboard,
                description = "Root、内核版本与模块加载状态概览"
            )
            Spacer(Modifier.height(AppDimensions.SpaceSmall))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpaceSmall)
            ) {
                StatusInfoCard(
                    title = "Root",
                    content = rootStatus,
                    icon = Icons.Default.Security,
                    modifier = Modifier.weight(1f)
                )
                StatusInfoCard(
                    title = "模块加载",
                    content = if (loaded) "已加载" else "未加载",
                    icon = if (loaded) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(AppDimensions.SpaceSmall))
            StatusInfoCard(
                title = "内核版本",
                content = kernelVersion,
                icon = Icons.Default.Memory
            )
        }

        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(
                    title = "候选 .ko 文件名",
                    icon = Icons.Default.FolderOpen,
                    description = "可尝试加载的内核模块文件名"
                )
                ActionButton(
                    text = "刷新候选",
                    icon = Icons.Default.Refresh,
                    onClick = {
                        scope.launch {
                            loading = true
                            try {
                                candidates = moduleManager.listCandidateModuleNames()
                                StatusStateHolder.candidates = candidates
                                OpEvents.success("候选名刷新成功")
                            } catch (t: Throwable) {
                                OpEvents.error("获取候选失败: ${t.message}")
                            } finally { loading = false }
                        }
                    },
                    enabled = !loading,
                    secondary = true
                )
            }
            Spacer(Modifier.height(AppDimensions.SpaceSmall))
            if (candidates.isEmpty()) {
                Text(
                    "(无)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    candidates.forEach { c ->
                        SuggestionChip(
                            onClick = { },
                            label = { Text(c, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        ButtonRow {
            ActionButton(
                text = "重新检测",
                icon = Icons.Default.Refresh,
                onClick = {
                    scope.launch {
                        loading = true
                        try {
                            val rs = RootShell.getRootStatus(forceRefresh = true)
                            rootStatus = rs.message
                            loaded = moduleManager.isLoaded()
                            val kv = moduleManager.getKernelVersion()
                            kernelVersion = kv.full
                            StatusStateHolder.rootStatus = rootStatus
                            StatusStateHolder.kernelVersion = kernelVersion
                            StatusStateHolder.loaded = loaded
                            StatusStateHolder.lastUpdate = System.currentTimeMillis()
                            OpEvents.success("状态刷新成功")
                        } catch (t: Throwable) {
                            OpEvents.error("刷新失败: ${t.message}")
                        } finally { loading = false }
                    }
                },
                enabled = !loading
            )
            ActionButton(
                text = "清空事件",
                icon = Icons.Default.ClearAll,
                onClick = { OpEvents.clear() },
                secondary = true
            )
        }

        PreferenceGroup(title = "最近事件", icon = Icons.Default.History) {
            if (events.isEmpty()) {
                Text(
                    "(暂无事件)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(events) { e ->
                        val color = when (e.type) {
                            OpEvents.Event.Type.SUCCESS -> MaterialTheme.colorScheme.primary
                            OpEvents.Event.Type.WARN -> MaterialTheme.colorScheme.tertiary
                            OpEvents.Event.Type.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.secondary
                        }
                        Text(
                            "${e.time} [${e.type}] ${e.msg}",
                            color = color,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(AppDimensions.SpaceLarge))
    }
}

@Composable
private fun StatusInfoCard(
    title: String,
    content: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(AppDimensions.SpaceSmall)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
