package com.lleps.tradexchange.strategy

import com.lleps.tradexchange.indicator.*
import com.lleps.tradexchange.util.get
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.nd4j.linalg.factory.Nd4j
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.CCIIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.ROCIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.helpers.*
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator
import org.ta4j.core.num.Num

/**
 * The point of this is to group model-related behavior, like feature gathering (evaluating a bunch of indicators),
 * This is stateful, may have a model loaded for predictions but can be used without it.
 */
class SeriesModel private constructor(
    private val series: TimeSeries,
    private val closeIndicator: ClosePriceIndicator,
    private val usedIndicators: List<Triple<String, String, Indicator<Num>>>
){
    private class IndicatorType(
        val group: String, // to group them in charts
        val name: String,
        val defaultValue: String,
        val factory: (TimeSeries, Indicator<Num>, List<Int>) -> Indicator<Num>
    )

    companion object {
        private val FEATURE_TYPES = listOf(
            IndicatorType("price", "close", "300") { series, _, input ->
                NormalizationIndicator(
                    indicator = ClosePriceIndicator(series),
                    timeFrame = input[0],
                    maxBound = MaxPriceIndicator(series),
                    minBound = MinPriceIndicator(series))
            },
            IndicatorType("price", "open", "300") { series, _, input ->
                NormalizationIndicator(
                    indicator = OpenPriceIndicator(series),
                    timeFrame = input[0],
                    maxBound = MaxPriceIndicator(series),
                    minBound = MinPriceIndicator(series))
            },
            IndicatorType("price", "high", "300") { series, _, input ->
                NormalizationIndicator(
                    indicator = MaxPriceIndicator(series),
                    timeFrame = input[0],
                    maxBound = MaxPriceIndicator(series),
                    minBound = MinPriceIndicator(series))
            },
            IndicatorType("price", "low", "300") { series, _, input ->
                NormalizationIndicator(
                    indicator = MinPriceIndicator(series),
                    timeFrame = input[0],
                    maxBound = MaxPriceIndicator(series),
                    minBound = MinPriceIndicator(series))
            },
            IndicatorType("price", "volume", "300") { series, _, input ->
                NormalizationIndicator(OnBalanceVolumeIndicator(series), input[0])
            },
            IndicatorType("pressure", "pressure", "100,2,100") { series, _, input ->
                BuyPressureIndicator(series, input[0], input[1], input[2])
            },
            IndicatorType("pvi", "pvi", "1") { series, _, _ ->
                PriceVariationIndicator(series)
            },
            IndicatorType("bb%", "bb%", "20,2,300") { _, indicator, input ->
                NormalizationIndicator(PercentBIndicatorFixed(indicator, input[0], input[1].toDouble()), input[2])
            },
            IndicatorType("williamsR%", "williamsR%", "14") { s, _, input ->
                MappingIndicator(WilliamsRIndicatorFixed(s, input[0])) { (it + 100.0) / 100.0 }
            },
            IndicatorType("cci", "cci", "20,300") { s, _, input ->
                NormalizationIndicator(CCIIndicator(s, input[0]), input[1])
            },
            IndicatorType("roc", "roc", "9,300") { _, indicator, input ->
                NormalizationIndicator(ROCIndicator(indicator, input[0]), input[1])
            },
            IndicatorType("rsi", "rsi", "14") { _, indicator, input ->
                MappingIndicator(RSIIndicator(indicator, input[0])) { it / 100.0 }
            },
            IndicatorType("macd", "macd", "12,26,300") { _, indicator, input ->
                NormalizationIndicator(MACDIndicator(indicator, input[0], input[1]), input[2])
            },
            IndicatorType("obvo", "obvo", "24,300") { series, _, input ->
                NormalizationIndicator(OBVOscillatorIndicator(series, input[0]), input[1])
            }
        )

        fun getRequiredInput() = FEATURE_TYPES.map { type -> "indicator.${type.name}" to type.defaultValue }

        fun createFromInput(series: TimeSeries, input: Map<String, String>, periodMultiplier: Int): SeriesModel {
            val usedIndicators = mutableListOf<Triple<String, String, Indicator<Num>>>()
            val closeIndicator = ClosePriceIndicator(series)
            for (type in FEATURE_TYPES) {
                val key = "indicator.${type.name}"
                val paramsArray = input.getValue(key).split(",").map { (it.toInt() * periodMultiplier) }
                if (paramsArray[0] == 0) continue // indicator ignored
                val indicator = type.factory(series, closeIndicator, paramsArray)
                usedIndicators.add(Triple(type.group, type.name, indicator))
            }
            return SeriesModel(series, closeIndicator, usedIndicators)
        }
    }

    private var buyModel: MultiLayerNetwork? = null
    private var sellModel: MultiLayerNetwork? = null

    /** Load model */
    fun loadModel(model: String) {
        val buyPath = "data/models/[train]$model-open.h5"
        val sellPath = "data/models/[train]$model-close.h5"
        buyModel = KerasModelImport.importKerasSequentialModelAndWeights(buyPath)
        sellModel = KerasModelImport.importKerasSequentialModelAndWeights(sellPath)
    }

    /** Evaluate model features on the series at [i]. Return a list of pairs of (feature name, feature value). */
    fun evaluateFeatures(i: Int): List<Triple<String, String, Double>> {
        val result = mutableListOf<Triple<String, String, Double>>()
        for ((group, name, indicator) in usedIndicators) {
            result += Triple(group, name, indicator[i])
        }
        return result
    }

    /** Calculate predictions for the tick [i]. */
    fun calculateBuySellPredictions(i: Int): Pair<Double, Double> {
        if (buyModel == null || sellModel == null) error("model not loaded ($buyModel, $sellModel)")
        val timesteps = 7
        val timestepsArray = Array(usedIndicators.size) { indicatorIndex ->
            DoubleArray(timesteps) { index ->
                usedIndicators[indicatorIndex].third[i - (timesteps - index - 1)]
            }
        }
        val data = arrayOf(timestepsArray)
        return Pair(buyModel!!.output(Nd4j.create(data)).getDouble(0), sellModel!!.output(Nd4j.create(data)).getDouble(0))
    }
}