package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;
import com.calplus.ihrgstats.utils.TableFormatter.Alignment;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Command handler for /rankhalls command.
 * Displays hall rankings based on average TrueElo of top 5 players (or all if less than 5).
 */
public class CommandRankHalls {
    private final LogHelper logHelper;
    private final String dbPath;

    // State management for round selection (static so it persists across instances)
    private static final Map<String, HallRankSelectionState> userSelectionStates = new HashMap<>();
    
    private static class HallRankSelectionState extends SelectionState {
        String selectedRound;  // "all" or specific round
    }

    public CommandRankHalls() {
        // Load environment variables
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.logHelper = new LogHelper();
        this.dbPath = DatabaseHelper.getDefaultDatabasePathString();
    }

    /**
     * Represents a hall's ranking data
     */
    private static class HallRankData {
        String hallName;
        double averageElo;
        int playerCount;
        int cappedCount;

        HallRankData(String hallName, double averageElo, int playerCount, int cappedCount) {
            this.hallName = hallName;
            this.averageElo = averageElo;
            this.playerCount = playerCount;
            this.cappedCount = cappedCount;
        }
    }

    /**
     * Represents a player's Elo data
     */
    private static class PlayerEloData {
        String name;
        String hall;
        Integer trueElo;
        String lastRound;
        boolean capped;

        PlayerEloData(String name, String hall, Integer trueElo, String lastRound, boolean capped) {
            this.name = name;
            this.hall = hall;
            this.trueElo = trueElo;
            this.lastRound = lastRound;
            this.capped = capped;
        }
    }

    /**
     * Holds last round data (round name and elo)
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
     * Handles the /rankhalls command - shows round selection UI
     * @param userId User ID for state tracking
     * @return Response with round selection buttons
     */
    public RankResponse handleCommand(String userId) {
        logHelper.logInfo("Processing /rankhalls command for user " + userId);

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
        callbacks.add("rankhalls_round_all");
        
        // Add round buttons
        for (String round : availableRounds) {
            labels.add(round.toUpperCase());
            callbacks.add("rankhalls_round_" + round.toLowerCase());
        }
        
        // Add "Cancel" button last
        labels.add("❌ Cancel");
        callbacks.add("rankhalls_cancel");

        String message = "🏆 **Hall Rankings**\n\n" +
                        "Select which round to rank halls up to:";

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
        HallRankSelectionState state = new HallRankSelectionState();
        state.selectedRound = selectedRound.toLowerCase();
        userSelectionStates.put(userId, state);

        // Fetch all players with their Elo (filtered by round)
        List<PlayerEloData> players = fetchPlayerData(selectedRound);

        if (players.isEmpty()) {
            String errorMsg = "ℹ️ No player data found for round " + selectedRound.toUpperCase() + ".";
            logHelper.logWarning("No players found for hall ranking in round " + selectedRound);
            userSelectionStates.remove(userId);
            return new RankResponse(errorMsg, (Path) null);
        }

        // Group by hall and calculate averages
        List<HallRankData> halls = calculateHallRankings(players);

        if (halls.isEmpty()) {
            String errorMsg = "ℹ️ No hall rankings could be calculated for round " + selectedRound.toUpperCase() + ".";
            logHelper.logWarning("No hall rankings calculated");
            userSelectionStates.remove(userId);
            return new RankResponse(errorMsg, (Path) null);
        }

        // Sort by average Elo descending
        halls.sort((h1, h2) -> Double.compare(h2.averageElo, h1.averageElo));

        // Get home hall setting for asterisk in text
        String homeHallForText = PropertyResolver.getProperty("settings.homeHall", "");

        // Format as table
        String table = formatHallsTable(halls, homeHallForText);

        String roundDisplay = selectedRound.equalsIgnoreCase("all") ? "All Rounds" : "Round " + selectedRound.toUpperCase();
        String message = "🏆 **Hall Rankings** (" + roundDisplay + ")\n\n" +
                        "Halls ranked by average TrueElo of top 5 players\n\n" +
                        table;

        // Generate table image with home hall highlighting
        Path imagePath = null;
        try {
            // Get home hall setting and find matching rows
            String homeHall = PropertyResolver.getProperty("settings.homeHall", "");
            Set<Integer> highlightRows = new HashSet<>();
            if (!homeHall.isEmpty()) {
                for (int i = 0; i < halls.size(); i++) {
                    if (halls.get(i).hallName.equals(homeHall)) {
                        highlightRows.add(i);
                        break;
                    }
                }
            }
            
            imagePath = generateHallsImage(halls, players, highlightRows, selectedRound);
        } catch (Exception e) {
            logHelper.logWarning("Failed to generate table image: " + e.getMessage());
        }

        // Clean up state
        userSelectionStates.remove(userId);

        logHelper.logSuccess(String.format("Ranked %d halls", halls.size()));

        return new RankResponse(message, imagePath);
    }

