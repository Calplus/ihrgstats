package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Command handler for /infomatchhall command.
 * Shows detailed match information for a specific hall in a specific round.
 * Displays 3 tables: Player ELO stats, Seating arrangement, and Match details.
 */
public class CommandInfoMatchHall {
    private final LogHelper logHelper;
    private final String dbPath;
    
    // State management for multi-step selection
    private static final Map<String, MatchHallSelectionState> userSelectionStates = new HashMap<>();
    
    private static class MatchHallSelectionState extends SelectionState {
        String selectedHall;
        String selectedRound;
    }
    
    public CommandInfoMatchHall() {
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
     * Handles the /infomatchhall command (initial call)
     */
    public InfoResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("User %s requested /infomatchhall command", userId));
        
        // Clear existing state and cleanup old states
        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.put(userId, new MatchHallSelectionState());
        
        // Fetch available halls
        List<String> halls = HallUtils.fetchAndSortAvailableHalls(dbPath);
        
        if (halls.isEmpty()) {
            String errorMsg = "ℹ️ No halls found in database. Please upload round data first.";
            logHelper.logWarning("No halls available for match info");
            return new InfoResponse(errorMsg, (Path) null, null);
        }
        
        // Create button layout (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String hall : halls) {
            labels.add(hall);
            callbacks.add("infomatchhall_hall_" + hall);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("infomatchhall_cancel");
        
        String message = "**🏛️ Hall Match Information**\n\n" +
                        "Select the **hall**:";
        
        return new InfoResponse(message, (Path) null, 
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles hall selection
     */
    public InfoResponse handleHallSelection(String userId, String hall) {
        logHelper.logInfo(String.format("User %s selected hall: %s", userId, hall));
        
        // Store state
        MatchHallSelectionState state = userSelectionStates.get(userId);
        if (state == null) state = new MatchHallSelectionState();
        state.selectedHall = hall;
        userSelectionStates.put(userId, state);
        
        // Get available rounds
        List<String> availableRounds = RoundDetector.getAvailableRounds(dbPath);
        
        if (availableRounds.isEmpty()) {
            String errorMsg = "ℹ️ No round data available in database.";
            userSelectionStates.remove(userId);
            return new InfoResponse(errorMsg, (Path) null, null);
        }
        
        // Create round selection buttons (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String round : availableRounds) {
            String displayName = VictoryRecordCalculator.getRoundDisplayName(round);
            labels.add(displayName);
            callbacks.add("infomatchhall_round_" + round);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("infomatchhall_cancel");
        
        String message = String.format("**🏛️ Hall Match Information**\n\n" +
                                      "Hall: **%s**\n" +
                                      "Select the **round**:", hall);
        
        return new InfoResponse(message, (Path) null,
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles round selection and generates the match hall info
     */
    public InfoResponse handleRoundSelection(String userId, String round) {
        MatchHallSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.selectedHall == null) {
            String errorMsg = "❌ Session expired. Please use /infomatchhall to start again.";
            return new InfoResponse(errorMsg, (Path) null, null);
        }
        
        state.selectedRound = round;
        logHelper.logInfo(String.format("%s selected round: %s for hall: %s", com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId), round, state.selectedHall));
        
        try {
            InfoResponse response = generateMatchHallInfo(state.selectedHall, state.selectedRound);
            userSelectionStates.remove(userId);  // Clean up state after successful generation
            return response;
        } catch (Exception e) {
            String errorMsg = "❌ Error generating match hall information: " + e.getMessage();
            logHelper.logError(errorMsg);
            e.printStackTrace();
            userSelectionStates.remove(userId);
            return new InfoResponse(errorMsg, (Path) null, null);
        }
    }
    
    /**
     * Handles cancellation
     */
    public InfoResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new InfoResponse("ℹ️ Hall match information request cancelled.", (Path) null, null);
    }
    
    /**
     * Player data for this specific hall and round
     */
    private static class HallPlayerData {
        String name;
        String hall;
        Integer seat;
        Integer currentElo;
        Integer prevElo;
        Integer currentRank;
        Integer prevRank;
        Integer outcome;
        String oppName;
        String oppHall;
        Integer oppElo;
        Double score;
        
        HallPlayerData(String name, String hall) {
            this.name = name;
            this.hall = hall;
        }
    }
    
