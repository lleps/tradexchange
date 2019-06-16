package com.lleps.tradexchange.server

import com.lleps.tradexchange.strategy.Strategy
import com.lleps.tradexchange.util.getTicksFromPoloniex
import com.lleps.tradexchange.util.parseCandlesFromCSV
import org.ta4j.core.Bar
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Handlers for behavior of the different instance types. */
interface InstanceController {
    fun getRequiredInput(): Map<String, String>
    fun onLoaded()
    fun onCreated()
    fun onDeleted()
    fun onExecute(input: Map<String, String>, button: Int)
    fun onToggleCandle(candleEpoch: Long, toggle: Int)
}

fun fetchTicksRequiredInput() = mapOf(
    "ticks.source" to "poloniex",
    "ticks.csv.file" to "../Bitfinex-historical-data/ETHUSD/Candles_1m/2019/merged.csv",
    "ticks.csv.startDate" to "2019-01-01",
    "ticks.csv.endDate" to "2019-01-04",
    "ticks.poloniex.dayRange" to "7-0"
)

/** Parse ticks from the given input. Useful for train and backtest, as these two share data sources. */
fun fetchTicks(pair: String, period: Long, input: Map<String, String>, out: Strategy.OutputWriter): List<Bar> {
    val btSource = input.getValue("ticks.source")
    val btCsvFile = input.getValue("ticks.csv.file")
    val btCsvDateStart = input.getValue("ticks.csv.startDate")
    val btCsvDateEnd = input.getValue("ticks.csv.endDate")
    val btPolDays0 = input.getValue("ticks.poloniex.dayRange").split("-")[0].toInt()
    val btPolDays1 = input.getValue("ticks.poloniex.dayRange").split("-")[1].toInt()

    return when (btSource) {
        "csv" -> {
            out.write("Parse ticks from $btCsvFile at period ${period.toInt()}")
            val ticks = parseCandlesFromCSV(
                file = btCsvFile,
                periodSeconds = period.toInt(),
                startDate = LocalDate.parse(btCsvDateStart, DateTimeFormatter.ISO_DATE).atStartOfDay(),
                endDate = LocalDate.parse(btCsvDateEnd, DateTimeFormatter.ISO_DATE).atStartOfDay())
            ticks
        }
        "poloniex" -> {
            out.write("Using data from poloniex server...")
            val limit = (Instant.now().toEpochMilli() / 1000) - (btPolDays1 * 24 * 3600)
            val ticks = getTicksFromPoloniex(pair, period.toInt(), btPolDays0)
                .filter { it.endTime.toEpochSecond() < limit }
            ticks
        }
        else -> error("invalid bt.source. Valid: 'csv' or 'poloniex'")
    }
}
