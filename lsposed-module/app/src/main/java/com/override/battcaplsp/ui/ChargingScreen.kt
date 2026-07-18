package com.override.battcaplsp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.debug.battcaplsp.core.OpEvents
import com.override.battcaplsp.core.*
import com.override.battcaplsp.ui.components.*
import com.override.battcaplsp.ui.theme.AppDimensions
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargingScreen(repo: ChgParamRepository, mgr: ChgModuleManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ui by repo.flow.collectAsState(initial = ChgUiState())
    LaunchedEffect(Unit) { repo.refresh() }

    var showWarningDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("chg_warning", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("warning_shown", false)) {
            showWarningDialog = true
        }
    }
    var batt by remember { mutableStateOf(TextFieldValue(ui.batt)) }
    var usb by remember { mutableStateOf(TextFieldValue(ui.usb)) }
    var vMax by remember { mutableStateOf(TextFieldValue(if (ui.voltageMax > 0) (ui.voltageMax / 1_000_000.0).toString() else "")) }
    var ccc by remember { mutableStateOf(TextFieldValue(if (ui.ccc > 0) (ui.ccc / 1000).toString() else "")) }
    var term by remember { mutableStateOf(TextFieldValue(if (ui.term > 0) (ui.term / 1000).toString() else "")) }
    var icl by remember { mutableStateOf(TextFieldValue(if (ui.icl > 0) (ui.icl / 1000).toString() else "")) }
    var ivl by remember { mutableStateOf(TextFieldValue(if (ui.ivl > 0) (ui.ivl / 1_000_000.0).toString() else "")) }
    var limit by remember { mutableStateOf(TextFieldValue(if (ui.chargeLimit > 0) ui.chargeLimit.toString() else "")) }
    var koPath by remember { mutableStateOf(TextFieldValue(ui.koPath)) }
    var verbose by remember { mutableStateOf(ui.verbose) }
    var msg by remember { mutableStateOf("") }
    var kernelLog by remember { mutableStateOf("") }
    var protocolInfo by remember { mutableStateOf<ChgModuleManager.ChargingProtocolInfo?>(null) }
    var showProtocolInfo by remember { mutableStateOf(true) }
    var availableSwitchMethods by remember { mutableStateOf<List<ChgModuleManager.ProtocolSwitchMethod>>(emptyList()) }
    var selectedProtocol by remember { mutableStateOf("") }

    LaunchedEffect(ui.loaded) {
        if (ui.loaded) {
            try {
                protocolInfo = mgr.readChargingProtocolInfo(usb.text.trim().ifEmpty { "usb" })
                availableSwitchMethods = mgr.detectAvailableSwitchMethods(usb.text.trim().ifEmpty { "usb" })
            } catch (e: Exception) {
                OpEvents.error("充电:自动读取协议信息失败 ${e.message}")
            }
        }
    }

    fun showSnackbar(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            icon = {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("风险提示与免责声明") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "本功能为实验性功能，通过内核模块直接修改充电参数。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("使用风险：", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        "• 错误的参数设置可能导致电池损坏、设备过热或充电异常\n" +
                                "• 超出安全范围的电压/电流可能造成硬件永久性损坏\n" +
                                "• 不当使用可能导致设备无法正常充电或电池寿命缩短",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("使用建议：", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "• 仅在了解充电原理的情况下使用\n" +
                                "• 建议从保守参数开始，逐步调整\n" +
                                "• 定期检查电池温度和充电状态\n" +
                                "• 如遇异常立即恢复默认设置",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "免责声明：",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "使用本功能产生的任何设备损坏、数据丢失或其他后果，均由用户自行承担。开发者不对因使用本功能导致的任何损失负责。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val prefs = context.getSharedPreferences("chg_warning", android.content.Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("warning_shown", true).apply()
                        showWarningDialog = false
                    },
                    shape = MaterialTheme.shapes.large
                ) { Text("我已了解风险，继续使用") }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) { Text("取消") }
            },
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { innerPad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppDimensions.SpaceMedium, vertical = AppDimensions.SpaceSmall)
                .padding(innerPad)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppDimensions.SpaceMedium)
        ) {
            ModuleHeroCard(
                title = "充电模块",
                icon = Icons.Default.ElectricBolt,
                loaded = ui.loaded
            )

            AppCard {
                SectionHeader(
                    title = "参数设置",
                    icon = Icons.Default.Tune,
                    description = "配置充电模块工作参数"
                )
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        batt, { batt = it },
                        label = { Text("电池节点") },
                        supportingText = { Text("通常为 battery") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        usb, { usb = it },
                        label = { Text("USB 节点") },
                        supportingText = { Text("通常为 usb") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                }
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                OutlinedTextField(
                    vMax, { vMax = it },
                    label = { Text("目标电压 (V)") },
                    supportingText = { Text("例: 4.46 (范围: ${ChgParamValidator.getVoltageMaxRange()})") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    isError = vMax.text.trim().isNotEmpty() && vMax.text.trim().toDoubleOrNull()?.let { v ->
                        val vUv = (v * 1_000_000).toLong()
                        !ChgParamValidator.validateVoltageMax(vUv).first
                    } ?: false
                )
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        ccc, { ccc = it },
                        label = { Text("恒流电流 (mA)") },
                        supportingText = { Text("范围: ${ChgParamValidator.getCccRange()}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        isError = ccc.text.trim().isNotEmpty() && ccc.text.trim().toDoubleOrNull()?.let { v ->
                            val vUa = (v * 1000).toLong()
                            !ChgParamValidator.validateCcc(vUa).first
                        } ?: false
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        term, { term = it },
                        label = { Text("终止电流 (mA)") },
                        supportingText = { Text("范围: ${ChgParamValidator.getTermRange()}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        isError = term.text.trim().isNotEmpty() && term.text.trim().toDoubleOrNull()?.let { v ->
                            val vUa = (v * 1000).toLong()
                            !ChgParamValidator.validateTerm(vUa).first
                        } ?: false
                    )
                }
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                OutlinedTextField(
                    limit, { limit = it },
                    label = { Text("充电限制 (%)") },
                    supportingText = { Text("范围: ${ChgParamValidator.getChargeLimitRange()}，0 表示不限制") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    isError = limit.text.trim().isNotEmpty() && limit.text.trim().toIntOrNull()?.let { v ->
                        !ChgParamValidator.validateChargeLimit(v).first
                    } ?: false
                )
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        icl, { icl = it },
                        label = { Text("输入电流 (mA)") },
                        supportingText = { Text("范围: ${ChgParamValidator.getIclRange()}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        isError = icl.text.trim().isNotEmpty() && icl.text.trim().toDoubleOrNull()?.let { v ->
                            val vUa = (v * 1000).toLong()
                            !ChgParamValidator.validateIcl(vUa).first
                        } ?: false
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        ivl, { ivl = it },
                        label = { Text("输入电压 (V)") },
                        supportingText = { Text("范围: ${ChgParamValidator.getIvlRange()}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        isError = ivl.text.trim().isNotEmpty() && ivl.text.trim().toDoubleOrNull()?.let { v ->
                            val vUv = (v * 1_000_000).toLong()
                            !ChgParamValidator.validateIvl(vUv).first
                        } ?: false
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "PPS 功率 = 输入电压 × 输入电流 (例: 9V × 2A = 18W)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SectionDivider()
                PreferenceSwitch(
                    title = "详细日志 (verbose)",
                    description = "输出更多充电模块调试日志",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    checked = verbose,
                    onCheckedChange = { verbose = it }
                )
                Spacer(Modifier.height(AppDimensions.SpaceMedium))
                ActionButton(
                    text = "保存并应用",
                    icon = Icons.Default.Save,
                    onClick = {
                        scope.launch {
                            try {
                                if (!ui.loaded) {
                                    msg = "ERROR:模块未加载"
                                    showSnackbar("模块未加载，无法保存")
                                    OpEvents.error("充电:模块未加载保存失败")
                                    return@launch
                                }
                                val vMaxVal = vMax.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                                val vMaxUv = (vMaxVal * 1_000_000).toLong()
                                val (vMaxValid, vMaxErr) = ChgParamValidator.validateVoltageMax(vMaxUv)
                                if (!vMaxValid) {
                                    msg = "ERROR:${vMaxErr ?: "目标电压校验失败"}"
                                    showSnackbar(vMaxErr ?: "目标电压校验失败")
                                    OpEvents.error("充电:${vMaxErr ?: "目标电压校验失败"}")
                                    return@launch
                                }

                                val cccVal = ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                                val cccUa = (cccVal * 1000).toLong()
                                val (cccValid, cccErr) = ChgParamValidator.validateCcc(cccUa)
                                if (!cccValid) {
                                    msg = "ERROR:${cccErr ?: "恒流电流校验失败"}"
                                    showSnackbar(cccErr ?: "恒流电流校验失败")
                                    OpEvents.error("充电:${cccErr ?: "恒流电流校验失败"}")
                                    return@launch
                                }

                                val termVal = term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                                val termUa = (termVal * 1000).toLong()
                                val (termValid, termErr) = ChgParamValidator.validateTerm(termUa)
                                if (!termValid) {
                                    msg = "ERROR:${termErr ?: "终止电流校验失败"}"
                                    showSnackbar(termErr ?: "终止电流校验失败")
                                    OpEvents.error("充电:${termErr ?: "终止电流校验失败"}")
                                    return@launch
                                }

                                val iclVal = icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                                val iclUa = (iclVal * 1000).toLong()
                                val (iclValid, iclErr) = ChgParamValidator.validateIcl(iclUa)
                                if (!iclValid) {
                                    msg = "ERROR:${iclErr ?: "输入电流校验失败"}"
                                    showSnackbar(iclErr ?: "输入电流校验失败")
                                    OpEvents.error("充电:${iclErr ?: "输入电流校验失败"}")
                                    return@launch
                                }

                                val ivlVal = ivl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                                val ivlUv = (ivlVal * 1_000_000).toLong()
                                val (ivlValid, ivlErr) = ChgParamValidator.validateIvl(ivlUv)
                                if (!ivlValid) {
                                    msg = "ERROR:${ivlErr ?: "输入电压校验失败"}"
                                    showSnackbar(ivlErr ?: "输入电压校验失败")
                                    OpEvents.error("充电:${ivlErr ?: "输入电压校验失败"}")
                                    return@launch
                                }

                                val limitVal = limit.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0
                                val (limitValid, limitErr) = ChgParamValidator.validateChargeLimit(limitVal)
                                if (!limitValid) {
                                    msg = "ERROR:${limitErr ?: "充电限制校验失败"}"
                                    showSnackbar(limitErr ?: "充电限制校验失败")
                                    OpEvents.error("充电:${limitErr ?: "充电限制校验失败"}")
                                    return@launch
                                }

                                val clampedVMax = ChgParamValidator.clampVoltageMax(vMaxUv)
                                val clampedCcc = ChgParamValidator.clampCcc(cccUa)
                                val clampedTerm = ChgParamValidator.clampTerm(termUa)
                                val clampedIcl = ChgParamValidator.clampIcl(iclUa)
                                val clampedIvl = ChgParamValidator.clampIvl(ivlUv)
                                val clampedLimit = ChgParamValidator.clampChargeLimit(limitVal)
                                repo.update {
                                    it.copy(
                                        koPath = koPath.text.trim(),
                                        batt = batt.text.trim(),
                                        usb = usb.text.trim(),
                                        voltageMax = clampedVMax,
                                        ccc = clampedCcc,
                                        term = clampedTerm,
                                        icl = clampedIcl,
                                        ivl = clampedIvl,
                                        chargeLimit = clampedLimit,
                                        verbose = verbose
                                    )
                                }
                                val applyRes = mgr.applyBatch(
                                    mapOf(
                                        "batt" to batt.text.trim(),
                                        "usb" to usb.text.trim(),
                                        "voltage_max" to clampedVMax.toString(),
                                        "ccc" to clampedCcc.toString(),
                                        "term" to clampedTerm.toString(),
                                        "icl" to clampedIcl.toString(),
                                        "ivl" to clampedIvl.toString(),
                                        "charge_limit" to clampedLimit.toString()
                                    )
                                )
                                if (applyRes.code == 0) {
                                    ConfigSync.syncChg(
                                        context,
                                        batt.text.trim(), usb.text.trim(),
                                        clampedVMax, clampedCcc, clampedTerm,
                                        clampedIcl, clampedIvl, clampedLimit,
                                        verbose, 1
                                    )
                                    msg = "SUCCESS:保存并应用完成"
                                    showSnackbar("保存并应用完成")
                                    OpEvents.success("充电:保存并应用成功")
                                } else {
                                    val detail = ResultFormatter.formatApplyResult(applyRes)
                                    val isIclIvlError = (iclVal > 0 || ivlVal > 0) && applyRes.err.contains(
                                        "ICL",
                                        ignoreCase = true
                                    ) ||
                                            applyRes.err.contains("IVL", ignoreCase = true) ||
                                            applyRes.err.contains("-22", ignoreCase = true) ||
                                            applyRes.err.contains("EINVAL", ignoreCase = true)
                                    if (isIclIvlError) {
                                        val usbOnline = try {
                                            val usbPath = "/sys/class/power_supply/${usb.text.trim().ifEmpty { "usb" }}/online"
                                            val onlineCheck = RootShell.exec("cat $usbPath 2>/dev/null || echo '0'")
                                            onlineCheck.out.trim() == "1"
                                        } catch (e: Exception) {
                                            false
                                        }
                                        val hasKprobe = try {
                                            val kprobeCheck = RootShell.exec("dmesg | grep -E 'power_supply_set_property.*hooked|pmic_glink_write.*hooked' | tail -2 || true")
                                            kprobeCheck.out.contains("hooked")
                                        } catch (e: Exception) {
                                            false
                                        }
                                        val hasPmicGlink = try {
                                            val pmicCheck = RootShell.exec("dmesg | grep -E 'pmic_glink_write.*hooked' | tail -1 || true")
                                            pmicCheck.out.contains("pmic_glink_write") && pmicCheck.out.contains("hooked")
                                        } catch (e: Exception) {
                                            false
                                        }
                                        val errorHint = when {
                                            !usbOnline -> "提示：USB设备未连接，某些设备需要在连接充电器时才能设置输入限制参数。"
                                            hasPmicGlink -> "提示：内核模块已启用pmic_glink_write拦截功能，已直接修改发送给电源IC的消息。\n即使驱动返回错误，内核模块也会绕过所有检查，直接向电源IC发送修改后的参数值。\n这是最底层的拦截方式，应该能够成功设置参数。"
                                            hasKprobe -> "提示：内核模块已启用kprobe拦截功能，已尝试拦截并覆盖参数值。\n即使驱动不支持，内核模块也会尝试通过拦截方式设置参数。\n如果仍然失败，可能是硬件限制或值超出支持范围。"
                                            else -> "提示：设备可能不支持输入电流/电压限制功能，或值超出硬件支持范围。\n请检查设备是否支持PPS充电，或确认内核模块是否启用了kprobe拦截功能。"
                                        }
                                        msg = "WARN:保存完成，但应用失败: ${com.override.battcaplsp.core.TextAbbrev.middle(detail, 120)}\n$errorHint"
                                        showSnackbar("保存完成，但应用失败")
                                        OpEvents.error("充电:写内核失败-ICL/IVL")
                                    } else {
                                        msg = if (detail.contains("失败")) {
                                            OpEvents.error("充电:写内核失败")
                                            "WARN:保存完成，但应用失败: ${com.override.battcaplsp.core.TextAbbrev.middle(detail, 160)}"
                                        } else detail
                                        showSnackbar("保存完成，但应用失败")
                                    }
                                }
                            } catch (t: Throwable) {
                                msg = "ERROR:保存异常 ${t.message}"
                                showSnackbar("保存异常: ${t.message}")
                                OpEvents.error("充电:保存异常 ${t.message}")
                            }
                        }
                    },
                    enabled = ui.loaded
                )
            }

            AppCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(
                        title = "充电协议信息",
                        icon = Icons.Default.Cable,
                        description = "PD / PPS 协议与实时功率"
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionButton(
                            text = "刷新",
                            icon = Icons.Default.Refresh,
                            onClick = {
                                scope.launch {
                                    try {
                                        protocolInfo = mgr.readChargingProtocolInfo(usb.text.trim().ifEmpty { "usb" })
                                        availableSwitchMethods = mgr.detectAvailableSwitchMethods(usb.text.trim().ifEmpty { "usb" })
                                        showProtocolInfo = true
                                        msg = "SUCCESS:协议信息读取成功"
                                        OpEvents.success("充电:读取协议信息成功")
                                    } catch (e: Exception) {
                                        msg = "ERROR:读取协议信息失败 ${e.message}"
                                        OpEvents.error("充电:读取协议信息失败 ${e.message}")
                                    }
                                }
                            },
                            secondary = true
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { showProtocolInfo = !showProtocolInfo }) {
                            Icon(
                                if (showProtocolInfo) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (showProtocolInfo) "收起" else "展开"
                            )
                        }
                    }
                }

                if (showProtocolInfo && protocolInfo != null) {
                    val info = protocolInfo!!
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    HorizontalDivider()
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    ProtocolInfoRows(info, availableSwitchMethods, selectedProtocol, { selectedProtocol = it }, mgr, usb.text) { newMsg ->
                        msg = newMsg
                    }
                } else if (showProtocolInfo) {
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    Text(
                        "点击刷新按钮读取协议信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AppCard {
                SectionHeader(
                    title = "模块操作",
                    icon = Icons.Default.BuildCircle,
                    description = "加载、卸载与查看日志"
                )
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                ButtonRow {
                    ActionButton(
                        text = "加载模块",
                        icon = Icons.Default.Power,
                        onClick = {
                            scope.launch {
                                try {
                                    val conf = ConfigSync.readConf(context)
                                    val battConf = conf["CHG_BATT_NAME"]?.ifBlank { null }
                                    val usbConf = conf["CHG_USB_NAME"]?.ifBlank { null }
                                    val verboseConf = conf["VERBOSE"]?.let {
                                        it == "1" || it.equals("true", true) || it.equals("Y", true)
                                    }
                                    val finalVerbose = verboseConf ?: verbose
                                    val res = mgr.loadModuleWithSmartNaming(
                                        targetBatt = battConf ?: batt.text.trim().ifEmpty { null },
                                        targetUsb = usbConf ?: usb.text.trim().ifEmpty { null },
                                        verbose = finalVerbose
                                    )
                                    repo.refresh()
                                    msg = ResultFormatter.formatModuleLoadResult(res)
                                    if (res.code == 0) {
                                        showSnackbar("充电模块加载成功")
                                        OpEvents.success("充电:加载模块成功")
                                    } else {
                                        showSnackbar("充电模块加载失败")
                                        OpEvents.error("充电:加载失败 ${res.err.take(60)}")
                                    }
                                } catch (t: Throwable) {
                                    msg = "ERROR:加载异常 ${t.message}"
                                    showSnackbar("加载异常: ${t.message}")
                                    OpEvents.error("充电:加载异常 ${t.message}")
                                }
                            }
                        },
                        enabled = !ui.loaded,
                        secondary = true
                    )
                    ActionButton(
                        text = "卸载模块",
                        icon = Icons.Default.PowerOff,
                        onClick = {
                            scope.launch {
                                try {
                                    val r = mgr.unload()
                                    repo.refresh()
                                    msg = ResultFormatter.formatModuleUnloadResult(r)
                                    if (r.code == 0) {
                                        showSnackbar("充电模块已卸载")
                                        OpEvents.success("充电:卸载成功")
                                    } else {
                                        showSnackbar("卸载失败")
                                        OpEvents.error("充电:卸载失败 ${r.err.take(40)}")
                                    }
                                } catch (t: Throwable) {
                                    msg = "ERROR:卸载异常 ${t.message}"
                                    showSnackbar("卸载异常: ${t.message}")
                                    OpEvents.error("充电:卸载异常 ${t.message}")
                                }
                            }
                        },
                        enabled = ui.loaded,
                        secondary = true
                    )
                }
                Spacer(Modifier.height(AppDimensions.SpaceSmall))
                ActionButton(
                    text = "查看内核日志",
                    icon = Icons.Default.Terminal,
                    onClick = {
                        scope.launch {
                            try {
                                val cmd = "(dmesg | grep -E 'chg_param_override' || true)"
                                var res = RootShell.exec(cmd)
                                var lines = res.out.split('\n').filter { it.isNotBlank() }
                                if (lines.isEmpty()) {
                                    val fallback = RootShell.exec("logcat -b kernel -d | grep -E 'chg_param_override' || true")
                                    if (fallback.out.isNotBlank()) {
                                        res = fallback
                                        lines = fallback.out.split('\n').filter { it.isNotBlank() }
                                    } else if (fallback.err.isNotBlank() && res.err.isBlank()) {
                                        res = fallback
                                    }
                                }
                                if (lines.isNotEmpty()) {
                                    val tail = if (lines.size > 300) lines.takeLast(300) else lines
                                    kernelLog = tail.joinToString("\n")
                                    msg = "SUCCESS:内核日志读取成功 (${tail.size} 行, 显示末尾)"
                                    OpEvents.success("充电:读取日志 ${tail.size}")
                                } else {
                                    kernelLog = ""
                                    msg = if (res.err.isNotBlank()) {
                                        OpEvents.warn("充电:日志stderr有输出")
                                        "WARN:未获取到匹配日志 (stderr: ${com.override.battcaplsp.core.TextAbbrev.middle(res.err, 120)})"
                                    } else {
                                        OpEvents.info("充电:日志无匹配")
                                        "INFO:没有匹配到包含 chg_param_override 的日志"
                                    }
                                }
                            } catch (t: Throwable) {
                                kernelLog = ""
                                msg = "ERROR:日志读取异常 ${t.message}"
                                OpEvents.error("充电:日志异常 ${t.message}")
                            }
                        }
                    },
                    secondary = true
                )
                if (kernelLog.isNotEmpty()) {
                    Spacer(Modifier.height(AppDimensions.SpaceSmall))
                    LogViewer(
                        title = "充电模块日志 (chg_param_override)",
                        logText = kernelLog,
                        maxHeight = 320
                    )
                }
            }

            if (msg.isNotBlank()) {
                AppStatusCard(status = msg)
            }

            Spacer(Modifier.height(AppDimensions.SpaceLarge))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolInfoRows(
    info: ChgModuleManager.ChargingProtocolInfo,
    availableSwitchMethods: List<ChgModuleManager.ProtocolSwitchMethod>,
    selectedProtocol: String,
    onProtocolSelected: (String) -> Unit,
    mgr: ChgModuleManager,
    usbName: String,
    onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("USB 类型:", style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(info.usbType, style = MaterialTheme.typography.bodyMedium)
            if (info.availableProtocols.isNotEmpty() && info.availableProtocols.size > 1) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "可用: ${info.availableProtocols.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("连接状态:", style = MaterialTheme.typography.bodyMedium)
        StatusBadge(if (info.usbOnline) "SUCCESS:已连接" else "INFO:未连接")
    }
    if (info.supportsPdSwitch && info.pdVerified != null) {
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("PD 协议:", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (info.pdVerified == "1") "PPS" else "MIPPS",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text("(小米设备)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else if (info.pdVerified != null && !info.supportsPdSwitch) {
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("PD 状态:", style = MaterialTheme.typography.bodyMedium)
            Text(info.pdVerified, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (info.voltageNow > 0 || info.currentNow > 0) {
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        HorizontalDivider()
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        Text("实时充电参数", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        if (info.voltageNow > 0) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("电压:", style = MaterialTheme.typography.bodySmall)
                Text("${info.voltageNow / 1000} mV", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (info.currentNow > 0) {
            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("电流:", style = MaterialTheme.typography.bodySmall)
                Text("${info.currentNow / 1000} mA", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (info.powerNow > 0) {
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("功率:", style = MaterialTheme.typography.bodySmall)
            Text("${info.powerNow / 1000} mW", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (info.supportedProtocols.isNotEmpty()) {
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        HorizontalDivider()
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        Text("支持的协议:", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            info.supportedProtocols.forEach { protocol ->
                AssistChip(
                    onClick = { },
                    label = { Text(protocol, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
    if (info.supportsPdSwitch && info.pdVerified != null) {
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        HorizontalDivider()
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        Text(
            "PD 协议切换（仅小米等特定设备支持）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                text = "切换到 PPS",
                onClick = {
                    scope.launch {
                        try {
                            val result = mgr.switchPdProtocol(1)
                            if (result.code == 0) {
                                onMessage("SUCCESS:已切换到 PPS")
                                OpEvents.success("充电:切换到 PPS")
                            } else {
                                onMessage("ERROR:切换失败 ${result.err}")
                                OpEvents.error("充电:切换失败 ${result.err}")
                            }
                        } catch (e: Exception) {
                            onMessage("ERROR:切换异常 ${e.message}")
                            OpEvents.error("充电:切换异常 ${e.message}")
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = info.pdVerified != "1"
            )
            ActionButton(
                text = "切换到 MIPPS",
                onClick = {
                    scope.launch {
                        try {
                            val result = mgr.switchPdProtocol(0)
                            if (result.code == 0) {
                                onMessage("SUCCESS:已切换到 MIPPS")
                                OpEvents.success("充电:切换到 MIPPS")
                            } else {
                                onMessage("ERROR:切换失败 ${result.err}")
                                OpEvents.error("充电:切换失败 ${result.err}")
                            }
                        } catch (e: Exception) {
                            onMessage("ERROR:切换异常 ${e.message}")
                            OpEvents.error("充电:切换异常 ${e.message}")
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = info.pdVerified != "0"
            )
        }
    } else if (!info.supportsPdSwitch && info.usbType.contains("PD", ignoreCase = true)) {
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        HorizontalDivider()
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        Text(
            "当前设备不支持 PD 协议切换功能",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "PD 协议切换功能仅支持小米等特定设备",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (availableSwitchMethods.contains(ChgModuleManager.ProtocolSwitchMethod.INPUT_LIMIT) ||
        availableSwitchMethods.contains(ChgModuleManager.ProtocolSwitchMethod.VOLTAGE_LIMIT)
    ) {
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        HorizontalDivider()
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        Text(
            "协议切换（通过输入限制）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "通过调整输入电流/电压限制来间接影响协议选择",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        val protocolOptions = listOf("SDP", "DCP", "CDP", "PD", "PD_PPS")
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedProtocol.ifEmpty { "选择协议..." },
                onValueChange = { },
                readOnly = true,
                label = { Text("目标协议") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                shape = MaterialTheme.shapes.large
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                protocolOptions.forEach { protocol ->
                    DropdownMenuItem(
                        text = { Text(protocol) },
                        onClick = {
                            onProtocolSelected(protocol)
                            expanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(AppDimensions.SpaceSmall))
        ActionButton(
            text = "切换到 $selectedProtocol",
            onClick = {
                scope.launch {
                    try {
                        if (selectedProtocol.isEmpty()) {
                            onMessage("ERROR:请先选择目标协议")
                            OpEvents.error("充电:未选择协议")
                            return@launch
                        }
                        val result = mgr.switchProtocolByLimit(selectedProtocol, usbName.trim().ifEmpty { "usb" })
                        if (result.code == 0) {
                            onMessage(result.out.ifEmpty { "SUCCESS:已尝试切换到 $selectedProtocol" })
                            OpEvents.success("充电:切换到 $selectedProtocol")
                        } else {
                            onMessage("ERROR:切换失败 ${result.err}")
                            OpEvents.error("充电:切换失败 ${result.err}")
                        }
                    } catch (e: Exception) {
                        onMessage("ERROR:切换异常 ${e.message}")
                        OpEvents.error("充电:切换异常 ${e.message}")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedProtocol.isNotEmpty()
        )
    }
}
