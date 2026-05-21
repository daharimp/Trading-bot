package com.tradingbot.chart;

import com.tradingbot.analysis.IndicatorEngine;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.None;
import org.ta4j.core.BarSeries;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ChartRenderer {

    /**
     * Renders a price + EMA chart for the given series.
     * Returns PNG bytes suitable for attaching to a Discord message.
     */
    public byte[] render(String ticker, BarSeries series) throws IOException {
        IndicatorEngine engine = new IndicatorEngine(series);
        int barCount = series.getBarCount();

        List<Double> xData     = new ArrayList<>(barCount);
        List<Double> close     = new ArrayList<>(barCount);
        List<Double> ema9List  = new ArrayList<>(barCount);
        List<Double> ema21List = new ArrayList<>(barCount);
        List<Double> ema50List = new ArrayList<>(barCount);

        for (int i = 0; i < barCount; i++) {
            xData.add((double) i);
            close.add(series.getBar(i).getClosePrice().doubleValue());
            ema9List.add(engine.ema9AtIndex(i));
            ema21List.add(engine.ema21AtIndex(i));
            ema50List.add(engine.ema50AtIndex(i));
        }

        XYChart chart = new XYChartBuilder()
                .width(900)
                .height(450)
                .title(ticker + " — Price & EMAs")
                .xAxisTitle("Bar")
                .yAxisTitle("Price")
                .build();

        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.getStyler().setChartBackgroundColor(new Color(30, 30, 30));
        chart.getStyler().setPlotBackgroundColor(new Color(20, 20, 20));
        chart.getStyler().setChartFontColor(Color.LIGHT_GRAY);
        chart.getStyler().setAxisTickLabelsColor(Color.LIGHT_GRAY);
        chart.getStyler().setLegendBackgroundColor(new Color(40, 40, 40));
        chart.getStyler().setPlotGridLinesColor(new Color(50, 50, 50));

        XYSeries closeSeries = chart.addSeries("Close", xData, close);
        closeSeries.setLineColor(Color.WHITE);
        closeSeries.setMarker(new None());

        XYSeries ema9Series = chart.addSeries("EMA9", xData, ema9List);
        ema9Series.setLineColor(new Color(100, 180, 255));
        ema9Series.setMarker(new None());

        XYSeries ema21Series = chart.addSeries("EMA21", xData, ema21List);
        ema21Series.setLineColor(new Color(255, 165, 0));
        ema21Series.setMarker(new None());

        XYSeries ema50Series = chart.addSeries("EMA50", xData, ema50List);
        ema50Series.setLineColor(new Color(220, 80, 80));
        ema50Series.setMarker(new None());

        return BitmapEncoder.getBitmapBytes(chart, BitmapEncoder.BitmapFormat.PNG);
    }
}
