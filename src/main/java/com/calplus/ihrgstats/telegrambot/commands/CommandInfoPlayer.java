package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Command handler for /infoplayer command.
 * Shows detailed information for a single player (exact same as ComparePlayers but for one player).
 */
public class CommandInfoPlayer {
    private final LogHelper logHelper;
    private final String dbPath;
    
    // State management for multi-step selection
    private static final Map<String, PlayerInfoSelectionState> userSelectionStates = new HashMap<>();
    
    private static class PlayerInfoSelectionState extends SelectionState {
        String playerHall;
        String playerName;
        String selectedRound;  // "all" or specific round
    }
    
    public CommandInfoPlayer() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.logHelper = new LogHelper();
        this.dbPath = DatabaseHelper.getDefaultDatabasePathString();
    }
    
    /**
     * Response class
     */
    public static class InfoResponse extends CommandResponse {
        public InfoResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            super(message, imagePath, buttonConfig);
        }
    }
    
    /**
     * Handles the /infoplayer command (initial call)
     */
    public InfoResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("User %s requested /infoplayer command", userId));
        
        // Clear existing state and cleanup old states
        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.put(userId, new PlayerInfoSelectionState());
        
        // Fetch available halls
        List<String> halls = HallUtils.fetchAndSortAvailableHalls(dbPath);
        
        if (halls.isEmpty()) {
            String errorMsg = "ℹ️ No halls found in database. Please upload round data first.";
            logHelper.logWarning("No halls available for player info");
            return new InfoResponse(errorMsg, (Path) null, null);
        }
        
        // Create button layout (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String hall : halls) {
            labels.add(hall);
            callbacks.add("infoplayer_hall_" + hall);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("infoplayer_cancel");
        
        String message = "**👤 Player Information**\n\n" +
                        "Select the **player's hall**:";
        
        return new InfoResponse(message, (Path) null, 
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles hall selection
     */
    public InfoResponse handleHallSelection(String userId, String hall) {
        logHelper.logInfo(String.format("User %s selected hall: %s", userId, hall));
        
        // Store state
        PlayerInfoSelectionState state = userSelectionStates.get(userId);
        if (state == null) state = new PlayerInfoSelectionState();
        state.playerHall = hall;
        userSelectionStates.put(userId, state);
        
        // Fetch players from the hall
        List<String> players = fetchPlayersFromHall(hall);
        
        if (players.isEmpty()) {
            String errorMsg = String.format("ℹ️ No active players found in %s.", hall);
            userSelectionStates.remove(userId);
            return new InfoResponse(errorMsg, (Path) null, null);
        }
        
        // Create button layout (1 per row for players, like CommandComparePlayers)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String player : players) {
            labels.add(player);
            callbacks.add("infoplayer_player_" + player);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("infoplayer_cancel");
        
        String message = String.format("**👤 Player Information**\n\n" +
                                      "Hall: **%s**\n" +
                                      "Select the **player**:", hall);
        
        return new InfoResponse(message, (Path) null,
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]), 1));
    }
    
    /**
     * Handles player selection
     */
    public InfoResponse handlePlayerSelection(String userId, String player) {
        PlayerInfoSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.playerHall == null) {
            String errorMsg = "❌ Session expired. Please use /infoplayer to start again.";
            return new InfoResponse(errorMsg, (Path) null, null);
        }
        
        state.playerName = player;
        
        logHelper.logInfo(String.format("User %s selected player: %s", userId, player));
        
        // Get available rounds
        List<String> availableRounds = getAvailableRounds();
        
        if (availableRounds.isEmpty()) {
            String errorMsg = "ℹ️ No round data available.";
            userSelectionStates.remove(userId);
            return new InfoResponse(errorMsg, (Path) null, null);
        }
        
        // Create round selection buttons (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        // Add "All" option first
        labels.add("All Rounds");
        callbacks.add("infoplayer_round_all");
        
        // Add individual rounds
        for (String round : availableRounds) {
            labels.add(VictoryRecordCalculator.getRoundDisplayName(round));
            callbacks.add("infoplayer_round_" + round);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("infoplayer_cancel");
        
        String message = String.format("**👤 Player Information**\n\n" +
                                      "Player: **%s** (%s)\n\n" +
                                      "Select rounds to display:",
                                      player, state.playerHall);
        
        return new InfoResponse(message, (Path) null,
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles round selection and generates player info
     */
    public InfoResponse handleRoundSelection(String userId, String selectedRound) {
        PlayerInfoSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.playerName == null || state.playerHall == null) {
            String errorMsg = "❌ Session expired. Please use /infoplayer to start again.";
            return new InfoResponse(errorMsg, (Path) null, null);
        }
        
        state.selectedRound = selectedRound;
        String playerName = state.playerName;
        String hall = state.playerHall;
        userSelectionStates.remove(userId);
        
        logHelper.logInfo(String.format("User %s requesting info for player: %s (%s) (rounds: %s)", 
            userId, playerName, hall, selectedRound));
        
        try {
            // Generate player info
            return generatePlayerInfo(playerName, hall, selectedRound);
        } catch (Exception e) {
            String errorMsg = "❌ Error generating player info: " + e.getMessage();
            logHelper.logError("Player info error: " + e.getMessage());
            e.printStackTrace();
            return new InfoResponse(errorMsg, (Path) null, null);
        }
    }
    
    /**
     * Handles cancellation
     */
    public InfoResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new InfoResponse("ℹ️ Player information request cancelled.", (Path) null, null);
    }
    
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
     * Gets available rounds from database (excluding skipped rounds)
     */
    private List<String> getAvailableRounds() {
        return RoundDetector.getAvailableRounds(dbPath);
    }
    
    /**
     * Player data container (matches CommandComparePlayers structure)
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
     * Generates complete player information
     */
    private InfoResponse generatePlayerInfo(String playerName, String hall, String selectedRound) throws Exception {
        // Get available rounds (excluding skipped rounds)
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
        PlayerData player = fetchPlayerData(playerName, hall, roundsToInclude);
        
        if (player == null) {
            throw new Exception("Player " + playerName + " not found in hall " + hall);
        }
        
        // Generate text output
        String textOutput = generateTextOutput(player, roundsToInclude);
        
        // Generate image using InfoImageGenerator for single entity
        Path imagePath = generateImage(player, roundsToInclude, selectedRound);
        
        logHelper.logSuccess(String.format("Generated player info: %s (%s) (rounds: %s)", 
            playerName, hall, selectedRound));
        
        return new InfoResponse(textOutput, imagePath, null);
    }
    
    /**
     * Fetches complete player data from database
     * Uses exact same logic as CommandComparePlayers.fetchPlayerData()
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
        String sql = "SELECT trueElo" + roundSuffix + " FROM A1_PlayerStats WHERE name = ? AND hall = ?";
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
     * Uses exact same logic as CommandComparePlayers.calculateRankForRound()
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
     * Generates text output (same format as CommandComparePlayers but for one player)
     */
    private String generateTextOutput(PlayerData player, List<String> roundsToInclude) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("**👤 Player Information**\n\n");
        sb.append(String.format("**%s** (%s)\n\n", player.name, player.hall));
        
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
                        deltaRank = "=";
                    }
                } else {
                    deltaRank = "-";
                }
                
                if (prevElo != null) {
                    int eloChange = elo - prevElo;
                    if (eloChange > 0) {
                        deltaElo = "+" + eloChange;
                    } else if (eloChange < 0) {
                        deltaElo = "-" + Math.abs(eloChange);
                    } else {
                        deltaElo = "=";
                    }
                } else {
                    deltaElo = "-";
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
            
            // Get emoji
            String emoji = VictoryRecordCalculator.getOutcomeEmoji(outcome);
            Integer oppOutcome = outcome == 0 ? 0 : -outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
            
            // Format hall names (use 2-letter abbreviation to match image)
            String playerHallFormatted = TableFormatter.shortenHallName(player.hall);
            String oppHallFormatted;
            
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                oppHallFormatted = "";
                oppEloStr = "-";  // Show dash for WALKOVER ELO
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);  // Loss for opponent
            } else if (oppHall != null) {
                oppHallFormatted = TableFormatter.shortenHallName(oppHall);
            } else {
                oppHallFormatted = "??";
            }
            
            // Format score - use actual score from database if available
            String score;
            Double playerScore = player.scoreByRound.get(round);
            
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                // For walkover, player gets their score and opponent gets 0
                if (playerScore != null) {
                    String scoreStr = (playerScore == Math.floor(playerScore)) ? 
                        String.format("%.0f", playerScore) : String.format("%.1f", playerScore);
                    score = scoreStr + "-0";
                } else {
                    score = "1-0";  // Fallback if score not available
                }
            } else if (playerScore != null) {
                // Use actual scores from database
                // Calculate opponent score (they should sum to maxSeeds)
                double maxSeeds;
                try {
                    maxSeeds = Double.parseDouble(PropertyResolver.getProperty("settings.maxSeeds", "368.5"));
                } catch (NumberFormatException e) {
                    maxSeeds = 368.5;
                }
                double oppScore = maxSeeds - playerScore;
                
                // Format scores (no decimal if whole number)
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
            
            // Build line matching image format: Rnd emoji hallAbbr elo playerName score oppName elo hallAbbr emoji
            String line = String.format("%-3s %s %-2s %-4s %-16s %s %-16s %-4s %-2s %s",
                VictoryRecordCalculator.getRoundDisplayName(round),
                emoji,
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
        sb.append("```\n");
        
        return sb.toString();
    }
    
    /**
     * Generates player information image using InfoImageGenerator
     */
    private Path generateImage(PlayerData player, List<String> roundsToInclude, String selectedRound) throws Exception {
        // Prepare metadata - use selected round or find max round from data
        String lastRoundForMetadata;
        if (selectedRound.equals("all")) {
            lastRoundForMetadata = player.lastRound;
        } else {
            lastRoundForMetadata = selectedRound;
        }
        
        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Player Information";
        metadata.subtitle = String.format("%s (Hall %s)", player.name, player.hall);
        metadata.description = "Player statistics and performance";
        metadata.lastRound = lastRoundForMetadata != null ? VictoryRecordCalculator.getRoundDisplayName(lastRoundForMetadata) : null;
        
        // Prepare sections
        List<InfoImageGenerator.Section> sections = new ArrayList<>();
        
        // Stats per round with deltas
        InfoImageGenerator.Section statsSection = new InfoImageGenerator.Section("Stats Per Round");
        statsSection.addMonospacedRow(String.format("%-4s %-6s %-10s %-6s %-10s", "Rnd", "Rank", "ΔRank", "ELO", "ΔELO"));
        
        Integer prevRank = null;
        Integer prevElo = null;
        for (String round : roundsToInclude) {
            Integer rank = player.rankByRound.get(round);
            Integer elo = player.eloByRound.get(round);
            if (rank != null && elo != null) {
                String deltaRank = "-";
                String deltaElo = "-";
                
                if (prevRank != null) {
                    int rankChange = prevRank - rank;
                    if (rankChange > 0) {
                        deltaRank = "+" + rankChange;
                    } else if (rankChange < 0) {
                        deltaRank = "-" + Math.abs(rankChange);
                    } else {
                        deltaRank = "=";
                    }
                }
                
                if (prevElo != null) {
                    int eloChange = elo - prevElo;
                    if (eloChange > 0) {
                        deltaElo = "+" + eloChange;
                    } else if (eloChange < 0) {
                        deltaElo = "-" + Math.abs(eloChange);
                    } else {
                        deltaElo = "=";
                    }
                }
                
                statsSection.addMonospacedRow(String.format("%-4s %-6d %-10s %-6d %-10s", 
                    VictoryRecordCalculator.getRoundDisplayName(round), rank, deltaRank, elo, deltaElo));
                
                prevRank = rank;
                prevElo = elo;
            }
        }
        sections.add(statsSection);
        
        // Seating arrangement
        InfoImageGenerator.Section seatSection = new InfoImageGenerator.Section("Seating");
        StringBuilder seatHeader = new StringBuilder("Rnd: ");
        StringBuilder seatData = new StringBuilder("Seat:");
        for (String round : roundsToInclude) {
            seatHeader.append(String.format("%-3s|", VictoryRecordCalculator.getRoundDisplayName(round)));
            Integer seat = player.seatByRound.get(round);
            seatData.append(String.format("%-3s|", seat != null ? seat : "-"));
        }
        seatSection.addMonospacedRow(seatHeader.toString());
        seatSection.addMonospacedRow(seatData.toString());
        sections.add(seatSection);
        
        // Victory record - use structured data
        InfoImageGenerator.Section victorySection = new InfoImageGenerator.Section("Victory Record");
        for (String round : roundsToInclude) {
            Integer outcome = player.outcomeByRound.get(round);
            if (outcome == null) {
                if (player.eloByRound.containsKey(round)) {
                    InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
                    entry.round = VictoryRecordCalculator.getRoundDisplayName(round);
                    entry.isNA = true;
                    victorySection.addVictoryEntry(entry);
                }
                continue;
            }
            
            String oppName = player.oppNameByRound.get(round);
            String oppHall = player.oppHallByRound.get(round);
            
            String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(outcome);
            Integer oppOutcome = outcome == 0 ? 0 : -outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
            
            // Use 2-letter hall abbreviations
            String playerHallFormatted = TableFormatter.shortenHallName(player.hall);
            String oppHallFormatted = oppHall != null ? TableFormatter.shortenHallName(oppHall) : "??";
            
            // Get player ELO for this round
            Integer playerElo = player.eloByRound.get(round);
            String playerEloStr = playerElo != null ? String.valueOf(playerElo) : "?";
            
            // Get opponent ELO from the fetched data
            Integer oppElo = player.oppEloByRound.get(round);
            String oppEloStr = oppElo != null ? String.valueOf(oppElo) : "?";
            
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
            
            InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
            entry.round = VictoryRecordCalculator.getRoundDisplayName(round);
            entry.hallEmoji = hallEmoji;
            entry.playerHall = playerHallFormatted;
            entry.playerElo = playerEloStr;
            entry.playerName = player.name;
            entry.score = score;
            entry.opponentName = oppName != null ? oppName : "?";
            entry.opponentElo = oppEloStr;
            entry.opponentHall = oppHallFormatted;
            entry.oppEmoji = oppEmoji;
            entry.isNA = false;
            victorySection.addVictoryEntry(entry);
        }
        sections.add(victorySection);
        
        // Generate image
        return InfoImageGenerator.generateInfoImage(metadata, sections, player.hall, "InfoPlayer", player.name);
    }
}
