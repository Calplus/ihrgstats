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
     * Formats a victory record line for a hall
     * Format: "round hallEmoji hallName score oppHall oppEmoji"
     * @param round Round name
     * @param hallName Hall name
     * @param hallScore Hall's total score for that round
     * @param opponentHallName Opponent hall name
     * @param opponentScore Opponent's total score
     * @param hallOutcome Overall outcome for the hall (based on score comparison)
     * @return Formatted string: "round hallEmoji hallName score oppHall oppEmoji"
     */
    public static String formatHallVictoryRecord(String round, String hallName, double hallScore,
                                                 String opponentHallName, double opponentScore,
                                                 Integer hallOutcome) {
        if (opponentHallName == null) {
            return String.format("%s -NA-", getRoundDisplayName(round));
        }
        
        // Apply hall naming scheme
        String formattedHall = formatHallName(hallName);
        String formattedOppHall = formatHallName(opponentHallName);
        
        // Get emojis for both sides
        String hallEmoji = getOutcomeEmoji(hallOutcome);
        // Opponent emoji is opposite: if hall wins (1), opponent loses (-1); if draw (0), both draw
        Integer oppOutcome = hallOutcome == null ? null : (hallOutcome == 0 ? 0 : -hallOutcome);
        String oppEmoji = getOutcomeEmoji(oppOutcome);
        
        // Handle WALKOVER - WALKOVER is a loss for the WALKOVER side
        if ("WALKOVER".equalsIgnoreCase(opponentHallName)) {
            String scoreStr = formatScore(hallScore) + "-" + formatScore(opponentScore);
            return String.format("%s %s %s %s %s %s",
                getRoundDisplayName(round),
                getOutcomeEmoji(1),  // Hall wins
                formattedHall,
                scoreStr,
                "WALKOVER",
                getOutcomeEmoji(-1));  // WALKOVER loses
        }
        
        // Format: "round hallEmoji hallName score oppHall oppEmoji"
        String scoreStr = formatScore(hallScore) + "-" + formatScore(opponentScore);
        
        return String.format("%s %s %s %s %s %s",
            getRoundDisplayName(round),
            hallEmoji,
            formattedHall,
            scoreStr,
            formattedOppHall,
            oppEmoji);
    }
    
    /**
     * Gets display name for round (T16 instead of t16)
     */
    public static String getRoundDisplayName(String round) {
        if (round.startsWith("t")) {
            return "T" + round.substring(1);
        }
        return round;
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
