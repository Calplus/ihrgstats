package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CommandInfoHall: Show information for a single hall
 */
public class CommandInfoHall {
    private final String dbPath;
    private final LogHelper logHelper;
    private static final Map<String, SelectionState> userSelectionStates = new ConcurrentHashMap<>();

    public CommandInfoHall() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.logHelper = new LogHelper();
        this.dbPath = DatabaseHelper.getDefaultDatabasePathString();
    }

    /**
     * Selection state for hall info command
     */
    private static class HallInfoSelectionState extends SelectionState {
        String hall;
        String selectedRound;
    }

    /**
     * Response container
     */
    public static class InfoResponse extends CommandResponse {
        public InfoResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            super(message, imagePath, buttonConfig);
        }
    }

    /**
     * Handles the /infohall command (initial call)
     */
    public InfoResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("User %s requested /infohall command", userId));
        
        // Clear any existing state
        userSelectionStates.put(userId, new HallInfoSelectionState());
        
        // Cleanup old states
        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        
        // Fetch available halls
        List<String> halls = HallUtils.fetchAndSortAvailableHalls(dbPath);
        
        if (halls.isEmpty()) {
            String errorMsg = "ℹ️ No halls found in database. Please upload round data first.";
            logHelper.logWarning("No halls available");
            return new InfoResponse(errorMsg, null, null);
        }
        
        // Create button layout (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String hall : halls) {
            labels.add(hall);
            callbacks.add("infohall_hall_" + hall);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("infohall_cancel");
        
        String message = "**🏛️ Hall Information**\n\n" +
                        "Select a **hall**:";
        
        return new InfoResponse(message, null, 
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }

    /**
     * Handles hall selection
     */
    public InfoResponse handleHallSelection(String userId, String hall) {
        logHelper.logInfo(String.format("User %s selected hall: %s", userId, hall));
        
        // Store state
        HallInfoSelectionState state = (HallInfoSelectionState) userSelectionStates.get(userId);
        if (state == null) state = new HallInfoSelectionState();
        state.hall = hall;
        userSelectionStates.put(userId, state);
        
        // Fetch available rounds
        List<String> availableRounds = RoundDetector.getAvailableRounds(dbPath);
        
        if (availableRounds.isEmpty()) {
            String errorMsg = "ℹ️ No rounds found in database.";
            userSelectionStates.remove(userId);
            return new InfoResponse(errorMsg, null, null);
        }
        
        // Create button layout (4 columns for rounds)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        // Add "All Rounds" option
        labels.add("📊 All Rounds");
        callbacks.add("infohall_round_all");
        
        // Add individual rounds
        for (String round : availableRounds) {
            String displayName = VictoryRecordCalculator.getRoundDisplayName(round);
            labels.add(displayName);
            callbacks.add("infohall_round_" + round);
        }
        
        // Add back and cancel buttons (should be on same row, use special handling)
        labels.add("⬅️ Back / ❌ Cancel");
        callbacks.add("infohall_back_hall");
        
        String message = String.format("**🏛️ Hall Information**\n\n" +
                                      "Hall: **%s**\n\n" +
                                      "Select a **round**:", hall);
        
        return new InfoResponse(message, null, 
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }

    /**
     * Handles round selection and generates hall info
     */
    public InfoResponse handleRoundSelection(String userId, String selectedRound) {
        logHelper.logInfo(String.format("User %s selected round: %s", userId, selectedRound));
        
        HallInfoSelectionState state = (HallInfoSelectionState) userSelectionStates.get(userId);
        if (state == null || state.hall == null) {
            return new InfoResponse("❌ Session expired. Please run /infohall again.", null, null);
        }
        
        // Handle "latest" special case
        if (selectedRound.equals("latest")) {
            List<String> availableRounds = RoundDetector.getAvailableRounds(dbPath);
            selectedRound = availableRounds.get(availableRounds.size() - 1);
        }
        
        state.selectedRound = selectedRound;
        
        try {
            InfoResponse response = generateHallInfo(state.hall, selectedRound);
            userSelectionStates.remove(userId);
            return response;
        } catch (Exception e) {
            logHelper.logError("Failed to generate hall info: " + e.getMessage());
            return new InfoResponse("❌ Error generating hall information. Please try again.", null, null);
        }
    }

    /**
     * Handles cancel button
     */
    public InfoResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new InfoResponse("❌ Hall information request cancelled.", null, null);
    }

    /**
     * Handles back button to hall selection
     */
    public InfoResponse handleBackToHall(String userId) {
        HallInfoSelectionState state = (HallInfoSelectionState) userSelectionStates.get(userId);
        if (state != null) {
            state.hall = null;
            state.selectedRound = null;
        }
        return handleCommand(userId);
    }

    /**
     * Generates complete hall info output
     */
    private InfoResponse generateHallInfo(String hallName, String selectedRound) throws Exception {
        // Get available rounds (excluding skipped rounds)
        List<String> availableRounds = RoundDetector.getAvailableRounds(dbPath);
        
        // Determine which rounds to include
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
        
        // Fetch hall data
        HallData hallData = fetchHallData(hallName, roundsToInclude);
        
        // Generate text output
        String textOutput = generateTextOutput(hallData, selectedRound);
        
        // Generate image
        Path imagePath = generateImage(hallData, selectedRound);
        
        logHelper.logSuccess(String.format("Generated hall info: %s (rounds: %s)", hallName, selectedRound));
        
        return new InfoResponse(textOutput, imagePath, null);
    }

    /**
     * Hall data container
     */
    private static class HallData {
        String hallName;
        List<PlayerData> players;
        String lastRound;
        Map<String, HallVictoryRecord> victoryRecords;
        Map<String, Double> hallEloByRound;  // Average ELO of top 5 players per round
        Map<String, Integer> hallRankByRound;  // Hall rank per round
        List<String> roundsIncluded;
        
        HallData(String hallName, List<String> roundsIncluded) {
            this.hallName = hallName;
            this.players = new ArrayList<>();
            this.victoryRecords = new HashMap<>();
            this.hallEloByRound = new HashMap<>();
            this.hallRankByRound = new HashMap<>();
            this.roundsIncluded = roundsIncluded;
        }
    }

    /**
     * Player data container
     */
    private static class PlayerData {
        String name;
        String hall;
        int rank;  // Hall rank
        int globalRank;  // Global rank across all players
        int elo;
        boolean capped;
        Map<String, Integer> eloByRound;
        Map<String, Integer> seatByRound;
        Map<String, Integer> outcomeByRound;
        Map<String, String> oppNameByRound;
        Map<String, String> oppHallByRound;
        double avgSeat;
        
        PlayerData(String name, String hall, int elo, boolean capped) {
            this.name = name;
            this.hall = hall;
            this.elo = elo;
            this.capped = capped;
            this.eloByRound = new HashMap<>();
            this.seatByRound = new HashMap<>();
            this.outcomeByRound = new HashMap<>();
            this.oppNameByRound = new HashMap<>();
            this.oppHallByRound = new HashMap<>();
        }
        
        void calculateAvgSeat(List<String> rounds) {
            List<Integer> seats = new ArrayList<>();
            for (String round : rounds) {
                Integer seat = seatByRound.get(round);
                if (seat != null) {
                    seats.add(seat);
                }
            }
            if (seats.isEmpty()) {
                avgSeat = 999;
            } else {
                avgSeat = seats.stream().mapToInt(Integer::intValue).average().orElse(999);
            }
        }
    }

    /**
     * Hall victory record for a round
     */
    private static class HallVictoryRecord {
        double hallScore;
        double oppScore;
        String oppHall;
        int outcome;
        Double oppHallElo;
        
        HallVictoryRecord(double hallScore, double oppScore, String oppHall, int outcome) {
            this.hallScore = hallScore;
            this.oppScore = oppScore;
            this.oppHall = oppHall;
            this.outcome = outcome;
            this.oppHallElo = null;
        }
    }

    /**
     * Fetches complete hall data from database
     */
    private HallData fetchHallData(String hallName, List<String> roundsToInclude) throws SQLException {
        HallData hallData = new HallData(hallName, roundsToInclude);
        
        try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
            // Build column list
            List<String> columns = new ArrayList<>();
            columns.add("name");
            columns.add("capped");
            for (String round : Constants.ROUND_SEQUENCE) {
                String suffix = RoundUtils.getRoundColumnSuffix(round);
                columns.add("trueElo" + suffix);
                columns.add("seat" + suffix);
                columns.add("outcome" + suffix);
                columns.add("oppName" + suffix);
                columns.add("oppHall" + suffix);
            }
            
            String sql = "SELECT " + String.join(", ", columns) + 
                        " FROM A1_PlayerStats WHERE hall = ? AND active = 1";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, hallName);
                ResultSet rs = pstmt.executeQuery();
                
                while (rs.next()) {
                    String playerName = rs.getString("name");
                    boolean capped = rs.getBoolean("capped");
                    
                    // Find last round played and ELO
                    Integer lastElo = null;
                    String lastRound = null;
                    
                    for (int i = Constants.ROUND_SEQUENCE.size() - 1; i >= 0; i--) {
                        String round = Constants.ROUND_SEQUENCE.get(i);
                        if (!roundsToInclude.contains(round)) continue;
                        
                        String colName = "trueElo" + RoundUtils.getRoundColumnSuffix(round);
                        Integer elo = (Integer) rs.getObject(colName);
                        if (elo != null) {
                            lastElo = elo;
                            lastRound = round;
                            if (hallData.lastRound == null || 
                                Constants.ROUND_SEQUENCE.indexOf(round) > Constants.ROUND_SEQUENCE.indexOf(hallData.lastRound)) {
                                hallData.lastRound = round;
                            }
                            break;
                        }
                    }
                    
                    if (lastElo == null) continue;
                    
                    PlayerData player = new PlayerData(playerName, hallName, lastElo, capped);
                    
                    // Load seating, outcomes, opponents, and ELOs for included rounds
                    for (String round : roundsToInclude) {
                        String suffix = RoundUtils.getRoundColumnSuffix(round);
                        Integer elo = (Integer) rs.getObject("trueElo" + suffix);
                        Integer seat = (Integer) rs.getObject("seat" + suffix);
                        Integer outcome = (Integer) rs.getObject("outcome" + suffix);
                        String oppName = rs.getString("oppName" + suffix);
                        String oppHall = rs.getString("oppHall" + suffix);
                        
                        if (elo != null) player.eloByRound.put(round, elo);
                        if (seat != null) player.seatByRound.put(round, seat);
                        if (outcome != null) player.outcomeByRound.put(round, outcome);
                        if (oppName != null) player.oppNameByRound.put(round, oppName);
                        if (oppHall != null) player.oppHallByRound.put(round, oppHall);
                    }
                    
                    // Calculate average seat
                    player.calculateAvgSeat(roundsToInclude);
                    
                    hallData.players.add(player);
                }
            }
            
            // Sort players by ELO (descending)
            hallData.players.sort((a, b) -> Integer.compare(b.elo, a.elo));
            
            // Assign hall ranks
            for (int i = 0; i < hallData.players.size(); i++) {
                hallData.players.get(i).rank = i + 1;
            }
            
            // Calculate global ranks for each player
            try (Connection rankConn = DatabaseHelper.getConnection(dbPath)) {
                for (PlayerData player : hallData.players) {
                    // Calculate global rank based on last played ELO
                    for (int i = roundsToInclude.size() - 1; i >= 0; i--) {
                        String round = roundsToInclude.get(i);
                        Integer elo = player.eloByRound.get(round);
                        if (elo != null) {
                            player.globalRank = calculateGlobalRankForRound(rankConn, round, elo);
                            break;
                        }
                    }
                }
                
                // Calculate hall ELO and rank per round
                calculateHallEloPerRound(hallData, rankConn);
                
                // Calculate victory records
                calculateHallVictoryRecords(hallData, rankConn);
            }
        }
        
        return hallData;
    }

    /**
     * Calculates hall victory records per round
     */
    private void calculateHallVictoryRecords(HallData hallData, Connection conn) throws SQLException {
        for (String round : hallData.roundsIncluded) {
            List<PlayerData> playingPlayers = hallData.players.stream()
                .filter(p -> p.seatByRound.containsKey(round))
                .collect(Collectors.toList());
            
            if (playingPlayers.isEmpty()) continue;
            
            double hallScore = 0.0;
            Map<String, Double> oppScores = new HashMap<>();
            
            for (PlayerData player : playingPlayers) {
                Integer outcome = player.outcomeByRound.get(round);
                String oppHall = player.oppHallByRound.get(round);
                String oppName = player.oppNameByRound.get(round);
                
                if (outcome == null) continue;
                
                Double points = VictoryRecordCalculator.outcomeToPoints(outcome);
                if (points != null) {
                    hallScore += points;
                    
                    if (oppHall != null && !oppHall.equals("WALKOVER") && !oppName.equals("WALKOVER")) {
                        oppScores.put(oppHall, oppScores.getOrDefault(oppHall, 0.0) + (1.0 - points));
                    }
                }
            }
            
            // Find primary opponent
            String primaryOppHall = null;
            if (!oppScores.isEmpty()) {
                primaryOppHall = oppScores.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            }
            
            // Check for WALKOVER
            boolean allWalkovers = playingPlayers.stream()
                .allMatch(p -> "WALKOVER".equalsIgnoreCase(p.oppNameByRound.get(round)));
            
            if (allWalkovers) {
                primaryOppHall = "WALKOVER";
            }
            
            if (primaryOppHall != null) {
                double oppScore = oppScores.getOrDefault(primaryOppHall, 0.0);
                int outcome = hallScore > oppScore ? 1 : (hallScore < oppScore ? -1 : 0);
                HallVictoryRecord record = new HallVictoryRecord(hallScore, oppScore, primaryOppHall, outcome);
                
                // Fetch opponent hall ELO for this round
                if (!"WALKOVER".equalsIgnoreCase(primaryOppHall)) {
                    record.oppHallElo = fetchOpponentHallElo(conn, primaryOppHall, round);
                }
                
                hallData.victoryRecords.put(round, record);
            }
        }
    }

    /**
     * Fetches the opponent hall's ELO for a specific round
     */
    private Double fetchOpponentHallElo(Connection conn, String oppHallName, String round) throws SQLException {
        String roundSuffix = RoundUtils.getRoundColumnSuffix(round);
        String sql = "SELECT trueElo" + roundSuffix + " FROM A1_PlayerStats WHERE Hall = ? AND trueElo" + roundSuffix + " IS NOT NULL ORDER BY trueElo" + roundSuffix + " DESC LIMIT 5";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, oppHallName);
            try (ResultSet rs = pstmt.executeQuery()) {
                List<Integer> elos = new ArrayList<>();
                while (rs.next()) {
                    Integer elo = (Integer) rs.getObject("trueElo" + roundSuffix);
                    if (elo != null) {
                        elos.add(elo);
                    }
                }
                
                if (!elos.isEmpty()) {
                    int sum = 0;
                    for (Integer elo : elos) {
                        sum += elo;
                    }
                    return (double) sum / elos.size();
                }
            }
        }
        return null;
    }

    /**
     * Calculates hall ELO for each round (average of top 5 players' TrueElo)
     */
    private void calculateHallEloPerRound(HallData hallData, Connection conn) throws SQLException {
        for (String round : hallData.roundsIncluded) {
            // Get all players from this hall who have played up to this round
            List<PlayerEloData> playersInRound = new ArrayList<>();
            
            for (PlayerData player : hallData.players) {
                Integer elo = player.eloByRound.get(round);
                if (elo != null) {
                    playersInRound.add(new PlayerEloData(player.name, elo));
                }
            }
            
            if (!playersInRound.isEmpty()) {
                // Sort by ELO descending
                playersInRound.sort((p1, p2) -> Integer.compare(p2.elo, p1.elo));
                
                // Take top 5 (or all if less than 5)
                int count = Math.min(5, playersInRound.size());
                int sum = 0;
                for (int i = 0; i < count; i++) {
                    sum += playersInRound.get(i).elo;
                }
                
                double avgElo = (double) sum / count;
                hallData.hallEloByRound.put(round, avgElo);
                
                // Calculate rank for this hall ELO in this round
                int rank = calculateHallRankForRound(conn, round, avgElo);
                hallData.hallRankByRound.put(round, rank);
            }
        }
    }

    /**
     * Helper class for player ELO data
     */
    private static class PlayerEloData {
        String name;
        int elo;
        
        PlayerEloData(String name, int elo) {
            this.name = name;
            this.elo = elo;
        }
    }

    /**
     * Calculates hall rank for a specific round
     */
    private int calculateHallRankForRound(Connection conn, String round, double hallElo) throws SQLException {
        String roundSuffix = RoundUtils.getRoundColumnSuffix(round);
        
        // Get all halls and their top 5 players' average ELO for this round
        String sql = "SELECT hall, trueElo" + roundSuffix + " FROM A1_PlayerStats WHERE trueElo" + roundSuffix + " IS NOT NULL AND active = 1";
        
        Map<String, List<Integer>> hallElos = new HashMap<>();
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String hall = rs.getString("hall");
                int elo = rs.getInt("trueElo" + roundSuffix);
                
                hallElos.computeIfAbsent(hall, k -> new ArrayList<>()).add(elo);
            }
        }
        
        // Calculate average ELO for each hall (top 5)
        int higherRankedHalls = 0;
        for (Map.Entry<String, List<Integer>> entry : hallElos.entrySet()) {
            List<Integer> elos = entry.getValue();
            elos.sort((a, b) -> Integer.compare(b, a));
            
            int count = Math.min(5, elos.size());
            int sum = 0;
            for (int i = 0; i < count; i++) {
                sum += elos.get(i);
            }
            
            double avgElo = (double) sum / count;
            if (avgElo > hallElo) {
                higherRankedHalls++;
            }
        }
        
        return higherRankedHalls + 1;
    }

    /**
     * Calculates global rank for a player in a specific round
     */
    private int calculateGlobalRankForRound(Connection conn, String round, int playerElo) throws SQLException {
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
        
        if (roundSuffixes.isEmpty()) {
            return 0;
        }
        
        // Build WHERE clause
        StringBuilder whereClause = new StringBuilder("(");
        for (int i = 0; i < roundSuffixes.size(); i++) {
            if (i > 0) whereClause.append(" OR ");
            whereClause.append("trueElo").append(roundSuffixes.get(i)).append(" IS NOT NULL");
        }
        whereClause.append(")");
        
        // Build expression to get latest ELO
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
        
        // Count players with higher ELO
        String sql = String.format("SELECT COUNT(*) as cnt FROM A1_PlayerStats WHERE %s AND (%s) > ? AND active = 1", whereClause, eloExpr);
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, playerElo);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("cnt") + 1;
                }
            }
        }
        
        return 0;
    }

    /**
     * Generates text output for hall info
     */
    /**
     * Formats hall name for image display
     */
    private String formatHallNameForImage(String hallName) {
        try {
            Integer num = Integer.parseInt(hallName);
            return "Hall " + num;
        } catch (NumberFormatException e) {
            // Not a pure number, add Hall suffix
            return hallName + " Hall";
        }
    }
    
    private String generateTextOutput(HallData hall, String selectedRound) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**🏛️ Hall %s Information**\n\n", hall.hallName));
        
        String roundDisplay = selectedRound.equals("all") ? "All Rounds" : VictoryRecordCalculator.getRoundDisplayName(selectedRound);
        sb.append(String.format("**Round:** %s\n", roundDisplay));
        sb.append(String.format("**Last Round:** %s\n\n", 
            hall.lastRound != null ? VictoryRecordCalculator.getRoundDisplayName(hall.lastRound) : "N/A"));
        
        // Hall Elo per round with deltas (formatted as code block table)
        sb.append("**🏛️ Hall Elo:**\n```\n");
        sb.append(String.format("%-4s %-8s %-10s %-6s %-10s\n", "Rnd", "Elo", "ΔElo", "Rank", "ΔRank"));
        sb.append(String.format("%-4s %-8s %-10s %-6s %-10s\n", "----", "--------", "----------", "------", "----------"));
        
        Double prevElo = null;
        Integer prevRank = null;
        
        for (String round : hall.roundsIncluded) {
            Double elo = hall.hallEloByRound.get(round);
            Integer rank = hall.hallRankByRound.get(round);
            
            if (elo != null && rank != null) {
                String eloStr = String.format("%.1f", elo);
                String rankStr = String.valueOf(rank);
                
                // Calculate deltas
                String eloChange = "-";
                String rankChange = "-";
                
                if (prevElo != null) {
                    double eloDiff = elo - prevElo;
                    if (eloDiff > 0) {
                        eloChange = String.format("+%.1f", eloDiff);
                    } else if (eloDiff < 0) {
                        eloChange = String.format("-%.1f", -eloDiff);
                    } else {
                        eloChange = "=";
                    }
                }
                
                if (prevRank != null) {
                    int rankDiff = prevRank - rank; // Positive = improvement (lower rank number)
                    if (rankDiff > 0) {
                        rankChange = "+" + rankDiff;
                    } else if (rankDiff < 0) {
                        rankChange = "-" + Math.abs(rankDiff);
                    } else {
                        rankChange = "=";
                    }
                }
                
                sb.append(String.format("%-4s %-8s %-10s %-6s %-10s\n",
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    eloStr, eloChange, rankStr, rankChange));
                
                prevElo = elo;
                prevRank = rank;
            } else {
                sb.append(String.format("%-4s %-8s %-10s %-6s %-10s\n",
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    "-", "-", "-", "-"));
            }
        }
        sb.append("```\n\n");
        
        // Player stats table with Hall Rank and Global Rank
        sb.append("**📋 Player Stats:**\n```\n");
        sb.append(String.format("%-8s %-8s %-6s %-7s %-20s\n", "HallRank", "GlobRank", "ELO", "Capped", "Name"));
        sb.append(String.format("%-8s %-8s %-6s %-7s %-20s\n", "--------", "--------", "------", "-------", "--------------------"));
        for (PlayerData p : hall.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            sb.append(String.format("%-8d %-8d %-6d %-7s %-20s\n", 
                p.rank, p.globalRank, p.elo, p.capped ? "Yes" : "No", name));
        }
        sb.append("```\n\n");
        
        // Seating arrangements (sorted by average seat)
        sb.append("**🪑 Seating Arrangements:**\n```\n");
        
        // Sort players by average seat
        List<PlayerData> sortedPlayers = new ArrayList<>(hall.players);
        sortedPlayers.sort((a, b) -> Double.compare(a.avgSeat, b.avgSeat));
        
        // Header row
        sb.append(String.format("%-4s %-15s: ", "Avg", "Name"));
        for (String round : hall.roundsIncluded) {
            sb.append(String.format("%-3s|", VictoryRecordCalculator.getRoundDisplayName(round)));
        }
        sb.append("\n");
        sb.append(String.format("%-4s %-15s  ", "----", "---------------"));
        for (int i = 0; i < hall.roundsIncluded.size(); i++) {
            sb.append("---|");
        }
        sb.append("\n");
        
        // Player rows
        for (PlayerData p : sortedPlayers) {
            String name = p.name.length() > 15 ? p.name.substring(0, 12) + "..." : p.name;
            String avgStr = p.avgSeat < 999 ? String.format("%.1f", p.avgSeat) : "-";
            sb.append(String.format("%-4s %-15s: ", avgStr, name));
            for (String round : hall.roundsIncluded) {
                Integer seat = p.seatByRound.get(round);
                sb.append(String.format("%-3s|", seat != null ? seat.toString() : "-"));
            }
            sb.append("\n");
        }
        sb.append("```\n\n");
        
        // Victory record (centered with round numbers)
        sb.append("**🏆 Victory Record:**\n```\n");
        sb.append(String.format("%-3s  %s\n", "Rnd", "Result"));
        sb.append(String.format("%-3s  %s\n", "---", "------"));
        for (String round : hall.roundsIncluded) {
            HallVictoryRecord record = hall.victoryRecords.get(round);
            if (record != null) {
                // Get ELO values for this round
                Double hallElo = hall.hallEloByRound.get(round);
                String hallEloStr = hallElo != null ? String.format("(%.1f)", hallElo) : "";
                String oppEloStr = record.oppHallElo != null ? String.format("(%.1f)", record.oppHallElo) : "";
                
                // Format hall names
                String formattedHall = "Hall " + hall.hallName;
                String formattedOppHall = "WALKOVER".equalsIgnoreCase(record.oppHall) ? "WALKOVER" : "Hall " + record.oppHall;
                
                // Get emojis
                String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(record.outcome);
                Integer oppOutcome = record.outcome == 0 ? 0 : -record.outcome;
                String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
                
                // Format score
                String score = String.format("%.0f-%.0f", record.hallScore, record.oppScore);
                
                // Build line with ELO in brackets
                String line = String.format("%s %s %s %s %s %s %s %s",
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    hallEmoji,
                    formattedHall,
                    hallEloStr,
                    score,
                    formattedOppHall,
                    oppEloStr,
                    oppEmoji);
                sb.append(line).append("\n");
            } else {
                sb.append(String.format("%-3s  -NA-\n", VictoryRecordCalculator.getRoundDisplayName(round)));
            }
        }
        sb.append("```");
        
        return sb.toString();
    }

    /**
     * Generates hall information image using InfoImageGenerator
     */
    private Path generateImage(HallData hall, String selectedRound) throws Exception {
        // Prepare metadata
        String lastRound = hall.lastRound != null ? VictoryRecordCalculator.getRoundDisplayName(hall.lastRound) : "N/A";
        
        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Hall Information";
        metadata.subtitle = String.format("Hall %s", hall.hallName);
        metadata.lastRound = lastRound;
        
        // Prepare sections
        List<InfoImageGenerator.Section> sections = new ArrayList<>();
        
        // Hall Elo per round (following CommandCompareHalls format)
        InfoImageGenerator.Section hallEloSection = new InfoImageGenerator.Section("Hall Elo");
        hallEloSection.addMonospacedRow(String.format("%-4s %-6s %-8s %-8s %-8s", "Rnd", "Rank", "ΔRank", "Elo", "ΔElo"));
        
        Double prevElo = null;
        Integer prevRank = null;
        Double lastKnownElo = null;
        Integer lastKnownRank = null;
        boolean hasStarted = false;
        
        for (String round : hall.roundsIncluded) {
            Double elo = hall.hallEloByRound.get(round);
            Integer rank = hall.hallRankByRound.get(round);
            
            // Mark as started once we have first data
            if (elo != null && rank != null) {
                hasStarted = true;
                lastKnownElo = elo;
                lastKnownRank = rank;
            }
            
            // Only display rounds after hall has started
            if (hasStarted) {
                // Use current values if available, otherwise last known
                Double displayElo = (elo != null) ? elo : lastKnownElo;
                Integer displayRank = (rank != null) ? rank : lastKnownRank;
                
                String eloStr = String.format("%.1f", displayElo);
                String rankStr = String.valueOf(displayRank);
                
                String eloChange;
                String rankChange;
                if (prevElo != null && elo != null) {
                    double eloDiff = elo - prevElo;
                    if (eloDiff > 0) {
                        eloChange = String.format("+%.1f", eloDiff);
                    } else if (eloDiff < 0) {
                        eloChange = String.format("-%.1f", -eloDiff);
                    } else {
                        eloChange = "= ";
                    }
                } else {
                    eloChange = "- ";
                }
                if (prevRank != null && rank != null) {
                    int rankDiff = prevRank - rank;
                    if (rankDiff > 0) {
                        rankChange = String.format("+%d", rankDiff);
                    } else if (rankDiff < 0) {
                        rankChange = String.format("-%d", -rankDiff);
                    } else {
                        rankChange = "= ";
                    }
                } else {
                    rankChange = "- ";
                }
                
                hallEloSection.addMonospacedRow(String.format("%-4s %-6s %-8s %-8s %-8s",
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    rankStr, rankChange, eloStr, eloChange));
                
                // Update prevElo/prevRank only if we have actual data for this round
                if (elo != null) {
                    prevElo = elo;
                }
                if (rank != null) {
                    prevRank = rank;
                }
            } else {
                hallEloSection.addMonospacedRow(String.format("%-4s %-6s %-8s %-8s %-8s",
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    "-", "-", "-", "-"));
            }
        }
        sections.add(hallEloSection);
        
        // Player Stats section (following CommandCompareHalls format)
        InfoImageGenerator.Section playersSection = new InfoImageGenerator.Section("Player Stats");
        playersSection.addMonospacedRow(String.format("%-8s %-8s %-6s %-7s %-20s", "HallRank", "GlobRank", "ELO", "Capped", "Name"));
        for (PlayerData p : hall.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            playersSection.addMonospacedRow(String.format("%-8s %-8s %-6s %-7s %-20s",
                String.valueOf(p.rank),
                String.valueOf(p.globalRank),
                String.valueOf(p.elo),
                p.capped ? "Yes" : "No",
                name));
        }
        sections.add(playersSection);
        
        // Seating section (sorted by average seat, following CommandCompareHalls format)
        List<PlayerData> sortedPlayers = new ArrayList<>(hall.players);
        sortedPlayers.sort((a, b) -> Double.compare(a.avgSeat, b.avgSeat));
        
        InfoImageGenerator.Section seatingSection = new InfoImageGenerator.Section("Seating");
        
        // Header row
        StringBuilder headerSb = new StringBuilder(String.format("%-4s %-15s: ", "Avg", "Name"));
        for (String round : hall.roundsIncluded) {
            headerSb.append(String.format("%-3s|", VictoryRecordCalculator.getRoundDisplayName(round)));
        }
        seatingSection.addMonospacedRow(headerSb.toString());
        
        // Player rows
        for (PlayerData p : sortedPlayers) {
            String name = p.name.length() > 15 ? p.name.substring(0, 12) + "..." : p.name;
            String avgStr = p.avgSeat < 999 ? String.format("%.1f", p.avgSeat) : "-";
            StringBuilder line = new StringBuilder(String.format("%-4s %-15s: ", avgStr, name));
            for (String round : hall.roundsIncluded) {
                Integer seat = p.seatByRound.get(round);
                line.append(String.format("%-3s|", seat != null ? seat.toString() : "-"));
            }
            seatingSection.addMonospacedRow(line.toString());
        }
        sections.add(seatingSection);
        
        // Victory record
        InfoImageGenerator.Section victorySection = new InfoImageGenerator.Section("Victory Record");
        for (String round : hall.roundsIncluded) {
            HallVictoryRecord record = hall.victoryRecords.get(round);
            if (record != null) {
                // Get hall ELO for this round
                Double hallElo = hall.hallEloByRound.get(round);
                String hallEloStr = hallElo != null ? String.format("%.1f", hallElo) : "?";
                
                // Get opponent hall ELO from the fetched data
                String oppEloStr = record.oppHallElo != null ? String.format("%.1f", record.oppHallElo) : "?";
                
                // Get emojis
                String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(record.outcome);
                Integer oppOutcome = record.outcome == 0 ? 0 : -record.outcome;
                String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
                
                // Format hall name for image
                String hallFormatted = formatHallNameForImage(hall.hallName);
                
                // Handle WALKOVER - don't format it, use as-is
                String oppHallFormatted;
                if ("WALKOVER".equalsIgnoreCase(record.oppHall)) {
                    oppHallFormatted = "WALKOVER";
                    oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
                    oppEloStr = "-";
                } else {
                    oppHallFormatted = record.oppHall != null ? formatHallNameForImage(record.oppHall) : "?";
                }
                
                // Format score - use integers if whole numbers
                String score;
                if (record.hallScore == Math.floor(record.hallScore) && record.oppScore == Math.floor(record.oppScore)) {
                    score = String.format("%d-%d", (int)record.hallScore, (int)record.oppScore);
                } else {
                    score = String.format("%.1f-%.1f", record.hallScore, record.oppScore);
                }
                
                InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
                entry.round = VictoryRecordCalculator.getRoundDisplayName(round);
                entry.hallEmoji = hallEmoji;
                entry.playerHall = hallFormatted;  // For halls, we use the hall name in the playerHall field
                entry.playerElo = hallEloStr;
                entry.playerName = "";  // Halls don't have player names
                entry.score = score;
                entry.opponentName = "";  // Halls don't have opponent player names
                entry.opponentElo = oppEloStr;
                entry.opponentHall = oppHallFormatted;
                entry.oppEmoji = oppEmoji;
                entry.isNA = false;
                victorySection.addVictoryEntry(entry);
            } else {
                InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
                entry.round = VictoryRecordCalculator.getRoundDisplayName(round);
                entry.isNA = true;
                victorySection.addVictoryEntry(entry);
            }
        }
        sections.add(victorySection);
        
        // Extract hall identifier for proper icon loading
        // If hall name is numeric (e.g., "4"), use as-is
        // If hall name has text (e.g., "Binjai"), extract it
        String hallIdentifier = hall.hallName;
        try {
            Integer.parseInt(hallIdentifier);
            // It's a number, use as-is
        } catch (NumberFormatException e) {
            // It's text, keep as-is but ensure lowercase for icon loading
            hallIdentifier = hallIdentifier.toLowerCase();
        }
        
        // Generate image
        return InfoImageGenerator.generateInfoImage(metadata, sections, hallIdentifier);
    }
}
