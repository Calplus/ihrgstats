package com.calplus.ihrgstats.utils;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for detecting which rounds are actually played in the database.
 * This handles cases where rounds are skipped during transition to bracket matchups
 * (e.g., round 6 skipped when moving to T16).
 */
public class RoundDetector {
    
    /**
     * Gets the list of rounds that have actually been played by checking for non-null opponent data.
     * @param dbPath Path to the database file
     * @return List of rounds that have been played, in sequence order
     */
    public static List<String> getAvailableRounds(String dbPath) {
        Set<String> playedRounds = new HashSet<>();
        
        try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
            // Check each round by looking for any non-null opponent names
            for (String round : Constants.ROUND_SEQUENCE) {
                String oppNameCol = RoundUtils.getRoundColumnName("oppName", round);
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
            return new ArrayList<>(Constants.ROUND_SEQUENCE);
        }
        
        // Return rounds in sequence order
        return Constants.ROUND_SEQUENCE.stream()
                             .filter(playedRounds::contains)
                             .collect(Collectors.toList());
    }
}
