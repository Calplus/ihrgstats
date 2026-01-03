package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;
import com.calplus.ihrgstats.utils.TableFormatter.Alignment;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Command handler for /rankplayers command.
 * Displays a ranked list of all players by their TrueElo rating.
 */
public class CommandRankPlayers {
    private final LogHelper logHelper;
    private final String dbPath;

    // State management for round selection (static so it persists across instances)
    private static final Map<String, RankSelectionState> userSelectionStates = new HashMap<>();
    
    private static class RankSelectionState extends SelectionState {
        String selectedRound;  // "all" or specific round
    }

    public CommandRankPlayers() {
        // Load environment variables
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.logHelper = new LogHelper();
        this.dbPath = DatabaseHelper.getDefaultDatabasePathString();
    }

    /**
     * Represents a player's ranking data
     */
    private static class PlayerRankData {
        String name;
        String hall;
        String lastRound;
        int trueElo;
        boolean isCapped;

        PlayerRankData(String name, String hall, String lastRound, int trueElo, boolean isCapped) {
            this.name = name;
            this.hall = hall;
            this.lastRound = lastRound;
            this.trueElo = trueElo;
            this.isCapped = isCapped;
        }
    }

    /**
     * Handles the /rankplayers command - shows round selection UI
     * @param userId User ID for state tracking
     * @return Response with round selection buttons
     */
    public RankResponse handleCommand(String userId) {
        logHelper.logInfo("Processing /rankplayers command for user " + userId);

        // Get available rounds from database (excluding skipped rounds)
        List<String> availableRounds = RoundDetector.getAvailableRounds(dbPath);
        
        if (availableRounds.isEmpty()) {
            String errorMsg = "ℹ️ No rounds with data found in database.";
            logHelper.logWarning("No available rounds found");
            return new RankResponse(errorMsg, (Path) null);
        }

        // Build button labels and callbacks
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        // Add "All Rounds" button first
        labels.add("All Rounds");
        callbacks.add("rankplayers_round_all");
        
        // Add round buttons
        for (String round : availableRounds) {
            labels.add(round.toUpperCase());
            callbacks.add("rankplayers_round_" + round.toLowerCase());
        }
        
        // Add "Cancel" button last
        labels.add("❌ Cancel");
        callbacks.add("rankplayers_cancel");

        String message = "🏆 **Player Rankings**\n\n" +
                        "Select which round to rank players up to:";

        ButtonConfig buttonConfig = new ButtonConfig(
            labels.toArray(new String[0]), 
            callbacks.toArray(new String[0])
        );
        
        return new RankResponse(message, buttonConfig);
    }

    /**
     * Handles round selection callback
     * @param userId User ID
     * @param selectedRound Selected round ("all" or specific round like "1", "t16")
     * @return Response with rankings for selected round
     */
    public RankResponse handleRoundSelection(String userId, String selectedRound) {
        logHelper.logInfo("User " + userId + " selected round: " + selectedRound);

        // Store selection state
        RankSelectionState state = new RankSelectionState();
        state.selectedRound = selectedRound.toLowerCase();
        userSelectionStates.put(userId, state);

        List<PlayerRankData> players = fetchPlayerData(selectedRound);

        if (players.isEmpty()) {
            String errorMsg = "ℹ️ No player data found for round " + selectedRound.toUpperCase() + ".";
            logHelper.logWarning("No players found for ranking in round " + selectedRound);
            userSelectionStates.remove(userId);
            return new RankResponse(errorMsg, (Path) null);
        }

        // Sort by TrueElo descending
        players.sort((p1, p2) -> Integer.compare(p2.trueElo, p1.trueElo));

        // Get home hall setting for asterisk in text
        String homeHallForText = PropertyResolver.getProperty("settings.homeHall", "");

        // Format as table
        String table = formatPlayersTable(players, homeHallForText);

        String roundDisplay = selectedRound.equalsIgnoreCase("all") ? "All Rounds" : "Round " + selectedRound.toUpperCase();
        String message = "🏆 **Player Rankings** (" + roundDisplay + ")\n\n" +
                        "Players ranked by TrueElo rating\n\n" +
                        table;

        // Generate table image with home hall highlighting
        Path imagePath = null;
        try {
            // Get home hall setting and find matching player rows
            String homeHall = PropertyResolver.getProperty("settings.homeHall", "");
            Set<Integer> highlightRows = new HashSet<>();
            if (!homeHall.isEmpty()) {
                for (int i = 0; i < players.size(); i++) {
                    if (players.get(i).hall.equals(homeHall)) {
                        highlightRows.add(i);
                    }
                }
            }
            
            imagePath = generatePlayersImage(players, highlightRows, selectedRound);
        } catch (Exception e) {
            logHelper.logWarning("Failed to generate table image: " + e.getMessage());
        }

        // Clean up state
        userSelectionStates.remove(userId);


        logHelper.logSuccess(String.format("Ranked %d players", players.size()));

        return new RankResponse(message, imagePath);
    }

