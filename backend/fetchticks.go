package main

import (
	"bufio"
	"fmt"
	"github.com/sdcoffey/big"
	"github.com/sdcoffey/techan"
	"os"
	"strconv"
	"strings"
	"time"
)

func addFetchTicksInput(state *InstanceState) {
	state.AddInputKey("ticks.csv.file", "../Bitfinex-historical-data/ETHUSD/Candles_1m/2019/merged.csv")
	state.AddInputKey("ticks.csv.startDate", "2019-01-01")
	state.AddInputKey("ticks.csv.endDate", "2019-01-04")
}

// To parse directly from an instance state's input
func parseSeriesFromInput(period int, input map[string]string) (*techan.TimeSeries, error) {
	path := input["ticks.csv.file"]
	startDate, err := time.Parse("2006-01-02", input["ticks.csv.startDate"])
	if err != nil {
		return nil, err
	}
	endDate, err := time.Parse("2006-01-02", input["ticks.csv.startDate"])
	if err != nil {
		return nil, err
	}
	return parseSeriesFromCSV(path, period, startDate, endDate)
}

// Get from path the data since start, up to end.
// Expects that the csv has a 60-sec candle, to join
// candles as necessary based on periodSeconds.
// The csv must be epochMilli,open,close,high,low,volume (6 fields)
func parseSeriesFromCSV(
	path string,
	periodSeconds int,
	start time.Time,
	end time.Time,
) (*techan.TimeSeries, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	series := techan.NewTimeSeries()
	scanner := bufio.NewScanner(file)
	firstLine := true
	for scanner.Scan() {
		line := scanner.Text()
		if firstLine { // ignore the header
			firstLine = false
			continue
		}

		// Parse line. format: epochMilli,open,close,high,low,volume (6)
		fields := strings.Split(line, ",")
		if len(fields) != 6 {
			return nil, fmt.Errorf("invalid line fields: %d (expected 6)", len(fields))
		}
		epoch, err0 := strconv.ParseInt(fields[0], 10, 64)
		open, err1 := strconv.ParseFloat(fields[1], 64)
		closePrice, err2 := strconv.ParseFloat(fields[2], 64)
		high, err3 := strconv.ParseFloat(fields[3], 64)
		low, err4 := strconv.ParseFloat(fields[4], 64)
		volume, err5 := strconv.ParseFloat(fields[5], 64)
		if err0 != nil || err1 != nil || err2 != nil || err3 != nil || err4 != nil || err5 != nil {
			return nil, fmt.Errorf("line '%s': (%s,%s,%s,%s,%s,%s)", line, err0, err1, err2, err3, err4, err5)
		}

		// check for time limits
		candleTime := time.Unix(epoch/1000, 0)
		if candleTime.Before(start) {
			continue
		} else if candleTime.After(end) {
			break
		}

		// build the candle
		period := techan.NewTimePeriod(candleTime, time.Second*time.Duration(periodSeconds))
		candle := techan.NewCandle(period)
		candle.OpenPrice = big.NewDecimal(open)
		candle.ClosePrice = big.NewDecimal(closePrice)
		candle.MaxPrice = big.NewDecimal(high)
		candle.MinPrice = big.NewDecimal(low)
		candle.Volume = big.NewDecimal(volume)
		series.AddCandle(candle)
	}

	if err := scanner.Err(); err != nil {
		return nil, err
	}

	return series, nil
}
