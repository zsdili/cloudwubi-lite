#!/bin/bash
# ============================================================
# CloudWubi Android APK 构建脚本（命令行，无 gradle 依赖）
#
# 原理：直接调用 Android SDK 命令行工具链
#   aapt2  -> 编译+打包资源与 Manifest
#   javac  -> 编译 Java 源码（依赖 android.jar）
#   d8     -> 转 dex
#   zipalign + apksigner -> 对齐+签名
#
# 前置：ANDROID_HOME 指向 Android SDK（CI 已预装）
# 用法：bash build_apk.sh
# ============================================================
set -e

SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    echo "❌ 未找到 Android SDK（设置 ANDROID_HOME）"
    exit 1
fi
echo "✅ Android SDK: $SDK"

BUILD_TOOLS="$SDK/build-tools"
BT_VER=$(ls "$BUILD_TOOLS" 2>/dev/null | sort -V | tail -1)
if [ -z "$BT_VER" ]; then
    echo "❌ 未找到 build-tools"
    exit 1
fi
BT="$BUILD_TOOLS/$BT_VER"
PLATFORM="$SDK/platforms"
PLAT_VER=$(ls "$PLATFORM" 2>/dev/null | grep -E "^android-[0-9]+$" | sort -V | tail -1)
ANDROID_JAR="$PLATFORM/$PLAT_VER/android.jar"
echo "✅ build-tools: $BT_VER | platform: $PLAT_VER"

PROJ_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJ_DIR"
OUT=build-apk
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen"

echo "== 1/6 编译资源（aapt2）=="
"$BT/aapt2" compile \
    --dir app/src/main/res \
    -o "$OUT/res.zip"

echo "== 2/6 同步版本号（build.gradle → Manifest，根治"版本号不更新"）=="
VER_NAME=$(grep -o 'versionName "[^"]*"' app/build.gradle | head -1 | cut -d'"' -f2)
VER_CODE=$(grep -o 'versionCode [0-9]*' app/build.gradle | head -1 | awk '{print $2}')
if [ -n "$VER_NAME" ] && [ -n "$VER_CODE" ]; then
    sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VER_CODE\"/" app/src/main/AndroidManifest.xml
    sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER_NAME\"/" app/src/main/AndroidManifest.xml
    echo "✅ 版本同步: $VER_NAME (code $VER_CODE)"
else
    echo "❌ 从 build.gradle 读取版本失败，中止"
    exit 1
fi

echo "== 3/6 链接资源+Manifest =="
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest app/src/main/AndroidManifest.xml \
    --java "$OUT/gen" \
    --min-sdk-version 21 \
    --target-sdk-version 33 \
    "$OUT/res.zip"

