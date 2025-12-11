package com.override.battcaplsp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Operations for chg_param_override kernel module and PD helper. */
class ChgModuleManager(
    private val moduleName: String = "chg_param_override",
    private val procPath: String = "/proc/chg_param_override"
) {
    private val battMgr by lazy { ModuleManager() }
    
    suspend fun isLoaded(): Boolean = withContext(Dispatchers.IO) {
        // 先尝试直接文件访问
        if (File(procPath).exists()) {
            return@withContext true
        }
        
        // 如果直接访问失败，使用 root shell 检测
        return@withContext try {
            val result = RootShell.exec("[ -e '$procPath' ] && echo 'exists' || echo 'not_exists'")
            result.code == 0 && result.out.trim() == "exists"
        } catch (e: Exception) {
            // 最后尝试通过 lsmod 检测
            try {
                val lsmodResult = RootShell.exec("lsmod | grep '^$moduleName ' | wc -l")
                lsmodResult.code == 0 && lsmodResult.out.trim().toIntOrNull() ?: 0 > 0
            } catch (e2: Exception) {
                false
            }
        }
    }

    /** 检查模块文件是否存在（是否已安装） */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        // 必须传入充电模块特定的搜索路径，否则默认只会搜索电池模块路径
        return@withContext battMgr.findAvailableKernelModule(moduleName, getDefaultSearchPaths()) != null
    }
    
    /** 智能查找并加载充电模块 */
    suspend fun loadModuleWithSmartNaming(
        targetBatt: String? = null, 
        targetUsb: String? = null, 
        verbose: Boolean = false,
        searchPaths: List<String> = getDefaultSearchPaths()
    ): RootShell.ExecResult = withContext(Dispatchers.IO) {
        val koPath = battMgr.findAvailableKernelModule(moduleName, searchPaths)
        if (koPath == null) {
            return@withContext RootShell.ExecResult(1, "", "未找到可用的充电模块文件: $moduleName")
        }
        
        return@withContext load(koPath, targetBatt, targetUsb, verbose)
    }
    
    /** 获取充电模块的默认搜索路径 */
    private fun getDefaultSearchPaths(): List<String> {
        return listOf(
            "/data/adb/modules/batt-design-override/common",
            "/data/adb/modules/batt-design-override-dynamic/common",
            "/data/adb/modules/chg-param-override/common",
            "/system/lib/modules",
            "/vendor/lib/modules",
            "/data/local/tmp/modules"
        )
    }

    /** Batch write k=v lines to /proc/chg_param_override. Empty values are skipped. */
    /** 应用批量参数，自动验证和限制参数范围 */
    suspend fun applyBatch(params: Map<String, String?>): RootShell.ExecResult = withContext(Dispatchers.IO) {
        fun sanitize(raw: String): String = raw
            .replace("\r", " ")
            .replace("\n", " ")          // 真换行
            .replace("\\n", " ")         // 字面 \n
            .replace(Regex("\u0000"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)

        // 验证和限制参数值
        fun validateAndClamp(key: String, value: String): String? {
            val sanitized = sanitize(value)
            if (sanitized.isBlank()) return null
            
            return when (key.lowercase()) {
                "voltage_max" -> {
                    val v = sanitized.toLongOrNull() ?: return null
                    val clamped = ChgParamValidator.clampVoltageMax(v)
                    // 0值表示不设置，不传递给内核
                    if (clamped == 0L) return null
                    clamped.toString()
                }
                "ccc" -> {
                    val v = sanitized.toLongOrNull() ?: return null
                    val clamped = ChgParamValidator.clampCcc(v)
                    // 0值表示不设置，不传递给内核
                    if (clamped == 0L) return null
                    clamped.toString()
                }
                "term" -> {
                    val v = sanitized.toLongOrNull() ?: return null
                    val clamped = ChgParamValidator.clampTerm(v)
                    // 0值表示不设置，不传递给内核
                    if (clamped == 0L) return null
                    clamped.toString()
                }
                "icl", "input_current_limit" -> {
                    val v = sanitized.toLongOrNull() ?: return null
                    val clamped = ChgParamValidator.clampIcl(v)
                    // 0值表示不设置，不传递给内核
                    if (clamped == 0L) return null
                    clamped.toString()
                }
                "ivl", "input_voltage_limit" -> {
                    val v = sanitized.toLongOrNull() ?: return null
                    val clamped = ChgParamValidator.clampIvl(v)
                    // 0值表示不设置，不传递给内核
                    if (clamped == 0L) return null
                    clamped.toString()
                }
                "charge_limit", "charge_control_limit" -> {
                    val v = sanitized.toIntOrNull() ?: return null
                    val clamped = ChgParamValidator.clampChargeLimit(v)
                    // 0值表示不限制，但可以传递（某些内核模块可能需要0来清除限制）
                    clamped.toString()
                }
                else -> sanitized  // 其他参数（如 batt, usb）不验证，直接使用
            }
        }

        val cleaned = params.entries
            .filter { !it.value.isNullOrBlank() }
            .mapNotNull { entry ->
                val validated = validateAndClamp(entry.key, entry.value!!)
                if (validated != null && validated.isNotBlank()) {
                    entry.key to validated
                } else null
            }
            .toMap()

        if (cleaned.isEmpty()) return@withContext RootShell.exec(":")

        val builder = buildString {
            cleaned.forEach { (k,v) -> append(k).append('=').append(v).append('\n') }
        }
        // 确保末尾有换行，便于内核逐行解析
        val payload = if (builder.endsWith("\n")) builder else builder + "\n"
        val result = RootShell.exec("printf %s "+RootShell.shellArg(payload)+" | tee "+procPath)
        
        // 如果写入失败，检查是否是权限问题或设备不支持
        if (result.code != 0) {
            // 检查 /proc 文件是否存在且可写
            val checkProc = RootShell.exec("[ -w $procPath ] && echo 'writable' || echo 'not_writable'")
            if (checkProc.out.trim() != "writable") {
                return@withContext RootShell.ExecResult(1, result.out, 
                    "无法写入 $procPath: 文件不可写或不存在。请确保模块已正确加载。")
            }
        }
        
        // 即使写入成功，也检查内核日志中是否有错误（内核模块可能在应用参数时失败）
        // 注意：这只是一个辅助检查，不会阻止返回成功，因为写入proc文件本身可能成功
        // 但实际应用可能失败（例如设备不支持某些属性）
        // 现在有了kprobe拦截功能，即使驱动不支持，内核模块也会尝试拦截并覆盖参数值
        if (result.code == 0) {
            try {
                // 检查是否有kprobe拦截成功的日志
                val interceptLog = RootShell.exec("dmesg | grep -E 'chg_param_override.*intercepted|chg_param_override.*overriding|power_supply_set_property.*hooked' | tail -3 || true")
                val hasIntercept = interceptLog.out.isNotBlank() && 
                    (interceptLog.out.contains("intercepted") || interceptLog.out.contains("overriding") || 
                     interceptLog.out.contains("hooked"))
                
                // 检查是否有错误日志
                val kernelLog = RootShell.exec("dmesg | grep -E 'chg_param_override.*failed|chg_param_override.*error' | tail -3 || true")
                if (kernelLog.out.isNotBlank()) {
                    // 如果内核日志中有错误，将其添加到错误信息中
                    val errorLines = kernelLog.out.trim().split('\n').filter { it.isNotBlank() }
                    if (errorLines.isNotEmpty()) {
                        val lastError = errorLines.last()
                        // 检查是否是ICL/IVL相关的错误
                        if (lastError.contains("ICL") || lastError.contains("IVL") || lastError.contains("-22")) {
                            // 如果有kprobe拦截，说明内核模块已经尝试拦截并覆盖参数值
                            val interceptHint = if (hasIntercept) {
                                "\n注意：内核模块已启用kprobe拦截功能，已尝试拦截并覆盖参数值。"
                            } else {
                                "\n提示：内核模块可能未启用kprobe拦截功能，或驱动不支持该属性。"
                            }
                            return@withContext RootShell.ExecResult(1, result.out, 
                                "参数写入成功，但内核应用失败: $lastError$interceptHint")
                        }
                    }
                } else if (hasIntercept) {
                    // 如果有拦截日志但没有错误，说明拦截功能正常工作
                    // 这种情况下，即使驱动不支持，我们也尝试了拦截，返回成功
                    // 但添加一个提示信息
                    android.util.Log.d("ChgModuleManager", "kprobe拦截功能已启用并工作正常")
                }
            } catch (e: Exception) {
                // 忽略检查内核日志时的错误，不影响主要流程
            }
        }
        
        result
    }

    suspend fun load(koPath: String, targetBatt: String?, targetUsb: String?, verbose: Boolean): RootShell.ExecResult {
        // 先检查模块是否已经加载
        if (isLoaded()) {
            return RootShell.ExecResult(1, "", "模块 $moduleName 已经加载，请先卸载")
        }
        
        val args = buildString {
            if (!targetBatt.isNullOrBlank()) append(" target_batt=").append(shellQuoteIfNeeded(targetBatt))
            if (!targetUsb.isNullOrBlank()) append(" target_usb=").append(shellQuoteIfNeeded(targetUsb))
            if (verbose) append(" verbose=1")
        }
        return RootShell.exec("insmod "+shellQuoteIfNeeded(koPath)+args)
    }

    suspend fun unload(): RootShell.ExecResult = RootShell.exec("rmmod $moduleName")

    // -------- PD helper via userspace script --------
    private val pdScript = "/data/local/tmp/pd_service.sh"
    private val pdPid = "/data/local/tmp/pd_service.pid"

    suspend fun deployPdHelper(desired: Int): RootShell.ExecResult {
        android.util.Log.d("ChgModuleManager", "开始部署PD守护进程，目标值: $desired")
        
        val script = """
        #!/system/bin/sh
        DESIRED_PD=${desired}
        PD_NODE=/sys/class/qcom-battery/pd_verifed
        USB_ONLINE=/sys/class/power_supply/usb/online
        LOG_FILE=/data/local/tmp/pd_service.log
        
        # 记录启动日志
        echo "${'$'}(date): PD守护进程启动，目标值: ${'$'}DESIRED_PD" >> ${'$'}LOG_FILE
        
        set_pd(){ 
            if [ -e "${'$'}PD_NODE" ]; then 
                echo "${'$'}DESIRED_PD" > "${'$'}PD_NODE" 2>/dev/null
                echo "${'$'}(date): 设置PD为 ${'$'}DESIRED_PD" >> ${'$'}LOG_FILE
            else
                echo "${'$'}(date): PD节点不存在: ${'$'}PD_NODE" >> ${'$'}LOG_FILE
            fi
        }
        
        last=-1
        set_pd
        
        while true; do
            online=$(cat "${'$'}USB_ONLINE" 2>/dev/null)
            if [ -n "${'$'}online" ] && [ "${'$'}online" != "${'$'}last" ]; then
                echo "${'$'}(date): USB状态变化: ${'$'}last -> ${'$'}online" >> ${'$'}LOG_FILE
                set_pd
                last="${'$'}online"
            fi
            sleep 2
        done
        """.trimIndent()
        
        val cmds = listOf(
            "cat > $pdScript <<'EOF'\n$script\nEOF",
            "chmod 755 $pdScript"
        ).joinToString(" && ")
        
        android.util.Log.d("ChgModuleManager", "执行部署命令: $cmds")
        val result = RootShell.exec(cmds)
        android.util.Log.d("ChgModuleManager", "部署结果: code=${result.code}, out=${result.out}, err=${result.err}")
        
        return result
    }

    suspend fun startPdHelper(): RootShell.ExecResult {
        android.util.Log.d("ChgModuleManager", "开始启动PD守护进程")
        
        // 先检查脚本是否存在
        val checkScript = RootShell.exec("[ -f $pdScript ] && echo 'exists' || echo 'not_exists'")
        android.util.Log.d("ChgModuleManager", "脚本检查结果: ${checkScript.out}")
        
        if (checkScript.out.trim() != "exists") {
            android.util.Log.e("ChgModuleManager", "PD脚本不存在: $pdScript")
            return RootShell.ExecResult(1, "", "PD脚本不存在，请先部署")
        }
        
        // 停止可能正在运行的进程
        stopPdHelper()
        
        // 启动新进程
        val cmd = "nohup $pdScript >/dev/null 2>&1 & echo \$! > $pdPid"
        android.util.Log.d("ChgModuleManager", "执行启动命令: $cmd")
        
        val result = RootShell.exec(cmd)
        android.util.Log.d("ChgModuleManager", "启动结果: code=${result.code}, out=${result.out}, err=${result.err}")
        
        // 验证进程是否启动成功
        if (result.code == 0) {
            val pidCheck = RootShell.exec("[ -f $pdPid ] && cat $pdPid || echo 'no_pid'")
            android.util.Log.d("ChgModuleManager", "PID文件检查: ${pidCheck.out}")
            
            if (pidCheck.out.trim() != "no_pid") {
                val pid = pidCheck.out.trim()
                val processCheck = RootShell.exec("ps | grep '^$pid ' || echo 'not_running'")
                android.util.Log.d("ChgModuleManager", "进程检查: ${processCheck.out}")
            }
        }
        
        return result
    }

    suspend fun stopPdHelper(): RootShell.ExecResult {
        android.util.Log.d("ChgModuleManager", "停止PD守护进程")
        
        val cmd = "if [ -f $pdPid ]; then kill $(cat $pdPid) 2>/dev/null; rm -f $pdPid; echo 'stopped'; else echo 'no_pid_file'; fi"
        android.util.Log.d("ChgModuleManager", "执行停止命令: $cmd")
        
        val result = RootShell.exec(cmd)
        android.util.Log.d("ChgModuleManager", "停止结果: code=${result.code}, out=${result.out}, err=${result.err}")
        
        return result
    }
    
    /** 检查PD守护进程状态 */
    suspend fun checkPdHelperStatus(): String {
        return try {
            // 检查PID文件
            val pidCheck = RootShell.exec("[ -f $pdPid ] && cat $pdPid || echo 'no_pid'")
            if (pidCheck.out.trim() == "no_pid") {
                return "未运行"
            }
            
            val pid = pidCheck.out.trim()
            
            // 检查进程是否还在运行
            val processCheck = RootShell.exec("ps | grep '^$pid ' || echo 'not_running'")
            if (processCheck.out.trim() == "not_running") {
                return "PID文件存在但进程未运行"
            }
            
            // 检查日志文件
            val logCheck = RootShell.exec("[ -f /data/local/tmp/pd_service.log ] && tail -3 /data/local/tmp/pd_service.log || echo 'no_log'")
            val logInfo = if (logCheck.out.trim() != "no_log") {
                "\n最近日志:\n${logCheck.out}"
            } else {
                ""
            }
            
            "运行中 (PID: $pid)$logInfo"
        } catch (e: Exception) {
            "检查状态时出错: ${e.message}"
        }
    }

    /** Read current values from /proc/chg_param_override and return as a map. */
    suspend fun readCurrent(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!isLoaded()) return@withContext emptyMap()
        val r = RootShell.exec("cat "+procPath+" 2>/dev/null || true")
        if (r.code != 0 || r.out.isBlank()) return@withContext emptyMap()
        val map = mutableMapOf<String, String>()
        val lines = r.out.split('\n')
        lines.forEach { rawLine ->
            val ln = rawLine.trim()
            if (ln.isEmpty()) return@forEach
            if (ln.startsWith("batt=") && ln.contains(" usb=")) {
                ln.split(' ').forEach { token ->
                    val idx = token.indexOf('=')
                    if (idx > 0) map[token.substring(0, idx)] = token.substring(idx + 1)
                }
                return@forEach
            }
            val idx = ln.indexOf('=')
            if (idx > 0) {
                val k = ln.substring(0, idx)
                var v = ln.substring(idx + 1)
                // 如果值里仍然混入其它行（异常情况），截断到第一个换行或出现第二个 key 样式片段前
                val secondKeyMatch = Regex("\\b(voltage_max|ccc|term|icl|charge_limit|auto_reapply)=").find(v)
                if (secondKeyMatch != null) {
                    v = v.substring(0, secondKeyMatch.range.first).trim()
                }
                v = v.replace("\r", " ").replace('\u0000', ' ').replace(Regex("\\s+"), " ").trim()
                map[k] = v
            }
        }
        return@withContext map
    }

    private fun shellQuoteIfNeeded(s: String): String {
        return if (s.matches(Regex("^[A-Za-z0-9._/:=-]+$"))) s else RootShell.shellArg(s)
    }

    /** 充电协议信息数据类 */
    data class ChargingProtocolInfo(
        val usbType: String = "Unknown",
        val usbOnline: Boolean = false,
        val pdVerified: String? = null,
        val supportsPdSwitch: Boolean = false,  // 是否支持 PD 协议切换（仅小米等特定设备）
        val availableProtocols: List<String> = emptyList(),  // 可用的协议列表（从 usb_type 解析）
        val voltageNow: Long = 0,  // uV
        val currentNow: Long = 0,   // uA
        val voltageMax: Long = 0,    // uV
        val currentMax: Long = 0,   // uA
        val powerNow: Long = 0,      // uW (calculated)
        val supportedProtocols: List<String> = emptyList()  // 支持的协议（推断）
    )
    
    /** 协议切换方法枚举 */
    enum class ProtocolSwitchMethod {
        PD_VERIFIED,      // 小米 PD 协议切换（MIPPS/PPS）
        INPUT_LIMIT,      // 通过输入限制间接影响协议
        VOLTAGE_LIMIT,    // 通过电压限制影响协议
        UNSUPPORTED       // 不支持
    }
    
    /** 检测设备是否支持 PD 协议切换（pd_verifed） */
    private suspend fun supportsPdSwitch(): Boolean = withContext(Dispatchers.IO) {
        // 检测方法1: 检查是否为小米设备
        val isMiui = try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java, String::class.java)
            val v = get.invoke(null, "ro.miui.ui.version.name", "") as String
            v.isNotEmpty() || android.os.Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
            android.os.Build.MANUFACTURER.contains("Redmi", ignoreCase = true) ||
            android.os.Build.MANUFACTURER.contains("POCO", ignoreCase = true)
        } catch (_: Throwable) {
            android.os.Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
            android.os.Build.MANUFACTURER.contains("Redmi", ignoreCase = true) ||
            android.os.Build.MANUFACTURER.contains("POCO", ignoreCase = true)
        }
        
        // 检测方法2: 检查 sysfs 节点是否存在
        val pdPath = "/sys/class/qcom-battery/pd_verifed"
        val checkResult = RootShell.exec("[ -e '$pdPath' ] && echo 'exists' || echo 'not_exists'")
        val nodeExists = checkResult.out.trim() == "exists"
        
        // 检测方法3: 检查模块是否支持（如果已加载）
        val moduleSupports = if (isLoaded()) {
            val current = readCurrent()
            current.containsKey("pd_verifed") || current.containsKey("pd_verifed_enabled")
        } else false
        
        // 只要满足任一条件就认为支持
        isMiui || nodeExists || moduleSupports
    }

    /** 读取当前充电协议信息 */
    suspend fun readChargingProtocolInfo(usbName: String = "usb"): ChargingProtocolInfo = withContext(Dispatchers.IO) {
        val usbPath = "/sys/class/power_supply/$usbName"
        val pdPath = "/sys/class/qcom-battery/pd_verifed"
        
        suspend fun readSysfs(path: String): String? {
            return try {
                val result = RootShell.exec("cat $path 2>/dev/null || echo ''")
                if (result.code == 0 && result.out.isNotBlank()) result.out.trim() else null
            } catch (e: Exception) {
                null
            }
        }
        
        suspend fun readLong(path: String): Long {
            return readSysfs(path)?.toLongOrNull() ?: 0L
        }
        
        val usbType = readSysfs("$usbPath/usb_type") ?: readSysfs("$usbPath/type") ?: "Unknown"
        val usbOnline = readSysfs("$usbPath/online")?.toIntOrNull() == 1
        val pdVerified = readSysfs(pdPath)
        
        // 解析可用的协议列表（从 usb_type 字符串中提取，格式通常是 "PD_PPS [PD] [DCP]"）
        val availableProtocols = mutableListOf<String>()
        val usbTypeParts = usbType.split(Regex("\\s+")).filter { it.isNotBlank() }
        usbTypeParts.forEach { part ->
            val clean = part.trim('[', ']', ' ')
            if (clean.isNotBlank() && clean != "Unknown") {
                availableProtocols.add(clean)
            }
        }
        // 如果没有解析到，使用原始值
        if (availableProtocols.isEmpty() && usbType != "Unknown") {
            availableProtocols.add(usbType)
        }
        val voltageNow = readLong("$usbPath/voltage_now")
        val currentNow = readLong("$usbPath/current_now")
        val voltageMax = readLong("$usbPath/voltage_max_design")
        val currentMax = readLong("$usbPath/current_max")
        
        // 计算功率 (uW = uV * uA / 1_000_000)
        val powerNow = if (voltageNow > 0 && currentNow > 0) {
            (voltageNow * currentNow) / 1_000_000L
        } else 0L
        
        // 推断支持的协议
        val supported = mutableListOf<String>()
        when {
            usbType.contains("PD_PPS", ignoreCase = true) -> {
                supported.add("PD_PPS")
                supported.add("PD")
            }
            usbType.contains("PD", ignoreCase = true) -> {
                supported.add("PD")
            }
            usbType.contains("DCP", ignoreCase = true) -> {
                supported.add("DCP")
            }
            usbType.contains("CDP", ignoreCase = true) -> {
                supported.add("CDP")
            }
            usbType.contains("SDP", ignoreCase = true) -> {
                supported.add("SDP")
            }
        }
        
        // 检测是否支持 PD 协议切换（同步检测，避免嵌套 suspend）
        val supportsSwitch = runCatching {
            val isMiui = try {
                val clz = Class.forName("android.os.SystemProperties")
                val get = clz.getMethod("get", String::class.java, String::class.java)
                val v = get.invoke(null, "ro.miui.ui.version.name", "") as String
                v.isNotEmpty() || android.os.Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
                android.os.Build.MANUFACTURER.contains("Redmi", ignoreCase = true) ||
                android.os.Build.MANUFACTURER.contains("POCO", ignoreCase = true)
            } catch (_: Throwable) {
                android.os.Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
                android.os.Build.MANUFACTURER.contains("Redmi", ignoreCase = true) ||
                android.os.Build.MANUFACTURER.contains("POCO", ignoreCase = true)
            }
            // 检查 sysfs 节点是否存在
            val nodeCheck = RootShell.exec("[ -e '$pdPath' ] && echo 'exists' || echo 'not_exists'")
            val nodeExists = nodeCheck.out.trim() == "exists"
            // 检查模块是否支持（如果已加载）
            val moduleSupports = if (isLoaded()) {
                val current = readCurrent()
                current.containsKey("pd_verifed") || current.containsKey("pd_verifed_enabled")
            } else false
            isMiui || nodeExists || moduleSupports
        }.getOrElse { false }
        
        if (supportsSwitch && pdVerified != null) {
            supported.add("MIPPS/PPS切换")
        }
        
        ChargingProtocolInfo(
            usbType = usbType,
            usbOnline = usbOnline,
            pdVerified = pdVerified,
            supportsPdSwitch = supportsSwitch,
            availableProtocols = availableProtocols,
            voltageNow = voltageNow,
            currentNow = currentNow,
            voltageMax = voltageMax,
            currentMax = currentMax,
            powerNow = powerNow,
            supportedProtocols = supported
        )
    }

    /** 切换 PD 协议 (0=MIPPS, 1=PPS) - 仅支持小米等特定设备 */
    suspend fun switchPdProtocol(value: Int): RootShell.ExecResult = withContext(Dispatchers.IO) {
        if (value != 0 && value != 1) {
            return@withContext RootShell.ExecResult(1, "", "PD 协议值必须是 0 (MIPPS) 或 1 (PPS)")
        }
        
        // 先检测是否支持
        if (!supportsPdSwitch()) {
            return@withContext RootShell.ExecResult(1, "", "当前设备不支持 PD 协议切换功能（仅小米等特定设备支持）")
        }
        
        // 方法1: 通过 /proc/chg_param_override（如果模块已加载）
        if (isLoaded()) {
            val result = applyBatch(mapOf("pd_verifed" to value.toString()))
            if (result.code == 0) {
                return@withContext result
            }
        }
        
        // 方法2: 直接写 sysfs (如果节点存在，通常是小米设备)
        val pdPath = "/sys/class/qcom-battery/pd_verifed"
        val checkResult = RootShell.exec("[ -e '$pdPath' ] && echo 'exists' || echo 'not_exists'")
        if (checkResult.out.trim() == "exists") {
            val writeResult = RootShell.exec("echo $value > $pdPath")
            if (writeResult.code == 0) {
                return@withContext writeResult
            }
            return@withContext RootShell.ExecResult(1, "", "写入 sysfs 失败: ${writeResult.err}")
        }
        
        RootShell.ExecResult(1, "", "无法切换 PD 协议：设备不支持或节点不可用")
    }

    /** 通过调整输入限制来间接影响协议选择（通用方法） */
    @Suppress("UNUSED_PARAMETER")
    suspend fun switchProtocolByLimit(
        targetProtocol: String,
        usbName: String = "usb"
    ): RootShell.ExecResult = withContext(Dispatchers.IO) {
        if (!isLoaded()) {
            return@withContext RootShell.ExecResult(1, "", "模块未加载，无法切换协议")
        }
        
        // 根据目标协议设置不同的输入限制
        // 注意：这是间接方法，通过限制输入功率来影响协议选择
        // 所有值都经过验证器限制，确保在安全范围内
        val limits = when (targetProtocol.uppercase()) {
            "SDP" -> {
                val icl = ChgParamValidator.clampIcl(500_000L)  // 500mA (SDP 标准限制)
                val ivl = ChgParamValidator.clampIvl(5_000_000L)  // 5V
                mapOf(
                    "input_current_limit" to icl.toString(),
                    "input_voltage_limit" to ivl.toString()
                )
            }
            "DCP" -> {
                val icl = ChgParamValidator.clampIcl(1_500_000L)  // 1.5A (DCP 常见限制)
                val ivl = ChgParamValidator.clampIvl(5_000_000L)  // 5V
                mapOf(
                    "input_current_limit" to icl.toString(),
                    "input_voltage_limit" to ivl.toString()
                )
            }
            "CDP" -> {
                val icl = ChgParamValidator.clampIcl(1_500_000L)  // 1.5A
                val ivl = ChgParamValidator.clampIvl(5_000_000L)   // 5V
                mapOf(
                    "input_current_limit" to icl.toString(),
                    "input_voltage_limit" to ivl.toString()
                )
            }
            "PD", "PD_PPS" -> {
                val icl = ChgParamValidator.clampIcl(3_000_000L)  // 3A (PD 常见)
                val ivl = ChgParamValidator.clampIvl(20_000_000L)  // 20V (PD 最大)
                mapOf(
                    "input_current_limit" to icl.toString(),
                    "input_voltage_limit" to ivl.toString()
                )
            }
            else -> {
                return@withContext RootShell.ExecResult(1, "", "不支持的协议类型: $targetProtocol")
            }
        }
        
        val result = applyBatch(limits)
        if (result.code == 0) {
            RootShell.ExecResult(0, "已通过调整输入限制尝试切换到 $targetProtocol\n注意：这是间接方法，实际协议由充电器决定", "")
        } else {
            result
        }
    }

    /** 检测可用的协议切换方法 */
    @Suppress("UNUSED_PARAMETER")
    suspend fun detectAvailableSwitchMethods(usbName: String = "usb"): List<ProtocolSwitchMethod> = withContext(Dispatchers.IO) {
        val methods = mutableListOf<ProtocolSwitchMethod>()
        
        // 方法1: PD Verified (小米)
        if (supportsPdSwitch()) {
            methods.add(ProtocolSwitchMethod.PD_VERIFIED)
        }
        
        // 方法2: 输入限制（如果模块已加载且支持）
        if (isLoaded()) {
            val current = readCurrent()
            if (current.containsKey("icl") || current.containsKey("input_current_limit")) {
                methods.add(ProtocolSwitchMethod.INPUT_LIMIT)
            }
            if (current.containsKey("ivl") || current.containsKey("input_voltage_limit")) {
                methods.add(ProtocolSwitchMethod.VOLTAGE_LIMIT)
            }
        }
        
        if (methods.isEmpty()) {
            methods.add(ProtocolSwitchMethod.UNSUPPORTED)
        }
        
        methods
    }
}