    /**
     * Handles cancel action
     * @param userId User ID
     * @return Response confirming cancellation
     */
    public RankResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        String message = "❌ Hall ranking cancelled.";
        return new RankResponse(message, (Path) null);
    }

    /**
     * Fetches player data from database, optionally filtering by round
     * @param round The round to fetch data up to ("all" for all rounds, or specific round like "1", "t16")
     */
    private List<PlayerEloData> fetchPlayerData(String round) {
        List<PlayerEloData> players = new ArrayList<>();

        try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
                String sql = "SELECT name, hall, capped FROM A1_PlayerStats WHERE active = 1";
                
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {

                    while (rs.next()) {
                        String name = rs.getString("name");
                        String hall = rs.getString("hall");
                        boolean capped = rs.getInt("capped") == 1;

                        // Find last round played and corresponding TrueElo (up to selected round)
                        LastRoundData lastRound = findLastRoundData(conn, name, round);
                        
                        if (lastRound != null && lastRound.trueElo != null) {
                            players.add(new PlayerEloData(name, hall, lastRound.trueElo, lastRound.roundName, capped));
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
     * Finds the last round data (round name and TrueElo) for a player, up to the specified round
     * @param round The round to check up to ("all" for all rounds)
     */
    private LastRoundData findLastRoundData(Connection conn, String playerName, String round) throws SQLException {
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

                        // Player actually played if they have an opponent
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
     * Calculates hall rankings from player data
     */
    private List<HallRankData> calculateHallRankings(List<PlayerEloData> players) {
        // Group players by hall
        Map<String, List<PlayerEloData>> hallGroups = new HashMap<>();
        
        for (PlayerEloData player : players) {
            hallGroups.computeIfAbsent(player.hall, k -> new ArrayList<>()).add(player);
        }

        List<HallRankData> hallRankings = new ArrayList<>();

        // Calculate average for each hall
        for (Map.Entry<String, List<PlayerEloData>> entry : hallGroups.entrySet()) {
            String hall = entry.getKey();
            List<PlayerEloData> hallPlayers = entry.getValue();

            // Sort players by Elo descending
            hallPlayers.sort((p1, p2) -> Integer.compare(p2.trueElo, p1.trueElo));

            // Take top 5 (or all if less than 5)
            int count = Math.min(5, hallPlayers.size());
            int sum = 0;
            
            for (int i = 0; i < count; i++) {
                sum += hallPlayers.get(i).trueElo;
            }

            double average = (double) sum / count;
            
            // Count capped players in this hall
            int cappedCount = (int) hallPlayers.stream().filter(p -> p.capped).count();
            
            hallRankings.add(new HallRankData(hall, average, count, cappedCount));
        }

        return hallRankings;
    }

    /**
     * Formats hall data as a table
     */
    private String formatHallsTable(List<HallRankData> halls, String homeHall) {
        // Table columns: Rank (right, 3 chars), Hall (left), Cap (right), Avg Elo (right, 1 decimal)
        String[] headers = {"Rank", "Hall", "Cap", "Avg Elo"};
        Alignment[] alignments = {Alignment.RIGHT, Alignment.LEFT, Alignment.RIGHT, Alignment.RIGHT};
        int[] columnWidths = {4, 10, 3, 7};

        List<String[]> rows = new ArrayList<>();

        int rank = 1;
        for (HallRankData hall : halls) {
            String rankStr = String.valueOf(rank);
            String hallName = hall.hallName;
            String cappedStr = String.valueOf(hall.cappedCount);
            String avgElo = String.format("%.1f", hall.averageElo);

            rows.add(new String[]{rankStr, hallName, cappedStr, avgElo});
            rank++;
        }

        String table = TableFormatter.formatTable(headers, rows, alignments, columnWidths);
        
        // Add asterisk to home hall row
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
                    // Data row - check if this row's hall matches homeHall
                    if (rowIndex < halls.size() && halls.get(rowIndex).hallName.equals(homeHall)) {
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
     * Generates an image of the halls table
     */
    private Path generateHallsImage(List<HallRankData> halls, List<PlayerEloData> players, Set<Integer> highlightRows, String selectedRound) throws Exception {
        String[] headers = {"Rank", "Hall", "Cap", "Avg Elo"};
        Alignment[] alignments = {Alignment.RIGHT, Alignment.LEFT, Alignment.RIGHT, Alignment.RIGHT};
        int[] columnWidths = {4, 10, 3, 7};

        List<String[]> rows = new ArrayList<>();
        List<String> hallNames = new ArrayList<>();

        int rank = 1;
        for (HallRankData hall : halls) {
            String rankStr = String.valueOf(rank);
            String hallName = hall.hallName;
            String cappedStr = String.valueOf(hall.cappedCount);
            String avgElo = String.format("%.1f", hall.averageElo);

            rows.add(new String[]{rankStr, hallName, cappedStr, avgElo});
            hallNames.add(hallName);
            rank++;
        }

        // Use the selected round for metadata
        // If "all" was selected, find the actual latest round from the data
        String lastRoundForMetadata;
        if (selectedRound.equalsIgnoreCase("all")) {
            // Find the highest round that has data by checking rounds in reverse order
            String maxRound = null;
            try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
                // Check rounds in reverse order to find the latest one with data
                for (int i = Constants.ROUND_SEQUENCE.size() - 1; i >= 0; i--) {
                    String checkRound = Constants.ROUND_SEQUENCE.get(i);
                    String oppNameCol = RoundUtils.getRoundColumnName("oppName", checkRound);
                    
                    String sql = String.format(
                        "SELECT COUNT(*) as count FROM A1_PlayerStats WHERE active = 1 AND %s IS NOT NULL AND %s != ''",
                        oppNameCol, oppNameCol
                    );
                    
                    try (PreparedStatement pstmt = conn.prepareStatement(sql);
                         ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next() && rs.getInt("count") > 0) {
                            maxRound = checkRound;
                            break;  // Found the latest round with data
                        }
                    }
                }
            }
            lastRoundForMetadata = maxRound;
        } else {
            lastRoundForMetadata = selectedRound;
        }
        
        // Create metadata with title, description, and last round
        TableImageGenerator.ImageMetadata metadata = new TableImageGenerator.ImageMetadata(
            "Hall Rankings",
            "Halls ranked by average\nTrueElo (top 5 players)",
            lastRoundForMetadata
        );

        // Use actual last round for filename (not "all")
        String entityName = lastRoundForMetadata != null ? lastRoundForMetadata : "unknown";
        return TableImageGenerator.generateHallTable(headers, rows, hallNames, alignments, columnWidths, metadata, highlightRows, "RankHalls", entityName);
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
