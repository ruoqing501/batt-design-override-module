package com.override.battcaplsp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.override.battcaplsp.core.ModuleManager
import com.override.battcaplsp.core.ParamRepository
import com.override.battcaplsp.core.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.io.File

class BootCompletedReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_LOAD, DEFAULT_AUTO_LOAD)) {
            Log.i(TAG, "Boot auto-load disabled, skip")
            return
        }

        scope.launch {
            try {
                // 1. Root 必须可用
                val rootStatus = RootShell.getRootStatus(forceRefresh = true)
                if (!rootStatus.available) {
                    Log.w(TAG, "Root not available, skip boot load")
                    return@launch
                }

                val mm = ModuleManager()

                // 2. 若已加载则跳过（Magisk service.sh 可能已加载）
                if (mm.isLoaded()) {
                    Log.i(TAG, "Module already loaded, skip")
                    return@launch
                }

                val repo = ParamRepository(context, mm)
                val state = repo.flow.first()

                // 3. ko 文件必须存在
                val koPath = state.koPath.ifBlank {
                    mm.findAvailableKernelModule("batt_design_override") ?: ""
                }
                if (koPath.isBlank() || !File(koPath).exists()) {
                    Log.w(TAG, "KO file not found: $koPath")
                    return@launch
                }

                // 4. 加载一次，失败不重试
                val initial = mapOf(
                    "design_uah" to state.designUah.takeIf { it > 0 }?.toString(),
                    "design_uwh" to state.designUwh.takeIf { it > 0 }?.toString(),
                    "model_name" to state.modelName.ifBlank { null },
                    "batt_name" to state.battName.ifBlank { null },
                    "override_any" to if (state.overrideAny) "1" else null,
                    "verbose" to if (state.verbose) "1" else null
                )
                val result = mm.load(koPath, initial)
                if (result.ok) {
                    Log.i(TAG, "Boot load success")
                } else {
                    Log.w(TAG, "Boot load failed: ${result.err}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Boot load exception", e)
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private const val PREFS_NAME = "boot_settings"
        private const val KEY_AUTO_LOAD = "auto_load_module_on_boot"
        // 默认关闭 App 端开机加载，由 Magisk service.sh 负责；用户可在设置中开启
        private const val DEFAULT_AUTO_LOAD = false
    }
}
