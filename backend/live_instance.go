package main

type LiveInstance struct {
	instance  string
	out       OutputWriter
	state     *InstanceState
	chartData *InstanceChartData
}

func (ins *LiveInstance) init() {
}

func (ins *LiveInstance) destroy() {
}

func (ins *LiveInstance) ensureInput() {
	ins.state.AddInputKey("pair", "USDT_ETH")
	ins.state.AddInputKey("period", "300")
	ins.state.AddInputKey("warmupTicks", "300")
	ins.state.AddInputKey("cooldownTicks", "300")
	ins.state.AddInputKey("initialMoney", "100")
	addFetchTicksInput(ins.state)
	addStrategyInput(ins.state)
}

func (ins *LiveInstance) update(ButtonIdx int, input map[string]string) {
}
