# Excessive obfuscation
-flattenpackagehierarchy
-allowaccessmodification

# WebUI（移植自 KsuWebUIStandalone）
# 保留所有 @JavascriptInterface 注解方法（WebView 通过方法名从 JS 调用）。
# 默认 proguard-android-optimize.txt 已包含此规则，这里显式声明以防被覆盖。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WebViewInterface / DownloadInterface / FileOutputStreamInterface 的类名
# 虽然不直接被 JS 用（用字符串别名 "ksu" / "ksu_download"），但 Kotlin 编译
# 出来的类有时会被 R8 改写为合成类，导致 addJavascriptInterface 后 JS 调用
# 找不到方法。这里 keep 这三个类与构造函数。
-keep class com.topjohnwu.magisk.webui.WebViewInterface { *; }
-keep class com.topjohnwu.magisk.webui.DownloadInterface { *; }
-keep class com.topjohnwu.magisk.webui.FileOutputStreamInterface { *; }

# RootServices 是 libsu RootService 子类，通过反射在 root 进程实例化。
-keep class com.topjohnwu.magisk.webui.services.RootServices { <init>(); }
