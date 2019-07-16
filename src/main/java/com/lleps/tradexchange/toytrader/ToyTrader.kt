package com.lleps.tradexchange.toytrader

import com.lleps.tradexchange.Candle
import com.lleps.tradexchange.Operation
import com.lleps.tradexchange.OperationType
import com.lleps.tradexchange.client.FullChart
import com.lleps.tradexchange.client.Toast
import com.lleps.tradexchange.server.fetchTicksRequiredInput
import com.lleps.tradexchange.strategy.ChartWriterImpl
import com.lleps.tradexchange.util.get
import com.lleps.tradexchange.util.parseCandlesFrom1MinCSV
import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.StochasticRSIIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator
import org.ta4j.core.num.Num
import kotlin.concurrent.thread

/** Program to play with the market and check how much money you can make,
 * as a human of course.
 * This is, create a chart with indicators, and start advancing time.
 * Add buy and sell buttons. To close an open position, should click on the position.
 * The positions should be a list with entries like '#1 $24 usd (+1.2%)'
 * Also should have a log for transactions, and a variable profit which should be
 * mutated on the trades.
 *
 * chart          profit: $240  coins: 5.2'c
 * chart          start/stop
 * chart          [amount]buy
 * chart          close #1 $21.0 (1.1%)
 * chart
 * indicator1
 * indicator2
 * indicator3
 */
