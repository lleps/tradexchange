package com.lleps.tradexchange.strategy

import com.lleps.tradexchange.OperationType
import com.lleps.tradexchange.indicator.*
import com.lleps.tradexchange.util.get
import com.lleps.tradexchange.util.loadFrom
import com.lleps.tradexchange.util.saveTo
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.*
import org.ta4j.core.indicators.helpers.*
import org.ta4j.core.indicators.volume.ChaikinMoneyFlowIndicator
import org.ta4j.core.indicators.volume.ChaikinOscillatorIndicator
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
    private val sellIndicators: List<Triple<String, String, Indicator<Num>>>,
    private val timesteps: Int
){
    private class IndicatorType(
        val group: String, // to group them in charts
        val name: String,
        val defaultValue: String,
        val type: OperationType?,
        val normalizationType: NormalizationType,
        val factory: (TimeSeries, Indicator<Num>, List<InputEntry>) -> Indicator<Num>
    )

    private enum class NormalizationType {
        /** Do not normalize at all (for those with built-in normalization) */
        NONE,
        /** Will use min,max to normalize based on the indicator being normalized. */
        STANDALONE,
        /** Will use min,max to normalize based on all the indicators that share this normalization type. */
        GROUP
    }

    /** Used to pass as parameters to indicator builders. Can be read as a series, or int (periods). */
    private class InputEntry(
        val indicators: Map<String, Indicator<Num>>,
        val input: Map<String, String>,
        val multiplier: Int,
        val entryContent: String
    ) {
        fun value(): Int {
            if (entryContent.startsWith("$")) {
                return input[entryContent.substring(1)]?.toInt()?.times(multiplier)
                    ?: error("var not found in input: ${entryContent.substring(1)}")
            }
            return entryContent.toInt() * multiplier
        }

        fun indicator() = indicators.getValue(entryContent)
    }

    private data class ModelMetadata(
        val input: Map<String, String> = emptyMap()
    )

    // TODO usar keys aca en indicadores, para performance y simplicidad.
    // ej, "macd" quiero tambien la signal y el histograma. Pero tengo
    // q calcularlo.
    // o para minimizar el max y min, todos se basarian en eso.
    // estaria bueno q en los parametros se puedan poner otros indicadores.

    companion object {
        private val FEATURE_TYPES = listOf(

            // All the in-price indicators.
            // Normalized in group.
            // They all share the same "price" chart

            IndicatorType("price", "close", "", null, NormalizationType.GROUP) { series, _, _ ->
                ClosePriceIndicator(series)
            },
            IndicatorType("price", "open", "", null, NormalizationType.GROUP) { series, _, _ ->
                OpenPriceIndicator(series)
            },
            IndicatorType("price", "high", "", null, NormalizationType.GROUP) { series, _, _ ->
                MaxPriceIndicator(series)
            },
            IndicatorType("price", "low", "", null, NormalizationType.GROUP) { series, _, _ ->
                MinPriceIndicator(series)
            },
            IndicatorType("price", "ema", "12*1,4", null, NormalizationType.GROUP) { _, indicator, input ->
                EMAIndicator(indicator, input[0].value())
            },

            // Past-trade indicators.
            // Those are the only specific to one op type. None normalized
            // Also those are non-deterministic, non-cacheable, since they all depends on the
            // state of the bars (ie buy/sell/none), which is mutable.

            IndicatorType("pressure", "buyPressure", "100,2,\$warmupTicks", OperationType.BUY, NormalizationType.NONE) { series, _, input ->
                BuyPressureIndicator(series, input[0].value(), input[1].value(), input[2].value())
            },
            IndicatorType("pressure", "sellPressure", "100", OperationType.SELL, NormalizationType.NONE) { series, _, input ->
                SellPressureIndicator(series, input[0].value())
            },
            IndicatorType("pct", "pct", "5", OperationType.SELL, NormalizationType.NONE) { series, _, input ->
                NormalizedBuyPercentChangeIndicator(series, input[0].value().toDouble())
            },

            // Standalone indicators. Drawn on their own charts.
            // Those are all decoupled from each other, so can be normalized
            // with the default (val-min)/(max-min).

            // this one is to get the color and size of the bar as a separate feature. Might be useful for the model
            IndicatorType("color", "color", "", null, NormalizationType.STANDALONE) { series, _, _ ->
                CandleColorIndicator(series)
            },
            // volatility indicators
            IndicatorType("bb%", "bb%", "20,2", null, NormalizationType.STANDALONE) { _, indicator, input ->
                PercentBIndicatorFixed(indicator, input[0].value(), input[1].value().toDouble())
            },
            // momentum indicators
            IndicatorType("williamsR%", "williamsR%", "14", null, NormalizationType.STANDALONE) { s, _, input ->
                WilliamsRIndicatorFixed(s, input[0].value())
            },
            IndicatorType("cci", "cci", "20", null, NormalizationType.STANDALONE) { s, _, input ->
                CCIIndicator(s, input[0].value())
            },
            IndicatorType("roc", "roc", "9", null, NormalizationType.STANDALONE) { _, indicator, input ->
                ROCIndicator(indicator, input[0].value())
            },
            IndicatorType("rsi", "rsi", "14", null, NormalizationType.STANDALONE) { _, indicator, input ->
                RSIIndicator(indicator, input[0].value())
            },
            // trending indicators
            IndicatorType("macd", "macd", "12,26", null, NormalizationType.STANDALONE) { _, indicator, input ->
                MACDIndicator(indicator, input[0].value(), input[1].value())
            },
            IndicatorType("macd", "signal", "macd1,9", null, NormalizationType.STANDALONE) { _, _, input ->
                EMAIndicator(input[0].indicator(), input[1].value())
            },
            IndicatorType("macd", "histogram", "macd1,signal", null, NormalizationType.STANDALONE) { _, _, input ->
                CompositeIndicator(input[0].indicator(), input[1].indicator()) { macd, signal -> macd - signal }
            },
            // volume indicators
            IndicatorType("obv", "obv", "", null, NormalizationType.STANDALONE) { series, _, _ ->
                OnBalanceVolumeIndicator(series)
            },
            IndicatorType("obvo", "obvo", "24", null, NormalizationType.STANDALONE) { series, _, input ->
                OBVOscillatorIndicator(series, input[0].value())
            },
            IndicatorType("cmf", "cmf", "20", null, NormalizationType.STANDALONE) { series, _, input ->
                ChaikinMoneyFlowIndicator(series, input[0].value())
            },
            IndicatorType("co", "co", "3,10", null, NormalizationType.STANDALONE) { series, _, input ->
                ChaikinOscillatorIndicator(series, input[0].value(), input[1].value())
            }
        )

        fun getRequiredInput(): Map<String, String> {
            return mapOf("model.buy.normalizationPeriod" to "300") +
                FEATURE_TYPES
                    .filter { type -> type.type == null || type.type == OperationType.BUY }
                    .map { type -> "model.buy.${type.name}" to type.defaultValue } +

                mapOf("model.sell.normalizationPeriod" to "300") +
                FEATURE_TYPES
                    .filter { type -> type.type == null || type.type == OperationType.SELL }
                    .map { type -> "model.sell.${type.name}" to type.defaultValue }
        }

        /** Creates a model from the given [series] amd input from [name]. */
        fun createFromFile(series: TimeSeries, name: String): PredictionModel {
            val path = "data/models/$name-metadata.json"
            val meta = loadFrom<ModelMetadata>(path) ?: error("can't find file '$path'")
            return createModel(series, meta.input)
        }

        private fun parseIndicators(
            input: Map<String, String>,
            opType: OperationType,
            series: TimeSeries,
            closeIndicator: ClosePriceIndicator,
            prefix: String
        ): List<Triple<String, String, Indicator<Num>>> {
            // TODO what to do with "period multipliers"? still worth it?
            val normalizationPeriod = input.getValue("$prefix.normalizationPeriod").toInt()
            val result = mutableListOf<Triple<String, String, Indicator<Num>>>()
            val indicators = mutableMapOf<String, Indicator<Num>>()
            val groupNormalizerBuilder = GroupNormalizer.Builder()
            for (type in FEATURE_TYPES.filter { it.type == null || it.type == opType }) {
                val key = "$prefix.${type.name}"
                val wholeValue = input.getValue(key)
                if (wholeValue.startsWith("#")) continue

                // input format: period1,period2*multiplier1,multiplier2.
                // multipliers are optional. no multiplier specified is equal to *1
                // ie, rsi value may be "14*1,2" for two rsi indicators, one rsi(14) and other rsi(28).
                // For references, indicators are named name{index}, ie on this case "rsi1" and "rsi2".
                val fields = wholeValue.split("*")
                val multipliers = if (fields.size > 1) {
                    fields[1].split(",").map { it.toInt() }
                } else {
                    listOf(1)
                }
                val value = fields[0]

                for ((idx, m) in multipliers.withIndex()) {
                    val paramsArray = value.split(",").map { InputEntry(indicators, input, m, it) }
                    val indicator = type.factory(series, closeIndicator, paramsArray)
                    indicators[type.name] = indicator

                    // TODO maybe pass the period directly to the builder. may be cached and be faster.
                    val finalIndicator = when (type.normalizationType) {
                        NormalizationType.NONE -> indicator
                        NormalizationType.STANDALONE -> NormalizationIndicator(indicator, normalizationPeriod)
                        NormalizationType.GROUP -> {
                            groupNormalizerBuilder.addIndicator(indicator)
                            GroupNormalizer(indicator, groupNormalizerBuilder, normalizationPeriod)
                        }
                    }
                    result.add(Triple(type.group, type.name + (idx + 1), finalIndicator))
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
            val buyIndicators = parseIndicators(input, OperationType.BUY, series, closeIndicator, "model.buy")
            val sellIndicators = parseIndicators(input, OperationType.SELL, series, closeIndicator, "model.sell")
            val timesteps = input.getValue("trainTimesteps").toInt()
            return PredictionModel(
                input.toMap(),
                buyIndicators,
                sellIndicators,
                timesteps)
        }
    }

    /** Saves the input used in this model (ie the configuration) as [name] */
    fun saveMetadata(name: String) {
        val metadata = ModelMetadata(input)
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

    // Predictions

    private val mlClient = WsPredictionClient.getOrCreate()

    /** Set the model used in [predictBuy]. */
    fun loadBuyModel(name: String) {
        val buyPath = "./data/models/$name-open.h5"
        mlClient.requestLoadBuyModel(buyPath)
    }

    /** Set the model used in [predictSell]. */
    fun loadSellModel(name: String) {
        val sellPath = "./data/models/$name-close.h5"
        mlClient.requestLoadSellModel(sellPath)
    }

    /** Calculate sell prediction for the tick [i] and a buy at tick [buyTick] */
    fun predictSell(buyTick: Int, i: Int): Double {
        // set on the sell indicators the buy tick
        for (indicator in sellIndicators) {
            if (indicator is SellIndicator) indicator.buyTick = buyTick
        }

        return predict(i, buy = false, indicators = sellIndicators)
    }

    /** Calculate global buy prediction for the tick [i]. */
    fun predictBuy(i: Int): Double {
        return predict(i, buy = true, indicators = buyIndicators)
    }

    private fun predict(
        i: Int,
        buy: Boolean = false,
        indicators: List<Triple<String, String, Indicator<Num>>>
    ): Double {
        val timestepsArray = Array(timesteps) { index ->
            DoubleArray(indicators.size) { indicatorIndex ->
                indicators[indicatorIndex].third[i - (timesteps - index - 1)]
            }
        }

        return if (buy) {
            mlClient.requestBuyPrediction(timestepsArray)
        } else {
            mlClient.requestSellPrediction(timestepsArray)
        }
    }
}