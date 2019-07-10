package main

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/gorilla/mux"
	"io/ioutil"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
)

// To delegate event handling to each instance type separately
type InstanceController interface {
	Init()
	Destroy()
	GetRequiredInput() map[string]string
	Update(ButtonIdx int, input map[string]string)
}

var instances []string
var instanceController = make(map[string]InstanceController)
var instanceState = make(map[string]*InstanceState)
var instanceChartData = make(map[string]*InstanceChartData)

func main() {
	// Make data paths
	allPaths := []string{"data/instances", "data/models", "data/trainings"}
	for _, p := range allPaths {
		if err := os.MkdirAll(p, os.ModePerm); err != nil {
			log.Fatal("can't create directory:", p, "err:", err)
		}
	}
	// load instance names.  Instance state, chart data and controllers are lazily loaded.
	path := "data/instances/list.json"
	if err := loadJsonFromFile(path, &instances); err != nil {
		if err = saveJsonToFile(path, []string{}); err != nil {
			log.Fatal("can't populate empty json:", err)
		} else {
			log.Fatal("can't load instance list:", err, "(populated an empty json list)")
		}
		return
	}

	// register router
	r := mux.NewRouter()
	r.HandleFunc("/instances", GetInstances).Methods("GET")
	r.HandleFunc("/instanceState/{instance}", GetInstanceState).Methods("GET")
	r.HandleFunc("/instanceChartData/{instance}", GetInstanceChartData).Methods("GET")
	r.HandleFunc("/updateInput/{instance}/{button}", UpdateInput).Methods("POST")
	r.HandleFunc("/createInstance/{instanceQuery}", CreateInstance).Methods("PUT")
	r.HandleFunc("/deleteInstance/{instance}", DeleteInstance).Methods("DELETE")
	r.HandleFunc("/getInstanceVersion/{instance}", GetInstanceVersion).Methods("GET")
	http.Handle("/", r)
	log.Fatal(http.ListenAndServe(":8081", nil))
}

func loadJsonFromFile(path string, dst interface{}) error {
	data, err := ioutil.ReadFile(path)
	if err != nil {
		return err
	}
	err = json.Unmarshal(data, dst)
	if err != nil {
		return err
	}
	return nil
}

func saveJsonToFile(path string, v interface{}) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	err = ioutil.WriteFile(path, data, 0644)
	if err != nil {
		return err
	}
	return nil
}

// Checks for the given instance name.
// If not found in memory, try to load from disk.
func resolveInstance(name string) (InstanceController, *InstanceState, *InstanceChartData, error) {
	state, ok1 := instanceState[name]
	chartData, ok2 := instanceChartData[name]
	controller, _ := instanceController[name]
	if !ok1 || !ok2 {
		// load both state and chart data from disk
		var state2 InstanceState
		var chartData2 InstanceChartData
		err := loadJsonFromFile(fmt.Sprintf("data/instances/state-%s.json", name), &state2)
		if err != nil {
			return nil, nil, nil, err
		}
		err = loadJsonFromFile(fmt.Sprintf("data/instances/chartData-%s.json", name), &chartData2)
		if err != nil {
			return nil, nil, nil, err
		}
		// register
		state = &state2
		chartData = &chartData2
		controller = newInstanceController(name, state, chartData)
		controller.Init()
		instanceController[name] = controller
		instanceState[name] = state
		instanceChartData[name] = chartData
	}
	return controller, state, chartData, nil
}

func GetInstances(w http.ResponseWriter, r *http.Request) {
	respond(w, instances, "json")
}

func GetInstanceState(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	_, state, _, err := resolveInstance(vars["instance"])
	if checkError(err, w) {
		return
	}

	respond(w, state, "json")
}

func UpdateInput(w http.ResponseWriter, r *http.Request) {
	// parse url paths
	vars := mux.Vars(r)
	controller, _, _, err := resolveInstance(vars["instance"])
	if checkError(err, w) {
		return
	}
	button, err := strconv.ParseInt(vars["button"], 10, 32)
	if checkError(err, w) {
		return
	}
	// parse body. Body contains a map with the user input as json
	var bodyMap map[string]string
	if checkError(json.NewDecoder(r.Body).Decode(&bodyMap), w) {
		return
	}
	log.Println(bodyMap)
	controller.Update(int(button), bodyMap)
	respond(w, "", "raw")
}

func GetInstanceChartData(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	_, _, chartData, err := resolveInstance(vars["instance"])
	if checkError(err, w) {
		return
	}

	respond(w, chartData, "json-gzip")
}

