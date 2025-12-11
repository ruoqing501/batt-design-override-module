package com.override.battcaplsp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import kotlin.OptIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.override.battcaplsp.core.*
import com.debug.battcaplsp.core.OpEvents
import com.override.battcaplsp.core.truncateMiddle
import com.override.battcaplsp.ui.StatusBadge
import kotlinx.coroutines.launch

@Composable
fun ChargingScreen(repo: ChgParamRepository, mgr: ChgModuleManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by repo.flow.collectAsState(initial = ChgUiState())
    LaunchedEffect(Unit) { repo.refresh() }
    
    // 首次进入风险提示弹窗
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
    var showProtocolInfo by remember { mutableStateOf(false) }
    var availableSwitchMethods by remember { mutableStateOf<List<ChgModuleManager.ProtocolSwitchMethod>>(emptyList()) }
    var selectedProtocol by remember { mutableStateOf("") }

    // 风险提示弹窗
    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text("⚠️ 风险提示与免责声明")
            },
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
                    Text(
                        "⚠️ 使用风险：",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "• 错误的参数设置可能导致电池损坏、设备过热或充电异常\n" +
                        "• 超出安全范围的电压/电流可能造成硬件永久性损坏\n" +
                        "• 不当使用可能导致设备无法正常充电或电池寿命缩短",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "📋 使用建议：",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "• 仅在了解充电原理的情况下使用\n" +
                        "• 建议从保守参数开始，逐步调整\n" +
                        "• 定期检查电池温度和充电状态\n" +
                        "• 如遇异常立即恢复默认设置",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "⚖️ 免责声明：",
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
                TextButton(
                    onClick = {
                        val prefs = context.getSharedPreferences("chg_warning", android.content.Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("warning_shown", true).apply()
                        showWarningDialog = false
                    }
                ) {
                    Text("我已了解风险，继续使用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        // 标题和状态
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("充电模块控制", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            StatusBadge(if (ui.loaded) "已加载" else "未加载")
        }
        Spacer(Modifier.height(16.dp))
        
        // 基础配置
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("基础配置", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        batt, { batt = it },
                        label = { Text("电池节点") },
                        supportingText = { Text("内核 power_supply 设备名称，通常为 battery") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        usb, { usb = it },
                        label = { Text("USB 节点") },
                        supportingText = { Text("USB 电源设备名称，通常为 usb") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        
        // 电池充电参数
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("电池充电参数", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    vMax, { vMax = it },
                    label = { Text("目标电压 (V)") },
                    supportingText = { Text("充电目标电压，例: 4.46 (范围: ${ChgParamValidator.getVoltageMaxRange()})") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = vMax.text.trim().isNotEmpty() && vMax.text.trim().toDoubleOrNull()?.let { v ->
                        val vUv = (v * 1_000_000).toLong()
                        !ChgParamValidator.validateVoltageMax(vUv).first
                    } ?: false
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        ccc, { ccc = it },
                        label = { Text("恒流电流 (mA)") },
                        supportingText = { Text("恒流充电阶段电流，范围: ${ChgParamValidator.getCccRange()}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = ccc.text.trim().isNotEmpty() && ccc.text.trim().toDoubleOrNull()?.let { v ->
                            val vUa = (v * 1000).toLong()
                            !ChgParamValidator.validateCcc(vUa).first
                        } ?: false
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        term, { term = it },
                        label = { Text("终止电流 (mA)") },
                        supportingText = { Text("充电终止判断电流，范围: ${ChgParamValidator.getTermRange()}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = term.text.trim().isNotEmpty() && term.text.trim().toDoubleOrNull()?.let { v ->
                            val vUa = (v * 1000).toLong()
                            !ChgParamValidator.validateTerm(vUa).first
                        } ?: false
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    limit, { limit = it },
                    label = { Text("充电限制 (%)") },
                    supportingText = { Text("充电上限百分比，范围: ${ChgParamValidator.getChargeLimitRange()}，0 表示不限制") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = limit.text.trim().isNotEmpty() && limit.text.trim().toIntOrNull()?.let { v ->
                        !ChgParamValidator.validateChargeLimit(v).first
                    } ?: false
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        
        // USB 输入参数
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("USB 输入参数 (PPS 功率控制)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        icl, { icl = it },
                        label = { Text("输入电流 (mA)") },
                        supportingText = { Text("USB 输入电流限制，范围: ${ChgParamValidator.getIclRange()}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = icl.text.trim().isNotEmpty() && icl.text.trim().toDoubleOrNull()?.let { v ->
                            val vUa = (v * 1000).toLong()
                            !ChgParamValidator.validateIcl(vUa).first
                        } ?: false
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        ivl, { ivl = it },
                        label = { Text("输入电压 (V)") },
                        supportingText = { Text("PPS 输入电压限制，范围: ${ChgParamValidator.getIvlRange()}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = ivl.text.trim().isNotEmpty() && ivl.text.trim().toDoubleOrNull()?.let { v ->
                            val vUv = (v * 1_000_000).toLong()
                            !ChgParamValidator.validateIvl(vUv).first
                        } ?: false
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "PPS 功率 = 输入电压 × 输入电流 (例: 9V × 2A = 18W)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        
        // 充电协议信息
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("充电协议信息", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
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
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("刷新")
                        }
                        IconButton(onClick = { showProtocolInfo = !showProtocolInfo }) {
                            if (showProtocolInfo) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "收起")
                            } else {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "展开")
                            }
                        }
                    }
                }
                
                if (showProtocolInfo && protocolInfo != null) {
                    val info = protocolInfo!!
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    
                    // USB 类型和在线状态
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("连接状态:", style = MaterialTheme.typography.bodyMedium)
                        StatusBadge(if (info.usbOnline) "已连接" else "未连接")
                    }
                    
                    // PD 协议状态（仅显示如果支持）
                    if (info.supportsPdSwitch && info.pdVerified != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("PD 协议:", style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (info.pdVerified == "1") "PPS" else "MIPPS",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "(小米设备)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (info.pdVerified != null && !info.supportsPdSwitch) {
                        // 显示 PD 状态但不支持切换
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("PD 状态:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                info.pdVerified,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    
                    // 实时充电参数
                    if (info.voltageNow > 0 || info.currentNow > 0) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("实时充电参数", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        if (info.voltageNow > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("电压:", style = MaterialTheme.typography.bodySmall)
                                Text("${info.voltageNow / 1000} mV", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (info.currentNow > 0) {
                            Spacer(Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("电流:", style = MaterialTheme.typography.bodySmall)
                                Text("${info.currentNow / 1000} mA", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (info.powerNow > 0) {
                            Spacer(Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("功率:", style = MaterialTheme.typography.bodySmall)
                                Text("${info.powerNow / 1000} mW", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    
                    // 支持的协议
                    if (info.supportedProtocols.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
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
                    
                    // PD 协议切换按钮（仅支持小米等特定设备）
                    if (info.supportsPdSwitch && info.pdVerified != null) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "PD 协议切换（仅小米等特定设备支持）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val result = mgr.switchPdProtocol(1)
                                            if (result.code == 0) {
                                                protocolInfo = mgr.readChargingProtocolInfo(usb.text.trim().ifEmpty { "usb" })
                                                msg = "SUCCESS:已切换到 PPS"
                                                OpEvents.success("充电:切换到 PPS")
                                            } else {
                                                msg = "ERROR:切换失败 ${result.err}"
                                                OpEvents.error("充电:切换失败 ${result.err}")
                                            }
                                        } catch (e: Exception) {
                                            msg = "ERROR:切换异常 ${e.message}"
                                            OpEvents.error("充电:切换异常 ${e.message}")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = info.pdVerified != "1"
                            ) {
                                Text("切换到 PPS")
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val result = mgr.switchPdProtocol(0)
                                            if (result.code == 0) {
                                                protocolInfo = mgr.readChargingProtocolInfo(usb.text.trim().ifEmpty { "usb" })
                                                msg = "SUCCESS:已切换到 MIPPS"
                                                OpEvents.success("充电:切换到 MIPPS")
                                            } else {
                                                msg = "ERROR:切换失败 ${result.err}"
                                                OpEvents.error("充电:切换失败 ${result.err}")
                                            }
                                        } catch (e: Exception) {
                                            msg = "ERROR:切换异常 ${e.message}"
                                            OpEvents.error("充电:切换异常 ${e.message}")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = info.pdVerified != "0"
                            ) {
                                Text("切换到 MIPPS")
                            }
                        }
                    } else if (!info.supportsPdSwitch && info.usbType.contains("PD", ignoreCase = true)) {
                        // 如果支持 PD 但不支持协议切换，显示提示
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
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
                    
                    // 通用协议切换（通过输入限制间接影响）
                    if (availableSwitchMethods.contains(ChgModuleManager.ProtocolSwitchMethod.INPUT_LIMIT) ||
                        availableSwitchMethods.contains(ChgModuleManager.ProtocolSwitchMethod.VOLTAGE_LIMIT)) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
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
                        Spacer(Modifier.height(8.dp))
                        
                        // 协议选择下拉菜单
                        val protocolOptions = listOf("SDP", "DCP", "CDP", "PD", "PD_PPS")
                        var expanded by remember { mutableStateOf(false) }
                        
                        @OptIn(ExperimentalMaterial3Api::class)
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
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                protocolOptions.forEach { protocol ->
                                    DropdownMenuItem(
                                        text = { Text(protocol) },
                                        onClick = {
                                            selectedProtocol = protocol
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        if (selectedProtocol.isEmpty()) {
                                            msg = "ERROR:请先选择目标协议"
                                            OpEvents.error("充电:未选择协议")
                                            return@launch
                                        }
                                        val result = mgr.switchProtocolByLimit(selectedProtocol, usb.text.trim().ifEmpty { "usb" })
                                        if (result.code == 0) {
                                            protocolInfo = mgr.readChargingProtocolInfo(usb.text.trim().ifEmpty { "usb" })
                                            msg = result.out.ifEmpty { "SUCCESS:已尝试切换到 $selectedProtocol" }
                                            OpEvents.success("充电:切换到 $selectedProtocol")
                                        } else {
                                            msg = "ERROR:切换失败 ${result.err}"
                                            OpEvents.error("充电:切换失败 ${result.err}")
                                        }
                                    } catch (e: Exception) {
                                        msg = "ERROR:切换异常 ${e.message}"
                                        OpEvents.error("充电:切换异常 ${e.message}")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedProtocol.isNotEmpty() && ui.loaded
                        ) {
                            Text("切换到 $selectedProtocol")
                        }
                    }
                } else if (showProtocolInfo) {
                    Spacer(Modifier.height(8.dp))
                    Text("点击刷新按钮读取协议信息", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        
        // 其他选项
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(verbose, { verbose = it })
                Spacer(Modifier.width(8.dp))
                Text("详细日志 (verbose)", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                scope.launch {
                    try {
                        if (!ui.loaded) { msg = "ERROR:模块未加载"; OpEvents.error("充电:模块未加载保存失败"); return@launch }
                        
                        // 验证并转换所有参数
                        val vMaxVal = vMax.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                        val vMaxUv = (vMaxVal * 1_000_000).toLong()
                        val (vMaxValid, vMaxErr) = ChgParamValidator.validateVoltageMax(vMaxUv)
                        if (!vMaxValid) {
                            msg = "ERROR:${vMaxErr}"; OpEvents.error("充电:${vMaxErr}"); return@launch
                        }
                        
                        val cccVal = ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                        val cccUa = (cccVal * 1000).toLong()
                        val (cccValid, cccErr) = ChgParamValidator.validateCcc(cccUa)
                        if (!cccValid) {
                            msg = "ERROR:${cccErr}"; OpEvents.error("充电:${cccErr}"); return@launch
                        }
                        
                        val termVal = term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                        val termUa = (termVal * 1000).toLong()
                        val (termValid, termErr) = ChgParamValidator.validateTerm(termUa)
                        if (!termValid) {
                            msg = "ERROR:${termErr}"; OpEvents.error("充电:${termErr}"); return@launch
                        }
                        
                        val iclVal = icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                        val iclUa = (iclVal * 1000).toLong()
                        val (iclValid, iclErr) = ChgParamValidator.validateIcl(iclUa)
                        if (!iclValid) {
                            msg = "ERROR:${iclErr}"; OpEvents.error("充电:${iclErr}"); return@launch
                        }
                        
                        val ivlVal = ivl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                        val ivlUv = (ivlVal * 1_000_000).toLong()
                        val (ivlValid, ivlErr) = ChgParamValidator.validateIvl(ivlUv)
                        if (!ivlValid) {
                            msg = "ERROR:${ivlErr}"; OpEvents.error("充电:${ivlErr}"); return@launch
                        }
                        
                        val limitVal = limit.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0
                        val (limitValid, limitErr) = ChgParamValidator.validateChargeLimit(limitVal)
                        if (!limitValid) {
                            msg = "ERROR:${limitErr}"; OpEvents.error("充电:${limitErr}"); return@launch
                        }
                        
                        // 使用验证器限制后的值（自动裁剪到安全范围）
                        val clampedVMax = ChgParamValidator.clampVoltageMax(vMaxUv)
                        val clampedCcc = ChgParamValidator.clampCcc(cccUa)
                        val clampedTerm = ChgParamValidator.clampTerm(termUa)
                        val clampedIcl = ChgParamValidator.clampIcl(iclUa)
                        val clampedIvl = ChgParamValidator.clampIvl(ivlUv)
                        val clampedLimit = ChgParamValidator.clampChargeLimit(limitVal)
                        repo.update { it.copy(
                            koPath = koPath.text.trim(),
                            batt = batt.text.trim(),
                            usb = usb.text.trim(),
                            voltageMax = clampedVMax,
                            ccc = clampedCcc,
                            term = clampedTerm,
                            icl = clampedIcl,
                            ivl = clampedIvl,
                            chargeLimit = clampedLimit,
                            verbose = verbose,
                        ) }
                        val applyRes = mgr.applyBatch(mapOf(
                            "batt" to batt.text.trim(),
                            "usb" to usb.text.trim(),
                            "voltage_max" to clampedVMax.toString(),
                            "ccc" to clampedCcc.toString(),
                            "term" to clampedTerm.toString(),
                            "icl" to clampedIcl.toString(),
                            "ivl" to clampedIvl.toString(),
                            "charge_limit" to clampedLimit.toString()
                        ))
                        if (applyRes.code == 0) {
                            ConfigSync.syncChg(
                                context,
                                batt.text.trim(), usb.text.trim(),
                                clampedVMax,
                                clampedCcc,
                                clampedTerm,
                                clampedIcl,
                                clampedIvl,
                                clampedLimit,
                                verbose,
                                1
                            )
                            msg = "SUCCESS:保存并应用完成"; OpEvents.success("充电:保存并应用成功")
                        } else {
                            val detail = ResultFormatter.formatApplyResult(applyRes)
                            // 检查是否是ICL/IVL设置失败（常见问题）
                            val isIclIvlError = (iclVal > 0 || ivlVal > 0) && applyRes.err.contains("ICL", ignoreCase = true) || 
                                                applyRes.err.contains("IVL", ignoreCase = true) ||
                                                applyRes.err.contains("-22", ignoreCase = true) ||
                                                applyRes.err.contains("EINVAL", ignoreCase = true)
                            
                            if (isIclIvlError) {
                                // 检查USB设备是否在线
                                val usbOnline = try {
                                    val usbPath = "/sys/class/power_supply/${usb.text.trim().ifEmpty { "usb" }}/online"
                                    val onlineCheck = RootShell.exec("cat $usbPath 2>/dev/null || echo '0'")
                                    onlineCheck.out.trim() == "1"
                                } catch (e: Exception) {
                                    false
                                }
                                
                                // 检查是否有kprobe拦截功能
                                val hasKprobe = try {
                                    val kprobeCheck = RootShell.exec("dmesg | grep -E 'power_supply_set_property.*hooked' | tail -1 || true")
                                    kprobeCheck.out.contains("hooked")
                                } catch (e: Exception) {
                                    false
                                }
                                
                                val errorHint = if (!usbOnline) {
                                    "提示：USB设备未连接，某些设备需要在连接充电器时才能设置输入限制参数。"
                                } else if (hasKprobe) {
                                    "提示：内核模块已启用kprobe拦截功能，已尝试拦截并覆盖参数值。\n" +
                                    "即使驱动不支持，内核模块也会尝试通过拦截方式设置参数。\n" +
                                    "如果仍然失败，可能是硬件限制或值超出支持范围。"
                                } else {
                                    "提示：设备可能不支持输入电流/电压限制功能，或值超出硬件支持范围。\n" +
                                    "请检查设备是否支持PPS充电，或确认内核模块是否启用了kprobe拦截功能。"
                                }
                                
                                msg = "WARN:保存完成，但应用失败: ${com.override.battcaplsp.core.TextAbbrev.middle(detail,120)}\n$errorHint"
                                OpEvents.error("充电:写内核失败-ICL/IVL")
                            } else {
                                msg = if (detail.contains("失败")) {
                                    OpEvents.error("充电:写内核失败"); "WARN:保存完成，但应用失败: ${com.override.battcaplsp.core.TextAbbrev.middle(detail,160)}" 
                                } else detail
                            }
                        }
                    } catch (t: Throwable) {
                        msg = "ERROR:保存异常 ${t.message}"; OpEvents.error("充电:保存异常 ${t.message}")
                    }
                }
            }, enabled = ui.loaded) { Text("保存并应用") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch {
                try {
                    val conf = com.override.battcaplsp.core.ConfigSync.readConf(context)
                    val battConf = conf["CHG_BATT_NAME"]?.ifBlank { null }
                    val usbConf = conf["CHG_USB_NAME"]?.ifBlank { null }
                    val verboseConf = conf["VERBOSE"]?.let { it == "1" || it.equals("true", true) || it.equals("Y", true) }
                    val finalVerbose = verboseConf ?: verbose
                    val res = mgr.loadModuleWithSmartNaming(
                        targetBatt = battConf ?: batt.text.trim().ifEmpty { null },
                        targetUsb = usbConf ?: usb.text.trim().ifEmpty { null },
                        verbose = finalVerbose
                    )
                    repo.refresh()
                    msg = ResultFormatter.formatModuleLoadResult(res)
                    if (res.code == 0) OpEvents.success("充电:加载模块成功") else OpEvents.error("充电:加载失败 ${res.err.take(60)}")
                } catch (t: Throwable) {
                    msg = "ERROR:加载异常 ${t.message}"; OpEvents.error("充电:加载异常 ${t.message}")
                }
            } }, enabled = !ui.loaded) { Text("加载模块") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch {
                try {
                    val r = mgr.unload(); repo.refresh(); msg = ResultFormatter.formatModuleUnloadResult(r)
                    if (r.code == 0) OpEvents.success("充电:卸载成功") else OpEvents.error("充电:卸载失败 ${r.err.take(40)}")
                } catch (t: Throwable) {
                    msg = "ERROR:卸载异常 ${t.message}"; OpEvents.error("充电:卸载异常 ${t.message}")
                }
            } }, enabled = ui.loaded) { Text("卸载模块") }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { scope.launch {
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
                            // 如果fallback有错误信息而原始命令没有，使用fallback的错误信息
                            res = fallback
                        }
                    }
                    if (lines.isNotEmpty()) {
                        val tail = if (lines.size > 300) lines.takeLast(300) else lines
                        kernelLog = tail.joinToString("\n")
                        msg = "SUCCESS:内核日志读取成功 (${tail.size} 行, 显示末尾)"; OpEvents.success("充电:读取日志 ${tail.size}")
                    } else {
                        kernelLog = ""
                        msg = if (res.err.isNotBlank()) {
                            OpEvents.warn("充电:日志stderr有输出"); "WARN:未获取到匹配日志 (stderr: ${com.override.battcaplsp.core.TextAbbrev.middle(res.err,120)})"
                        } else {
                            OpEvents.info("充电:日志无匹配"); "INFO:没有匹配到包含 chg_param_override 的日志"
                        }
                    }
                } catch (t: Throwable) {
                    kernelLog = ""; msg = "ERROR:日志读取异常 ${t.message}"; OpEvents.error("充电:日志异常 ${t.message}")
                }
            } }) { Text("查看内核日志") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch {
                try {
                    if (!mgr.isLoaded()) { msg = "ERROR:模块未加载，无法读取参数"; OpEvents.error("充电:读取参数失败未加载"); return@launch }
                    val m = mgr.readCurrent();
                    m["batt"]?.let { batt = TextFieldValue(it) }
                    m["usb"]?.let { usb = TextFieldValue(it) }
                    m["voltage_max"]?.toLongOrNull()?.let { vMax = TextFieldValue((it / 1_000_000.0).toString()) }
                    m["ccc"]?.toLongOrNull()?.let { ccc = TextFieldValue((it / 1000).toString()) }
                    m["term"]?.toLongOrNull()?.let { term = TextFieldValue((it / 1000).toString()) }
                    m["icl"]?.toLongOrNull()?.let { icl = TextFieldValue((it / 1000).toString()) }
                    m["ivl"]?.toLongOrNull()?.let { ivl = TextFieldValue((it / 1_000_000.0).toString()) }
                    m["input_voltage_limit"]?.toLongOrNull()?.let { ivl = TextFieldValue((it / 1_000_000.0).toString()) }
                    m["charge_limit"]?.toIntOrNull()?.let { limit = TextFieldValue(it.toString()) }
                    msg = "SUCCESS:当前参数读取成功"; OpEvents.success("充电:读取当前参数成功")
                } catch (t: Throwable) {
                    msg = "ERROR:读取参数异常 ${t.message}"; OpEvents.error("充电:读取参数异常 ${t.message}")
                }
            } }) { Text("读取当前参数") }
        }
        Spacer(Modifier.height(8.dp))
        if (kernelLog.isNotEmpty()) {
            LogViewer(
                title = "充电模块日志 (chg_param_override)",
                logText = kernelLog,
                onClear = { kernelLog = "" },
                maxHeight = 320
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
        if (msg.isNotBlank()) {
            StatusBadge(msg, showLabel = "结果:")
        }
    }
}
