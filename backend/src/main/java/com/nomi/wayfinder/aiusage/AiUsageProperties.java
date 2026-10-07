package com.nomi.wayfinder.aiusage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI spending ("nomi.ai-usage.*"). Prices are OpenAI's list prices of the model in USD per million tokens
 * (check platform.openai.com/docs/pricing when the model changes); costs are estimates from them.
 *
 * @param monthlyBudgetUsd our own lock under the OpenAI project limit: once this month's estimated cost reaches it,
 *                         the assistant answers with the rule-based parser and photos wait (0 = no lock)
 */
@ConfigurationProperties(prefix = "nomi.ai-usage")
public record AiUsageProperties(
        double inputPricePerMillion,
        double outputPricePerMillion,
        double monthlyBudgetUsd
) {

    public double cost(int inputTokens, int outputTokens) {
        return (inputTokens * inputPricePerMillion + outputTokens * outputPricePerMillion) / 1_000_000;
    }
}
