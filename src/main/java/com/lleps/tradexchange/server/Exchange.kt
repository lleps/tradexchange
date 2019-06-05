package com.lleps.tradexchange.server

import org.ta4j.core.Bar

interface Exchange {
    val warmUpHistory: List<Bar>

    val moneyBalance: Double

    val coinBalance: Double

    fun fetchTick(): Bar?

    fun buy(coins: Double, price: Double)

    fun sell(coins: Double, price: Double)

    fun prettyBalance() = "money: \$$moneyBalance coins: $coinBalance"
}