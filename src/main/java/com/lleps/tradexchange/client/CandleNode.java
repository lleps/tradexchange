/*
 * Copyright (c) 2008, 2016, Oracle and/or its affiliates.
 * All rights reserved. Use is subject to license terms.
 *
 * This file is available and licensed under the following license:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  - Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the distribution.
 *  - Neither the name of Oracle Corporation nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.lleps.tradexchange.client;

import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.shape.Line;

/** Candle node used for drawing a candle */
public class CandleNode extends Group {
    private Line highLowLine = new Line();
    private Region bar = new Region();
    private String seriesStyleClass;
    private String dataStyleClass;
    private boolean openAboveClose = true;
    //private Tooltip tooltip = new Tooltip();

    CandleNode(String seriesStyleClass, String dataStyleClass) {
        setAutoSizeChildren(false);
        getChildren().addAll(highLowLine, bar);
        this.seriesStyleClass = seriesStyleClass;
        this.dataStyleClass = dataStyleClass;
        updateStyleClasses();
        //tooltip.setGraphic(new TooltipContent());
        //Tooltip.install(bar, tooltip);
    }

    public void setSeriesAndDataStyleClasses(String seriesStyleClass,
                                             String dataStyleClass) {
        this.seriesStyleClass = seriesStyleClass;
        this.dataStyleClass = dataStyleClass;
        updateStyleClasses();
    }

    public void update(double closeOffset, double highOffset,
                       double lowOffset, double candleWidth) {
        if (closeOffset == 0.0) closeOffset = 0.01;
        openAboveClose = closeOffset > 0;
        updateStyleClasses();
        highLowLine.setStartY(highOffset);
        highLowLine.setEndY(lowOffset);
        if (candleWidth == -1) {
            candleWidth = bar.prefWidth(-1);
        }
        if (openAboveClose) {
            bar.resizeRelocate(-candleWidth / 2, 0,
                    candleWidth, closeOffset);
        } else {
            bar.resizeRelocate(-candleWidth / 2, closeOffset,
                    candleWidth, -closeOffset);
        }
    }

    public void updateTooltip(double open, double close, double high, double low) {
        //TooltipContent tooltipContent = (TooltipContent) tooltip.getGraphic();
        //tooltipContent.update(open, close, high, low);
    }

    private void updateStyleClasses() {
        final String aboveClose =
                openAboveClose ? "open-above-close" : "close-above-open";
        getStyleClass().setAll("candlestick-candle",
                seriesStyleClass, dataStyleClass);
        highLowLine.getStyleClass().setAll("candlestick-line",
                seriesStyleClass, dataStyleClass,
                aboveClose);
        bar.getStyleClass().setAll("candlestick-bar",
                seriesStyleClass, dataStyleClass,
                aboveClose);
    }
}