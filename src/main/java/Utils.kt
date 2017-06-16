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

fun List<PoloniexChartData>.dataLatestHours(period: Long, hours: Long): List<PoloniexChartData> {
    return takeLast(((hours*3600) / period).toInt())
}

fun List<PoloniexChartData>.smaHours(period: Long, hours: Long): Double {
    val datas = dataLatestHours(period, hours)
    return datas.sumByDouble { it.weightedAverage.toDouble() } / datas.size
}

fun List<PoloniexChartData>.maxPriceHours(period: Long, hours: Long): Double {
    val datas = dataLatestHours(period, hours)
    return datas.map { it.price }.max()!!
}

fun List<PoloniexChartData>.minPriceHours(period: Long, hours: Long): Double {
    val datas = dataLatestHours(period, hours)
    return datas.map { it.price }.min()!!
}

fun Long.periodsForHours(hours: Long) = (hours*3600) / this

// TODO test this class
fun List<PoloniexChartData>.sma(periods: Long): Double {
    val datas = takeLast(periods.toInt())
    return datas.sumByDouble { it.price } / datas.size
}

fun List<PoloniexChartData>.ema(periods: Int): Double {
    if (size == 1) return last().price

    val datas = takeLast(periods)
    val c = 2.0 / (datas.size + 1).toDouble()
    return c * datas.last().price + (1-c) * dropLast(1).ema(periods)
}

val PoloniexChartData.price: Double
    get() = weightedAverage.toDouble()