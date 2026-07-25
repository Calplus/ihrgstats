package com.calplus.ihrgstats.ml;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates a player's rating-confidence signals (family 2 of the feature
 * catalog: RD, career boards, strength-of-schedule bias, graph
 * insularity, rating stability) into a single 0-100 score plus
 * human-readable flags - "how much should you trust this player's Elo",
 * shown in {@code /predict} and {@code /lineup}.
 *
 * The score is a simple, transparent weighted penalty from 100 (deducting
 * for each confidence risk), not a learned model - the point is
 * explainability (every flag maps directly to a deduction), not
 * precision.
 */
public class ReliabilityScore {

    public final int score; // 0-100, higher = more trustworthy
    public final List<String> flags;

    private ReliabilityScore(int score, List<String> flags) {
        this.score = score;
        this.flags = flags;
    }

    public static ReliabilityScore compute(FeatureExtractor.Side side) {
        int score = 100;
        List<String> flags = new ArrayList<>();

        if (side.rd > 250) {
            score -= 30;
            flags.add(String.format("high rating uncertainty (RD %.0f)", side.rd));
        } else if (side.rd > 150) {
            score -= 15;
            flags.add(String.format("moderate rating uncertainty (RD %.0f)", side.rd));
        }

        if (side.careerBoards < 3) {
            score -= 25;
            flags.add("almost no history (" + side.careerBoards + " career boards)");
        } else if (side.careerBoards < 8) {
            score -= 10;
            flags.add("limited history (" + side.careerBoards + " career boards)");
        }

        if (side.graphInsularity > 0.75) {
            score -= 20;
            flags.add("has mostly faced a single hall - rating may not generalize");
        } else if (side.graphInsularity > 0.5) {
            score -= 10;
            flags.add("comparison graph is somewhat insular");
        }

        if (Math.abs(side.oppQualityBias) > 150) {
            score -= 15;
            flags.add(side.oppQualityBias > 0
                    ? "has mostly faced stronger-than-average opponents (rating may be conservative)"
                    : "has mostly faced weaker-than-average opponents (rating may be inflated)");
        }

        if (side.ratingStability > 100) {
            score -= 10;
            flags.add("rating has swung significantly in recent rounds");
        }

        score = Math.max(0, Math.min(100, score));
        return new ReliabilityScore(score, flags);
    }

    /** Short label for compact display (pairing tables etc). */
    public String tier() {
        if (score >= 80) return "High";
        if (score >= 55) return "Medium";
        return "Low";
    }
}
