import com.lleps.tradexchange.server.PoloniexLiveExchange

class PoloniexLiveExchangeTest {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val poloniex = PoloniexLiveExchange(
                pair = "USDT_ETC",
                period = 10,
                warmUpPeriods = 10,
                apiKey = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK",
                apiSecret = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b")
            println("coin balance: ${poloniex.coinBalance}")
            println("money balance: ${poloniex.moneyBalance}")
            println("ticker: ${poloniex.fetchTick()}")
            println("selling 0.001 for 19.0")
            poloniex.sell(coins = 0.001, price = 19.0)
            Thread.sleep(30000)
            println("buying 0.001 etc for 19.0")
            poloniex.buy(coins = 0.001, price = 19.0)
        }
    }
}