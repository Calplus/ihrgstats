package com.calplus.ihrgstats.utils;

import java.util.Comparator;
import java.util.Map;

/**
 * Enhanced utility class for calculating and formatting victory records.
 * Converts database outcome values (1/0/-1) to points (1/0.5/0) and provides formatted strings.
 */
public class VictoryRecordCalculator {
    
    /**
     * Converts database outcome value to points
     * @param outcome Database outcome value (1=win, 0=draw, -1=loss, null=no game)
     * @return Points (1.0 for win, 0.5 for draw, 0.0 for loss, null for no game)
     */
    public static Double outcomeToPoints(Integer outcome) {
        if (outcome == null) {
            return null;
        }
        switch (outcome) {
            case 1:  return 1.0;  // Win
            case 0:  return 0.5;  // Draw
            case -1: return 0.0;  // Loss
            default: return null;
        }
    }
    
    /**
     * Gets emoji for outcome
     * @param outcome Database outcome value (1=win, 0=draw, -1=loss)
     * @return Emoji string
     */
    public static String getOutcomeEmoji(Integer outcome) {
        if (outcome == null) {
            return "❓";
        }
        switch (outcome) {
            case 1:  return "✅";  // Win
            case 0:  return "🟰";  // Draw
            case -1: return "❌";  // Loss
            default: return "❓";
        }
    }
    
    /**
     * Converts the new schema's REAL outcome convention (1.0=win, 0.5=draw,
     * 0.0=loss, stored in match_participants.outcome) to the legacy Integer
     * convention (1/0/-1) expected by this class and OutcomeIconRenderer.
     */
    public static Integer toLegacyOutcome(Double outcome) {
        if (outcome == null) {
            return null;
        }
        if (outcome == 1.0) return 1;
        if (outcome == 0.5) return 0;
        if (outcome == 0.0) return -1;
        return null;
    }

    /**
     * Formats score value - shows as integer if .0, otherwise 2 decimal places
     */
    public static String formatScore(double score) {
        if (score == Math.floor(score)) {
            return String.format("%.0f", score);
        }
        return String.format("%.2f", score);
    }
    
    /**
     * Formats a rank/ELO change as "+n", "-n" or "=" - shared by every
     * info/compare command's ΔRank/ΔELO columns (previously five identical
     * private copies).
     */
    public static String deltaString(int change) {
        if (change > 0) return "+" + change;
        if (change < 0) return "-" + Math.abs(change);
        return "=";
    }

    /**
     * Formats "myScore-oppScore" for a single board, rendering a timed-out
     * side as the literal "TIMEOUT" (previously three identical private
     * copies across the info/compare commands).
     */
    public static String formatScorePair(Double myScore, Double oppScore, boolean selfTimeout, boolean oppTimeout) {
        String myStr = selfTimeout ? "TIMEOUT" : (myScore != null ? formatScore(myScore) : "?");
        String oppStr = oppTimeout ? "TIMEOUT" : (oppScore != null ? formatScore(oppScore) : "0");
        return myStr + "-" + oppStr;
    }

    /**
     * Formats a hall-level "a-b" score pair - whole numbers without
     * decimals, otherwise one decimal place each.
     */
    public static String formatScorePair(double hallScore, double oppScore) {
        if (hallScore == Math.floor(hallScore) && oppScore == Math.floor(oppScore)) {
            return String.format("%d-%d", (int) hallScore, (int) oppScore);
        }
        return String.format("%.1f-%.1f", hallScore, oppScore);
    }

    /**
     * Formats hall name: "Hall 4" for numeric, "Binjai Hall" for non-numeric,
     * WALKOVER unchanged, null hardened to "?" (nulls flow near these
     * formatters from optional lookups - a placeholder beats an NPE).
     */
    public static String formatHallName(String hallName) {
        if (hallName == null) {
            return "?";
        }
        if (hallName.equalsIgnoreCase("WALKOVER")) {
            return "WALKOVER";
        }
        try {
            int num = Integer.parseInt(hallName);
            return "Hall " + num;
        } catch (NumberFormatException e) {
            return hallName + " Hall";
        }
    }

    /**
     * Primary opponent for a hall's round = whichever opponent hall was
     * faced on the most boards (the normal case is exactly one opponent
     * hall). An exactly-equal board split breaks deterministically: higher
     * own score first, then name ascending - previously the pick fell to
     * HashMap iteration order, stable across runs but arbitrary (shared by
     * the hall stats builder and the single-round hall view, which had
     * drifted-in-place twin copies of the same max()).
     */
    public static String primaryOpponent(Map<String, Integer> boardsByOpp, Map<String, Double> myScoreByOpp) {
        return boardsByOpp.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing((Map.Entry<String, Integer> e) -> myScoreByOpp.getOrDefault(e.getKey(), 0.0))
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Signed one-decimal delta for fractional values (hall Elo etc.) -
     * kept separate from deltaString(int) so an int-typed delta can never
     * silently switch to "+3.0" formatting.
     */
    public static String deltaDoubleString(double change) {
        if (change > 0) return String.format("+%.1f", change);
        if (change < 0) return String.format("-%.1f", Math.abs(change));
        return "=";
    }

}

