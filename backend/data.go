package main

type TradeEntry struct {
	Id     int     `json:"id"`
	Buy    float64 `json:"buy"`
	Sell   float64 `json:"sell"`
	Amount float64 `json:"amount"`
}

type Candle struct {
	Timestamp int64   `json:"timestamp"`
	Open      float64 `json:"open"`
	Close     float64 `json:"close"`
	High      float64 `json:"high"`
	Low       float64 `json:"low"`
}

type Operation struct {
	Timestamp   int64  `json:"timestamp"`
	Kind        string `json:"type"`
	Price       int64  `json:"price"`
	Description string `json:"description"`
	Code        int    `json:"code"`
}

// the state of an instance
type InstanceState struct {
	Kind               string            `json:"type"`
	Input              map[string]string `json:"input"`
	Output             string            `json:"output"`
	Trades             []TradeEntry      `json:"trades"`
	StatusText         string            `json:"statusText"`
	StatusPositiveness int               `json:"statusPositiveness"`
	StateVersion       int               `json:"stateVersion"`
	ChartVersion       int               `json:"chartVersion"`
	Action1            string            `json:"action1"`
	Action2            string            `json:"action2"`
}

// Content of a series. Timestamps to values
type SeriesData map[int64]float64

// The state of an instance's chart
type InstanceChartData struct {
	Candles         []Candle                         `json:"candles"`
	Operations      []Operation                      `json:"operations"`
	PriceIndicators map[string]SeriesData            `json:"priceIndicators"`
	ExtraIndicators map[string]map[string]SeriesData `json:"extraIndicators"`
}
