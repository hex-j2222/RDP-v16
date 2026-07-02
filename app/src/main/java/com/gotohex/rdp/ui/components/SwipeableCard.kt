package com.gotohex.rdp.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.gotohex.rdp.R
import com.gotohex.rdp.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.*

// ── Swipe state ───────────────────────────────────────────────────────────────
private enum class SwipeAction { NONE, DELETE, EDIT }

/**
 * Professional swipeable card with animated action reveals:
 *   • Swipe RIGHT → DELETE  (danger red  + trash icon + label)
 *   • Swipe LEFT  → EDIT    (cyan blue   + edit icon  + label)
 *
 * Features:
 * - Spring-physics return animation
 * - Icon + ring scale/glow as drag progresses  (0→1 over first 50% threshold)
 * - Haptic at threshold crossing
 * - Rubber-band effect past 100% threshold
 * - Smooth dismiss slide-out when action triggered
 */
@Composable
fun SwipeableProfileCard(
    onDelete:      () -> Unit,
    onEdit:        () -> Unit,
    modifier:      Modifier = Modifier,
    hapticEnabled: Boolean = true,   // FIX #1: إعداد hapticFeedback لم يكن يُفحص
    // BUGFIX-UI-1: onDelete/onEdit فقط يفتحان Dialog تأكيد/تعديل خارجي — لا يحذفان
    // العنصر فوراً. إن أُلغي الـ Dialog يبقى الكرت مخفياً للأبد لأن `dismissed`
    // حالة داخلية لا تعرف أن الإجراء أُلغي. `actionPending` يمرره المستدعي ليعكس
    // ما إذا كان هناك Dialog مفتوح مرتبط بهذا الكرت تحديداً؛ عند إغلاقه دون أن
    // يختفي الكرت فعلياً من القائمة (أي لم يُحذف)، نعيد إظهاره.
    actionPending: Boolean = false,
    content:       @Composable () -> Unit,
) {
    val density  = LocalDensity.current
    val haptics  = LocalHapticFeedback.current
    val sound    = LocalSoundManager.current
    val scope    = rememberCoroutineScope()

    // ── Drag offset (px) ─────────────────────────────────────────────────────
    // FIX #2: استخدام mutableStateOf لتتبع موضع السحب الفوري بدلاً من Animatable،
    //         لتجنب تنافس coroutines على mutex داخل Animatable عند كل بكسل حركة.
    //         Animatable يُستخدم فقط للانيميشن عند انتهاء السحب (spring / slide-off).
    val offsetPx        = remember { Animatable(0f) }
    var dragOffsetPx    by remember { mutableStateOf(0f) }   // ← تحديث فوري بدون coroutine
    var isDragging      by remember { mutableStateOf(false) }
    var thresholdHit    by remember { mutableStateOf(false) }
    var dismissed       by remember { mutableStateOf(false) }

    // Threshold = 140 dp
    val thresholdPx  = with(density) { 140.dp.toPx() }
    // Max drag without dismiss  = 200dp (rubber band after threshold)
    val maxDragPx    = with(density) { 220.dp.toPx() }

    // ── Unified display offset: raw state during drag, Animatable during animation ──
    val displayOffset = if (isDragging) dragOffsetPx else offsetPx.value

    // ── Derive visual quantities ──────────────────────────────────────────────
    val dragFraction  = (abs(displayOffset) / thresholdPx).coerceIn(0f, 1.6f)
    val action        = when {
        displayOffset >  8f -> SwipeAction.DELETE
        displayOffset < -8f -> SwipeAction.EDIT
        else                 -> SwipeAction.NONE
    }
    val iconProgress  = (dragFraction).coerceIn(0f, 1f)          // 0→1 over threshold
    val overThreshold = abs(displayOffset) >= thresholdPx

    // Haptic once per threshold crossing
    LaunchedEffect(overThreshold, action) {
        if (overThreshold && action != SwipeAction.NONE && !thresholdHit) {
            // FIX #1: فحص hapticEnabled قبل تنفيذ haptic feedback
            if (hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TOGGLE, 0.4f)
            thresholdHit = true
        } else if (!overThreshold) {
            thresholdHit = false
        }
    }

    // BUGFIX-UI-1: عندما يُغلق الـ Dialog المرتبط (حذف أو تعديل) دون أن يختفي
    // الكرت فعلياً — أي أن actionPending رجعت false بينما الكرت لا يزال حياً في
    // الشجرة — فهذا يعني أن المستخدم ألغى العملية. نُعيد الكرت لوضعه الطبيعي
    // بدلاً من تركه مخفياً للأبد.
    LaunchedEffect(actionPending) {
        if (!actionPending && dismissed) {
            dismissed = false
            offsetPx.snapTo(0f)
            dragOffsetPx = 0f
        }
    }

    // BUGFIX (swipe direction reversed in Arabic/RTL): the card's drag offset
    // is tracked in raw, absolute screen pixels (a right-swipe is always a
    // positive offset, regardless of locale), but Alignment.CenterStart /
    // CenterEnd used below to place the DELETE / EDIT icon+label are
    // direction-aware and get mirrored under the app's Arabic (RTL) layout
    // direction. That mismatch made the reveal icon appear on the opposite
    // side of the screen from where the card actually opened, i.e. a
    // right-swipe visually looked like a left-swipe. Forcing LTR for just
    // this drag mechanics subtree makes "Start"/"End" resolve to absolute
    // left/right, matching the absolute drag direction. The card's own
    // content() is explicitly restored to the app's real layout direction
    // just below so Arabic text/icons inside the card are unaffected.
    val appLayoutDirection = LocalLayoutDirection.current

    AnimatedVisibility(
        visible = !dismissed,
        exit    = androidx.compose.animation.shrinkVertically(
            tween(280, easing = FastOutSlowInEasing)
        ) + androidx.compose.animation.fadeOut(tween(200)),
    ) {
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier.fillMaxWidth()
        ) {
            // ── DELETE Background (right-swipe) ───────────────────────────────
            // BUGFIX: previously BOTH backgrounds were always composed with the
            // *same* shared `iconProgress`, gated only by an unused `visible`
            // flag that never actually zeroed the background alpha. That made
            // the blue EDIT gradient bleed underneath/over the red DELETE
            // gradient on every right-swipe (and vice-versa on left-swipe),
            // washing the red out to a muddy blue and hiding the delete icon.
            // Now each background is only composed at all while its own
            // action is the active one, so the inactive side is never drawn.
            if (action == SwipeAction.DELETE) {
                ActionBackground(
                    visible  = true,
                    action   = SwipeAction.DELETE,
                    progress = iconProgress,
                    modifier = Modifier.matchParentSize()
                )
            }

            // ── EDIT Background (left-swipe) ──────────────────────────────────
            if (action == SwipeAction.EDIT) {
                ActionBackground(
                    visible  = true,
                    action   = SwipeAction.EDIT,
                    progress = iconProgress,
                    modifier = Modifier.matchParentSize()
                )
            }

            // ── Card (draggable layer) ─────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // FIX #2: استخدام displayOffset بدل offsetPx.value وحده
                    .offset { IntOffset(displayOffset.roundToInt(), 0) }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart  = {
                                // مزامنة dragOffsetPx مع القيمة الحالية عند بدء السحب
                                dragOffsetPx = offsetPx.value
                                isDragging = true
                            },
                            onDragEnd    = {
                                isDragging = false
                                scope.launch {
                                    // مزامنة Animatable مع الموضع الفعلي قبل الانيميشن
                                    offsetPx.snapTo(dragOffsetPx)
                                    if (abs(dragOffsetPx) >= thresholdPx) {
                                        // Slide fully off screen then trigger
                                        val dir = if (dragOffsetPx > 0) 1f else -1f
                                        offsetPx.animateTo(
                                            dir * size.width.toFloat() * 1.1f,
                                            spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)
                                        )
                                        dismissed = true
                                        if (dir > 0) onDelete() else onEdit()
                                    } else {
                                        // Snap back with spring
                                        offsetPx.animateTo(
                                            0f,
                                            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                scope.launch {
                                    offsetPx.snapTo(dragOffsetPx)
                                    offsetPx.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                                }
                            },
                            // FIX #2: تحديث مباشر لـ mutableStateOf — بدون scope.launch، بدون mutex
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val target = dragOffsetPx + dragAmount
                                // Rubber band past max
                                dragOffsetPx = when {
                                    target > maxDragPx  -> maxDragPx + (target - maxDragPx) * 0.18f
                                    target < -maxDragPx -> -maxDragPx + (target + maxDragPx) * 0.18f
                                    else                -> target
                                }
                            }
                        )
                    }
            ) {
                // Restore the app's real (possibly RTL/Arabic) layout direction
                // for the card's own content — only the swipe mechanics above
                // needed to be pinned to LTR/absolute coordinates.
                CompositionLocalProvider(LocalLayoutDirection provides appLayoutDirection) {
                    content()
                }
            }
        }
      }
    }
}

