package com.gotohex.rdp.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry

// ─────────────────────────────────────────────────────────────────────────────
//  SPACE MOTION — shared "hyperspace jump" transition used everywhere a new
//  screen or full-screen surface opens (Settings + all of its sections,
//  Connection History, Sessions, the Add/Edit Connection form, …).
//
//  UI-FIX (transitions): the previous NavHost transitions were a plain
//  horizontal slide — the generic default every Android app uses, and it
//  didn't read as intentional for an RDP/space-themed client. This replaces
//  it everywhere with one consistent motion language: incoming screens
//  scale up from slightly-small + drift in from below + fade in (like
//  pulling a new console panel into focus), while outgoing screens scale
//  down and fade (receding into the distance) instead of just sliding off.
//  Applied uniformly via NavHost's transition lambdas AND the full-screen
//  Dialog used by the "Add/Edit Connection" form, so every "open a new
//  surface" moment in the app now shares the same feel.
// ─────────────────────────────────────────────────────────────────────────────
object SpaceMotion {

    /** Fast start, long soft landing — used when something is arriving. */
    val WarpIn: CubicBezierEasing = CubicBezierEasing(0.16f, 0.78f, 0.14f, 1f)

    /** Soft start, fast departure — used when something is leaving. */
    val WarpOut: CubicBezierEasing = CubicBezierEasing(0.3f, 0f, 0.78f, 0.15f)

    const val DURATION_ENTER = 420
    const val DURATION_EXIT = 260

    // ── NavHost transitions (screen ↔ screen) ──────────────────────────────
    val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        scaleIn(
            initialScale = 0.94f,
            animationSpec = tween(DURATION_ENTER, easing = WarpIn)
        ) + slideInVertically(
            animationSpec = tween(DURATION_ENTER, easing = WarpIn)
        ) { fullHeight -> fullHeight / 14 } + fadeIn(
            animationSpec = tween(DURATION_ENTER, easing = LinearOutSlowInEasing)
        )
    }

    val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        scaleOut(
            targetScale = 1.05f,
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        ) + fadeOut(animationSpec = tween(DURATION_EXIT, easing = WarpOut))
    }

    val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        scaleIn(
            initialScale = 1.05f,
            animationSpec = tween(DURATION_ENTER, easing = WarpIn)
        ) + fadeIn(animationSpec = tween(DURATION_ENTER, easing = LinearOutSlowInEasing))
    }

    val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        scaleOut(
            targetScale = 0.94f,
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        ) + slideOutVertically(
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        ) { fullHeight -> fullHeight / 14 } + fadeOut(
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        )
    }

    // ── Full-screen Dialog transitions (Add/Edit Connection form) ─────────
    val dialogEnter =
        scaleIn(
            initialScale = 0.90f,
            animationSpec = tween(DURATION_ENTER, easing = WarpIn)
        ) + slideInVertically(
            animationSpec = tween(DURATION_ENTER, easing = WarpIn)
        ) { fullHeight -> fullHeight / 10 } + fadeIn(
            animationSpec = tween(DURATION_ENTER, easing = LinearOutSlowInEasing)
        )

    val dialogExit =
        scaleOut(
            targetScale = 0.92f,
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        ) + slideOutVertically(
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        ) { fullHeight -> fullHeight / 10 } + fadeOut(
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        )

    /** Matches [dialogExit]'s duration so callers can delay real dismissal
     *  until the animation has actually finished playing. */
    val DIALOG_EXIT_MS: Long = DURATION_EXIT.toLong()
}
