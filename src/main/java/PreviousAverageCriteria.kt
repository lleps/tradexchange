import com.cf.data.model.poloniex.PoloniexChartData

class PreviousAverageCriteria : Criteria {
    private var count = 0

    override fun evaluate(periodSeconds: Long, candle: PoloniexChartData, history: List<PoloniexChartData>): Double {
        val dataLast24h = history.reversed().take((3*3600) / periodSeconds.toInt())
        val max24h = dataLast24h.maxBy { it.high.toDouble() }!!.high.toDouble()
        val min24h = dataLast24h.minBy { it.low.toDouble() }!!.low.toDouble()
        val average24h = (max24h + min24h) / 2.0
        val close = candle.close.toDouble()
        val open = candle.open.toDouble()
        val average = (close + open) / 2.0
        if (count++ % 4 == 0) {
            println("reuslt: ${(average - average24h)}")
            val off = (average - average24h)
            return off
            /*if (average > average24h+0.1) {
                val magnitude = (average - average24h)
                return magnitude.toInt()
            } else if (average < average24h-0.1) {
                return -1
            }*/
        }

        return 0.0
    }
}