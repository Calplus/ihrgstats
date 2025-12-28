package com.calplus.ihrgstats.utils;

/**
 * Utility class for round-related operations.
 * Consolidates round naming, suffix generation, and formatting logic used across the codebase.
 */
public final class RoundUtils {
    
    // Prevent instantiation
    private RoundUtils() {
        throw new UnsupportedOperationException("RoundUtils class cannot be instantiated");
    }
    
    /**
     * Gets the database column suffix for a round.
     * Example: "1" -> "R1", "t16" -> "T16", "t8" -> "T8"
     * @param round Round identifier (e.g., "1", "t16")
     * @return Column suffix (e.g., "R1", "T16")
     */
    public static String getRoundColumnSuffix(String round) {
        if (round == null || round.isEmpty()) {
            throw new IllegalArgumentException("Round cannot be null or empty");
        }
        
        if (round.startsWith("t")) {
            return "T" + round.substring(1).toUpperCase();
        }
        return "R" + round;
    }
    
    /**
     * Gets the full database column name for a round with the given prefix.
     * Example: ("trueElo", "1") -> "trueEloR1"
     * @param prefix Column prefix (e.g., "trueElo", "seat", "outcome")
     * @param round Round identifier
     * @return Full column name
     */
    public static String getRoundColumnName(String prefix, String round) {
        return prefix + getRoundColumnSuffix(round);
    }
    
    /**
     * Checks if a round identifier is valid.
     * @param round Round identifier to check
     * @return true if the round exists in ROUND_SEQUENCE
     */
    public static boolean isValidRound(String round) {
        return Constants.ROUND_SEQUENCE.contains(round);
    }
    
    /**
     * Gets the index of a round in the ROUND_SEQUENCE.
     * @param round Round identifier
     * @return Index of the round, or -1 if not found
     */
    public static int getRoundIndex(String round) {
        return Constants.ROUND_SEQUENCE.indexOf(round);
    }
}
