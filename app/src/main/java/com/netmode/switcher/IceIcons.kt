package com.netmode.switcher

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The freezer's app icons, drawn once off the main thread into bitmaps of one size. A late icon
 * then never changes its view's measured size, so it cannot lay the scrolling list out again.
 */
internal class IceIcons(private val activity: AppCompatActivity) {
    private val px = (IceGrid.Size.entries.maxOf { it.icon } * activity.resources.displayMetrics.density).toInt()
    private val bitmaps = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val loading = HashMap<String, Deferred<Bitmap>>()
    private val io = Dispatchers.IO.limitedParallelism(2)
    private var preloading: Job? = null

    /** Keeps the slot's size while its icon is on the way. */
    private val blank = object : ColorDrawable(Color.TRANSPARENT) {
        override fun getIntrinsicWidth() = px
        override fun getIntrinsicHeight() = px
    }

    fun bind(target: ImageView, key: String, frozen: Boolean = false) {
        target.tag = key
        val ready = bitmaps.get(key)
        if (ready != null) {
            target.setImageDrawable(drawable(ready, frozen))
            return
        }
        target.setImageDrawable(blank)
        val work = load(key)
        activity.lifecycleScope.launch {
            val bitmap = work.await()
            if (target.tag == key) target.setImageDrawable(drawable(bitmap, frozen))
        }
    }

    /** Draws the list's icons ahead of the scroll, the first ones first. */
    fun preload(keys: List<String>) {
        preloading?.cancel()
        preloading = activity.lifecycleScope.launch {
            keys.take(PRELOAD).forEach { key -> if (bitmaps.get(key) == null) load(key).await() }
        }
    }

    private fun load(key: String): Deferred<Bitmap> = loading.getOrPut(key) {
        activity.lifecycleScope.async {
            val bitmap = withContext(io) {
                val pm = activity.packageManager
                val icon = runCatching { Freezer.info(pm, key)?.loadIcon(pm) }.getOrNull() ?: pm.defaultActivityIcon
                icon.toBitmap(px, px).also { it.prepareToDraw() }
            }
            bitmaps.put(key, bitmap)
            loading.remove(key)
            bitmap
        }
    }

    private fun drawable(bitmap: Bitmap, frozen: Boolean): Drawable =
        IceShortcuts.greyed(BitmapDrawable(activity.resources, bitmap), frozen)

    private companion object {
        const val CACHE_BYTES = 32 * 1024 * 1024
        const val PRELOAD = 150
    }
}
