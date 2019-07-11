package main

import (
	"github.com/sdcoffey/big"
	"github.com/sdcoffey/techan"
	"io/ioutil"
	"os"
	"reflect"
	"testing"
	"time"
)

func makeCandle(epochMilli int64, open float64, close float64, high float64, low float64, vol float64) *techan.Candle {
	c := techan.NewCandle(techan.NewTimePeriod(time.Unix(epochMilli/1000, 0), time.Minute))
	c.OpenPrice = big.NewDecimal(open)
	c.ClosePrice = big.NewDecimal(close)
	c.MaxPrice = big.NewDecimal(high)
	c.MinPrice = big.NewDecimal(low)
	c.Volume = big.NewDecimal(vol)
	return c
}

func TestParseSeriesFromCSV(t *testing.T) {
	path := "/tmp/series.csv"
	content := []byte("asd\n100000,1,2,3,4,5\n200000,6,7,8,9,10\n300000,11,12,13,14,15\n400000,16,17,18,19,20")
	_ = ioutil.WriteFile(path, content, os.ModePerm)
	series, err := parseSeriesFromCSV(path, 60, time.Unix(100, 1), time.Unix(300, 1))
	if err != nil {
		t.Fatal(err)
	}
	expected := []*techan.Candle{
		//makeCandle(100000, 1, 2, 3, 4, 5),
		makeCandle(200000, 6, 7, 8, 9, 10),
		makeCandle(300000, 11, 12, 13, 14, 15),
		//makeCandle(400000, 16, 17, 18, 19, 20),
	}
	if !reflect.DeepEqual(series.Candles, expected) {
		t.Fatalf("expected: %s.\ngot: %s", expected, series.Candles)
	}
	_ = os.Remove(path)
}
