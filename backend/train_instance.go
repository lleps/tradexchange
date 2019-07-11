package main

type TrainInstance struct {
	instance  string
	out       OutputWriter
	state     *InstanceState
	chartData *InstanceChartData
}

func (ins *TrainInstance) init() {
}

func (ins *TrainInstance) destroy() {
}

func (ins *TrainInstance) ensureInput() {
	ins.state.AddInputKey("pair", "USDT_ETH")
	ins.state.AddInputKey("period", "300")
	ins.state.AddInputKey("warmupTicks", "300")
	ins.state.AddInputKey("cooldownTicks", "300")
	ins.state.AddInputKey("initialMoney", "100")
	addFetchTicksInput(ins.state)
}

func (ins *TrainInstance) update(ButtonIdx int, input map[string]string) {

}
