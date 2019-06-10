package com.lleps.tradexchange.server

import com.cf.client.poloniex.PoloniexExchangeService
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class PoloniexLiveExchange(
    private val pair: String,
    apiKey: String = API_KEY,
    apiSecret: String = API_SECRET
) : Exchange {
    private val poloniex = PoloniexExchangeService(apiKey, apiSecret)

    override val moneyBalance: Double
        get() = poloniex.returnCurrencyBalance(pair.split("_")[0]).available.toDouble() // X_IGNORED

    override val coinBalance: Double
        get() = poloniex.returnCurrencyBalance(pair.split("_")[1]).available.toDouble() // IGNORED_X

    override fun fetchTicker(): Exchange.Ticker {
        val ticker = poloniex.returnTicker(pair)
        return Exchange.Ticker(ticker.last.toDouble(), ticker.baseVolume.toDouble(), ticker.quoteVolume.toDouble())
    }

    override fun buy(coins: Double): Double {
        val maxAttempts = 5
        var buyPrice: Double? = null
        for (i in 1..maxAttempts) {
            val lowestAsk = poloniex.returnTicker(pair).lowestAsk
            val result = poloniex.buy(pair, lowestAsk, BigDecimal.valueOf(coins), true, true, false)
            if (result.resultingTrades == null || result.resultingTrades.isEmpty()) {
                LOGGER.info("Attempt $i/$maxAttempts to buy failed (ask: ${lowestAsk.toDouble()}). Wait 2 sec and try again.")
                Thread.sleep(2000)
            } else {
                buyPrice = lowestAsk.toDouble()
                break
            }
        }
        if (buyPrice == null) error("can't buy $coins at market price. Tried $maxAttempts timse and all failed.")
        LOGGER.info("Bought $coins at $buyPrice on poloniex, for real.")
        return buyPrice
    }

    override fun sell(coins: Double): Double {
        val maxAttempts = 5
        var sellPrice: Double? = null
        for (i in 1..maxAttempts) {
            val highestBid = poloniex.returnTicker(pair).highestBid
            val result = poloniex.sell(pair, highestBid, BigDecimal.valueOf(coins), true, true, false)
            if (result.resultingTrades == null || result.resultingTrades.isEmpty()) {
                LOGGER.info("Attempt $i/$maxAttempts to sell failed (bid: ${highestBid.toDouble()}). Wait 2 sec and try again.")
                Thread.sleep(2000)
            } else {
                sellPrice = highestBid.toDouble()
                break
            }
        }
        if (sellPrice == null) error("can't sell $coins at market price. Tried $maxAttempts times and all failed.")
        LOGGER.info("Sold $coins at $sellPrice on poloniex, for real.")
        return sellPrice
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(PoloniexLiveExchange::class.java)
        private const val API_KEY = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK"
        private const val API_SECRET = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"

        /**
         * Test for the live exchange
         */
        @JvmStatic
        fun main(args: Array<String>) {
            val pol = PoloniexLiveExchange("USDT_ETH")
            println(pol.fetchTicker())

            // Buy test
            /*val amount = 1.1 / 246.13
            println("buy $amount ETH...")
            pol.buy(amount)
            println("ok!")
            */

            // Sell test
            /*val amount = 0.00445
            println("sell $amount ETH...")
            pol.sell(amount)
            println("sold!")*/
        }
    }
}