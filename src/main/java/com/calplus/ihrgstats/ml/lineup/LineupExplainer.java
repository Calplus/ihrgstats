package com.calplus.ihrgstats.ml.lineup;

import com.calplus.ihrgstats.ml.ReliabilityScore;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Deterministic, rule-based "why this lineup" prose built entirely from
 * {@link LineupOptimizer.Result}'s own numbers - sacrifice detection,
 * the best-response/maximin robustness gap, and reliability warnings. No
 * LLM, no randomness: same result in, same text out, every time (see the
 * plan's explicit 2026-07-25 decision to drop the LLM explanation phase).
 *
 * DB-free and pure by design (player names are supplied by the caller via
 * {@code nameResolver}) so this stays trivially unit-testable.
 */
public final class LineupExplainer {

    private LineupExplainer() {
    }

    private static final double ROBUST_GAP_THRESHOLD = 0.15;
    private static final double FAVORED_THRESHOLD = 0.5;

    public static String explain(LineupOptimizer.Result result, Function<String, String> nameResolver) {
        StringBuilder sb = new StringBuilder();

        appendSacrificeNarrative(sb, result, nameResolver);
        appendRobustnessNarrative(sb, result);
        appendReliabilityWarnings(sb, result, nameResolver);

        return sb.toString();
    }

    private static void appendSacrificeNarrative(StringBuilder sb, LineupOptimizer.Result result, Function<String, String> nameResolver) {
        var row = result.bestResponsePairingVsTopOpponentOrder;
        if (row.isEmpty()) {
            return;
        }
        int worstSeat = 0;
        for (int i = 1; i < row.size(); i++) {
            if (row.get(i).model.pWin < row.get(worstSeat).model.pWin) {
                worstSeat = i;
            }
        }

        StringBuilder favored = new StringBuilder();
        int favoredCount = 0;
        for (int i = 0; i < row.size(); i++) {
            if (i == worstSeat || row.get(i).model.pWin < FAVORED_THRESHOLD) {
                continue;
            }
            if (favoredCount > 0) {
                favored.append(", ");
            }
            favored.append(String.format(Locale.ROOT, "board %d (%.0f%%)", i + 1, row.get(i).model.pWin * 100));
            favoredCount++;
        }

        if (row.get(worstSeat).model.pWin < FAVORED_THRESHOLD) {
            sb.append(String.format(Locale.ROOT, "Board %d looks like your sacrifice: %s is at %.0f%% there.",
                    worstSeat + 1, nameResolver.apply(result.bestResponse.playerIdsBySeat.get(worstSeat)),
                    row.get(worstSeat).model.pWin * 100));
            if (favoredCount > 0) {
                sb.append(" Freeing you favored on ").append(favored).append(".");
            }
        } else {
            sb.append("No board looks like a deliberate sacrifice here - you're competitive across the board.");
        }
        sb.append(" ");
    }

    private static void appendRobustnessNarrative(StringBuilder sb, LineupOptimizer.Result result) {
        double expected = result.bestResponse.expectedResult.pWin;
        double worst = result.bestResponse.worstCaseResult.pWin;
        double gap = expected - worst;
        if (gap > ROBUST_GAP_THRESHOLD) {
            sb.append(String.format(Locale.ROOT,
                    "This recommendation leans on the opponent fielding their predicted order (%.0f%% expected win) - " +
                    "if they adapt, your worst case drops to %.0f%%. The maximin lineup below trades some expected value for a safer floor of %.0f%%.",
                    expected * 100, worst * 100, result.maximin.worstCaseResult.pWin * 100));
        } else {
            sb.append(String.format(Locale.ROOT,
                    "This lineup is fairly robust either way - even in the opponent's least favorable plausible ordering, you're still around %.0f%% (expected %.0f%%).",
                    worst * 100, expected * 100));
        }
        sb.append(" ");
    }

    private static void appendReliabilityWarnings(StringBuilder sb, LineupOptimizer.Result result, Function<String, String> nameResolver) {
        boolean any = false;
        for (Map.Entry<String, ReliabilityScore> entry : result.reliability.entrySet()) {
            if (entry.getValue().flags.isEmpty()) {
                continue;
            }
            if (!any) {
                sb.append("Reliability notes: ");
                any = true;
            } else {
                sb.append(" ");
            }
            sb.append(nameResolver.apply(entry.getKey())).append(" - ").append(String.join(", ", entry.getValue().flags)).append(".");
        }
    }
}