    /**
     * Generates complete match hall information
     */
    private InfoResponse generateMatchHallInfo(String hall, String round) throws Exception {
        // Fetch all players from this hall who played this round
        List<HallPlayerData> players = fetchHallPlayersForRound(hall, round);
        
        if (players.isEmpty()) {
            String errorMsg = String.format("ℹ️ No players from %s found in round %s.", 
                hall, VictoryRecordCalculator.getRoundDisplayName(round));
            return new InfoResponse(errorMsg, (Path) null, null);
        }
        
        // Sort by seat number for display
        players.sort(Comparator.comparingInt(p -> p.seat != null ? p.seat : 999));
        
        // Generate text output
        String textOutput = generateTextOutput(hall, round, players);
        
        // Generate image
        Path imagePath = generateImage(hall, round, players);
        
        logHelper.logSuccess(String.format("Generated hall match info: %s, round %s", hall, round));
        
        return new InfoResponse(textOutput, imagePath, null);
    }
    
    /**
     * Fetches all players from the specified hall for the specified round
     */
    private List<HallPlayerData> fetchHallPlayersForRound(String hall, String round) throws SQLException {
        List<HallPlayerData> players = new ArrayList<>();
        String roundSuffix = RoundUtils.getRoundColumnSuffix(round);
        
        // Get round index to determine previous round
        int roundIndex = Constants.ROUND_SEQUENCE.indexOf(round);
        String prevRoundSuffix = null;
        if (roundIndex > 0) {
            prevRoundSuffix = RoundUtils.getRoundColumnSuffix(Constants.ROUND_SEQUENCE.get(roundIndex - 1));
        }
        
        try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
            // Build column list
            List<String> columns = new ArrayList<>();
            columns.add("name");
            columns.add("hall");
            columns.add("trueElo" + roundSuffix);
            columns.add("seat" + roundSuffix);
            columns.add("outcome" + roundSuffix);
            columns.add("oppName" + roundSuffix);
            columns.add("oppHall" + roundSuffix);
            columns.add("score" + roundSuffix);
            columns.add("baseTrueElo");  // For round 1 prevElo
            
            // Add previous round columns if not round 1
            if (prevRoundSuffix != null) {
                columns.add("trueElo" + prevRoundSuffix);
            }
            
            String sql = "SELECT " + String.join(", ", columns) + 
                        " FROM A1_PlayerStats WHERE hall = ? AND trueElo" + roundSuffix + " IS NOT NULL";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, hall);
                ResultSet rs = pstmt.executeQuery();
                
