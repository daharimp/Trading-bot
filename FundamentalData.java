package com.tradingbot.model;

public class FundamentalData {

    public final String ticker;
    public final String companyName;
    public final String sector;
    public final String industry;
    public final String marketCap;
    public final String peRatio;
    public final String eps;
    public final String profitMargin;
    public final String quarterlyRevenueGrowthYOY;
    public final String debtToEquity;
    public final String dividendYield;
    public final String week52High;
    public final String week52Low;
    public final String analystTargetPrice;
    public final String latestEpsActual;
    public final String latestEpsEstimate;
    public final String latestEarningsDate;
    public final String description;

    public FundamentalData(
            String ticker, String companyName, String sector, String industry,
            String marketCap, String peRatio, String eps, String profitMargin,
            String quarterlyRevenueGrowthYOY, String debtToEquity, String dividendYield,
            String week52High, String week52Low, String analystTargetPrice,
            String latestEpsActual, String latestEpsEstimate, String latestEarningsDate,
            String description) {
        this.ticker = ticker;
        this.companyName = companyName;
        this.sector = sector;
        this.industry = industry;
        this.marketCap = marketCap;
        this.peRatio = peRatio;
        this.eps = eps;
        this.profitMargin = profitMargin;
        this.quarterlyRevenueGrowthYOY = quarterlyRevenueGrowthYOY;
        this.debtToEquity = debtToEquity;
        this.dividendYield = dividendYield;
        this.week52High = week52High;
        this.week52Low = week52Low;
        this.analystTargetPrice = analystTargetPrice;
        this.latestEpsActual = latestEpsActual;
        this.latestEpsEstimate = latestEpsEstimate;
        this.latestEarningsDate = latestEarningsDate;
        this.description = description;
    }
}
