package com.lleps.tradexchange.server

import com.cf.client.poloniex.PoloniexExchangeService
import com.cf.data.model.poloniex.PoloniexChartData
import com.lleps.tradexchange.util.loadFrom
import com.lleps.tradexchange.util.saveTo
import org.slf4j.LoggerFactory
import org.ta4j.core.Bar
import org.ta4j.core.BaseBar
import org.ta4j.core.num.DoubleNum
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

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

class PoloniexBacktestExchange(
    initialMoney: Double = 0.0,
    initialCoins: Double = 0.0
) : Exchange {

    companion object {
        private val LOGGER = LoggerFactory.getLogger(PoloniexBacktestExchange::class.java)
    }

    override var moneyBalance: Double = initialMoney
    override var coinBalance: Double = initialCoins

    override fun buy(coins: Double, price: Double) {
        if (moneyBalance < coins * price) {
            LOGGER.error("Not enough balance to buy $coins coins at $price each.")
            //return
        }

        moneyBalance -= coins * price
        coinBalance += coins
        LOGGER.debug("Buying $coins coins at $price (total: ${coins * price})")
    }

    override fun sell(coins: Double, price: Double) {
        if (coinBalance < coins) {
            LOGGER.error("Want to sell $coins coins at $price but got only $coinBalance coins.")
            //return
        }

        moneyBalance += coins * price
        coinBalance -= coins
        LOGGER.debug("Selling $coins coins at $price (total: ${coins * price})")
    }
}