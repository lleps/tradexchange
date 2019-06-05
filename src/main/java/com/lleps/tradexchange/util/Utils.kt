package com.lleps.tradexchange.util

import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.scene.control.Tooltip
import javafx.util.Duration
import org.ta4j.core.Indicator
import org.ta4j.core.num.Num

operator fun Indicator<Num>.get(index: Int) = getValue(index).doubleValue()

fun avg(vararg entries: Pair<Double, Int>) = entries.sumByDouble { it.first * it.second } / entries.sumBy { it.second }

fun hackTooltipStartTiming(tooltip: Tooltip = Tooltip()) {
    try {
        val fieldBehavior = tooltip.javaClass.getDeclaredField("BEHAVIOR")
        fieldBehavior.isAccessible = true
        val objBehavior = fieldBehavior.get(tooltip)

        val fieldTimer = objBehavior.javaClass.getDeclaredField("activationTimer")
        fieldTimer.isAccessible = true
        val objTimer = fieldTimer.get(objBehavior) as Timeline

        objTimer.keyFrames.clear()
        objTimer.keyFrames.add(KeyFrame(Duration(15.0)))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun Indicator<Num>.crossOver(other: Indicator<Num>, tick: Int): Boolean {
    return get(tick) > other[tick] && get(tick - 1) < other[tick - 1]
}

fun Indicator<Num>.crossUnder(other: Indicator<Num>, tick: Int): Boolean {
    return get(tick) < other[tick] && get(tick - 1) > other[tick - 1]
}

fun Indicator<Num>.falling(tick: Int, length: Int): Boolean {
    repeat(length) { i ->
        if (get(tick - i - 1) > get(tick - i)) {
            return false
        }
    }
    return true
}

fun Indicator<Num>.rising(tick: Int, length: Int): Boolean {
    repeat(length) { i ->
        if (get(tick - i - 1) < get(tick - i)) {
            return false
        }
    }
    return true
}

fun Indicator<Num>.isAfterLocalMaximum(tick: Int): Boolean {
    return get(tick - 1) > get(tick) && get(tick - 1) > get(tick - 2)
}

fun Indicator<Num>.isAfterLocalMinimum(tick: Int): Boolean {
    return get(tick - 1) < get(tick) && get(tick - 1) < get(tick - 2)
}

fun Long.forHours(hours: Long) = ((hours*3600) / this).toInt()

fun List<Double>.isLocalMaximum(periods: Int = 3): Boolean {
    check(periods % 3 == 0) { "periods for local maximum must be divisible by 3" }
    val reversed = asReversed()
    val a = if (periods == 3) reversed[2] else if (periods == 6) ((reversed[4] + reversed[5]) / 2.0) else error("unsupported period")
    val b = if (periods == 3) reversed[1] else if (periods == 6) ((reversed[3] + reversed[2]) / 2.0) else error("unsupported period")
    val c = if (periods == 3) reversed[0] else if (periods == 6) ((reversed[1] + reversed[0]) / 2.0) else error("unsupported period")
    return b > a && b > c
}

fun List<Double>.isLocalMinimum(periods: Int = 3): Boolean {
    check(periods % 3 == 0) { "periods for local maximum must be divisible by 3" }
    val reversed = asReversed()
    val a = if (periods == 3) reversed[2] else if (periods == 6) ((reversed[4] + reversed[5]) / 2.0) else error("unsupported period")
    val b = if (periods == 3) reversed[1] else if (periods == 6) ((reversed[3] + reversed[2]) / 2.0) else error("unsupported period")
    val c = if (periods == 3) reversed[0] else if (periods == 6) ((reversed[1] + reversed[0]) / 2.0) else error("unsupported period")
    return b < a && b < c
}

