package com.override.battcaplsp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.override.battcaplsp.ui.theme.AppDimensions

/**
 * 终端风格日志查看组件（Material You 化）
 * 功能:
 * - 固定等宽字体、深色背景、可滚动
 * - 支持复制、清空、自动滚动到底部
 * - 限制最大行数（由调用方处理截断逻辑）
 */
@Composable
fun LogViewer(
    title: String,
    logText: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    maxHeight: Int = 500,
    autoScroll: Boolean = true
) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 当日志变化且需要自动滚动时，滚动到底部
    LaunchedEffect(logText) {
        if (autoScroll) {
            // 延迟一帧等待布局完成
            kotlinx.coroutines.delay(16)
            vScroll.scrollTo(vScroll.maxValue)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(Modifier.padding(AppDimensions.SpaceMedium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(logText.ifBlank { "(空)" }))
                        copied = true
                        scope.launch {
                            kotlinx.coroutines.delay(1200)
                            copied = false
                        }
                    }) {
                        Icon(
                            if (copied) Icons.Default.ContentCopy else Icons.Default.ContentCopy,
                            contentDescription = "复制"
                        )
                    }
                    if (onClear != null) {
                        IconButton(onClick = { onClear() }) {
                            Icon(Icons.Default.Delete, contentDescription = "清空")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = maxHeight.dp)
                    .background(Color(0xFF111111), shape = MaterialTheme.shapes.large)
                    .padding(12.dp)
            ) {
                if (logText.isBlank()) {
                    Text(
                        "(暂无日志)",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll)
                    ) {
                        Text(
                            logText,
                            color = Color(0xFFEEEEEE),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }
        }
    }
}