class ToyTrader : Application() {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            launch(ToyTrader::class.java)
        }
    }

    // state
    private var autoUpdateMarket = true
    private var currentEpoch = 0L
    private var currentPrice = 1.0
    private val openPositions = mutableListOf<Pair<Double, Double>>()
    private var currentDollars = 1000.0
    private var currentCoins = 1.0
    private val ops = mutableListOf<Operation>()

    // controls
    private lateinit var stage: Stage
    private lateinit var profitLabel: Label
    private lateinit var startStopButton: Button
    private lateinit var buyField: TextField
    private lateinit var buyButton: Button
    private lateinit var chart: FullChart
    private val closeButtons = FXCollections.observableArrayList<Button>()

    // chart data
    private lateinit var series: TimeSeries
    private var currentTick = 100
    private var window = 50
    private lateinit var closeIndicator: ClosePriceIndicator
    private val priceIndicators = mutableListOf<Pair<String, Indicator<Num>>>()
    private val extraIndicators = mutableListOf<Pair<String, Indicator<Num>>>()

    private fun updateLabel() {
        profitLabel.text = "market $%.2f    usd $%.2f   coins %.2f'c".format(currentPrice, currentDollars, currentCoins)
    }

    private fun updateChart() {
        // build chart
        val candles = mutableListOf<Candle>()
        val chartWriter = ChartWriterImpl()
        for (i in currentTick - window .. currentTick) {
            val bar = series.getBar(i)
            val epoch = bar.endTime.toEpochSecond()
            candles.add(Candle(
                bar.endTime.toEpochSecond(),
                bar.openPrice.doubleValue(),
                bar.closePrice.doubleValue(),
                bar.maxPrice.doubleValue(),
                bar.minPrice.doubleValue()
            ))
            for ((name, ind) in priceIndicators) chartWriter.priceIndicator(name, epoch, ind[i])
            for ((name, ind) in extraIndicators) chartWriter.extraIndicator(name, name, epoch, ind[i])
        }

        currentEpoch = series.getBar(currentTick).endTime.toEpochSecond()

        // paint
        chart.priceData = candles
        chart.priceIndicators = chartWriter.priceIndicators
        chart.extraIndicators = chartWriter.extraIndicators
        chart.operations = ops
        chart.fill()
    }

    private fun updateMarket() {
        currentPrice = closeIndicator[++currentTick]
        updateChart()
        updateLabel()
        for ((idx, data) in openPositions.withIndex()) {
            val (open, coins) = data
            val button = closeButtons[idx]
            val off = currentPrice - open
            button.text = "close #%d at $%.2f (%.2f%s)".format(idx + 1, off * coins, off / open * 100.0, "%")
            button.textFill = if (off < 0) Color.ORANGERED else Color.GREEN
        }
    }

    private fun createPanel(): VBox {
        profitLabel = Label("market $24    $24 5.1'c")
        startStopButton = Button("Stop").apply {
            setOnAction {
                autoUpdateMarket = !autoUpdateMarket
                text = if (autoUpdateMarket) "Stop" else "Start"
            }
        }
        buyField = TextField()
        buyButton = Button("Buy").apply {
            setOnAction {
                val money = buyField.text.toDouble()
                if (money <= 0 || money > currentDollars) {
                    Toast.show("invalid money", stage)
                } else {
                    val coins = money / currentPrice
                    currentCoins += coins
                    currentDollars -= money
                    val pair = currentPrice to coins
                    openPositions.add(pair)
                    println("buy \$$money of coins at $currentPrice")
                    val openPrice = currentPrice
                    var close: Button? = null
                    close = Button("...").apply {
                        setOnAction {
                            val off = currentPrice - openPrice
                            println("close at \$%.3f".format(off))
                            currentDollars += currentPrice * coins
                            currentCoins -= coins
                            openPositions.remove(pair)
                            closeButtons.remove(close)
                            updateLabel()
                            ops.add(Operation(currentEpoch, OperationType.SELL, currentPrice, "sell"))
                        }
                    }
                    ops.add(Operation(currentEpoch, OperationType.BUY, currentPrice, "buy"))
                    closeButtons.add(close)
                }
            }
        }
        return VBox(
            5.0,
            profitLabel,
            startStopButton,
            HBox(5.0, buyField, buyButton),
            VBox().apply {
                closeButtons.addListener { _: ListChangeListener.Change<out Button> ->
                    this.children.setAll(closeButtons)
                }
            }
        )
    }

    // TODO normalize the price in the model using max-min

    // como hacerlo.
    // 1. periodo ie 300 (3d). buscar, de todos los elementos ahi, el max y el min
    // 2. para cada valor, restarle min y dividirlo por max-min.
    // ej, min 5 max 200.
    // value 10. seria (10-5)/(200-5) = 0.02.
    // value 110. (110-5)/(200-5) = 0.53

    // entonces para crearlo, puedo...
    // 1. no puede estar normalizado.
    // SpecialNormalizer(indicatorToNormalize, vararg otherIndicatorsToCalculateMinMax)
    // so... I could first of all, create all the indicators as-is
    // then, assign the "normalizer" to "standalone" or "groupNormalizer" (NOT_NORMALIZED, OWN_NORMALIZER, GROUP_NORMALIZED)
    // then, you will create a GroupNormalizer.Builder. And add indicators to it.
    // then, you end up doing GroupNormalizer(indicator, GroupNormalizer.Builder)

    private fun initSeries() {
        println("loading csv...")
        val data = parseCandlesFrom1MinCSV("../Bitfinex-historical-data/ETHUSD/Candles_1m/2019/merged.csv", 900)
        series = BaseTimeSeries(data)
        println("loaded.")
        closeIndicator = ClosePriceIndicator(series)
        priceIndicators.add(Pair("emaShort", EMAIndicator(closeIndicator, 26)))
        priceIndicators.add(Pair("emaLong", EMAIndicator(closeIndicator, 80)))
        priceIndicators.add(Pair("emaLongLong", EMAIndicator(closeIndicator, 300)))
        extraIndicators.add(Pair("stoch", StochasticRSIIndicator(closeIndicator, 14)))
        extraIndicators.add(Pair("macd", MACDIndicator(closeIndicator, 12, 26)))
        extraIndicators.add(Pair("obv", OnBalanceVolumeIndicator(series)))
    }

    override fun start(stage: Stage) {
        thread {
            while (true) {
                if (::series.isInitialized && autoUpdateMarket) {
                    Platform.runLater { updateMarket() }
                }
                Thread.sleep(1000)
            }
        }
        chart = FullChart()
        initSeries()
        val root = HBox(chart, createPanel())
        root.setOnKeyPressed {
            if (it.code == KeyCode.RIGHT) {
                updateMarket()
            }
        }
        stage.scene = Scene(root)
        this.stage = stage
        stage.show()
    }
}