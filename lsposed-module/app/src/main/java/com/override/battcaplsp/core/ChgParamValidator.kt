package com.override.battcaplsp.core

/**
 * 充电参数验证工具类
 * 为所有有风险的参数提供范围限制和验证
 */
object ChgParamValidator {
    // 参数范围定义（单位：微单位，如 uV, uA）
    
    /** 充电电压范围 (uV): 3.0V - 5.5V */
    const val VOLTAGE_MAX_MIN_UV = 3_000_000L      // 3.0V
    const val VOLTAGE_MAX_MAX_UV = 5_500_000L      // 5.5V
    
    /** 恒流充电电流范围 (uA): 0 - 5000mA */
    const val CCC_MIN_UA = 0L
    const val CCC_MAX_UA = 5_000_000L               // 5000mA (5A)
    
    /** 终止电流范围 (uA): 0 - 500mA */
    const val TERM_MIN_UA = 0L
    const val TERM_MAX_UA = 500_000L                // 500mA
    
    /** 输入电流限制范围 (uA): 0 - 5000mA */
    const val ICL_MIN_UA = 0L
    const val ICL_MAX_UA = 5_000_000L               // 5000mA (5A)
    
    /** 输入电压限制范围 (uV): 3.0V - 20.0V */
    const val IVL_MIN_UV = 3_000_000L               // 3.0V
    const val IVL_MAX_UV = 20_000_000L              // 20.0V (PD 最大)
    
    /** 充电限制范围 (%): 0 - 100 */
    const val CHARGE_LIMIT_MIN = 0
    const val CHARGE_LIMIT_MAX = 100
    
    /**
     * 验证并限制充电电压 (voltage_max)
     * @return 限制后的值，如果输入为 0 则返回 0（表示不设置）
     */
    fun clampVoltageMax(value: Long): Long {
        if (value == 0L) return 0L
        return value.coerceIn(VOLTAGE_MAX_MIN_UV, VOLTAGE_MAX_MAX_UV)
    }
    
    /**
     * 验证并限制恒流充电电流 (ccc)
     * @return 限制后的值，如果输入为 0 则返回 0（表示不设置）
     */
    fun clampCcc(value: Long): Long {
        if (value == 0L) return 0L
        return value.coerceIn(CCC_MIN_UA, CCC_MAX_UA)
    }
    
    /**
     * 验证并限制终止电流 (term)
     * @return 限制后的值，如果输入为 0 则返回 0（表示不设置）
     */
    fun clampTerm(value: Long): Long {
        if (value == 0L) return 0L
        return value.coerceIn(TERM_MIN_UA, TERM_MAX_UA)
    }
    
    /**
     * 验证并限制输入电流限制 (icl)
     * @return 限制后的值，如果输入为 0 则返回 0（表示不设置）
     */
    fun clampIcl(value: Long): Long {
        if (value == 0L) return 0L
        return value.coerceIn(ICL_MIN_UA, ICL_MAX_UA)
    }
    
    /**
     * 验证并限制输入电压限制 (ivl)
     * @return 限制后的值，如果输入为 0 则返回 0（表示不设置）
     */
    fun clampIvl(value: Long): Long {
        if (value == 0L) return 0L
        return value.coerceIn(IVL_MIN_UV, IVL_MAX_UV)
    }
    
    /**
     * 验证并限制充电限制百分比
     * @return 限制后的值，如果输入为 0 则返回 0（表示不限制）
     */
    fun clampChargeLimit(value: Int): Int {
        if (value == 0) return 0
        return value.coerceIn(CHARGE_LIMIT_MIN, CHARGE_LIMIT_MAX)
    }
    
    /**
     * 验证电压值是否在有效范围内
     * @return Pair<是否有效, 错误消息>
     */
    fun validateVoltageMax(value: Long): Pair<Boolean, String?> {
        if (value == 0L) return Pair(true, null)
        return if (value < VOLTAGE_MAX_MIN_UV || value > VOLTAGE_MAX_MAX_UV) {
            Pair(false, "充电电压必须在 ${VOLTAGE_MAX_MIN_UV / 1_000_000.0}V - ${VOLTAGE_MAX_MAX_UV / 1_000_000.0}V 之间")
        } else {
            Pair(true, null)
        }
    }
    
    /**
     * 验证 CCC 值是否在有效范围内
     */
    fun validateCcc(value: Long): Pair<Boolean, String?> {
        if (value == 0L) return Pair(true, null)
        return if (value < CCC_MIN_UA || value > CCC_MAX_UA) {
            Pair(false, "恒流电流必须在 ${CCC_MIN_UA / 1000}mA - ${CCC_MAX_UA / 1000}mA 之间")
        } else {
            Pair(true, null)
        }
    }
    
    /**
     * 验证终止电流值是否在有效范围内
     */
    fun validateTerm(value: Long): Pair<Boolean, String?> {
        if (value == 0L) return Pair(true, null)
        return if (value < TERM_MIN_UA || value > TERM_MAX_UA) {
            Pair(false, "终止电流必须在 ${TERM_MIN_UA / 1000}mA - ${TERM_MAX_UA / 1000}mA 之间")
        } else {
            Pair(true, null)
        }
    }
    
    /**
     * 验证输入电流限制值是否在有效范围内
     */
    fun validateIcl(value: Long): Pair<Boolean, String?> {
        if (value == 0L) return Pair(true, null)
        return if (value < ICL_MIN_UA || value > ICL_MAX_UA) {
            Pair(false, "输入电流限制必须在 ${ICL_MIN_UA / 1000}mA - ${ICL_MAX_UA / 1000}mA 之间")
        } else {
            Pair(true, null)
        }
    }
    
    /**
     * 验证输入电压限制值是否在有效范围内
     */
    fun validateIvl(value: Long): Pair<Boolean, String?> {
        if (value == 0L) return Pair(true, null)
        return if (value < IVL_MIN_UV || value > IVL_MAX_UV) {
            Pair(false, "输入电压限制必须在 ${IVL_MIN_UV / 1_000_000.0}V - ${IVL_MAX_UV / 1_000_000.0}V 之间")
        } else {
            Pair(true, null)
        }
    }
    
    /**
     * 验证充电限制百分比是否在有效范围内
     */
    fun validateChargeLimit(value: Int): Pair<Boolean, String?> {
        if (value == 0) return Pair(true, null)
        return if (value < CHARGE_LIMIT_MIN || value > CHARGE_LIMIT_MAX) {
            Pair(false, "充电限制必须在 $CHARGE_LIMIT_MIN% - $CHARGE_LIMIT_MAX% 之间")
        } else {
            Pair(true, null)
        }
    }
    
    /**
     * 获取参数的友好范围描述（用于 UI 提示）
     */
    fun getVoltageMaxRange(): String = "${VOLTAGE_MAX_MIN_UV / 1_000_000.0}V - ${VOLTAGE_MAX_MAX_UV / 1_000_000.0}V"
    fun getCccRange(): String = "${CCC_MIN_UA / 1000}mA - ${CCC_MAX_UA / 1000}mA"
    fun getTermRange(): String = "${TERM_MIN_UA / 1000}mA - ${TERM_MAX_UA / 1000}mA"
    fun getIclRange(): String = "${ICL_MIN_UA / 1000}mA - ${ICL_MAX_UA / 1000}mA"
    fun getIvlRange(): String = "${IVL_MIN_UV / 1_000_000.0}V - ${IVL_MAX_UV / 1_000_000.0}V"
    fun getChargeLimitRange(): String = "$CHARGE_LIMIT_MIN% - $CHARGE_LIMIT_MAX%"
}


