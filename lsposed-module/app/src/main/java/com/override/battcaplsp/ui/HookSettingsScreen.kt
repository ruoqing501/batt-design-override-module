package com.override.battcaplsp.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.override.battcaplsp.BuildConfig
import com.override.battcaplsp.core.*
import com.override.battcaplsp.ui.components.*
import com.override.battcaplsp.ui.theme.AppDimensions
import com.debug.battcaplsp.core.OpEvents
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun extractKernelVersionFromVermagic(vermagic: String): String {
    if (vermagic.isBlank()) return ""
    val versionRegex = Regex("""(\d+\.\d+\.\d+)""")
    return versionRegex.find(vermagic)?.value ?: ""
}

private enum class StatusType { SUCCESS, ERROR, WARN, INFO, UPDATE }

private object HookSettingsStatusCache {
    var lastStatusLoadTime = 0L
    var lastModulesFetchTime = 0L
    var cachedModules: List<com.override.battcaplsp.core.KernelModuleDownloader.ModuleInfo> = emptyList()
    var rootStatus: RootShell.RootStatus? = null
    var battModuleLoaded: Boolean? = null
    var chgModuleLoaded: Boolean? = null
    var battModuleAvailable: Boolean? = null
    var chgModuleAvailable: Boolean? = null
    var kernelVersion: String = ""
    var kernelVersionDetail: String = ""
    var battModuleVermagic: String = ""
    var chgModuleVermagic: String = ""
    var magiskAvailable: Boolean? = null
    var magiskModuleInstalled: Boolean? = null
    var detectedKernelVersion: ModuleManager.KernelVersion? = null
}