echo "== 4/6 编译 Java（javac，含 aapt2 生成的 R.java）=="
find app/src/main/java "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -source 1.8 -target 1.8 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "== 5/6 转 dex（R8 混淆+裁剪，v0.5.6 腾体积用于词库扩容）=="
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
# R8 jar 位置探测：build-tools/lib → cmdline-tools/latest/lib → 任意 cmdline-tools/lib
R8_JAR=""
for cand in "$BT/lib/r8.jar" "$SDK/cmdline-tools/latest/lib/r8.jar" "$SDK"/cmdline-tools/*/lib/r8.jar; do
    if [ -f "$cand" ]; then R8_JAR="$cand"; break; fi
done
if [ -n "$R8_JAR" ]; then
    echo "   🚀 使用 R8（混淆+裁剪）: $R8_JAR"
    java -cp "$R8_JAR" com.android.tools.r8.R8 \
        --release --min-api 21 \
        --lib "$ANDROID_JAR" \
        --pg-conf "$PROJ_DIR/proguard-rules.pro" \
        --output "$OUT" \
        $(cat "$OUT/classes.txt")
else
    echo "   ⚠️ 未找到 r8.jar，回退 d8"
    "$BT/d8" --release --lib "$ANDROID_JAR" \
        --output "$OUT" \
        $(cat "$OUT/classes.txt")
fi

echo "== 6/6 打包 dex + assets 进 APK =="
cd "$OUT"
# v0.5.11：zip -9 最优压缩 + -X 去 extra field（腾体积，保证 ≤100KB 硬门禁）
echo "   dex bytes: $(stat -c%s classes.dex)"
zip -q -9 -X base.apk classes.dex
# v0.5.5 fix：词库位于 assets。zip 必须带 assets/ 前缀（AssetManager 按 assets/<name> 读取），
# 且用 PROJ_DIR 绝对路径定位（此处已在 build-apk 内，$(dirname "$0") 解析会错）
ASSETS_DIR="$PROJ_DIR/app/src/main/assets"
if [ -d "$ASSETS_DIR" ]; then
    OUT_DIR="$(pwd)"
    (cd "$(dirname "$ASSETS_DIR")" && zip -q -9 -X -r "$OUT_DIR/base.apk" assets)
    echo "   ✅ 已并入 assets: $(ls "$ASSETS_DIR" | tr '\n' ' ')"
else
    echo "   ❌ assets 目录不存在: $ASSETS_DIR"
    exit 1
fi

echo "== 7/6 对齐 + 签名（固定发布签名，保证各版本可覆盖安装）=="
"$BT/zipalign" -f 4 base.apk aligned.apk
# v0.5.5 反馈②：优先使用固定签名 keystore（android/keystore/cloudwubi.jks，公开开源），
# 保证每个版本签名一致 → 用户可直接覆盖安装，无需卸载；无固定 keystore 时回退 debug key
KEYSTORE=""
STORE_PASS=""
KEY_PASS=""
# v0.5.5 fix：签名段可能已 cd 到 build-apk，必须用绝对路径定位 keystore
if [ -f "$PROJ_DIR/keystore/cloudwubi.jks" ]; then
    KEYSTORE="$PROJ_DIR/keystore/cloudwubi.jks"
    STORE_PASS="cloudwubi2026"
    KEY_PASS="cloudwubi2026"
fi
if [ -z "$KEYSTORE" ]; then
    # debug keystore（首次生成，仅本地临时构建回退）
    KEYSTORE=debug.keystore
    STORE_PASS=android
    KEY_PASS=android
    if [ ! -f "$KEYSTORE" ]; then
        keytool -genkeypair -v -keystore "$KEYSTORE" \
            -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass android -keypass android \
            -dname "CN=CloudWubi, OU=OSS, O=CloudWubi, L=SZ, ST=GD, C=CN" 2>/dev/null
    fi
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:"$STORE_PASS" \
    --key-pass pass:"$KEY_PASS" \
    --out CloudWubi.apk aligned.apk

echo ""
echo "== 构建完成 =="
SIZE=$(stat -c %s CloudWubi.apk 2>/dev/null || stat -f %z CloudWubi.apk)
echo "✅ CloudWubi.apk: $SIZE 字节（$(du -h CloudWubi.apk | cut -f1)）"
# v0.5.91 用户要求：安装包文件名带版本号便于识别
VNAME=$(grep -o 'versionName "[^"]*"' "$PROJ_DIR/app/build.gradle" | head -1 | sed 's/versionName "//;s/"//')
if [ -n "$VNAME" ]; then
  cp -f CloudWubi.apk "CloudWubi-v$VNAME.apk" 2>/dev/null || true
  echo "✅ 带版本号产物: CloudWubi-v$VNAME.apk"
fi
if [ "$SIZE" -gt 102400 ]; then
    echo "❌ 超过 100KB 上限！（固化要求：安装包 ≤100KB）"
    exit 1
fi
echo "✅ 体积达标（≤ 100KB）"

echo "== 自检：APK 内必须包含基础库 assets（v0.5.23：动态拼词仅需 wubi_single.txt）=="
if unzip -l CloudWubi.apk | grep -q "assets/wubi86_single.txt"; then
    echo "✅ 基础库已打包（wubi_single.txt，动态拼词引擎替代词组库）"
else
    echo "❌ APK 缺少基础库 assets！"
    unzip -l CloudWubi.apk | head -30
    exit 1
fi
