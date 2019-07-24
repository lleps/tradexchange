package main

import (
	"fmt"
	"strconv"
)

type BacktestInstance struct {
	instance  string
	out       OutputWriter
	state     *InstanceState
	chartData *InstanceChartData
}

func (ins *BacktestInstance) init() {
	ins.state.Action1 = "Run"
}

func (ins *BacktestInstance) destroy() {
}

func (ins *BacktestInstance) ensureInput() {
	ins.state.AddInputKey("pair", "USDT_ETH")
	ins.state.AddInputKey("period", "300")
	ins.state.AddInputKey("warmupTicks", "300")
	ins.state.AddInputKey("cooldownTicks", "300")
	ins.state.AddInputKey("initialMoney", "100")
	addFetchTicksInput(ins.state)
	addStrategyInput(ins.state)
}

func (ins *BacktestInstance) update(ButtonIdx int, input map[string]string) {
	go func() {
		if err := doBacktest(ins.state, ins.out, input); err != nil {
			ins.out.Write(fmt.Sprintf("Error: %s", err))
		}
	}()
}

type backtestInput struct {
	pair string
	period int
	warmupTicks int
}

func parseInt(s string, dst *int) error {
	result, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return err
	}
	*dst = int(result)
	return nil
}

func parseBacktestInput(input map[string]string) (*backtestInput, error) {
	var result backtestInput
	var err error
	result.pair = input["pair"]
	if err = parseInt(input["period"], &result.period); err != nil {
		return nil, err
	}
	if err = parseInt(input["warmupTicks"], &result.warmupTicks); err != nil {
		return nil, err
	}
	return &result, nil
}

func doBacktest(state *InstanceState, out OutputWriter, input map[string]string) error {
	period, err := strconv.ParseInt(input["period"], 10, 64)
	if err != nil {
		return err
	}
	_, err = strconv.ParseInt(input["warmupTicks"], 10, 32)
	if err != nil {
		return err
	}
	out.Write("Reading series...")
	series, err := parseSeriesFromInput(int(period), input)
	if err != nil {
		return err
	}
	out.Write(fmt.Sprint(len(series.Candles), "ticks loaded."))

	/*
		pseudo code for run:

			model := &Model{}
			exchange := &TestExchange{}
			strategy := &Strategy{exchange}
			chartWriter := NewChartWriter()
			candles := make([]Candle, 10)
			ops := make([]Operation, 10)
			for i, max := int(warmupTicks), series.LastIndex(); i <= max; i++ {
				results := strategy.doTick(i, chartWriter)
				if len(results) > 0 {
					for op := range results {
						ops = append(ops, op)
						if op.Kind == "sell" {
							// register full trade
						}
					}
				}
			}
	*/
	return nil
}
