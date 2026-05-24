package com.tradingbot.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tradingbot.model.FundamentalData;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class FundamentalAnalyst {

    private static final Logger log = LoggerFactory.getLogger(FundamentalAnalyst.class);

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int MAX_TOKENS = 600;

    private static final String SYSTEM_PROMPT = """
            You are a fundamental equity analyst with 15 years of experience evaluating
            public companies for investment merit. You will receive key financial metrics for a stock.

            Your job is to:
            - Assess the quality of earnings, revenue growth trajectory, and margin trends.
            - Evaluate valuation (P/E relative to sector norms, analyst target vs current price).
            - Identify the company's competitive moat and key risks.
            - Flag upcoming catalysts (earnings date, guidance, sector tailwinds/headwinds).
            - Give an overall fundamental rating.

            Format your response EXACTLY like this:

            FUNDAMENTAL RATING: <STRONG BUY | BUY | NEUTRAL | SELL | STRONG SELL>
            • <bullet 1>
            • <bullet 2>
            • <bullet 3>
            • <bullet 4>
            • <bullet 5 — optional>
            • <bullet 6 — optional>

            Keep each bullet concise (1-2 sentences max). Focus on what a trader needs to know.
            """;

    private final OkHttpClient http;
    private final String apiKey;
    private final String model;

    public FundamentalAnalyst(String openRouterApiKey, String model) {
        this.apiKey = openRouterApiKey;
        this.model = model;
        this.http = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(120))
                .readTimeout(Duration.ofSeconds(120))
                .build();
    }

    public String analyze(String ticker, FundamentalData data) {
        String userPrompt = buildPrompt(ticker, data);

        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", MAX_TOKENS);

            JsonArray messages = new JsonArray();
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", SYSTEM_PROMPT);
            messages.add(systemMsg);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userPrompt);
            messages.add(userMsg);
            body.add("messages", messages);

            Request request = new Request.Builder()
                    .url(OPENROUTER_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            try (Response resp = http.newCall(request).execute()) {
                ResponseBody respBody = resp.body();
                String raw = respBody != null ? respBody.string() : "";
                if (!resp.isSuccessful()) {
                    throw new RuntimeException("OpenRouter API error " + resp.code() + ": " + raw);
                }
                JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
                return json.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString()
                        .trim();
            }

        } catch (Exception e) {
            log.error("Fundamental LLM analysis failed for {}: {}", ticker, e.getMessage());
            return "⚠️ Fundamental analysis unavailable: " + e.getMessage();
        }
    }

    private String buildPrompt(String ticker, FundamentalData data) {
        return String.format("""
                ## Fundamental Data for %s — %s (%s | %s)

                **Valuation**
                - P/E Ratio: %s
                - EPS: %s
                - Analyst 12-month target: %s
                - 52-week range: %s – %s

                **Profitability & Growth**
                - Profit Margin: %s
                - Quarterly Revenue Growth (YoY): %s
                - Debt/Equity: %s
                - Dividend Yield: %s

                **Market**
                - Market Cap: %s

                **Recent Earnings**
                - Date: %s | Reported EPS: %s | Estimated EPS: %s

                **Company Description**
                %s

                Please provide your fundamental analysis.
                """,
                ticker, data.companyName, data.sector, data.industry,
                data.peRatio, data.eps, data.analystTargetPrice,
                data.week52Low, data.week52High,
                data.profitMargin, data.quarterlyRevenueGrowthYOY,
                data.debtToEquity, data.dividendYield,
                data.marketCap,
                data.latestEarningsDate, data.latestEpsActual, data.latestEpsEstimate,
                data.description);
    }
}
