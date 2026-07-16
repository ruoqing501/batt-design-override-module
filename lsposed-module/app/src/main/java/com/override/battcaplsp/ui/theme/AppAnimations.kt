package com.override.battcaplsp.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

/**
 * 应用统一动画规范
 * 时长：Fast=150ms, Default=300ms, Slow=500ms
 * 缓动：Material 标准 cubic-bezier(0.4, 0.0, 0.2, 1)
 */
object AppAnimations {
    const val Fast = 150
    const val Default = 300
    const val Slow = 500

    val AppEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    fun <T> appTween(duration: Int = Default) = tween<T>(
        durationMillis = duration,
        easing = AppEasing
    )

    /** 淡入 */
    fun fadeIn(): EnterTransition = fadeIn(appTween())

    /** 淡出 */
    fun fadeOut(): ExitTransition = fadeOut(appTween())

    /** 从底部滑入 */
    fun slideInBottom(): EnterTransition = slideInVertically(
        initialOffsetY = { it / 4 },
        animationSpec = appTween()
    )

    /** 向底部滑出 */
    fun slideOutBottom(): ExitTransition = slideOutVertically(
        targetOffsetY = { it / 4 },
        animationSpec = appTween()
    )

    /** 缩放进入 */
    fun scaleIn(): EnterTransition = scaleIn(
        initialScale = 0.92f,
        animationSpec = appTween()
    )

    /** 水平滑入（用于页面左右切换） */
    fun slideInForward(): EnterTransition = slideInHorizontally(
        initialOffsetX = { it / 5 },
        animationSpec = appTween()
    )

    /** 水平滑出 */
    fun slideOutBack(): ExitTransition = slideOutHorizontally(
        targetOffsetX = { -it / 5 },
        animationSpec = appTween()
    )

    /** 卡片/列表项出现组合动画 */
    fun itemEnter(): EnterTransition = fadeIn(appTween(Default)) + slideInBottom()

    /** 内容切换：淡入淡出 + 轻微缩放 */
    fun contentEnter(): EnterTransition = fadeIn(appTween(Default)) + scaleIn()

    /** 内容退出 */
    fun contentExit(): ExitTransition = fadeOut(appTween(Default))
}
