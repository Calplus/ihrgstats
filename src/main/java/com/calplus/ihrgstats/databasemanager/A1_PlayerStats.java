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
        Integer baseElo;
        String dateLogged;
        Map<String, Integer> trueEloByRound = new HashMap<>();
        Map<String, Integer> perfEloByRound = new HashMap<>();
        Map<String, Integer> seatByRound = new HashMap<>();
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
            return false;
        }

        // Extract players from CSV
        Map<String, PlayerStats> csvPlayers = extractPlayersFromGames(games);

        // Check and set capped status
        checkCappedStatus(csvPlayers, cappedPlayers);

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
            System.out.println("DEBUG: uploadChatCallback is " + (uploadChatCallback != null ? "SET" : "NULL"));
            if (uploadChatCallback != null) {
                System.out.println("DEBUG: Calling uploadChatCallback.sendMessage with: " + successMsg);
                uploadChatCallback.sendMessage(successMsg);
                System.out.println("DEBUG: uploadChatCallback.sendMessage completed");
            } else {
                System.out.println("DEBUG: uploadChatCallback is null, cannot send to upload chat");
            }
            
            return true;
            
        } catch (Exception e) {
            discordLog.flushBatch();
            telegramLog.flushBatch();
            String errorMsg = "Database update failed: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
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
            return false;
        }

        // Round 1 doesn't need previous round
        if (roundIndex == 0) {
            return true;
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
                String sql = String.format("SELECT COUNT(*) FROM A1_PlayerStats WHERE %s IS NOT NULL", columnName);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        String errorMsg = String.format("Previous round (round_%s) has not been processed yet. Please process rounds in order: %s",
                            previousRound, String.join(", ", ROUND_SEQUENCE));
                        discordLog.logError(errorMsg);
                        telegramLog.logError(errorMsg);
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
                    player.baseElo = (Integer) rs.getObject("baseElo");
                    player.dateLogged = rs.getString("dateLogged");
                    player.existsInDb = true;

                    // Load ELO ratings for all rounds
                    for (String round : ROUND_SEQUENCE) {
                        String trueEloCol = getRoundColumnName("trueElo", round);
                        String perfEloCol = getRoundColumnName("perfElo", round);
                        String seatCol = getRoundColumnName("seat", round);

                        player.trueEloByRound.put(round, (Integer) rs.getObject(trueEloCol));
                        player.perfEloByRound.put(round, (Integer) rs.getObject(perfEloCol));
                        player.seatByRound.put(round, (Integer) rs.getObject(seatCol));
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
     * Validates player name/hall matches between CSV and database
     */
    private boolean validatePlayerMatches(Map<String, PlayerStats> csvPlayers, Map<String, PlayerStats> dbPlayers) {
        List<String> warnings = new ArrayList<>();
        List<String> majorIssues = new ArrayList<>();

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

        // Log warnings without requiring confirmation
        if (!warnings.isEmpty()) {
            discordLog.flushBatch();
            telegramLog.flushBatch();
            for (String warning : warnings) {
                discordLog.logWarning(warning);
                telegramLog.logWarning(warning);
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
            Scanner scanner = new Scanner(System.in);
            String response = scanner.nextLine().trim().toLowerCase();
            return response.equals("yes") || response.equals("y");
        }
    }

    /**
     * Calculates seating arrangements for players
     */
    private void calculateSeating(List<GameEntry> games, Map<String, PlayerStats> csvPlayers, String roundName) {
        Map<String, Integer> hallSeatCounter = new HashMap<>();

        for (GameEntry game : games) {
            // Player 1 seating (skip if walkover)
            if (!game.name1.equalsIgnoreCase("WALKOVER")) {
                String hall1Lower = game.hall1.toLowerCase();
                int seat1 = hallSeatCounter.getOrDefault(hall1Lower, 0) + 1;
                hallSeatCounter.put(hall1Lower, seat1);
                
                PlayerStats player1 = csvPlayers.get(game.name1.toLowerCase());
                if (player1 != null) {
                    player1.seatByRound.put(roundName, seat1);
                }
            }

            // Player 2 seating (skip if walkover)
            if (!game.name2.equalsIgnoreCase("WALKOVER")) {
                String hall2Lower = game.hall2.toLowerCase();
                int seat2 = hallSeatCounter.getOrDefault(hall2Lower, 0) + 1;
                hallSeatCounter.put(hall2Lower, seat2);
                
                PlayerStats player2 = csvPlayers.get(game.name2.toLowerCase());
                if (player2 != null) {
                    player2.seatByRound.put(roundName, seat2);
                }
            }
        }

        discordLog.batchInfo(String.format("Seating arrangements calculated for round %s", roundName));
        telegramLog.batchInfo(String.format("Seating arrangements calculated for round %s", roundName));
    }

    /**
     * Calculates ELO ratings for all players
     */
    private void calculateEloRatings(List<GameEntry> games, Map<String, PlayerStats> csvPlayers, 
                                     Map<String, PlayerStats> dbPlayers, String roundName) {
        
        // Get previous round for ELO history
        String previousRound = EloCalculator.getPreviousRound(roundName);
        
        // Build game list for ELO calculator
        List<EloCalculator.Game> trueEloGames = new ArrayList<>();
        List<EloCalculator.Game> perfEloGames = new ArrayList<>();
        Set<String> allPlayers = new HashSet<>(csvPlayers.keySet());
        int timeStep = EloCalculator.roundNameToTimeStep(roundName);

        for (GameEntry game : games) {
            // Skip walkovers for ELO calculation
            if (game.name1.equalsIgnoreCase("WALKOVER") || game.name2.equalsIgnoreCase("WALKOVER")) {
                continue;
            }

            String player1Key = game.name1.toLowerCase();
            String player2Key = game.name2.toLowerCase();

            // Determine winner and point margin
            boolean player1Won;
            boolean player2Won;
            double pointMargin = 0.0;
            
            // Check if both winby columns are filled with "0" or "1"
            if (!game.winby1.isEmpty() && !game.winby2.isEmpty() && 
                (game.winby1.equals("0") || game.winby1.equals("1")) && 
                (game.winby2.equals("0") || game.winby2.equals("1"))) {
                // Binary win/loss mode: "1" = win, "0" = loss
                player1Won = game.winby1.equals("1");
                player2Won = game.winby2.equals("1");
                pointMargin = 0.0; // No point margin in binary mode
            } else {
                // Traditional mode: filled = won, empty = lost
                player1Won = !game.winby1.isEmpty();
                player2Won = !game.winby2.isEmpty();
                
                // Calculate point margin for PerfElo
                if (player1Won && !game.winby1.isEmpty()) {
                    try {
                        pointMargin = Double.parseDouble(game.winby1);
                    } catch (NumberFormatException e) {
                        pointMargin = 0.0;
                    }
                } else if (player2Won && !game.winby2.isEmpty()) {
                    try {
                        pointMargin = -Double.parseDouble(game.winby2);
                    } catch (NumberFormatException e) {
                        pointMargin = 0.0;
                    }
                }
            }

            // TrueElo: Binary win/loss
            double trueScore1 = player1Won ? 1.0 : 0.0;
            trueEloGames.add(new EloCalculator.Game(player1Key, player2Key, trueScore1, timeStep));

            // PerfElo: Performance score based on point margin
            if (perfEloEnabled) {
                // For perfElo, also consider color advantage
                PlayerStats p1 = csvPlayers.get(player1Key);
                Integer seat1 = p1.seatByRound.get(roundName);
                boolean player1IsBlack = (seat1 != null && (seat1 % 2 == 1)); // Seats 1,3,5 are black
                
                // Adjust margin for color (black gets komi disadvantage already in winby)
                double perfScore = EloCalculator.pointMarginToPerformanceScore(pointMargin);
                perfEloGames.add(new EloCalculator.Game(player1Key, player2Key, perfScore, timeStep));
            }
        }

        // Get previous ELO ratings
        Map<String, Double> previousTrueElos = new HashMap<>();
        Map<String, Double> previousPerfElos = new HashMap<>();

        for (String playerKey : allPlayers) {
            PlayerStats csvPlayer = csvPlayers.get(playerKey);
            PlayerStats dbPlayer = dbPlayers.get(playerKey);

            double prevTrueElo = 1000.0;
            double prevPerfElo = 1000.0;

            if (dbPlayer != null && previousRound != null) {
                Integer prevTrue = dbPlayer.trueEloByRound.get(previousRound);
                if (prevTrue != null) {
                    prevTrueElo = prevTrue.doubleValue();
                }

                if (perfEloEnabled) {
                    Integer prevPerf = dbPlayer.perfEloByRound.get(previousRound);
                    if (prevPerf != null) {
                        prevPerfElo = prevPerf.doubleValue();
                    }
                }
            }

            previousTrueElos.put(playerKey, prevTrueElo);
            previousPerfElos.put(playerKey, prevPerfElo);
        }

        // Calculate new ratings
        Map<String, Double> newTrueElos = EloCalculator.calculateTrueElo(trueEloGames, allPlayers, previousTrueElos);
        
        // Update player stats
        for (Map.Entry<String, Double> entry : newTrueElos.entrySet()) {
            String playerKey = entry.getKey();
            PlayerStats player = csvPlayers.get(playerKey);
            if (player != null) {
                player.trueEloByRound.put(roundName, (int) Math.round(entry.getValue()));
            }
        }

        if (perfEloEnabled) {
            Map<String, Double> newPerfElos = EloCalculator.calculatePerfElo(perfEloGames, allPlayers, previousPerfElos);
            for (Map.Entry<String, Double> entry : newPerfElos.entrySet()) {
                String playerKey = entry.getKey();
                PlayerStats player = csvPlayers.get(playerKey);
                if (player != null) {
                    player.perfEloByRound.put(roundName, (int) Math.round(entry.getValue()));
                }
            }
        } else {
            // Set perfElo to null if disabled
            for (PlayerStats player : csvPlayers.values()) {
                player.perfEloByRound.put(roundName, null);
            }
        }

        discordLog.batchInfo(String.format("ELO ratings calculated for round %s (%s)", 
            roundName, perfEloEnabled ? "TrueElo + PerfElo" : "TrueElo only"));
        telegramLog.batchInfo(String.format("ELO ratings calculated for round %s (%s)", 
            roundName, perfEloEnabled ? "TrueElo + PerfElo" : "TrueElo only"));
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
                dbPlayer.trueEloByRound.put(roundName, prevTrueElo != null ? prevTrueElo : 1000);
                dbPlayer.perfEloByRound.put(roundName, perfEloEnabled ? (prevPerfElo != null ? prevPerfElo : 1000) : null);
                dbPlayer.seatByRound.put(roundName, null); // No seat if didn't play

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

        // Update all round columns
        for (String round : ROUND_SEQUENCE) {
            String trueEloCol = getRoundColumnName("trueElo", round);
            String perfEloCol = getRoundColumnName("perfElo", round);
            String seatCol = getRoundColumnName("seat", round);

            Integer trueElo = player.trueEloByRound.get(round);
            Integer perfElo = player.perfEloByRound.get(round);
            Integer seat = player.seatByRound.get(round);

            sql.append(trueEloCol).append(" = ?, ");
            params.add(trueElo);

            sql.append(perfEloCol).append(" = ?, ");
            params.add(perfElo);

            sql.append(seatCol).append(" = ?, ");
            params.add(seat);
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
        StringBuilder sql = new StringBuilder("INSERT INTO A1_PlayerStats (name, hall, capped, baseElo, dateLogged");
        StringBuilder values = new StringBuilder("VALUES (?, ?, ?, ?, ?");
        List<Object> params = new ArrayList<>();

        // Get current timestamp
        String currentTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

        params.add(player.name);
        params.add(player.hall);
        params.add(player.capped ? 1 : 0); // SQLite boolean as 0/1
        params.add(1000); // Base ELO
        params.add(currentTimestamp);

        // Add all round columns
        for (String round : ROUND_SEQUENCE) {
            String trueEloCol = getRoundColumnName("trueElo", round);
            String perfEloCol = getRoundColumnName("perfElo", round);
            String seatCol = getRoundColumnName("seat", round);

            sql.append(", ").append(trueEloCol);
            sql.append(", ").append(perfEloCol);
            sql.append(", ").append(seatCol);
            
            values.append(", ?, ?, ?");

            // Fill previous rounds with 1000, current round with calculated, future rounds with null
            int currentIdx = ROUND_SEQUENCE.indexOf(currentRound);
            int roundIdx = ROUND_SEQUENCE.indexOf(round);

            if (roundIdx < currentIdx) {
                // Previous rounds - fill with 1000
                params.add(1000);
                params.add(perfEloEnabled ? 1000 : null);
                params.add(null);
            } else if (roundIdx == currentIdx) {
                // Current round - use calculated values
                params.add(player.trueEloByRound.get(round));
                params.add(player.perfEloByRound.get(round));
                params.add(player.seatByRound.get(round));
            } else {
                // Future rounds - null
                params.add(null);
                params.add(null);
                params.add(null);
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
