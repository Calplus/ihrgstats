package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Command handler for /compareplayers command.
 * Allows comparison of two players with detailed statistics.
 */
public class CommandComparePlayers {
    private final LogHelper logHelper;
    private final String dbPath;
    
    // State management for multi-step selection (static so it persists across instances)
    private static final Map<String, PlayerCompareSelectionState> userSelectionStates = new HashMap<>();
    
    private static class PlayerCompareSelectionState extends SelectionState {
        String firstPlayerHall;
        String firstPlayerName;
        String secondPlayerHall;
        String secondPlayerName;
        String selectedRound;  // "all" or specific round
    }
    
    public CommandComparePlayers() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.logHelper = new LogHelper();
        this.dbPath = DatabaseHelper.getDefaultDatabasePathString();
    }
    
    /**
     * Response class (alias for CommandResponse for backward compatibility)
     */
    public static class CompareResponse extends CommandResponse {
        public CompareResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            super(message, imagePath, buttonConfig);
        }
    }
    
    /**
     * Handles the /compareplayers command (initial call)
     */
    public CompareResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("User %s requested /compareplayers command", userId));
        
        // Clear any existing state
        userSelectionStates.put(userId, new PlayerCompareSelectionState());
        
        // Fetch available halls
        List<String> halls = HallUtils.fetchAndSortAvailableHalls(dbPath);
        
        if (halls.isEmpty()) {
            String errorMsg = "ℹ️ No halls found in database. Please upload round data first.";
            logHelper.logWarning("No halls available for player comparison");
            return new CompareResponse(errorMsg, null, null);
        }
        
        // Create button layout (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String hall : halls) {
            labels.add(hall);
            callbacks.add("compareplayers_selecthall1_" + hall);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("compareplayers_cancel");
        
        String message = "**👥 Player Comparison**\n\n" +
                        "Select the **first player's hall**:";
        
        return new CompareResponse(message, null, 
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles first player's hall selection
     */
    public CompareResponse handleFirstHallSelection(String userId, String firstHall) {
        logHelper.logInfo(String.format("User %s selected first player's hall: %s", userId, firstHall));
        
        // Store state
        PlayerCompareSelectionState state = (PlayerCompareSelectionState) userSelectionStates.get(userId);
        if (state == null) state = new PlayerCompareSelectionState();
        state.firstPlayerHall = firstHall;
        userSelectionStates.put(userId, state);
        
        // Fetch players from the hall
        List<String> players = fetchPlayersFromHall(firstHall);
        
        if (players.isEmpty()) {
            String errorMsg = "ℹ️ No players found in hall " + firstHall + ".";
            userSelectionStates.remove(userId);
            return new CompareResponse(errorMsg, null, null);
        }
        
        // Create button layout (1 per row)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String player : players) {
            labels.add(player);
            callbacks.add("compareplayers_selectplayer1_" + player);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("compareplayers_cancel");
        
        String message = String.format("**👥 Player Comparison**\n\n" +
                                      "First player's hall: **%s**\n" +
                                      "Select the **first player**:", firstHall);
        
        return new CompareResponse(message, null,
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles first player selection
     */
    public CompareResponse handleFirstPlayerSelection(String userId, String firstPlayer) {
        PlayerCompareSelectionState state = (PlayerCompareSelectionState) userSelectionStates.get(userId);
        if (state == null || state.firstPlayerHall == null) {
            String errorMsg = "❌ Session expired. Please use /compareplayers to start again.";
            return new CompareResponse(errorMsg, null, null);
        }
        
        state.firstPlayerName = firstPlayer;
        
        logHelper.logInfo(String.format("User %s selected first player: %s from %s", 
            userId, firstPlayer, state.firstPlayerHall));
        
        // Fetch available halls for second player
        List<String> halls = HallUtils.fetchAndSortAvailableHalls(dbPath);
        
        if (halls.isEmpty()) {
            String errorMsg = "ℹ️ No halls available for second player selection.";
            userSelectionStates.remove(userId);
            return new CompareResponse(errorMsg, null, null);
        }
        
        // Create button layout (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String hall : halls) {
            labels.add(hall);
            callbacks.add("compareplayers_selecthall2_" + hall);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("compareplayers_cancel");
        
        String message = String.format("**👥 Player Comparison**\n\n" +
                                      "First player: **%s** (%s)\n" +
                                      "Select the **second player's hall**:",
                                      firstPlayer, state.firstPlayerHall);
        
        return new CompareResponse(message, null,
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles second player's hall selection
     */
    public CompareResponse handleSecondHallSelection(String userId, String secondHall) {
        PlayerCompareSelectionState state = (PlayerCompareSelectionState) userSelectionStates.get(userId);
        if (state == null || state.firstPlayerName == null) {
            String errorMsg = "❌ Session expired. Please use /compareplayers to start again.";
            return new CompareResponse(errorMsg, null, null);
        }
        
        state.secondPlayerHall = secondHall;
        
        logHelper.logInfo(String.format("User %s selected second player's hall: %s", userId, secondHall));
        
        // Fetch players from the hall
        List<String> players = fetchPlayersFromHall(secondHall);
        
        // If same hall as first player, remove the first player's name
        if (secondHall.equals(state.firstPlayerHall)) {
            players.remove(state.firstPlayerName);
        }
        
        if (players.isEmpty()) {
            String errorMsg = "ℹ️ No other players available in hall " + secondHall + ".";
            userSelectionStates.remove(userId);
            return new CompareResponse(errorMsg, null, null);
        }
        
        // Create button layout (1 per row)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String player : players) {
            labels.add(player);
            callbacks.add("compareplayers_selectplayer2_" + player);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("compareplayers_cancel");
        
        String message = String.format("**👥 Player Comparison**\n\n" +
                                      "First player: **%s** (%s)\n" +
                                      "Second player's hall: **%s**\n" +
                                      "Select the **second player**:",
                                      state.firstPlayerName, state.firstPlayerHall, secondHall);
        
        return new CompareResponse(message, null,
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles second player selection
     */
    public CompareResponse handleSecondPlayerSelection(String userId, String secondPlayer) {
        PlayerCompareSelectionState state = (PlayerCompareSelectionState) userSelectionStates.get(userId);
        if (state == null || state.firstPlayerName == null || state.secondPlayerHall == null) {
            String errorMsg = "❌ Session expired. Please use /compareplayers to start again.";
            return new CompareResponse(errorMsg, null, null);
        }
        
        state.secondPlayerName = secondPlayer;
        
        logHelper.logInfo(String.format("User %s selected second player: %s", userId, secondPlayer));
        
        // Get available rounds
        List<String> availableRounds = getAvailableRounds();
        
        if (availableRounds.isEmpty()) {
            String errorMsg = "ℹ️ No round data available.";
            userSelectionStates.remove(userId);
            return new CompareResponse(errorMsg, null, null);
        }
        
        // Create round selection buttons (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        // Add "All" option first
        labels.add("All Rounds");
        callbacks.add("compareplayers_selectround_all");
        
        // Add individual rounds
        for (String round : availableRounds) {
            labels.add(VictoryRecordCalculator.getRoundDisplayName(round));
            callbacks.add("compareplayers_selectround_" + round);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("compareplayers_cancel");
        
        String message = String.format("**👥 Player Comparison**\n\n" +
                                      "First player: **%s** (%s)\n" +
                                      "Second player: **%s** (%s)\n\n" +
                                      "Select rounds to compare:",
                                      state.firstPlayerName, state.firstPlayerHall, secondPlayer, state.secondPlayerHall);
        
        return new CompareResponse(message, null,
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles round selection and generates comparison
     */
    public CompareResponse handleRoundSelection(String userId, String selectedRound) {
        PlayerCompareSelectionState state = (PlayerCompareSelectionState) userSelectionStates.get(userId);
        if (state == null || state.firstPlayerName == null || state.secondPlayerName == null) {
            String errorMsg = "❌ Session expired. Please use /compareplayers to start again.";
            return new CompareResponse(errorMsg, null, null);
        }
        
        state.selectedRound = selectedRound;
        String firstPlayer = state.firstPlayerName;
        String firstHall = state.firstPlayerHall;
        String secondPlayer = state.secondPlayerName;
        String secondHall = state.secondPlayerHall;
        userSelectionStates.remove(userId);
        
        logHelper.logInfo(String.format("User %s comparing players: %s (%s) vs %s (%s) (rounds: %s)", 
            userId, firstPlayer, firstHall, secondPlayer, secondHall, selectedRound));
        
        try {
            // Generate comparison
            return generateComparison(firstPlayer, firstHall, secondPlayer, secondHall, selectedRound);
        } catch (Exception e) {
            String errorMsg = "❌ Error generating comparison: " + e.getMessage();
            logHelper.logError("Player comparison error: " + e.getMessage());
            e.printStackTrace();
            return new CompareResponse(errorMsg, null, null);
        }
    }
    
    /**
     * Handles cancellation
     */
    public CompareResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new CompareResponse("ℹ️ Player comparison cancelled.", null, null);
    }
    
    /**
     * Fetches available halls from database
     */
    /**
     * Fetches players from a specific hall
     */
    private List<String> fetchPlayersFromHall(String hall) {
        List<String> players = new ArrayList<>();
        
        try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
            String sql = "SELECT name FROM A1_PlayerStats WHERE hall = ? AND active = 1 ORDER BY name";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, hall);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    players.add(rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            logHelper.logError("Database error fetching players: " + e.getMessage());
        }
        
        return players;
    }
    
    /**
     * Gets available rounds from database
     */
    private List<String> getAvailableRounds() {
        // Use RoundDetector to get only rounds that have actually been played
        // This filters out skipped rounds (e.g., round 6 when transitioning to T16)
        return RoundDetector.getAvailableRounds(dbPath);
    }
    
    /**
     * Player data container
     */
    private static class PlayerData {
        String name;
        String hall;
        boolean capped;
        String lastRound;
        Map<String, Integer> rankByRound;
        Map<String, Integer> eloByRound;
        Map<String, Integer> seatByRound;
        Map<String, Integer> outcomeByRound;
        Map<String, String> oppNameByRound;
        Map<String, String> oppHallByRound;
        Map<String, Integer> oppEloByRound;  // Opponent ELO for each round
        Map<String, Double> scoreByRound;    // Player's board win score for each round
        
        PlayerData(String name, String hall, boolean capped) {
            this.name = name;
            this.hall = hall;
            this.capped = capped;
            this.rankByRound = new HashMap<>();
            this.eloByRound = new HashMap<>();
            this.seatByRound = new HashMap<>();
            this.outcomeByRound = new HashMap<>();
            this.oppNameByRound = new HashMap<>();
            this.oppHallByRound = new HashMap<>();
            this.oppEloByRound = new HashMap<>();
            this.scoreByRound = new HashMap<>();
        }
    }
    
    /**
     * Generates complete comparison data
     */
    private CompareResponse generateComparison(String player1Name, String player1Hall,
                                              String player2Name, String player2Hall, String selectedRound) throws Exception {
        // Get available rounds (excluding skipped rounds like round 6 when transitioning to T16)
        List<String> availableRounds = RoundDetector.getAvailableRounds(dbPath);
        
        // Determine which rounds to include based on selected round
        List<String> roundsToInclude;
        if (selectedRound.equals("all")) {
            roundsToInclude = availableRounds;
        } else {
            // Include only available rounds up to the selected round
            int selectedIndex = Constants.ROUND_SEQUENCE.indexOf(selectedRound);
            roundsToInclude = availableRounds.stream()
                .filter(r -> Constants.ROUND_SEQUENCE.indexOf(r) <= selectedIndex)
                .collect(Collectors.toList());
        }
        
        // Fetch player data
        PlayerData data1 = fetchPlayerData(player1Name, player1Hall, roundsToInclude);
        PlayerData data2 = fetchPlayerData(player2Name, player2Hall, roundsToInclude);
        
        if (data1 == null) {
            throw new Exception("Player " + player1Name + " not found in hall " + player1Hall);
        }
        if (data2 == null) {
            throw new Exception("Player " + player2Name + " not found in hall " + player2Hall);
        }
        
        // Generate text output
        String textOutput = generateTextOutput(data1, data2, roundsToInclude);
        
        // Generate image
        Path imagePath = generateImage(data1, data2, roundsToInclude);
        
        logHelper.logSuccess(String.format("Generated player comparison: %s (%s) vs %s (%s) (rounds: %s)", 
            player1Name, player1Hall, player2Name, player2Hall, selectedRound));
        
        return new CompareResponse(textOutput, imagePath, null);
    }
    
    /**
     * Fetches complete player data from database
     */
    private PlayerData fetchPlayerData(String playerName, String hall, List<String> roundsToInclude) throws SQLException {
        
        try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
            // Build column list
            List<String> columns = new ArrayList<>();
            columns.add("name");
            columns.add("hall");
            columns.add("capped");
            for (String round : roundsToInclude) {
                String suffix = RoundUtils.getRoundColumnSuffix(round);
                columns.add("trueElo" + suffix);
                columns.add("seat" + suffix);
                columns.add("outcome" + suffix);
                columns.add("oppName" + suffix);
                columns.add("oppHall" + suffix);
                columns.add("score" + suffix);
            }
            
            String sql = "SELECT " + String.join(", ", columns) + 
                        " FROM A1_PlayerStats WHERE name = ? AND hall = ? AND active = 1";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, playerName);
                pstmt.setString(2, hall);
                ResultSet rs = pstmt.executeQuery();
                
                if (rs.next()) {
                    boolean capped = rs.getBoolean("capped");
                    PlayerData player = new PlayerData(playerName, hall, capped);
                    
                    // Find last round played
                    for (int i = roundsToInclude.size() - 1; i >= 0; i--) {
                        String round = roundsToInclude.get(i);
                        String colName = "trueElo" + RoundUtils.getRoundColumnSuffix(round);
                        Integer elo = (Integer) rs.getObject(colName);
                        if (elo != null) {
                            player.lastRound = round;
                            break;
                        }
                    }
                    
                    // Load data for included rounds only
                    for (String round : roundsToInclude) {
                        String suffix = RoundUtils.getRoundColumnSuffix(round);
                        Integer elo = (Integer) rs.getObject("trueElo" + suffix);
                        Integer seat = (Integer) rs.getObject("seat" + suffix);
                        Integer outcome = (Integer) rs.getObject("outcome" + suffix);
                        String oppName = rs.getString("oppName" + suffix);
                        String oppHall = rs.getString("oppHall" + suffix);
                        Double score = (Double) rs.getObject("score" + suffix);
                        
                        if (elo != null) {
                            player.eloByRound.put(round, elo);
                            // Calculate rank for this round
                            int rank = calculateRankForRound(conn, round, elo);
                            player.rankByRound.put(round, rank);
                        }
                        if (seat != null) player.seatByRound.put(round, seat);
                        if (outcome != null) player.outcomeByRound.put(round, outcome);
                        if (oppName != null) player.oppNameByRound.put(round, oppName);
                        if (oppHall != null) player.oppHallByRound.put(round, oppHall);
                        if (score != null) player.scoreByRound.put(round, score);
                        
                        // Fetch opponent ELO for this round
                        if (oppName != null && oppHall != null && !oppName.equalsIgnoreCase("WALKOVER")) {
                            Integer oppElo = fetchOpponentElo(conn, oppName, oppHall, suffix);
                            if (oppElo != null) {
                                player.oppEloByRound.put(round, oppElo);
                            }
                        }
                    }
                    
                    return player;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Fetches the opponent's ELO for a specific round
     */
    private Integer fetchOpponentElo(Connection conn, String oppName, String oppHall, String roundSuffix) throws SQLException {
        String sql = "SELECT trueElo" + roundSuffix + " FROM A1_PlayerStats WHERE Name = ? AND Hall = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, oppName);
            pstmt.setString(2, oppHall);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return (Integer) rs.getObject("trueElo" + roundSuffix);
                }
            }
        }
        return null;
    }
    
    /**
     * Calculates rank for a player in a specific round based on their LATEST ELO up to that round
     * Ranks among ALL players who existed up to that round
     */
    private int calculateRankForRound(Connection conn, String round, int playerElo) throws SQLException {
        // Get the index of current round
        int currentRoundIndex = Constants.ROUND_SEQUENCE.indexOf(round);
        if (currentRoundIndex == -1) {
            return 0;
        }
        
        // Build list of round suffixes up to current round
        List<String> roundSuffixes = new ArrayList<>();
        for (int i = 0; i <= currentRoundIndex; i++) {
            String suffix = RoundUtils.getRoundColumnSuffix(Constants.ROUND_SEQUENCE.get(i));
            if (suffix != null && !suffix.isEmpty()) {
                roundSuffixes.add(suffix);
            }
        }
        
        // Safety check: if no valid round suffixes, return 0
        if (roundSuffixes.isEmpty()) {
            return 0;
        }
        
        // Build WHERE clause to check if player has data in any round up to current round
        StringBuilder whereClause = new StringBuilder("(");
        for (int i = 0; i < roundSuffixes.size(); i++) {
            if (i > 0) whereClause.append(" OR ");
            whereClause.append("trueElo").append(roundSuffixes.get(i)).append(" IS NOT NULL");
        }
        whereClause.append(")");
        
        // Build expression to get latest ELO (check from current round backwards to R1)
        // COALESCE requires at least 2 arguments, so for single round use column directly
        String eloExpr;
        if (roundSuffixes.size() == 1) {
            eloExpr = "trueElo" + roundSuffixes.get(0);
        } else {
            StringBuilder coalesceExpr = new StringBuilder("COALESCE(");
            for (int i = roundSuffixes.size() - 1; i >= 0; i--) {
                if (i < roundSuffixes.size() - 1) coalesceExpr.append(", ");
                coalesceExpr.append("trueElo").append(roundSuffixes.get(i));
            }
            coalesceExpr.append(")");
            eloExpr = coalesceExpr.toString();
        }
        
        // Count players with higher latest ELO
        String sql = "SELECT COUNT(*) as rank FROM A1_PlayerStats " +
                    "WHERE " + whereClause.toString() + " AND " + eloExpr + " > ? AND active = 1";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, playerElo);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("rank") + 1;  // +1 because COUNT gives how many are higher
            }
        }
        return 0;
    }
    
    /**
     * Generates text output
     */
    private String generateTextOutput(PlayerData player1, PlayerData player2, List<String> roundsToInclude) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("**👥 Player Comparison**\n\n");
        sb.append(String.format("**%s** (%s) vs **%s** (%s)\n\n", 
            player1.name, player1.hall, player2.name, player2.hall));
        
        // Player 1 details
        sb.append(generatePlayerDetails(player1, roundsToInclude));
        sb.append("\n");
        
        // Player 2 details
        sb.append(generatePlayerDetails(player2, roundsToInclude));
        
        return sb.toString();
    }
    
    /**
     * Generates details for one player (text)
     */
    private String generatePlayerDetails(PlayerData player, List<String> roundsToInclude) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(String.format("━━━ **%s (%s)** ━━━\n\n", player.name, player.hall));
        
        // Player stats per round with deltas
        sb.append("**📊 Stats Per Round:**\n```\n");
        sb.append(String.format("%-4s %-6s %-10s %-6s %-10s\n", "Rnd", "Rank", "ΔRank", "ELO", "ΔELO"));
        sb.append(String.format("%-4s %-6s %-10s %-6s %-10s\n", "----", "------", "----------", "------", "----------"));
        
        Integer prevRank = null;
        Integer prevElo = null;
        
        for (String round : roundsToInclude) {
            Integer rank = player.rankByRound.get(round);
            Integer elo = player.eloByRound.get(round);
            
            if (rank != null && elo != null) {
                String deltaRank = "-";
                String deltaElo = "-";
                
                if (prevRank != null) {
                    int rankChange = prevRank - rank; // Positive = improvement (lower rank number)
                    if (rankChange > 0) {
                        deltaRank = "+" + rankChange;
                    } else if (rankChange < 0) {
                        deltaRank = "-" + Math.abs(rankChange);
                    } else {
                        deltaRank = "= ";
                    }
                } else {
                    deltaRank = "- ";
                }
                
                if (prevElo != null) {
                    int eloChange = elo - prevElo;
                    if (eloChange > 0) {
                        deltaElo = "+" + eloChange;
                    } else if (eloChange < 0) {
                        deltaElo = "-" + Math.abs(eloChange);
                    } else {
                        deltaElo = "= ";
                    }
                } else {
                    deltaElo = "- ";
                }
                
                sb.append(String.format("%-4s %-6d %-10s %-6d %-10s\n", 
                    VictoryRecordCalculator.getRoundDisplayName(round), rank, deltaRank, elo, deltaElo));
                
                prevRank = rank;
                prevElo = elo;
            }
        }
        sb.append("```\n\n");
        
        // Seating arrangement with proper alignment
        sb.append("**🪑 Seating Arrangement:**\n```\n");
        StringBuilder roundsLine = new StringBuilder();
        StringBuilder seatsLine = new StringBuilder();
        
        roundsLine.append("Rnd: ");
        seatsLine.append("Seat:");
        
        for (String round : roundsToInclude) {
            String roundName = VictoryRecordCalculator.getRoundDisplayName(round);
            Integer seat = player.seatByRound.get(round);
            String seatStr = seat != null ? String.valueOf(seat) : "-";
            
            // Pad to 4 characters with trailing space and separator
            roundsLine.append(String.format("%-4s", roundName)).append("|");
            seatsLine.append(String.format(" %-3s", seatStr)).append("|");
        }
        
        sb.append(roundsLine.toString()).append("\n");
        sb.append(seatsLine.toString()).append("\n");
        sb.append("```\n\n");
        
        // Victory record
        sb.append("**🏆 Victory Record:**\n```\n");
        for (String round : roundsToInclude) {
            Integer outcome = player.outcomeByRound.get(round);
            if (outcome == null) {
                if (player.eloByRound.containsKey(round)) {
                    // Player existed this round but didn't play
                    sb.append(String.format("%-3s  -NA-\n", VictoryRecordCalculator.getRoundDisplayName(round)));
                }
                continue;
            }
            
            String oppName = player.oppNameByRound.get(round);
            String oppHall = player.oppHallByRound.get(round);
            
            // Get ELO values (no parentheses to match image)
            Integer playerElo = player.eloByRound.get(round);
            Integer oppElo = player.oppEloByRound.get(round);
            String playerEloStr = playerElo != null ? String.valueOf(playerElo) : "?";
            String oppEloStr = oppElo != null ? String.valueOf(oppElo) : "?";
            
            // Format: emoji playerHall playerName score oppName oppHall oppEmoji
            String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(outcome);
            Integer oppOutcome = outcome == 0 ? 0 : -outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
            
            String playerHallFormatted = TableFormatter.shortenHallName(player.hall);
            String oppHallFormatted;
            
            // Format score - use actual score from database if available
            String score;
            Double playerScore = player.scoreByRound.get(round);
            
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                if (playerScore != null) {
                    String scoreStr = (playerScore == Math.floor(playerScore)) ? 
                        String.format("%.0f", playerScore) : String.format("%.1f", playerScore);
                    score = scoreStr + "-0";
                } else {
                    score = "1-0";  // Fallback
                }
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
                oppEloStr = "-";  // Dash for WALKOVER
                oppHallFormatted = "";  // No hall for WALKOVER
            } else {
                oppHallFormatted = oppHall != null ? TableFormatter.shortenHallName(oppHall) : "??";
                if (playerScore != null) {
                    double maxSeeds = Double.parseDouble(PropertyResolver.getProperty("settings.maxSeeds", "368.5"));
                    double oppScore = maxSeeds - playerScore;
                    
                    String playerScoreStr = (playerScore == Math.floor(playerScore)) ? 
                        String.format("%.0f", playerScore) : String.format("%.1f", playerScore);
                    String oppScoreStr = (oppScore == Math.floor(oppScore)) ? 
                        String.format("%.0f", oppScore) : String.format("%.1f", oppScore);
                        
                    score = playerScoreStr + "-" + oppScoreStr;
                } else {
                    // Fallback to outcome-based if score not available
                    if (outcome == 1) {
                        score = "1-0";
                    } else if (outcome == 0) {
                        score = "0.5-0.5";
                    } else {
                        score = "0-1";
                    }
                }
            }
            
            // Build line matching image format: Rnd emoji hallAbbr elo playerName score oppName elo hallAbbr emoji
            String line = String.format("%-3s %s %-2s %-4s %-16s %s %-16s %-4s %-2s %s",
                VictoryRecordCalculator.getRoundDisplayName(round),
                hallEmoji,
                playerHallFormatted,
                playerEloStr,
                player.name,
                score,
                oppName != null ? oppName : "?",
                oppEloStr,
                oppHallFormatted,
                oppEmoji);
            sb.append(line).append("\n");
        }
        sb.append("```\n\n");
        
        return sb.toString();
    }
    
    /**
     * Formats hall name for display (used in text output)
     */
    private String formatHallName(String hallName) {
        if (hallName.equalsIgnoreCase("WALKOVER")) {
            return "WALKOVER";
        }
        try {
            int num = Integer.parseInt(hallName);
            return "Hall " + num;
        } catch (NumberFormatException e) {
            return hallName + " Hall";
        }
    }
    
    /**
     * Formats hall name for image (no "Hall" prefix for numbers)
     */
    private String formatHallNameForImage(String hallName) {
        if (hallName.equalsIgnoreCase("WALKOVER")) {
            return "WALKOVER";
        }
        try {
            int num = Integer.parseInt(hallName);
            return String.valueOf(num);  // Just the number
        } catch (NumberFormatException e) {
            return hallName;  // Just the name without " Hall"
        }
    }
    
    /**
     * Creates subtitle for image header (follows hall naming convention)
     */
    private String createSubtitle(String name, String hall) {
        try {
            int num = Integer.parseInt(hall);
            return String.format("%s (Hall %d)", name, num);
        } catch (NumberFormatException e) {
            return String.format("%s (%s Hall)", name, hall);
        }
    }
    
    /**
     * Generates comparison image
     */
    private Path generateImage(PlayerData player1, PlayerData player2, List<String> roundsToInclude) throws Exception {
        // Prepare metadata
        String lastRound1 = player1.lastRound != null ? VictoryRecordCalculator.getRoundDisplayName(player1.lastRound) : "N/A";
        String lastRound2 = player2.lastRound != null ? VictoryRecordCalculator.getRoundDisplayName(player2.lastRound) : "N/A";
        String lastRound = lastRound1.equals(lastRound2) ? lastRound1 : lastRound1 + " / " + lastRound2;
        
        String description = String.format("%s (%s) vs %s (%s)", 
            player1.name, player1.hall, player2.name, player2.hall);
        ComparisonImageGenerator.ImageMetadata metadata = new ComparisonImageGenerator.ImageMetadata(
            "Player Comparison", description, lastRound);
        
        // Prepare left side data (player 1)
        List<ComparisonImageGenerator.Section> sections1 = new ArrayList<>();
        
        // Stats per round with deltas
        List<String> statsLines1 = new ArrayList<>();
        statsLines1.add(String.format("%-4s %-6s %-10s %-6s %-10s", "Rnd", "Rank", "ΔRank", "ELO", "ΔELO"));
        
        Integer prevRank1 = null;
        Integer prevElo1 = null;
        for (String round : roundsToInclude) {
            Integer rank = player1.rankByRound.get(round);
            Integer elo = player1.eloByRound.get(round);
            if (rank != null && elo != null) {
                String deltaRank = "-";
                String deltaElo = "-";
                
                if (prevRank1 != null) {
                    int rankChange = prevRank1 - rank;
                    if (rankChange > 0) {
                        deltaRank = "+" + rankChange;
                    } else if (rankChange < 0) {
                        deltaRank = "-" + Math.abs(rankChange);
                    } else {
                        deltaRank = "=";
                    }
                }
                
                if (prevElo1 != null) {
                    int eloChange = elo - prevElo1;
                    if (eloChange > 0) {
                        deltaElo = "+" + eloChange;
                    } else if (eloChange < 0) {
                        deltaElo = "-" + Math.abs(eloChange);
                    } else {
                        deltaElo = "=";
                    }
                }
                
                statsLines1.add(String.format("%-4s %-6d %-10s %-6d %-10s", 
                    VictoryRecordCalculator.getRoundDisplayName(round), rank, deltaRank, elo, deltaElo));
                
                prevRank1 = rank;
                prevElo1 = elo;
            }
        }
        sections1.add(new ComparisonImageGenerator.Section("Stats Per Round", statsLines1));
        
        // Seating arrangement
        List<String> seatLines1 = new ArrayList<>();
        StringBuilder seatHeader1 = new StringBuilder("Rnd: ");
        StringBuilder seatData1 = new StringBuilder("Seat:");
        for (String round : roundsToInclude) {
            seatHeader1.append(String.format("%-3s|", VictoryRecordCalculator.getRoundDisplayName(round)));
            Integer seat = player1.seatByRound.get(round);
            seatData1.append(String.format("%-3s|", seat != null ? seat : "-"));
        }
        seatLines1.add(seatHeader1.toString());
        seatLines1.add(seatData1.toString());
        sections1.add(new ComparisonImageGenerator.Section("Seating", seatLines1));
        
        // Victory record - use structured data
        List<ComparisonImageGenerator.PlayerVictoryEntry> victoryEntries1 = new ArrayList<>();
        for (String round : roundsToInclude) {
            Integer outcome = player1.outcomeByRound.get(round);
            if (outcome == null) {
                if (player1.eloByRound.containsKey(round)) {
                    victoryEntries1.add(new ComparisonImageGenerator.PlayerVictoryEntry(
                        VictoryRecordCalculator.getRoundDisplayName(round),
                        true  // isNA
                    ));
                }
                continue;
            }
            
            String oppName = player1.oppNameByRound.get(round);
            String oppHall = player1.oppHallByRound.get(round);
            
            String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(outcome);
            Integer oppOutcome = outcome == 0 ? 0 : -outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
            
            // Use 2-letter hall abbreviations
            String playerHallFormatted = TableFormatter.shortenHallName(player1.hall);
            String oppHallFormatted = oppHall != null ? TableFormatter.shortenHallName(oppHall) : "??";
            
            // Get player ELO for this round
            Integer playerElo = player1.eloByRound.get(round);
            String playerEloStr = playerElo != null ? String.valueOf(playerElo) : "?";
            
            // Get opponent ELO from the fetched data
            Integer oppElo = player1.oppEloByRound.get(round);
            String oppEloStr = oppElo != null ? String.valueOf(oppElo) : "?";
            
            // Format score - use actual score from database if available
            String score;
            Double playerScore = player1.scoreByRound.get(round);
            
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                if (playerScore != null) {
                    String scoreStr = (playerScore == Math.floor(playerScore)) ? 
                        String.format("%.0f", playerScore) : String.format("%.1f", playerScore);
                    score = scoreStr + "-0";
                } else {
                    score = "1-0";  // Fallback
                }
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
                oppEloStr = "-";  // Show dash for WALKOVER ELO
            } else if (playerScore != null) {
                double maxSeeds = Double.parseDouble(PropertyResolver.getProperty("settings.maxSeeds", "368.5"));
                double oppScore = maxSeeds - playerScore;
                
                String playerScoreStr = (playerScore == Math.floor(playerScore)) ? 
                    String.format("%.0f", playerScore) : String.format("%.1f", playerScore);
                String oppScoreStr = (oppScore == Math.floor(oppScore)) ? 
                    String.format("%.0f", oppScore) : String.format("%.1f", oppScore);
                    
                score = playerScoreStr + "-" + oppScoreStr;
            } else {
                // Fallback to outcome-based if score not available
                if (outcome == 1) {
                    score = "1-0";
                } else if (outcome == 0) {
                    score = "0.5-0.5";
                } else {
                    score = "0-1";
                }
            }
            
            // Create structured entry
            victoryEntries1.add(new ComparisonImageGenerator.PlayerVictoryEntry(
                VictoryRecordCalculator.getRoundDisplayName(round),
                hallEmoji,
                playerHallFormatted,
                playerEloStr,
                player1.name,
                score,
                oppName != null ? oppName : "?",
                oppEloStr,
                oppHallFormatted,
                oppEmoji
            ));
        }
        sections1.add(ComparisonImageGenerator.Section.forPlayerVictory("Victory Record", victoryEntries1));
        
        // Prepare right side data (player 2)
        List<ComparisonImageGenerator.Section> sections2 = new ArrayList<>();
        
        // Stats per round with deltas
        List<String> statsLines2 = new ArrayList<>();
        statsLines2.add(String.format("%-4s %-6s %-10s %-6s %-10s", "Rnd", "Rank", "ΔRank", "ELO", "ΔELO"));
        
        Integer prevRank2 = null;
        Integer prevElo2 = null;
        
        for (String round : roundsToInclude) {
            Integer rank = player2.rankByRound.get(round);
            Integer elo = player2.eloByRound.get(round);
            
            if (rank != null && elo != null) {
                String deltaRank = "-";
                String deltaElo = "-";
                
                if (prevRank2 != null) {
                    int rankChange = prevRank2 - rank;
                    if (rankChange > 0) {
                        deltaRank = "+" + rankChange;
                    } else if (rankChange < 0) {
                        deltaRank = "-" + Math.abs(rankChange);
                    } else {
                        deltaRank = "= ";
                    }
                } else {
                    deltaRank = "- ";
                }
                
                if (prevElo2 != null) {
                    int eloChange = elo - prevElo2;
                    if (eloChange > 0) {
                        deltaElo = "+" + eloChange;
                    } else if (eloChange < 0) {
                        deltaElo = "-" + Math.abs(eloChange);
                    } else {
                        deltaElo = "= ";
                    }
                } else {
                    deltaElo = "- ";
                }
                
                statsLines2.add(String.format("%-4s %-6d %-10s %-6d %-10s", 
                    VictoryRecordCalculator.getRoundDisplayName(round), rank, deltaRank, elo, deltaElo));
                
                prevRank2 = rank;
                prevElo2 = elo;
            }
        }
        sections2.add(new ComparisonImageGenerator.Section("Stats Per Round", statsLines2));
        
        // Seating arrangement
        List<String> seatLines2 = new ArrayList<>();
        StringBuilder seatHeader2 = new StringBuilder("Rnd: ");
        StringBuilder seatData2 = new StringBuilder("Seat:");
        for (String round : roundsToInclude) {
            seatHeader2.append(String.format("%-3s|", VictoryRecordCalculator.getRoundDisplayName(round)));
            Integer seat = player2.seatByRound.get(round);
            seatData2.append(String.format("%-3s|", seat != null ? seat : "-"));
        }
        seatLines2.add(seatHeader2.toString());
        seatLines2.add(seatData2.toString());
        sections2.add(new ComparisonImageGenerator.Section("Seating", seatLines2));
        
        // Victory record - use structured data
        List<ComparisonImageGenerator.PlayerVictoryEntry> victoryEntries2 = new ArrayList<>();
        for (String round : roundsToInclude) {
            Integer outcome = player2.outcomeByRound.get(round);
            if (outcome == null) {
                if (player2.eloByRound.containsKey(round)) {
                    victoryEntries2.add(new ComparisonImageGenerator.PlayerVictoryEntry(
                        VictoryRecordCalculator.getRoundDisplayName(round),
                        true  // isNA
                    ));
                }
                continue;
            }
            
            String oppName = player2.oppNameByRound.get(round);
            String oppHall = player2.oppHallByRound.get(round);
            
            String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(outcome);
            Integer oppOutcome = outcome == 0 ? 0 : -outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
            
            // Use 2-letter hall abbreviations
            String playerHallFormatted = TableFormatter.shortenHallName(player2.hall);
            String oppHallFormatted = oppHall != null ? TableFormatter.shortenHallName(oppHall) : "??";
            
            // Get player ELO for this round
            Integer playerElo = player2.eloByRound.get(round);
            String playerEloStr = playerElo != null ? String.valueOf(playerElo) : "?";
            
            // Get opponent ELO from the fetched data
            Integer oppElo = player2.oppEloByRound.get(round);
            String oppEloStr = oppElo != null ? String.valueOf(oppElo) : "?";
            
            // Format score - use actual score from database if available
            String score;
            Double playerScore = player2.scoreByRound.get(round);
            
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                if (playerScore != null) {
                    String scoreStr = (playerScore == Math.floor(playerScore)) ? 
                        String.format("%.0f", playerScore) : String.format("%.1f", playerScore);
                    score = scoreStr + "-0";
                } else {
                    score = "1-0";  // Fallback
                }
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
                oppEloStr = "-";  // Show dash for WALKOVER ELO
            } else if (playerScore != null) {
                double maxSeeds = Double.parseDouble(PropertyResolver.getProperty("settings.maxSeeds", "368.5"));
                double oppScore = maxSeeds - playerScore;
                
                String playerScoreStr = (playerScore == Math.floor(playerScore)) ? 
                    String.format("%.0f", playerScore) : String.format("%.1f", playerScore);
                String oppScoreStr = (oppScore == Math.floor(oppScore)) ? 
                    String.format("%.0f", oppScore) : String.format("%.1f", oppScore);
                    
                score = playerScoreStr + "-" + oppScoreStr;
            } else {
                // Fallback to outcome-based if score not available
                if (outcome == 1) {
                    score = "1-0";
                } else if (outcome == 0) {
                    score = "0.5-0.5";
                } else {
                    score = "0-1";
                }
            }
            
            // Create structured entry
            victoryEntries2.add(new ComparisonImageGenerator.PlayerVictoryEntry(
                VictoryRecordCalculator.getRoundDisplayName(round),
                hallEmoji,
                playerHallFormatted,
                playerEloStr,
                player2.name,
                score,
                oppName != null ? oppName : "?",
                oppEloStr,
                oppHallFormatted,
                oppEmoji
            ));
        }
        sections2.add(ComparisonImageGenerator.Section.forPlayerVictory("Victory Record", victoryEntries2));
        
        // Equalize section sizes
        equalizeSectionSizes(sections1, sections2);
        
        // Create proper subtitles with hall name formatting
        String subtitle1 = createSubtitle(player1.name, player1.hall);
        String subtitle2 = createSubtitle(player2.name, player2.hall);
        
        ComparisonImageGenerator.ComparisonData data1 = new ComparisonImageGenerator.ComparisonData(
            player1.name, subtitle1, sections1);
        ComparisonImageGenerator.ComparisonData data2 = new ComparisonImageGenerator.ComparisonData(
            player2.name, subtitle2, sections2);
        
        return ComparisonImageGenerator.generateComparisonImage("Player Comparison", data1, data2, metadata);
    }
    
    /**
     * Equalizes section sizes between two players by adding empty rows to sections with fewer rows.
     */
    private void equalizeSectionSizes(List<ComparisonImageGenerator.Section> sections1,
                                     List<ComparisonImageGenerator.Section> sections2) {
        int sectionCount = Math.min(sections1.size(), sections2.size());
        
        for (int i = 0; i < sectionCount; i++) {
            ComparisonImageGenerator.Section s1 = sections1.get(i);
            ComparisonImageGenerator.Section s2 = sections2.get(i);
            
            // Get size based on what type of data the section contains
            int size1 = getSectionSize(s1);
            int size2 = getSectionSize(s2);
            
            if (size1 < size2) {
                addEmptyRows(s1, size2 - size1);
            } else if (size2 < size1) {
                addEmptyRows(s2, size1 - size2);
            }
        }
    }
    
    private int getSectionSize(ComparisonImageGenerator.Section section) {
        if (section.hallVictoryEntries != null) {
            return section.hallVictoryEntries.size();
        } else if (section.playerVictoryEntries != null) {
            return section.playerVictoryEntries.size();
        } else if (section.lines != null) {
            return section.lines.size();
        }
        return 0;
    }
    
    private void addEmptyRows(ComparisonImageGenerator.Section section, int count) {
        if (section.hallVictoryEntries != null) {
            for (int j = 0; j < count; j++) {
                section.hallVictoryEntries.add(new ComparisonImageGenerator.HallVictoryEntry("", true));
            }
        } else if (section.playerVictoryEntries != null) {
            for (int j = 0; j < count; j++) {
                section.playerVictoryEntries.add(new ComparisonImageGenerator.PlayerVictoryEntry("", true));
            }
        } else if (section.lines != null) {
            for (int j = 0; j < count; j++) {
                section.lines.add("");
            }
        }
    }
}