func CreateInstance(w http.ResponseWriter, r *http.Request) {
	// parse query (in the form kind:name)
	query := mux.Vars(r)["instanceQuery"]
	fields := strings.Split(query, ":")
	if len(fields) != 2 {
		checkError(fmt.Errorf("fields must be length 2, is %d", len(fields)), w)
		return
	}
	kind, name := fields[0], fields[1]
	if kind != "train" && kind != "backtest" && kind != "live" {
		checkError(fmt.Errorf("given type must be 'train', backtest' or 'live'. is '%s'", kind), w)
		return
	}

	// ensure name is free
	fullName := fmt.Sprintf("[%s]%s", kind, name)
	for _, v := range instances {
		if v == fullName {
			checkError(fmt.Errorf("instance name '%s' already exists", fullName), w)
			return
		}
	}
	instances = append(instances, fullName)

	// add to the instances list but only save the files for chart data and state,
	// so the instance will be lazily loaded on their next request, as always.
	if checkError(saveJsonToFile("data/instances/list.json", instances), w) {
		return
	}
	state := &InstanceState{Kind: kind}
	if checkError(saveJsonToFile(fmt.Sprintf("data/instances/state-%s.json", fullName), state), w) {
		return
	}
	chartData := &InstanceChartData{}
	if checkError(saveJsonToFile(fmt.Sprintf("data/instances/chartData-%s.json", fullName), chartData), w) {
		return
	}

	respond(w, fullName, "raw")
}

func DeleteInstance(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	instanceName := vars["instance"]
	controller, _, _, err := resolveInstance(instanceName)
	if checkError(err, w) {
		return
	}

	// destroy the controller and map keys
	controller.Destroy()
	delete(instanceController, instanceName)
	delete(instanceState, instanceName)
	delete(instanceChartData, instanceName)

	// remove from the slice
	for idx, name := range instances {
		if name == instanceName {
			instances = append(instances[:idx], instances[idx+1:]...)
		}
	}
	if checkError(saveJsonToFile("data/instances/list.json", instances), w) {
		return
	}

	// remove files
	if checkError(os.Remove(fmt.Sprintf("data/instances/state-%s.json", instanceName)), w) ||
		checkError(os.Remove(fmt.Sprintf("data/instances/chartData-%s.json", instanceName)), w) {
		return
	}

	respond(w, "", "raw")
}

func GetInstanceVersion(w http.ResponseWriter, r *http.Request) {
	_, state, _, err := resolveInstance(mux.Vars(r)["instance"])
	if checkError(err, w) {
		return
	}

	respond(w, fmt.Sprintf("%d:%d", state.StateVersion, state.ChartVersion), "raw")
}

// Creates an instance controller for the given state and chart data
func newInstanceController(
	instance string,
	state *InstanceState,
	chartData *InstanceChartData,
) InstanceController {
	out := NewOutputWriter(state)
	switch state.Kind {
	case "train", "TRAIN":
		return &TrainInstance{instance: instance, state: state, chartData: chartData, out: out}
	case "backtest", "BACKTEST":
		return &BacktestInstance{instance: instance, state: state, chartData: chartData, out: out}
	case "live", "LIVE":
		return &LiveInstance{instance: instance, state: state, chartData: chartData, out: out}
	}
	panic(fmt.Sprint("invalid state.Kind:", state.Kind))
}

// Returns true if err is not nil, also logs the err and responds to the client
func checkError(err error, w http.ResponseWriter) bool {
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		log.Println(err)
		return true
	}
	return false
}

// Utility to send response to the client in various formats
//noinspection GoUnhandledErrorResult
func respond(w http.ResponseWriter, value interface{}, kind string) {
	switch kind {
	case "json":
		err := json.NewEncoder(w).Encode(value)
		checkError(err, w)
		return
	case "json-gzip":
		// to json
		dataBytes, err := json.Marshal(value)
		if checkError(err, w) {
			return
		}
		// to gzip buffer
		var buf bytes.Buffer
		zw := gzip.NewWriter(&buf)
		zw.Write(dataBytes)
		zw.Close()
		// now to base64. Write directly to the ResponseWriter
		enc := base64.NewEncoder(base64.StdEncoding, w)
		enc.Write(buf.Bytes())
		enc.Close()
		return
	case "raw":
		_, err := fmt.Fprint(w, value)
		checkError(err, w)
		return
	}
	panic(fmt.Sprint("invalid kind in respond:", kind))
}
