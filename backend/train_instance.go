package main

type TrainInstance struct {
	instance  string
	out       OutputWriter
	state     *InstanceState
	chartData *InstanceChartData
}

func (instance *TrainInstance) Init() {
}

func (instance *TrainInstance) Destroy() {
}

func (instance *TrainInstance) GetRequiredInput() map[string]string {
	return map[string]string{}
}

func (instance *TrainInstance) Update(ButtonIdx int, input map[string]string) {

}
