package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.calculations.EloCalculator;
import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Processes round_n.csv files and updates the A1_PlayerStats table.
 * Handles round sequencing, player validation, ELO calculations, and seating arrangements.
 */
public class A1_PlayerStats {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final String dbPath;
    private boolean perfEloEnabled;
    private UserConfirmationCallback confirmationCallback;
    private MultiChoiceConfirmationCallback multiChoiceCallback;
    private UploadChatMessageCallback uploadChatCallback;

    /**
     * Interface for user confirmation callbacks (used by Telegram listener)
     */
    public interface UserConfirmationCallback {
        boolean requestConfirmation(String message);
    }
    
    /**
     * Interface for multi-choice confirmation callbacks (used by Telegram listener)
     */
    public interface MultiChoiceConfirmationCallback {
        int requestChoice(String message, String[] options);
    }

    /**
     * Interface for sending messages to upload chat
     */
    public interface UploadChatMessageCallback {
        void sendMessage(String message);
    }

    public A1_PlayerStats() {
        // Load environment variables
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.dbPath = DatabaseHelper.getDefaultDatabasePathString();
        this.loadConfig();
        this.confirmationCallback = null; // Default to CLI confirmation
        this.multiChoiceCallback = null;
        this.uploadChatCallback = null;
    }

    /**
     * Formats a message like TelegramLog (with emote, timestamp, filename, type)
     */
    private String formatUploadMessage(String emote, String type, String message) {
        String timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        );
        String filename = "A1_PlayerStats";
        return String.format("%s [%s] [%s] %s: %s", emote, timestamp, filename, type, message);
    }

    /**
     * Sets a custom confirmation callback (for Telegram integration)
     */
    public void setConfirmationCallback(UserConfirmationCallback callback) {
        this.confirmationCallback = callback;
    }
    
    /**
     * Sets a custom multi-choice confirmation callback (for Telegram integration)
     */
    public void setMultiChoiceCallback(MultiChoiceConfirmationCallback callback) {
        this.multiChoiceCallback = callback;
    }

    /**
     * Sets a custom upload chat message callback (for Telegram integration)
     */
    public void setUploadChatCallback(UploadChatMessageCallback callback) {
        this.uploadChatCallback = callback;
    }

    /**
     * Loads configuration from application.properties
     */
    private void loadConfig() {
        try {
            String perfEloSetting = PropertyResolver.getProperty("settings.perfElo.enabled", "true");
            this.perfEloEnabled = Boolean.parseBoolean(perfEloSetting);
        } catch (Exception e) {
            System.err.println("Error loading perfElo setting, defaulting to true: " + e.getMessage());
            this.perfEloEnabled = true;
        }
    }

    /**
     * Represents a game entry from the CSV
     */
    private static class GameEntry {
        String name1;
        String hall1;
        String winby1; // Can be empty
        String name2;
        String hall2;
        String winby2; // Can be empty

        GameEntry(String name1, String hall1, String winby1, String name2, String hall2, String winby2) {
            this.name1 = name1;
            this.hall1 = hall1;
            this.winby1 = winby1;
            this.name2 = name2;
            this.hall2 = hall2;
            this.winby2 = winby2;
        }
    }

    /**
     * Represents a player's stats
     */
    private static class PlayerStats {
        String name;
        String hall;
        boolean capped;
        boolean active; // true if found in round_n.csv, false if only from imports/cappedlist
        Integer baseTrueElo;
        Integer basePerfElo;
        
        // Glicko-2 base parameters
        Double baseRdTrueElo;
        Double baseVolTrueElo;
        Double baseRdPerfElo;
        Double baseVolPerfElo;
        
        String dateLogged;
        Map<String, Integer> trueEloByRound = new HashMap<>();
        Map<String, Integer> perfEloByRound = new HashMap<>();
        
        // Glicko-2 parameters by round
        Map<String, Double> rdTrueEloByRound = new HashMap<>();
        Map<String, Double> volTrueEloByRound = new HashMap<>();
        Map<String, Double> rdPerfEloByRound = new HashMap<>();
        Map<String, Double> volPerfEloByRound = new HashMap<>();
        
        Map<String, Integer> seatByRound = new HashMap<>();
        Map<String, String> oppHallByRound = new HashMap<>();
        Map<String, String> oppNameByRound = new HashMap<>();
        Map<String, Integer> oppTrueEloByRound = new HashMap<>();
        Map<String, Integer> oppPerfEloByRound = new HashMap<>();
        Map<String, Integer> outcomeByRound = new HashMap<>(); // 1=win, 0=draw, -1=loss
        Map<String, Double> scoreByRound = new HashMap<>(); // Player's board win score for the match
        boolean existsInDb = false;
        int dbId = -1;
    }

    /**
     * Represents a detected name mismatch between CSV and database
     */
    private static class NameMismatch {
        PlayerStats csvPlayer;
        PlayerStats dbPlayer;
        String csvKey;
        String dbKey;
        String type; // "partial" or "spelling"
        String description;
        
        NameMismatch(PlayerStats csvPlayer, PlayerStats dbPlayer, String csvKey, String dbKey, String type, String description) {
            this.csvPlayer = csvPlayer;
            this.dbPlayer = dbPlayer;
            this.csvKey = csvKey;
            this.dbKey = dbKey;
            this.type = type;
            this.description = description;
        }
    }

    /**
     * Represents a detected hall mismatch between CSV and database
     */
    private static class HallMismatch {
        PlayerStats csvPlayer;
        PlayerStats dbPlayer;
        
        HallMismatch(PlayerStats csvPlayer, PlayerStats dbPlayer) {
            this.csvPlayer = csvPlayer;
            this.dbPlayer = dbPlayer;
        }
    }

    /**
     * Represents a hall mismatch for inactive (active == 0) players with resolution options
     */
    private static class InactiveHallMismatch {
        PlayerStats csvPlayer;
        PlayerStats dbPlayer;
        String userChoice; // "keep_old", "update_same", "create_new"
        
        InactiveHallMismatch(PlayerStats csvPlayer, PlayerStats dbPlayer) {
            this.csvPlayer = csvPlayer;
            this.dbPlayer = dbPlayer;
            this.userChoice = null; // Will be set after user confirms
        }
    }

    /**
     * Main processing method for round_n.csv files
     * @param csvFilePath Path to the round CSV file
     * @param roundName Round identifier (e.g., "1", "t8")
     * @return true if successful, false otherwise
     */
    public boolean processRound(String csvFilePath, String roundName) {
        discordLog.logInfo(String.format("Starting round_%s.csv processing...", roundName));
        telegramLog.logInfo(String.format("Starting round_%s.csv processing...", roundName));

        File csvFile = new File(csvFilePath);
        if (!csvFile.exists()) {
            String errorMsg = String.format("round_%s.csv file not found at: %s", roundName, csvFilePath);
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            
            // Send error to upload chat if callback is set
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return false;
        }

        // Validate round sequence
        if (!validateRoundSequence(roundName)) {
            return false;
        }

        // Parse and validate CSV
        List<GameEntry> games;
        try {
            games = parseAndValidateCSV(csvFilePath);
        } catch (Exception e) {
            discordLog.flushBatch();
            telegramLog.flushBatch();
            String errorMsg = "CSV validation failed: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            
            // Send error to upload chat if callback is set
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return false;
        }

        discordLog.batchInfo(String.format("CSV validated successfully. %d games found.", games.size()));
        telegramLog.batchInfo(String.format("CSV validated successfully. %d games found.", games.size()));

        // Load existing database data
        Map<String, PlayerStats> dbPlayers;
        Map<String, String> cappedPlayers;
        try {
            dbPlayers = loadDatabasePlayers();
            cappedPlayers = loadCappedPlayers();
        } catch (Exception e) {
            discordLog.flushBatch();
            telegramLog.flushBatch();
            String errorMsg = "Failed to load database: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            
            // Send error to upload chat if callback is set
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return false;
        }

        // Check if this round was already processed (re-upload detection)
        if (!dbPlayers.isEmpty() && isRoundAlreadyProcessed(roundName, dbPlayers)) {
            String warningMsg = String.format(
                "⚠️ **Round Already Processed**\n\n" +
                "Round %s has already been processed!\n\n" +
                "**If you continue:**\n" +
                "- Round %s will be reprocessed with the new data\n" +
                "- ALL rounds after round %s will be DELETED\n" +
                "- You will need to re-upload those rounds again\n\n" +
                "**Do you want to continue?**",
                roundName, roundName, roundName
            );
            
            String[] options = {"Continue and reprocess", "Cancel"};
            
            discordLog.flushBatch();
            telegramLog.flushBatch();
            
            int choice = requestMultiChoice(warningMsg, options);
            
            if (choice != 0) {
                String cancelMsg = String.format("Round %s reprocessing cancelled by user.", roundName);
                discordLog.logWarning(cancelMsg);
                telegramLog.logWarning(cancelMsg);
                
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("🟡", "WARNING", cancelMsg);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
                
                return false;
            }
            
            // User confirmed - clear all future rounds
            try {
                clearFutureRounds(roundName);
                String infoMsg = String.format("Cleared all rounds after round_%s. Reprocessing...", roundName);
                discordLog.logInfo(infoMsg);
                telegramLog.logInfo(infoMsg);
                
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("ℹ️", "INFO", infoMsg);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
            } catch (Exception e) {
                String errorMsg = "Failed to clear future rounds: " + e.getMessage();
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
                
                return false;
            }
            
            // Reload database after clearing
            try {
                dbPlayers = loadDatabasePlayers();
            } catch (Exception e) {
                String errorMsg = "Failed to reload database: " + e.getMessage();
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                return false;
            }
        }

        // Extract players from CSV
        Map<String, PlayerStats> csvPlayers = extractPlayersFromGames(games);

        // Check and set capped status
        checkCappedStatus(csvPlayers, cappedPlayers);

        // Validate players per hall count
        if (!validatePlayersPerHall(csvPlayers)) {
            return false;
        }

        // Validate player name/hall matches
        if (!validatePlayerMatches(csvPlayers, dbPlayers)) {
            return false;
        }

        // Calculate seating arrangements
        calculateSeating(games, csvPlayers, roundName);

        // Calculate ELO ratings
        try {
            calculateEloRatings(games, csvPlayers, dbPlayers, roundName);
        } catch (Exception e) {
            discordLog.flushBatch();
            telegramLog.flushBatch();
            String errorMsg = "ELO calculation failed: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            
            // Send error to upload chat if callback is set
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return false;
        }

        // Handle missing players (old players not in CSV)
        handleMissingPlayers(csvPlayers, dbPlayers, roundName);

        // Update database
        try {
            updateDatabase(csvPlayers, dbPlayers, roundName);
            
            // Update A2 mapped field for matched players
            updateA2MappedField(csvPlayers);
            
            discordLog.flushBatch();
            telegramLog.flushBatch();
            String successMsg = String.format("round_%s.csv processed successfully. Updated %d players in A1_PlayerStats table.", 
                roundName, csvPlayers.size());
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
     * Imports player data from an exported CSV file
     * @param csvFilePath Path to the playerExport CSV file
     * @return true if successful, false otherwise
     */
    public boolean importPlayerExport(String csvFilePath) {
        discordLog.logInfo("Starting player export import...");
        telegramLog.logInfo("Starting player export import...");
        
        if (uploadChatCallback != null) {
            String formattedMsg = formatUploadMessage("🔵", "INFO", "Starting player export import...");
            uploadChatCallback.sendMessage(formattedMsg);
        }
        
        try {
            // Check if table is empty
            boolean tableIsEmpty = isTableEmpty();
            
            if (!tableIsEmpty) {
                // Table has data - ask user if they want to overwrite
                String warningMsg = "⚠️ **Database Not Empty**\n\n" +
                    "The A1_PlayerStats table already contains data.\n\n" +
                    "**If you continue:**\n" +
                    "- ALL existing player data will be DELETED\n" +
                    "- New data from the CSV will be imported\n\n" +
                    "**Do you want to overwrite all existing data?**";
                
                String[] options = {"Yes - Delete all and import", "No - Cancel import"};
                
                discordLog.flushBatch();
                telegramLog.flushBatch();
                
                int choice = requestMultiChoice(warningMsg, options);
                
                if (choice != 0) {
                    String cancelMsg = "Player import cancelled by user.";
                    discordLog.logInfo(cancelMsg);
                    telegramLog.logInfo(cancelMsg);
                    
                    if (uploadChatCallback != null) {
                        String formattedMsg = formatUploadMessage("🟡", "INFO", cancelMsg);
                        uploadChatCallback.sendMessage(formattedMsg);
                    }
                    
                    return false;
                }
                
                // User confirmed - delete all existing data
                deleteAllPlayerData();
                
                discordLog.logInfo("All existing player data deleted.");
                telegramLog.logInfo("All existing player data deleted.");
                
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("🟡", "INFO", "All existing player data deleted.");
                    uploadChatCallback.sendMessage(formattedMsg);
                }
            }
            
            // Parse CSV file
            List<PlayerExportData> importData = parsePlayerExportCSV(csvFilePath);
            
            if (importData.isEmpty()) {
                String errorMsg = "No valid player data found in export file.";
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
                
                return false;
            }
            
            // Update database with imported data
            importPlayersToDatabase(importData);
            
            discordLog.flushBatch();
            telegramLog.flushBatch();
            
            String successMsg = String.format("Successfully imported %d players from export file.", importData.size());
            discordLog.logSuccess(successMsg);
            telegramLog.logSuccess(successMsg);
            
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🟢", "SUCCESS", successMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return true;
            
        } catch (Exception e) {
            discordLog.flushBatch();
            telegramLog.flushBatch();
            String errorMsg = "Player import failed: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            e.printStackTrace();
            
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return false;
        }
    }
    
    /**
     * Represents imported player data from export CSV
     */
    private static class PlayerExportData {
        String name;
        int trueElo;
        Integer perfElo;
        Double rdTrueElo;
        Double volTrueElo;
        Double rdPerfElo;
        Double volPerfElo;
        String lastRound;
        String lastHall;
        boolean capped;
        
        PlayerExportData(String name, int trueElo, Integer perfElo, 
                        Double rdTrueElo, Double volTrueElo, 
                        Double rdPerfElo, Double volPerfElo,
                        String lastRound, String lastHall) {
            this.name = name;
            this.trueElo = trueElo;
            this.perfElo = perfElo;
            this.rdTrueElo = rdTrueElo;
            this.volTrueElo = volTrueElo;
            this.rdPerfElo = rdPerfElo;
            this.volPerfElo = volPerfElo;
            this.lastRound = lastRound;
            this.lastHall = lastHall;
        }
    }
    
    /**
     * Parses player export CSV file
     * Expected format: name,trueElo,perfElo,rdTrueElo,volTrueElo,rdPerfElo,volPerfElo,lastRound,lastHall,capped
     */
    private List<PlayerExportData> parsePlayerExportCSV(String csvFilePath) throws Exception {
        List<PlayerExportData> importData = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
            String line;
            int lineNumber = 0;
            boolean isHeader = true;
            
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                
                if (line.isEmpty()) continue;
                
                // Parse CSV line
                String[] parts = parseCSVLine(line);
                
                // Check header
                if (isHeader) {
                    isHeader = false;
                    // Validate header format
                    if (parts.length < 10 || !parts[0].equalsIgnoreCase("name")) {
                        throw new Exception("Invalid CSV header. Expected: name,trueElo,perfElo,rdTrueElo,volTrueElo,rdPerfElo,volPerfElo,lastRound,lastHall,capped");
                    }
                    continue;
                }
                
                // Validate data row
                if (parts.length < 10) {
                    throw new Exception(String.format("Line %d: Expected 10 columns, found %d", lineNumber, parts.length));
                }
                
                try {
                    String name = parts[0].trim();
                    int trueElo = Integer.parseInt(parts[1].trim());
                    Integer perfElo = parts[2].trim().isEmpty() ? null : Integer.parseInt(parts[2].trim());
                    Double rdTrueElo = parts[3].trim().isEmpty() ? null : Double.parseDouble(parts[3].trim());
                    Double volTrueElo = parts[4].trim().isEmpty() ? null : Double.parseDouble(parts[4].trim());
                    Double rdPerfElo = parts[5].trim().isEmpty() ? null : Double.parseDouble(parts[5].trim());
                    Double volPerfElo = parts[6].trim().isEmpty() ? null : Double.parseDouble(parts[6].trim());
                    String lastRound = parts[7].trim().isEmpty() ? null : parts[7].trim();
                    String lastHall = parts[8].trim();
                    boolean capped = parts[9].trim().equalsIgnoreCase("true");
                    
                    if (name.isEmpty()) {
                        throw new Exception(String.format("Line %d: Player name cannot be empty", lineNumber));
                    }
                    if (lastHall.isEmpty()) {
                        throw new Exception(String.format("Line %d: Hall cannot be empty", lineNumber));
                    }
                    
                    importData.add(new PlayerExportData(name, trueElo, perfElo, rdTrueElo, volTrueElo, 
                                                       rdPerfElo, volPerfElo, lastRound, lastHall));
                    
                    discordLog.batchInfo(String.format("Parsed player: %s (Hall: %s, TrueElo: %d)", name, lastHall, trueElo));
                    telegramLog.batchInfo(String.format("Parsed player: %s (Hall: %s, TrueElo: %d)", name, lastHall, trueElo));
                    
                } catch (NumberFormatException e) {
                    throw new Exception(String.format("Line %d: Invalid number format - %s", lineNumber, e.getMessage()));
                }
            }
            
            if (importData.isEmpty()) {
                throw new Exception("CSV file contains no data rows");
            }
            
        } catch (IOException e) {
            throw new Exception("Error reading CSV file: " + e.getMessage());
        }
        
        return importData;
    }
    
    /**
     * Imports players into database, creating or updating existing records
     */
    private void importPlayersToDatabase(List<PlayerExportData> importData) throws Exception {
        
        try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
            conn.setAutoCommit(false);
            
            try {
                // Check if players exist and update/insert accordingly
                for (PlayerExportData data : importData) {
                    String key = (data.name + "_" + data.lastHall).toLowerCase();
                    
                    // Check if player exists
                    String checkSQL = "SELECT id FROM A1_PlayerStats WHERE LOWER(name || '_' || hall) = ?";
                    Long playerId = null;
                    
                    try (PreparedStatement checkStmt = conn.prepareStatement(checkSQL)) {
                        checkStmt.setString(1, key);
                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next()) {
                                playerId = rs.getLong("id");
                            }
                        }
                    }
                    
                    if (playerId != null) {
                        // Update existing player
                        String updateSQL = "UPDATE A1_PlayerStats SET " +
                            "baseTrueElo = ?, basePerfElo = ?, " +
                            "baseRdTrueElo = ?, baseVolTrueElo = ?, " +
                            "baseRdPerfElo = ?, baseVolPerfElo = ?, " +
                            "capped = ?, active = ?, dateLogged = ? " +
                            "WHERE id = ?";
                        
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateSQL)) {
                            updateStmt.setInt(1, data.trueElo);
                            if (data.perfElo != null) {
                                updateStmt.setInt(2, data.perfElo);
                            } else {
                                updateStmt.setNull(2, Types.INTEGER);
                            }
                            if (data.rdTrueElo != null) {
                                updateStmt.setDouble(3, data.rdTrueElo);
                            } else {
                                updateStmt.setNull(3, Types.REAL);
                            }
                            if (data.volTrueElo != null) {
                                updateStmt.setDouble(4, data.volTrueElo);
                            } else {
                                updateStmt.setNull(4, Types.REAL);
                            }
                            if (data.rdPerfElo != null) {
                                updateStmt.setDouble(5, data.rdPerfElo);
                            } else {
                                updateStmt.setNull(5, Types.REAL);
                            }
                            if (data.volPerfElo != null) {
                                updateStmt.setDouble(6, data.volPerfElo);
                            } else {
                                updateStmt.setNull(6, Types.REAL);
                            }
                            updateStmt.setInt(7, data.capped ? 1 : 0);
                            updateStmt.setInt(8, 0); // active = false (imported, not from round CSV)
                            updateStmt.setString(9, new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
                            updateStmt.setLong(10, playerId);
                            updateStmt.executeUpdate();
                        }
                        
                        discordLog.batchInfo(String.format("Updated player: %s (Hall: %s)", data.name, data.lastHall));
                        telegramLog.batchInfo(String.format("Updated player: %s (Hall: %s)", data.name, data.lastHall));
                        
                    } else {
                        // Insert new player - hall MUST be recorded from playerExport
                        String insertSQL = "INSERT INTO A1_PlayerStats " +
                            "(name, capped, active, hall, baseTrueElo, basePerfElo, " +
                            "baseRdTrueElo, baseVolTrueElo, baseRdPerfElo, baseVolPerfElo, dateLogged) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                        
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertSQL)) {
                            insertStmt.setString(1, data.name);
                            insertStmt.setInt(2, data.capped ? 1 : 0);
                            insertStmt.setInt(3, 0); // active = false (imported, not from round CSV)
                            insertStmt.setString(4, data.lastHall);
                            insertStmt.setInt(5, data.trueElo);
                            if (data.perfElo != null) {
                                insertStmt.setInt(6, data.perfElo);
                            } else {
                                insertStmt.setNull(6, Types.INTEGER);
                            }
                            if (data.rdTrueElo != null) {
                                insertStmt.setDouble(7, data.rdTrueElo);
                            } else {
                                insertStmt.setNull(7, Types.REAL);
                            }
                            if (data.volTrueElo != null) {
                                insertStmt.setDouble(8, data.volTrueElo);
                            } else {
                                insertStmt.setNull(8, Types.REAL);
                            }
                            if (data.rdPerfElo != null) {
                                insertStmt.setDouble(9, data.rdPerfElo);
                            } else {
                                insertStmt.setNull(9, Types.REAL);
                            }
                            if (data.volPerfElo != null) {
                                insertStmt.setDouble(10, data.volPerfElo);
                            } else {
                                insertStmt.setNull(10, Types.REAL);
                            }
                            insertStmt.setString(11, new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
                            insertStmt.executeUpdate();
                        }
                        
                        discordLog.batchInfo(String.format("Inserted new player: %s (Hall: %s)", data.name, data.lastHall));
                        telegramLog.batchInfo(String.format("Inserted new player: %s (Hall: %s)", data.name, data.lastHall));
                    }
                }
                
                conn.commit();
                discordLog.batchInfo("Database import committed successfully");
                telegramLog.batchInfo("Database import committed successfully");
                
            } catch (SQLException e) {
                conn.rollback();
                throw new Exception("Database transaction failed: " + e.getMessage());
            }
            
        } catch (SQLException e) {
            throw new Exception("Database connection failed: " + e.getMessage());
        }
    }

    /**
     * Validates that the previous round has been processed
     */
    private boolean validateRoundSequence(String roundName) {
        int roundIndex = Constants.ROUND_SEQUENCE.indexOf(roundName);
        if (roundIndex == -1) {
            String errorMsg = String.format("Invalid round name: %s. Valid rounds: %s", roundName, String.join(", ", Constants.ROUND_SEQUENCE));
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            
            // Send error to upload chat if callback is set
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return false;
        }

        // Round 1 doesn't need previous round
        if (roundIndex == 0) {
            return true;
        }

        // Special handling for t16 - check if round 6 has been processed
        if (roundName.equals("t16")) {
            return validateBracketTransition(roundIndex);
        }

        // Check if previous round is processed
        String previousRound = Constants.ROUND_SEQUENCE.get(roundIndex - 1);
        String columnName = "trueEloR" + previousRound.toUpperCase();
        if (previousRound.startsWith("t")) {
            columnName = "trueEloT" + previousRound.substring(1);
        }

        try {
            try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
                // Check if previous round has been processed
                String sql = String.format("SELECT COUNT(*) FROM A1_PlayerStats WHERE %s IS NOT NULL", columnName);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        // Find last processed round
                        String lastProcessed = findLastProcessedRound(conn, roundIndex);
                        String lastProcessedMsg = lastProcessed != null ? 
                            String.format(" Last processed round: round_%s.", lastProcessed) : 
                            " No rounds have been processed yet.";
                        
                        String errorMsg = String.format("Previous round (round_%s) has not been processed yet.%s Please process rounds in order: %s",
                            previousRound, lastProcessedMsg, String.join(", ", Constants.ROUND_SEQUENCE));
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
            }
        } catch (SQLException e) {
            // If column doesn't exist or other error, assume we can proceed
            System.err.println("Warning: Could not validate previous round: " + e.getMessage());
        }

        return true;
    }

    /**
     * Finds the last processed round in the database
     * @param conn Database connection
     * @param beforeIndex Only check rounds before this index
     * @return Last processed round name, or null if none processed
     */
    private String findLastProcessedRound(Connection conn, int beforeIndex) {
        try {
            for (int i = beforeIndex - 1; i >= 0; i--) {
                String round = Constants.ROUND_SEQUENCE.get(i);
                String columnName = "trueEloR" + round.toUpperCase();
                if (round.startsWith("t")) {
                    columnName = "trueEloT" + round.substring(1);
                }
                
                String sql = String.format("SELECT COUNT(*) FROM A1_PlayerStats WHERE %s IS NOT NULL", columnName);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        return round;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Warning: Could not find last processed round: " + e.getMessage());
        }
        return null;
    }

    /**
     * Validates transition from round-robin to bracket (t16)
     * Checks if round 6 has been processed, if not asks user if tournament moved to bracket
     */
    private boolean validateBracketTransition(int t16Index) {
        try {
            String jdbcUrl = "jdbc:sqlite:" + dbPath;
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                // Check which rounds have been processed
                String lastProcessedRound = null;
                for (int i = t16Index - 1; i >= 0; i--) {
                    String round = Constants.ROUND_SEQUENCE.get(i);
                    String columnName = "trueEloR" + round.toUpperCase();
                    if (round.startsWith("t")) {
                        columnName = "trueEloT" + round.substring(1);
                    }

                    String sql = String.format("SELECT COUNT(*) FROM A1_PlayerStats WHERE %s IS NOT NULL", columnName);
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(sql)) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            lastProcessedRound = round;
                            break;
                        }
                    }
                }

                // If round 6 has not been processed, warn user
                // This includes both cases: no rounds processed (null) or last round != 6
                if (lastProcessedRound == null || !lastProcessedRound.equals("6")) {
                    StringBuilder message = new StringBuilder();
                    message.append("⚠️ **Bracket Transition Detected**\n\n");
                    message.append(String.format("Tournament bracket (t16) is being uploaded, but round 6 has not been processed.\n"));
                    if (lastProcessedRound != null) {
                        message.append(String.format("Last processed round: %s\n\n", lastProcessedRound));
                    } else {
                        message.append("Last processed round: none (database is empty)\n\n");
                    }
                    message.append("Has the tournament moved to bracket matchup format, skipping the remaining round-robin rounds?\n\n");
                    
                    // List missing rounds
                    List<String> missingRounds = new ArrayList<>();
                    if (lastProcessedRound != null) {
                        int lastIdx = Constants.ROUND_SEQUENCE.indexOf(lastProcessedRound);
                        for (int i = lastIdx + 1; i < t16Index; i++) {
                            missingRounds.add(Constants.ROUND_SEQUENCE.get(i));
                        }
                    } else {
                        // No rounds processed - all rounds 1-6 are missing
                        for (int i = 0; i < t16Index; i++) {
                            missingRounds.add(Constants.ROUND_SEQUENCE.get(i));
                        }
                    }
                    message.append("**Missing rounds: ").append(String.join(", ", missingRounds)).append("**\n\n");
                    message.append("**What would you like to do?**");

                    String[] options = {
                        "Fill missing rounds and continue",
                        "Stop processing"
                    };

                    discordLog.flushBatch();
                    telegramLog.flushBatch();

                    int choice = requestMultiChoice(message.toString(), options);

                    if (choice != 0) {
                        String errorMsg = "Processing stopped: User declined bracket transition.";
                        discordLog.logError(errorMsg);
                        telegramLog.logError(errorMsg);
                        
                        // Send error to upload chat if callback is set
                        if (uploadChatCallback != null) {
                            String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                            uploadChatCallback.sendMessage(formattedMsg);
                        }
                        
                        return false;
                    }

                    // Fill missing rounds with last known ELO
                    fillMissingRounds(lastProcessedRound, missingRounds);
                    
                    discordLog.logInfo("Missing rounds filled with last known ELO values.");
                    telegramLog.logInfo("Missing rounds filled with last known ELO values.");
                    
                    // Send success message to upload chat if callback is set
                    if (uploadChatCallback != null) {
                        String successMsg = "Missing rounds filled with last known ELO values.";
                        String formattedMsg = formatUploadMessage("🟢", "SUCCESS", successMsg);
                        uploadChatCallback.sendMessage(formattedMsg);
                    }
                }

                return true;
            }
        } catch (SQLException e) {
            System.err.println("Warning: Could not validate bracket transition: " + e.getMessage());
            return true; // Proceed anyway
        }
    }

    /**
     * Fills missing rounds with last known ELO values
     */
    private void fillMissingRounds(String lastProcessedRound, List<String> missingRounds) {
        try {
            String jdbcUrl = "jdbc:sqlite:" + dbPath;
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                conn.setAutoCommit(false);

                String lastRoundTrueEloCol = getRoundColumnName("trueElo", lastProcessedRound);
                String lastRoundPerfEloCol = getRoundColumnName("perfElo", lastProcessedRound);

                // Get all players
                String selectSQL = String.format("SELECT id, %s, %s FROM A1_PlayerStats", 
                    lastRoundTrueEloCol, lastRoundPerfEloCol);

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(selectSQL)) {

                    while (rs.next()) {
                        int playerId = rs.getInt("id");
                        Integer lastTrueElo = (Integer) rs.getObject(lastRoundTrueEloCol);
                        Integer lastPerfElo = (Integer) rs.getObject(lastRoundPerfEloCol);

                        // Fill each missing round with the last known values
                        for (String missingRound : missingRounds) {
                            String trueEloCol = getRoundColumnName("trueElo", missingRound);
                            String perfEloCol = getRoundColumnName("perfElo", missingRound);

                            String updateSQL = String.format("UPDATE A1_PlayerStats SET %s = ?, %s = ? WHERE id = ?",
                                trueEloCol, perfEloCol);

                            try (PreparedStatement pstmt = conn.prepareStatement(updateSQL)) {
                                pstmt.setObject(1, lastTrueElo);
                                pstmt.setObject(2, lastPerfElo);
                                pstmt.setInt(3, playerId);
                                pstmt.executeUpdate();
                            }
                        }
                    }
                }

                conn.commit();
                discordLog.batchInfo(String.format("Filled %d missing rounds with last known ELO values.", missingRounds.size()));
                telegramLog.batchInfo(String.format("Filled %d missing rounds with last known ELO values.", missingRounds.size()));
            }
        } catch (SQLException e) {
            String errorMsg = "Failed to fill missing rounds: " + e.getMessage();
            System.err.println(errorMsg);
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
        }
    }

    /**
     * Parses and validates the round CSV file
     */
    private List<GameEntry> parseAndValidateCSV(String csvFilePath) throws Exception {
        List<GameEntry> games = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
            String line;
            int lineNumber = 0;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                
                if (line.isEmpty()) continue;

                String[] parts = parseCSVLine(line);

                if (isHeader) {
                    if (parts.length != 6) {
                        throw new Exception("Invalid CSV format: Header must have exactly 6 columns (name1,hall1,winby1,name2,hall2,winby2)");
                    }
                    String[] expected = {"name1", "hall1", "winby1", "name2", "hall2", "winby2"};
                    for (int i = 0; i < 6; i++) {
                        if (!parts[i].trim().toLowerCase().equals(expected[i])) {
                            throw new Exception(String.format("Invalid CSV header: Expected '%s' at column %d, found '%s'", 
                                expected[i], i + 1, parts[i].trim()));
                        }
                    }
                    isHeader = false;
                    continue;
                }

                if (parts.length != 6) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Expected 6 columns, found %d", lineNumber, parts.length));
                }

                String name1 = parts[0].trim();
                String hall1 = parts[1].trim();
                String winby1 = parts[2].trim();
                String name2 = parts[3].trim();
                String hall2 = parts[4].trim();
                String winby2 = parts[5].trim();

                // Validate names
                if (name1.isEmpty() || name2.isEmpty()) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Player names cannot be empty", lineNumber));
                }

                // Check for WALKOVER
                boolean player1IsWalkover = name1.equalsIgnoreCase("WALKOVER");
                boolean player2IsWalkover = name2.equalsIgnoreCase("WALKOVER");
                
                if (player1IsWalkover && player2IsWalkover) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Both players cannot be WALKOVER", lineNumber));
                }
                
                if (player1IsWalkover) {
                    // Player 1 is walkover - player 2 wins by default
                    // Hall and score for walkover player are optional (can be present or empty)
                    if (hall2.isEmpty()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: Non-WALKOVER player must have a hall", lineNumber));
                    }
                } else if (player2IsWalkover) {
                    // Player 2 is walkover - player 1 wins by default
                    // Hall and score for walkover player are optional (can be present or empty)
                    if (hall1.isEmpty()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: Non-WALKOVER player must have a hall", lineNumber));
                    }
                } else {
                    // Normal game - both halls must be present
                    if (hall1.isEmpty() || hall2.isEmpty()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: Hall names cannot be empty for regular games", lineNumber));
                    }
                    
                    // At least one winby field must be filled
                    if (winby1.isEmpty() && winby2.isEmpty()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: At least one winby field must be filled", lineNumber));
                    }
                    
                    // Validate winby logic: if both filled, check for valid combinations
                    if (!winby1.isEmpty() && !winby2.isEmpty()) {
                        // Check for draw: both must be "draw" (case-insensitive)
                        boolean bothDraw = winby1.equalsIgnoreCase("draw") && winby2.equalsIgnoreCase("draw");
                        
                        // Check for win/loss: both must be "0" or "1"
                        boolean bothBinary = (winby1.equals("0") || winby1.equals("1")) && (winby2.equals("0") || winby2.equals("1"));
                        
                        if (!bothDraw && !bothBinary) {
                            throw new Exception(String.format("Invalid CSV format at line %d: When both winby columns are filled, values must be either both 'draw' or '0'/'1'", lineNumber));
                        }
                        
                        // If binary, exactly one must be "1" and one must be "0"
                        if (bothBinary && winby1.equals(winby2)) {
                            throw new Exception(String.format("Invalid CSV format at line %d: When using '0'/'1' for both winby columns, one must be '0' and the other '1'", lineNumber));
                        }
                    }
                }

                games.add(new GameEntry(name1, hall1, winby1, name2, hall2, winby2));
            }

            if (games.isEmpty()) {
                throw new Exception("CSV file contains no data rows");
            }

        } catch (IOException e) {
            throw new Exception("Error reading CSV file: " + e.getMessage());
        }

        return games;
    }

    /**
     * Parses a CSV line with support for quoted fields (RFC 4180 compliant)
     */
    private String[] parseCSVLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote
                    currentField.append('"');
                    i++; // Skip next quote
                } else {
                    // Toggle quote mode
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                // End of field
                fields.add(currentField.toString());
                currentField = new StringBuilder();
            } else {
                currentField.append(c);
            }
        }
        
        // Add last field
        fields.add(currentField.toString());
        
        return fields.toArray(new String[0]);
    }

    /**
     * Loads capped players from A2_CappedPlayers table
     */
    private Map<String, String> loadCappedPlayers() throws Exception {
        Map<String, String> cappedPlayers = new HashMap<>();
        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            String sql = "SELECT name, prevHall FROM A2_CappedPlayers";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                while (rs.next()) {
                    String name = rs.getString("name");
                    String prevHall = rs.getString("prevHall");
                    cappedPlayers.put(name.toLowerCase(), prevHall);
                }
            }
        } catch (SQLException e) {
            // Table might not exist yet
            System.err.println("Warning: Could not load capped players: " + e.getMessage());
        }

        return cappedPlayers;
    }

    /**
     * Loads all players from the database
     */
    private Map<String, PlayerStats> loadDatabasePlayers() throws Exception {
        Map<String, PlayerStats> players = new HashMap<>();
        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            String sql = "SELECT * FROM A1_PlayerStats";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                while (rs.next()) {
                    PlayerStats player = new PlayerStats();
                    player.dbId = rs.getInt("id");
                    player.name = rs.getString("name");
                    player.hall = rs.getString("hall");
                    player.capped = rs.getInt("capped") == 1; // SQLite boolean as 0/1
                    player.active = rs.getInt("active") == 1; // SQLite boolean as 0/1
                    player.baseTrueElo = (Integer) rs.getObject("baseTrueElo");
                    player.basePerfElo = (Integer) rs.getObject("basePerfElo");
                    
                    // Load Glicko-2 base parameters
                    player.baseRdTrueElo = (Double) rs.getObject("baseRdTrueElo");
                    player.baseVolTrueElo = (Double) rs.getObject("baseVolTrueElo");
                    player.baseRdPerfElo = (Double) rs.getObject("baseRdPerfElo");
                    player.baseVolPerfElo = (Double) rs.getObject("baseVolPerfElo");
                    
                    player.dateLogged = rs.getString("dateLogged");
                    player.existsInDb = true;

                    // Load ELO ratings for all rounds
                    for (String round : Constants.ROUND_SEQUENCE) {
                        String trueEloCol = getRoundColumnName("trueElo", round);
                        String perfEloCol = getRoundColumnName("perfElo", round);
                        String rdTrueEloCol = getRoundColumnName("rdTrueElo", round);
                        String volTrueEloCol = getRoundColumnName("volTrueElo", round);
                        String rdPerfEloCol = getRoundColumnName("rdPerfElo", round);
                        String volPerfEloCol = getRoundColumnName("volPerfElo", round);
                        String seatCol = getRoundColumnName("seat", round);
                        String outcomeCol = getRoundColumnName("outcome", round);
                        String oppHallCol = getRoundColumnName("oppHall", round);
                        String oppNameCol = getRoundColumnName("oppName", round);
                        String oppTrueEloCol = getRoundColumnName("oppTrueElo", round);
                        String oppPerfEloCol = getRoundColumnName("oppPerfElo", round);

                        player.trueEloByRound.put(round, (Integer) rs.getObject(trueEloCol));
                        player.perfEloByRound.put(round, (Integer) rs.getObject(perfEloCol));
                        player.rdTrueEloByRound.put(round, (Double) rs.getObject(rdTrueEloCol));
                        player.volTrueEloByRound.put(round, (Double) rs.getObject(volTrueEloCol));
                        player.rdPerfEloByRound.put(round, (Double) rs.getObject(rdPerfEloCol));
                        player.volPerfEloByRound.put(round, (Double) rs.getObject(volPerfEloCol));
                        player.seatByRound.put(round, (Integer) rs.getObject(seatCol));
                        player.outcomeByRound.put(round, (Integer) rs.getObject(outcomeCol));
                        player.oppHallByRound.put(round, rs.getString(oppHallCol));
                        player.oppNameByRound.put(round, rs.getString(oppNameCol));
                        player.oppTrueEloByRound.put(round, (Integer) rs.getObject(oppTrueEloCol));
                        player.oppPerfEloByRound.put(round, (Integer) rs.getObject(oppPerfEloCol));
                    }

                    String key = player.name.toLowerCase();
                    players.put(key, player);
                }
            }
        } catch (SQLException e) {
            throw new Exception("Database query failed: " + e.getMessage());
        }

        return players;
    }

    /**
     * Checks and sets capped status for players based on A2_CappedPlayers table
     */
    private void checkCappedStatus(Map<String, PlayerStats> csvPlayers, Map<String, String> cappedPlayers) {
        for (Map.Entry<String, PlayerStats> entry : csvPlayers.entrySet()) {
            String playerKey = entry.getKey();
            PlayerStats player = entry.getValue();
            
            if (cappedPlayers.containsKey(playerKey)) {
                player.capped = true;
                String prevHall = cappedPlayers.get(playerKey);
                
                // Check if hall is different
                if (!player.hall.equalsIgnoreCase(prevHall)) {
                    String warningMsg = String.format("⚠️ Player '%s' is capped but hall changed from '%s' to '%s'", 
                        player.name, prevHall, player.hall);
                    discordLog.batchWarning(warningMsg);
                    telegramLog.batchWarning(warningMsg);
                    
                    // Send warning to upload chat if callback is set
                    if (uploadChatCallback != null) {
                        String formattedMsg = formatUploadMessage("⚠️", "WARNING", warningMsg);
                        uploadChatCallback.sendMessage(formattedMsg);
                    }
                }
            }
        }
    }

    /**
     * Extracts players from game entries
     */
    private Map<String, PlayerStats> extractPlayersFromGames(List<GameEntry> games) {
        Map<String, PlayerStats> players = new HashMap<>();

        for (GameEntry game : games) {
            // Player 1 (skip if walkover)
            if (!game.name1.equalsIgnoreCase("WALKOVER")) {
                String key1 = game.name1.toLowerCase();
                if (!players.containsKey(key1)) {
                    PlayerStats player = new PlayerStats();
                    player.name = game.name1;
                    player.hall = game.hall1;
                    player.capped = false;
                    player.active = true; // Player is active (seen in round CSV)
                    players.put(key1, player);
                }
            }

            // Player 2 (skip if walkover)
            if (!game.name2.equalsIgnoreCase("WALKOVER")) {
                String key2 = game.name2.toLowerCase();
                if (!players.containsKey(key2)) {
                    PlayerStats player = new PlayerStats();
                    player.name = game.name2;
                    player.hall = game.hall2;
                    player.capped = false;
                    player.active = true; // Player is active (seen in round CSV)
                    players.put(key2, player);
                }
            }
        }

        return players;
    }

    /**
     * Validates that no hall has more than 5 players
     * @return true if validation passes or user confirms to continue, false otherwise
     */
    private boolean validatePlayersPerHall(Map<String, PlayerStats> csvPlayers) {
        // Count players per hall
        Map<String, Integer> playersPerHall = new HashMap<>();
        for (PlayerStats player : csvPlayers.values()) {
            String hall = player.hall.toLowerCase();
            playersPerHall.put(hall, playersPerHall.getOrDefault(hall, 0) + 1);
        }

        // Check for halls with more than 5 players
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : playersPerHall.entrySet()) {
            if (entry.getValue() > 5) {
                violations.add(String.format("Hall '%s' has %d players (max: 5)", 
                    entry.getKey(), entry.getValue()));
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder message = new StringBuilder("⚠️ **Hall Capacity Violations**\n\n");
            message.append("The following halls exceed the 5-player limit:\n\n");
            for (String violation : violations) {
                message.append("  - ").append(violation).append("\n");
            }
            message.append("\n**Do you want to continue processing?**");

            String[] options = {"Continue", "Cancel"};

            discordLog.flushBatch();
            telegramLog.flushBatch();

            int choice = requestMultiChoice(message.toString(), options);
            
            if (choice != 0) {
                String errorMsg = "Processing cancelled due to player count violations.";
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
                
                return false;
            }

            discordLog.logInfo("User confirmed to proceed despite player count violations.");
            telegramLog.logInfo("User confirmed to proceed despite player count violations.");
        }

        return true;
    }

    /**
     * Validates player name/hall matches between CSV and database
     * NEW LOGIC:
     * - active == 1: Match by name (including misspellings) AND hall
     * - active == 0, hall matches: Continue as usual
     * - active == 0, hall doesn't match: Ask user for confirmation with 3 options
     */
    private boolean validatePlayerMatches(Map<String, PlayerStats> csvPlayers, Map<String, PlayerStats> dbPlayers) {
        // Store different types of mismatches
        List<HallMismatch> activeHallMismatches = new ArrayList<>(); // active == 1 with hall mismatch
        List<InactiveHallMismatch> inactiveHallMismatches = new ArrayList<>(); // active == 0 with hall mismatch
        List<String> crossHallIssues = new ArrayList<>();
        List<NameMismatch> nameMismatches = new ArrayList<>();

        // First pass: Check for exact key matches, categorize by active status
        for (Map.Entry<String, PlayerStats> entry : csvPlayers.entrySet()) {
            String key = entry.getKey();
            PlayerStats csvPlayer = entry.getValue();
            PlayerStats dbPlayer = dbPlayers.get(key);

            if (dbPlayer != null) {
                // Player exists in database with exact name match - check hall match
                if (dbPlayer.hall == null) {
                    // PlayerExport import without hall set - set it from CSV
                    dbPlayer.hall = csvPlayer.hall;
                } else if (!csvPlayer.hall.equalsIgnoreCase(dbPlayer.hall)) {
                    // Hall mismatch detected - categorize by active status
                    if (dbPlayer.active) {
                        // active == 1: This is an error (active players shouldn't change halls without confirmation)
                        activeHallMismatches.add(new HallMismatch(csvPlayer, dbPlayer));
                    } else {
                        // active == 0: Requires user confirmation for hall update
                        inactiveHallMismatches.add(new InactiveHallMismatch(csvPlayer, dbPlayer));
                    }
                }
            } else {
                // Player not found with exact key - check if they exist in other halls
                for (Map.Entry<String, PlayerStats> dbEntry : dbPlayers.entrySet()) {
                    PlayerStats otherDbPlayer = dbEntry.getValue();
                    if (otherDbPlayer.hall != null && 
                        csvPlayer.name.equalsIgnoreCase(otherDbPlayer.name) && 
                        !csvPlayer.hall.equalsIgnoreCase(otherDbPlayer.hall)) {
                        
                        // Check active status
                        if (otherDbPlayer.active) {
                            // active == 1: Different hall means error or different person
                            crossHallIssues.add(String.format(
                                "⚠️ Player '%s' found in CSV hall '%s' but exists as ACTIVE in database in hall '%s'. Is this an error or a different person?",
                                csvPlayer.name, csvPlayer.hall, otherDbPlayer.hall));
                        } else {
                            // active == 0: Potential hall change - add to inactive mismatches
                            inactiveHallMismatches.add(new InactiveHallMismatch(csvPlayer, otherDbPlayer));
                        }
                        break;
                    }
                }
            }
        }

        // Second pass: Check for potential name mismatches within same hall
        for (Map.Entry<String, PlayerStats> csvEntry : csvPlayers.entrySet()) {
            String csvKey = csvEntry.getKey();
            PlayerStats csvPlayer = csvEntry.getValue();
            
            // Get all DB players from the same hall
            List<Map.Entry<String, PlayerStats>> sameHallDbPlayers = dbPlayers.entrySet().stream()
                .filter(dbEntry -> dbEntry.getValue().hall != null && dbEntry.getValue().hall.equalsIgnoreCase(csvPlayer.hall))
                .collect(Collectors.toList());
            
            for (Map.Entry<String, PlayerStats> dbEntry : sameHallDbPlayers) {
                String dbKey = dbEntry.getKey();
                PlayerStats dbPlayer = dbEntry.getValue();
                
                if (!csvKey.equals(dbKey)) {
                    // Check if names are similar (partial match or small misspelling)
                    if (isPartialNameMatch(csvKey, dbKey)) {
                        String description = String.format("⚠️ Possible partial name: '%s' (CSV) matches '%s' (DB) in hall '%s'", 
                            csvPlayer.name, dbPlayer.name, csvPlayer.hall);
                        nameMismatches.add(new NameMismatch(csvPlayer, dbPlayer, csvKey, dbKey, "partial", description));
                    } else if (areSimilarNames(csvKey, dbKey)) {
                        String description = String.format("❌ Potential major misspelling: '%s' (CSV) vs '%s' (DB) in hall '%s'", 
                            csvPlayer.name, dbPlayer.name, csvPlayer.hall);
                        nameMismatches.add(new NameMismatch(csvPlayer, dbPlayer, csvKey, dbKey, "spelling", description));
                    }
                }
            }
        }

        // Handle active player hall mismatches (active == 1) - treat as errors
        if (!activeHallMismatches.isEmpty()) {
            StringBuilder message = new StringBuilder("🔴 Active Player Hall Mismatch Error\n\n");
            message.append("The following ACTIVE players have hall mismatches:\n\n");
            for (HallMismatch mismatch : activeHallMismatches) {
                message.append(String.format("- %s: CSV hall '%s' != DB hall '%s'\n", 
                    mismatch.csvPlayer.name, mismatch.csvPlayer.hall, mismatch.dbPlayer.hall));
            }
            message.append("\nActive players should not have hall changes. This is likely an error.");

            discordLog.flushBatch();
            telegramLog.flushBatch();

            String errorMsg = "Processing stopped: Active players with hall mismatches detected.";
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🔴", "ERROR", message.toString());
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return false;
        }

        // Handle cross-hall issues for active players (requires immediate confirmation)
        if (!crossHallIssues.isEmpty()) {
            StringBuilder message = new StringBuilder("⚠️ Cross-Hall Active Player Detection\n\n");
            message.append("The following players exist in different halls:\n\n");
            for (String issue : crossHallIssues) {
                message.append("- ").append(issue).append("\n");
            }
            message.append("\nIs this an error or are these different people?");

            String[] options = {
                "It's an error - Stop processing",
                "Different people - Continue"
            };

            discordLog.flushBatch();
            telegramLog.flushBatch();

            int choice = requestMultiChoice(message.toString(), options);
            
            if (choice == 0) {
                String errorMsg = "Processing stopped: User confirmed cross-hall players are errors.";
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
                
                return false;
            }

            discordLog.logInfo("User confirmed cross-hall players are different people. Continuing...");
            telegramLog.logInfo("User confirmed cross-hall players are different people. Continuing...");
        }

        // Handle inactive player hall mismatches (active == 0) - interactive resolution with 3 options
        if (!inactiveHallMismatches.isEmpty()) {
            if (!handleInactiveHallMismatches(inactiveHallMismatches, csvPlayers, dbPlayers)) {
                return false;
            }
        }

        // Handle name mismatches (both partial and spelling) with single dialog
        if (!nameMismatches.isEmpty()) {
            if (!handleNameMismatches(nameMismatches)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Handles inactive player hall mismatches with interactive resolution
     * Offers 3 choices:
     * 1. Keep old hall (DB) - same player
     * 2. Use new hall (CSV) - same player who changed halls
     * 3. Use new hall (CSV) - treat as different player
     */
    private boolean handleInactiveHallMismatches(List<InactiveHallMismatch> mismatches, 
                                                 Map<String, PlayerStats> csvPlayers, 
                                                 Map<String, PlayerStats> dbPlayers) {
        // First: Ask if user wants to update individually or all at once
        StringBuilder message = new StringBuilder("⚠️ Inactive Player Hall Mismatches Detected\n\n");
        message.append("The following INACTIVE players (active == 0) have hall mismatches:\n\n");
        for (InactiveHallMismatch mismatch : mismatches) {
            message.append(String.format("- %s: CSV hall '%s' -> DB hall '%s'\n", 
                mismatch.csvPlayer.name, mismatch.csvPlayer.hall, mismatch.dbPlayer.hall));
        }
        message.append(String.format("\nTotal: %d player(s)\n", mismatches.size()));
        message.append("\nHow would you like to handle these mismatches?");

        String[] bulkOptions = {
            "Update individually (choose for each player)",
            "Update all at once (same choice for all)",
            "Cancel processing"
        };

        discordLog.flushBatch();
        telegramLog.flushBatch();

        int bulkChoice = requestMultiChoice(message.toString(), bulkOptions);
        
        if (bulkChoice == 2) {
            // Cancel
            String cancelMsg = "Processing cancelled by user during inactive hall mismatch resolution.";
            discordLog.logError(cancelMsg);
            telegramLog.logError(cancelMsg);
            
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🔴", "ERROR", cancelMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return false;
        }

        if (bulkChoice == 1) {
            // Update all at once - ask for single resolution strategy
            String bulkMessage = String.format(
                "⚠️ Bulk Hall Mismatch Resolution\n\n" +
                "Applying resolution to %d player(s)\n\n" +
                "Choose resolution strategy:",
                mismatches.size()
            );

            String[] resolutionOptions = {
                "Keep old hall (DB) - same player, don't update hall",
                "Use new hall (CSV) - same player who changed halls",
                "Use new hall (CSV) - treat as different player",
                "Cancel processing"
            };

            int resolutionChoice = requestMultiChoice(bulkMessage, resolutionOptions);
            
            if (resolutionChoice == 3) {
                // Cancel
                String cancelMsg = "Processing cancelled by user during bulk resolution.";
                discordLog.logError(cancelMsg);
                telegramLog.logError(cancelMsg);
                
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("🔴", "ERROR", cancelMsg);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
                
                return false;
            }

            // Apply same choice to all mismatches
            for (InactiveHallMismatch mismatch : mismatches) {
                if (resolutionChoice == 0) {
                    mismatch.userChoice = "keep_old";
                } else if (resolutionChoice == 1) {
                    mismatch.userChoice = "update_same";
                } else if (resolutionChoice == 2) {
                    mismatch.userChoice = "create_new";
                }
            }

        } else {
            // Update individually - ask for each player
            for (InactiveHallMismatch mismatch : mismatches) {
                String individualMessage = String.format(
                    "⚠️ Hall Mismatch Resolution\n\n" +
                    "Player: %s\n" +
                    "CSV Hall: %s\n" +
                    "Database Hall: %s\n" +
                    "Active Status: INACTIVE (0)\n\n" +
                    "Choose resolution:",
                    mismatch.csvPlayer.name,
                    mismatch.csvPlayer.hall,
                    mismatch.dbPlayer.hall
                );

                String[] individualOptions = {
                    "Keep old hall (" + mismatch.dbPlayer.hall + ") - same player",
                    "Use new hall (" + mismatch.csvPlayer.hall + ") - same player who changed",
                    "Use new hall (" + mismatch.csvPlayer.hall + ") - different player",
                    "Cancel processing"
                };

                int individualChoice = requestMultiChoice(individualMessage, individualOptions);
                
                if (individualChoice == 3) {
                    // Cancel
                    String cancelMsg = "Processing cancelled by user during individual resolution.";
                    discordLog.logError(cancelMsg);
                    telegramLog.logError(cancelMsg);
                    
                    if (uploadChatCallback != null) {
                        String formattedMsg = formatUploadMessage("🔴", "ERROR", cancelMsg);
                        uploadChatCallback.sendMessage(formattedMsg);
                    }
                    
                    return false;
                }

                if (individualChoice == 0) {
                    mismatch.userChoice = "keep_old";
                } else if (individualChoice == 1) {
                    mismatch.userChoice = "update_same";
                } else if (individualChoice == 2) {
                    mismatch.userChoice = "create_new";
                }
            }
        }

        // Apply the user's choices
        for (InactiveHallMismatch mismatch : mismatches) {
            applyInactiveHallMismatchResolution(mismatch, csvPlayers, dbPlayers);
        }

        return true;
    }

    /**
     * Applies the user's resolution choice for an inactive hall mismatch
     */
    private void applyInactiveHallMismatchResolution(InactiveHallMismatch mismatch, 
                                                     Map<String, PlayerStats> csvPlayers, 
                                                     Map<String, PlayerStats> dbPlayers) {
        if ("keep_old".equals(mismatch.userChoice)) {
            // Keep old hall (DB) - update CSV player to use DB hall
            String oldHall = mismatch.csvPlayer.hall;
            mismatch.csvPlayer.hall = mismatch.dbPlayer.hall;
            
            // Link CSV player to DB player
            mismatch.csvPlayer.existsInDb = true;
            mismatch.csvPlayer.dbId = mismatch.dbPlayer.dbId;
            
            discordLog.logInfo(String.format("Hall resolved (keep old): '%s' changed from '%s' to '%s' (using DB hall)", 
                mismatch.csvPlayer.name, oldHall, mismatch.csvPlayer.hall));
            telegramLog.logInfo(String.format("Hall resolved (keep old): '%s' changed from '%s' to '%s' (using DB hall)", 
                mismatch.csvPlayer.name, oldHall, mismatch.csvPlayer.hall));
                
        } else if ("update_same".equals(mismatch.userChoice)) {
            // Use new hall (CSV) - same player who changed halls
            // Update DB player's hall to match CSV
            String oldHall = mismatch.dbPlayer.hall;
            mismatch.dbPlayer.hall = mismatch.csvPlayer.hall;
            
            // Link CSV player to DB player
            mismatch.csvPlayer.existsInDb = true;
            mismatch.csvPlayer.dbId = mismatch.dbPlayer.dbId;
            
            discordLog.logInfo(String.format("Hall resolved (update same): '%s' hall updated from '%s' to '%s' in database", 
                mismatch.csvPlayer.name, oldHall, mismatch.csvPlayer.hall));
            telegramLog.logInfo(String.format("Hall resolved (update same): '%s' hall updated from '%s' to '%s' in database", 
                mismatch.csvPlayer.name, oldHall, mismatch.csvPlayer.hall));
                
        } else if ("create_new".equals(mismatch.userChoice)) {
            // Use new hall (CSV) - treat as different player
            // CSV player will be inserted as new entry (don't link to DB player)
            mismatch.csvPlayer.existsInDb = false;
            mismatch.csvPlayer.dbId = -1;
            
            discordLog.logInfo(String.format("Hall resolved (create new): '%s' in hall '%s' will be treated as new player (separate from DB hall '%s')", 
                mismatch.csvPlayer.name, mismatch.csvPlayer.hall, mismatch.dbPlayer.hall));
            telegramLog.logInfo(String.format("Hall resolved (create new): '%s' in hall '%s' will be treated as new player (separate from DB hall '%s')", 
                mismatch.csvPlayer.name, mismatch.csvPlayer.hall, mismatch.dbPlayer.hall));
        }
    }

    /**
     * Handles name mismatches with interactive resolution
     */
    private boolean handleNameMismatches(List<NameMismatch> nameMismatches) {
        StringBuilder message = new StringBuilder("⚠️ Name Mismatch Detected\n\n");
        message.append("The following potential name mismatches were found:\n\n");
        
        for (NameMismatch mismatch : nameMismatches) {
            message.append("- ").append(mismatch.description).append("\n");
        }
        
        message.append("\nPlease choose how to handle these mismatches:");
        
        String[] options = {
            "Treat as same person (use DB name)",
            "Treat as different people",
            "Cancel processing"
        };

        discordLog.flushBatch();
        telegramLog.flushBatch();

        int choice = requestMultiChoice(message.toString(), options);
        
        if (choice == 0) {
            // Treat as same person - map CSV players to DB players
            discordLog.logInfo("User chose to treat mismatched names as same person. Mapping to database names...");
            telegramLog.logInfo("User chose to treat mismatched names as same person. Mapping to database names...");
            
            for (NameMismatch mismatch : nameMismatches) {
                // Mark CSV player as existing in DB and link to DB player
                mismatch.csvPlayer.existsInDb = true;
                mismatch.csvPlayer.dbId = mismatch.dbPlayer.dbId;
                
                // Update CSV player name to match DB name
                String oldCsvName = mismatch.csvPlayer.name;
                mismatch.csvPlayer.name = mismatch.dbPlayer.name;
                
                // Copy base ELO values from DB player
                mismatch.csvPlayer.baseTrueElo = mismatch.dbPlayer.baseTrueElo;
                mismatch.csvPlayer.basePerfElo = mismatch.dbPlayer.basePerfElo;
                mismatch.csvPlayer.baseRdTrueElo = mismatch.dbPlayer.baseRdTrueElo;
                mismatch.csvPlayer.baseVolTrueElo = mismatch.dbPlayer.baseVolTrueElo;
                mismatch.csvPlayer.baseRdPerfElo = mismatch.dbPlayer.baseRdPerfElo;
                mismatch.csvPlayer.baseVolPerfElo = mismatch.dbPlayer.baseVolPerfElo;
                
                // Copy all historical round data from DB player
                mismatch.csvPlayer.trueEloByRound.putAll(mismatch.dbPlayer.trueEloByRound);
                mismatch.csvPlayer.perfEloByRound.putAll(mismatch.dbPlayer.perfEloByRound);
                mismatch.csvPlayer.rdTrueEloByRound.putAll(mismatch.dbPlayer.rdTrueEloByRound);
                mismatch.csvPlayer.volTrueEloByRound.putAll(mismatch.dbPlayer.volTrueEloByRound);
                mismatch.csvPlayer.rdPerfEloByRound.putAll(mismatch.dbPlayer.rdPerfEloByRound);
                mismatch.csvPlayer.volPerfEloByRound.putAll(mismatch.dbPlayer.volPerfEloByRound);
                mismatch.csvPlayer.seatByRound.putAll(mismatch.dbPlayer.seatByRound);
                mismatch.csvPlayer.oppHallByRound.putAll(mismatch.dbPlayer.oppHallByRound);
                mismatch.csvPlayer.oppNameByRound.putAll(mismatch.dbPlayer.oppNameByRound);
                mismatch.csvPlayer.oppTrueEloByRound.putAll(mismatch.dbPlayer.oppTrueEloByRound);
                mismatch.csvPlayer.oppPerfEloByRound.putAll(mismatch.dbPlayer.oppPerfEloByRound);
                
                discordLog.logInfo(String.format("Mapped '%s' (CSV) to '%s' (DB) in hall '%s'", 
                    oldCsvName, mismatch.dbPlayer.name, mismatch.csvPlayer.hall));
                telegramLog.logInfo(String.format("Mapped '%s' (CSV) to '%s' (DB) in hall '%s'", 
                    oldCsvName, mismatch.dbPlayer.name, mismatch.csvPlayer.hall));
            }
            
        } else if (choice == 1) {
            // Treat as different people - continue as-is
            discordLog.logInfo("User chose to treat mismatched names as different people. Continuing...");
            telegramLog.logInfo("User chose to treat mismatched names as different people. Continuing...");
            
        } else {
            // Cancel processing
            String cancelMsg = "Processing cancelled by user due to name mismatches.";
            discordLog.logWarning(cancelMsg);
            telegramLog.logWarning(cancelMsg);
            
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🟡", "WARNING", cancelMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            
            return false;
        }

        return true;
    }

    /**
     * Checks if one name is a partial match of another (substring, comma differences)
     */
    private boolean isPartialNameMatch(String name1, String name2) {
        // Remove commas and extra spaces for comparison
        String clean1 = name1.replaceAll(",", "").replaceAll("\\s+", " ").trim();
        String clean2 = name2.replaceAll(",", "").replaceAll("\\s+", " ").trim();
        
        // Check if one is a substring of the other
        if (clean1.contains(clean2) || clean2.contains(clean1)) {
            return true;
        }
        
        // Check if all words from shorter name are in longer name
        String[] words1 = clean1.split("\\s+");
        String[] words2 = clean2.split("\\s+");
        
        String[] shorterWords = words1.length <= words2.length ? words1 : words2;
        String longerName = words1.length > words2.length ? clean1 : clean2;
        
        int matchCount = 0;
        for (String word : shorterWords) {
            if (longerName.contains(word)) {
                matchCount++;
            }
        }
        
        // If most words match, it's likely a partial name
        return matchCount >= shorterWords.length * 0.7;
    }

    /**
     * Checks if two names are similar (potential typo)
     */
    private boolean areSimilarNames(String name1, String name2) {
        int distance = levenshteinDistance(name1, name2);
        int maxLen = Math.max(name1.length(), name2.length());
        return distance > 0 && distance <= 2 && maxLen > 3;
    }

    /**
     * Calculates Levenshtein distance between two strings
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }

        return dp[s1.length()][s2.length()];
    }

    /**
     * Requests user confirmation (either via callback or CLI)
     */
    private boolean requestUserConfirmation(String message) {
        if (confirmationCallback != null) {
            return confirmationCallback.requestConfirmation(message);
        } else {
            // CLI confirmation
            System.out.println("\n" + message);
            try (Scanner scanner = new Scanner(System.in)) {
                String response = scanner.nextLine().trim().toLowerCase();
                return response.equals("yes") || response.equals("y");
            }
        }
    }
    
    /**
     * Requests user to choose from multiple options (either via callback or CLI)
     * @param message The message to display
     * @param options Array of option strings
     * @return Index of chosen option (0-based), or -1 if cancelled
     */
    private int requestMultiChoice(String message, String[] options) {
        if (multiChoiceCallback != null) {
            return multiChoiceCallback.requestChoice(message, options);
        } else {
            // CLI fallback
            System.out.println("\n" + message);
            for (int i = 0; i < options.length; i++) {
                System.out.println((i + 1) + ". " + options[i]);
            }
            System.out.print("Enter choice (1-" + options.length + "): ");
            try (Scanner scanner = new Scanner(System.in)) {
                try {
                    int choice = Integer.parseInt(scanner.nextLine().trim());
                    if (choice >= 1 && choice <= options.length) {
                        return choice - 1;
                    }
                } catch (NumberFormatException e) {
                    // Invalid input
                }
            }
            return -1; // Invalid choice
        }
    }

    /**
     * Calculates match outcome for a player
     * @param playerWinby The player's winby field from CSV
     * @param opponentWinby The opponent's winby field from CSV
     * @param opponentIsWalkover True if opponent is WALKOVER
     * @return 1 for win, 0 for draw, -1 for loss
     */
    private int calculateMatchOutcome(String playerWinby, String opponentWinby, boolean opponentIsWalkover) {
        // If opponent is walkover, player wins
        if (opponentIsWalkover) {
            return 1;
        }
        
        // Check for draw (both players have winby="draw")
        if (playerWinby.equalsIgnoreCase("draw") && opponentWinby.equalsIgnoreCase("draw")) {
            return 0;
        }
        
        // If player has a winby value (not empty and not "draw" when opponent also has "draw"), player wins
        if (!playerWinby.isEmpty()) {
            return 1;
        }
        
        // Otherwise, player lost
        return -1;
    }

    /**
     * Calculates the board win score for a player based on winby values and maxSeeds
     * Formula:
     * - If player wins by X: player gets (maxSeeds/2) + X, opponent gets (maxSeeds/2) - X
     * - If draw: both get maxSeeds/2
     * - If WALKOVER: player gets ceil(maxSeeds/2) or (maxSeeds/2)+1 if already int, opponent gets 0
     * - If no play: returns null
     * 
     * @param playerWinby Player's winby value (can be empty, "draw", or a number)
     * @param opponentWinby Opponent's winby value
     * @param opponentIsWalkover True if opponent is a WALKOVER
     * @return Player's board win score, or null if no play occurred
     */
    private Double calculateScore(String playerWinby, String opponentWinby, boolean opponentIsWalkover) {
        double maxSeeds;
        try {
            String maxSeedsStr = PropertyResolver.getProperty("settings.maxSeeds", "368.5");
            maxSeeds = Double.parseDouble(maxSeedsStr);
        } catch (NumberFormatException e) {
            maxSeeds = 368.5; // Default fallback
        }
        
        double halfSeeds = maxSeeds / 2.0;
        
        // WALKOVER opponent: player gets ceil(halfSeeds) or halfSeeds+1 if already int
        if (opponentIsWalkover) {
            if (halfSeeds == Math.floor(halfSeeds)) {
                // halfSeeds is already an integer, add 1
                return halfSeeds + 1;
            } else {
                // halfSeeds has decimal, use ceiling
                return Math.ceil(halfSeeds);
            }
        }
        
        // Draw: both players get halfSeeds
        if (playerWinby.equalsIgnoreCase("draw") && opponentWinby.equalsIgnoreCase("draw")) {
            return halfSeeds;
        }
        
        // No play: both winby are empty or don't meet win/loss criteria
        if (playerWinby.isEmpty() && opponentWinby.isEmpty()) {
            return null;
        }
        
        // Player won by X: score = (maxSeeds + X) / 2
        if (!playerWinby.isEmpty() && !playerWinby.equalsIgnoreCase("draw")) {
            try {
                double winby = Double.parseDouble(playerWinby);
                return (maxSeeds + winby) / 2.0;
            } catch (NumberFormatException e) {
                // Invalid winby format, treat as null
                return null;
            }
        }
        
        // Player lost (opponent won by Y): score = (maxSeeds - Y) / 2
        if (!opponentWinby.isEmpty() && !opponentWinby.equalsIgnoreCase("draw")) {
            try {
                double oppWinby = Double.parseDouble(opponentWinby);
                return (maxSeeds - oppWinby) / 2.0;
            } catch (NumberFormatException e) {
                // Invalid winby format, treat as null
                return null;
            }
        }
        
        return null;
    }

    /**
     * Calculates seating arrangements for players and records opponent information
     */
    private void calculateSeating(List<GameEntry> games, Map<String, PlayerStats> csvPlayers, String roundName) {
        Map<String, Integer> hallSeatCounter = new HashMap<>();

        for (GameEntry game : games) {
            // Player 1 seating and opponent info (skip if walkover)
            if (!game.name1.equalsIgnoreCase("WALKOVER")) {
                String hall1Lower = game.hall1.toLowerCase();
                int seat1 = hallSeatCounter.getOrDefault(hall1Lower, 0) + 1;
                hallSeatCounter.put(hall1Lower, seat1);
                
                PlayerStats player1 = csvPlayers.get(game.name1.toLowerCase());
                if (player1 != null) {
                    player1.seatByRound.put(roundName, seat1);
                    // Set opponent info for player 1
                    if (game.name2.equalsIgnoreCase("WALKOVER")) {
                        player1.oppNameByRound.put(roundName, "WALKOVER");
                        player1.oppHallByRound.put(roundName, game.hall2); // Preserve hall even for walkover
                        // ELO fields left null for walkover
                    } else {
                        player1.oppNameByRound.put(roundName, game.name2);
                        player1.oppHallByRound.put(roundName, game.hall2);
                        // ELO fields will be populated after calculation
                    }
                    // Calculate and store outcome for player 1
                    boolean player2IsWalkover = game.name2.equalsIgnoreCase("WALKOVER");
                    int outcome1 = calculateMatchOutcome(game.winby1, game.winby2, player2IsWalkover);
                    player1.outcomeByRound.put(roundName, outcome1);
                    
                    // Calculate and store score for player 1
                    Double score1 = calculateScore(game.winby1, game.winby2, player2IsWalkover);
                    if (score1 != null) {
                        player1.scoreByRound.put(roundName, score1);
                    }
                }
            }

            // Player 2 seating and opponent info (skip if walkover)
            if (!game.name2.equalsIgnoreCase("WALKOVER")) {
                String hall2Lower = game.hall2.toLowerCase();
                int seat2 = hallSeatCounter.getOrDefault(hall2Lower, 0) + 1;
                hallSeatCounter.put(hall2Lower, seat2);
                
                PlayerStats player2 = csvPlayers.get(game.name2.toLowerCase());
                if (player2 != null) {
                    player2.seatByRound.put(roundName, seat2);
                    // Set opponent info for player 2
                    if (game.name1.equalsIgnoreCase("WALKOVER")) {
                        player2.oppNameByRound.put(roundName, "WALKOVER");
                        player2.oppHallByRound.put(roundName, game.hall1); // Preserve hall even for walkover
                        // ELO fields left null for walkover
                    } else {
                        player2.oppNameByRound.put(roundName, game.name1);
                        player2.oppHallByRound.put(roundName, game.hall1);
                        // ELO fields will be populated after calculation
                    }
                    // Calculate and store outcome for player 2
                    boolean player1IsWalkover = game.name1.equalsIgnoreCase("WALKOVER");
                    int outcome2 = calculateMatchOutcome(game.winby2, game.winby1, player1IsWalkover);
                    player2.outcomeByRound.put(roundName, outcome2);
                    
                    // Calculate and store score for player 2
                    Double score2 = calculateScore(game.winby2, game.winby1, player1IsWalkover);
                    if (score2 != null) {
                        player2.scoreByRound.put(roundName, score2);
                    }
                }
            }
        }

        discordLog.batchInfo(String.format("Seating arrangements and opponent info calculated for round %s", roundName));
        telegramLog.batchInfo(String.format("Seating arrangements and opponent info calculated for round %s", roundName));
    }

    /**
     * Calculates ELO ratings for all players using Glicko-2 algorithm
     * Processes rounds sequentially, updating ratings after each round
     */
    private void calculateEloRatings(List<GameEntry> games, Map<String, PlayerStats> csvPlayers, 
                                     Map<String, PlayerStats> dbPlayers, String roundName) {
        
        int currentRoundIndex = Constants.ROUND_SEQUENCE.indexOf(roundName);
        
        // Step 1: Collect all unique players
        Set<String> allPlayers = new HashSet<>();
        allPlayers.addAll(csvPlayers.keySet());
        for (PlayerStats dbPlayer : dbPlayers.values()) {
            allPlayers.add(dbPlayer.name.toLowerCase());
        }
        
        // Step 2: Initialize Glicko-2 ratings for all players
        Map<String, EloCalculator.Glicko2Rating> currentTrueRatings = new HashMap<>();
        Map<String, EloCalculator.Glicko2Rating> currentPerfRatings = new HashMap<>();
        
        for (String playerKey : allPlayers) {
            PlayerStats dbPlayer = dbPlayers.get(playerKey);
            
            // Initialize TrueElo rating
            if (dbPlayer != null && dbPlayer.baseRdTrueElo != null && dbPlayer.baseVolTrueElo != null) {
                double rating = dbPlayer.baseTrueElo != null ? dbPlayer.baseTrueElo.doubleValue() : 1000.0;
                currentTrueRatings.put(playerKey, new EloCalculator.Glicko2Rating(
                    rating, dbPlayer.baseRdTrueElo, dbPlayer.baseVolTrueElo));
            } else {
                double rating = dbPlayer != null && dbPlayer.baseTrueElo != null ? 
                    dbPlayer.baseTrueElo.doubleValue() : 1000.0;
                currentTrueRatings.put(playerKey, new EloCalculator.Glicko2Rating(rating, 350.0, 0.06));
            }
            
            // Initialize PerfElo rating
            if (perfEloEnabled) {
                if (dbPlayer != null && dbPlayer.baseRdPerfElo != null && dbPlayer.baseVolPerfElo != null) {
                    double rating = dbPlayer.basePerfElo != null ? dbPlayer.basePerfElo.doubleValue() : 1000.0;
                    currentPerfRatings.put(playerKey, new EloCalculator.Glicko2Rating(
                        rating, dbPlayer.baseRdPerfElo, dbPlayer.baseVolPerfElo));
                } else {
                    double rating = dbPlayer != null && dbPlayer.basePerfElo != null ? 
                        dbPlayer.basePerfElo.doubleValue() : 1000.0;
                    currentPerfRatings.put(playerKey, new EloCalculator.Glicko2Rating(rating, 350.0, 0.06));
                }
            }
        }
        
        // Step 3: Reconstruct games for all rounds up to current
        Map<String, List<EloCalculator.Game>> gamesByRound = new HashMap<>();
        
        // Reconstruct previous rounds' games from database
        for (int i = 0; i < currentRoundIndex; i++) {
            String pastRound = Constants.ROUND_SEQUENCE.get(i);
            Map<String, GameEntry> pastGames = reconstructGamesFromDatabase(dbPlayers, pastRound);
            
            List<EloCalculator.Game> trueGames = new ArrayList<>();
            List<EloCalculator.Game> perfGames = new ArrayList<>();
            
            for (GameEntry game : pastGames.values()) {
                if (game.name1.equalsIgnoreCase("WALKOVER") || game.name2.equalsIgnoreCase("WALKOVER")) {
                    continue;
                }
                
                String player1Key = game.name1.toLowerCase();
                String player2Key = game.name2.toLowerCase();
                
                // Determine winner and point margin
                boolean player1Won;
                double pointMargin = 0.0;
                
                if (!game.winby1.isEmpty() && !game.winby2.isEmpty() && 
                    (game.winby1.equals("0") || game.winby1.equals("1")) && 
                    (game.winby2.equals("0") || game.winby2.equals("1"))) {
                    player1Won = game.winby1.equals("1");
                } else {
                    player1Won = !game.winby1.isEmpty();
                    if (player1Won && !game.winby1.isEmpty()) {
                        try { pointMargin = Double.parseDouble(game.winby1); } catch (NumberFormatException e) {}
                    } else if (!game.winby2.isEmpty()) {
                        try { pointMargin = -Double.parseDouble(game.winby2); } catch (NumberFormatException e) {}
                    }
                }
                
                double trueScore = player1Won ? 1.0 : 0.0;
                trueGames.add(new EloCalculator.Game(player1Key, player2Key, trueScore, pointMargin, pastRound));
                
                if (perfEloEnabled) {
                    perfGames.add(new EloCalculator.Game(player1Key, player2Key, trueScore, pointMargin, pastRound));
                }
            }
            
            if (!trueGames.isEmpty()) {
                gamesByRound.put(pastRound, trueGames);
            }
        }
        
        // Add current round's games
        List<EloCalculator.Game> currentTrueGames = new ArrayList<>();
        List<EloCalculator.Game> currentPerfGames = new ArrayList<>();
        
        for (GameEntry game : games) {
            if (game.name1.equalsIgnoreCase("WALKOVER") || game.name2.equalsIgnoreCase("WALKOVER")) {
                continue;
            }
            
            String player1Key = game.name1.toLowerCase();
            String player2Key = game.name2.toLowerCase();
            
            boolean player1Won;
            double pointMargin = 0.0;
            
            if (!game.winby1.isEmpty() && !game.winby2.isEmpty() && 
                (game.winby1.equals("0") || game.winby1.equals("1")) && 
                (game.winby2.equals("0") || game.winby2.equals("1"))) {
                player1Won = game.winby1.equals("1");
            } else {
                player1Won = !game.winby1.isEmpty();
                if (player1Won && !game.winby1.isEmpty()) {
                    try { pointMargin = Double.parseDouble(game.winby1); } catch (NumberFormatException e) {}
                } else if (!game.winby2.isEmpty()) {
                    try { pointMargin = -Double.parseDouble(game.winby2); } catch (NumberFormatException e) {}
                }
            }
            
            double trueScore = player1Won ? 1.0 : 0.0;
            currentTrueGames.add(new EloCalculator.Game(player1Key, player2Key, trueScore, pointMargin, roundName));
            
            // DEBUG: Print first game
            if (currentTrueGames.size() == 1) {
                System.out.println("DEBUG: First game parsed:");
                System.out.println("  Player1: " + player1Key + " (won=" + player1Won + ")");
                System.out.println("  Player2: " + player2Key);
                System.out.println("  TrueScore: " + trueScore);
                System.out.println("  PointMargin: " + pointMargin);
            }
            
            if (perfEloEnabled) {
                // For perfElo, we pass the same Game objects with point margins
                // The calculateGlicko2PerfElo method will apply sigmoid transform
                currentPerfGames.add(new EloCalculator.Game(player1Key, player2Key, trueScore, pointMargin, roundName));
            }
        }
        
        gamesByRound.put(roundName, currentTrueGames);
        if (perfEloEnabled) {
            // Store perfElo games separately for later processing
            // (Note: currently not used, but kept for potential future optimization)
        }
        
        // Step 4: Process rounds sequentially using Glicko-2 with iterative refinement
        List<String> roundsToProcess = Constants.ROUND_SEQUENCE.subList(0, currentRoundIndex + 1);
        
        System.out.println("DEBUG: About to calculate Glicko-2");
        System.out.println("  - Total players: " + allPlayers.size());
        System.out.println("  - Rounds to process: " + roundsToProcess);
        System.out.println("  - Total games: " + flattenGames(gamesByRound, roundsToProcess).size());
        System.out.println("  - First 3 players: " + allPlayers.stream().limit(3).toArray());
        
        // Determine number of iterations based on round
        int iterations = (currentRoundIndex == 0) ? 1 : 3;
        System.out.println("  - Iterations: " + iterations + " (Round " + (currentRoundIndex + 1) + ")");
        
        // Calculate TrueElo with iterative refinement
        Map<String, EloCalculator.Glicko2Rating> initialTrueRatings = new HashMap<>(currentTrueRatings);
        EloCalculator.Glicko2Result trueResult = null;
        
        for (int iter = 0; iter < iterations; iter++) {
            System.out.println("  - TrueElo iteration " + (iter + 1) + "/" + iterations);
            trueResult = EloCalculator.calculateGlicko2TrueElo(
                flattenGames(gamesByRound, roundsToProcess),
                allPlayers,
                initialTrueRatings,  // Always use original base ratings, not previous iteration's final ratings
                roundsToProcess
            );
            
            // Note: We do NOT update initialTrueRatings for next iteration
            // Each iteration should start from the same base ratings to converge properly
        }
        
        System.out.println("DEBUG: TrueElo calculation complete");
        System.out.println("  - Rounds in result: " + trueResult.ratingsByRound.keySet());
        
        // Calculate PerfElo with iterative refinement
        EloCalculator.Glicko2Result perfResult = null;
        if (perfEloEnabled) {
            Map<String, EloCalculator.Glicko2Rating> initialPerfRatings = new HashMap<>(currentPerfRatings);
            
            for (int iter = 0; iter < iterations; iter++) {
                System.out.println("  - PerfElo iteration " + (iter + 1) + "/" + iterations);
                perfResult = EloCalculator.calculateGlicko2PerfElo(
                    flattenGames(gamesByRound, roundsToProcess),
                    allPlayers,
                    initialPerfRatings,  // Always use original base ratings, not previous iteration's final ratings
                    roundsToProcess
                );
                
                // Note: We do NOT update initialPerfRatings for next iteration
                // Each iteration should start from the same base ratings to converge properly
            }
        }
        
        // Step 5: Store calculated ratings in player stats
        int storedCount = 0;
        for (int i = 0; i <= currentRoundIndex; i++) {
            String round = Constants.ROUND_SEQUENCE.get(i);
            Map<String, EloCalculator.Glicko2Rating> roundTrueRatings = trueResult.ratingsByRound.get(round);
            Map<String, EloCalculator.Glicko2Rating> roundPerfRatings = 
                perfEloEnabled ? perfResult.ratingsByRound.get(round) : null;
            
            System.out.println("DEBUG: Storing ratings for round " + round);
            System.out.println("  - roundTrueRatings has " + (roundTrueRatings != null ? roundTrueRatings.size() : 0) + " players");
            
            for (String playerKey : allPlayers) {
                // Update csvPlayers (current round data)
                PlayerStats csvPlayer = csvPlayers.get(playerKey);
                if (csvPlayer != null) {
                    if (roundTrueRatings != null && roundTrueRatings.containsKey(playerKey)) {
                        EloCalculator.Glicko2Rating rating = roundTrueRatings.get(playerKey);
                        csvPlayer.trueEloByRound.put(round, (int) Math.round(rating.rating));
                        csvPlayer.rdTrueEloByRound.put(round, rating.rd);
                        csvPlayer.volTrueEloByRound.put(round, rating.volatility);
                        storedCount++;
                        
                        if (storedCount <= 2) {
                            System.out.println("  Sample storage: " + playerKey + " -> " + 
                                Math.round(rating.rating) + " (RD:" + String.format("%.2f", rating.rd) + ")");
                        }
                    }
                    
                    if (perfEloEnabled && roundPerfRatings != null && roundPerfRatings.containsKey(playerKey)) {
                        EloCalculator.Glicko2Rating rating = roundPerfRatings.get(playerKey);
                        csvPlayer.perfEloByRound.put(round, (int) Math.round(rating.rating));
                        csvPlayer.rdPerfEloByRound.put(round, rating.rd);
                        csvPlayer.volPerfEloByRound.put(round, rating.volatility);
                    }
                }
                
                // Update dbPlayers (for previous rounds)
                PlayerStats dbPlayer = dbPlayers.get(playerKey);
                if (dbPlayer != null && i < currentRoundIndex) {
                    if (roundTrueRatings != null && roundTrueRatings.containsKey(playerKey)) {
                        EloCalculator.Glicko2Rating rating = roundTrueRatings.get(playerKey);
                        dbPlayer.trueEloByRound.put(round, (int) Math.round(rating.rating));
                        dbPlayer.rdTrueEloByRound.put(round, rating.rd);
                        dbPlayer.volTrueEloByRound.put(round, rating.volatility);
                    }
                    
                    if (perfEloEnabled && roundPerfRatings != null && roundPerfRatings.containsKey(playerKey)) {
                        EloCalculator.Glicko2Rating rating = roundPerfRatings.get(playerKey);
                        dbPlayer.perfEloByRound.put(round, (int) Math.round(rating.rating));
                        dbPlayer.rdPerfEloByRound.put(round, rating.rd);
                        dbPlayer.volPerfEloByRound.put(round, rating.volatility);
                    }
                }
            }
        }
        
        // Step 6: Populate opponent ELO values for current round
        for (PlayerStats player : csvPlayers.values()) {
            String oppName = player.oppNameByRound.get(roundName);
            if (oppName != null && !oppName.equalsIgnoreCase("WALKOVER")) {
                String oppKey = oppName.toLowerCase();
                PlayerStats opponent = csvPlayers.get(oppKey);
                if (opponent != null) {
                    player.oppTrueEloByRound.put(roundName, opponent.trueEloByRound.get(roundName));
                    player.oppPerfEloByRound.put(roundName, opponent.perfEloByRound.get(roundName));
                }
            }
        }
        
        discordLog.batchInfo(String.format("Glicko-2 ELO ratings calculated for round %s", roundName));
        telegramLog.batchInfo(String.format("Glicko-2 ELO ratings calculated for round %s", roundName));
    }
    
    /**
     * Flattens games from multiple rounds into a single list
     */
    private List<EloCalculator.Game> flattenGames(Map<String, List<EloCalculator.Game>> gamesByRound, List<String> rounds) {
        List<EloCalculator.Game> allGames = new ArrayList<>();
        for (String round : rounds) {
            List<EloCalculator.Game> roundGames = gamesByRound.get(round);
            if (roundGames != null) {
                allGames.addAll(roundGames);
            }
        }
        return allGames;
    }

    /**
     * Reconstructs games from database using opponent information
     * Returns map of player name -> GameEntry
     */
    private Map<String, GameEntry> reconstructGamesFromDatabase(Map<String, PlayerStats> dbPlayers, String roundName) {
        Map<String, GameEntry> games = new HashMap<>();
        Set<String> processedPairs = new HashSet<>();
        
        for (Map.Entry<String, PlayerStats> entry : dbPlayers.entrySet()) {
            String playerKey = entry.getKey();
            PlayerStats player = entry.getValue();
            
            String oppName = player.oppNameByRound.get(roundName);
            if (oppName == null || oppName.equalsIgnoreCase("WALKOVER")) {
                continue;
            }
            
            String oppKey = oppName.toLowerCase();
            
            // Create a unique pair identifier (sorted to avoid duplicates)
            String pairKey = playerKey.compareTo(oppKey) < 0 
                ? playerKey + "|" + oppKey 
                : oppKey + "|" + playerKey;
            
            if (processedPairs.contains(pairKey)) {
                continue; // Already processed this matchup
            }
            processedPairs.add(pairKey);
            
            // Get opponent stats
            PlayerStats opponent = dbPlayers.get(oppKey);
            if (opponent == null) {
                continue; // Opponent not in database
            }
            
            // Determine who won based on ELO changes or seat numbers
            // For now, we'll need to determine winner from the fact that someone has a higher seat = won
            Integer playerSeat = player.seatByRound.get(roundName);
            Integer oppSeat = opponent.seatByRound.get(roundName);
            
            // We can't fully reconstruct winby without original CSV, but we can reconstruct who played whom
            // Set winby1 = "1" if player had higher outcome, winby2 = "1" if opponent did
            // For simplicity, if we can't determine winner, skip this game
            if (playerSeat == null || oppSeat == null) {
                continue;
            }
            
            // Use trueElo comparison to guess winner (higher ELO gain likely means win)
            Integer playerElo = player.trueEloByRound.get(roundName);
            Integer oppElo = opponent.trueEloByRound.get(roundName);
            
            // Get previous ELOs to determine change
            int roundIndex = Constants.ROUND_SEQUENCE.indexOf(roundName);
            Integer playerPrevElo = null;
            Integer oppPrevElo = null;
            
            if (roundIndex > 0) {
                String prevRound = Constants.ROUND_SEQUENCE.get(roundIndex - 1);
                playerPrevElo = player.trueEloByRound.get(prevRound);
                oppPrevElo = opponent.trueEloByRound.get(prevRound);
            }
            
            if (playerPrevElo == null) playerPrevElo = player.baseTrueElo != null ? player.baseTrueElo : 1000;
            if (oppPrevElo == null) oppPrevElo = opponent.baseTrueElo != null ? opponent.baseTrueElo : 1000;
            
            if (playerElo == null || oppElo == null) {
                continue; // Can't reconstruct without ELO data
            }
            
            int playerChange = playerElo - playerPrevElo;
            int oppChange = oppElo - oppPrevElo;
            
            // Whoever gained more ELO likely won (this is an approximation)
            String winby1 = playerChange > oppChange ? "1" : "0";
            String winby2 = oppChange > playerChange ? "1" : "0";
            
            GameEntry game = new GameEntry(
                player.name,
                player.hall != null ? player.hall : "unknown",
                winby1,
                opponent.name,
                opponent.oppHallByRound.get(roundName) != null ? opponent.oppHallByRound.get(roundName) : (opponent.hall != null ? opponent.hall : "unknown"),
                winby2
            );
            
            games.put(playerKey, game);
        }
        
        return games;
    }

    /**
     * Handles players who are in the database but not in the current CSV
     */
    private void handleMissingPlayers(Map<String, PlayerStats> csvPlayers, Map<String, PlayerStats> dbPlayers, String roundName) {
        // Get halls that played this round
        Set<String> playingHalls = csvPlayers.values().stream()
            .filter(p -> p.hall != null)
            .map(p -> p.hall.toLowerCase())
            .collect(Collectors.toSet());

        String previousRound = EloCalculator.getPreviousRound(roundName);

        for (Map.Entry<String, PlayerStats> entry : dbPlayers.entrySet()) {
            String playerKey = entry.getKey();
            PlayerStats dbPlayer = entry.getValue();

            // Skip if player is in CSV
            if (csvPlayers.containsKey(playerKey)) {
                continue;
            }

            // Skip if player has no hall (from playerExport import)
            if (dbPlayer.hall == null) {
                continue;
            }

            // Check if their hall played this round
            if (!playingHalls.contains(dbPlayer.hall.toLowerCase())) {
                // Hall didn't play - don't update this player
                continue;
            }

            // Hall played but player didn't - carry forward previous ELO
            if (previousRound != null) {
                // Find the most recent previous ELO
                Integer prevTrueElo = null;
                Integer prevPerfElo = null;
                Integer prevSeat = null;
                Integer prevOutcome = null;

                // Search backwards from previous round
                int prevRoundIdx = Constants.ROUND_SEQUENCE.indexOf(previousRound);
                for (int i = prevRoundIdx; i >= 0; i--) {
                    String checkRound = Constants.ROUND_SEQUENCE.get(i);
                    Integer trueElo = dbPlayer.trueEloByRound.get(checkRound);
                    if (trueElo != null) {
                        prevTrueElo = trueElo;
                        prevPerfElo = dbPlayer.perfEloByRound.get(checkRound);
                        prevSeat = dbPlayer.seatByRound.get(checkRound);
                        prevOutcome = dbPlayer.outcomeByRound.get(checkRound);
                        break;
                    }
                }

                // Update current round with previous values (or defaults)
                dbPlayer.trueEloByRound.put(roundName, prevTrueElo != null ? prevTrueElo : Constants.BASE_ELO);
                dbPlayer.perfEloByRound.put(roundName, perfEloEnabled ? (prevPerfElo != null ? prevPerfElo : Constants.BASE_ELO) : null);
                dbPlayer.seatByRound.put(roundName, null); // No seat if didn't play
                dbPlayer.outcomeByRound.put(roundName, null); // No outcome if didn't play
                
                // No opponent data since player didn't play
                dbPlayer.oppHallByRound.put(roundName, null);
                dbPlayer.oppNameByRound.put(roundName, null);
                dbPlayer.oppTrueEloByRound.put(roundName, null);
                dbPlayer.oppPerfEloByRound.put(roundName, null);

                // Preserve active status from database (don't change it)
                // If player was active before, keep them active

                // Add to csvPlayers so it gets updated
                csvPlayers.put(playerKey, dbPlayer);
            }
        }

        // Set future rounds to null
        int currentRoundIdx = Constants.ROUND_SEQUENCE.indexOf(roundName);
        for (PlayerStats player : csvPlayers.values()) {
            for (int i = currentRoundIdx + 1; i < Constants.ROUND_SEQUENCE.size(); i++) {
                String futureRound = Constants.ROUND_SEQUENCE.get(i);
                player.trueEloByRound.put(futureRound, null);
                player.perfEloByRound.put(futureRound, null);
                player.seatByRound.put(futureRound, null);
                player.outcomeByRound.put(futureRound, null);
                player.oppHallByRound.put(futureRound, null);
                player.oppNameByRound.put(futureRound, null);
                player.oppTrueEloByRound.put(futureRound, null);
                player.oppPerfEloByRound.put(futureRound, null);
            }
        }
    }

    /**
     * Updates the database with all player stats
     */
    private void updateDatabase(Map<String, PlayerStats> csvPlayers, Map<String, PlayerStats> dbPlayers, String roundName) throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);

            try {
                int newPlayers = 0;
                int updatedPlayers = 0;

                for (PlayerStats csvPlayer : csvPlayers.values()) {
                    if (csvPlayer.existsInDb) {
                        // This CSV player is mapped to an existing DB player
                        // Find the DB player by ID
                        PlayerStats dbPlayer = null;
                        for (PlayerStats db : dbPlayers.values()) {
                            if (db.dbId == csvPlayer.dbId) {
                                dbPlayer = db;
                                break;
                            }
                        }
                        
                        if (dbPlayer != null) {
                            // Update existing player
                            updatePlayerInDatabase(conn, csvPlayer, dbPlayer, roundName);
                            updatedPlayers++;
                            discordLog.batchInfo(String.format("Updated player: %s (ID: %d)", csvPlayer.name, dbPlayer.dbId));
                            telegramLog.batchInfo(String.format("Updated player: %s (ID: %d)", csvPlayer.name, dbPlayer.dbId));
                        } else {
                            // This shouldn't happen, but handle it
                            discordLog.logWarning(String.format("Warning: CSV player '%s' marked as existsInDb but DB player not found (ID: %d)", 
                                csvPlayer.name, csvPlayer.dbId));
                            telegramLog.logWarning(String.format("Warning: CSV player '%s' marked as existsInDb but DB player not found (ID: %d)", 
                                csvPlayer.name, csvPlayer.dbId));
                        }
                    } else {
                        // Check if this player exists in DB by exact name match
                        String playerKey = csvPlayer.name.toLowerCase();
                        PlayerStats dbPlayer = dbPlayers.get(playerKey);
                        
                        if (dbPlayer != null) {
                            // Player exists - update
                            updatePlayerInDatabase(conn, csvPlayer, dbPlayer, roundName);
                            updatedPlayers++;
                            discordLog.batchInfo(String.format("Updated player: %s", csvPlayer.name));
                            telegramLog.batchInfo(String.format("Updated player: %s", csvPlayer.name));
                        } else {
                            // Insert new player
                            insertPlayerInDatabase(conn, csvPlayer, roundName);
                            newPlayers++;
                            discordLog.batchInfo(String.format("Inserted new player: %s", csvPlayer.name));
                            telegramLog.batchInfo(String.format("Inserted new player: %s", csvPlayer.name));
                        }
                    }
                }

                conn.commit();
                
                discordLog.batchInfo(String.format("Database updated: %d new players, %d updated players", newPlayers, updatedPlayers));
                telegramLog.batchInfo(String.format("Database updated: %d new players, %d updated players", newPlayers, updatedPlayers));

            } catch (SQLException e) {
                conn.rollback();
                throw new Exception("Database transaction failed: " + e.getMessage());
            }
        }
    }

    /**
     * Updates an existing player in the database
     * Updates current and future rounds only - previous rounds remain unchanged
     */
    private void updatePlayerInDatabase(Connection conn, PlayerStats player, PlayerStats dbPlayer, String roundName) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE A1_PlayerStats SET ");
        List<Object> params = new ArrayList<>();

        // Get current timestamp
        String currentTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

        // Always update hall, capped status, active status, and dateLogged
        // Note: active status can only go from false->true, never true->false
        // If player was already active (1) in DB, keep them active even if current round shows false
        boolean finalActive = player.active || dbPlayer.active; // Once active, always active
        sql.append("hall = ?, capped = ?, active = ?, dateLogged = ?, ");
        params.add(player.hall);
        params.add(player.capped ? 1 : 0); // SQLite boolean as 0/1
        params.add(finalActive ? 1 : 0); // SQLite boolean as 0/1
        params.add(currentTimestamp);

        // Update ONLY current and future rounds (previous rounds remain unchanged)
        int currentRoundIndex = Constants.ROUND_SEQUENCE.indexOf(roundName);
        
        for (int i = currentRoundIndex; i < Constants.ROUND_SEQUENCE.size(); i++) {
            String round = Constants.ROUND_SEQUENCE.get(i);
            
            String trueEloCol = getRoundColumnName("trueElo", round);
            String perfEloCol = getRoundColumnName("perfElo", round);
            String rdTrueEloCol = getRoundColumnName("rdTrueElo", round);
            String volTrueEloCol = getRoundColumnName("volTrueElo", round);
            String rdPerfEloCol = getRoundColumnName("rdPerfElo", round);
            String volPerfEloCol = getRoundColumnName("volPerfElo", round);
            String seatCol = getRoundColumnName("seat", round);
            String outcomeCol = getRoundColumnName("outcome", round);
            String oppHallCol = getRoundColumnName("oppHall", round);
            String oppNameCol = getRoundColumnName("oppName", round);
            String oppTrueEloCol = getRoundColumnName("oppTrueElo", round);
            String oppPerfEloCol = getRoundColumnName("oppPerfElo", round);
            String scoreCol = getRoundColumnName("score", round);

            Integer trueElo = player.trueEloByRound.get(round);
            Integer perfElo = player.perfEloByRound.get(round);
            Double rdTrueElo = player.rdTrueEloByRound.get(round);
            Double volTrueElo = player.volTrueEloByRound.get(round);
            Double rdPerfElo = player.rdPerfEloByRound.get(round);
            Double volPerfElo = player.volPerfEloByRound.get(round);
            Integer seat = player.seatByRound.get(round);
            Integer outcome = player.outcomeByRound.get(round);
            String oppHall = player.oppHallByRound.get(round);
            String oppName = player.oppNameByRound.get(round);
            Integer oppTrueElo = player.oppTrueEloByRound.get(round);
            Integer oppPerfElo = player.oppPerfEloByRound.get(round);
            Double score = player.scoreByRound.get(round);

            sql.append(trueEloCol).append(" = ?, ");
            params.add(trueElo);

            sql.append(perfEloCol).append(" = ?, ");
            params.add(perfElo);
            
            sql.append(rdTrueEloCol).append(" = ?, ");
            params.add(rdTrueElo);
            
            sql.append(volTrueEloCol).append(" = ?, ");
            params.add(volTrueElo);
            
            sql.append(rdPerfEloCol).append(" = ?, ");
            params.add(rdPerfElo);
            
            sql.append(volPerfEloCol).append(" = ?, ");
            params.add(volPerfElo);

            sql.append(seatCol).append(" = ?, ");
            params.add(seat);

            sql.append(outcomeCol).append(" = ?, ");
            params.add(outcome);

            sql.append(oppHallCol).append(" = ?, ");
            params.add(oppHall);

            sql.append(oppNameCol).append(" = ?, ");
            params.add(oppName);

            sql.append(oppTrueEloCol).append(" = ?, ");
            params.add(oppTrueElo);

            sql.append(oppPerfEloCol).append(" = ?, ");
            params.add(oppPerfElo);

            sql.append(scoreCol).append(" = ?, ");
            params.add(score);
        }

        // Remove trailing comma
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE id = ?");
        params.add(dbPlayer.dbId);

        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            pstmt.executeUpdate();
        }
    }

    /**
     * Inserts a new player into the database
     */
    private void insertPlayerInDatabase(Connection conn, PlayerStats player, String currentRound) throws SQLException {
        StringBuilder sql = new StringBuilder("INSERT INTO A1_PlayerStats (name, hall, capped, active, baseTrueElo, basePerfElo, baseRdTrueElo, baseVolTrueElo, baseRdPerfElo, baseVolPerfElo, dateLogged");
        StringBuilder values = new StringBuilder("VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?");
        List<Object> params = new ArrayList<>();

        // Get current timestamp
        String currentTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

        params.add(player.name);
        params.add(player.hall);
        params.add(player.capped ? 1 : 0); // SQLite boolean as 0/1
        params.add(player.active ? 1 : 0); // SQLite boolean as 0/1
        // Use existing base ELO if set (from import), otherwise default to Constants.BASE_ELO
        params.add(player.baseTrueElo != null ? player.baseTrueElo : Constants.BASE_ELO);
        params.add(player.basePerfElo != null ? player.basePerfElo : (perfEloEnabled ? Constants.BASE_ELO : null));
        params.add(player.baseRdTrueElo != null ? player.baseRdTrueElo : 350.0); // Default RD
        params.add(player.baseVolTrueElo != null ? player.baseVolTrueElo : 0.06); // Default volatility
        params.add(player.baseRdPerfElo != null ? player.baseRdPerfElo : (perfEloEnabled ? 350.0 : null));
        params.add(player.baseVolPerfElo != null ? player.baseVolPerfElo : (perfEloEnabled ? 0.06 : null));
        params.add(currentTimestamp);

        // Add all round columns
        for (String round : Constants.ROUND_SEQUENCE) {
            String trueEloCol = getRoundColumnName("trueElo", round);
            String perfEloCol = getRoundColumnName("perfElo", round);
            String rdTrueEloCol = getRoundColumnName("rdTrueElo", round);
            String volTrueEloCol = getRoundColumnName("volTrueElo", round);
            String rdPerfEloCol = getRoundColumnName("rdPerfElo", round);
            String volPerfEloCol = getRoundColumnName("volPerfElo", round);
            String seatCol = getRoundColumnName("seat", round);
            String outcomeCol = getRoundColumnName("outcome", round);
            String oppHallCol = getRoundColumnName("oppHall", round);
            String oppNameCol = getRoundColumnName("oppName", round);
            String oppTrueEloCol = getRoundColumnName("oppTrueElo", round);
            String oppPerfEloCol = getRoundColumnName("oppPerfElo", round);
            String scoreCol = getRoundColumnName("score", round);

            sql.append(", ").append(trueEloCol);
            sql.append(", ").append(perfEloCol);
            sql.append(", ").append(rdTrueEloCol);
            sql.append(", ").append(volTrueEloCol);
            sql.append(", ").append(rdPerfEloCol);
            sql.append(", ").append(volPerfEloCol);
            sql.append(", ").append(seatCol);
            sql.append(", ").append(outcomeCol);
            sql.append(", ").append(oppHallCol);
            sql.append(", ").append(oppNameCol);
            sql.append(", ").append(oppTrueEloCol);
            sql.append(", ").append(oppPerfEloCol);
            sql.append(", ").append(scoreCol);
            
            values.append(", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?");

            // Fill previous rounds with base ELO, current round with calculated, future rounds with null
            int currentIdx = Constants.ROUND_SEQUENCE.indexOf(currentRound);
            int roundIdx = Constants.ROUND_SEQUENCE.indexOf(round);

            if (roundIdx < currentIdx) {
                // Previous rounds - fill with base ELO if available, otherwise Constants.BASE_ELO
                params.add(player.baseTrueElo != null ? player.baseTrueElo : Constants.BASE_ELO);
                params.add(perfEloEnabled ? (player.basePerfElo != null ? player.basePerfElo : Constants.BASE_ELO) : null);
                params.add(player.baseRdTrueElo != null ? player.baseRdTrueElo : 350.0);
                params.add(player.baseVolTrueElo != null ? player.baseVolTrueElo : 0.06);
                params.add(perfEloEnabled ? (player.baseRdPerfElo != null ? player.baseRdPerfElo : 350.0) : null);
                params.add(perfEloEnabled ? (player.baseVolPerfElo != null ? player.baseVolPerfElo : 0.06) : null);
                params.add(null); // seat
                params.add(null); // outcome
                params.add(null); // oppHall
                params.add(null); // oppName
                params.add(null); // oppTrueElo
                params.add(null); // oppPerfElo
                params.add(null); // score
            } else if (roundIdx == currentIdx) {
                // Current round - use calculated values
                params.add(player.trueEloByRound.get(round));
                params.add(player.perfEloByRound.get(round));
                params.add(player.rdTrueEloByRound.get(round));
                params.add(player.volTrueEloByRound.get(round));
                params.add(player.rdPerfEloByRound.get(round));
                params.add(player.volPerfEloByRound.get(round));
                params.add(player.seatByRound.get(round));
                params.add(player.outcomeByRound.get(round));
                params.add(player.oppHallByRound.get(round));
                params.add(player.oppNameByRound.get(round));
                params.add(player.oppTrueEloByRound.get(round));
                params.add(player.oppPerfEloByRound.get(round));
                params.add(player.scoreByRound.get(round));
            } else {
                // Future rounds - null
                params.add(null); // trueElo
                params.add(null); // perfElo
                params.add(null); // rdTrueElo
                params.add(null); // volTrueElo
                params.add(null); // rdPerfElo
                params.add(null); // volPerfElo
                params.add(null); // seat
                params.add(null); // outcome
                params.add(null); // oppHall
                params.add(null); // oppName
                params.add(null); // oppTrueElo
                params.add(null); // oppPerfElo
                params.add(null); // score
            }
        }

        sql.append(") ").append(values).append(")");

        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            pstmt.executeUpdate();
        }
    }

    /**
     * Updates the mapped field in A2_CappedPlayers for players that were successfully matched
     */
    private void updateA2MappedField(Map<String, PlayerStats> csvPlayers) {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            String sql = "UPDATE A2_CappedPlayers SET mapped = 1 WHERE LOWER(name) = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int updateCount = 0;
                for (Map.Entry<String, PlayerStats> entry : csvPlayers.entrySet()) {
                    PlayerStats player = entry.getValue();
                    if (player.capped) {
                        pstmt.setString(1, entry.getKey());
                        int updated = pstmt.executeUpdate();
                        if (updated > 0) {
                            updateCount++;
                        }
                    }
                }
                if (updateCount > 0) {
                    discordLog.batchInfo(String.format("Updated 'mapped' field for %d players in A2_CappedPlayers", updateCount));
                    telegramLog.batchInfo(String.format("Updated 'mapped' field for %d players in A2_CappedPlayers", updateCount));
                }
            }
        } catch (SQLException e) {
            System.err.println("Warning: Could not update A2 mapped field: " + e.getMessage());
        }
    }

    /**
     * Gets the database column name for a round
     */
    private String getRoundColumnName(String prefix, String round) {
        if (round.startsWith("t")) {
            return prefix + "T" + round.substring(1);
        } else {
            return prefix + "R" + round;
        }
    }

    /**
     * Checks if a round has already been processed
     * @param roundName The round to check
     * @param dbPlayers Map of players from database
     * @return true if the round has been processed, false otherwise
     */
    private boolean isRoundAlreadyProcessed(String roundName, Map<String, PlayerStats> dbPlayers) {
        if (dbPlayers.isEmpty()) {
            return false;
        }
        
        // Check if any player has a non-null ELO value for this round
        for (PlayerStats player : dbPlayers.values()) {
            Integer trueElo = player.trueEloByRound.get(roundName);
            if (trueElo != null) {
                return true; // Found at least one player with this round processed
            }
        }
        
        return false;
    }

    /**
     * Clears all rounds after (and including) the specified round
     * @param fromRound The round from which to start clearing (inclusive)
     */
    private void clearFutureRounds(String fromRound) throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        int fromIndex = Constants.ROUND_SEQUENCE.indexOf(fromRound);
        
        if (fromIndex == -1) {
            throw new IllegalArgumentException("Invalid round name: " + fromRound);
        }
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            
            try {
                StringBuilder sql = new StringBuilder("UPDATE A1_PlayerStats SET ");
                
                // Set all rounds from fromRound onwards to NULL
                for (int i = fromIndex; i < Constants.ROUND_SEQUENCE.size(); i++) {
                    String round = Constants.ROUND_SEQUENCE.get(i);
                    
                    sql.append(getRoundColumnName("trueElo", round)).append(" = NULL, ");
                    sql.append(getRoundColumnName("perfElo", round)).append(" = NULL, ");
                    sql.append(getRoundColumnName("seat", round)).append(" = NULL, ");
                    sql.append(getRoundColumnName("oppHall", round)).append(" = NULL, ");
                    sql.append(getRoundColumnName("oppName", round)).append(" = NULL, ");
                    sql.append(getRoundColumnName("oppTrueElo", round)).append(" = NULL, ");
                    sql.append(getRoundColumnName("oppPerfElo", round)).append(" = NULL, ");
                }
                
                // Remove trailing comma and space
                sql.setLength(sql.length() - 2);
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                    int rowsAffected = pstmt.executeUpdate();
                    conn.commit();
                    
                    discordLog.batchInfo(String.format("Cleared %d rounds for %d players", 
                        Constants.ROUND_SEQUENCE.size() - fromIndex, rowsAffected));
                    telegramLog.batchInfo(String.format("Cleared %d rounds for %d players", 
                        Constants.ROUND_SEQUENCE.size() - fromIndex, rowsAffected));
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Checks if the A1_PlayerStats table is empty
     * @return true if table is empty, false otherwise
     */
    private boolean isTableEmpty() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            String sql = "SELECT COUNT(*) as count FROM A1_PlayerStats";
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt("count");
                    return count == 0;
                }
            }
        } catch (SQLException e) {
            throw new Exception("Failed to check if table is empty: " + e.getMessage());
        }
        
        return false;
    }

    /**
     * Deletes all player data from the A1_PlayerStats table
     */
    private void deleteAllPlayerData() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            
            try {
                String sql = "DELETE FROM A1_PlayerStats";
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate(sql);
                }
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new Exception("Failed to delete player data: " + e.getMessage());
            }
        } catch (SQLException e) {
            throw new Exception("Database connection failed: " + e.getMessage());
        }
    }

    /**
     * Main method for testing and CLI usage
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: A1_PlayerStats <csv_file_path> <round_name>");
            System.err.println("Example: A1_PlayerStats round_1.csv 1");
            System.exit(1);
        }

        String csvPath = args[0];
        String roundName = args[1];

        A1_PlayerStats processor = new A1_PlayerStats();
        boolean success = processor.processRound(csvPath, roundName);
        
        System.exit(success ? 0 : 1);
    }
}
