package com.ahmedgamil.wakili.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Text styles ported from public/app.css (1 CSS px = 1 sp). The web base font
 * is 15px/1.55; the named roles below map each recurring CSS size to a
 * Material role so screens never hardcode a font size.
 */
val WakiliTypography = Typography(
    // .messages:empty::before greeting — 26px/600, -.01em
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.01).em,
    ),
    // .lg-title — 20px/700
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    // .perm-head / card titles — 16px/600
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    // base body — 15px/1.55
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    // .s-title / .dd-opt / .ep-label — 14px
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    // .side-files-btn / .slash-desc / captions — 13px
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    // button labels / .ep-label — 14px/600
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // .group-head / .ft-group-title — 12px/700, .04em (uppercase via SectionLabel)
    labelMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.04.em,
    ),
    // .appr-label / .files-sec-title — 11px/700, .04em
    labelSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.04.em,
    ),
)

/** Monospace styles — ui-monospace in app.css. */
object WakiliMono {
    // .term-out — 13px mono
    val Body = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.5.sp,
    )

    // .tool / .diff pre / .perm-body — 12.5px mono
    val Small = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        lineHeight = 18.75.sp,
    )

    // .ep-host / .term-cwd / .fp-crumb — 12px mono
    val Label = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
}

/** .think-body / .msg.assistant .think — 13.5px italic. */
val ThinkTextStyle = TextStyle(
    fontSize = 13.5.sp,
    lineHeight = 20.sp,
    fontStyle = FontStyle.Italic,
)
