package dev.quietledger.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用图标：从 PackageManager 取已安装应用的图标，进程内缓存。
 *
 * 为什么需要缓存：明细/下钻/日详情动辄几十行，每行每帧都走 Binder 取图标会拖慢滚动，
 * 而且 LazyColumn 的复用会让同一 pkg 反复请求。这里用 LruCache 单例（容量 96，
 * key=pkg）缓存 ImageBitmap；取不到（应用卸载/无权限/空包名）也缓存一个空结果，
 * 避免列表里反复重试、反复闪烁。
 *
 * 视觉约束：图标本身保留形状（不裁圆、不投影），尺寸固定为正方形；
 * 没有图标时画「应用名首字」的 9sp 等宽占位（无 label 时画「税」），
 * 保证图标列宽度不变、应用名左端不跳动，也不再出现会让用户以为漏画的空框。
 *
 * [label] 传应用名（由调用方给）：取不到图标时用它的第一个字当占位，
 * 比统一画「税」更能认出是哪一行，也和 P1-5 的验收一致。
 */
@Composable
fun AppIcon(pkg: String, size: Dp = 18.dp, label: String = "", modifier: Modifier = Modifier) {
    if (pkg.isBlank()) {
        AppIconPlaceholder(size, label, modifier)
        return
    }
    val appContext = LocalContext.current.applicationContext
    // 先同步取缓存：命中时首帧就是图标，不再闪一下占位框。
    // first = 是否已经查过（含查到空结果），second = 图标本身。
    val cached = remember(pkg) { AppIconCache.lookup(pkg) }
    var bitmap by remember(pkg) { mutableStateOf(cached.second) }
    LaunchedEffect(pkg) {
        if (!cached.first) {
            bitmap = withContext(Dispatchers.IO) { AppIconCache.load(appContext, pkg) }
        }
    }
    val image = bitmap
    if (image == null) {
        AppIconPlaceholder(size, label, modifier)
    } else {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * 拿不到图标时的占位：纸底 + 1dp 线框 + 应用名首字（9sp 等宽，墨色偏软）。
 * 宽度与真图标一致（仍由调用方传的 size 决定，票面/明细的 20dp 图标列不变）。
 */
@Composable
private fun AppIconPlaceholder(size: Dp, label: String, modifier: Modifier) {
    val initial = label.trim().firstOrNull()?.toString() ?: "税"
    Box(
        modifier
            .size(size)
            .background(QL.Paper)
            .border(1.dp, QL.Rule),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = QL.mono.copy(fontSize = 9.sp, lineHeight = 9.sp, color = QL.InkSoft),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * 图标缓存：包在 Entry 里，这样「查过但失败」也能缓存（bitmap 为 null），
 * 不会每次组合都重新请求 PackageManager。
 */
private object AppIconCache {
    private class Entry(val bitmap: ImageBitmap?)

    private val cache = LruCache<String, Entry>(96)

    /** 查缓存：first 表示「查过没有」（含查到失败），second 是图标（可能为 null）。 */
    fun lookup(pkg: String): Pair<Boolean, ImageBitmap?> = synchronized(cache) {
        val entry = cache.get(pkg)
        if (entry == null) false to null else true to entry.bitmap
    }

    fun load(context: Context, pkg: String): ImageBitmap? {
        synchronized(cache) { cache.get(pkg) }?.let { return it.bitmap }
        // 取图标 + 转位图整段兜底：卸载残留、个别 ROM 的 Drawable 异常都不该把列表搞崩。
        val bitmap = runCatching {
            drawableToBitmap(context.packageManager.getApplicationIcon(pkg))
        }.getOrNull()
        synchronized(cache) { cache.put(pkg, Entry(bitmap)) }
        return bitmap
    }

    /**
     * Drawable → ImageBitmap：直接按 96px 目标缩放绘制，避免把 192/512px 的原图
     * 整张留在内存里（96 个应用 × 大图会明显占堆）。列表最大显示 20dp，
     * 96px 在 4x 屏上仍然够清楚。
     */
    private fun drawableToBitmap(drawable: Drawable): ImageBitmap? {
        val target = 96
        val bitmap = runCatching {
            Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val canvas = Canvas(bitmap)
        val intrinsicW = drawable.intrinsicWidth
        val intrinsicH = drawable.intrinsicHeight
        if (intrinsicW <= 0 || intrinsicH <= 0) {
            // 少数 Drawable 没有固有尺寸：铺满目标框即可，别画到画布外。
            drawable.setBounds(0, 0, target, target)
        } else {
            val scale = target.toFloat() / maxOf(intrinsicW, intrinsicH)
            val w = (intrinsicW * scale).toInt().coerceAtLeast(1)
            val h = (intrinsicH * scale).toInt().coerceAtLeast(1)
            val left = (target - w) / 2
            val top = (target - h) / 2
            drawable.setBounds(left, top, left + w, top + h)
        }
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }
}
