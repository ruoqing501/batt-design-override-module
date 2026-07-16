package com.debug.battcaplsp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.override.battcaplsp.core.GitHubReleaseClient
import com.override.battcaplsp.core.RootShell
import com.override.battcaplsp.core.ModuleManager
import com.override.battcaplsp.ui.LogViewer
import com.override.battcaplsp.ui.components.*
import com.override.battcaplsp.ui.theme.AppDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.debug.battcaplsp.core.TempModuleTester

/**
 * 内部调试面板（仅 Debug 构建可用）
 */
@Composable
fun DebugPanel(moduleManager: ModuleManager) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var rootStatus by remember { mutableStateOf("(未查询)") }
    var paramsDump by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val tester = remember(context) { TempModuleTester(context = context) }
    var tempUrl by remember { mutableStateOf(TextFieldValue("")) }
    var tempModuleName by remember { mutableStateOf(TextFieldValue("")) }
    var tempParams by remember { mutableStateOf(TextFieldValue("")) }
    var tempDownloadMsg by remember { mutableStateOf("") }
    var tempLoadMsg by remember { mutableStateOf("") }
    var tempDmesg by remember { mutableStateOf("") }
    var tempListRefreshToggle by remember { mutableStateOf(false) }
    val downloaded = remember(tempListRefreshToggle) { tester.listDownloaded() }

    val ghClient = remember { GitHubReleaseClient() }
    var koAssets by remember { mutableStateOf<List<GitHubReleaseClient.KoAsset>>(emptyList()) }
    var loadingKoList by remember { mutableStateOf(false) }
    var showPreset by remember { mutableStateOf(false) }
    var koListError by remember { mutableStateOf<String?>(null) }

    var compatCollecting by remember { mutableStateOf(false) }
    var compatInfo by remember { mutableStateOf("") }
    var compatMsg by remember { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { innerPad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = AppDimensions.SpaceMedium, vertical = AppDimensions.SpaceSmall)
                .padding(innerPad)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppDimensions.SpaceMedium)
        ) {
            AppCard {
                SectionHeader(title = "Root 状态", icon = Icons.Default.Security)
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rootStatus,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    ActionButton(
                        text = "刷新 Root",
                        icon = Icons.Default.Refresh,
                        onClick = {
                            scope.launch {
                                loading = true
                                val status = RootShell.getRootStatus(forceRefresh = true)
                                rootStatus = status.message
                                loading = false
                            }
                        },
                        enabled = !loading,
                        secondary = true
                    )
                }
            }

            CollapsibleCard(
                title = "内核模块参数 Dump",
                icon = Icons.Default.Memory,
                description = "读取并查看当前模块参数"
            ) {
                ButtonRow {
                    ActionButton(
                        text = "读取",
                        icon = Icons.Default.Download,
                        onClick = {
                            scope.launch {
                                loading = true
                                try {
                                    val map = moduleManager.readAll()
                                    paramsDump = buildString {
                                        appendLine("参数 (存在=值 / 不存在=null)")
                                        for ((k, v) in map) appendLine("$k = ${v ?: "<null>"}")
                                    }
                                } finally { loading = false }
                            }
                        },
                        enabled = !loading
                    )
                    ActionButton(
                        text = "清空",
                        icon = Icons.Default.Clear,
                        onClick = { paramsDump = "" },
                        secondary = true
                    )
                }
                if (paramsDump.isNotBlank()) {
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    LogViewer(
                        title = "参数",
                        logText = paramsDump,
                        onClear = { paramsDump = "" },
                        maxHeight = 200
                    )
                }
            }

            CollapsibleCard(
                title = "临时内核模块测试",
                icon = Icons.Default.Science,
                description = "1. 从给定 URL 下载 .ko 到应用内部目录  2. 手动 insmod  3. 抓取最近 dmesg"
            ) {
                OutlinedTextField(
                    tempUrl,
                    { tempUrl = it },
                    label = { Text(".ko 下载 URL") },
                    supportingText = { Text("http(s):// 开头, <=5MB") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                )
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                OutlinedTextField(
                    tempModuleName,
                    { tempModuleName = it },
                    label = { Text("模块名 (不含.ko)") },
                    supportingText = { Text("用于 rmmod 与日志过滤") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                OutlinedTextField(
                    tempParams,
                    { tempParams = it },
                    label = { Text("加载参数 可选") },
                    supportingText = { Text("格式: key1=val1 key2=val2") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                )
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                ButtonRow {
                    ActionButton(
                        text = "下载 .ko",
                        icon = Icons.Default.Download,
                        onClick = {
                            val url = tempUrl.text.trim()
                            if (url.isEmpty()) {
                                scope.launch { snackbarHostState.showSnackbar("URL 不能为空") }
                                return@ActionButton
                            }
                            scope.launch {
                                loading = true
                                tempDownloadMsg = "正在下载..."
                                tempLoadMsg = ""
                                tempDmesg = ""
                                val res = tester.download(url)
                                loading = false
                                tempDownloadMsg = if (res.success) {
                                    tempListRefreshToggle = !tempListRefreshToggle
                                    "SUCCESS: 下载完成 -> ${res.path} (${res.size}B)"
                                } else {
                                    "ERROR: ${res.error}"
                                }
                            }
                        },
                        enabled = !loading
                    )
                    Box {
                        ActionButton(
                            text = if (loadingKoList) "加载中" else "Release 预设",
                            icon = Icons.Default.CloudDownload,
                            onClick = {
                                showPreset = true
                                if (koAssets.isEmpty() && !loadingKoList) {
                                    scope.launch {
                                        loadingKoList = true
                                        koListError = null
                                        val list = try {
                                            ghClient.listLatestKoAssets()
                                        } catch (e: Throwable) {
                                            emptyList()
                                        }
                                        loadingKoList = false
                                        if (list.isEmpty()) koListError = "未获取到 .ko Release 资产" else koAssets = list
                                    }
                                }
                            },
                            secondary = true
                        )
                        DropdownMenu(expanded = showPreset, onDismissRequest = { showPreset = false }) {
                            if (loadingKoList) {
                                DropdownMenuItem(text = { Text("正在加载...") }, onClick = { })
                            } else if (koListError != null) {
                                DropdownMenuItem(
                                    text = { Text(koListError ?: "错误", maxLines = 2) },
                                    onClick = { showPreset = false }
                                )
                            } else if (koAssets.isEmpty()) {
                                DropdownMenuItem(text = { Text("无 .ko 资产") }, onClick = { showPreset = false })
                            } else {
                                koAssets.forEach { asset ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${asset.name} (${asset.tag})",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        onClick = {
                                            tempUrl = TextFieldValue(asset.downloadUrl)
                                            showPreset = false
                                            scope.launch { snackbarHostState.showSnackbar("选择: ${asset.name}") }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    ActionButton(
                        text = "刷新列表",
                        icon = Icons.Default.Refresh,
                        onClick = { tempListRefreshToggle = !tempListRefreshToggle },
                        enabled = downloaded.isNotEmpty() && !loading,
                        secondary = true
                    )
                    ActionButton(
                        text = "清理全部",
                        icon = Icons.Default.DeleteOutline,
                        onClick = {
                            val count = tester.deleteAll()
                            tempListRefreshToggle = !tempListRefreshToggle
                            tempDownloadMsg = "INFO: 已删除 $count 个"
                            tempLoadMsg = ""
                            tempDmesg = ""
                        },
                        enabled = downloaded.isNotEmpty() && !loading,
                        secondary = true
                    )
                }
                if (tempDownloadMsg.isNotBlank()) {
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    AppStatusCard(status = tempDownloadMsg)
                }
                if (downloaded.isNotEmpty()) {
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    Text("已下载模块:", style = MaterialTheme.typography.titleSmall)
                    downloaded.forEach { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(f.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${f.length()}B",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val modName =
                                    if (tempModuleName.text.trim().isNotEmpty()) tempModuleName.text.trim() else f.name.removeSuffix(
                                        ".ko"
                                    )
                                scope.launch {
                                    if (modName.isBlank()) {
                                        snackbarHostState.showSnackbar("请填写模块名")
                                        return@launch
                                    }
                                    val root = RootShell.getRootStatus(forceRefresh = false)
                                    if (!root.available) {
                                        snackbarHostState.showSnackbar("缺少 Root 权限，无法 insmod")
                                        return@launch
                                    }
                                    loading = true
                                    tempLoadMsg = "正在加载 ${f.name}..."
                                    tempDmesg = ""
                                    val paramsMap = tempParams.text.trim().split(Regex("\\s+"))
                                        .mapNotNull { p ->
                                            if (p.isBlank()) null else p.split('=', limit = 2)
                                                .let { kv -> if (kv.size == 2) kv[0] to kv[1] else null }
                                        }.toMap()
                                    val load = tester.insmod(f.absolutePath, paramsMap)
                                    loading = false
                                    tempLoadMsg = if (load.success) "SUCCESS: 加载成功" else "ERROR: ${load.error}"
                                    tempDmesg = load.dmesg
                                }
                            }) { Text("加载") }
                        }
                    }
                }
                if (tempLoadMsg.isNotBlank()) {
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    AppStatusCard(status = tempLoadMsg)
                }
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                ButtonRow {
                    ActionButton(
                        text = "卸载模块",
                        icon = Icons.Default.PowerOff,
                        onClick = {
                            scope.launch {
                                val name = tempModuleName.text.trim().ifBlank {
                                    downloaded.firstOrNull()?.name?.removeSuffix(".ko") ?: ""
                                }
                                if (name.isBlank()) {
                                    snackbarHostState.showSnackbar("无模块名可卸载")
                                    return@launch
                                }
                                val root = RootShell.getRootStatus(forceRefresh = false)
                                if (!root.available) {
                                    snackbarHostState.showSnackbar("缺少 Root 权限，无法卸载")
                                    return@launch
                                }
                                loading = true
                                val r = tester.rmmod(name)
                                loading = false
                                tempLoadMsg = if (r.success) "SUCCESS: 卸载 $name" else "ERROR: 卸载失败 ${r.error}"
                            }
                        },
                        enabled = !loading,
                        secondary = true
                    )
                    ActionButton(
                        text = "抓取日志",
                        icon = Icons.Default.Terminal,
                        onClick = {
                            scope.launch {
                                val name = tempModuleName.text.trim().ifBlank {
                                    downloaded.firstOrNull()?.name?.removeSuffix(".ko") ?: ""
                                }
                                if (name.isBlank()) {
                                    snackbarHostState.showSnackbar("缺少关键字")
                                    return@launch
                                }
                                loading = true
                                tempDmesg = tester.collectModuleLogs(name)
                                loading = false
                            }
                        },
                        secondary = true
                    )
                }
                if (tempDmesg.isNotBlank()) {
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    LogViewer(
                        title = "内核日志 (tail)",
                        logText = tempDmesg,
                        onClear = { tempDmesg = "" },
                        maxHeight = 260
                    )
                }
            }

            CollapsibleCard(
                title = "设备内核兼容信息采集",
                icon = Icons.Default.DeveloperBoard,
                description = "用于分析 .ko 不兼容：一次脚本采集内核版本/vermagic/模块/配置/prop/安全状态等。"
            ) {
                ButtonRow {
                    ActionButton(
                        text = "采集信息",
                        icon = Icons.Default.Download,
                        onClick = {
                            scope.launch {
                                compatCollecting = true
                                compatMsg = "正在采集..."
                                compatInfo = ""
                                val script = """
                                echo '==== uname -a ===='; uname -a
                                echo '==== /proc/version ===='; cat /proc/version 2>/dev/null || echo 'N/A'
                                echo '==== arch ===='; uname -m
                                echo '==== randomize_va_space ===='; cat /proc/sys/kernel/randomize_va_space 2>/dev/null || echo 'N/A'
                                echo '==== kernel tainted ===='; cat /proc/sys/kernel/tainted 2>/dev/null || echo 'N/A'
                                echo '==== /proc/modules (head 60) ===='; head -n 60 /proc/modules 2>/dev/null || echo 'N/A'
                                echo '==== getprop (key subset) ===='
                                for K in ro.product.device ro.product.model ro.product.board ro.hardware ro.boot.hardware.platform \
                                         ro.bootloader ro.build.version.release ro.build.version.sdk ro.build.id ro.build.fingerprint \
                                         ro.build.flavor ro.miui.ui.version.name ro.boot.verifiedbootstate ro.boot.vbmeta.device_state; do
                                  VAL="${'$'}(getprop ${'$'}K)"; echo ${'$'}K="${'$'}VAL";
                                done
                                echo '==== /proc/config.gz (filtered) ===='
                                if [ -f /proc/config.gz ]; then
                                  zcat /proc/config.gz 2>/dev/null | egrep 'CONFIG_(MODULES|MODVERSIONS|LOCALVERSION|PREEMPT|ANDROID|KALLSYMS|KERNEL_LZ4|ARM64_PTR_AUTH|ARM64_MTE|CFI_CLANG|LTO|BPF|BPF_SYSCALL|STACKPROTECTOR|KASLR)' | head -n 120
                                else
                                  echo '不可用'
                                fi
                                echo '==== vermagic probe ===='
                                grep -Rhs 'vermagic=' /sys/module/*/sections 2>/dev/null | head -n 2 || echo '未找到'
                                echo '==== cpuinfo (Features line) ===='
                                grep -m1 '^Features' /proc/cpuinfo 2>/dev/null || echo 'N/A'
                                echo '==== vendor modules dir listing ===='
                                if [ -d /vendor/lib/modules ]; then ls -1 /vendor/lib/modules | head -n 80; else echo '目录不存在'; fi
                                echo '==== dmesg (module tail 80) ===='
                                dmesg | grep -i module | tail -n 80 2>/dev/null || echo 'N/A'
                            """.trimIndent()
                                val res = RootShell.exec(script)
                                compatInfo =
                                    (res.out + if (res.err.isNotBlank()) "\n[STDERR]\n${res.err}" else "").trim()
                                compatCollecting = false
                                compatMsg = "完成 (单次脚本)"
                            }
                        },
                        enabled = !compatCollecting
                    )
                    ActionButton(
                        text = "复制",
                        icon = Icons.Default.ContentCopy,
                        onClick = {
                            scope.launch {
                                try {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("compat", compatInfo))
                                    snackbarHostState.showSnackbar("已复制")
                                } catch (e: Throwable) {
                                    snackbarHostState.showSnackbar("复制失败: ${e.message}")
                                }
                            }
                        },
                        enabled = compatInfo.isNotBlank(),
                        secondary = true
                    )
                    ActionButton(
                        text = "保存文件",
                        icon = Icons.Default.Save,
                        onClick = {
                            scope.launch {
                                try {
                                    val file = java.io.File(context.filesDir, "compat_info.txt")
                                    file.writeText(compatInfo)
                                    snackbarHostState.showSnackbar("已保存: ${file.absolutePath}")
                                } catch (e: Throwable) {
                                    snackbarHostState.showSnackbar("保存失败: ${e.message}")
                                }
                            }
                        },
                        enabled = compatInfo.isNotBlank(),
                        secondary = true
                    )
                }
                if (compatMsg.isNotBlank()) {
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    Text(compatMsg, style = MaterialTheme.typography.bodySmall)
                }
                if (compatInfo.isNotBlank()) {
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    LogViewer(
                        title = "兼容信息",
                        logText = compatInfo,
                        onClear = { compatInfo = "" },
                        maxHeight = 360
                    )
                }
            }

            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(AppDimensions.SpaceLarge))
        }
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    AppCard {
        SectionHeader(
            title = title,
            icon = icon,
            description = description,
            expanded = expanded,
            onExpandToggle = { expanded = !expanded }
        )
        if (expanded) {
            Spacer(Modifier.height(AppDimensions.SpaceSmall))
            content()
        }
    }
}
