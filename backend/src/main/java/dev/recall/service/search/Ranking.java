package dev.recall.service.search;

import dev.recall.config.RecallProperties;

/**
 * The search score from the spec, with every weight taken from {@code recall.ranking.*}:
 * <pre>
 * score = w_cos * cosine
 *       + w_use * min(1, ln(1 + uses) / ln(1 + usage_saturation))
 *       + w_rec * exp(-age_days / recency_days)
 *       + w_ctx * (same repo or cwd ? 1 : 0)
 * </pre>
 */
public final class Ranking {

    private final RecallProperties.Ranking p;

    public Ranking(RecallProperties.Ranking p) {
        this.p = p;
    }

    public double score(double cosine, long uses, double ageDays, boolean contextMatch) {
        double usage = Math.min(1.0, Math.log1p(Math.max(0, uses)) / Math.log1p(p.usageSaturation()));
        double recency = Math.exp(-Math.max(0, ageDays) / p.recencyDays());
        return p.cosineWeight() * cosine
                + p.usageWeight() * usage
                + p.recencyWeight() * recency
                + p.contextWeight() * (contextMatch ? 1 : 0);
    }

    public boolean confident(double score) {
        return score >= p.threshold();
    }

    public double threshold() {
        return p.threshold();
    }
}
