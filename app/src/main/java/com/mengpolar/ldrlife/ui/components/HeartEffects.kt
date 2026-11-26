package com.mengpolar.ldrlife.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mengpolar.ldrlife.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

/**
 * 触摸爱心特效：每次触摸在触点生成一只手绘爱心，向上飘起、渐隐，随机倾斜角度。
 * 事件不消费，不影响下层任何交互；爱心素材 = 用户提供的「粉色爱心.png」。
 */
private data class HeartAnim(val id: Int, val x: Float, val y: Float, val rot: Float)

@Composable
fun FloatingHeartsLayer(modifier: Modifier = Modifier) {
    val hearts = remember { mutableStateListOf<HeartAnim>() }
    var nextId by remember { mutableIntStateOf(0) }
    var w by remember { mutableIntStateOf(1080) }
    var h by remember { mutableIntStateOf(2000) }

    Box(
        modifier
            .onGloballyPositioned { w = it.size.width; h = it.size.height }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    hearts.add(
                        HeartAnim(
                            id = nextId++,
                            x = down.position.x.coerceIn(44f, (w - 44).toFloat()),
                            y = down.position.y.coerceIn(60f, (h - 60).toFloat()),
                            rot = Random.nextFloat() * 36f - 18f, // ±18° 随机倾斜
                        )
                    )
                    if (hearts.size > 12) hearts.removeAt(0) // 连击防爆量
                }
            },
    ) {
        hearts.forEach { heart ->
            FloatingHeart(heart) { hearts.removeAll { it.id == heart.id } }
        }
    }
}

@Composable
private fun FloatingHeart(heart: HeartAnim, onDone: () -> Unit) {
    val dyAnim = remember(heart.id) { Animatable(0f) }
    val alphaAnim = remember(heart.id) { Animatable(1f) }
    LaunchedEffect(heart.id) {
        launch { dyAnim.animateTo(-420f, tween(1500, easing = FastOutSlowInEasing)) }
        launch { alphaAnim.animateTo(0f, tween(1500)) }
        delay(1500)
        onDone()
    }
    Image(
        painter = painterResource(R.drawable.heart_float),
        contentDescription = null,
        modifier = Modifier
            .offset { IntOffset(heart.x.toInt() - 44, heart.y.toInt() - 60) }
            .size(88.dp)
            .graphicsLayer {
                translationY = dyAnim.value
                alpha = alphaAnim.value
                rotationZ = heart.rot
            },
    )
}
