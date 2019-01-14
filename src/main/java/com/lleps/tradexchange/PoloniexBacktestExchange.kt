package com.lleps.tradexchange

import com.cf.client.poloniex.PoloniexExchangeService
import com.cf.data.model.poloniex.PoloniexChartData
import org.slf4j.LoggerFactory
import org.ta4j.core.BaseTick
import org.ta4j.core.Decimal
import org.ta4j.core.Tick
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
        val file = "data/cache-pol-$pair-$period-${from/3600}.json"
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

    private val warmUpChartData = chartData.subList(0, warmUpTicks).toMutableList()

    private val testingChartData = chartData.subList(warmUpTicks, chartData.size).toMutableList()

    override val warmUpHistory: List<Tick>
        get() = warmUpChartData.map {
            BaseTick(
                Duration.ofSeconds(period),
                Instant.ofEpochSecond(it.date.toEpochSecond()).atZone(ZoneOffset.UTC),
                Decimal.valueOf(it.open.toDouble()),
                Decimal.valueOf(it.high.toDouble()),
                Decimal.valueOf(it.low.toDouble()),
                Decimal.valueOf(it.close.toDouble()),
                Decimal.valueOf(it.volume.toDouble())
            )
        }

    override var moneyBalance: Double = initialMoney

    override var coinBalance: Double = initialCoins

    private val chartDataAsTicks: MutableList<BaseTick> by lazy {
        testingChartData.map {
            BaseTick(
                Duration.ofSeconds(period),
                Instant.ofEpochSecond(it.date.toEpochSecond()).atZone(ZoneOffset.UTC),
                Decimal.valueOf(it.open.toDouble()),
                Decimal.valueOf(it.high.toDouble()),
                Decimal.valueOf(it.low.toDouble()),
                Decimal.valueOf(it.close.toDouble()),
                Decimal.valueOf(it.volume.toDouble())
            )
        }.toMutableList()
    }

    override fun fetchTick(): Tick? {
        if (chartDataAsTicks.isEmpty()) return null
        val result = chartDataAsTicks.removeAt(0)
        return result
    }

    override fun buy(coins: Double, price: Double) {
        if (moneyBalance < coins * price) {
            LOGGER.error("Not enough balance to buy $coins coins at $price each.")
            return
        }

        moneyBalance -= coins * price
        coinBalance += coins
        LOGGER.debug("Buying $coins coins at $price (total: ${coins * price})")
    }

    override fun sell(coins: Double, price: Double) {
        if (coinBalance < coins) {
            LOGGER.error("Want to sell $coins coins at $price but got only $coinBalance coins.")
            return
        }

        moneyBalance += coins * price
        coinBalance -= coins
        LOGGER.debug("Selling $coins coins at $price (total: ${coins * price})")
    }
}