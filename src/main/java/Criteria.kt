import com.cf.data.model.poloniex.PoloniexChartData

interface Criteria {
    fun evaluate(periodSeconds: Long, candle: PoloniexChartData, history: List<PoloniexChartData>): Double
}