// ── Action Background ─────────────────────────────────────────────────────────
@Composable
private fun ActionBackground(
    visible:  Boolean,
    action:   SwipeAction,
    progress: Float,        // 0f → 1f+ as user drags to threshold
    modifier: Modifier = Modifier
) {
    // Colors
    val (bgStart, bgEnd, iconTint, label, icon) = when (action) {
        SwipeAction.DELETE -> SwipeColors(
            bg1   = Color(0xFFB71C1C),
            bg2   = Color(0xFFFF2D78),
            tint  = Color.White,
            label = stringResource(R.string.delete),
            icon  = Icons.Outlined.Delete
        )
        SwipeAction.EDIT -> SwipeColors(
            bg1   = Color(0xFF0D47A1),
            bg2   = Color(0xFF00E5FF),
            tint  = Color.White,
            label = stringResource(R.string.edit),
            icon  = Icons.Outlined.Edit
        )
        SwipeAction.NONE -> return  // nothing to draw
    }

    // Icon scale: 0.4→1.15 over progress 0→1, then bounce back to 1.0
    val iconScale by animateFloatAsState(
        targetValue   = if (!visible) 0.3f else
            if (progress < 1f) lerp(0.4f, 1.15f, progress)
            else lerp(1.15f, 1.0f, (progress - 1f).coerceIn(0f, 0.6f)),
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label         = "icon_scale"
    )

    // Glow pulse when over threshold
    val infiniteT = rememberInfiniteTransition(label = "glow_pulse")
    val glowPulse by infiniteT.animateFloat(
        initialValue  = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "glow"
    )
    val showGlow = progress >= 1f

    // Label opacity: fades in from 60% drag
    val labelAlpha = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)
    // Background alpha: 0 → 1 over first 30% drag
    val bgAlpha    = (progress / 0.3f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                brush = when (action) {
                    SwipeAction.DELETE -> Brush.horizontalGradient(
                        listOf(bgStart.copy(bgAlpha), bgEnd.copy(bgAlpha * 0.8f))
                    )
                    SwipeAction.EDIT   -> Brush.horizontalGradient(
                        listOf(bgEnd.copy(bgAlpha * 0.8f), bgStart.copy(bgAlpha))
                    )
                    else               -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                }
            )
    ) {
        // Action indicator — icon + label + orbit ring
        Column(
            modifier = Modifier
                .align(
                    if (action == SwipeAction.DELETE) Alignment.CenterStart
                    else Alignment.CenterEnd
                )
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Orbit ring + icon cluster
            Box(contentAlignment = Alignment.Center) {
                // Outer glow ring
                Canvas(modifier = Modifier.size(64.dp)) {
                    if (showGlow) {
                        drawCircle(
                            color  = iconTint.copy(alpha = 0.18f * glowPulse),
                            radius = size.minDimension / 2 * 1.55f
                        )
                        drawCircle(
                            color  = iconTint.copy(alpha = 0.09f * glowPulse),
                            radius = size.minDimension / 2 * 2.2f
                        )
                    }
                    // Orbit ring (dashed arc)
                    drawArc(
                        color      = iconTint.copy(alpha = (iconScale - 0.3f).coerceIn(0f, 0.6f)),
                        startAngle = -90f,
                        sweepAngle = 300f * progress.coerceIn(0f, 1f),
                        useCenter  = false,
                        style      = Stroke(
                            width       = 2f,
                            pathEffect  = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(8f, 6f), 0f
                            )
                        )
                    )
                }
                // Icon circle
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .scale(iconScale)
                        .background(iconTint.copy(alpha = 0.18f), CircleShape)
                        .border(1.5.dp, iconTint.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(24.dp))
                }
            }

            // Label fade-in
            if (labelAlpha > 0.01f) {
                Spacer(Modifier.height(6.dp))
                Text(
                    label,
                    color      = iconTint.copy(alpha = labelAlpha),
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Helper data class ─────────────────────────────────────────────────────────
private data class SwipeColors(
    val bg1:   Color,
    val bg2:   Color,
    val tint:  Color,
    val label: String,
    val icon:  androidx.compose.ui.graphics.vector.ImageVector,
)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
