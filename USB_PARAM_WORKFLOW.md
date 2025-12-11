# USB 电压电流参数修改工作流程

## 概述
本文档说明修改USB输入电流限制（ICL）和输入电压限制（IVL）的完整工作流程。

## 工作流程

### 1. App层（用户界面）
- **位置**: `ChargingScreen.kt`
- **功能**: 用户输入ICL/IVL参数值
- **处理**: 
  - 参数验证和范围限制（`ChgParamValidator`）
  - 调用 `ChgModuleManager.applyBatch()` 批量应用参数

### 2. App层（参数管理）
- **位置**: `ChgModuleManager.kt`
- **功能**: 管理参数应用逻辑
- **处理**:
  - `validateAndClamp()`: 验证并限制参数范围，如果值为0则返回null（不传递给内核）
  - `applyBatch()`: 构建参数字符串，通过root shell写入 `/proc/chg_param_override`
  - 检查内核日志，检测kprobe拦截功能是否启用
  - 提供详细的错误提示

### 3. 内核模块（参数接收）
- **位置**: `chg_param_override.c`
- **接口**: `/proc/chg_param_override`
- **处理**:
  - `proc_write()`: 接收参数字符串（格式：`key=value`，每行一个）
  - `parse_kv()`: 解析参数并存储到 `g_targets` 结构体
  - `apply_targets_locked()`: 应用所有目标参数

### 4. 内核模块（直接设置）
- **位置**: `chg_param_override.c` -> `apply_targets_locked()`
- **功能**: 尝试直接通过power_supply接口设置参数
- **处理**:
  - `write_psy_int()`: 调用 `power_supply_set_property()` 设置参数
  - 对于ICL: `POWER_SUPPLY_PROP_INPUT_CURRENT_LIMIT`
  - 对于IVL: `POWER_SUPPLY_PROP_INPUT_VOLTAGE_LIMIT`
  - **问题**: 某些驱动可能不支持或拒绝这些参数（例如：PD_PPS检查、不支持IVL）

### 5. 内核模块（三层拦截机制）

#### 5.1 第一层：power_supply_set_property kprobe（参数值拦截）
- **位置**: `set_entry_handler()`
- **时机**: 在驱动处理**之前**拦截
- **功能**: 修改传入驱动层的参数值
- **处理**:
  - 拦截 `power_supply_set_property()` 调用
  - 如果是USB的ICL/IVL设置，将参数值替换为 `g_targets` 中的目标值
  - **限制**: 只能修改参数值，无法绕过驱动内部的检查逻辑（如PD_PPS检查）

#### 5.2 第二层：power_supply_set_property kretprobe（返回值拦截）
- **位置**: `set_ret_entry_handler()` + `set_ret_handler()`
- **时机**: 在驱动处理**之后**拦截返回值
- **功能**: 如果驱动返回错误，覆盖返回值为成功（0）
- **处理**:
  - `set_ret_entry_handler()`: 在函数入口保存拦截信息（标记需要拦截的参数）
  - `set_ret_handler()`: 在函数返回时，如果原始返回值<0且已标记拦截，则覆盖为0（成功）
  - **限制**: 只能欺骗上层调用者，无法真正绕过驱动检查，消息可能根本不会发送到PMIC

#### 5.3 第三层：pmic_glink_write kprobe（底层消息拦截）⭐ **最有效**
- **位置**: `pmic_glink_write_entry_handler()`
- **时机**: 在消息发送到PMIC硬件**之前**拦截
- **功能**: 直接修改发送给电源IC的原始消息结构
- **处理**:
  - 拦截 `pmic_glink_write()` 调用（这是驱动向PMIC发送消息的底层函数）
  - 检查消息类型：`MSG_OWNER_BC` + `BC_USB_STATUS_SET` + `USB_INPUT_CURR_LIMIT`
  - 直接修改消息结构中的 `value` 字段为目标值
  - **优势**: 完全绕过所有驱动检查，直接向硬件发送修改后的消息
  - **当前支持**: 仅ICL（`USB_INPUT_CURR_LIMIT`），IVL需要根据实际property_id添加

## 拦截层选择策略

### 对于ICL（输入电流限制）:
1. **首选**: `pmic_glink_write` 拦截（第三层）- 完全绕过驱动检查
2. **备选**: `power_supply_set_property` kprobe + kretprobe（第一层+第二层）- 如果第三层不可用

### 对于IVL（输入电压限制）:
1. **当前**: `power_supply_set_property` kprobe + kretprobe（第一层+第二层）
2. **未来**: 可以扩展 `pmic_glink_write` 拦截支持IVL（需要找到对应的property_id）

### 对于其他参数（voltage_max, ccc, term等）:
- 使用 `power_supply_set_property` kprobe（第一层）即可，这些参数通常驱动支持

## 代码清理说明

### 已清理：
- ✅ 删除重复的 `set_ret_handler()` 和 `set_ret_entry_handler()` 函数定义

### 保留的拦截层：
- ✅ `power_supply_set_property` kprobe（第一层）- 仍然有用，处理IVL和其他参数
- ✅ `power_supply_set_property` kretprobe（第二层）- 仍然有用，即使pmic_glink_write拦截成功，驱动仍可能返回错误
- ✅ `pmic_glink_write` kprobe（第三层）- 最有效的拦截方式，专门用于ICL

### 为什么保留所有三层？
1. **兼容性**: 不同设备可能支持不同层级的拦截
2. **功能互补**: 
   - 第一层处理IVL和其他参数
   - 第二层处理返回值，即使底层拦截成功
   - 第三层专门处理ICL，绕过所有检查
3. **降级策略**: 如果第三层不可用（符号未找到），可以降级到第一层+第二层

## 错误处理

### App层错误检测：
- 检查内核日志中的 `chg_param_override.*failed` 或 `chg_param_override.*error`
- 检查kprobe拦截日志：`power_supply_set_property.*hooked` 或 `pmic_glink_write.*hooked`
- 根据拦截层提供不同的错误提示

### 内核层错误处理：
- 如果 `power_supply_set_property()` 返回错误，记录日志但不阻止继续
- 如果 `pmic_glink_write` 符号未找到，仅警告，不阻止模块加载
- 所有拦截层都是非阻塞的，即使失败也不影响其他功能

## 性能考虑

- 所有kprobe拦截都在原子上下文（中断上下文）中执行，不能睡眠
- 读取 `g_targets` 时不加锁（简单整数，撕裂风险低且可接受）
- 拦截层开销很小，对性能影响可忽略
