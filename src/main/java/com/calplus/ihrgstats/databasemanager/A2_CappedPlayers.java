package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;

import java.io.*;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;

/**
 * Processes cappedlist.csv and updates the A2_CappedPlayers table.
 * Validates CSV format and updates database accordingly.
 */
public class A2_CappedPlayers {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final String dbPath;
    private UploadChatMessageCallback uploadChatCallback;

    /**
     * Interface for sending messages to upload chat
     */
    public interface UploadChatMessageCallback {
        void sendMessage(String message);
    }

    public A2_CappedPlayers() {
        // Load environment variables
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.dbPath = Paths.get(System.getProperty("user.dir"), "database", "core", "default.db").toString();
        this.uploadChatCallback = null;
    }

    /**
     * Formats a message like TelegramLog (with emote, timestamp, filename, type)
     */
    private String formatUploadMessage(String emote, String type, String message) {
        String timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        );
        String filename = "A2_CappedPlayers";
        return String.format("%s [%s] [%s] %s: %s", emote, timestamp, filename, type, message);
    }

    /**
     * Sets a custom upload chat message callback (for Telegram integration)
     */
    public void setUploadChatCallback(UploadChatMessageCallback callback) {
        this.uploadChatCallback = callback;
    }

    /**
     * Represents a capped player entry
     */
    private static class CappedPlayerEntry {
        String name;
        String prevHall;

        CappedPlayerEntry(String name, String prevHall) {
            this.name = name;
            this.prevHall = prevHall;
        }
    }

    /**
     * Validates and processes the cappedlist.csv file
     * @param csvFilePath Path to the cappedlist.csv file
     * @return true if successful, false otherwise
     */
    public boolean processCappedList(String csvFilePath) {
        discordLog.logInfo("Starting cappedlist.csv processing...");
        telegramLog.logInfo("Starting cappedlist.csv processing...");

        File csvFile = new File(csvFilePath);
        if (!csvFile.exists()) {
            String errorMsg = "cappedlist.csv file not found at: " + csvFilePath;
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return false;
        }

        // Parse and validate CSV
        List<CappedPlayerEntry> entries = new ArrayList<>();
        try {
            entries = parseAndValidateCSV(csvFilePath);
        } catch (Exception e) {
            discordLog.flushBatch();
            telegramLog.flushBatch();
            String errorMsg = "CSV validation failed: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return false;
        }

        discordLog.batchInfo(String.format("CSV validated successfully. %d capped players found.", entries.size()));
        telegramLog.batchInfo(String.format("CSV validated successfully. %d capped players found.", entries.size()));

        // Update database
        try {
            updateDatabase(entries);
            
            discordLog.flushBatch();
            telegramLog.flushBatch();
            String successMsg = String.format("cappedlist.csv processed successfully. Updated %d players in A2_CappedPlayers table.", entries.size());
            discordLog.logSuccess(successMsg);
            telegramLog.logSuccess(successMsg);
            
            // Send to upload chat if callback is set
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🟢", "SUCCESS", successMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return true;
            
        } catch (Exception e) {
            discordLog.flushBatch();
            telegramLog.flushBatch();
            String errorMsg = "Database update failed: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            
            // Send error to upload chat if callback is set
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return false;
        }
    }

    /**
     * Parses and validates the cappedlist.csv file
     * Expected format: name,hall (with header row)
     * @param csvFilePath Path to CSV file
     * @return List of validated capped player entries
     * @throws Exception if validation fails
     */
    private List<CappedPlayerEntry> parseAndValidateCSV(String csvFilePath) throws Exception {
        List<CappedPlayerEntry> entries = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
            String line;
            int lineNumber = 0;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                
                // Skip empty lines
                if (line.isEmpty()) continue;

                // Parse CSV line (simple comma split - assumes no quoted values)
                String[] parts = line.split(",", -1);

                // Check header
                if (isHeader) {
                    if (parts.length != 2) {
                        throw new Exception("Invalid CSV format: Header must have exactly 2 columns (name,hall)");
                    }
                    String col1 = parts[0].trim().toLowerCase();
                    String col2 = parts[1].trim().toLowerCase();
                    
                    if (!col1.equals("name") || !col2.equals("hall")) {
                        throw new Exception("Invalid CSV format: Header must be 'name,hall' (case insensitive)");
                    }
                    isHeader = false;
                    continue;
                }

                // Validate data rows
                if (parts.length != 2) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Expected 2 columns, found %d", lineNumber, parts.length));
                }

                String name = parts[0].trim();
                String hall = parts[1].trim();

                // Validate not empty
                if (name.isEmpty()) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Player name cannot be empty", lineNumber));
                }
                if (hall.isEmpty()) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Hall cannot be empty", lineNumber));
                }

                entries.add(new CappedPlayerEntry(name, hall));
            }

            if (entries.isEmpty()) {
                throw new Exception("CSV file contains no data rows");
            }

        } catch (IOException e) {
            throw new Exception("Error reading CSV file: " + e.getMessage());
        }

        return entries;
    }

    /**
     * Updates the A2_CappedPlayers table with the capped player entries
     * Clears existing data and inserts new entries
     * @param entries List of capped player entries
     * @throws Exception if database operation fails
     */
    private void updateDatabase(List<CappedPlayerEntry> entries) throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);

            try {
                // Clear existing data
                String deleteSQL = "DELETE FROM A2_CappedPlayers";
                try (Statement stmt = conn.createStatement()) {
                    int deletedRows = stmt.executeUpdate(deleteSQL);
                    discordLog.batchInfo(String.format("Cleared %d existing entries from A2_CappedPlayers", deletedRows));
                    telegramLog.batchInfo(String.format("Cleared %d existing entries from A2_CappedPlayers", deletedRows));
                }

                // Insert new entries
                String insertSQL = "INSERT INTO A2_CappedPlayers (name, prevHall, mapped) VALUES (?, ?, ?)";
                try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                    for (CappedPlayerEntry entry : entries) {
                        pstmt.setString(1, entry.name);
                        pstmt.setString(2, entry.prevHall);
                        pstmt.setInt(3, 0); // Default mapped to false (0)
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }

                discordLog.batchInfo(String.format("Inserted %d new entries into A2_CappedPlayers", entries.size()));
                telegramLog.batchInfo(String.format("Inserted %d new entries into A2_CappedPlayers", entries.size()));

                conn.commit();
                
            } catch (SQLException e) {
                conn.rollback();
                throw new Exception("Database transaction failed: " + e.getMessage());
            }

        } catch (SQLException e) {
            throw new Exception("Database connection failed: " + e.getMessage());
        }
    }

    /**
     * Main method for testing and CLI usage
     */
    public static void main(String[] args) {
        String csvPath = "cappedlist.csv";
        
        if (args.length > 0) {
            csvPath = args[0];
        }

        A2_CappedPlayers processor = new A2_CappedPlayers();
        boolean success = processor.processCappedList(csvPath);
        
        System.exit(success ? 0 : 1);
    }
}
