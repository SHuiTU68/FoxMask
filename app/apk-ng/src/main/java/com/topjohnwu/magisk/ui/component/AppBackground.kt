package com.topjohnwu.magisk.ui.component

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 应用内背景壁纸：从用户选中的图片 Uri 解码为 ImageBitmap，供 MagiskTheme 绘制在内容之下。
 *
 * 持久化：选图后用 takePersistableUriPermission 永久持有读权限，重启后仍可访问。
 * 解码在 IO 线程执行（大图解码耗时，禁止主线程调用）。
 *
 * 兼容性：ImageDecoder 需 API 28+；低于 28 回退 MediaStore.Images.Media.getBitmap。
 */
object AppBackground {

    private const val TAG = "AppBackground"

    /**
     * 永久持有对 [uri] 的读权限，使重启后仍能访问。
     * 仅对通过 ACTION_OPEN_DOCUMENT / Photo Picker 返回的 content Uri 有效。
     */
    fun takePersistablePermission(context: Context, uri: Uri) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure { Log.w(TAG, "take persistable permission failed", it) }
    }

    /**
     * 释放对 [uri] 的持久读权限（取消背景时调用）。
     */
    fun releasePersistablePermission(context: Context, uri: Uri) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        }.onFailure { Log.w(TAG, "release persistable permission failed", it) }
    }

    /**
     * 从 [uri] 解码为 ImageBitmap。在 IO 线程调用。
     * 失败返回 null。
     */
    fun decode(context: Context, uri: Uri): ImageBitmap? {
        return try {
            val resolver = context.contentResolver
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(resolver, uri)
                ImageDecoder.decodeBitmap(source) { d, _, _ ->
                    // 用 ARGB_8888 保证有 alpha 通道，可被主题叠加蒙层
                    d.setMutableRequired(true)
                    d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(resolver, uri)
            }
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            Log.e(TAG, "decode background failed", e)
            null
        }
    }
}
