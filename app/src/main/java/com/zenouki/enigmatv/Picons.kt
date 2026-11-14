package com.zenouki.enigmatv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Tiny picon loader with memory cache (no external libs). */
object Picons {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    var auth: String? = null

    fun load(url: String?, iv: ImageView) {
        iv.tag = url
        if (url.isNullOrBlank() || missing.contains(url)) {
            iv.setImageDrawable(null)
            return
        }
        val hit = cache.get(url)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }
        iv.setImageDrawable(null)
        pool.execute {
            val bmp = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 8000
                auth?.let { c.setRequestProperty("Authorization", it) }
                try {
                    if (c.responseCode == 200) c.inputStream.use { BitmapFactory.decodeStream(it) } else null
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
                null
            }
            main.post {
                if (bmp == null) missing.add(url) else cache.put(url, bmp)
                if (iv.tag == url) iv.setImageBitmap(bmp)
            }
        }
    }

    fun clear() {
        cache.evictAll()
        missing.clear()
    }
}
