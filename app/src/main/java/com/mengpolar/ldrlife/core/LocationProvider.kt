package com.mengpolar.ldrlife.core

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * 定位封装：系统 LocationManager（WGS-84）。
 * 不依赖高德定位 Key；调用方拿到坐标后做 WGS→GCJ 转换与精度处理。
 * 优先 GPS → 网络 → 被动，先取缓存位置，无缓存则请求单次定位。
 */
object LocationProvider {

    /** 返回 true 表示已发起（缓存命中同步回调 / 否则异步回调）；false 表示无定位源 */
    fun requestSingleFix(context: Context, callback: (lat: Double, lon: Double) -> Unit): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        val enabled = { p: String -> try { lm.isProviderEnabled(p) } catch (_: Exception) { false } }
        val order = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var delivered = false
        val deliver = { loc: Location ->
            if (!delivered) {
                delivered = true
                callback(loc.latitude, loc.longitude)
            }
        }
        // 1. 任一缓存位置直接用
        for (p in order) {
            if (!enabled(p)) continue
            val last = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
            if (last != null && System.currentTimeMillis() - last.time < 10 * 60 * 1000) {
                deliver(last)
                return true
            }
        }
        // 2. 无新鲜缓存：挑可用 provider 请求单次定位（10s 超时自动放弃）
        val provider = order.firstOrNull { enabled(it) } ?: return false
        val listener = LocationListener { loc -> deliver(loc) }
        try {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                lm.removeUpdates(listener)
            }, 10_000)
            return true
        } catch (_: Exception) {
            return false
        }
    }
}
