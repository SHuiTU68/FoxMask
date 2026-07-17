plugins {
    alias(libs.plugins.android.application)
    kotlin("plugin.parcelize")
    alias(libs.plugins.compose.compiler)
}

setupMainApk()

android {
    buildFeatures {
        compose = true
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

    // Miuix UI library (0.9.3 拆分模块)
    // 注: 不引入 miuix-blur，因其 minSdk=33 与本工程 minSdk=23 冲突；
    // 本工程不使用毛玻璃特性，无需 miuix-blur 的 MiuiBlurHost/BackStop 等高版本 API。
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)

    // Splash screen
    implementation(libs.core.splashscreen)

    // Navigation3
    implementation(libs.navigation3.runtime)
    implementation(libs.navigationevent.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.ui)
}
