package com.lleps.tradexchange.util

import com.cf.client.poloniex.PoloniexExchangeService
import com.cf.data.model.poloniex.PoloniexChartData
import com.github.os72.protobuf351.DoubleValue
import org.ta4j.core.Bar
import org.ta4j.core.BaseBar
import org.ta4j.core.Indicator
import org.ta4j.core.num.DoubleNum
import org.ta4j.core.num.Num
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.*
import java.util.ArrayList


operator fun Indicator<Num>.get(index: Int) = getValue(index).doubleValue()

/** To mark a bar as a buy(1) or sell(2). Can be marked only once. */
fun Bar.markAs(type: Int) {
    check(trades == 0) { "this bar is already marked (as $trades)" }
    check(amount.isZero) { "amount is not zero, this bar is not clean." }
    check(type in 1..2) { "bars can be marked as 1(buy) or 2(sell) only" }
    repeat(type) { addTrade(amount/*aka num(0)*/, closePrice) }
}

/** Get the bar mark (0 unmarked, 1 buy, 2 sell). */
fun Bar.getMark(): Int {
    check(trades in 0..2) { "the mark of this bar is $trades (neither 0(unmarked), 1(buy), or 2(sell))" }
    return trades
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

/** Get market history from poloniex, using a local cache to speed it up. */
private class ChartDataWrapper(val content: List<PoloniexChartData> = mutableListOf())
fun getTicksFromPoloniex(pair: String, period: Int, daysBack: Int): List<Bar> {
    val fromEpoch = (Instant.now().toEpochMilli() / 1000) - (daysBack * 24 * 3600)
    File("data").mkdir()
    File("data/cache").mkdir()
    val file = "data/cache/pol-$pair-$period-${fromEpoch/3600}.json"
    val cached = loadFrom<ChartDataWrapper>(file)
    val poloniex = PoloniexExchangeService("", "")
    val chartData = if (cached == null) {
        val result = ChartDataWrapper(poloniex.returnChartData(pair, period.toLong(), fromEpoch).toList())
        result.saveTo(file)
        result.content
    } else {
        cached.content
    }

    return chartData.map {
        BaseBar(
            Duration.ofSeconds(period.toLong()),
            Instant.ofEpochSecond(it.date.toEpochSecond()).atZone(ZoneOffset.UTC),
            DoubleNum.valueOf(it.open.toDouble()),
            DoubleNum.valueOf(it.high.toDouble()),
            DoubleNum.valueOf(it.low.toDouble()),
            DoubleNum.valueOf(it.close.toDouble()),
            DoubleNum.valueOf(it.volume.toDouble()),
            DoubleNum.valueOf(0)
        )
    }
}

fun parseCandlesFrom1MinCSV(
    file: String,
    periodSeconds: Int,
    startDate: LocalDateTime? = null,
    endDate: LocalDateTime? = null
): List<Bar> {
    check(periodSeconds % 60 == 0) { "only periods multipliers of 60 allowed." }
    val startEpochMilli = (startDate?.toEpochSecond(ZoneOffset.UTC) ?: 0) * 1000
    val endEpochMilli = (endDate?.toEpochSecond(ZoneOffset.UTC) ?: 0) * 1000
    val result = ArrayList<Bar>(50000)
    val duration = java.time.Duration.ofSeconds(periodSeconds.toLong())
    var firstLine = true
    val linesToBuildCandle = periodSeconds / 60
    var candleAccumulator = 0
    var candleMin = 0.0
    var candleMax = 0.0
    var candleOpen = 0.0
    var candleVolumeAccumulator = 0.0
    for (line in Files.lines(Paths.get(file))) {
        if (firstLine) { firstLine = false; continue }

        // parse tick, check for time bounds
        val parts = line.split(",")
        val epoch = parts[0].toLong()
        if (epoch < startEpochMilli) continue
        else if (endEpochMilli in 1..(epoch - 1)) break
        val open = parts[1].toDouble()
        val high = parts[3].toDouble()
        val low = parts[4].toDouble()
        val close = parts[2].toDouble()
        val volume = parts[5].toDouble()

        if (candleAccumulator < linesToBuildCandle) {
            candleAccumulator++
            // reset variables and track open (or should track close as open?)
            if (candleAccumulator == 1) {
                candleMax = high
                candleMin = low
                candleOpen = open // the first candle. for the following candles the open is the latest candle close
                candleVolumeAccumulator = 0.0
            }
            // update vol, min, max
            candleMax = maxOf(candleMax, high)
            candleMin = minOf(candleMin, low)
            candleVolumeAccumulator += volume

            // candle epoch should be when it opens or when it closes?
            // check for the latest candle in the block. On that case, append to result and reset the accumulator
            if (candleAccumulator == linesToBuildCandle) {
                candleAccumulator = 0
                val tick = BaseBar(
                    duration,
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneOffset.UTC),
                    DoubleNum.valueOf(candleOpen), // open
                    DoubleNum.valueOf(candleMax), // high
                    DoubleNum.valueOf(candleMin), // low
                    DoubleNum.valueOf(close), // close
                    DoubleNum.valueOf(candleVolumeAccumulator), // volume
                    DoubleNum.valueOf(0)
                )
                //candleOpen = close // for the next candle
                result.add(tick)
            }
        }
    }
    return result
}