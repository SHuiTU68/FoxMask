package com.topjohnwu.magisk.core

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.topjohnwu.magisk.StubApk
import com.topjohnwu.magisk.core.utils.RootUtils
import org.lsposed.hiddenapibypass.HiddenApiBypass

open class App() : Application() {

    companion object {
        /**
         * 通过反射调用 ApplicationInfo.setEnableOnBackInvokedCallback(hidden API, API 34+)
         * 来运行时开启/关闭预测性返回手势。移植自 KernelSU。
         *
         * 不在 AndroidManifest 里静态声明 android:enableOnBackInvokedCallback，
         * 因为静态声明后无法在运行时切换；这里走反射让用户在设置里动态控制。
         */
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val method = ApplicationInfo::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }

    constructor(o: Any) : this() {
        val data = StubApk.Data(o)
        // Add the root service name mapping
        data.classToComponent[RootUtils::class.java.name] = data.rootService.name
        // Send back the actual root service class
        data.rootService = RootUtils::class.java
        Info.stub = data
    }

    override fun attachBaseContext(context: Context) {
        if (context is Application) {
            AppContext.attachApplication(context)
        } else {
            super.attachBaseContext(context)
            AppContext.attachApplication(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 预测性返回手势: 仅 Android 14+ (API 34) 支持。
        // 用 HiddenApiBypass 解除 hidden API 访问限制，然后反射调用
        // ApplicationInfo.setEnableOnBackInvokedCallback 应用用户偏好。
        // 默认关闭（Config.predictiveBack=false），用户在设置里手动开启。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val enable = Config.predictiveBack
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"
            )
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }
    }
}
