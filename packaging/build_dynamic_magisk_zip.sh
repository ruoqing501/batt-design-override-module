#!/usr/bin/env bash
#
# 打包默认动态版 batt_design_override Magisk 模块 ZIP
# 源目录：packaging/magisk-batt-design-override-dynamic/
# 该目录默认已包含 battcaplsp.apk、batt_design_override.ko、chg_param_override.ko
#
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)

MODULE_DIR="$WS_ROOT/packaging/magisk-batt-design-override-dynamic"
OUT_DIR="$WS_ROOT/dist"

usage(){
  cat <<EOF
用法: $0 [--version <ver>] [--output <dir>] [--apk-path <path>] [--batt-ko <path>] [--chg-ko <path>]
打包默认动态版 Magisk 模块，源目录已包含默认 .ko 与 APK
EOF
}

die(){ echo "[x] $*" >&2; exit 1; }

APK_PATH=""
BATT_KO_PATH=""
CHG_KO_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="$2"; shift 2;;
    --output) OUT_DIR="$2"; shift 2;;
    --apk-path) APK_PATH="$2"; shift 2;;
    --batt-ko) BATT_KO_PATH="$2"; shift 2;;
    --chg-ko) CHG_KO_PATH="$2"; shift 2;;
    -h|--help) usage; exit 0;;
    *) die "未知参数: $1";;
  esac
done

[[ -d "$MODULE_DIR" ]] || die "模块目录不存在: $MODULE_DIR"

MODULE_PROP="$MODULE_DIR/module.prop"
[[ -f "$MODULE_PROP" ]] || die "缺少 module.prop: $MODULE_PROP"

# 默认从 module.prop 读取 id 和 version
BASE_ID=$(grep -E '^id=' "$MODULE_PROP" | head -n1 | cut -d= -f2- | tr -d '\r')
[[ -n "$BASE_ID" ]] || BASE_ID="batt-design-override"

if [[ -z "${VERSION:-}" ]]; then
  VERSION=$(grep -E '^version=' "$MODULE_PROP" | head -n1 | cut -d= -f2- | tr -d '\r')
  [[ -n "$VERSION" ]] || die "无法解析版本号"
fi

mkdir -p "$OUT_DIR"
OUT_DIR=$(cd -- "$OUT_DIR" && pwd)

STAGE="$OUT_DIR/${BASE_ID}-stage"
rm -rf "$STAGE" && mkdir -p "$STAGE/common"
rsync -a "$MODULE_DIR/" "$STAGE/"

# 更新 stage 里的版本号（不回写原始源码）
sed -i "s/^version=.*/version=$VERSION/" "$STAGE/module.prop"

# 覆盖 APK（若提供）
if [[ -n "$APK_PATH" && -f "$APK_PATH" ]]; then
  cp -f "$APK_PATH" "$STAGE/common/battcaplsp.apk"
  echo "[i] 已覆盖应用 APK: $APK_PATH"
fi

# 覆盖电池模块 .ko（若提供）
if [[ -n "$BATT_KO_PATH" && -f "$BATT_KO_PATH" ]]; then
  cp -f "$BATT_KO_PATH" "$STAGE/common/batt_design_override.ko"
  echo "[i] 已覆盖电池模块 .ko: $BATT_KO_PATH"
fi

# 覆盖充电模块 .ko（若提供）
if [[ -n "$CHG_KO_PATH" && -f "$CHG_KO_PATH" ]]; then
  cp -f "$CHG_KO_PATH" "$STAGE/common/chg_param_override.ko"
  echo "[i] 已覆盖充电模块 .ko: $CHG_KO_PATH"
fi

# 设置脚本权限
for f in service.sh post-fs-data.sh customize.sh; do
  [[ -f "$STAGE/$f" ]] && chmod 755 "$STAGE/$f"
done

# 移除来源中可能存在的旧签名文件，避免重新打包后签名失效
rm -f "$STAGE/META-INF/ANDROID.RSA" "$STAGE/META-INF/ANDROID.SF" "$STAGE/META-INF/MANIFEST.MF"

# 动态模块 zip 文件名直接使用模块 id
ZIP_NAME="${BASE_ID}-${VERSION}.zip"
(
  cd "$STAGE"
  zip -r9 "$OUT_DIR/$ZIP_NAME" . >/dev/null
)

rm -rf "$STAGE"

echo "[i] MODULE_ID: $BASE_ID"
echo "[i] VERSION: $VERSION"
echo "[i] OUT: $OUT_DIR/$ZIP_NAME"
echo "[✓] 动态模块打包完成"
