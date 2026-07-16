package com.override.battcaplsp.ui

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.debug.battcaplsp.ui.DebugPanel
import com.debug.battcaplsp.ui.StatusScreen
import com.override.battcaplsp.BuildConfig
import com.override.battcaplsp.LaunchTrace
import com.override.battcaplsp.core.ConfigSync
import com.override.battcaplsp.core.ModuleManager
import com.override.battcaplsp.core.ParamRepository
import com.override.battcaplsp.core.RootShell
import com.override.battcaplsp.ui.components.*
import com.override.battcaplsp.ui.theme.AppAnimations
import com.override.battcaplsp.ui.theme.AppDimensions
import com.override.battcaplsp.ui.theme.AppTheme
import com.override.battcaplsp.ui.theme.ColorRoles
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val battMgr by lazy { ModuleManager() }
    private val battRepo by lazy { ParamRepository(this, battMgr) }
    private val chgMgr by lazy { com.override.battcaplsp.core.ChgModuleManager() }
    private val chgRepo by lazy { com.override.battcaplsp.core.ChgParamRepository(this, chgMgr) }
    private val hookRepo by lazy { com.override.battcaplsp.core.HookSettingsRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        LaunchTrace.markActivityCreateStart()
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splashScreen.setOnExitAnimationListener { splashView ->
            splashView.iconView.animate()
                .alpha(0f)
                .setDuration(300L)
                .withEndAction { splashView.remove() }
                .start()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        LaunchTrace.markSetContentStart()
        setContent {
            SideEffect { LaunchTrace.markFirstCompose() }
            AppTheme { AppScaffold() }
        }
        lifecycleScope.launch {
            try {
                RootShell.exec("echo 'init'")
            } catch (_: Exception) {
            }
            delay(500)
            checkAndShowRootStatus()
            LaunchTrace.markUiInteractive()
        }
    }

    private suspend fun checkAndShowRootStatus() {
        val rootStatus = RootShell.getRootStatus(forceRefresh = true)
        val message = if (rootStatus.available) {
            "SUCCESS:Root 权限已获取\n模块功能完全可用"
        } else {
            "WARN:Root 权限未获取\n${rootStatus.message}\n\n如果刚授予权限，请稍等片刻后重试"
        }
        runOnUiThread {
            Toast.makeText(this, message.stripStatusPrefix(), Toast.LENGTH_LONG).show()
        }
    }

    private fun formatModuleLoadResult(res: RootShell.ExecResult): String =
        ResultFormatter.formatModuleLoadResult(res)

    private fun formatModuleUnloadResult(res: RootShell.ExecResult): String =
        ResultFormatter.formatModuleUnloadResult(res)

    @Composable
    private fun getResultColor(result: String): androidx.compose.ui.graphics.Color =
        ResultFormatter.getResultColor(result)

    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    private fun AppScaffold() {
        var tab by remember { mutableStateOf(0) }
        val prefs = remember { getPreferences(android.content.Context.MODE_PRIVATE) }
        var isBattAvailable by remember {
            mutableStateOf(
                prefs.getBoolean(
                    "cache_batt_available",
                    true
                )
            )
        }
        var isChgAvailable by remember {
            mutableStateOf(
                prefs.getBoolean(
                    "cache_chg_available",
                    false
                )
            )
        }
        var refreshTrigger by remember { mutableStateOf(0) }

        LaunchedEffect(refreshTrigger) {
            val (newBatt, newChg) = checkModuleAvailability()
            if (newBatt != isBattAvailable) {
                isBattAvailable = newBatt
                prefs.edit().putBoolean("cache_batt_available", newBatt).apply()
            }
            if (newChg != isChgAvailable) {
                isChgAvailable = newChg
                prefs.edit().putBoolean("cache_chg_available", newChg).apply()
            }
        }

        val tabs = remember(isBattAvailable, isChgAvailable) {
            buildList {
                if (BuildConfig.DEBUG) add("状态")
                add("电池")
                if (isChgAvailable) add("充电")
                add("设置")
                if (BuildConfig.ENABLE_INTERNAL_DEBUG_PANEL) add("调试")
            }
        }
        if (tab >= tabs.size) tab = 0

        Scaffold(
            bottomBar = {
                AppBottomBar(
                    tabs = tabs,
                    selectedIndex = tab,
                    onSelectedChange = { tab = it }
                )
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                val currentTabName = tabs.getOrElse(tab) { tabs.firstOrNull() ?: "设置" }
                AnimatedContent(
                    targetState = currentTabName,
                    transitionSpec = {
                        AppAnimations.contentEnter().togetherWith(AppAnimations.contentExit())
                    },
                    label = "tab_switch"
                ) { tabName ->
                    when (tabName) {
                        "状态" -> StatusScreen(moduleManager = battMgr)
                        "电池" -> BatteryScreen()
                        "充电" -> ChargingScreen(repo = chgRepo, mgr = chgMgr)
                        "设置" -> HookSettingsScreen(
                            repo = hookRepo,
                            onModuleInstalled = { refreshTrigger++ }
                        )

                        "调试" -> DebugPanel(moduleManager = battMgr)
                        else -> StatusScreen(moduleManager = battMgr)
                    }
                }
            }
        }
    }

    private suspend fun checkModuleAvailability(): Pair<Boolean, Boolean> = coroutineScope {
        val battInstalled = async { battMgr.isAvailable() }
        val battLoaded = async { battMgr.isLoaded() }
        val chgInstalled = async { chgMgr.isAvailable() }
        val chgLoaded = async { chgMgr.isLoaded() }
        val newBatt = battInstalled.await() || battLoaded.await()
        val newChg = chgInstalled.await() || chgLoaded.await()
        newBatt to newChg
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun BatteryScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val uiState by battRepo.flow.collectAsState(initial = com.override.battcaplsp.core.UiState())
        LaunchedEffect(Unit) { battRepo.refresh() }

        var battName by remember { mutableStateOf(TextFieldValue(uiState.battName)) }
        var designUah by remember {
            mutableStateOf(
                TextFieldValue(
                    if (uiState.designUah > 0) (uiState.designUah / 1000.0).toString() else ""
                )
            )
        }
        var designUwh by remember {
            mutableStateOf(
                TextFieldValue(
                    if (uiState.designUwh > 0) (uiState.designUwh / 1000000.0).toString() else ""
                )
            )
        }
        var modelName by remember { mutableStateOf(TextFieldValue(uiState.modelName)) }
        var koPath by remember { mutableStateOf(TextFieldValue(uiState.koPath)) }
        var overrideAny by remember { mutableStateOf(uiState.overrideAny) }
        var verbose by remember { mutableStateOf(uiState.verbose) }
        var kernelMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
        var opResult by remember { mutableStateOf("") }
        var kernelLog by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }

        LaunchedEffect(uiState.moduleLoaded) {
            if (uiState.moduleLoaded) {
                kernelMap = battMgr.readAll()
                kernelMap["design_uah"]?.toLongOrNull()
                    ?.let { designUah = TextFieldValue((it / 1000.0).toString()) }
                kernelMap["design_uwh"]?.toLongOrNull()
                    ?.let { designUwh = TextFieldValue((it / 1000000.0).toString()) }
                kernelMap["batt_name"]?.let { battName = TextFieldValue(it) }
                kernelMap["model_name"]?.let { modelName = TextFieldValue(it) }
                kernelMap["override_any"]
                    ?.let { overrideAny = it == "Y" || it == "1" || it.equals("true", true) }
                kernelMap["verbose"]
                    ?.let { verbose = it == "Y" || it == "1" || it.equals("true", true) }
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            modifier = Modifier.fillMaxSize()
        ) { innerPad ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppDimensions.SpaceMedium, vertical = AppDimensions.SpaceSmall)
                    .padding(innerPad),
                verticalArrangement = Arrangement.spacedBy(AppDimensions.SpaceMedium)
            ) {
                item {
                    ModuleHeroCard(
                        loaded = uiState.moduleLoaded,
                        isLoading = isLoading
                    )
                }

                item {
                    AppCard {
                        SectionHeader(
                            title = "参数设置",
                            icon = Icons.Default.Tune,
                            description = "设置电池模块工作参数"
                        )
                        Spacer(Modifier.height(AppDimensions.SpaceSmall))
                        OutlinedTextField(
                            value = battName,
                            onValueChange = { battName = it },
                            label = { Text("batt_name") },
                            supportingText = { Text("目标电池 power_supply 名称，默认 battery") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(AppDimensions.SpaceSmall))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = designUah,
                                onValueChange = { designUah = it },
                                label = { Text("design_capacity (mAh)") },
                                supportingText = { Text("设计容量，单位毫安时 mAh") },
                                modifier = Modifier
                                    .weight(1.1f)
                                    .padding(end = AppDimensions.SpaceSmall),
                                singleLine = true,
                                shape = MaterialTheme.shapes.large
                            )
                            OutlinedTextField(
                                value = designUwh,
                                onValueChange = { designUwh = it },
                                label = { Text("design_energy (Wh)") },
                                supportingText = { Text("设计能量，单位瓦时 Wh") },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = AppDimensions.SpaceSmall),
                                singleLine = true,
                                shape = MaterialTheme.shapes.large
                            )
                        }
                        Spacer(Modifier.height(AppDimensions.SpaceSmall))
                        OutlinedTextField(
                            value = modelName,
                            onValueChange = { modelName = it },
                            label = { Text("model_name") },
                            supportingText = { Text("型号字符串，仅用于展示") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = MaterialTheme.shapes.large
                        )
                    }
                }

                item {
                    AppCard {
                        SectionHeader(
                            title = "选项",
                            icon = Icons.Default.SettingsSuggest,
                            description = "模块高级行为控制"
                        )
                        Spacer(Modifier.height(AppDimensions.SpaceSmall))
                        PreferenceSwitch(
                            title = "override_any",
                            description = "覆盖任意电池节点",
                            icon = Icons.Default.Layers,
                            checked = overrideAny,
                            onCheckedChange = { overrideAny = it }
                        )
                        SectionDivider()
                        PreferenceSwitch(
                            title = "verbose",
                            description = "输出详细内核日志",
                            icon = Icons.AutoMirrored.Filled.ReceiptLong,
                            checked = verbose,
                            onCheckedChange = { verbose = it }
                        )
                    }
                }

                item {
                    AppCard {
                        SectionHeader(
                            title = "操作与日志",
                            icon = Icons.Default.Build,
                            description = "加载、卸载、刷新与保存"
                        )
                        Spacer(Modifier.height(AppDimensions.SpaceSmall))
                        ButtonRow {
                            ActionButton(
                                text = "加载模块",
                                icon = Icons.Default.Power,
                                onClick = {
                                    scope.launch {
                                        isLoading = true
                                        try {
                                            val conf = ConfigSync.readConf(context)
                                            val battFromConf = conf["BATT_NAME"]?.ifBlank { null }
                                            val duahFromConf =
                                                conf["DESIGN_UAH"]?.toLongOrNull()?.takeIf { it > 0 }
                                            val duwhFromConf =
                                                conf["DESIGN_UWH"]?.toLongOrNull()?.takeIf { it > 0 }
                                            val modelFromConf = conf["MODEL_NAME"]?.ifBlank { null }
                                            val overrideFromConf =
                                                conf["OVERRIDE_ANY"]?.let {
                                                    if (it == "1" || it.equals("true", true)) "1" else null
                                                }
                                            val verboseFromConf = conf["VERBOSE"]?.let {
                                                if (it == "1" || it.equals(
                                                        "true",
                                                        true
                                                    ) || it.equals("Y", true)
                                                ) "1" else "0"
                                            }
                                            val mAhStr2 = designUah.text.trim()
                                            val whStr2 = designUwh.text.trim()
                                            val designUahMicro = duahFromConf?.toString()
                                                ?: ((mAhStr2.ifEmpty { "0" }.toDoubleOrNull()
                                                    ?: 0.0) * 1000).toLong().toString()
                                            val designUwhMicro = duwhFromConf?.toString()
                                                ?: ((whStr2.ifEmpty { "0" }.toDoubleOrNull()
                                                    ?: 0.0) * 1000000).toLong().toString()
                                            val res = battMgr.loadModuleWithSmartNaming(
                                                "batt_design_override",
                                                mapOf(
                                                    "design_uah" to designUahMicro.ifEmpty { null },
                                                    "design_uwh" to designUwhMicro.ifEmpty { null },
                                                    "model_name" to (modelFromConf
                                                        ?: modelName.text.trim().ifEmpty { null }),
                                                    "batt_name" to (battFromConf
                                                        ?: battName.text.trim().ifEmpty { null }),
                                                    "override_any" to (overrideFromConf
                                                        ?: (if (overrideAny) "1" else null)),
                                                    "verbose" to (verboseFromConf
                                                        ?: (if (verbose) "1" else "0"))
                                                )
                                            )
                                            battRepo.refresh()
                                            opResult = formatModuleLoadResult(res)
                                            if (res.code == 0) com.debug.battcaplsp.core.OpEvents.success(
                                                "加载模块成功"
                                            ) else com.debug.battcaplsp.core.OpEvents.error(
                                                "加载模块失败: ${res.err.take(80)}"
                                            )
                                        } catch (t: Throwable) {
                                            opResult = "ERROR:加载异常 ${t.message}"
                                            com.debug.battcaplsp.core.OpEvents.error("加载异常: ${t.message}")
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                },
                                enabled = !uiState.moduleLoaded && !isLoading
                            )
                            ActionButton(
                                text = "卸载模块",
                                icon = Icons.Default.PowerOff,
                                onClick = {
                                    scope.launch {
                                        isLoading = true
                                        try {
                                            val res = battMgr.unload()
                                            battRepo.refresh()
                                            opResult = formatModuleUnloadResult(res)
                                            if (res.code == 0) com.debug.battcaplsp.core.OpEvents.success(
                                                "卸载模块成功"
                                            ) else com.debug.battcaplsp.core.OpEvents.error(
                                                "卸载失败: ${res.err.take(60)}"
                                            )
                                        } catch (t: Throwable) {
                                            opResult = "ERROR:卸载异常 ${t.message}"
                                            com.debug.battcaplsp.core.OpEvents.error("卸载异常: ${t.message}")
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                },
                                enabled = uiState.moduleLoaded && !isLoading,
                                secondary = true
                            )
                        }
                        Spacer(Modifier.height(AppDimensions.SpaceSmall))
                        ButtonRow {
                            ActionButton(
                                text = "刷新参数",
                                icon = Icons.Default.Refresh,
                                onClick = {
                                    scope.launch {
                                        isLoading = true
                                        try {
                                            val km = battMgr.readAll()
                                            kernelMap = km
                                            km["design_uah"]?.toLongOrNull()
                                                ?.let { designUah = TextFieldValue((it / 1000.0).toString()) }
                                            km["design_uwh"]?.toLongOrNull()
                                                ?.let { designUwh = TextFieldValue((it / 1000000.0).toString()) }
                                            km["batt_name"]?.let { battName = TextFieldValue(it) }
                                            km["model_name"]?.let { modelName = TextFieldValue(it) }
                                            km["override_any"]
                                                ?.let { overrideAny = it == "Y" || it == "1" || it.equals("true", true) }
                                            km["verbose"]
                                                ?.let { verbose = it == "Y" || it == "1" || it.equals("true", true) }
                                            opResult = "SUCCESS:内核参数读取成功"
                                            com.debug.battcaplsp.core.OpEvents.success("刷新参数成功")
                                        } catch (t: Throwable) {
                                            opResult = "ERROR:读取失败 ${t.message}"
                                            com.debug.battcaplsp.core.OpEvents.error("刷新参数失败: ${t.message}")
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                },
                                enabled = uiState.moduleLoaded && !isLoading,
                                secondary = true
                            )
                            ActionButton(
                                text = "保存并应用",
                                icon = Icons.Default.Save,
                                onClick = {
                                    scope.launch {
                                        try {
                                            val mAhStr = designUah.text.trim()
                                            val whStr = designUwh.text.trim()
                                            val uahVal =
                                                ((mAhStr.ifEmpty { "0" }.toDoubleOrNull()
                                                    ?: 0.0) * 1000).toLong()
                                            val uwhVal =
                                                ((whStr.ifEmpty { "0" }.toDoubleOrNull()
                                                    ?: 0.0) * 1000000).toLong()
                                            if (uahVal < 0 || uahVal > 20000000L) {
                                                snackbarHostState.showSnackbar("设计容量(mAh)超出范围或格式错误 (0~20000mAh)")
                                                com.debug.battcaplsp.core.OpEvents.warn("设计容量异常: $uahVal")
                                                return@launch
                                            }
                                            if (uwhVal < 0 || uwhVal > 100000000L) {
                                                snackbarHostState.showSnackbar("设计能量(Wh)超出范围或格式错误 (0~100Wh)")
                                                com.debug.battcaplsp.core.OpEvents.warn("设计能量异常: $uwhVal")
                                                return@launch
                                            }
                                            battRepo.update {
                                                it.copy(
                                                    battName = battName.text.trim(),
                                                    designUah = uahVal,
                                                    designUwh = uwhVal,
                                                    modelName = modelName.text.trim(),
                                                    overrideAny = overrideAny,
                                                    verbose = verbose,
                                                    koPath = koPath.text.trim()
                                                )
                                            }
                                            val tasks = listOf(
                                                Pair("batt_name", battName.text.trim()),
                                                Pair("design_uah", uahVal.toString()),
                                                Pair("design_uwh", uwhVal.toString()),
                                                Pair("model_name", modelName.text.trim()),
                                                Pair("override_any", if (overrideAny) "1" else "0"),
                                                Pair("verbose", if (verbose) "1" else "0")
                                            )
                                            var okCnt = 0
                                            for ((k, v) in tasks) if (v.isNotEmpty()) if (battMgr.writeParam(
                                                    k,
                                                    v
                                                )
                                            ) okCnt++
                                            ConfigSync.syncBatt(
                                                context,
                                                battName.text.trim(),
                                                uahVal,
                                                uwhVal,
                                                modelName.text.trim(),
                                                overrideAny,
                                                verbose
                                            )
                                            kernelMap = battMgr.readAll()
                                            val msg = if (okCnt > 0) "SUCCESS:保存并应用完成 (成功 $okCnt 项)" else "WARN:保存完成，但应用失败"
                                            opResult = msg
                                            if (okCnt > 0) com.debug.battcaplsp.core.OpEvents.success(
                                                "保存并应用成功 ($okCnt)"
                                            ) else com.debug.battcaplsp.core.OpEvents.warn("保存写入内核失败")
                                        } catch (t: Throwable) {
                                            opResult = "ERROR:保存失败 ${t.message}"
                                            com.debug.battcaplsp.core.OpEvents.error("保存失败: ${t.message}")
                                        }
                                    }
                                },
                                enabled = uiState.moduleLoaded && !isLoading
                            )
                        }
                        Spacer(Modifier.height(AppDimensions.SpaceSmall))
                        ActionButton(
                            text = "查看内核日志",
                            icon = Icons.Default.Terminal,
                            onClick = {
                                scope.launch {
                                    try {
                                        val cmd = "(dmesg | grep -E 'batt_design_override' || true)"
                                        var res = RootShell.exec(cmd)
                                        var lines = res.out.split('\n').filter { it.isNotBlank() }
                                        if (lines.isEmpty()) {
                                            val fb = RootShell.exec("logcat -b kernel -d | grep -E 'batt_design_override' || true")
                                            if (fb.out.isNotBlank()) {
                                                res = fb
                                                lines = fb.out.split('\n').filter { it.isNotBlank() }
                                            }
                                        }
                                        if (lines.isNotEmpty()) {
                                            val tail = if (lines.size > 300) lines.takeLast(300) else lines
                                            kernelLog = tail.joinToString("\n")
                                            opResult = "SUCCESS:内核日志读取成功 (${tail.size} 行, 显示末尾)"
                                            com.debug.battcaplsp.core.OpEvents.success("读取日志 ${tail.size} 行")
                                        } else {
                                            kernelLog = ""
                                            opResult = if (res.err.isNotBlank()) {
                                                "WARN:未获取到匹配日志 (stderr: ${com.override.battcaplsp.core.TextAbbrev.middle(
                                                    res.err,
                                                    120
                                                )})".also {
                                                    com.debug.battcaplsp.core.OpEvents.warn(
                                                        "日志为空(含stderr)"
                                                    )
                                                }
                                            } else {
                                                "INFO:没有匹配到包含 batt_design_override 的日志".also {
                                                    com.debug.battcaplsp.core.OpEvents.info("日志无匹配")
                                                }
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        kernelLog = ""
                                        opResult = "ERROR:日志读取异常 ${t.message}"
                                        com.debug.battcaplsp.core.OpEvents.error("日志读取异常: ${t.message}")
                                    }
                                }
                            },
                            secondary = true
                        )
                        if (kernelLog.isNotEmpty()) {
                            Spacer(Modifier.height(AppDimensions.SpaceSmall))
                            LogViewer(
                                title = "电池模块日志 (batt_design_override)",
                                logText = kernelLog,
                                onClear = { kernelLog = "" },
                                maxHeight = 320
                            )
                        }
                    }
                }

                if (kernelMap.isNotEmpty()) {
                    item {
                        AppCard {
                            SectionHeader(
                                title = "内核参数",
                                icon = Icons.Default.Memory,
                                description = "点击任意行可复制完整键值"
                            )
                            Spacer(Modifier.height(AppDimensions.SpaceSmall))
                            Column {
                                for ((k, v) in kernelMap) {
                                    KeyValueItem(key = k, value = v)
                                }
                            }
                        }
                    }
                }

                if (opResult.isNotBlank()) {
                    item {
                        AppStatusCard(status = opResult)
                    }
                }

                item { Spacer(Modifier.height(AppDimensions.SpaceLarge)) }
            }
        }
    }

    @Composable
    private fun ModuleHeroCard(loaded: Boolean, isLoading: Boolean) {
        val gradient = if (loaded) {
            Brush.linearGradient(
                colors = listOf(
                    ColorRoles.successContainer.copy(alpha = 0.7f),
                    MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        }
        AppCard(gradient = gradient) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpaceMedium)
            ) {
                val icon = if (loaded) Icons.Default.BatteryChargingFull else Icons.Default.BatteryAlert
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(
                            if (loaded) ColorRoles.successContainer else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = if (loaded) ColorRoles.onSuccessContainer else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "电池模块",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (loaded) "已加载 · 参数生效中" else "未加载 · 请配置后加载模块",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(if (loaded) "SUCCESS:已加载" else "INFO:未加载")
            }
        }
    }
}
