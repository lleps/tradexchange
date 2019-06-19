package com.lleps.tradexchange.strategy

import com.lleps.tradexchange.Candle

class ChartWriterImpl(private val plotLevel: Int = 3) : Strategy.ChartWriter {
    val candles = mutableListOf<Candle>() // just for convenience, not actually used
    val priceIndicators = mutableMapOf<String, MutableMap<Long, Double>>()
    val extraIndicators = mutableMapOf<String, MutableMap<String, MutableMap<Long, Double>>>()

    override fun priceIndicator(name: String, epoch: Long, value: Double) {
        if (plotLevel >= 2) {
            val data = priceIndicators.getOrPut(name) { mutableMapOf() }
            data[epoch] = value
        }
    }

    override fun extraIndicator(chart: String, name: String, epoch: Long, value: Double) {
        if (plotLevel >= 3) {
            val chartData = extraIndicators.getOrPut(chart) { mutableMapOf() }
            val data = chartData.getOrPut(name) { mutableMapOf() }
            data[epoch] = value
        }
    }
}