                while (rs.next()) {
                    String playerName = rs.getString("name");
                    HallPlayerData player = new HallPlayerData(playerName, hall);
                    
                    // Current round data
                    player.currentElo = (Integer) rs.getObject("trueElo" + roundSuffix);
                    player.seat = (Integer) rs.getObject("seat" + roundSuffix);
                    player.outcome = (Integer) rs.getObject("outcome" + roundSuffix);
                    player.oppName = rs.getString("oppName" + roundSuffix);
                    player.oppHall = rs.getString("oppHall" + roundSuffix);
                    player.score = (Double) rs.getObject("score" + roundSuffix);
                    
                    // Previous ELO logic (same as A1_PlayerStats)
                    if (prevRoundSuffix != null) {
                        player.prevElo = (Integer) rs.getObject("trueElo" + prevRoundSuffix);
                    }
                    if (player.prevElo == null) {
                        Integer baseElo = (Integer) rs.getObject("baseTrueElo");
                        player.prevElo = baseElo != null ? baseElo : 1000;
                    }
                    
                    // Calculate ranks
                    if (player.currentElo != null) {
                        player.currentRank = calculateRankForRound(conn, round, player.currentElo);
                    }
                    
                    // Calculate previous rank if we have previous round
                    if (player.prevElo != null && prevRoundSuffix != null) {
                        String prevRound = Constants.ROUND_SEQUENCE.get(roundIndex - 1);
                        player.prevRank = calculateRankForRound(conn, prevRound, player.prevElo);
                    } else if (player.prevElo != null) {
                        // For round 1, there's no previous rank
                        player.prevRank = null;
                    }
                    
                    // Fetch opponent ELO
                    if (player.oppName != null && player.oppHall != null && !player.oppName.equalsIgnoreCase("WALKOVER")) {
                        player.oppElo = fetchOpponentElo(conn, player.oppName, player.oppHall, roundSuffix);
                    }
                    
                    players.add(player);
                }
            }
        }
        
        return players;
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
     * Calculates rank for a player in a specific round based on their ELO
     * (Same logic as CommandInfoPlayer.calculateRankForRound())
     */
    private int calculateRankForRound(Connection conn, String round, int playerElo) throws SQLException {
        int currentRoundIndex = Constants.ROUND_SEQUENCE.indexOf(round);
        if (currentRoundIndex == -1) {
            return 0;
        }
        
        List<String> roundSuffixes = new ArrayList<>();
        for (int i = 0; i <= currentRoundIndex; i++) {
            String suffix = RoundUtils.getRoundColumnSuffix(Constants.ROUND_SEQUENCE.get(i));
            if (suffix != null && !suffix.isEmpty()) {
                roundSuffixes.add(suffix);
            }
        }
        
        if (roundSuffixes.isEmpty()) {
            return 0;
        }
        
        StringBuilder whereClause = new StringBuilder("(");
        for (int i = 0; i < roundSuffixes.size(); i++) {
            if (i > 0) whereClause.append(" OR ");
            whereClause.append("trueElo").append(roundSuffixes.get(i)).append(" IS NOT NULL");
        }
        whereClause.append(")");
        
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
        
        String sql = "SELECT COUNT(*) as rank FROM A1_PlayerStats " +
                    "WHERE " + whereClause.toString() + " AND " + eloExpr + " > ? AND active = 1";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, playerElo);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("rank") + 1;
            }
        }
        return 0;
    }
    
    /**
     * Determines the opponent hall for the match
     * Returns the first non-null opponent hall found, or "WALKOVER" if all are walkovers
     */
    private String getOpponentHall(List<HallPlayerData> players) {
        for (HallPlayerData player : players) {
            if (player.oppHall != null && !player.oppHall.equalsIgnoreCase("WALKOVER")) {
                return player.oppHall;
            }
        }
        // If all opponents are walkovers or null, return "WALKOVER"
        for (HallPlayerData player : players) {
            if (player.oppHall != null) {
                return "WALKOVER";
            }
        }
        return "WALKOVER";
    }
    
    /**
     * Calculates the match score for the current hall
     * Returns a formatted string like "4-1" or "3.5-1.5"
     * +1 for a win, +0.5 for a draw
     */
    private String calculateMatchScore(List<HallPlayerData> players) {
        double hallScore = 0.0;
        double oppScore = 0.0;
        
        for (HallPlayerData player : players) {
            if (player.outcome != null) {
                if (player.outcome == 1) {  // Win
                    hallScore += 1.0;
                } else if (player.outcome == 0) {  // Draw
                    hallScore += 0.5;
                    oppScore += 0.5;
                } else if (player.outcome == -1) {  // Loss
                    oppScore += 1.0;
                }
            }
        }
        
        // Format scores: if they're whole numbers, show without decimals
        String hallScoreStr = (hallScore % 1 == 0) ? String.format("%.0f", hallScore) : String.format("%.1f", hallScore);
        String oppScoreStr = (oppScore % 1 == 0) ? String.format("%.0f", oppScore) : String.format("%.1f", oppScore);
        
        return hallScoreStr + "-" + oppScoreStr;
    }
    
    /**
     * Generates text output with 3 tables
     */
    private String generateTextOutput(String hall, String round, List<HallPlayerData> players) {
        StringBuilder sb = new StringBuilder();
        
        // Get opponent hall and match score
        String opponentHall = getOpponentHall(players);
        String matchScore = calculateMatchScore(players);
        
        sb.append("**🏛️ Hall Match Information**\n\n");
        sb.append(String.format("**Hall:** %s vs %s\n", hall, opponentHall));
        sb.append(String.format("**Round:** %s\n", VictoryRecordCalculator.getRoundDisplayName(round)));
        sb.append(String.format("**Score:** %s\n\n", matchScore));
        
        // TABLE 1: Player ELO Stats
        sb.append("**📊 Player ELO Stats:**\n```\n");
        sb.append(String.format("%-18s %-6s %-10s %-6s %-10s\n", "Name", "Rank", "ΔRank", "ELO", "ΔELO"));
        sb.append(String.format("%-18s %-6s %-10s %-6s %-10s\n", "------------------", "------", "----------", "------", "----------"));
        
        for (HallPlayerData player : players) {
            if (player.currentRank == null || player.currentElo == null) continue;
            
            String deltaRank = "-";
            String deltaElo = "-";
            
            if (player.prevRank != null) {
                int rankChange = player.prevRank - player.currentRank;
                if (rankChange > 0) {
                    deltaRank = "+" + rankChange;
                } else if (rankChange < 0) {
                    deltaRank = "-" + Math.abs(rankChange);
                } else {
                    deltaRank = "=";
                }
            }
            
            if (player.prevElo != null) {
                int eloChange = player.currentElo - player.prevElo;
                if (eloChange > 0) {
                    deltaElo = "+" + eloChange;
                } else if (eloChange < 0) {
                    deltaElo = "-" + Math.abs(eloChange);
                } else {
                    deltaElo = "=";
                }
            }
            
            sb.append(String.format("%-18s %-6d %-10s %-6d %-10s\n", 
                player.name, player.currentRank, deltaRank, player.currentElo, deltaElo));
        }
        sb.append("```\n\n");
        
        // TABLE 2: Seating
        sb.append("**🪑 Seating:**\n```\n");
        sb.append(String.format("%-6s %-18s\n", "Seat", "Name"));
        sb.append(String.format("%-6s %-18s\n", "------", "------------------"));
        
        for (HallPlayerData player : players) {
            if (player.seat != null) {
                sb.append(String.format("%-6d %-18s\n", player.seat, player.name));
            }
        }
        sb.append("```\n\n");
        
        // TABLE 3: Match Details (Victory Record style, but with seating number instead of round)
        sb.append("**🏆 Match Details:**\n```\n");
        
        for (HallPlayerData player : players) {
            if (player.outcome == null) {
                if (player.seat != null) {
                    sb.append(String.format("%-3d  -NA-\n", player.seat));
                }
                continue;
            }
            
            String oppName = player.oppName;
            String oppHall = player.oppHall;
            
            // Get ELO values
            String playerEloStr = player.currentElo != null ? String.valueOf(player.currentElo) : "?";
            String oppEloStr = player.oppElo != null ? String.valueOf(player.oppElo) : "?";
            
            // Get emojis
            String emoji = VictoryRecordCalculator.getOutcomeEmoji(player.outcome);
            Integer oppOutcome = player.outcome == 0 ? 0 : -player.outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
            
            // Format hall names
            String playerHallFormatted = TableFormatter.shortenHallName(player.hall);
            String oppHallFormatted;
            
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                oppHallFormatted = "";
                oppEloStr = "-";
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
            } else if (oppHall != null) {
                oppHallFormatted = TableFormatter.shortenHallName(oppHall);
            } else {
                oppHallFormatted = "??";
            }
            
            // Format score
            String score;
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                if (player.score != null) {
                    String scoreStr = (player.score == Math.floor(player.score)) ? 
                        String.format("%.0f", player.score) : String.format("%.1f", player.score);
                    score = scoreStr + "-0";
                } else {
                    score = "1-0";
                }
            } else if (player.score != null) {
                double maxSeeds;
                try {
                    maxSeeds = Double.parseDouble(PropertyResolver.getProperty("settings.maxSeeds", "368.5"));
                } catch (NumberFormatException e) {
                    maxSeeds = 368.5;
                }
                double oppScore = maxSeeds - player.score;
                
                String playerScoreStr = (player.score == Math.floor(player.score)) ? 
                    String.format("%.0f", player.score) : String.format("%.1f", player.score);
                String oppScoreStr = (oppScore == Math.floor(oppScore)) ? 
                    String.format("%.0f", oppScore) : String.format("%.1f", oppScore);
                    
                score = playerScoreStr + "-" + oppScoreStr;
            } else {
                if (player.outcome == 1) {
                    score = "1-0";
                } else if (player.outcome == 0) {
                    score = "0.5-0.5";
                } else {
                    score = "0-1";
                }
            }
            
            // Build line: Seat emoji hallAbbr elo playerName score oppName elo hallAbbr emoji
            String line = String.format("%-3d %s %-2s %-4s %-16s %s %-16s %-4s %-2s %s",
                player.seat != null ? player.seat : 0,
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
     * Generates image with all 3 tables
     */
    private Path generateImage(String hall, String round, List<HallPlayerData> players) throws IOException {
        String roundDisplayName = VictoryRecordCalculator.getRoundDisplayName(round);
        
        // Get opponent hall and match score
        String opponentHall = getOpponentHall(players);
        String matchScore = calculateMatchScore(players);
        
        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Hall Match Information";
        metadata.subtitle = String.format("%s vs %s - %s", hall, opponentHall, roundDisplayName);
        metadata.description = String.format("Score: %s", matchScore);
        metadata.lastRound = roundDisplayName;
        
        // Set second hall identifier for dual icon display
        // Use "unknown" for WALKOVER cases
        metadata.secondHallIdentifier = opponentHall.equalsIgnoreCase("WALKOVER") ? "unknown" : opponentHall;
        
        List<InfoImageGenerator.Section> sections = new ArrayList<>();
        
        // SECTION 1: Player ELO Stats
        InfoImageGenerator.Section eloSection = new InfoImageGenerator.Section("Player ELO Stats");
        eloSection.addMonospacedRow(String.format("%-18s %-6s %-10s %-6s %-10s", "Name", "Rank", "ΔRank", "ELO", "ΔELO"));
        
        for (HallPlayerData player : players) {
            if (player.currentRank == null || player.currentElo == null) continue;
            
            String deltaRank = "-";
            String deltaElo = "-";
            
            if (player.prevRank != null) {
                int rankChange = player.prevRank - player.currentRank;
                if (rankChange > 0) {
                    deltaRank = "+" + rankChange;
                } else if (rankChange < 0) {
                    deltaRank = "-" + Math.abs(rankChange);
                } else {
                    deltaRank = "=";
                }
            }
            
            if (player.prevElo != null) {
                int eloChange = player.currentElo - player.prevElo;
                if (eloChange > 0) {
                    deltaElo = "+" + eloChange;
                } else if (eloChange < 0) {
                    deltaElo = "-" + Math.abs(eloChange);
                } else {
                    deltaElo = "=";
                }
            }
            
            eloSection.addMonospacedRow(String.format("%-18s %-6d %-10s %-6d %-10s", 
                player.name, player.currentRank, deltaRank, player.currentElo, deltaElo));
        }
        sections.add(eloSection);
        
        // SECTION 2: Seating
        InfoImageGenerator.Section seatSection = new InfoImageGenerator.Section("Seating");
        seatSection.addMonospacedRow(String.format("%-6s %-18s", "Seat", "Name"));
        
        for (HallPlayerData player : players) {
            if (player.seat != null) {
                seatSection.addMonospacedRow(String.format("%-6d %-18s", player.seat, player.name));
            }
        }
        sections.add(seatSection);
        
        // SECTION 3: Match Details (victory record style)
        InfoImageGenerator.Section matchSection = new InfoImageGenerator.Section("Match Details");
        
        for (HallPlayerData player : players) {
            if (player.outcome == null) {
                if (player.seat != null) {
                    InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
                    entry.round = String.valueOf(player.seat);
                    entry.isNA = true;
                    matchSection.addVictoryEntry(entry);
                }
                continue;
            }
            
            String oppName = player.oppName;
            String oppHall = player.oppHall;
            
            String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(player.outcome);
            Integer oppOutcome = player.outcome == 0 ? 0 : -player.outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
            
            String playerHallFormatted = TableFormatter.shortenHallName(player.hall);
            String oppHallFormatted = oppHall != null ? TableFormatter.shortenHallName(oppHall) : "??";
            
            String playerEloStr = player.currentElo != null ? String.valueOf(player.currentElo) : "?";
            String oppEloStr = player.oppElo != null ? String.valueOf(player.oppElo) : "?";
            
            // Format score
            String score;
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                if (player.score != null) {
                    String scoreStr = (player.score == Math.floor(player.score)) ? 
                        String.format("%.0f", player.score) : String.format("%.1f", player.score);
                    score = scoreStr + "-0";
                } else {
                    score = "1-0";
                }
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
                oppEloStr = "-";
            } else if (player.score != null) {
                double maxSeeds = Double.parseDouble(PropertyResolver.getProperty("settings.maxSeeds", "368.5"));
                double oppScore = maxSeeds - player.score;
                
                String playerScoreStr = (player.score == Math.floor(player.score)) ? 
                    String.format("%.0f", player.score) : String.format("%.1f", player.score);
                String oppScoreStr = (oppScore == Math.floor(oppScore)) ? 
                    String.format("%.0f", oppScore) : String.format("%.1f", oppScore);
                    
                score = playerScoreStr + "-" + oppScoreStr;
            } else {
                if (player.outcome == 1) {
                    score = "1-0";
                } else if (player.outcome == 0) {
                    score = "0.5-0.5";
                } else {
                    score = "0-1";
                }
            }
            
            InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
            entry.round = player.seat != null ? String.valueOf(player.seat) : "?";
            entry.hallEmoji = hallEmoji;
            entry.hallOutcome = player.outcome;
            entry.playerHall = playerHallFormatted;
            entry.playerElo = playerEloStr;
            entry.playerName = player.name;
            entry.score = score;
            entry.opponentName = oppName != null ? oppName : "?";
            entry.opponentElo = oppEloStr;
            entry.opponentHall = oppHallFormatted;
            entry.oppEmoji = oppEmoji;
            entry.oppOutcome = oppOutcome;
            entry.isNA = false;
            matchSection.addVictoryEntry(entry);
        }
        sections.add(matchSection);
        
        // Generate image
        String fileName = String.format("%s_%s", hall, roundDisplayName);
        return InfoImageGenerator.generateInfoImage(metadata, sections, hall, "InfoMatchHall", fileName);
    }
}
