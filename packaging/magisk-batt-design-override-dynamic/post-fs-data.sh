#!/system/bin/sh
# Magisk post-fs-data: prepare/mount power_profile.xml override before services start

MODDIR=${0%/*}

# Load params
. "$MODDIR/common/params.conf"

# Optional: override power_profile.xml to change Settings displayed capacity
PROFILE_ENABLE=${PROFILE_ENABLE:-0}
CAPACITY_MAH_CFG=${CAPACITY_MAH:-0}
PATCH_ALL=${PATCH_ALL:-1}
# DEVICE_FEATURES_ENABLE 默认：若用户未在 params.conf 明确设置，则在检测到 MIUI/HyperOS 时自动启用，否则关闭
if [ -z "${DEVICE_FEATURES_ENABLE+x}" ]; then
  if getprop ro.miui.ui.version.name >/dev/null 2>&1 && [ -n "$(getprop ro.miui.ui.version.name)" ]; then
    DEVICE_FEATURES_ENABLE=1
    logi "auto-enable device_features patch for MIUI/HyperOS (ro.miui.ui.version.name=$(getprop ro.miui.ui.version.name))"
  else
    DEVICE_FEATURES_ENABLE=0
  fi
else
  DEVICE_FEATURES_ENABLE=${DEVICE_FEATURES_ENABLE:-0}
fi

calc_cap_mah() {
  # Prefer explicit CAPACITY_MAH; else derive from DESIGN_UAH
  if [ "${CAPACITY_MAH_CFG:-0}" -gt 0 ] 2>/dev/null; then
    echo "$CAPACITY_MAH_CFG"
  elif [ "${DESIGN_UAH:-0}" -gt 0 ] 2>/dev/null; then
    echo $((DESIGN_UAH / 1000))
  else
    echo 0
  fi
}

patch_profile() {
  local src="$1" tgt="$2" cap="$3"
  mkdir -p "$(dirname "$tgt")"
  cp -p "$src" "$tgt" 2>/dev/null || cp "$src" "$tgt" 2>/dev/null || return 1
  # Replace <item name="battery.capacity">VALUE</item>
  sed -i -E "s#(<item[[:space:]]+name=\"battery\\.capacity\">)[0-9.]+([[:space:]]*</item>)#\\1${cap}\\2#g" "$tgt"
  # Also try single quotes variant
  sed -i -E "s#(<item[[:space:]]+name='battery\\.capacity'>)[0-9.]+([[:space:]]*</item>)#\\1${cap}\\2#g" "$tgt"
}

map_target() {
  case "$1" in
    /vendor/*)     echo "$MODDIR/system/vendor${1#/vendor}" ;;
    /odm/*)        echo "$MODDIR/system/odm${1#/odm}" ;;
    /system_ext/*) echo "$MODDIR/system/system_ext${1#/system_ext}" ;;
    /product/*)    echo "$MODDIR/system/product${1#/product}" ;;
    /system/*)     echo "$MODDIR/system${1#/system}" ;;
    *)             echo "$MODDIR/system/etc/power_profile.xml" ;;
  esac
}

logi "PROFILE_ENABLE=${PROFILE_ENABLE} DEVICE_FEATURES_ENABLE=${DEVICE_FEATURES_ENABLE} PATCH_ALL=${PATCH_ALL}"

if [ "$PROFILE_ENABLE" = "1" ]; then
  CAP_MAH=$(calc_cap_mah)
  logi "target capacity (mAh) for power_profile: ${CAP_MAH} (from CAPACITY_MAH=${CAPACITY_MAH_CFG}, DESIGN_UAH=${DESIGN_UAH})"
  if [ "$CAP_MAH" -gt 0 ] 2>/dev/null; then
    FOUND=0
    # Detect and patch all matching power_profile xml files
    for ORIG in \
      /vendor/etc/power_profile.xml \
      /vendor/etc/power_profile_*.xml \
      /odm/etc/power_profile.xml \
      /odm/etc/power_profile_*.xml \
      /system_ext/etc/power_profile.xml \
      /system_ext/etc/power_profile_*.xml \
      /product/etc/power_profile.xml \
      /product/etc/power_profile_*.xml \
      /system/etc/power_profile.xml \
      /system/etc/power_profile_*.xml; do
      if [ -f "$ORIG" ]; then
        TGT=$(map_target "$ORIG")
        if patch_profile "$ORIG" "$TGT" "$CAP_MAH"; then
          FOUND=1
          logi "patched power_profile: $ORIG -> $TGT (capacity=${CAP_MAH}mAh)"
          # show snippet for verification
          grep -n "battery.capacity" "$TGT" | head -n 2 | while read -r line; do logi "verify: ${line}"; done
          [ "$PATCH_ALL" = "1" ] || break
        else
          logi "failed to patch $ORIG"
        fi
      fi
    done
    if [ "$FOUND" != "1" ]; then
      # Fallback: create a minimal external power_profile.xml; some ROMs prefer external file over framework-res resource
      TGT_FALLBACK="$MODDIR/system/etc/power_profile.xml"
      mkdir -p "$(dirname "$TGT_FALLBACK")"
      cat >"$TGT_FALLBACK" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<device name="Android">
    <item name="battery.capacity">${CAP_MAH}</item>
</device>
EOF
      logi "created fallback power_profile: $TGT_FALLBACK (capacity=${CAP_MAH}mAh)"
    fi
  else
    logi "PROFILE_ENABLE=1 but no valid capacity found"
  fi
fi

# ===== Xiaomi/HyperOS device_features 覆盖（影响设置显示）=====
if [ "${DEVICE_FEATURES_ENABLE}" = "1" ]; then
  CAP_MAH=$(calc_cap_mah)
  logi "target capacity (mAh) for device_features: ${CAP_MAH}"
  if [ "$CAP_MAH" -gt 0 ] 2>/dev/null; then
    DF_FOUND=0
    for ORIG in \
      /system/etc/device_features/*.xml \
      /product/etc/device_features/*.xml \
      /system_ext/etc/device_features/*.xml \
      /vendor/etc/device_features/*.xml \
      /odm/etc/device_features/*.xml; do
      if [ -f "$ORIG" ]; then
        TGT=$(map_target "$ORIG")
        mkdir -p "$(dirname "$TGT")"
        if cp -p "$ORIG" "$TGT" 2>/dev/null || cp "$ORIG" "$TGT" 2>/dev/null; then
          # 常见形式：<integer name=\"battery_capacity\">4500</integer>
          sed -i -E "s#(<integer[^>]+name=\"battery_capacity\"[^>]*>)[0-9.]+(</integer>)#\\1${CAP_MAH}\\2#g" "$TGT"
          # 扩展：typ/min/max/rated/show 等键
          sed -i -E "s#(<integer[^>]+name=\"battery_capacity_typ\"[^>]*>)[0-9.]+(</integer>)#\\1${CAP_MAH}\\2#g" "$TGT"
          sed -i -E "s#(<integer[^>]+name=\"battery_capacity_show\"[^>]*>)[0-9.]+(</integer>)#\\1${CAP_MAH}\\2#g" "$TGT"
          # 兼容 <item ...>
          sed -i -E "s#(<item[^>]+name=\"battery_capacity\"[^>]*>)[0-9.]+(</item>)#\\1${CAP_MAH}\\2#g" "$TGT"
          sed -i -E "s#(<item[^>]+name=\"battery_capacity_typ\"[^>]*>)[0-9.]+(</item>)#\\1${CAP_MAH}\\2#g" "$TGT"
          sed -i -E "s#(<item[^>]+name=\"battery_capacity_show\"[^>]*>)[0-9.]+(</item>)#\\1${CAP_MAH}\\2#g" "$TGT"
          # 兼容 <string ...>
          sed -i -E "s#(<string[^>]+name=\"battery_capacity\"[^>]*>)[0-9.]+(</string>)#\\1${CAP_MAH}\\2#g" "$TGT"
          sed -i -E "s#(<string[^>]+name=\"battery_capacity_typ\"[^>]*>)[0-9.]+(</string>)#\\1${CAP_MAH}\\2#g" "$TGT"
          sed -i -E "s#(<string[^>]+name=\"battery_capacity_show\"[^>]*>)[0-9.]+(</string>)#\\1${CAP_MAH}\\2#g" "$TGT"
          # 兼容 属性对 battery_capacity="4500" 以及其他键
          for key in battery_capacity battery_capacity_typ battery_capacity_show; do
            sed -i -E "s#(${key}=\")[0-9.]+(\")#\\1${CAP_MAH}\\2#g" "$TGT"
          done
          # 如果关键键不存在，则在 </features> 前插入 integer 形式，增强兼容性
          ensure_key() {
            local key="$1"; local val="$2"; local file="$3"
            if ! grep -q "name=\"${key}\"" "$file" 2>/dev/null; then
              sed -i "/<\\/features>/i \\t<integer name=\"${key}\">${val}<\\/integer>" "$file"
              logi "inserted device_features key: ${key}=${val} -> $file"
            fi
          }
          ensure_key battery_capacity "$CAP_MAH" "$TGT"
          ensure_key battery_capacity_show "$CAP_MAH" "$TGT"
          ensure_key battery_rated_capacity "$CAP_MAH" "$TGT"
          DF_FOUND=1
          logi "patched device_features: $ORIG -> $TGT (battery_capacity=${CAP_MAH}mAh)"
          grep -n "battery_capacity" "$TGT" | head -n 2 | while read -r line; do logi "verify: ${line}"; done
          [ "$PATCH_ALL" = "1" ] || break
        else
          logi "failed to copy $ORIG -> $TGT"
        fi
      fi
    done
    [ "$DF_FOUND" = "1" ] || logi "no device_features/*.xml found to patch"
  fi
fi

# ===== 挂载自定义 overlay APK（/vendor/overlay/ 路径）=====
if [ -d "$MODDIR/common/overlay" ]; then
  for APK in "$MODDIR"/common/overlay/*.apk; do
    [ -f "$APK" ] || continue
    TGT="$MODDIR/system/vendor/overlay/$(basename "$APK")"
    mkdir -p "$(dirname "$TGT")"
    cp -p "$APK" "$TGT" 2>/dev/null || cp "$APK" "$TGT" 2>/dev/null
    chmod 0644 "$TGT" 2>/dev/null
    if command -v chcon >/dev/null 2>&1; then
      chcon -h u:object_r:system_file:s0 "$TGT" 2>/dev/null || true
    fi
    logi "mounted custom overlay: $TGT"
  done
fi

# Fallback: bind mount到 /vendor/overlay
for APK in "$MODDIR"/system/vendor/overlay/*.apk; do
  [ -f "$APK" ] || continue
  BASENAME=$(basename "$APK")
  REAL_DIR=/vendor/overlay
  REAL_PATH="$REAL_DIR/$BASENAME"
  
  if grep -q " $REAL_PATH " /proc/mounts 2>/dev/null; then
    logi "skip bind (already mounted): $REAL_PATH"
    continue
  fi
  
  if [ -d "$REAL_DIR" ]; then
    if mount --bind "$APK" "$REAL_PATH" 2>/dev/null; then
      chmod 0644 "$REAL_PATH" 2>/dev/null || true
      if command -v chcon >/dev/null 2>&1; then
        chcon -h u:object_r:system_file:s0 "$REAL_PATH" 2>/dev/null || true
      fi
      logi "bind-mounted overlay: $APK -> $REAL_PATH"
    else
      logi "bind mount failed: $APK -> $REAL_PATH"
    fi
  else
    logi "vendor overlay dir missing: $REAL_DIR"
  fi
done

pm disable com.miui.securitycenter/com.miui.powercenter.provider.PowerSaveService

exit 0
