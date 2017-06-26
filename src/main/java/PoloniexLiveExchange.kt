import com.cf.client.poloniex.PoloniexExchangeService
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.ZonedDateTime

class PoloniexLiveExchange(
        private val pair: String,
        private val period: Long,
        warmUpPeriods: Int,
        apiKey: String = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK",
        apiSecret: String = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"
) : Exchange {

    private val poloniex = PoloniexExchangeService(apiKey, apiSecret)
    private val initialNowEpoch = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond()
    private val warmUp = poloniex.returnChartData(pair, period, initialNowEpoch - warmUpPeriods*period).toMutableList()

    override val warmUpHistory: List<Double>
        get() = warmUp.map { it.price }

    override val moneyBalance: Double
        get() = poloniex.returnBalance(pair.split("_")[0]).available.toDouble() // X_IGNORED

    override val coinBalance: Double
        get() = poloniex.returnBalance(pair.split("_")[1]).available.toDouble() // IGNORED_X

    override fun fetchTicker(): Exchange.Ticker? {
        Thread.sleep(period * 1000)
        val nowEpoch = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond()
        val ticker = poloniex.returnTicker(pair) ?: error("ticker for $pair is null")
        return Exchange.Ticker(ticker.last.toDouble(), nowEpoch)
    }

    override fun buy(coins: Double, price: Double) {
        println("Buy $coins coins at $price on poloniex.")
        val result = poloniex.buy(pair, BigDecimal.valueOf(price), BigDecimal.valueOf(coins), false, false, false)
        println("error: ${result.error}")
    }

    override fun sell(coins: Double, price: Double) {
        println("Sell $coins coins at $price on poloniex.")
        val result = poloniex.sell(pair, BigDecimal.valueOf(price), BigDecimal.valueOf(coins), false, false, false)
        println("error: ${result.error}")
    }
}