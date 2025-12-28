package com.calplus.ihrgstats.utils;

import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for detecting which rounds are actually played in the database.
 * This handles cases where rounds are skipped during transition to bracket matchups
 * (e.g., round 6 skipped when moving to T16).
 */
public class RoundDetector {
    private static final List<String> ROUND_SEQUENCE = Arrays.asList("1", "2", "3", "4", "5", "6", "t16", "t8", "t4", "t2");
    
    /**
     * Gets the list of rounds that have actually been played by checking for non-null opponent data.
     * @param dbPath Path to the database file
     * @return List of rounds that have been played, in sequence order
     */
    public static List<String> getAvailableRounds(String dbPath) {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        Set<String> playedRounds = new HashSet<>();
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            // Check each round by looking for any non-null opponent names
            for (String round : ROUND_SEQUENCE) {
                String oppNameCol = getRoundColumnName("oppName", round);
                String sql = String.format("SELECT COUNT(*) as count FROM A1_PlayerStats WHERE %s IS NOT NULL AND %s != ''", 
                                         oppNameCol, oppNameCol);
                
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next() && rs.getInt("count") > 0) {
                        playedRounds.add(round);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error detecting available rounds: " + e.getMessage());
            e.printStackTrace();
            // Return all rounds as fallback
            return new ArrayList<>(ROUND_SEQUENCE);
        }
        
        // Return rounds in sequence order
        return ROUND_SEQUENCE.stream()
                             .filter(playedRounds::contains)
                             .collect(Collectors.toList());
    }
    
    /**
     * Gets the database column name for a round
     */
    private static String getRoundColumnName(String prefix, String round) {
        if (round.matches("[1-6]")) {
            return prefix + "R" + round;
        } else {
            // t16 -> T16, t8 -> T8, etc.
            return prefix + round.toUpperCase();
        }
    }
}
