package com.mengpolar.ldrlife.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「信笺 · 纸短情长」视觉基调（2026-11-26 用户选定）。
 * 隐喻：一封两人共同书写的信——距离写成文字，状态是信末落款，
 * 「想你了」是盖一枚朱砂印。层级靠字号与留白，不靠卡片。
 */
object InkColors {
    /** 宣纸暖白（页面底色） */
    val Paper = Color(0xFFF7F3EC)

    /** 稍深的纸色（局部衬底） */
    val PaperDeep = Color(0xFFEFEADF)

    /** 墨色（正文） */
    val Ink = Color(0xFF2B2B2B)

    /** 淡墨（次级文字） */
    val InkSoft = Color(0xFF8A8577)

    /** 朱砂（点睛：印章 / 提醒 / 未读） */
    val Cinnabar = Color(0xFFC03F2E)

    /** 朱砂淡染（按压/选中底色） */
    val CinnabarWash = Color(0x14C03F2E)

    /** 细分隔线（一根淡墨线） */
    val Rule = Color(0x332B2B2B)
}

private val LetterScheme = lightColorScheme(
    primary = InkColors.Cinnabar,
    onPrimary = Color(0xFFF7F3EC),
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

/** 信笺字体：标题走宋体气质（系统 Serif，缺字回退黑体），正文系统无衬线 */
object LetterType {
    val Title = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 38.sp,
    )
    val TitleSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    )
    val Body = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
    val Footnote = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    )
}

@Composable
fun LDRLifeTheme(content: @Composable () -> Unit) {
    // 信笺是纸：白天黑夜都是纸（固定浅色宣纸，避免暗色下失味）
    MaterialTheme(
        colorScheme = LetterScheme,
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
