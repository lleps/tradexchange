package com.lleps.tradexchange.strategy

import com.lleps.tradexchange.OperationType
import com.lleps.tradexchange.indicator.*
import com.lleps.tradexchange.util.get
import com.lleps.tradexchange.util.loadFrom
import com.lleps.tradexchange.util.saveTo
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
 * The point of this is to group model-related behavior and data, like feature gathering (evaluating
 * a bunch of indicators), and interacting with deeplearning4j to load keras models and predict with them.
 * This is stateful, may have a model loaded for predictions but can be used without it (for example, on training
 * you don't need to do predictions, just the features).
 */
class PredictionModel private constructor(
    private val input: Map<String, String>,
    private val buyIndicators: List<Triple<String, String, Indicator<Num>>>,
    private val sellIndicators: List<Triple<String, String, Indicator<Num>>>
){
    private class IndicatorType(
        val group: String, // to group them in charts
        val name: String,
        val defaultValue: String,
        val periodMultiplied: Boolean, // for indicators that may have multiple "instances" with different multipliers
        val factory: (TimeSeries, Indicator<Num>, List<Int>) -> Indicator<Num>
    )

    private data class ModelMetadata(
        val input: Map<String, String> = emptyMap()
    )

    companion object {
        private val FEATURE_TYPES = listOf(
            // open-high-low-close
            IndicatorType("price", "close", "300", false) { series, _, input ->
                NormalizationIndicator(
                    indicator = ClosePriceIndicator(series),
                    timeFrame = input[0],
                    maxBound = MaxPriceIndicator(series),
                    minBound = MinPriceIndicator(series))
            },
            IndicatorType("price", "open", "300", false) { series, _, input ->
                NormalizationIndicator(
                    indicator = OpenPriceIndicator(series),
                    timeFrame = input[0],
                    maxBound = MaxPriceIndicator(series),
                    minBound = MinPriceIndicator(series))
            },
            IndicatorType("price", "high", "300", false) { series, _, input ->
                NormalizationIndicator(
                    indicator = MaxPriceIndicator(series),
                    timeFrame = input[0],
                    maxBound = MaxPriceIndicator(series),
                    minBound = MinPriceIndicator(series))
            },
            IndicatorType("price", "low", "300", false) { series, _, input ->
                NormalizationIndicator(
                    indicator = MinPriceIndicator(series),
                    timeFrame = input[0],
                    maxBound = MaxPriceIndicator(series),
                    minBound = MinPriceIndicator(series))
            },
            // raw volume (but keeping in mind the sign)
            IndicatorType("price", "volume", "300", false) { series, _, input ->
                NormalizationIndicator(OnBalanceVolumeIndicator(series), input[0])
            },
            // pressure (ie time since last trade)
            IndicatorType("pressure", "buyPressure", "100,2,300", false) { series, _, input ->
                BuyPressureIndicator(series, input[0], input[1], input[2])
            },
            IndicatorType("pressure", "sellPressure", "100", false) { series, _, input ->
                SellPressureIndicator(series, input[0])
            },
            // this one is the ratio of change, not needed anymore since we have candles now
            IndicatorType("pvi", "pvi", "1", false) { series, _, _ ->
                PriceVariationIndicator(series)
            },
            // technical indicators. those can be period-multiplied
            IndicatorType("bb%", "bb%", "20,2,300", true) { _, indicator, input ->
                NormalizationIndicator(PercentBIndicatorFixed(indicator, input[0], input[1].toDouble()), input[2])
            },
            IndicatorType("williamsR%", "williamsR%", "14", true) { s, _, input ->
                MappingIndicator(WilliamsRIndicatorFixed(s, input[0])) { (it + 100.0) / 100.0 }
            },
            IndicatorType("cci", "cci", "20,300", true) { s, _, input ->
                NormalizationIndicator(CCIIndicator(s, input[0]), input[1])
            },
            IndicatorType("roc", "roc", "9,300", true) { _, indicator, input ->
                NormalizationIndicator(ROCIndicator(indicator, input[0]), input[1])
            },
            IndicatorType("rsi", "rsi", "14", true) { _, indicator, input ->
                MappingIndicator(RSIIndicator(indicator, input[0])) { it / 100.0 }
            },
            IndicatorType("macd", "macd", "12,26,300", true) { _, indicator, input ->
                NormalizationIndicator(MACDIndicator(indicator, input[0], input[1]), input[2])
            },
            IndicatorType("obvo", "obvo", "24,300", true) { series, _, input ->
                NormalizationIndicator(OBVOscillatorIndicator(series, input[0]), input[1])
            }
        )

        fun getRequiredInput(): Map<String, String> {
            return mapOf("model.buy.periodMultipliers" to "1") +
                FEATURE_TYPES.map { type -> "model.buy.${type.name}" to type.defaultValue } +
                mapOf("model.sell.periodMultipliers" to "1") +
                FEATURE_TYPES.map { type -> "model.sell.${type.name}" to type.defaultValue }
        }

        /** Creates a model from the given [series] amd input from [name]. */
        fun createFromFile(series: TimeSeries, name: String): PredictionModel {
            val path = "data/models/$name-metadata.json"
            val meta = loadFrom<ModelMetadata>(path) ?: error("can't find file '$path'")
            return createModel(series, meta.input)
        }

        private fun parseIndicators(
            input: Map<String, String>,
            series: TimeSeries,
            closeIndicator: ClosePriceIndicator,
            prefix: String
        ): List<Triple<String, String, Indicator<Num>>> {
            val multipliers = input.getValue("$prefix.periodMultipliers").split(",").map { it.toInt() }
            val mainMultiplier = multipliers[0]
            val result = mutableListOf<Triple<String, String, Indicator<Num>>>()
            for (type in FEATURE_TYPES) {
                val key = "$prefix.${type.name}"
                if (type.periodMultiplied) {
                    // add an instance for every multiplier. Series named name(*multiplier), ie rsi(*1) and rsi(*4)
                    // but the group remains the same so it can be drawn on the same chart.
                    for (m in multipliers) {
                        val paramsArray = input.getValue(key).split(",").map { (it.toInt() * m) }
                        if (paramsArray[0] == 0) continue // indicator ignored
                        val indicator = type.factory(series, closeIndicator, paramsArray)
                        result.add(Triple(type.group, "${type.name}(*$m)", indicator))
                    }
                } else {
                    // add just one instance with the first multiplier. Group named as the default group.
                    val paramsArray = input.getValue(key).split(",").map { (it.toInt() * mainMultiplier) }
                    if (paramsArray[0] == 0) continue // indicator ignored
                    val indicator = type.factory(series, closeIndicator, paramsArray)
                    result.add(Triple(type.group, type.name, indicator))
                }
            }
            return result
        }

        /** Creates a model from the given [series] and [input]. */
        fun createModel(
            series: TimeSeries,
            input: Map<String, String>
        ): PredictionModel {
            val closeIndicator = ClosePriceIndicator(series)
            val buyIndicators = parseIndicators(input, series, closeIndicator, "model.buy")
            val sellIndicators = parseIndicators(input, series, closeIndicator, "model.sell")
            return PredictionModel(
                input.toMap(),
                buyIndicators,
                sellIndicators)
        }
    }

    /** Saves the input used in this model (ie the configuration) as [name] */
    fun saveMetadata(name: String) {
        val metadata = ModelMetadata(input.filter { it.key.startsWith("model.") })
        metadata.saveTo("data/models/$name-metadata.json")
    }

    /**
     * Draw used features of the [type] model at tick [i] on the given [chartWriter].
     * Set [limit] if you want to limit the amount of groups drawn.
     */
    fun drawFeatures(type: OperationType, i: Int, epoch: Long, chartWriter: Strategy.ChartWriter, limit: Int = 0) {
        val indicators = if (type == OperationType.BUY) buyIndicators else sellIndicators
        var count = 0
        var lastGroup = ""
        for ((group, name, indicator) in indicators) {
            chartWriter.extraIndicator(group, name, epoch, indicator[i])
            if (group != lastGroup && limit > 0 && ++count >= limit) break
            lastGroup = group
        }
    }

    // Prediction-related. Wraps ml code here, not just the feature group.

    private var buyModel: MultiLayerNetwork? = null
    private var sellModel: MultiLayerNetwork? = null

    /** Set the model used in [calculateBuySellPredictions]. */
    fun loadPredictionsModel(model: String) {
        val buyPath = "data/models/[train]$model-open.h5"
        val sellPath = "data/models/[train]$model-close.h5"
        buyModel = KerasModelImport.importKerasSequentialModelAndWeights(buyPath)
        sellModel = KerasModelImport.importKerasSequentialModelAndWeights(sellPath)
    }

    /** Calculate predictions for the tick [i]. */
    fun calculateBuySellPredictions(i: Int): Pair<Double, Double> {
        if (buyModel == null || sellModel == null) error("model not loaded ($buyModel, $sellModel)")
        val timesteps = 7
        val timestepsArray = Array(buyIndicators.size) { indicatorIndex ->
            DoubleArray(timesteps) { index ->
                buyIndicators[indicatorIndex].third[i - (timesteps - index - 1)]
            }
        }
        val data = arrayOf(timestepsArray)
        return Pair(buyModel!!.output(Nd4j.create(data)).getDouble(0), sellModel!!.output(Nd4j.create(data)).getDouble(0))
    }
}