package com.calplus.ihrgstats.utils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for hall-related operations.
 * Consolidates hall fetching, sorting, and formatting logic used across multiple command classes.
 */
public final class HallUtils {
    
    // Prevent instantiation
    private HallUtils() {
        throw new UnsupportedOperationException("HallUtils class cannot be instantiated");
    }
    
    /**
     * Fetches all active halls from the database.
     * @param dbPath Path to the database file
     * @return List of hall names, or empty list if error occurs
     */
    public static List<String> fetchAvailableHalls(String dbPath) {
        List<String> halls = new ArrayList<>();
        
        try (Connection conn = DatabaseHelper.getConnection(dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DISTINCT hall FROM A1_PlayerStats WHERE active = 1 ORDER BY hall")) {
            
            while (rs.next()) {
                halls.add(rs.getString("hall"));
            }
        } catch (SQLException e) {
            System.err.println("Error fetching available halls: " + e.getMessage());
        }
        
        return halls;
    }
    
    /**
     * Sorts halls by numbers first (1, 2, 3...9, 10, 11), then alphabetically (HallA, HallB).
     * Modifies the list in-place.
     * @param halls List of hall names to sort
     */
    public static void sortHalls(List<String> halls) {
        halls.sort((h1, h2) -> {
            Integer num1 = extractNumber(h1);
            Integer num2 = extractNumber(h2);
            
            // Both have numbers - compare numerically
            if (num1 != null && num2 != null) {
                return Integer.compare(num1, num2);
            }
            
            // Only h1 has number - h1 comes first
            if (num1 != null) {
                return -1;
            }
            
            // Only h2 has number - h2 comes first
            if (num2 != null) {
                return 1;
            }
            
            // Neither has number - compare alphabetically
            return h1.compareToIgnoreCase(h2);
        });
    }
    
    /**
     * Fetches and sorts available halls from the database.
     * @param dbPath Path to the database file
     * @return Sorted list of hall names
     */
    public static List<String> fetchAndSortAvailableHalls(String dbPath) {
        List<String> halls = fetchAvailableHalls(dbPath);
        sortHalls(halls);
        return halls;
    }
    
    /**
     * Extracts a number from a hall name.
     * @param hall Hall name (e.g., "5", "Hall10", "10thHall")
     * @return The number, or null if no number found
     */
    private static Integer extractNumber(String hall) {
        // Try direct parse first
        try {
            return Integer.parseInt(hall);
        } catch (NumberFormatException e) {
            // Extract digits from the hall name
            StringBuilder digits = new StringBuilder();
            for (char c : hall.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                }
            }
            
            if (digits.length() > 0) {
                try {
                    return Integer.parseInt(digits.toString());
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            return null;
        }
    }
}
