import com.cf.client.poloniex.PoloniexExchangeService
import com.cf.data.model.poloniex.PoloniexChartData

class PoloniexBacktestExchange(pair: String,
                               period: Long,
                               fromEpoch: Long,
                               warmUpPeriods: Int,
                               initialMoney: Double = 0.0,
                               initialCoins: Double = 0.0) : Exchange {

    private val apiKey = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK"
    private val apiSecret = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"
    private val poloniex = PoloniexExchangeService(apiKey, apiSecret)
    private class ChartDataWrapper(val content: List<PoloniexChartData> = mutableListOf())
    private val chartData: List<PoloniexChartData>

    init {
        val from = fromEpoch - warmUpPeriods
        val file = "data/cache-pol-$pair-$period-${from/3600}.json"
        val cached = loadFrom<ChartDataWrapper>(file)
        if (cached == null) {
            val result = ChartDataWrapper(poloniex.returnChartData(pair, period, from).toList())
            result.saveTo(file)
            chartData = result.content
        } else {
            chartData = cached.content
        }
    }

    private val warmUpChartData = chartData.subList(0, warmUpPeriods).toMutableList()

    private val testingChartData = chartData.subList(warmUpPeriods, chartData.size).toMutableList()

    override val warmUpHistory: List<Double>
        get() = warmUpChartData.map { it.price }

    override var moneyBalance: Double = initialMoney

    override var coinBalance: Double = initialCoins

    override fun fetchTicker(): Exchange.Ticker? {
        if (testingChartData.isEmpty()) return null
        val result = testingChartData.removeAt(0)
        return Exchange.Ticker(result.price, result.date.toLong())
    }

    override fun buy(coins: Double, price: Double) {
        if (moneyBalance < coins * price) {
            println("Error: Not enough balance to buy $coins coins at $price each.")
            return
        }

        moneyBalance -= coins * price
        coinBalance += coins
        println("Buying $coins coins at $price (total: ${coins * price})")
    }

    override fun sell(coins: Double, price: Double) {
        if (coinBalance < coins) {
            println("Error: Want to sell $coins coins at $price but got only $coinBalance coins.")
            return
        }

        moneyBalance += coins * price
        coinBalance -= coins
        println("Selling $coins coins at $price (total: ${coins * price})")
    }
}