package com.lleps.tradexchange.server

import org.ta4j.core.Bar

/** A simple interface to interact with an exchange. The impls are the real one, and a fake one for backtesting. */
interface Exchange {
    val moneyBalance: Double

    val coinBalance: Double

    fun buy(coins: Double, price: Double)

    fun sell(coins: Double, price: Double)

    fun prettyBalance() = "money: \$$moneyBalance coins: $coinBalance"
}