@Composable
private fun StatusLine(type: StatusType, text: String) {
    val (icon, tint) = when (type) {
        StatusType.SUCCESS -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
        StatusType.ERROR -> Icons.Default.Warning to MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
        StatusType.WARN -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
        StatusType.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        StatusType.UPDATE -> Icons.Default.Info to MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
fun HookSettingsScreen(
    repo: HookSettingsRepository,
    onModuleInstalled: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val ui by repo.flow.collectAsState(initial = HookSettingsState())
    val context = LocalContext.current

    val battMgr = remember { ModuleManager() }
    val chgMgr = remember { ChgModuleManager() }
    val downloader = remember { com.override.battcaplsp.core.KernelModuleDownloader(context) }
    val magiskManager = remember { com.override.battcaplsp.core.MagiskModuleManager(context) }
    val githubClient = remember { com.override.battcaplsp.core.GitHubReleaseClient() }
    val safeInstaller = remember { com.override.battcaplsp.core.SafeModuleInstaller(context) }

    var rootStatus by remember { mutableStateOf(HookSettingsStatusCache.rootStatus) }
    var battModuleLoaded by remember { mutableStateOf(HookSettingsStatusCache.battModuleLoaded) }
    var chgModuleLoaded by remember { mutableStateOf(HookSettingsStatusCache.chgModuleLoaded) }
    var battModuleAvailable by remember { mutableStateOf(HookSettingsStatusCache.battModuleAvailable) }
    var chgModuleAvailable by remember { mutableStateOf(HookSettingsStatusCache.chgModuleAvailable) }
    var kernelVersion by remember { mutableStateOf(HookSettingsStatusCache.kernelVersion) }
    var kernelVersionDetail by remember { mutableStateOf(HookSettingsStatusCache.kernelVersionDetail) }
    var battModuleVermagic by remember { mutableStateOf(HookSettingsStatusCache.battModuleVermagic) }
    var chgModuleVermagic by remember { mutableStateOf(HookSettingsStatusCache.chgModuleVermagic) }
    var showRootDialog by remember { mutableStateOf(false) }
    var magiskAvailable by remember { mutableStateOf(HookSettingsStatusCache.magiskAvailable) }
    var magiskModuleInstalled by remember { mutableStateOf(HookSettingsStatusCache.magiskModuleInstalled) }
    var detectedKernelVersion by remember { mutableStateOf(HookSettingsStatusCache.detectedKernelVersion) }
    var availableModules by remember { mutableStateOf(HookSettingsStatusCache.cachedModules) }
    var downloadingModule by remember { mutableStateOf<String?>(null) }
    var moduleDownloadProgress by remember { mutableStateOf(0) }
    var moduleManagementMessage by remember { mutableStateOf("") }
    var lastTestFailureDetail by remember { mutableStateOf<String?>(null) }
    var showModuleDownloadDialog by remember { mutableStateOf(false) }
    var showCalibrationDialog by remember { mutableStateOf(false) }
    var isInstallingModule by remember { mutableStateOf(false) }
    var initialLoading by remember { mutableStateOf(HookSettingsStatusCache.lastStatusLoadTime == 0L) }

    var versionCheckResult by remember { mutableStateOf<com.override.battcaplsp.core.GitHubReleaseClient.VersionCheckResult?>(null) }
    var isCheckingVersion by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadingApk by remember { mutableStateOf(false) }
    var apkDownloadProgress by remember { mutableStateOf(0) }
    var apkDownloadId by remember { mutableStateOf<Long?>(null) }
    var apkLocalPath by remember { mutableStateOf<String?>(null) }
    var apkPhase by remember { mutableStateOf("idle") }
    val apkDownloadManager = remember { com.override.battcaplsp.core.ApkDownloadManager(context) }

    val isMiuiDevice = remember { DeviceUtils.isMiuiDevice() }

    val statusTtlMs = 5000L
    val modulesTtlMs = 60000L

    suspend fun loadStatus(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && !initialLoading && (now - HookSettingsStatusCache.lastStatusLoadTime) < statusTtlMs && rootStatus != null) return

        if (force) {
            RootShell.clearCache()
            battMgr.clearCache()
            chgMgr.clearCache()
        }

        coroutineScope {
            launch {
                val newRoot = RootShell.getRootStatus(forceRefresh = force)
                rootStatus = newRoot
                HookSettingsStatusCache.rootStatus = newRoot
            }
            launch {
                val newKernelVersion = runCatching { battMgr.getKernelVersion() }.getOrNull()
                val newKernelVersionStr = newKernelVersion?.majorMinor ?: "未知"
                val newKernelVersionDetailStr = newKernelVersion?.full?.split('-')?.take(2)?.joinToString("-") ?: ""
                detectedKernelVersion = newKernelVersion
                kernelVersion = newKernelVersionStr
                kernelVersionDetail = newKernelVersionDetailStr
                HookSettingsStatusCache.detectedKernelVersion = newKernelVersion
                HookSettingsStatusCache.kernelVersion = newKernelVersionStr
                HookSettingsStatusCache.kernelVersionDetail = newKernelVersionDetailStr
                if (newKernelVersion != null) {
                    val needFetchModules = HookSettingsStatusCache.cachedModules.isEmpty() || (now - HookSettingsStatusCache.lastModulesFetchTime) > modulesTtlMs
                    if (needFetchModules) {
                        val fetched = runCatching { downloader.getAvailableModules(newKernelVersion) }.getOrElse { emptyList() }
                        availableModules = fetched
                        HookSettingsStatusCache.cachedModules = fetched
                        HookSettingsStatusCache.lastModulesFetchTime = now
                    } else {
                        availableModules = HookSettingsStatusCache.cachedModules
                    }
                }
            }
            launch {
                val newBattLoaded = battMgr.isLoaded()
                battModuleLoaded = newBattLoaded
                HookSettingsStatusCache.battModuleLoaded = newBattLoaded
                if (newBattLoaded) {
                    val newBattVermagic = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val sysFile = File("/sys/module/batt_design_override/vermagic")
                            if (sysFile.exists()) return@withContext sysFile.readText().trim()
                            val vres = RootShell.exec("modinfo -F vermagic batt_design_override | head -1")
                            if (vres.code == 0 && vres.out.isNotBlank()) return@withContext vres.out.trim()
                        } catch (_: Throwable) { }
                        ""
                    }
                    battModuleVermagic = newBattVermagic
                    HookSettingsStatusCache.battModuleVermagic = newBattVermagic
                }
            }
            launch {
                val newChgLoaded = chgMgr.isLoaded()
                chgModuleLoaded = newChgLoaded
                HookSettingsStatusCache.chgModuleLoaded = newChgLoaded
                if (newChgLoaded) {
                    val newChgVermagic = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val sysFile = File("/sys/module/chg_param_override/vermagic")
                            if (sysFile.exists()) return@withContext sysFile.readText().trim()
                            val vres = RootShell.exec("modinfo -F vermagic chg_param_override | head -1")
                            if (vres.code == 0 && vres.out.isNotBlank()) return@withContext vres.out.trim()
                        } catch (_: Throwable) { }
                        ""
                    }
                    chgModuleVermagic = newChgVermagic
                    HookSettingsStatusCache.chgModuleVermagic = newChgVermagic
                }
            }
            launch {
                val newBattAvailable = battMgr.isAvailable()
                battModuleAvailable = newBattAvailable
                HookSettingsStatusCache.battModuleAvailable = newBattAvailable
            }
            launch {
                val newChgAvailable = chgMgr.isAvailable()
                chgModuleAvailable = newChgAvailable
                HookSettingsStatusCache.chgModuleAvailable = newChgAvailable
            }
            launch {
                val newMagiskAvail = magiskManager.isMagiskAvailable()
                magiskAvailable = newMagiskAvail
                HookSettingsStatusCache.magiskAvailable = newMagiskAvail
            }
            launch {
                val newMagiskInstalled = magiskManager.isModuleInstalled()
                magiskModuleInstalled = newMagiskInstalled
                HookSettingsStatusCache.magiskModuleInstalled = newMagiskInstalled
            }
        }
        initialLoading = false
        HookSettingsStatusCache.lastStatusLoadTime = now
    }

    LaunchedEffect(Unit) { loadStatus() }

    // 打开设置页时自动检测 App 更新
    LaunchedEffect(Unit) {
        if (isCheckingVersion || versionCheckResult != null) return@LaunchedEffect
        isCheckingVersion = true
        try {
            versionCheckResult = githubClient.checkForUpdates(context)
        } catch (e: Exception) {
            versionCheckResult = com.override.battcaplsp.core.GitHubReleaseClient.VersionCheckResult(
                hasUpdate = false,
                currentVersion = "未知",
                latestVersion = null,
                releaseInfo = null,
                error = "检查更新失败: ${e.message}"
            )
        }
        isCheckingVersion = false
        if (versionCheckResult?.hasUpdate == true) showUpdateDialog = true
    }

    var hookEnabled by remember { mutableStateOf(ui.hookEnabled) }
    var displayCapacity by remember { mutableStateOf(TextFieldValue(if (ui.displayCapacity > 0) ui.displayCapacity.toString() else "")) }
    var useSystemProp by remember { mutableStateOf(ui.useSystemProp) }
    var customCapacity by remember { mutableStateOf(TextFieldValue(if (ui.customCapacity > 0) ui.customCapacity.toString() else "")) }
    var hookTextView by remember { mutableStateOf(ui.hookTextView) }
    var hookSharedPrefs by remember { mutableStateOf(ui.hookSharedPrefs) }
    var hookJsonMethods by remember { mutableStateOf(ui.hookJsonMethods) }
    var launcherIconEnabled by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(ui) {
        hookEnabled = ui.hookEnabled
        displayCapacity = TextFieldValue(if (ui.displayCapacity > 0) ui.displayCapacity.toString() else "")
        useSystemProp = ui.useSystemProp
        customCapacity = TextFieldValue(if (ui.customCapacity > 0) ui.customCapacity.toString() else "")
        hookTextView = ui.hookTextView
        hookSharedPrefs = ui.hookSharedPrefs
        hookJsonMethods = ui.hookJsonMethods
        launcherIconEnabled = ui.launcherIconEnabled
    }

    if (showRootDialog && rootStatus != null) {
        AlertDialog(
            onDismissRequest = { showRootDialog = false },
            title = { Text("Root 权限状态") },
            text = { Text(rootStatus!!.message) },
            confirmButton = {
                TextButton(onClick = { showRootDialog = false }) { Text("确定") }
            },
            icon = {
                Icon(
                    if (rootStatus!!.available) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (rootStatus!!.available) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                )
            }
        )
    }

    suspend fun implicitTestAndInstall(
        moduleName: String,
        localPath: String,
        version: String,
        setMsg: (String) -> Unit
    ) {
        isInstallingModule = true
        setMsg("INFO:正在测试 $moduleName...")
        val test = safeInstaller.quickTestModule(moduleName, localPath, emptyMap())
        if (!test.passed) {
            val detail = buildString {
                append(test.message)
                test.executedCmd?.let { append("\n命令: "); append(it) }
            }
            lastTestFailureDetail = detail
            setMsg("ERROR:测试失败: ${test.message}")
            try { RootShell.exec("rmmod ${moduleName}") } catch (_: Throwable) { }
            isInstallingModule = false
            return
        }
        lastTestFailureDetail = null
        setMsg("INFO:测试通过，正在安装...")
        val autoLoad = true
        val detailed = magiskManager.installKernelModuleDetailed(
            moduleName = moduleName,
            koFilePath = localPath,
            version = version,
            autoLoad = autoLoad,
            loadParams = "verbose=1"
        )
        if (detailed.success) {
            val loadPart = if (autoLoad) { if (detailed.autoLoaded) "已加载生效" else "已安装(需重启)" } else "已安装"
            val errTail = if (detailed.errorMessages.isNotEmpty()) " | warn:${detailed.errorMessages.first()}" else ""
            setMsg("SUCCESS:$moduleName 安装成功 ($loadPart)$errTail")
            loadStatus(force = true)
            onModuleInstalled()
        } else {
            val reason = detailed.errorMessages.firstOrNull()?.take(120) ?: "未知原因"
            setMsg("ERROR:$moduleName 安装失败: $reason")
        }
        isInstallingModule = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = AppDimensions.SpaceMedium, vertical = AppDimensions.SpaceSmall)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppDimensions.SpaceMedium)
    ) {
        ModuleDownloadSection(
            kernelVersion = kernelVersion,
            kernelVersionDetail = kernelVersionDetail,
            battModuleAvailable = battModuleAvailable,
            battModuleLoaded = battModuleLoaded,
            battModuleVermagic = battModuleVermagic,
            chgModuleAvailable = chgModuleAvailable,
            chgModuleLoaded = chgModuleLoaded,
            chgModuleVermagic = chgModuleVermagic,
            rootStatus = rootStatus,
            magiskAvailable = magiskAvailable,
            magiskModuleInstalled = magiskModuleInstalled,
            availableModules = availableModules,
            isInstallingModule = isInstallingModule,
            moduleManagementMessage = moduleManagementMessage,
            lastTestFailureDetail = lastTestFailureDetail,
            onRefreshRoot = {
                scope.launch {
                    RootShell.clearCache()
                    rootStatus = RootShell.getRootStatus(forceRefresh = true)
                    showRootDialog = true
                }
            },
            onRefreshStatus = {
                scope.launch { loadStatus(force = true); moduleManagementMessage = "INFO:状态已刷新" }
            },
            onInstallMagiskModule = {
                scope.launch {
                    isInstallingModule = true
                    moduleManagementMessage = "正在创建动态模块..."
                    try {
                        val result = magiskManager.createLightweightModule()
                        if (result.success) {
                            magiskModuleInstalled = magiskManager.isModuleInstalled()
                            moduleManagementMessage = "SUCCESS:${result.message}"
                        } else {
                            moduleManagementMessage = "ERROR:${result.message}"
                        }
                    } finally { isInstallingModule = false }
                }
            },
            onUninstallMagiskModule = {
                scope.launch {
                    isInstallingModule = true
                    moduleManagementMessage = "正在卸载模块..."
                    try {
                        val result = magiskManager.uninstallModule()
                        if (result.success) {
                            magiskModuleInstalled = magiskManager.isModuleInstalled()
                            moduleManagementMessage = "SUCCESS:${result.message}"
                        } else {
                            moduleManagementMessage = "ERROR:${result.message}"
                        }
                    } finally { isInstallingModule = false }
                }
            },
            onShowModuleDownloadDialog = { if (availableModules.isNotEmpty()) showModuleDownloadDialog = true }
        )

        if (isMiuiDevice) {
            XiaomiHookSection(
                hookEnabled = hookEnabled,
                onHookEnabledChange = { hookEnabled = it },
                displayCapacity = displayCapacity,
                onDisplayCapacityChange = { displayCapacity = it },
                useSystemProp = useSystemProp,
                onUseSystemPropChange = { useSystemProp = it },
                customCapacity = customCapacity,
                onCustomCapacityChange = { customCapacity = it },
                hookTextView = hookTextView,
                onHookTextViewChange = { hookTextView = it },
                hookSharedPrefs = hookSharedPrefs,
                onHookSharedPrefsChange = { hookSharedPrefs = it },
                hookJsonMethods = hookJsonMethods,
                onHookJsonMethodsChange = { hookJsonMethods = it },
                onSave = {
                    scope.launch {
                        try {
                            val displayCap = displayCapacity.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0
                            if (displayCap < 0 || displayCap > 200000) {
                                msg = "ERROR:显示容量超出范围"; OpEvents.error("Hook:显示容量非法 $displayCap"); return@launch
                            }
                            repo.update { it.copy(
                                hookEnabled = hookEnabled,
                                displayCapacity = displayCap,
                                useSystemProp = useSystemProp,
                                customCapacity = customCapacity.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0,
                                hookTextView = hookTextView,
                                hookSharedPrefs = hookSharedPrefs,
                                hookJsonMethods = hookJsonMethods,
                                launcherIconEnabled = launcherIconEnabled
                            ) }
                            val pm = context.packageManager
                            val comp = android.content.ComponentName("com.override.battcaplsp", "com.override.battcaplsp.Launcher")
                            pm.setComponentEnabledSetting(
                                comp,
                                if (launcherIconEnabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                android.content.pm.PackageManager.DONT_KILL_APP
                            )
                            msg = "SUCCESS:设置保存成功"; OpEvents.success("Hook:设置保存")
                        } catch (t: Throwable) {
                            msg = "ERROR:保存异常 ${t.message}"; OpEvents.error("Hook:保存异常 ${t.message}")
                        }
                    }
                },
                onReset = {
                    scope.launch {
                        try {
                            hookEnabled = true
                            displayCapacity = TextFieldValue("")
                            useSystemProp = true
                            customCapacity = TextFieldValue("")
                            hookTextView = true
                            hookSharedPrefs = true
                            hookJsonMethods = true
                            repo.update { HookSettingsState() }
                            msg = "INFO:已重置为默认设置"; OpEvents.info("Hook:重置默认")
                        } catch (t: Throwable) {
                            msg = "ERROR:重置异常 ${t.message}"; OpEvents.error("Hook:重置异常 ${t.message}")
                        }
                    }
                },
                resultMessage = msg
            )
        }

        PreferenceGroup(title = "桌面入口", icon = Icons.Default.Home) {
            PreferenceSwitch(
                title = "显示本应用桌面图标",
                description = "关闭后将从桌面隐藏应用图标，可通过 LSPosed 管理器的「模块设置」进入此应用。",
                checked = launcherIconEnabled,
                onCheckedChange = { enabled ->
                    launcherIconEnabled = enabled
                    scope.launch {
                        repo.update { it.copy(launcherIconEnabled = enabled) }
                        val pm = context.packageManager
                        val comp = android.content.ComponentName("com.override.battcaplsp", "com.override.battcaplsp.Launcher")
                        pm.setComponentEnabledSetting(
                            comp,
                            if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP
                        )
                    }
                }
            )
        }

        PreferenceGroup(title = "电池容量矫正", icon = Icons.Default.BatteryChargingFull) {
            Text(
                "清空电池日志，防止系统误判容量。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(AppDimensions.SpaceSmall))
            ActionButton(
                text = "立即矫正",
                icon = Icons.Default.CleaningServices,
                onClick = { showCalibrationDialog = true },
                secondary = true
            )
        }

        AboutSection()

        Spacer(Modifier.height(AppDimensions.SpaceLarge))
    }

    if (showModuleDownloadDialog) {
        ModuleDownloadDialog(
            availableModules = availableModules,
            downloadingModule = downloadingModule,
            moduleDownloadProgress = moduleDownloadProgress,
            onDismiss = { showModuleDownloadDialog = false },
            onDownload = { moduleInfo ->
                showModuleDownloadDialog = false
                scope.launch {
                    downloadingModule = moduleInfo.name
                    moduleDownloadProgress = 0
                    moduleManagementMessage = "正在重新下载 ${moduleInfo.name} (忽略本地缓存)..."
                    val result = downloader.downloadModule(moduleInfo) { progress -> moduleDownloadProgress = progress }
                    downloadingModule = null
                    if (result.success && result.localPath != null) {
                        implicitTestAndInstall(moduleInfo.name, result.localPath, moduleInfo.version) { moduleManagementMessage = it }
                    } else {
                        moduleManagementMessage = "ERROR:${result.message}"
                    }
                }
            }
        )
    }

    if (showUpdateDialog && versionCheckResult?.releaseInfo != null) {
        UpdateDialog(
            versionCheckResult = versionCheckResult!!,
            apkPhase = apkPhase,
            apkDownloadProgress = apkDownloadProgress,
            apkLocalPath = apkLocalPath,
            apkDownloadManager = apkDownloadManager,
            onDismiss = { showUpdateDialog = false },
            onPhaseChange = { apkPhase = it },
            onDownloadingApkChange = { downloadingApk = it },
            onApkDownloadProgressChange = { apkDownloadProgress = it },
            onApkDownloadIdChange = { apkDownloadId = it },
            onApkLocalPathChange = { apkLocalPath = it }
        )
    }

    if (showCalibrationDialog) {
        CalibrationDialog(
            onDismiss = { showCalibrationDialog = false },
            onConfirm = {
                showCalibrationDialog = false
                scope.launch {
                    try {
                        RootShell.exec("cmd battery reset")
                        RootShell.exec("dumpsys batterystats --reset")
                        RootShell.exec("rm -f /data/system/batterystats.bin")
                        RootShell.exec("rm -f /data/system/batterystats-checkin.bin")
                        RootShell.exec("rm -f /data/system/batterystats-daily.xml")
                        RootShell.exec("rm -f /data/system/batterystats-history.bin")
                        OpEvents.success("电池日志已深度清空，建议重启设备")
                    } catch (t: Throwable) {
                        OpEvents.error("矫正失败: ${t.message}")
                    }
                }
            }
        )
    }
}

@Composable
private fun ModuleDownloadSection(
    kernelVersion: String,
    kernelVersionDetail: String,
    battModuleAvailable: Boolean?,
    battModuleLoaded: Boolean?,
    battModuleVermagic: String,
    chgModuleAvailable: Boolean?,
    chgModuleLoaded: Boolean?,
    chgModuleVermagic: String,
    rootStatus: RootShell.RootStatus?,
    magiskAvailable: Boolean?,
    magiskModuleInstalled: Boolean?,
    availableModules: List<com.override.battcaplsp.core.KernelModuleDownloader.ModuleInfo>,
    isInstallingModule: Boolean,
    moduleManagementMessage: String,
    lastTestFailureDetail: String?,
    onRefreshRoot: () -> Unit,
    onRefreshStatus: () -> Unit,
    onInstallMagiskModule: () -> Unit,
    onUninstallMagiskModule: () -> Unit,
    onShowModuleDownloadDialog: () -> Unit
) {
    AppCard {
        SectionHeader(
            title = "模块状态与管理",
            icon = Icons.Default.Extension,
            description = "内核模块、Magisk 环境与动态安装"
        )
        Spacer(Modifier.height(AppDimensions.SpaceMedium))

        // 状态网格
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusTile(
                modifier = Modifier.weight(1f),
                label = "内核版本",
                value = kernelVersionDetail.ifBlank { kernelVersion.ifEmpty { "获取中" } },
                icon = Icons.Default.Memory,
                isActive = kernelVersion.isNotEmpty()
            )
            Spacer(Modifier.width(AppDimensions.SpaceSmall))
            StatusTile(
                modifier = Modifier.weight(1f),
                label = "Root 权限",
                value = when (rootStatus?.available) {
                    true -> "已授权"
                    false -> "未授权"
                    else -> "检测中"
                },
                icon = Icons.Default.Security,
                isActive = rootStatus?.available == true,
                onClick = onRefreshRoot
            )
        }
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusTile(
                modifier = Modifier.weight(1f),
                label = "电池模块",
                value = moduleStatusText(
                    loaded = battModuleLoaded == true,
                    available = battModuleAvailable == true,
                    vermagic = battModuleVermagic
                ),
                icon = Icons.Default.BatteryFull,
                isActive = battModuleLoaded == true
            )
            Spacer(Modifier.width(AppDimensions.SpaceSmall))
            StatusTile(
                modifier = Modifier.weight(1f),
                label = "充电模块",
                value = moduleStatusText(
                    loaded = chgModuleLoaded == true,
                    available = chgModuleAvailable == true,
                    vermagic = chgModuleVermagic
                ),
                icon = Icons.Default.ElectricBolt,
                isActive = chgModuleLoaded == true
            )
        }
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusTile(
                modifier = Modifier.weight(1f),
                label = "Magisk",
                value = if (magiskAvailable == true) "已安装" else if (magiskAvailable == false) "未安装" else "检测中",
                icon = Icons.Default.Extension,
                isActive = magiskAvailable == true
            )
            Spacer(Modifier.width(AppDimensions.SpaceSmall))
            StatusTile(
                modifier = Modifier.weight(1f),
                label = "动态模块",
                value = if (magiskModuleInstalled == true) "已安装" else if (magiskModuleInstalled == false) "未安装" else "检测中",
                icon = Icons.Default.ViewModule,
                isActive = magiskModuleInstalled == true
            )
        }

        SectionDivider()
        PreferenceItem(
            title = "可用内核模块",
            description = "下载与当前内核匹配的 .ko 模块",
            icon = Icons.Default.CloudDownload,
            value = "${availableModules.size} 个",
            onClick = onShowModuleDownloadDialog
        )
        Spacer(Modifier.height(AppDimensions.SpaceMedium))
        ButtonRow {
            ActionButton(
                text = if (isInstallingModule) "处理中..." else "安装动态模块",
                icon = Icons.Default.InstallMobile,
                onClick = onInstallMagiskModule,
                enabled = (magiskAvailable == true) && (magiskModuleInstalled == false) && !isInstallingModule,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                text = if (isInstallingModule) "处理中..." else "卸载模块",
                icon = Icons.Default.DeleteOutline,
                onClick = onUninstallMagiskModule,
                enabled = (magiskModuleInstalled == true) && !isInstallingModule,
                modifier = Modifier.weight(1f),
                secondary = true
            )
        }
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        ActionButton(
            text = "刷新状态",
            icon = Icons.Default.Refresh,
            onClick = onRefreshStatus,
            modifier = Modifier.fillMaxWidth(),
            secondary = true
        )
        if (moduleManagementMessage.isNotEmpty()) {
            Spacer(Modifier.height(AppDimensions.SpaceSmall))
            AppStatusCard(status = moduleManagementMessage)
            val stype = remember(moduleManagementMessage) {
                val parts = moduleManagementMessage.split(":", limit = 2)
                if (parts.size == 2) {
                    when (parts[0]) {
                        "SUCCESS" -> StatusType.SUCCESS
                        "ERROR" -> StatusType.ERROR
                        "INFO" -> StatusType.INFO
                        else -> StatusType.INFO
                    }
                } else StatusType.INFO
            }
            if (stype == StatusType.ERROR && !lastTestFailureDetail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = lastTestFailureDetail,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.90f)
                )
            }
        }
    }
}

