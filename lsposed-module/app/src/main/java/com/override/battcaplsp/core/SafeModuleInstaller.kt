package com.override.battcaplsp.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File

/**
 * 优化的模块安装管理器
 * 添加 insmod 测试机制，避免无限循环问题
 */
class SafeModuleInstaller(private val context: Context) {
    
    companion object {
        private const val TEST_WAIT_MS = 2000L     // 2秒等待时间
    // 为了精简界面查看的内核日志长度，将原先 50 行缩减
    // 说明: 这里主要用于快速兼容性测试，只需捕获最近的错误/失败关键字，无需保留过长历史
    private const val DMSG_TAIL_LINES = 40      // 读取内核日志行数 (从 50 -> 40)
        // 通过反射惰性读取 BuildConfig.DEBUG，避免在某些编译场景（如包名或生成延迟）出现直接引用 unresolved
        @Volatile internal var DEBUG: Boolean = run {
            try {
                val clazz = Class.forName("com.override.battcaplsp.BuildConfig")
                val field = clazz.getField("DEBUG")
                field.getBoolean(null)
            } catch (_: Throwable) {
                false // 失败时默认关闭调试
            }
        }

        private fun logD(tag: String, msg: String) { if (DEBUG) android.util.Log.d(tag, msg) }
        private fun logW(tag: String, msg: String, t: Throwable? = null) { if (DEBUG) android.util.Log.w(tag, msg, t) }
        private fun logE(tag: String, msg: String, t: Throwable? = null) { android.util.Log.e(tag, msg, t) }
    }
    
    
    data class TestResult(
        val passed: Boolean,
        val message: String,
        val dmesgTail: String? = null,
        val executedCmd: String? = null
    )
    
    // safeInstallModule 逻辑已在设置页面改为隐式 quickTest + installKernelModule，去除公开安装函数以降低 API 面。
    
    /**
     * 执行 insmod 测试
     */
    private suspend fun performInsmodTest(
        moduleName: String,
        koFilePath: String,
        initialParams: Map<String, String?>
    ): TestResult = withContext(Dispatchers.IO) {
        
    logD("SafeModuleInstaller", "开始 insmod 测试")
        var executedCmd: String? = null
        try {
            // 1. 检查模块是否已经加载
            if (isModuleLoaded(moduleName)) {
                logW("SafeModuleInstaller", "模块已加载，先卸载")
                unloadTestModule(moduleName)
                delay(1000) // 等待卸载完成
            }
            
            // 2. 获取当前日志标记（替代清理日志）
            val logMarker = getKernelLogMarker()
            
            // 3. 构建 insmod 命令
            val insmodCmd = buildInsmodCommand(koFilePath, initialParams)
            executedCmd = insmodCmd
            logD("SafeModuleInstaller", "执行命令: $insmodCmd")
            
            // 4. 执行 insmod
            val insmodResult = RootShell.exec(insmodCmd)
            logD("SafeModuleInstaller", "insmod 结果: code=${insmodResult.code}, out=${insmodResult.out}, err=${insmodResult.err}")
            
            if (insmodResult.code != 0) {
                val errorMsg = if (insmodResult.err.isNotBlank()) insmodResult.err else insmodResult.out
                return@withContext TestResult(
                    passed = false,
                    message = "insmod 失败 (code ${insmodResult.code}): $errorMsg",
                    dmesgTail = getRecentKernelLog(),
                    executedCmd = executedCmd
                )
            }
            
            // 5. 等待模块稳定
            delay(TEST_WAIT_MS)
            
            // 6. 验证模块是否正确加载
            if (!isModuleLoaded(moduleName)) {
                return@withContext TestResult(
                    passed = false,
                    message = "模块加载后未在系统中找到",
                    dmesgTail = getRecentKernelLog(),
                    executedCmd = executedCmd
                )
            }
            
            // 7. 检查模块状态 (只检查新产生的日志)
            val dmesgOutput = getKernelLogSince(logMarker)
            
            // 8. 检查是否有与当前模块直接关联的错误信息
            // 只在同一行同时包含 模块名 + 关键字(error/failed/panic) 时判定失败
            run {
                val moduleNameLower = moduleName.lowercase()
                val errorKeywords = listOf("error", "failed", "panic")
                val ignoredKeywords = listOf("module verification failed", "tainting kernel") // 忽略常见非致命错误

                val hitLines = dmesgOutput.lineSequence().filter { line ->
                    val lower = line.lowercase()
                    if (!lower.contains(moduleNameLower)) return@filter false
                    
                    // 必须包含错误关键字
                    val hasError = errorKeywords.any { kw -> lower.contains(kw) }
                    if (!hasError) return@filter false
                    
                    // 必须不包含忽略关键字
                    val isIgnored = ignoredKeywords.any { kw -> lower.contains(kw) }
                    !isIgnored
                }.toList()

                if (hitLines.isNotEmpty()) {
                    return@withContext TestResult(
                        passed = false,
                        message = "内核日志中发现与模块相关错误信息",
                        dmesgTail = (hitLines + "---- FULL TAIL ----" + dmesgOutput).joinToString("\n"),
                        executedCmd = executedCmd
                    )
                }
            }
            
            logD("SafeModuleInstaller", "模块测试通过")
            TestResult(passed = true, message = "模块测试通过", dmesgTail = dmesgOutput, executedCmd = executedCmd)
            
        } catch (e: Exception) {
            logE("SafeModuleInstaller", "测试过程异常", e)
            TestResult(passed = false, message = "测试异常: ${e.message}", dmesgTail = getRecentKernelLog(), executedCmd = executedCmd)
        }
    }
    
