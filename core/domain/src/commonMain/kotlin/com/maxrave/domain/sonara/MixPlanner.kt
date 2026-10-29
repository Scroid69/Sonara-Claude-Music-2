package com.maxrave.domain.sonara

import kotlin.math.max
import kotlin.math.min

/** What the player knows when the user presses Next. */
data class NextPressContext(
    /** Mix-on-Next setting. */
    val mixOnNext: Boolean,
    /** Auto-mix master switch. Mix-on-Next is a part of auto-mix, so it does nothing without it. */
    val autoMixEnabled: Boolean,
    val isPlaying: Boolean,
    val hasNext: Boolean,
    val isAlreadyMixing: Boolean,
    val currentIsVideoOrLive: Boolean,
    val nextIsVideoOrLive: Boolean,
    val currentPositionMs: Long,
    /** Duration of the current track, or <= 0 when unknown. */
    val currentDurationMs: Long,
    /** Duration of the incoming track, or <= 0 when not known yet. */
    val nextDurationMs: Long,
    /** The user's chosen mix length, already resolved from "Auto". */
    val configuredDurationMs: Long,
    /** Milliseconds since the previous Next press, or null when there was none recently. */
    val msSinceLastNextPress: Long?,
    /** True when the user long-pressed Next, meaning "just skip". */
    val forceHardSkip: Boolean = false,
)

sealed interface NextDecision {
    /** Do what the player always did: cut to the next track. */
    data class HardSkip(
        val reason: String,
    ) : NextDecision

    /** Start a blend into the next track, lasting [durationMs]. */
    data class Mix(
        val durationMs: Long,
    ) : NextDecision
}

object MixPlanner {
    /** Tracks shorter than this are never mixed (spec 9.1: "Never mix tracks under 60 s"). */
    const val MIN_MIXABLE_TRACK_MS = 60_000L

    /** Floor for any blend (spec 9.3.3). */
    const val MIN_MIX_MS = 1_500L

    /** Floor for a blend started by rapid tapping (spec 9.3.4c). */
    const val MIN_RAPID_MIX_MS = 1_200L

    /** Taps closer together than this count as skimming. */
    const val RAPID_TAP_WINDOW_MS = 2_000L

    const val RAPID_TAP_FACTOR = 0.6f

    fun decideNext(ctx: NextPressContext): NextDecision {
        if (ctx.forceHardSkip) return NextDecision.HardSkip("long-press")
        if (!ctx.mixOnNext) return NextDecision.HardSkip("mix-on-next off")
        if (!ctx.autoMixEnabled) return NextDecision.HardSkip("auto-mix off")
        if (!ctx.hasNext) return NextDecision.HardSkip("no next track")
        if (!ctx.isPlaying) return NextDecision.HardSkip("paused")
        if (ctx.currentIsVideoOrLive || ctx.nextIsVideoOrLive) return NextDecision.HardSkip("video or live")
        if (ctx.currentDurationMs in 1 until MIN_MIXABLE_TRACK_MS) return NextDecision.HardSkip("track too short")
        if (ctx.nextDurationMs in 1 until MIN_MIXABLE_TRACK_MS) return NextDecision.HardSkip("next track too short")

        val rapid = ctx.msSinceLastNextPress?.let { it in 0 until RAPID_TAP_WINDOW_MS } == true
        return NextDecision.Mix(mixDurationMs(ctx, rapid))
    }

    /**
     * Length of a blend started by a Next press.
     *
     * - never longer than the time the outgoing track has left (it would end mid-fade),
     * - never longer than half the incoming track,
     * - shorter when the user is skimming, so the app keeps up with their thumb,
     * - never below [MIN_MIX_MS] ([MIN_RAPID_MIX_MS] while skimming).
     */
    fun mixDurationMs(
        ctx: NextPressContext,
        rapid: Boolean,
    ): Long {
        var d = ctx.configuredDurationMs
        val floor = if (rapid) MIN_RAPID_MIX_MS else MIN_MIX_MS
        if (rapid) d = (d * RAPID_TAP_FACTOR).toLong()
        if (ctx.currentDurationMs > 0L) {
            val remaining = max(0L, ctx.currentDurationMs - ctx.currentPositionMs)
            d = min(d, remaining)
        }
        if (ctx.nextDurationMs > 0L) d = min(d, ctx.nextDurationMs / 2)
        return max(floor, d)
    }

    /** User chosen mix lengths, in seconds. 0 stands for "Auto". The list must contain 15 and 30. */
    val DURATION_CHOICES_SEC: List<Int> = listOf(0, 1, 2, 3, 5, 8, 10, 12, 15, 20, 25, 30, 40, 45)

    const val MAX_CUSTOM_SEC = 45f
    const val MIN_CUSTOM_SEC = 1f
}
