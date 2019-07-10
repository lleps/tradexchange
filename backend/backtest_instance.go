package main

type BacktestInstance struct {
	instance  string
	out       OutputWriter
	state     *InstanceState
	chartData *InstanceChartData
}

func (instance *BacktestInstance) Init() {
}

func (instance *BacktestInstance) Destroy() {
}

func (instance *BacktestInstance) GetRequiredInput() map[string]string {
	return map[string]string{}
}

func (instance *BacktestInstance) Update(ButtonIdx int, input map[string]string) {
}
