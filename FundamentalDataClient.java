package com.tradingbot.fundamental;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tradingbot.model.FundamentalData;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;

public class FundamentalDataClient {

    private static final String BASE_URL = "https://www.alphavantage.co/query";

    private final String apiKey;
    private final OkHttpClient http;

    public FundamentalDataClient(String apiKey) {
        this.apiKey = apiKey;
        this.http = new OkHttpClient();
    }

    public FundamentalData fetch(String ticker) throws IOException {
        JsonObject overview = fetchFunction("OVERVIEW", ticker);

        // Rate limit: Alpha Vantage free tier allows 5 req/min
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        JsonObject earnings = fetchFunction("EARNINGS", ticker);

        return parse(ticker, overview, earnings);
    }

    private JsonObject fetchFunction(String function, String ticker) throws IOException {
        String url = BASE_URL + "?function=" + function + "&symbol=" + ticker + "&apikey=" + apiKey;
        Request request = new Request.Builder().url(url).build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Alpha Vantage HTTP " + response.code() + " for " + function);
            }
            String body = response.body().string();
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            if (json.has("Note") || json.has("Information")) {
                String msg = json.has("Note") ? json.get("Note").getAsString()
                                               : json.get("Information").getAsString();
                throw new IOException("Alpha Vantage rate limit or API error: " + msg);
            }

            return json;
        }
    }

    private FundamentalData parse(String ticker, JsonObject overview, JsonObject earnings) {
        String companyName               = str(overview, "Name");
        String sector                    = str(overview, "Sector");
        String industry                  = str(overview, "Industry");
        String marketCap                 = str(overview, "MarketCapitalization");
        String peRatio                   = str(overview, "PERatio");
        String eps                       = str(overview, "EPS");
        String profitMargin              = str(overview, "ProfitMargin");
        String quarterlyRevenueGrowthYOY = str(overview, "QuarterlyRevenueGrowthYOY");
        String debtToEquity              = str(overview, "DebtToEquityRatio");
        String dividendYield             = str(overview, "DividendYield");
        String week52High                = str(overview, "52WeekHigh");
        String week52Low                 = str(overview, "52WeekLow");
        String analystTargetPrice        = str(overview, "AnalystTargetPrice");
        String description               = str(overview, "Description");

        String latestEpsActual   = "N/A";
        String latestEpsEstimate = "N/A";
        String latestEarningsDate = "N/A";

        if (earnings.has("quarterlyEarnings")) {
            JsonArray quarterly = earnings.getAsJsonArray("quarterlyEarnings");
            if (quarterly != null && quarterly.size() > 0) {
                JsonObject latest = quarterly.get(0).getAsJsonObject();
                latestEarningsDate = str(latest, "reportedDate");
                latestEpsActual    = str(latest, "reportedEPS");
                latestEpsEstimate  = str(latest, "estimatedEPS");
            }
        }

        return new FundamentalData(
                ticker, companyName, sector, industry,
                marketCap, peRatio, eps, profitMargin,
                quarterlyRevenueGrowthYOY, debtToEquity, dividendYield,
                week52High, week52Low, analystTargetPrice,
                latestEpsActual, latestEpsEstimate, latestEarningsDate,
                description);
    }

    private String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "N/A";
        String val = obj.get(key).getAsString();
        return val.isBlank() || val.equals("None") ? "N/A" : val;
    }
}
