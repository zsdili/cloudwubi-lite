# CloudWubi R8 规则（v0.5.6）
# 目标：裁剪未用代码 + 混淆类名，腾出 APK 空间用于词库扩容（体积硬约束 ≤100KB）

# 输入法服务由系统按 AndroidManifest 中的类名反射加载，必须完整保留
-keep class com.cloudwubi.ime.CloudWubiIME { *; }

# 资源 R 类（R8 需保留，资源引用按 R 字段名）
-keep class com.cloudwubi.ime.R { *; }
-keep class com.cloudwubi.ime.R$* { *; }

# 键盘视图被 Java 代码直接引用，R8 自动保留；但 onSizeChanged/onMeasure 等
# 系统回调方法签名不得混淆（Android 框架反射调用）
-keepclassmembers class com.cloudwubi.ime.CloudKeyboardView {
    public protected *;
}

# 忽略缺失类告警（各 SDK 版本差异），不阻断构建
-dontwarn **
