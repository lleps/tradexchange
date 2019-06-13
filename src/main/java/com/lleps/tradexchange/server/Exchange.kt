package com.lleps.tradexchange.server

/** A simple interface to buy, sell and fetch ticker from an exchange. */
interface Exchange {
    /** Get trades */
    data class Trade(val type: String, val epoch: Long, val coins: Double, val price: Double, val total: Double)
    val pastTrades: List<Trade>

    /** Balance in USD for the account */
    val moneyBalance: Double

    /** Balance in coins for the account */
    val coinBalance: Double

    /** Fetch the ticker from the exchange */
    data class Ticker(val last: Double, val baseVolume: Double, val quoteVolume: Double)
    fun fetchTicker(): Ticker

    /**
     * Buy [coins] from the exchange at market price.
     * May throw errors.
     * Returns the currency price at which the coins were bought.
     * If for some reason can't sell, throws an exception.
     */
    fun buy(coins: Double): Double

    /**
     * Sell [coins] from the exchange at market price.
     * Returns the currency price at which the coins were sold.
     * If for some reason can't sell, throws an exception.
     */
    fun sell(coins: Double): Double
}