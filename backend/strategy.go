package main

// To write to the instance output
type OutputWriter interface {
	Write(message string)
}

type DefaultOutputWriter struct {
	state *InstanceState
}

func (writer *DefaultOutputWriter) Write(message string) {
	writer.state.Output += message
	writer.state.Output += "\n"
}

func NewOutputWriter(state *InstanceState) *DefaultOutputWriter {
	return &DefaultOutputWriter{state}
}

// To write extra points to a candlestick chart
type ChartWriter interface {
	PriceIndicator(seriesName string, epoch int64, value float64)
	ExtraIndicator(chartName string, seriesName string, epoch int64, value float64)
}

// This impl just accumulates the writes
type DefaultChartWriter struct {
	PriceIndicators map[string]SeriesData
	ExtraIndicators map[string]map[string]SeriesData
}

func NewChartWriter() *DefaultChartWriter {
	return &DefaultChartWriter{}
}

func (chartWriter *DefaultChartWriter) PriceIndicator(seriesName string, epoch int64, value float64) {
	seriesMap := chartWriter.PriceIndicators
	if seriesMap == nil {
		seriesMap = make(map[string]SeriesData)
		chartWriter.PriceIndicators = seriesMap
	}
	dataMap, ok := seriesMap[seriesName]
	if !ok {
		dataMap = make(SeriesData)
		seriesMap[seriesName] = dataMap
	}
	dataMap[epoch] = value
}

func (chartWriter *DefaultChartWriter) ExtraIndicator(chartName string, seriesName string, epoch int64, value float64) {
	chartsMap := chartWriter.ExtraIndicators
	if chartsMap == nil {
		chartsMap = make(map[string]map[string]SeriesData)
		chartWriter.ExtraIndicators = chartsMap
	}
	seriesMap, ok := chartsMap[chartName]
	if !ok {
		seriesMap = make(map[string]SeriesData)
		chartsMap[chartName] = seriesMap
	}
	dataMap, ok := seriesMap[seriesName]
	if !ok {
		dataMap = make(SeriesData)
		seriesMap[seriesName] = dataMap
	}
	dataMap[epoch] = value
}

type Strategy struct {
}

type strategyInput struct {
	buyMlValue float32
	sellMlValue float32
}

func parseStrategyInput(input map[string]string) (*strategyInput, error) {

}

func addStrategyInput(state *InstanceState) {
	state.AddInputKey("strategy.buyMlValue", "0.1")
	state.AddInputKey("strategy.sellMlValue", "0.1")
}


