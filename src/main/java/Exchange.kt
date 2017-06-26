interface Exchange {
    data class Ticker(val price: Double, val epoch: Long)

    val warmUpHistory: List<Double>

    val moneyBalance: Double

    val coinBalance: Double

    fun fetchTicker(): Ticker?

    fun buy(coins: Double, price: Double)

    fun sell(coins: Double, price: Double)

    fun prettyBalance() = "money: \$$moneyBalance coins: $coinBalance"
}