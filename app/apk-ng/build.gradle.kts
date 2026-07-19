plugins {
    alias(libs.plugins.android.application)
    kotlin("plugin.parcelize")
    alias(libs.plugins.compose.compiler)
}

setupMainApk()

android {
    buildFeatures {
        compose = true
        // WebUI 移植自 KsuWebUIStandalone：
        // aidl 用于 IKsuWebuiStandaloneInterface（跨用户取已安装包列表）。
        // BuildConfig.DEBUG 仍走 core 模块的 BuildConfig（与 apk-ng 既有用法一致）。
        aidl = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            excludes += "lib/*/libandroidx.graphics.path.so"
            // kptools 是已 stripped 的第三方 ARM64 ELF，AGP 默认 strip 会报
            // "section table goes past the end of file"，排除不再 strip。
            doNotStrip += "**/libkptools.so"
        }
    }

    defaultConfig {
        proguardFile("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring(libs.jdk.libs)

    // Compose
    implementation(libs.compose.ui)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.material3)

    // MaterialKolor: 从种子色生成 Material3 ColorScheme（Monet 自定义种子色模式用）
    implementation(libs.materialKolor)

    // miuix-blur: 悬浮底栏液态玻璃核心库（移植自 KernelSU miuix 主题）。
    // 仅 blur 模块，提供 Backdrop/drawBackdrop/lens 折射等 API。
    // Android 12+ RenderEffect 真模糊，13+ RuntimeShader 折射/色散，低版本自动降级。
    implementation(libs.miuix.blur)

    // HiddenApiBypass: 预测性返回手势开关需反射调用 hidden API（移植自 KernelSU）
    implementation(libs.hiddenapibypass)

    // Splash screen
    implementation(libs.core.splashscreen)

    // Navigation3
    implementation(libs.navigation3.runtime)
    implementation(libs.navigationevent.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.ui)

    // WebUI（移植自 KsuWebUIStandalone）
    implementation(libs.androidx.webkit)
    implementation(libs.rikka.parcelablelist)
    // Material Components (View 体系): WebUI 的 MaterialAlertDialogBuilder /
    // CircularProgressIndicator 与 Theme.Material3 主题需要此库。
    // FoxMask 主界面走 Compose Material3，不依赖此库；仅 WebUIActivity 需要。
    implementation(libs.material)
    // appcompat: WebViewHelper 用到 androidx.appcompat.R.dimen.abc_dialog_padding_material
    //（material 库会传递依赖 appcompat，这里显式声明以保证编译期可见）。
    implementation(libs.appcompat)
    // core-ktx: ViewCompat / WindowInsetsCompat / PackageInfoCompat / toUri 等扩展。
    implementation(libs.core.ktx)
}
