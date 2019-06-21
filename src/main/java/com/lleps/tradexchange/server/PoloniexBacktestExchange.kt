package com.lleps.tradexchange.server

import org.slf4j.LoggerFactory


class PoloniexBacktestExchange(
    initialMoney: Double = 0.0,
    initialCoins: Double = 0.0
) : Exchange {

    companion object {
        private val LOGGER = LoggerFactory.getLogger(PoloniexBacktestExchange::class.java)
    }

    var marketPrice: Double = 0.0

    override val pastTrades: List<Exchange.Trade>
        get() = emptyList() // may add fake trades on buy and sell calls. all of this shit may be used to make the program more "stateless"

    override fun fetchTicker(): Exchange.Ticker {
        return Exchange.Ticker(marketPrice, 0.0, 0.0)
    }

    override var moneyBalance: Double = initialMoney
    override var coinBalance: Double = initialCoins

    override fun buy(coins: Double): Double {
        val price = marketPrice
        val totalPrice = coins * price
        if (moneyBalance < totalPrice) error("Not enough balance to buy $coins coins at $$price each (total price: $totalPrice)")
        if (coins * price < 1.1) error("only orders of > 1 USD allowed. (tried to buy $$totalPrice of coins, coins: $coins, price: $price)")
        // TODO: maybe simulate fees
        // TODO: should buy at a simulated "lowest ask". 0.5% difference.
        moneyBalance -= totalPrice
        coinBalance += coins
        LOGGER.debug("Buying $coins coins at $price (total: ${coins * price})")
        return price
    }

    override fun sell(coins: Double): Double {
        val price = marketPrice
        val totalPrice = coins * price
        if (coinBalance < coins) error("Want to sell $coins coins at $price but got only $coinBalance coins.")
        if (totalPrice < 1.1) error("only orders of > 1 USD allowed (tried to sell $coins coins at $price, total $totalPrice)")
        moneyBalance += totalPrice
        coinBalance -= coins
        LOGGER.debug("Selling $coins coins at $price (total: ${coins * price})")
        return price
    }
}