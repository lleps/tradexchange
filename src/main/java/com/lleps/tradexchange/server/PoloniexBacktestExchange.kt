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

class PoloniexBacktestExchange(
    pair: String,
    private val period: Long,
    fromEpoch: Long,
    warmUpTicks: Int,
    initialMoney: Double = 0.0,
    initialCoins: Double = 0.0
) : Exchange {

    private val apiKey = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK"
    private val apiSecret = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"
    private val poloniex = PoloniexExchangeService(apiKey, apiSecret)
    private class ChartDataWrapper(val content: List<PoloniexChartData> = mutableListOf())
    private val chartData: List<PoloniexChartData>

    companion object {
        private val LOGGER = LoggerFactory.getLogger(PoloniexBacktestExchange::class.java)
    }

    init {
        val from = fromEpoch - (warmUpTicks * period)
        File("data").mkdir()
        File("data/cache").mkdir()
        val file = "data/cache/pol-$pair-$period-${from/3600}.json"
        LOGGER.info("Trying to load data from '$file'...")
        val cached = loadFrom<ChartDataWrapper>(file)
        if (cached == null) {
            LOGGER.info("Failed. Fetching online...")
            val result = ChartDataWrapper(poloniex.returnChartData(pair, period, from).toList())
            result.saveTo(file)
            chartData = result.content
        } else {
            LOGGER.info("Loaded from cache.")
            chartData = cached.content
        }
    }

    private val warmUpChartData by lazy {
        chartData.subList(0, warmUpTicks).toMutableList()
    }

    private val testingChartData by lazy {
        chartData.subList(warmUpTicks, chartData.size).toMutableList()
    }

    override val warmUpHistory: List<Bar>
        get() = warmUpChartData.map {
            BaseBar(
                Duration.ofSeconds(period),
                Instant.ofEpochSecond(it.date.toEpochSecond()).atZone(ZoneOffset.UTC),
                DoubleNum.valueOf(it.open.toDouble()),
                DoubleNum.valueOf(it.high.toDouble()),
                DoubleNum.valueOf(it.low.toDouble()),
                DoubleNum.valueOf(it.close.toDouble()),
                DoubleNum.valueOf(it.volume.toDouble()),
                DoubleNum.valueOf(0)
            )
        }

    override var moneyBalance: Double = initialMoney// + 0.001

    override var coinBalance: Double = initialCoins// + 0.001

    private val chartDataAsTicks: MutableList<BaseBar> by lazy {
        testingChartData.map {
            BaseBar(
                Duration.ofSeconds(period),
                Instant.ofEpochSecond(it.date.toEpochSecond()).atZone(ZoneOffset.UTC),
                DoubleNum.valueOf(it.open.toDouble()),
                DoubleNum.valueOf(it.high.toDouble()),
                DoubleNum.valueOf(it.low.toDouble()),
                DoubleNum.valueOf(it.close.toDouble()),
                DoubleNum.valueOf(it.volume.toDouble()),
                DoubleNum.valueOf(0)
            )
        }.toMutableList()
    }

    override fun fetchTick(): Bar? {
        if (chartDataAsTicks.isEmpty()) return null
        return chartDataAsTicks.removeAt(0)
    }

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