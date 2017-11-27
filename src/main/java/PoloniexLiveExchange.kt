import com.cf.client.poloniex.PoloniexExchangeService
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
        apiKey: String = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK",
        apiSecret: String = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"
) : Exchange {

    private val poloniex = PoloniexExchangeService(apiKey, apiSecret)
    private val initialNowEpoch = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond()
    private val warmUp = poloniex.returnChartData(pair, period, initialNowEpoch - warmUpPeriods*period).toMutableList()

    override val warmUpHistory: List<Tick>
        get() = warmUp.map {
            BaseTick(Duration.ofSeconds(period), Instant.ofEpochSecond(it.date.toLong()).atZone(ZoneOffset.UTC),
                    Decimal.valueOf(it.open), Decimal.valueOf(it.high), Decimal.valueOf(it.low), Decimal.valueOf(it.close),
                    Decimal.valueOf(it.volume))
        }

    override val moneyBalance: Double
        get() = poloniex.returnBalance(pair.split("_")[0]).available.toDouble() // X_IGNORED

    override val coinBalance: Double
        get() = poloniex.returnBalance(pair.split("_")[1]).available.toDouble() // IGNORED_X

    override fun fetchTick(): Tick? = null

    override fun buy(coins: Double, price: Double) {
        println("Buy $coins coins at $price on poloniex.")
        poloniex.buy(pair, BigDecimal.valueOf(price), BigDecimal.valueOf(coins), false, false, false)
    }

    override fun sell(coins: Double, price: Double) {
        println("Sell $coins coins at $price on poloniex.")
         poloniex.sell(pair, BigDecimal.valueOf(price), BigDecimal.valueOf(coins), false, false, false)
    }
}