    /**
     * Handles cancel action
     * @param userId User ID
     * @return Response confirming cancellation
     */
    public RankResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        String message = "❌ Player ranking cancelled.";
        return new RankResponse(message, (Path) null);
    }

    /**
     * Fetches player data from database, optionally filtering by round
     * @param round The round to fetch data up to ("all" for all rounds, or specific round like "1", "t16")
     */
    private List<PlayerRankData> fetchPlayerData(String round) {
        List<PlayerRankData> players = new ArrayList<>();

        try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
                String sql = "SELECT name, hall FROM A1_PlayerStats WHERE active = 1";
                
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {

                    while (rs.next()) {
                        String name = rs.getString("name");
                        String hall = rs.getString("hall");

                        // Find last round played and corresponding TrueElo (up to selected round)
                        LastRoundData lastRoundData = findLastRoundPlayed(conn, name, round);
                        
                        // Check if player is capped
                        boolean isCapped = isPlayerCapped(conn, name);
                        
                        if (lastRoundData != null && lastRoundData.trueElo != null) {
                            players.add(new PlayerRankData(name, hall, 
                                lastRoundData.roundName, lastRoundData.trueElo, isCapped));
                        }
                    }
                }
        } catch (SQLException e) {
            logHelper.logError("Error fetching player data: " + e.getMessage());
            e.printStackTrace();
        }

        return players;
    }

    /**
     * Helper class for last round data
     */
    private static class LastRoundData {
        String roundName;
        Integer trueElo;

        LastRoundData(String roundName, Integer trueElo) {
            this.roundName = roundName;
            this.trueElo = trueElo;
        }
    }

    /**
     * Finds the last round a player actually played, up to the specified round
     * @param round The round to check up to ("all" for all rounds)
     */
    private LastRoundData findLastRoundPlayed(Connection conn, String playerName, String round) throws SQLException {
        // Determine which rounds to check based on selected round
        List<String> roundsToCheck;
        if (round.equalsIgnoreCase("all")) {
            roundsToCheck = Constants.ROUND_SEQUENCE;
        } else {
            // Find index of selected round and only check rounds up to that point
            int selectedIndex = Constants.ROUND_SEQUENCE.indexOf(round.toLowerCase());
            if (selectedIndex == -1) {
                roundsToCheck = Constants.ROUND_SEQUENCE;  // Fallback to all rounds
            } else {
                roundsToCheck = Constants.ROUND_SEQUENCE.subList(0, selectedIndex + 1);
            }
        }
        
        // Check rounds in reverse order
        for (int i = roundsToCheck.size() - 1; i >= 0; i--) {
            String checkRound = roundsToCheck.get(i);
            String roundCol = RoundUtils.getRoundColumnName("trueElo", checkRound);
            String oppNameCol = RoundUtils.getRoundColumnName("oppName", checkRound);

            String sql = String.format(
                "SELECT %s, %s FROM A1_PlayerStats WHERE name = ?",
                roundCol, oppNameCol
            );

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, playerName);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        Integer trueElo = (Integer) rs.getObject(roundCol);
                        String oppName = rs.getString(oppNameCol);

                        // Player actually played if they have an opponent (not null and not empty)
                        if (trueElo != null && oppName != null && !oppName.isEmpty()) {
                            return new LastRoundData(checkRound, trueElo);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Checks if a player is capped
     */
    private boolean isPlayerCapped(Connection conn, String playerName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM A2_CappedPlayers WHERE name = ?";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playerName);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        
        return false;
    }

    /**
     * Formats player data as a table
     */
    private String formatPlayersTable(List<PlayerRankData> players, String homeHall) {
        // Table columns: Rank (right, 3 chars), Elo (right), Hall (center, 2 chars), LR (center), Cap (center, 3 chars), Name (left, max 20 chars)
        String[] headers = {"Rank", "Elo", "Hall", "LR", "Cap", "Name"};
        Alignment[] alignments = {Alignment.RIGHT, Alignment.RIGHT, Alignment.CENTER, Alignment.CENTER, Alignment.CENTER, Alignment.LEFT};
        int[] columnWidths = {4, 4, 4, 3, 3, 20};

        List<String[]> rows = new ArrayList<>();

        int rank = 1;
        for (PlayerRankData player : players) {
            String rankStr = String.valueOf(rank);
            String elo = String.valueOf(player.trueElo);
            String hall = TableFormatter.shortenHallName(player.hall);
            String lastRound = TableFormatter.shortenRoundName(player.lastRound);
            String cap = player.isCapped ? "*" : "";
            String name = TableFormatter.shortenPlayerName(player.name, 20);

            rows.add(new String[]{rankStr, elo, hall, lastRound, cap, name});
            rank++;
        }

        String table = TableFormatter.formatTable(headers, rows, alignments, columnWidths);
        
        // Add asterisk to home hall rows
        if (!homeHall.isEmpty()) {
            String[] lines = table.split("\n");
            StringBuilder result = new StringBuilder();
            int rowIndex = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                // Skip first 3 lines: opening ```, header, and === separator
                if (i < 3) {
                    result.append(line).append("\n");
                } else if (line.contains("----")) {
                    // For --- separator lines in the middle, just append without checking
                    result.append(line).append("\n");
                } else if (line.trim().equals("```")) {
                    // Closing ``` tag, just append
                    result.append(line);
                } else {
                    // Data row - check if this row's player hall matches homeHall
                    if (rowIndex < players.size() && players.get(rowIndex).hall.equals(homeHall)) {
                        result.append(line).append("*\n");
                    } else {
                        result.append(line).append("\n");
                    }
                    rowIndex++;
                }
            }
            return result.toString().trim();
        }
        
        return table;
    }
    
    /**
     * Generates an image of the players table
     */
    private Path generatePlayersImage(List<PlayerRankData> players, Set<Integer> highlightRows, String selectedRound) throws Exception {
        String[] headers = {"Rank", "Elo", "Hall", "LR", "Cap", "Name"};
        Alignment[] alignments = {Alignment.RIGHT, Alignment.RIGHT, Alignment.CENTER, Alignment.CENTER, Alignment.CENTER, Alignment.LEFT};
        int[] columnWidths = {4, 4, 4, 3, 3, 20};

        List<String[]> rows = new ArrayList<>();

        int rank = 1;
        for (PlayerRankData player : players) {
            String rankStr = String.valueOf(rank);
            String elo = String.valueOf(player.trueElo);
            String hall = TableFormatter.shortenHallName(player.hall);
            String lastRound = TableFormatter.shortenRoundName(player.lastRound);
            String cap = player.isCapped ? "*" : "";
            String name = TableFormatter.shortenPlayerName(player.name, 20);

            rows.add(new String[]{rankStr, elo, hall, lastRound, cap, name});
            rank++;
        }

        // Use the selected round for metadata (not the individual player's last round)
        // If "all" was selected, find the actual latest round from the player data
        String lastRoundForMetadata;
        if (selectedRound.equalsIgnoreCase("all")) {
            // Find the highest round from all players
            String maxRound = null;
            for (PlayerRankData player : players) {
                if (player.lastRound != null) {
                    if (maxRound == null || Constants.ROUND_SEQUENCE.indexOf(player.lastRound) > Constants.ROUND_SEQUENCE.indexOf(maxRound)) {
                        maxRound = player.lastRound;
                    }
                }
            }
            lastRoundForMetadata = maxRound;
        } else {
            lastRoundForMetadata = selectedRound;
        }
        
        // Create metadata with title, description, and last round
        TableImageGenerator.ImageMetadata metadata = new TableImageGenerator.ImageMetadata(
            "Player Rankings",
            "Players ranked by TrueElo rating",
            lastRoundForMetadata
        );

        // Use actual last round for filename (not "all")
        String entityName = lastRoundForMetadata != null ? lastRoundForMetadata : "unknown";
        return TableImageGenerator.generatePlayerTable(headers, rows, alignments, columnWidths, metadata, highlightRows, "RankPlayers", entityName);
    }
    
    /**
     * Response object containing message and image path
     */
    /**
     * Response class (alias for CommandResponse for backward compatibility)
     */
    public static class RankResponse extends CommandResponse {
        public RankResponse(String message, Path imagePath) {
            super(message, imagePath);
        }
        
        public RankResponse(String message, ButtonConfig buttonConfig) {
            super(message, buttonConfig);
        }
    }
}
