package main

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
}
