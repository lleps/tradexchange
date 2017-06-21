import com.cf.data.model.poloniex.PoloniexChartData
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.scene.control.Tooltip
import javafx.util.Duration

fun hackTooltipStartTiming(tooltip: Tooltip) {
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

fun Long.forHours(hours: Long) = ((hours*3600) / this).toInt()

fun List<Double>.sma(periods: Long): Double {
    val lastElements = takeLast(periods.toInt())
    return lastElements.sum() / lastElements.size
}

typealias EmaEntry = Pair<List<Double>, Int>
private val emaCache = mutableMapOf<EmaEntry, Double>()

fun List<Double>.ema(periods: Int): Double {
    var cached = emaCache[EmaEntry(this, periods)]
    if (cached != null) return cached

    if (size == 1) return last()

    val lastElements = takeLast(periods)
    val c = 2.0 / (lastElements.size + 1).toDouble()
    cached = c*lastElements.last() + (1-c)*dropLast(1).ema(periods)
    emaCache[EmaEntry(this, periods)] = cached
    return cached
}

data class MACDEntry(val list: List<Double>, val emaShortPeriod: Int, val emaLongPeriod: Int, val signalPeriod: Int)

data class MACD(val macd: Double, val signal: Double, val histogram: Double)

private val macdCache = mutableMapOf<MACDEntry, MACD>()
fun List<Double>.macd(emaShortPeriod: Int = 12, emaLongPeriod: Int = 26, signalPeriod: Int = 9): MACD {
    val emaShortResult = ema(emaShortPeriod)
    val emaLongResult = ema(emaLongPeriod)
    val macd = emaShortResult - emaLongResult
    val macdForLatestPoints = mutableListOf<Double>()
    (signalPeriod - 1 downTo 0).forEach { macdForLatestPoints += dropLast(it).ema(emaShortPeriod) - dropLast(it).ema(emaLongPeriod) }
    val signal = macdForLatestPoints.ema(signalPeriod)
    val histogram = macd - signal
    return MACD(macd, signal, histogram)
}

fun List<Double>.rsi(periods: Int): Double {
    val prices = takeLast(periods)
    var sumGain = 0.0
    var sumLoss = 0.0
    for (i in 1..prices.size-1) {
        val diff = prices[i] - prices[i-1]
        if (diff > 0) {
            sumGain += diff
        } else {
            sumLoss -= diff
        }
    }
    if (sumGain == 0.0) return 0.0
    if (Math.abs(sumLoss) < 0.1) return 100.0
    val relativeStrength = sumGain / sumLoss
    return 100.0 - (100.0 / (1 + relativeStrength))
}

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

val PoloniexChartData.price: Double
    get() = close.toDouble()