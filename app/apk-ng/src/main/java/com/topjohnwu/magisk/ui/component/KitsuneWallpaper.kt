package com.topjohnwu.magisk.ui.component

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.topjohnwu.magisk.core.R

/**
 * 将 wallpaper_kitsune 矢量图渲染为壁纸尺寸 Bitmap 并应用到系统壁纸。
 *
 * 矢量图 viewport 是 1080x2400（手机壁纸比例），这里按 WallpaperManager 推荐的
 * 最小宽高渲染，确保清晰度；再用 setBitmap 写入。整个过程在调用方提供的
 * CoroutineScope 里执行（壁纸渲染/写入是耗时操作，禁止主线程调用）。
 */
object KitsuneWallpaper {

    private const val TAG = "KitsuneWallpaper"

    /** 渲染并设置壁纸。返回是否成功。在 IO 线程调用。 */
    fun apply(context: Context): Boolean {
        return try {
            val drawable = ResourcesCompat.getDrawable(
                context.resources, R.drawable.wallpaper_kitsune, context.theme
            ) ?: run {
                Log.e(TAG, "wallpaper_kitsune drawable not found")
                return false
            }

            val wm = WallpaperManager.getInstance(context)
            // 用 WallpaperManager 推荐尺寸，兜底 1080x2400
            val width = wm.desiredMinimumWidth.takeIf { it > 0 } ?: 1080
            val height = wm.desiredMinimumHeight.takeIf { it > 0 } ?: 2400

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // 背景先填黑，避免矢量图透明区域露出未初始化像素
            canvas.drawColor(Color.BLACK)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            wm.setBitmap(bitmap)
            // 大 bitmap 及时回收，降低内存峰值
            bitmap.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "apply wallpaper failed", e)
            false
        }
    }
}
