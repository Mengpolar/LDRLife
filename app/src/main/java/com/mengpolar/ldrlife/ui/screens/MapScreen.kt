package com.mengpolar.ldrlife.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.mengpolar.ldrlife.core.Geo
import com.mengpolar.ldrlife.session.Session

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(session: Session, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mapView = remember { MapView(context) }
    var aMap by remember { mutableStateOf<AMap?>(null) }
    val myLoc = session.myLoc.collectAsState().value
    val partnerLoc = session.partnerLoc.collectAsState().value
    val precision = remember { mutableStateOf(session.currentPrecision()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        mapView.onCreate(null)
        aMap = mapView.map
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    // 打开地图页自动上报一次自己的位置（双方各开一次即互相可见，无需手点按钮；
    // LocationProvider 有 10 分钟缓存，频繁进出不会反复定位）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        session.sendMyLocation(precision.value)
    }

    // markers + 连线 + 视角自适应（设计文档 §6.4：fitBounds，缩放上限 16）
    androidx.compose.runtime.LaunchedEffect(myLoc, partnerLoc, aMap) {
        val a = aMap ?: return@LaunchedEffect
        a.clear()
        val builder = LatLngBounds.Builder()
        var any = false
        myLoc?.let {
            a.addMarker(MarkerOptions().position(LatLng(it.lat, it.lon)).title("我的位置"))
            builder.include(LatLng(it.lat, it.lon))
            any = true
        }
        partnerLoc?.let {
            a.addMarker(MarkerOptions().position(LatLng(it.lat, it.lon)).title("TA 的位置"))
            builder.include(LatLng(it.lat, it.lon))
            any = true
        }
        if (myLoc != null && partnerLoc != null) {
            a.addPolyline(
                PolylineOptions()
                    .add(LatLng(myLoc.lat, myLoc.lon), LatLng(partnerLoc.lat, partnerLoc.lon))
                    .width(6f).color(0xE85A71.toInt())
            )
        }
        if (any) {
            try {
                a.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 140))
            } catch (_: Exception) {
                a.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.9042, 116.4074), 4f))
            }
        } else {
            a.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.9042, 116.4074), 4f))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("位置 · 已加密") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                // 顶部信息条：距离 + 双方精度 + 新鲜度
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        val both = myLoc != null && partnerLoc != null
                        Text(
                            if (both) {
                                val d = Geo.haversineKm(myLoc!!.lat, myLoc.lon, partnerLoc!!.lat, partnerLoc.lon)
                                "直线距离约 ${Geo.formatKm(d)}"
                            } else "等待双方位置...",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            buildString {
                                append("我的精度：${precision.value.label}")
                                partnerLoc?.let { append(" ｜ TA：${it.precisionLabel} · ${freshness(it.ts)}") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Geo.Precision.entries.forEach { p ->
                    FilterChip(
                        selected = precision.value == p,
                        onClick = {
                            precision.value = p
                            session.setPrecision(p.label)
                        },
                        label = { Text(p.label) },
                    )
                }
            }
            Button(
                onClick = { session.sendMyLocation(precision.value) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
            ) { Text("刷新我的位置并共享") }
        }
    }
}

private fun freshness(ts: Long): String {
    val mins = (System.currentTimeMillis() - ts) / 60000
    return when {
        mins < 1 -> "刚刚"
        mins < 60 -> "$mins 分钟前"
        else -> "${mins / 60} 小时前"
    }
}