private fun moduleStatusText(loaded: Boolean, available: Boolean, vermagic: String): String {
    if (loaded) {
        val kv = extractKernelVersionFromVermagic(vermagic)
        return if (kv.isNotEmpty()) "运行中 · $kv" else "运行中"
    }
    if (available) return "可用未加载"
    return "未检测到"
}

@Composable
private fun StatusTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (value.isNotEmpty() && value != "获取失败" && value != "获取中...") MaterialTheme.colorScheme.primary else Color.Gray
        )
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun ModuleStatusRow(label: String, vermagic: String, loaded: Boolean, available: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val source = if (vermagic.isNotEmpty() && vermagic != "未知" && vermagic != "获取失败") extractKernelVersionFromVermagic(vermagic) else ""
            if (source.isNotBlank()) {
                Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                loaded -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), modifier = Modifier.size(16.dp))
                available -> Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.70f), modifier = Modifier.size(16.dp))
                else -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
    }
    Spacer(Modifier.height(1.dp))
}

@Composable
private fun StatusIconRow(label: String, available: Boolean?, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (available) {
                null -> { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(4.dp)); Text("检测中...", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                true -> { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("可用") }
                false -> { Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("不可用") }
            }
        }
    }
    Spacer(Modifier.height(1.dp))
}

@Composable
private fun XiaomiHookSection(
    hookEnabled: Boolean,
    onHookEnabledChange: (Boolean) -> Unit,
    displayCapacity: TextFieldValue,
    onDisplayCapacityChange: (TextFieldValue) -> Unit,
    useSystemProp: Boolean,
    onUseSystemPropChange: (Boolean) -> Unit,
    customCapacity: TextFieldValue,
    onCustomCapacityChange: (TextFieldValue) -> Unit,
    hookTextView: Boolean,
    onHookTextViewChange: (Boolean) -> Unit,
    hookSharedPrefs: Boolean,
    onHookSharedPrefsChange: (Boolean) -> Unit,
    hookJsonMethods: Boolean,
    onHookJsonMethodsChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    resultMessage: String
) {
    var expanded by remember { mutableStateOf(false) }
    PreferenceGroup(title = "小米设备 Hook 设置", icon = Icons.Default.PhoneAndroid) {
        SectionHeader(
            title = "我的设备页面-电池容量显示",
            description = "对系统设置页面的电量显示进行 Hook",
            expanded = expanded,
            onExpandToggle = { expanded = !expanded }
        )
        if (expanded) {
            Spacer(Modifier.height(AppDimensions.SpaceSmall))
            PreferenceSwitch(
                title = "启用 Hook 功能",
                description = "控制是否对设置页面进行电量显示 Hook",
                icon = Icons.Default.ToggleOn,
                checked = hookEnabled,
                onCheckedChange = onHookEnabledChange
            )
            SectionDivider()
            PreferenceSwitch(
                title = "使用系统属性",
                description = "优先从 persist.sys.batt.capacity_mah 读取容量",
                icon = Icons.Default.SettingsSystemDaydream,
                checked = useSystemProp,
                onCheckedChange = onUseSystemPropChange
            )
            Spacer(Modifier.height(AppDimensions.SpaceSmall))
            OutlinedTextField(
                value = customCapacity,
                onValueChange = onCustomCapacityChange,
                label = { Text("自定义容量 (mAh)") },
                supportingText = { Text("当不使用系统属性时，使用此值") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !useSystemProp,
                shape = MaterialTheme.shapes.large
            )
            SectionDivider()
            PreferenceSwitch(
                title = "Hook TextView.setText",
                description = "拦截 TextView 文本设置，替换其中的 mAh 数值",
                icon = Icons.Default.TextFields,
                checked = hookTextView,
                onCheckedChange = onHookTextViewChange
            )
            SectionDivider()
            PreferenceSwitch(
                title = "Hook SharedPreferences",
                description = "拦截 SharedPreferences 读写，修改 basic_info_key",
                icon = Icons.Default.Storage,
                checked = hookSharedPrefs,
                onCheckedChange = onHookSharedPrefsChange
            )
            SectionDivider()
            PreferenceSwitch(
                title = "Hook JSON 方法",
                description = "拦截 JSON 相关方法，修改设备信息 JSON",
                icon = Icons.Default.DataObject,
                checked = hookJsonMethods,
                onCheckedChange = onHookJsonMethodsChange
            )
            SectionDivider()
            OutlinedTextField(
                value = displayCapacity,
                onValueChange = onDisplayCapacityChange,
                label = { Text("显示容量 (mAh)") },
                supportingText = { Text("在设置页面显示的电池容量，0 表示使用系统默认") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            )
            Spacer(Modifier.height(AppDimensions.SpaceMedium))
            ButtonRow {
                ActionButton(text = "保存设置", icon = Icons.Default.Save, onClick = onSave)
                ActionButton(text = "重置默认", icon = Icons.Default.RestartAlt, onClick = onReset, secondary = true)
            }
            if (resultMessage.isNotBlank()) {
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                AppStatusCard(status = resultMessage)
            }
            Spacer(Modifier.height(AppDimensions.SpaceSmall))
            Text(
                """1. Hook 总开关：控制是否启用所有 Hook 功能
2. 数据源设置：选择容量数据的来源
3. Hook 方法设置：选择要使用的 Hook 方法
4. 显示容量：设置要在设置页面显示的容量值

修改设置后需要重启设置应用才能生效""".trimIndent(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModuleDownloadDialog(
    availableModules: List<com.override.battcaplsp.core.KernelModuleDownloader.ModuleInfo>,
    downloadingModule: String?,
    moduleDownloadProgress: Int,
    onDismiss: () -> Unit,
    onDownload: (com.override.battcaplsp.core.KernelModuleDownloader.ModuleInfo) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("下载内核模块") },
        text = {
            Column {
                Text(
                    "检测到 ${availableModules.size} 个可用的内核模块：",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                availableModules.forEach { moduleInfo ->
                    val isDownloading = downloadingModule == moduleInfo.name
                    AppCard(
                        onClick = if (!isDownloading) ({ onDownload(moduleInfo) }) else null
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    moduleInfo.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "版本: ${moduleInfo.version} · 内核: ${moduleInfo.kernelVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "大小: ${String.format("%.1f", moduleInfo.size / 1024.0)} KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = "下载",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        if (isDownloading) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { moduleDownloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$moduleDownloadProgress%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    AppCard {
        SectionHeader(
            title = "关于",
            icon = Icons.Default.Info,
            description = "应用信息、开源链接与致谢"
        )
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        PreferenceItem(
            title = "Battery Design Override",
            description = "版本 ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",
            icon = Icons.Default.BatteryChargingFull,
            value = null,
            onClick = null
        )
        SectionDivider()
        PreferenceItem(
            title = "GitHub 仓库",
            description = "查看源码、提交 Issue 与参与贡献",
            icon = Icons.Default.Code,
            value = "打开",
            onClick = {
                try {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/ruoqing501/batt-design-override-module")
                    )
                    context.startActivity(intent)
                } catch (_: Throwable) {
                    Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun UpdateDialog(
    versionCheckResult: com.override.battcaplsp.core.GitHubReleaseClient.VersionCheckResult,
    apkPhase: String,
    apkDownloadProgress: Int,
    apkLocalPath: String?,
    apkDownloadManager: com.override.battcaplsp.core.ApkDownloadManager,
    onDismiss: () -> Unit,
    onPhaseChange: (String) -> Unit,
    onDownloadingApkChange: (Boolean) -> Unit,
    onApkDownloadProgressChange: (Int) -> Unit,
    onApkDownloadIdChange: (Long?) -> Unit,
    onApkLocalPathChange: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val releaseInfo = versionCheckResult.releaseInfo!!
    val currentVersion = versionCheckResult.currentVersion
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("发现新版本") },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column {
                Text("发现新版本 ${releaseInfo.versionName}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                Text("当前版本: ${currentVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                if (releaseInfo.releaseNotes.isNotEmpty()) {
                    Text("更新内容:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                    Text(releaseInfo.releaseNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (apkPhase) {
                    "downloading" -> {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在下载... ${apkDownloadProgress}%", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    "ready" -> { Spacer(Modifier.height(8.dp)); StatusLine(StatusType.SUCCESS, "下载完成，准备安装") }
                    "installing" -> {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在启动安装...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    "error" -> { Spacer(Modifier.height(8.dp)); StatusLine(StatusType.ERROR, "更新失败，请重试") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        when (apkPhase) {
                            "idle", "error" -> {
                                onDownloadingApkChange(true)
                                onPhaseChange("downloading")
                                onApkDownloadProgressChange(0)
                                val result = apkDownloadManager.downloadApk(releaseInfo.downloadUrl, releaseInfo.versionName)
                                if (result.success && result.downloadId != null && result.filePath != null) {
                                    onApkDownloadIdChange(result.downloadId)
                                    onApkLocalPathChange(result.filePath)
                                    while (true) {
                                        kotlinx.coroutines.delay(600)
                                        val pg = result.downloadId.let { apkDownloadManager.queryProgress(it) }
                                        if (pg != null) {
                                            onApkDownloadProgressChange(pg.percent)
                                            if (pg.completed) {
                                                val local = pg.localUri?.removePrefix("file://") ?: result.filePath
                                                onApkLocalPathChange(local)
                                                onPhaseChange("ready")
                                                onDownloadingApkChange(false)
                                                onPhaseChange("installing")
                                                val p = local
                                                if (p != null) {
                                                    val res = apkDownloadManager.installApk(p)
                                                    if (res.success) {
                                                        onPhaseChange("done")
                                                        onDismiss()
                                                    } else {
                                                        onPhaseChange("error")
                                                        android.widget.Toast.makeText(context, "安装失败：${res.error ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show()
                                                    }
                                                } else {
                                                    onPhaseChange("error")
                                                    android.widget.Toast.makeText(context, "文件路径缺失", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                                break
                                            }
                                            if (pg.failed) {
                                                onPhaseChange("error")
                                                onDownloadingApkChange(false)
                                                android.widget.Toast.makeText(context, "下载失败", android.widget.Toast.LENGTH_LONG).show()
                                                break
                                            }
                                        }
                                    }
                                } else {
                                    onPhaseChange("error")
                                    onDownloadingApkChange(false)
                                    android.widget.Toast.makeText(context, "下载启动失败: ${result.error}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                            "ready" -> {
                                onPhaseChange("installing")
                                val p = apkLocalPath
                                if (p != null) {
                                    val res = apkDownloadManager.installApk(p)
                                    if (res.success) {
                                        onPhaseChange("done")
                                        onDismiss()
                                    } else {
                                        onPhaseChange("error")
                                        android.widget.Toast.makeText(context, "安装失败：${res.error ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    onPhaseChange("error")
                                    android.widget.Toast.makeText(context, "文件路径缺失", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                            "downloading", "installing" -> { }
                            "done" -> onDismiss()
                        }
                    }
                },
                enabled = apkPhase !in setOf("downloading", "installing")
            ) {
                val label = when (apkPhase) {
                    "idle", "error" -> "下载更新"
                    "downloading" -> "下载中..."
                    "ready" -> "安装更新"
                    "installing" -> "安装中..."
                    "done" -> "已完成"
                    else -> "下载更新"
                }
                Text(label)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后更新") }
        }
    )
}

@Composable
private fun CalibrationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("推荐充满校正") },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column {
                Text("系统依据电池统计日志来估算剩余容量。长期使用后更换电池日志可能产生偏差，导致电量显示不准。")
                Spacer(Modifier.height(8.dp))
                Text("清空日志可消除累积误差，强制系统重新校准。")
                Spacer(Modifier.height(12.dp))
                Text("副作用说明：", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Text("• 历史耗电统计将被清空\n• 系统需重新学习电池特性，短期内充电速度可能变慢或电量显示波动", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("建议：在关机充满电后开机执行，完成后重启设备。", style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认矫正") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