    /**
     * 构建 insmod 命令
     */
    private fun buildInsmodCommand(koFilePath: String, initialParams: Map<String, String?>): String {
        val cmd = StringBuilder("insmod ${shellQuote(koFilePath)}")
        
        for ((key, value) in initialParams) {
            if (!value.isNullOrBlank()) {
                cmd.append(" $key=${shellQuote(value)}")
            }
        }
        
        return cmd.toString()
    }
    
    /**
     * 检查模块是否已加载
     */
    private suspend fun isModuleLoaded(moduleName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 检查 /sys/module 目录
            val sysModuleResult = RootShell.exec(
                "[ -d ${RootShell.shellArg("/sys/module/$moduleName")} ] && echo 'loaded' || echo 'not_loaded'"
            )
            if (sysModuleResult.code == 0 && sysModuleResult.out.trim() == "loaded") {
                return@withContext true
            }

            // 检查 /proc/modules
            val procResult = RootShell.exec(
                "if awk -v name=" + RootShell.shellArg(moduleName) +
                " '$1 == name {found=1} END {if (found) exit 0; exit 1}' /proc/modules; then echo 'loaded'; else echo 'not_loaded'; fi"
            )
            if (procResult.code == 0 && procResult.out.trim() == "loaded") {
                return@withContext true
            }

            false
        } catch (e: Exception) {
            logW("SafeModuleInstaller", "检查模块加载状态失败", e)
            false
        }
    }
    
    /**
     * 卸载测试模块
     */
    private suspend fun unloadTestModule(moduleName: String) {
        withContext(Dispatchers.IO) {
            try {
                if (isModuleLoaded(moduleName)) {
                    logD("SafeModuleInstaller", "卸载测试模块: $moduleName")
                    val result = RootShell.exec("rmmod ${RootShell.shellArg(moduleName)}")
                    if (result.code == 0) {
                        logD("SafeModuleInstaller", "模块卸载成功")
                    } else {
                        logW("SafeModuleInstaller", "模块卸载失败: ${result.err}")
                    }
                } else {
                    // 显式 else 分支以避免 Kotlin 将 if 作为需要返回值的表达式
                    logD("SafeModuleInstaller", "模块未加载, 无需卸载: $moduleName")
                }
                // 确保 try 代码块以明确的 Unit 结束，避免 if 作为最后表达式触发编译器要求 else
                Unit
            } catch (e: Exception) {
                logW("SafeModuleInstaller", "卸载模块异常", e)
                // 捕获分支也显式返回 Unit
                Unit
            }
        }
    }
    
    /**
     * 获取当前内核日志的最后一行作为标记
     */
    private suspend fun getKernelLogMarker(): String = withContext(Dispatchers.IO) {
        try {
            val result = RootShell.exec("dmesg | tail -n 1")
            if (result.code == 0) result.out.trim() else ""
        } catch (e: Exception) { "" }
    }

    /**
     * 获取自标记以来的新内核日志
     */
    private suspend fun getKernelLogSince(marker: String): String = withContext(Dispatchers.IO) {
        try {
            // 读取更多行以确保覆盖
            val result = RootShell.exec("dmesg | tail -n 100")
            if (result.code == 0) {
                val fullLog = result.out
                if (marker.isEmpty()) return@withContext fullLog
                
                val lines = fullLog.lines()
                // 寻找标记行的最后一次出现位置
                val markerIndex = lines.indexOfLast { it.trim() == marker }
                
                if (markerIndex != -1 && markerIndex < lines.size - 1) {
                    // 返回标记之后的内容
                    lines.subList(markerIndex + 1, lines.size).joinToString("\n")
                } else if (markerIndex == -1) {
                    // 没找到标记，可能日志滚动了，返回全部
                    fullLog
                } else {
                    // 标记是最后一行，没有新日志
                    ""
                }
            } else {
                "无法获取内核日志"
            }
        } catch (e: Exception) {
            "获取内核日志异常: ${e.message}"
        }
    }
    
    /**
     * 获取最近的内核日志
     */
    private suspend fun getRecentKernelLog(): String = withContext(Dispatchers.IO) {
        try {
            val result = RootShell.exec("dmesg | tail -${DMSG_TAIL_LINES}")
            if (result.code == 0) {
                result.out
            } else {
                "无法获取内核日志"
            }
        } catch (e: Exception) {
            "获取内核日志异常: ${e.message}"
        }
    }
    
    // Magisk 安装与配置写入逻辑已由设置页面直接管理，删除 installToMagiskModule / updateModuleConfig。
    
    /**
     * 快速测试模块兼容性（不安装）
     */
    suspend fun quickTestModule(
        moduleName: String,
        koFilePath: String,
        initialParams: Map<String, String?> = emptyMap()
    ): TestResult = withContext(Dispatchers.IO) {
        
        android.util.Log.d("SafeModuleInstaller", "开始快速兼容性测试")
        
        try {
            // 检查文件
            val sourceFile = File(koFilePath)
            if (!sourceFile.exists()) {
                return@withContext TestResult(
                    passed = false,
                    message = "模块文件不存在"
                )
            }
            
            // 检查模块信息
            val modinfoResult = RootShell.exec("modinfo ${RootShell.shellArg(koFilePath)} 2>/dev/null || echo 'modinfo_failed'")
            if (modinfoResult.out.contains("modinfo_failed")) {
                return@withContext TestResult(
                    passed = false,
                    message = "模块文件格式无效"
                )
            }
            
            // 执行测试加载
            val testResult = performInsmodTest(moduleName, koFilePath, initialParams)
            
            // 无论测试结果如何，都卸载测试模块
            unloadTestModule(moduleName)
            
            testResult
            
        } catch (e: Exception) {
            android.util.Log.e("SafeModuleInstaller", "快速测试异常", e)
            TestResult(
                passed = false,
                message = "测试异常: ${e.message}"
            )
        }
    }
    
    // getInstallRecommendation 已不再使用，移除。
    
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}