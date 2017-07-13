import eu.verdelhan.ta4j.Tick

interface Exchange {
    val warmUpHistory: List<Tick>

    val moneyBalance: Double

    val coinBalance: Double

    fun fetchTick(): Tick?

    fun buy(coins: Double, price: Double)

    fun sell(coins: Double, price: Double)

    fun prettyBalance() = "money: \$$moneyBalance coins: $coinBalance"
}