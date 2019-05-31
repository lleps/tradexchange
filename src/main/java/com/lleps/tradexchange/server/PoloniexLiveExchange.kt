package com.lleps.tradexchange.server

import com.cf.client.poloniex.PoloniexExchangeService
import org.slf4j.LoggerFactory
import org.ta4j.core.BaseTick
import org.ta4j.core.Decimal
import org.ta4j.core.Tick
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class PoloniexLiveExchange(
    private val pair: String,
    private val period: Long,
    warmUpPeriods: Int,
    apiKey: String = API_KEY,
    apiSecret: String = API_SECRET
) : Exchange {
    private val poloniex = PoloniexExchangeService(apiKey, apiSecret)
    private val initialNowEpoch = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond()
    private val warmUp = poloniex.returnChartData(pair, period, initialNowEpoch - warmUpPeriods*period).toMutableList()

    companion object {
        private val LOGGER = LoggerFactory.getLogger(PoloniexLiveExchange::class.java)
        private const val API_KEY = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK"
        private const val API_SECRET = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"

        @JvmStatic
        fun main(args: Array<String>) {
            // Try to sell immediately
            val poloniex = PoloniexExchangeService(API_KEY, API_SECRET)
            val pair = "USDT_ETH"
            val ticker = poloniex.returnTicker(pair)
            val ethsPerUsd = 1.1 / ticker.last.toDouble()
            val buy = poloniex.buy("USDT_ETH", ticker.lowestAsk, BigDecimal(ethsPerUsd), true, true, false)
            println("err: ${buy.error}")
            println("num: ${buy.orderNumber}")
            println("trades: ${buy.resultingTrades}")
            println("return open orders...")
            val orders = poloniex.returnOpenOrders(pair)
            println(orders.toString())
            println("return trade history...")
            println(poloniex.returnTradeHistory(pair))
        }
    }

    override val warmUpHistory: List<Tick>
        get() = warmUp.map {
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

    override val moneyBalance: Double
        get() = poloniex.returnCurrencyBalance(pair.split("_")[0]).available.toDouble() // X_IGNORED

    override val coinBalance: Double
        get() = poloniex.returnCurrencyBalance(pair.split("_")[1]).available.toDouble() // IGNORED_X

    override fun fetchTick(): Tick? {
        // Fetch ticker every few seconds, to grab max,min,open,close,etc and return as a candle
        val tickExpire = System.currentTimeMillis() + period*1000
        var high = 0.0
        var low = Double.MAX_VALUE
        var ticker = poloniex.returnTicker(pair)
        val open = ticker.last.toDouble()
        Thread.sleep(5000)
        while (System.currentTimeMillis() < tickExpire) {
            ticker = poloniex.returnTicker(pair)
            val price = ticker.last.toDouble()
            if (price > high) high = price
            if (price < low) low = price
            Thread.sleep(10*1000)
        }
        val close = ticker.last.toDouble()
        return BaseTick(
                Duration.ofSeconds(period),
                Instant.now().atZone(ZoneOffset.UTC),
                Decimal.valueOf(open), // open
                Decimal.valueOf(high), // high
                Decimal.valueOf(low), // low
                Decimal.valueOf(close), // close
                Decimal.valueOf(ticker.baseVolume.toDouble())
        )
    }

    override fun buy(coins: Double, price: Double) {
        LOGGER.info("Buy $coins coins at $price on poloniex.")
        poloniex.buy(pair, BigDecimal.valueOf(price), BigDecimal.valueOf(coins), false, false, false)
    }

    override fun sell(coins: Double, price: Double) {
        LOGGER.info("Sell $coins coins at $price on poloniex.")
        poloniex.sell(pair, BigDecimal.valueOf(price), BigDecimal.valueOf(coins), false, false, false)
    }
}