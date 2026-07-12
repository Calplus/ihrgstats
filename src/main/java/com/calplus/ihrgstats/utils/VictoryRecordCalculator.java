package com.calplus.ihrgstats.utils;

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
     * Formats score value - shows as integer if .0, otherwise 1 decimal place
     */
    public static String formatScore(double score) {
        if (score == Math.floor(score)) {
            return String.format("%.0f", score);
        }
        return String.format("%.2f", score);
    }
    
    /**
     * Formats hall name: "Hall 4" for numeric, "Binjai Hall" for non-numeric, WALKOVER unchanged
     */
    public static String formatHallName(String hallName) {
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
     * Calculates win percentage
     * @param wins Number of wins
     * @param draws Number of draws
     * @param losses Number of losses
     * @return Win percentage as string
     */
    public static String calculateWinPercentage(int wins, int draws, int losses) {
        int total = wins + draws + losses;
        if (total == 0) {
            return "0.0%";
        }
        double winRate = ((wins * 1.0 + draws * 0.5) / total) * 100;
        return String.format("%.2f%%", winRate);
    }
}

