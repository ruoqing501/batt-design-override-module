#!/system/bin/sh
# 自动加载 batt_design_override.ko 与可选 chg_param_override.ko
# 读取同目录 common/params.conf 中的参数并 insmod/应用
# 支持的环境变量/键：
#   MODEL_NAME   -> model_name=<val>
#   DESIGN_UAH   -> design_uah=<val>
#   DESIGN_UWH   -> design_uwh=<val>
#   OVERRIDE_ANY -> override_any=1|0
#   BATT_NAME    -> batt_name=<val>
#   VERBOSE      -> verbose=1|0
#
# 可通过创建 /data/adb/modules/batt-design-override/disable_autoload 标记文件禁用自动加载。

MODDIR=${0%/*}
COMM_DIR="$MODDIR/common"
CONF="$COMM_DIR/params.conf"
FLAG_DISABLE="$MODDIR/disable_autoload"
KERNEL_LINE="" # 仅用于区分多版本 ko（可选）

# 先尝试读取配置以获取 VERBOSE（尽早决定是否写日志）
[ -f "$CONF" ] && . "$CONF"
# shellcheck source=/dev/null

# 根据 VERBOSE 控制是否写入文件日志
LOGFILE="$MODDIR/log.txt"
_log() {
  if [ "${VERBOSE:-0}" = "1" ]; then
    echo "[batt-design-override][service] $*" | tee -a "$LOGFILE" >/dev/null
  else
    echo "[batt-design-override][service] $*"
  fi
}
log() { _log "$*"; }
logw() {
  if [ "${VERBOSE:-0}" = "1" ]; then
    echo "[batt-design-override][service][warn] $*" | tee -a "$LOGFILE" >/dev/null
  else
    echo "[batt-design-override][service][warn] $*"
  fi
}

# Enhanced error logging with dmesg support
log_insmod_error() {
    local module_path="$1"
    local module_name="$(basename "$module_path" .ko)"
    
    log "insmod failed for $module_name, collecting detailed error information..."
    
    # 获取最新的dmesg信息（最近30行）
    if [ "${VERBOSE:-0}" = "1" ]; then
      echo "[batt-design-override][service] === dmesg output (last 30 lines) ===" >> "$LOGFILE"
      dmesg | tail -30 >> "$LOGFILE" 2>/dev/null || echo "dmesg not available" >> "$LOGFILE"
    fi
    
    # 查找与模块相关的特定错误信息
    if [ "${VERBOSE:-0}" = "1" ]; then
      echo "[batt-design-override][service] === module-specific errors ===" >> "$LOGFILE"
      dmesg | grep -i "$module_name" | tail -10 >> "$LOGFILE" 2>/dev/null || echo "no module-specific dmesg found" >> "$LOGFILE"
    fi
    
    # 查找一般的内核模块加载错误
    if [ "${VERBOSE:-0}" = "1" ]; then
      echo "[batt-design-override][service] === general insmod/modprobe errors ===" >> "$LOGFILE"
      dmesg | grep -E "(insmod|modprobe|module.*failed|Invalid module|Unknown symbol)" | tail -10 >> "$LOGFILE" 2>/dev/null || echo "no general module errors found" >> "$LOGFILE"
    fi
    
    # 检查模块文件信息
    if [ "${VERBOSE:-0}" = "1" ]; then
      echo "[batt-design-override][service] === module file info ===" >> "$LOGFILE"
      ls -la "$module_path" >> "$LOGFILE" 2>/dev/null || echo "module file not found: $module_path" >> "$LOGFILE"
    fi
    
    # 检查内核版本兼容性
    if [ "${VERBOSE:-0}" = "1" ]; then
      echo "[batt-design-override][service] === kernel compatibility check ===" >> "$LOGFILE"
      echo "Current kernel: $(uname -r)" >> "$LOGFILE"
      if command -v modinfo >/dev/null 2>&1; then
        modinfo "$module_path" 2>/dev/null | grep -E "(vermagic|depends)" >> "$LOGFILE" || echo "modinfo failed or not available" >> "$LOGFILE"
      fi
    fi
    
    # 检查模块依赖
    if [ "${VERBOSE:-0}" = "1" ]; then
      if [ -f /proc/modules ]; then
        echo "[batt-design-override][service] === loaded modules check ===" >> "$LOGFILE"
        grep -E "(battery|power_supply|qcom)" /proc/modules >> "$LOGFILE" 2>/dev/null || echo "no related modules found in /proc/modules" >> "$LOGFILE"
      fi
      echo "[batt-design-override][service] === end of detailed error report ===" >> "$LOGFILE"
      log "detailed error information collected, check $LOGFILE for full report"
    fi
}

if [ -f "$FLAG_DISABLE" ]; then
  log "disable_autoload 存在，跳过加载"
  exit 0
fi

# 选择优先顺序：完全匹配的文件名优先
# 简单检测当前内核主版本 (如 5.15.123-gXXXX)
KREL=$(uname -r 2>/dev/null)
BASE_VER="${KREL%%-*}"   # 5.15.123
MAJOR_MINOR=$(echo "$BASE_VER" | cut -d. -f1,2) # 5.15

# 查找可用的 .ko 文件（按优先级排序，完全匹配优先）
ANDROID_VERSIONS="android11 android12 android13 android14 android15"
KO_SELECTED=""

# 优先级0: 新格式 androidXX-kernel_module.ko
for android_ver in $ANDROID_VERSIONS; do
    # 尝试完整版本
    ko_file="$COMM_DIR/${android_ver}-${KREL}_batt_design_override.ko"
    if [ -f "$ko_file" ]; then
        KO_SELECTED="$ko_file"
        log "找到新格式模块 (完整版本): $(basename "$KO_SELECTED")"
        break
    fi
    # 尝试主次版本
    ko_file="$COMM_DIR/${android_ver}-${MAJOR_MINOR}_batt_design_override.ko"
    if [ -f "$ko_file" ]; then
        KO_SELECTED="$ko_file"
        log "找到新格式模块 (主次版本): $(basename "$KO_SELECTED")"
        break
    fi
done

# 优先级1: 完全匹配 android版本+完整内核版本
if [ -z "$KO_SELECTED" ]; then
    for android_ver in $ANDROID_VERSIONS; do
        ko_file="$COMM_DIR/batt_design_override-${android_ver}-${KREL}.ko"
        if [ -f "$ko_file" ]; then
            KO_SELECTED="$ko_file"
            log "找到完全匹配的模块 (android+完整版本): $(basename "$KO_SELECTED")"
            break
        fi
    done
fi

# 优先级2: 完全匹配 android版本+主次版本
if [ -z "$KO_SELECTED" ]; then
    for android_ver in $ANDROID_VERSIONS; do
        ko_file="$COMM_DIR/batt_design_override-${android_ver}-${MAJOR_MINOR}.ko"
        if [ -f "$ko_file" ]; then
            KO_SELECTED="$ko_file"
            log "找到完全匹配的模块 (android+主次版本): $(basename "$KO_SELECTED")"
            break
        fi
    done
fi

# 优先级3: 简化匹配 完整内核版本
if [ -z "$KO_SELECTED" ]; then
    ko_file="$COMM_DIR/batt_design_override-${KREL}.ko"
    if [ -f "$ko_file" ]; then
        KO_SELECTED="$ko_file"
        log "找到匹配的模块 (完整版本): $(basename "$KO_SELECTED")"
    fi
fi

# 优先级4: 简化匹配 主次版本
if [ -z "$KO_SELECTED" ]; then
    ko_file="$COMM_DIR/batt_design_override-${MAJOR_MINOR}.ko"
    if [ -f "$ko_file" ]; then
        KO_SELECTED="$ko_file"
        log "找到匹配的模块 (主次版本): $(basename "$KO_SELECTED")"
    fi
fi

# 优先级5: 通用匹配
if [ -z "$KO_SELECTED" ]; then
    ko_file="$COMM_DIR/batt_design_override.ko"
    if [ -f "$ko_file" ]; then
        KO_SELECTED="$ko_file"
        log "找到通用模块: $(basename "$KO_SELECTED")"
    fi
fi

if [ -z "$KO_SELECTED" ]; then
  log "未找到可用内核模块"; exit 1
fi

# 解析配置
[ -f "$CONF" ] && . "$CONF"
# shellcheck source=/dev/null

# 转换键为 insmod 参数
ARGS=""
[ -n "$MODEL_NAME" ] && ARGS="$ARGS model_name=$MODEL_NAME"
[ -n "$DESIGN_UAH" ] && ARGS="$ARGS design_uah=$DESIGN_UAH"
[ -n "$DESIGN_UWH" ] && ARGS="$ARGS design_uwh=$DESIGN_UWH"
[ -n "$BATT_NAME" ] && ARGS="$ARGS batt_name=$BATT_NAME"
[ -n "$OVERRIDE_ANY" ] && ARGS="$ARGS override_any=$OVERRIDE_ANY"
[ -n "$VERBOSE" ] && ARGS="$ARGS verbose=$VERBOSE"

# 去掉前导空格
ARGS=$(echo "$ARGS" | sed 's/^ *//')

log "加载模块: $KO_SELECTED 参数: $ARGS"
# 优先使用 insmod；如果失败尝试 modprobe （多数 AOSP 系统不含）
if ! insmod "$KO_SELECTED" $ARGS 2>>"$LOGFILE"; then
  log_insmod_error "$KO_SELECTED"
  if command -v modprobe >/dev/null 2>&1; then
    log "insmod 失败，尝试 modprobe"
    if modprobe "$KO_SELECTED" $ARGS 2>>"$LOGFILE"; then
      log "modprobe 成功"
    else
      log "modprobe 也失败"
      log_insmod_error "$KO_SELECTED"
    fi
  else
    log "insmod 失败且无 modprobe 可用"
  fi
else
  log "insmod 成功"
fi

# ========== 充电模块开机加载已禁用 ==========
# 说明：chg_param_override.ko 目前存在稳定性问题，开机自动加载可能导致设备重启。
# 如需使用充电模块，请进入 App 的「充电」页手动加载。
log "充电模块开机自动加载已禁用"

# ========== 可选：安装随包 APK（LSPosed 助手） ==========
# 通过 APP_AUTOINSTALL=1 控制是否自动安装（默认 1）
APP_APK="$COMM_DIR/battcaplsp.apk"
APP_PKG="${APP_PKG:-com.override.battcaplsp}"
APP_OLD_PKG="${APP_OLD_PKG:-com.example.battcaplsp}"
APP_REMOVE_OLD="${APP_REMOVE_OLD:-1}"
if [ "${APP_AUTOINSTALL:-1}" = "1" ] && [ -f "$APP_APK" ]; then
  log "检测到 APK，准备安装: $APP_APK (pkg=$APP_PKG)"
  # 等待开机完成属性，最多等待 20 秒
  n=0; while [ $n -lt 20 ]; do
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then break; fi
    sleep 1; n=$((n+1))
  done
  # 若启用，先检测并卸载旧包
  if [ "$APP_REMOVE_OLD" = "1" ] && [ -n "$APP_OLD_PKG" ]; then
    if command -v cmd >/dev/null 2>&1; then
      if cmd package list packages | grep -q "^package:$APP_OLD_PKG$"; then
        log "检测到旧包，卸载: $APP_OLD_PKG"
        cmd package uninstall --user 0 "$APP_OLD_PKG" >/dev/null 2>&1 || pm uninstall -k --user 0 "$APP_OLD_PKG" >/dev/null 2>&1 || logw "卸载旧包失败"
        sleep 1
      fi
    else
      if pm list packages | grep -q "^package:$APP_OLD_PKG$"; then
        log "检测到旧包，卸载: $APP_OLD_PKG"
        pm uninstall -k --user 0 "$APP_OLD_PKG" >/dev/null 2>&1 || logw "卸载旧包失败"
        sleep 1
      fi
    fi
  fi

  # 已安装检查
  INSTALLED=0
  if command -v cmd >/dev/null 2>&1; then
    if cmd package list packages | grep -q "^package:$APP_PKG$"; then INSTALLED=1; fi
  else
    if pm list packages | grep -q "^package:$APP_PKG$"; then INSTALLED=1; fi
  fi
  if [ $INSTALLED -eq 1 ] && [ "${APP_FORCE_REINSTALL:-0}" != "1" ]; then
    log "检测到已安装且未启用强制重装，跳过安装"
  else
    if command -v cmd >/dev/null 2>&1; then
      cmd package install -r -d -g --user 0 "$APP_APK" || pm install -r -d -g "$APP_APK" || logw "App 安装失败"
    else
      pm install -r -d -g "$APP_APK" || logw "App 安装失败"
    fi
  fi
else
  log "未启用 APP_AUTOINSTALL 或 APK 缺失，跳过 App 安装"
fi

exit 0
