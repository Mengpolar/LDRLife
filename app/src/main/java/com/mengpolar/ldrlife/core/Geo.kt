package com.mengpolar.ldrlife.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.abs

/**
 * 位置工具：精度分级、Haversine 距离、WGS-84→GCJ-02 转换（设计文档 §6）。
 * 坐标全链路 GCJ-02（高德体系），系统定位给出 WGS-84，显示前转换。
 */
object Geo {

    enum class Precision(val label: String) {
        EXACT("精确 ~100m"),
        FUZZY("模糊 ±1.1km"),
        CITY("城市 ±11km"),
    }

    fun fromLabel(label: String): Precision =
        Precision.entries.firstOrNull { it.label == label } ?: Precision.FUZZY

    /** 网格对齐降精度：数据不撒谎，只降分辨率（EXACT 不动，FUZZY≈1.1km 网格，CITY≈11km 网格） */
    fun applyPrecision(lat: Double, lon: Double, p: Precision): Pair<Double, Double> = when (p) {
        Precision.EXACT -> lat to lon
        Precision.FUZZY -> (Math.floor(lat * 100) / 100) to (Math.floor(lon * 100) / 100)
        Precision.CITY -> (Math.floor(lat * 10) / 10) to (Math.floor(lon * 10) / 10)
    }

    /** Haversine 直线距离（公里） */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    fun formatKm(km: Double): String = when {
        km < 1.0 -> "${(km * 1000).toInt()} m"
        km < 100.0 -> String.format("%.1f km", km)
        else -> "${km.toInt()} km"
    }

    // ---------- WGS-84 → GCJ-02（国测局公开偏移算法） ----------

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
