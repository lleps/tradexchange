package main

type LiveInstance struct {
	instance  string
	out       OutputWriter
	state     *InstanceState
	chartData *InstanceChartData
}

func (instance *LiveInstance) Init() {
}

func (instance *LiveInstance) Destroy() {
}

func (instance *LiveInstance) GetRequiredInput() map[string]string {
	return map[string]string{}
}

func (instance *LiveInstance) Update(ButtonIdx int, input map[string]string) {
}
