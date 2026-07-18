package com.topjohnwu.magisk.ui.component

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.util.Log

/**
 * 把用户从相册选中的图片 Uri 应用为系统壁纸。
 *
 * 渲染策略：按 WallpaperManager 推荐尺寸（兜底 1080x2400）创建目标 bitmap，
 * 用 centerCrop 等比缩放源图填满目标区域，再 setBitmap 写入。
 * 整个过程在 IO 线程调用（解码大图 + setBitmap 均为耗时操作）。
 *
 * 兼容性：API 24+。Android 13+ 走系统 Photo Picker（无需权限），
 * 低版本走 ACTION_PICK（依赖 SET_WALLPAPER 权限，已在 Manifest 声明）。
 */
object WallpaperUtil {

    private const val TAG = "WallpaperUtil"

    /** 壁纸作用范围：主屏 / 锁屏 / 两者 */
    enum class Target(val flags: Int) {
        SYSTEM(WallpaperManager.FLAG_SYSTEM),
        LOCK(WallpaperManager.FLAG_LOCK),
        BOTH(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
    }

    /**
     * 从 [uri] 解码、缩放并设置为壁纸。
     * [target] 指定写到主屏 / 锁屏 / 两者（API 24+ 的 setBitmap(bitmap, visible) 重载）。
     * 返回是否成功。在 IO 线程调用。
     */
    fun applyFromUri(context: Context, uri: Uri, target: Target = Target.BOTH): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(context)
            val targetW = wm.desiredMinimumWidth.takeIf { it > 0 } ?: 1080
            val targetH = wm.desiredMinimumHeight.takeIf { it > 0 } ?: 2400

            val decoded = decodeSampled(context, uri, targetW * 2, targetH * 2)
                ?: run {
                    Log.e(TAG, "decode bitmap failed for $uri")
                    return false
                }

            // centerCrop 到目标尺寸
            val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val srcW = decoded.width.toFloat()
            val srcH = decoded.height.toFloat()
            val scale = maxOf(targetW / srcW, targetH / srcH)
            val scaledW = srcW * scale
            val scaledH = srcH * scale
            val srcRect = Rect(
                ((srcW - scaledW) / 2f).toInt().coerceAtLeast(0),
                ((srcH - scaledH) / 2f).toInt().coerceAtLeast(0),
                ((srcW + scaledW) / 2f).toInt().coerceAtMost(srcW.toInt()),
                ((srcH + scaledH) / 2f).toInt().coerceAtMost(srcH.toInt())
            )
            val dstRect = Rect(0, 0, targetW, targetH)
            canvas.drawBitmap(decoded, srcRect, dstRect, null)

            // setBitmap(bitmap, visible) 指定作用范围：FLAG_SYSTEM=主屏, FLAG_LOCK=锁屏
            wm.setBitmap(out, true, target.flags)
            decoded.recycle()
            out.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "apply wallpaper failed", e)
            false
        }
    }

    /** 两遍解码：先 inJustDecodeBounds 量尺寸算 sampleSize，再真正解码，避免大图 OOM。 */
    private fun decodeSampled(context: Context, uri: Uri, reqW: Int, reqH: Int): Bitmap? {
        val resolver = context.contentResolver

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        opts.inSampleSize = calcSampleSize(opts.outWidth, opts.outHeight, reqW, reqH)
        opts.inJustDecodeBounds = false

        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun calcSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) {
            sample *= 2
        }
        return sample
    }
}
