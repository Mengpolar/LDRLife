package com.mengpolar.ldrlife.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mengpolar.ldrlife.R

/**
 * 「甜甜恋爱」视觉基调（2026-11-27 用户选定：粉色调 + 可爱字体 + 爱心动效）。
 * 排版简洁干净：层级靠字号与留白，不靠卡片堆叠。
 * 注意：InkColors/LetterType 沿用旧对象名以减少改动面，语义已切换为粉色系。
 */
object InkColors {
    /** 浅粉白（页面底色） */
    val Paper = Color(0xFFFFF5F7)

    /** 淡粉（局部衬底） */
    val PaperDeep = Color(0xFFFFE3EB)

    /** 暖深灰（正文） */
    val Ink = Color(0xFF4A3A3C)

    /** 粉灰（次级文字） */
    val InkSoft = Color(0xFFB89AA2)

    /** 爱心粉（主强调色） */
    val Cinnabar = Color(0xFFF25D8A)

    /** 爱心粉淡染 */
    val CinnabarWash = Color(0x14F25D8A)

    /** 细分隔线（淡粉灰） */
    val Rule = Color(0x334A3A3C)
}

/** 站酷快乐体（免费商用）：全 App 统一可爱字 */
val SweetFont = FontFamily(Font(R.font.zcool_kuaile))

private val SweetScheme = lightColorScheme(
    primary = InkColors.Cinnabar,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = InkColors.CinnabarWash,
    onPrimaryContainer = InkColors.Cinnabar,
    secondary = InkColors.Ink,
    onSecondary = InkColors.Paper,
    background = InkColors.Paper,
    onBackground = InkColors.Ink,
    surface = InkColors.Paper,
    onSurface = InkColors.Ink,
    surfaceVariant = InkColors.PaperDeep,
    onSurfaceVariant = InkColors.InkSoft,
    outline = InkColors.InkSoft,
    error = InkColors.Cinnabar,
)

object LetterType {
    val Title = TextStyle(
        fontFamily = SweetFont,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 38.sp,
    )
    val TitleSmall = TextStyle(
        fontFamily = SweetFont,
        fontWeight = FontWeight.Normal,
        fontSize = 19.sp,
        lineHeight = 26.sp,
    )
    val Body = TextStyle(
        fontFamily = SweetFont,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
    val Footnote = TextStyle(
        fontFamily = SweetFont,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
}

@Composable
fun LDRLifeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SweetScheme,
        typography = MaterialTheme.typography.copy(
            titleLarge = LetterType.Title,
            titleMedium = LetterType.TitleSmall,
            bodyMedium = LetterType.Body,
            bodySmall = LetterType.Footnote,
            labelMedium = LetterType.Footnote,
            labelSmall = LetterType.Footnote,
        ),
        content = content,
    )
}
