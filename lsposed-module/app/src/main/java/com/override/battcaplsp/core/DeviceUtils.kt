package com.override.battcaplsp.core

import android.os.Build

object DeviceUtils {

    /** 检测当前设备是否为小米/Redmi/POCO 或运行 MIUI/HyperOS */
    fun isMiuiDevice(): Boolean {
        val miuiVersion = runCatching {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, "ro.miui.ui.version.name", "") as String
        }.getOrNull().orEmpty()

        return miuiVersion.isNotEmpty() ||
                Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
                Build.MANUFACTURER.contains("Redmi", ignoreCase = true) ||
                Build.MANUFACTURER.contains("POCO", ignoreCase = true)
    }
}
