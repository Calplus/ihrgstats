package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.calculations.EloCalculator;
import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.PropertyResolver;

import java.io.*;
import java.nio.file.Paths;
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
    private UploadChatMessageCallback uploadChatCallback;

    // Round sequence
    private static final List<String> ROUND_SEQUENCE = Arrays.asList("1", "2", "3", "4", "5", "6", "t16", "t8", "t4", "t2");
    private final int BASE_ELO = 1000;

    /**
     * Interface for user confirmation callbacks (used by Telegram listener)
     */
    public interface UserConfirmationCallback {
        boolean requestConfirmation(String message);
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
        this.dbPath = Paths.get(System.getProperty("user.dir"), "database", "core", "default.db").toString();
        this.loadConfig();
        this.confirmationCallback = null; // Default to CLI confirmation
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
        boolean existsInDb = false;
        int dbId = -1;
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
                "WARNING: round_%s has already been processed!\n\n" +
                "If you continue:\n" +
                "- Round %s will be reprocessed with the new data\n" +
                "- ALL rounds after round %s will be DELETED\n" +
                "- You will need to re-upload those rounds again\n\n" +
                "Do you want to continue and reprocess this round? (yes/no)",
                roundName, roundName, roundName
            );
            
            discordLog.flushBatch();
            telegramLog.flushBatch();
            
            boolean confirmed = requestUserConfirmation(warningMsg);
            
            if (!confirmed) {
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
     * Validates that the previous round has been processed
     */
    private boolean validateRoundSequence(String roundName) {
        int roundIndex = ROUND_SEQUENCE.indexOf(roundName);
        if (roundIndex == -1) {
            String errorMsg = String.format("Invalid round name: %s. Valid rounds: %s", roundName, String.join(", ", ROUND_SEQUENCE));
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
        String previousRound = ROUND_SEQUENCE.get(roundIndex - 1);
        String columnName = "trueEloR" + previousRound.toUpperCase();
        if (previousRound.startsWith("t")) {
            columnName = "trueEloT" + previousRound.substring(1);
        }

        try {
            String jdbcUrl = "jdbc:sqlite:" + dbPath;
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
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
                            previousRound, lastProcessedMsg, String.join(", ", ROUND_SEQUENCE));
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
                String round = ROUND_SEQUENCE.get(i);
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
                    String round = ROUND_SEQUENCE.get(i);
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
                    message.append(String.format("⚠️ Tournament bracket (t16) is being uploaded, but round 6 has not been processed.\n"));
                    if (lastProcessedRound != null) {
                        message.append(String.format("Last processed round: %s\n\n", lastProcessedRound));
                    } else {
                        message.append("Last processed round: none (database is empty)\n\n");
                    }
                    message.append("Has the tournament moved to bracket matchup format, skipping the remaining round-robin rounds?\n\n");
                    message.append("Answer 'yes' to fill missing rounds (");
                    
                    // List missing rounds
                    List<String> missingRounds = new ArrayList<>();
                    if (lastProcessedRound != null) {
                        int lastIdx = ROUND_SEQUENCE.indexOf(lastProcessedRound);
                        for (int i = lastIdx + 1; i < t16Index; i++) {
                            missingRounds.add(ROUND_SEQUENCE.get(i));
                        }
                    } else {
                        // No rounds processed - all rounds 1-6 are missing
                        for (int i = 0; i < t16Index; i++) {
                            missingRounds.add(ROUND_SEQUENCE.get(i));
                        }
                    }
                    message.append(String.join(", ", missingRounds));
                    message.append(") with last known ELO values and continue.\n");
                    message.append("Answer 'no' to stop processing.");

                    discordLog.flushBatch();
                    telegramLog.flushBatch();
                    
                    // Send warning message to upload chat if callback is set
                    if (uploadChatCallback != null) {
                        String formattedMsg = formatUploadMessage("⚠️", "WARNING", message.toString());
                        uploadChatCallback.sendMessage(formattedMsg);
                    }

                    boolean confirmed = requestUserConfirmation(message.toString());

                    if (!confirmed) {
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
                    
                    // Validate winby logic: if both filled, must be "0" or "1"
                    if (!winby1.isEmpty() && !winby2.isEmpty()) {
                        if (!(winby1.equals("0") || winby1.equals("1")) || !(winby2.equals("0") || winby2.equals("1"))) {
                            throw new Exception(String.format("Invalid CSV format at line %d: When both winby columns are filled, values must be '0' (loss) or '1' (win)", lineNumber));
                        }
                        // Exactly one must be "1" and one must be "0"
                        if (winby1.equals(winby2)) {
                            throw new Exception(String.format("Invalid CSV format at line %d: When both winby columns are filled, one must be '0' and the other '1'", lineNumber));
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
                    for (String round : ROUND_SEQUENCE) {
                        String trueEloCol = getRoundColumnName("trueElo", round);
                        String perfEloCol = getRoundColumnName("perfElo", round);
                        String rdTrueEloCol = getRoundColumnName("rdTrueElo", round);
                        String volTrueEloCol = getRoundColumnName("volTrueElo", round);
                        String rdPerfEloCol = getRoundColumnName("rdPerfElo", round);
                        String volPerfEloCol = getRoundColumnName("volPerfElo", round);
                        String seatCol = getRoundColumnName("seat", round);
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
            StringBuilder message = new StringBuilder("WARNING: The following halls exceed the 5-player limit:\\n\\n");
            for (String violation : violations) {
                message.append("  - ").append(violation).append("\\n");
            }
            message.append("\\nDo you want to continue processing? (yes/no)");

            discordLog.flushBatch();
            telegramLog.flushBatch();

            boolean confirmed = requestUserConfirmation(message.toString());
            
            if (!confirmed) {
                String errorMsg = "Processing cancelled due to player count violations.";
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                
                // Send error to upload chat if callback is set
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
     */
    private boolean validatePlayerMatches(Map<String, PlayerStats> csvPlayers, Map<String, PlayerStats> dbPlayers) {
        List<String> warnings = new ArrayList<>();
        List<String> majorIssues = new ArrayList<>();
        List<String> crossHallIssues = new ArrayList<>();

        for (Map.Entry<String, PlayerStats> entry : csvPlayers.entrySet()) {
            String key = entry.getKey();
            PlayerStats csvPlayer = entry.getValue();
            PlayerStats dbPlayer = dbPlayers.get(key);

            if (dbPlayer != null) {
                // Player exists in database - check hall match
                if (!csvPlayer.hall.equalsIgnoreCase(dbPlayer.hall)) {
                    warnings.add(String.format("⚠️ Player '%s' hall mismatch: CSV='%s', DB='%s'", 
                        csvPlayer.name, csvPlayer.hall, dbPlayer.hall));
                }
            } else {
                // Player not found in same hall - check if they exist in other halls
                for (Map.Entry<String, PlayerStats> dbEntry : dbPlayers.entrySet()) {
                    PlayerStats otherDbPlayer = dbEntry.getValue();
                    if (csvPlayer.name.equalsIgnoreCase(otherDbPlayer.name) && 
                        !csvPlayer.hall.equalsIgnoreCase(otherDbPlayer.hall)) {
                        crossHallIssues.add(String.format(
                            "⚠️ Player '%s' found in CSV hall '%s' but exists in database in hall '%s'. Is this an error or a different person?",
                            csvPlayer.name, csvPlayer.hall, otherDbPlayer.hall));
                        break;
                    }
                }
            }
        }

        // Check for potential typos/partial names within same hall only
        for (Map.Entry<String, PlayerStats> csvEntry : csvPlayers.entrySet()) {
            String csvKey = csvEntry.getKey();
            PlayerStats csvPlayer = csvEntry.getValue();
            
            // Get all DB players from the same hall
            List<Map.Entry<String, PlayerStats>> sameHallDbPlayers = dbPlayers.entrySet().stream()
                .filter(dbEntry -> dbEntry.getValue().hall.equalsIgnoreCase(csvPlayer.hall))
                .collect(Collectors.toList());
            
            for (Map.Entry<String, PlayerStats> dbEntry : sameHallDbPlayers) {
                String dbKey = dbEntry.getKey();
                PlayerStats dbPlayer = dbEntry.getValue();
                
                if (!csvKey.equals(dbKey)) {
                    // Check if names are similar (partial match, comma difference, or small misspelling)
                    if (isPartialNameMatch(csvKey, dbKey)) {
                        warnings.add(String.format("⚠️ Possible partial name: '%s' (CSV) matches '%s' (DB) in hall '%s'", 
                            csvPlayer.name, dbPlayer.name, csvPlayer.hall));
                    } else if (areSimilarNames(csvKey, dbKey)) {
                        majorIssues.add(String.format("❌ Potential major misspelling: '%s' (CSV) vs '%s' (DB) in hall '%s'", 
                            csvPlayer.name, dbPlayer.name, csvPlayer.hall));
                    }
                }
            }
        }

        // Handle cross-hall issues first (requires immediate confirmation)
        if (!crossHallIssues.isEmpty()) {
            StringBuilder message = new StringBuilder("The following players exist in different halls:\n\n");
            for (String issue : crossHallIssues) {
                message.append("- ").append(issue).append("\n");
            }
            message.append("\nIs this an error (same person, wrong hall)? (yes/no)\n");
            message.append("Answer 'yes' if it's an error and processing should stop.\n");
            message.append("Answer 'no' if these are different people and processing should continue.");

            discordLog.flushBatch();
            telegramLog.flushBatch();

            boolean isError = requestUserConfirmation(message.toString());
            
            if (isError) {
                String errorMsg = "Processing stopped: User confirmed cross-hall players are errors.";
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                
                // Send error to upload chat if callback is set
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
                
                return false;
            }

            discordLog.logInfo("User confirmed cross-hall players are different people. Continuing...");
            telegramLog.logInfo("User confirmed cross-hall players are different people. Continuing...");
        }

        // Log warnings without requiring confirmation
        if (!warnings.isEmpty()) {
            discordLog.flushBatch();
            telegramLog.flushBatch();
            for (String warning : warnings) {
                discordLog.logWarning(warning);
                telegramLog.logWarning(warning);
                
                // Send warning to upload chat if callback is set
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("⚠️", "WARNING", warning);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
            }
        }

        // Major issues require confirmation
        if (!majorIssues.isEmpty()) {
            StringBuilder message = new StringBuilder("The following major issues were detected:\n\n");
            for (String issue : majorIssues) {
                message.append("- ").append(issue).append("\n");
            }
            message.append("\nDo you want to continue processing? (yes/no)");

            discordLog.flushBatch();
            telegramLog.flushBatch();

            boolean confirmed = requestUserConfirmation(message.toString());
            
            if (!confirmed) {
                String cancelMsg = "Processing cancelled by user due to validation issues.";
                discordLog.logWarning(cancelMsg);
                telegramLog.logWarning(cancelMsg);
                
                // Send warning to upload chat if callback is set
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("🟡", "WARNING", cancelMsg);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
                
                return false;
            }

            discordLog.logInfo("User confirmed to proceed despite validation issues.");
            telegramLog.logInfo("User confirmed to proceed despite validation issues.");
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
        
        int currentRoundIndex = ROUND_SEQUENCE.indexOf(roundName);
        
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
            String pastRound = ROUND_SEQUENCE.get(i);
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
        
        // Step 4: Process rounds sequentially using Glicko-2
        List<String> roundsToProcess = ROUND_SEQUENCE.subList(0, currentRoundIndex + 1);
        
        System.out.println("DEBUG: About to calculate Glicko-2");
        System.out.println("  - Total players: " + allPlayers.size());
        System.out.println("  - Rounds to process: " + roundsToProcess);
        System.out.println("  - Total games: " + flattenGames(gamesByRound, roundsToProcess).size());
        System.out.println("  - First 3 players: " + allPlayers.stream().limit(3).toArray());
        
        // Calculate TrueElo
        Map<String, EloCalculator.Glicko2Rating> initialTrueRatings = new HashMap<>(currentTrueRatings);
        EloCalculator.Glicko2Result trueResult = EloCalculator.calculateGlicko2TrueElo(
            flattenGames(gamesByRound, roundsToProcess),
            allPlayers,
            initialTrueRatings,
            roundsToProcess
        );
        
        System.out.println("DEBUG: TrueElo calculation complete");
        System.out.println("  - Rounds in result: " + trueResult.ratingsByRound.keySet());
        
        // Calculate PerfElo
        EloCalculator.Glicko2Result perfResult = null;
        if (perfEloEnabled) {
            Map<String, EloCalculator.Glicko2Rating> initialPerfRatings = new HashMap<>(currentPerfRatings);
            perfResult = EloCalculator.calculateGlicko2PerfElo(
                flattenGames(gamesByRound, roundsToProcess),
                allPlayers,
                initialPerfRatings,
                roundsToProcess
            );
        }
        
        // Step 5: Store calculated ratings in player stats
        int storedCount = 0;
        for (int i = 0; i <= currentRoundIndex; i++) {
            String round = ROUND_SEQUENCE.get(i);
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
            int roundIndex = ROUND_SEQUENCE.indexOf(roundName);
            Integer playerPrevElo = null;
            Integer oppPrevElo = null;
            
            if (roundIndex > 0) {
                String prevRound = ROUND_SEQUENCE.get(roundIndex - 1);
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
                player.hall,
                winby1,
                opponent.name,
                opponent.oppHallByRound.get(roundName) != null ? opponent.oppHallByRound.get(roundName) : opponent.hall,
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

                // Search backwards from previous round
                int prevRoundIdx = ROUND_SEQUENCE.indexOf(previousRound);
                for (int i = prevRoundIdx; i >= 0; i--) {
                    String checkRound = ROUND_SEQUENCE.get(i);
                    Integer trueElo = dbPlayer.trueEloByRound.get(checkRound);
                    if (trueElo != null) {
                        prevTrueElo = trueElo;
                        prevPerfElo = dbPlayer.perfEloByRound.get(checkRound);
                        prevSeat = dbPlayer.seatByRound.get(checkRound);
                        break;
                    }
                }

                // Update current round with previous values (or defaults)
                dbPlayer.trueEloByRound.put(roundName, prevTrueElo != null ? prevTrueElo : BASE_ELO);
                dbPlayer.perfEloByRound.put(roundName, perfEloEnabled ? (prevPerfElo != null ? prevPerfElo : BASE_ELO) : null);
                dbPlayer.seatByRound.put(roundName, null); // No seat if didn't play
                
                // No opponent data since player didn't play
                dbPlayer.oppHallByRound.put(roundName, null);
                dbPlayer.oppNameByRound.put(roundName, null);
                dbPlayer.oppTrueEloByRound.put(roundName, null);
                dbPlayer.oppPerfEloByRound.put(roundName, null);

                // Add to csvPlayers so it gets updated
                csvPlayers.put(playerKey, dbPlayer);
            }
        }

        // Set future rounds to null
        int currentRoundIdx = ROUND_SEQUENCE.indexOf(roundName);
        for (PlayerStats player : csvPlayers.values()) {
            for (int i = currentRoundIdx + 1; i < ROUND_SEQUENCE.size(); i++) {
                String futureRound = ROUND_SEQUENCE.get(i);
                player.trueEloByRound.put(futureRound, null);
                player.perfEloByRound.put(futureRound, null);
                player.seatByRound.put(futureRound, null);
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

                for (PlayerStats player : csvPlayers.values()) {
                    String playerKey = player.name.toLowerCase();
                    PlayerStats dbPlayer = dbPlayers.get(playerKey);

                    if (dbPlayer != null && dbPlayer.existsInDb) {
                        // Update existing player
                        updatePlayerInDatabase(conn, player, dbPlayer, roundName);
                        updatedPlayers++;
                    } else {
                        // Insert new player
                        insertPlayerInDatabase(conn, player, roundName);
                        newPlayers++;
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

        // Always update hall, capped status, and dateLogged
        sql.append("hall = ?, capped = ?, dateLogged = ?, ");
        params.add(player.hall);
        params.add(player.capped ? 1 : 0); // SQLite boolean as 0/1
        params.add(currentTimestamp);

        // Update ONLY current and future rounds (previous rounds remain unchanged)
        int currentRoundIndex = ROUND_SEQUENCE.indexOf(roundName);
        
        for (int i = currentRoundIndex; i < ROUND_SEQUENCE.size(); i++) {
            String round = ROUND_SEQUENCE.get(i);
            
            String trueEloCol = getRoundColumnName("trueElo", round);
            String perfEloCol = getRoundColumnName("perfElo", round);
            String rdTrueEloCol = getRoundColumnName("rdTrueElo", round);
            String volTrueEloCol = getRoundColumnName("volTrueElo", round);
            String rdPerfEloCol = getRoundColumnName("rdPerfElo", round);
            String volPerfEloCol = getRoundColumnName("volPerfElo", round);
            String seatCol = getRoundColumnName("seat", round);
            String oppHallCol = getRoundColumnName("oppHall", round);
            String oppNameCol = getRoundColumnName("oppName", round);
            String oppTrueEloCol = getRoundColumnName("oppTrueElo", round);
            String oppPerfEloCol = getRoundColumnName("oppPerfElo", round);

            Integer trueElo = player.trueEloByRound.get(round);
            Integer perfElo = player.perfEloByRound.get(round);
            Double rdTrueElo = player.rdTrueEloByRound.get(round);
            Double volTrueElo = player.volTrueEloByRound.get(round);
            Double rdPerfElo = player.rdPerfEloByRound.get(round);
            Double volPerfElo = player.volPerfEloByRound.get(round);
            Integer seat = player.seatByRound.get(round);
            String oppHall = player.oppHallByRound.get(round);
            String oppName = player.oppNameByRound.get(round);
            Integer oppTrueElo = player.oppTrueEloByRound.get(round);
            Integer oppPerfElo = player.oppPerfEloByRound.get(round);

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

            sql.append(oppHallCol).append(" = ?, ");
            params.add(oppHall);

            sql.append(oppNameCol).append(" = ?, ");
            params.add(oppName);

            sql.append(oppTrueEloCol).append(" = ?, ");
            params.add(oppTrueElo);

            sql.append(oppPerfEloCol).append(" = ?, ");
            params.add(oppPerfElo);
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
        StringBuilder sql = new StringBuilder("INSERT INTO A1_PlayerStats (name, hall, capped, baseTrueElo, basePerfElo, baseRdTrueElo, baseVolTrueElo, baseRdPerfElo, baseVolPerfElo, dateLogged");
        StringBuilder values = new StringBuilder("VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?");
        List<Object> params = new ArrayList<>();

        // Get current timestamp
        String currentTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

        params.add(player.name);
        params.add(player.hall);
        params.add(player.capped ? 1 : 0); // SQLite boolean as 0/1
        // Use existing base ELO if set (from import), otherwise default to BASE_ELO
        params.add(player.baseTrueElo != null ? player.baseTrueElo : BASE_ELO);
        params.add(player.basePerfElo != null ? player.basePerfElo : (perfEloEnabled ? BASE_ELO : null));
        params.add(player.baseRdTrueElo != null ? player.baseRdTrueElo : 350.0); // Default RD
        params.add(player.baseVolTrueElo != null ? player.baseVolTrueElo : 0.06); // Default volatility
        params.add(player.baseRdPerfElo != null ? player.baseRdPerfElo : (perfEloEnabled ? 350.0 : null));
        params.add(player.baseVolPerfElo != null ? player.baseVolPerfElo : (perfEloEnabled ? 0.06 : null));
        params.add(currentTimestamp);

        // Add all round columns
        for (String round : ROUND_SEQUENCE) {
            String trueEloCol = getRoundColumnName("trueElo", round);
            String perfEloCol = getRoundColumnName("perfElo", round);
            String rdTrueEloCol = getRoundColumnName("rdTrueElo", round);
            String volTrueEloCol = getRoundColumnName("volTrueElo", round);
            String rdPerfEloCol = getRoundColumnName("rdPerfElo", round);
            String volPerfEloCol = getRoundColumnName("volPerfElo", round);
            String seatCol = getRoundColumnName("seat", round);
            String oppHallCol = getRoundColumnName("oppHall", round);
            String oppNameCol = getRoundColumnName("oppName", round);
            String oppTrueEloCol = getRoundColumnName("oppTrueElo", round);
            String oppPerfEloCol = getRoundColumnName("oppPerfElo", round);

            sql.append(", ").append(trueEloCol);
            sql.append(", ").append(perfEloCol);
            sql.append(", ").append(rdTrueEloCol);
            sql.append(", ").append(volTrueEloCol);
            sql.append(", ").append(rdPerfEloCol);
            sql.append(", ").append(volPerfEloCol);
            sql.append(", ").append(seatCol);
            sql.append(", ").append(oppHallCol);
            sql.append(", ").append(oppNameCol);
            sql.append(", ").append(oppTrueEloCol);
            sql.append(", ").append(oppPerfEloCol);
            
            values.append(", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?");

            // Fill previous rounds with base ELO, current round with calculated, future rounds with null
            int currentIdx = ROUND_SEQUENCE.indexOf(currentRound);
            int roundIdx = ROUND_SEQUENCE.indexOf(round);

            if (roundIdx < currentIdx) {
                // Previous rounds - fill with base ELO if available, otherwise BASE_ELO
                params.add(player.baseTrueElo != null ? player.baseTrueElo : BASE_ELO);
                params.add(perfEloEnabled ? (player.basePerfElo != null ? player.basePerfElo : BASE_ELO) : null);
                params.add(player.baseRdTrueElo != null ? player.baseRdTrueElo : 350.0);
                params.add(player.baseVolTrueElo != null ? player.baseVolTrueElo : 0.06);
                params.add(perfEloEnabled ? (player.baseRdPerfElo != null ? player.baseRdPerfElo : 350.0) : null);
                params.add(perfEloEnabled ? (player.baseVolPerfElo != null ? player.baseVolPerfElo : 0.06) : null);
                params.add(null); // seat
                params.add(null); // oppHall
                params.add(null); // oppName
                params.add(null); // oppTrueElo
                params.add(null); // oppPerfElo
            } else if (roundIdx == currentIdx) {
                // Current round - use calculated values
                params.add(player.trueEloByRound.get(round));
                params.add(player.perfEloByRound.get(round));
                params.add(player.rdTrueEloByRound.get(round));
                params.add(player.volTrueEloByRound.get(round));
                params.add(player.rdPerfEloByRound.get(round));
                params.add(player.volPerfEloByRound.get(round));
                params.add(player.seatByRound.get(round));
                params.add(player.oppHallByRound.get(round));
                params.add(player.oppNameByRound.get(round));
                params.add(player.oppTrueEloByRound.get(round));
                params.add(player.oppPerfEloByRound.get(round));
            } else {
                // Future rounds - null
                params.add(null); // trueElo
                params.add(null); // perfElo
                params.add(null); // rdTrueElo
                params.add(null); // volTrueElo
                params.add(null); // rdPerfElo
                params.add(null); // volPerfElo
                params.add(null); // seat
                params.add(null); // oppHall
                params.add(null); // oppName
                params.add(null); // oppTrueElo
                params.add(null); // oppPerfElo
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
        int fromIndex = ROUND_SEQUENCE.indexOf(fromRound);
        
        if (fromIndex == -1) {
            throw new IllegalArgumentException("Invalid round name: " + fromRound);
        }
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            
            try {
                StringBuilder sql = new StringBuilder("UPDATE A1_PlayerStats SET ");
                
                // Set all rounds from fromRound onwards to NULL
                for (int i = fromIndex; i < ROUND_SEQUENCE.size(); i++) {
                    String round = ROUND_SEQUENCE.get(i);
                    
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
                        ROUND_SEQUENCE.size() - fromIndex, rowsAffected));
                    telegramLog.batchInfo(String.format("Cleared %d rounds for %d players", 
                        ROUND_SEQUENCE.size() - fromIndex, rowsAffected));
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
     * Imports player data from playerExport CSV file
     * Updates/creates baseTrueElo, basePerfElo, and hall for players
     * Leaves capped status as default (false) - capped should be managed via cappedlist.csv
     * Validates that A1_PlayerStats table is empty before processing
     * @param csvFilePath Path to the playerExport CSV file
     * @return true if successful, false otherwise
     */
    public boolean importPlayerExport(String csvFilePath) {
        discordLog.logInfo("Starting player import from export file...");
        telegramLog.logInfo("Starting player import from export file...");

        File csvFile = new File(csvFilePath);
        if (!csvFile.exists()) {
            String errorMsg = "Player export file not found at: " + csvFilePath;
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            if (uploadChatCallback != null) {
                String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                uploadChatCallback.sendMessage(formattedMsg);
            }
            return false;
        }

        try {
            // Validate that A1_PlayerStats table is empty
            if (!isTableEmpty()) {
                String errorMsg = "Cannot import player data: A1_PlayerStats table is not empty. Table must be empty before importing playerExport.csv";
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                if (uploadChatCallback != null) {
                    String formattedMsg = formatUploadMessage("🔴", "ERROR", errorMsg);
                    uploadChatCallback.sendMessage(formattedMsg);
                }
                return false;
            }

            // Parse CSV
            List<PlayerImportEntry> entries = parsePlayerExportCSV(csvFilePath);

            discordLog.batchInfo(String.format("CSV parsed successfully. %d players found.", entries.size()));
            telegramLog.batchInfo(String.format("CSV parsed successfully. %d players found.", entries.size()));

            // Import into database
            importPlayerData(entries);

            discordLog.flushBatch();
            telegramLog.flushBatch();
            String successMsg = String.format("Player import completed successfully. Updated/created %d players.", entries.size());
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
            String errorMsg = "Player import failed: " + e.getMessage();
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
     * Represents a player import entry from CSV
     */
    private static class PlayerImportEntry {
        String name;
        Integer trueElo;
        Integer perfElo;
        String hall;

        PlayerImportEntry(String name, Integer trueElo, Integer perfElo, String hall) {
            this.name = name;
            this.trueElo = trueElo;
            this.perfElo = perfElo;
            this.hall = hall;
        }
    }

    /**
     * Parses the playerExport CSV file
     * Expected format: name,trueElo,perfElo,lastRound,lastHall,capped
     */
    private List<PlayerImportEntry> parsePlayerExportCSV(String csvFilePath) throws Exception {
        List<PlayerImportEntry> entries = new ArrayList<>();

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
                        throw new Exception("Invalid CSV format: Header must have exactly 6 columns (name,trueElo,perfElo,lastRound,lastHall,capped)");
                    }
                    String[] expected = {"name", "trueelo", "perfelo", "lastround", "lasthall", "capped"};
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

                String name = parts[0].trim();
                String trueEloStr = parts[1].trim();
                String perfEloStr = parts[2].trim();
                // lastRound (parts[3]) is not used during import
                String lastHall = parts[4].trim();
                // capped (parts[5]) is not imported - capped status is managed via cappedlist.csv

                if (name.isEmpty()) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Name cannot be empty", lineNumber));
                }

                if (trueEloStr.isEmpty()) {
                    throw new Exception(String.format("Invalid CSV format at line %d: trueElo cannot be empty", lineNumber));
                }

                // lastHall can be empty/null for players who haven't played yet
                if (lastHall.isEmpty()) {
                    lastHall = null;
                }

                Integer trueElo = null;
                Integer perfElo = null;

                try {
                    trueElo = Integer.parseInt(trueEloStr);
                } catch (NumberFormatException e) {
                    throw new Exception(String.format("Invalid CSV format at line %d: trueElo must be an integer", lineNumber));
                }

                if (!perfEloStr.isEmpty()) {
                    try {
                        perfElo = Integer.parseInt(perfEloStr);
                    } catch (NumberFormatException e) {
                        throw new Exception(String.format("Invalid CSV format at line %d: perfElo must be an integer or empty", lineNumber));
                    }
                }

                entries.add(new PlayerImportEntry(name, trueElo, perfElo, lastHall));
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
     * Checks if a player has played any rounds (has any non-null ELO values in round columns)
     * @param conn Database connection
     * @param playerId Player's ID
     * @return true if player has played rounds, false otherwise
     */
    private boolean hasPlayedRounds(Connection conn, int playerId) throws SQLException {
        String sql = "SELECT trueEloR1, trueEloR2, trueEloR3, trueEloR4, trueEloR5, trueEloR6, " +
                     "trueEloT16, trueEloT8, trueEloT4, trueEloT2 FROM A1_PlayerStats WHERE id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, playerId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    // Check if any round ELO column is not null
                    for (int i = 1; i <= 10; i++) {
                        if (rs.getObject(i) != null) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Imports player data into the database
     * Creates new players or updates existing ones (by name, case-insensitive)
     * Writes hall field from CSV but does NOT import capped status (managed via cappedlist.csv)
     * Detects hall conflicts and requests user confirmation when needed
     */
    private void importPlayerData(List<PlayerImportEntry> entries) throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);

            try {
                String currentTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

                for (PlayerImportEntry entry : entries) {
                    // Check if player exists (case-insensitive name match)
                    String checkSQL = "SELECT id, hall FROM A1_PlayerStats WHERE LOWER(name) = LOWER(?)";
                    Integer existingId = null;
                    String existingHall = null;

                    try (PreparedStatement checkStmt = conn.prepareStatement(checkSQL)) {
                        checkStmt.setString(1, entry.name);
                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next()) {
                                existingId = rs.getInt("id");
                                existingHall = rs.getString("hall");
                            }
                        }
                    }

                    if (existingId != null) {
                        // Player exists - check for hall conflict
                        boolean hallConflict = false;
                        
                        if (existingHall != null && entry.hall != null && 
                            !existingHall.equalsIgnoreCase(entry.hall)) {
                            // Hall differs - check if player has played any rounds
                            if (!hasPlayedRounds(conn, existingId)) {
                                // Player hasn't played yet - ask user for confirmation
                                hallConflict = true;
                                
                                // Build confirmation message
                                String conflictMsg = String.format(
                                    "⚠️ HALL CONFLICT DETECTED\\n\\n" +
                                    "Player: %s\\n" +
                                    "Database hall: %s\\n" +
                                    "CSV hall: %s\\n\\n" +
                                    "This player exists in the database but has not played any rounds yet.\\n" +
                                    "Are these the same player?\\n\\n" +
                                    "Reply with:\\n" +
                                    "✅ 'yes' - Update hall to '%s' (CSV value)\\n" +
                                    "❌ 'no' - Keep as different players (import will fail)",
                                    entry.name, existingHall, entry.hall, entry.hall
                                );
                                
                                discordLog.logWarning(conflictMsg);
                                telegramLog.logWarning(conflictMsg);
                                
                                // Send to upload chat for user confirmation
                                if (uploadChatCallback != null) {
                                    uploadChatCallback.sendMessage(conflictMsg);
                                }
                                
                                // For now, we'll throw an exception to halt the import
                                // In a real implementation, this would wait for user response
                                throw new Exception(String.format(
                                    "Hall conflict detected for player '%s' (DB: %s, CSV: %s). " +
                                    "Player has not played any rounds. Please confirm if same player.",
                                    entry.name, existingHall, entry.hall
                                ));
                            }
                        }
                        
                        // Update existing player - update base ELO and hall values
                        String updateSQL = "UPDATE A1_PlayerStats SET baseTrueElo = ?, basePerfElo = ?, hall = ?, dateLogged = ? WHERE id = ?";
                        try (PreparedStatement pstmt = conn.prepareStatement(updateSQL)) {
                            pstmt.setInt(1, entry.trueElo);
                            pstmt.setObject(2, entry.perfElo);
                            pstmt.setString(3, entry.hall);
                            pstmt.setString(4, currentTimestamp);
                            pstmt.setInt(5, existingId);
                            pstmt.executeUpdate();
                        }
                        discordLog.batchInfo(String.format("Updated player: %s (hall: %s)",
                            entry.name, entry.hall != null ? entry.hall : "none"));
                        telegramLog.batchInfo(String.format("Updated player: %s (hall: %s)",
                            entry.name, entry.hall != null ? entry.hall : "none"));
                    } else {
                        // Insert new player - name, base ELO, and hall. capped defaults to 0 (false)
                        String insertSQL = "INSERT INTO A1_PlayerStats (name, hall, capped, baseTrueElo, basePerfElo, dateLogged) VALUES (?, ?, 0, ?, ?, ?)";
                        try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                            pstmt.setString(1, entry.name);
                            pstmt.setString(2, entry.hall);
                            pstmt.setInt(3, entry.trueElo);
                            pstmt.setObject(4, entry.perfElo);
                            pstmt.setString(5, currentTimestamp);
                            pstmt.executeUpdate();
                        }
                        discordLog.batchInfo(String.format("Created new player: %s (hall: %s)",
                            entry.name, entry.hall != null ? entry.hall : "none"));
                        telegramLog.batchInfo(String.format("Created new player: %s (hall: %s)",
                            entry.name, entry.hall != null ? entry.hall : "none"));
                    }
                }

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
