#!/system/bin/sh
# Magisk service script: load batt_design_override.ko early in post-boot

MODDIR=${0%/*}

# logging
LOGFILE="$MODDIR/log.txt"
_log() { echo "[magisk-batt][service] $*" | tee -a "$LOGFILE" >/dev/null; }
_logcat() { command -v log >/dev/null 2>&1 && log -p i -t magisk-batt "[service] $*"; }
logi() { _log "$*"; _logcat "$*"; }

# read params from config
. "$MODDIR/common/params.conf"

# 兼容：若用户写了小写 model_name，则与大写变量互通
[ -n "${model_name:-}" ] && [ -z "${MODEL_NAME:-}" ] && MODEL_NAME="$model_name"
[ -n "${MODEL_NAME:-}" ] && model_name="$MODEL_NAME"

# where we place the ko
KOMOD="$MODDIR/common/batt_design_override.ko"

logi "boot wait..."
# Wait for boot complete and su available if needed
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done
logi "boot completed"

logi "params batt_name=${BATT_NAME} design_uah=${DESIGN_UAH} design_uwh=${DESIGN_UWH} model_name=${MODEL_NAME:-<empty>} override_any=${OVERRIDE_ANY} verbose=${VERBOSE}"

# Prefer insmod (kernel module loader)
if [ -f "$KOMOD" ]; then
  # Try to insmod with parameters; fall back to modprobe if available
  # 仅当配置了 MODEL_NAME（非空）时才传递，避免空字符串影响判断；
  # 但 insmod 传空字符串也问题不大，这里做条件控制更直观。
  # Build argument list safely without eval
  set --
  set -- "$@" "batt_name=$BATT_NAME"
  set -- "$@" "design_uah=$DESIGN_UAH"
  set -- "$@" "design_uwh=$DESIGN_UWH"
  [ -n "${MODEL_NAME:-}" ] && set -- "$@" "model_name=$MODEL_NAME"
  set -- "$@" "override_any=$OVERRIDE_ANY"
  set -- "$@" "verbose=$VERBOSE"
  if insmod "$KOMOD" "$@" 2>>"$LOGFILE"; then
    logi "insmod success"
  elif command -v modprobe >/dev/null 2>&1; then
    if modprobe "$KOMOD" "$@" 2>>"$LOGFILE"; then
      logi "modprobe success"
    else
      logi "modprobe failed"
    fi
  else
    logi "insmod failed (no modprobe)"
  fi
else
  logi "ko not found: $KOMOD"
fi

# 将目标容量作为系统属性暴露，供 LSPosed Hook 使用（无论是否启用 shared_prefs 覆盖）
calc_cap_mah_global() {
  if [ "${CAPACITY_MAH:-0}" -gt 0 ] 2>/dev/null; then
    echo "${CAPACITY_MAH}"
  elif [ "${DESIGN_UAH:-0}" -gt 0 ] 2>/dev/null; then
    echo $((DESIGN_UAH / 1000))
  else
    echo 0
  fi
}
CAP_MAH_GLOBAL=$(calc_cap_mah_global)
if [ "$CAP_MAH_GLOBAL" -gt 0 ] 2>/dev/null; then
  setprop persist.sys.batt.capacity_mah "$CAP_MAH_GLOBAL"
  logi "exported persist.sys.batt.capacity_mah=$CAP_MAH_GLOBAL"
fi


# ===== 自动安装 LSPosed 模块 APK（可选）=====
auto_install_lsp() {
  local pkg="${LSP_PKG_NAME:-com.example.battcaplsp}"
  local apk_path_cfg="${LSP_APK_PATH:-$MODDIR/common/battcaplsp.apk}"
  local apk_path="$apk_path_cfg"
  # 若路径中包含 ${MODDIR} 变量文本，安全展开（避免 eval）
  apk_path=$(echo "$apk_path" | sed "s|\\\${MODDIR}|$MODDIR|g")
  if [ "${AUTO_INSTALL_LSPOSED:-0}" != "1" ]; then
    return
  fi
  if [ ! -f "$apk_path" ]; then
    logi "auto-install: apk not found $apk_path"
    return
  fi
  local installed=0
  if pm list packages | grep -q "$pkg" 2>/dev/null; then
    installed=1
  fi
  if [ "$installed" = "1" ] && [ "${LSP_FORCE_REINSTALL:-0}" != "1" ]; then
    logi "auto-install: $pkg already installed, skip"
    return
  fi
  # 推送到临时路径后再安装，避免直接从挂载路径安装出错
  local tmp_apk="/data/local/tmp/$(basename "$apk_path")"
  cp "$apk_path" "$tmp_apk" 2>/dev/null || {
    logi "auto-install: copy to $tmp_apk failed"
    return
  }
  pm install -r -d "$tmp_apk" >/dev/null 2>&1 && logi "auto-install: installed $pkg" || logi "auto-install: install failed"
}

# 执行自动安装
auto_install_lsp &
