package com.tradingbot.analysis;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.tradingbot.model.FundamentalData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FundamentalAnalyst {

    private static final Logger log = LoggerFactory.getLogger(FundamentalAnalyst.class);

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

    private static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";

    private final OpenAIClient client;
    private final String model;

    public FundamentalAnalyst(String openRouterApiKey, String model) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(openRouterApiKey)
                .baseUrl(OPENROUTER_BASE_URL)
                .build();
        this.model = model;
    }

    public String analyze(String ticker, FundamentalData data) {
        String userPrompt = buildPrompt(ticker, data);

        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(model)
                    .maxCompletionTokens(600)
                    .addSystemMessage(SYSTEM_PROMPT)
                    .addUserMessage(userPrompt)
                    .build();

            ChatCompletion completion = client.chat().completions().create(params);
            return completion.choices().get(0).message().content().orElse("").trim();

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
