package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Command handler for /comparehalls command.
 * Allows comparison of two halls with detailed statistics.
 */
public class CommandCompareHalls {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final String dbPath;
    
    private static final List<String> ROUND_SEQUENCE = Arrays.asList("1", "2", "3", "4", "5", "6", "t16", "t8", "t4", "t2");
    
    // State management for multi-step selection (static so it persists across instances)
    private static final Map<String, SelectionState> userSelectionStates = new HashMap<>();
    
    private static class SelectionState {
        String firstHall;
        String secondHall;
        String selectedRound;  // "all" or specific round
        long timestamp;
        
        SelectionState() {
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    public CommandCompareHalls() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.dbPath = Paths.get(System.getProperty("user.dir"), "database", "core", "default.db").toString();
    }
    
    /**
     * Response class containing message, image, and button config
     */
    public static class CompareResponse {
        public final String message;
        public final Path imagePath;
        public final ButtonConfig buttonConfig;
        
        public CompareResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            this.message = message;
            this.imagePath = imagePath;
            this.buttonConfig = buttonConfig;
        }
    }
    
    /**
     * Button configuration for inline keyboards
     */
    public static class ButtonConfig {
        public final String[] labels;
        public final String[] callbacks;
        
        public ButtonConfig(String[] labels, String[] callbacks) {
            this.labels = labels;
            this.callbacks = callbacks;
        }
    }
    
    /**
     * Handles the /comparehalls command (initial call)
     */
    public CompareResponse handleCommand(String userId) {
        discordLog.logInfo(String.format("User %s requested /comparehalls command", userId));
        telegramLog.logInfo(String.format("User %s requested /comparehalls command", userId));
        
        // Clear any existing state
        userSelectionStates.put(userId, new SelectionState());
        
        // Fetch available halls
        List<String> halls = fetchAvailableHalls();
        
        if (halls.isEmpty()) {
            String errorMsg = "ℹ️ No halls found in database. Please upload round data first.";
            discordLog.logWarning("No halls available for comparison");
            telegramLog.logWarning("No halls available for comparison");
            return new CompareResponse(errorMsg, null, null);
        }
        
        if (halls.size() < 2) {
            String errorMsg = "ℹ️ At least 2 halls are required for comparison. Current halls: " + halls.size();
            discordLog.logWarning("Insufficient halls for comparison");
            telegramLog.logWarning("Insufficient halls for comparison");
            return new CompareResponse(errorMsg, null, null);
        }
        
        // Sort halls (numbers first, then alphabetical)
        sortHalls(halls);
        
        // Create button layout (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String hall : halls) {
            labels.add(hall);
            callbacks.add("comparehalls_select1_" + hall);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("comparehalls_cancel");
        
        String message = "**🏛️ Hall Comparison**\n\n" +
                        "Select the **first hall** to compare:";
        
        return new CompareResponse(message, null, 
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles first hall selection
     */
    public CompareResponse handleFirstHallSelection(String userId, String firstHall) {
        discordLog.logInfo(String.format("User %s selected first hall: %s", userId, firstHall));
        telegramLog.logInfo(String.format("User %s selected first hall: %s", userId, firstHall));
        
        // Store state
        SelectionState state = userSelectionStates.get(userId);
        if (state == null) state = new SelectionState();
        state.firstHall = firstHall;
        userSelectionStates.put(userId, state);
        
        // Fetch available halls (excluding first selection)
        List<String> halls = fetchAvailableHalls();
        halls.remove(firstHall);
        
        if (halls.isEmpty()) {
            String errorMsg = "ℹ️ No other halls available for comparison.";
            userSelectionStates.remove(userId);
            return new CompareResponse(errorMsg, null, null);
        }
        
        // Sort halls (numbers first, then alphabetical)
        sortHalls(halls);
        
        // Create button layout (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        for (String hall : halls) {
            labels.add(hall);
            callbacks.add("comparehalls_select2_" + hall);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("comparehalls_cancel");
        
        String message = String.format("**🏛️ Hall Comparison**\n\n" +
                                      "First hall: **%s**\n" +
                                      "Select the **second hall** to compare:", firstHall);
        
        return new CompareResponse(message, null,
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles second hall selection
     */
    public CompareResponse handleSecondHallSelection(String userId, String secondHall) {
        SelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstHall == null) {
            String errorMsg = "❌ Session expired. Please use /comparehalls to start again.";
            return new CompareResponse(errorMsg, null, null);
        }
        
        state.secondHall = secondHall;
        
        discordLog.logInfo(String.format("User %s selected second hall: %s", userId, secondHall));
        telegramLog.logInfo(String.format("User %s selected second hall: %s", userId, secondHall));
        
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
        callbacks.add("comparehalls_selectround_all");
        
        // Add individual rounds
        for (String round : availableRounds) {
            labels.add(VictoryRecordCalculator.getRoundDisplayName(round));
            callbacks.add("comparehalls_selectround_" + round);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("comparehalls_cancel");
        
        String message = String.format("**🏛️ Hall Comparison**\n\n" +
                                      "First hall: **%s**\n" +
                                      "Second hall: **%s**\n\n" +
                                      "Select rounds to compare:",
                                      state.firstHall, secondHall);
        
        return new CompareResponse(message, null,
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }
    
    /**
     * Handles round selection and generates comparison
     */
    public CompareResponse handleRoundSelection(String userId, String selectedRound) {
        SelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstHall == null || state.secondHall == null) {
            String errorMsg = "❌ Session expired. Please use /comparehalls to start again.";
            return new CompareResponse(errorMsg, null, null);
        }
        
        state.selectedRound = selectedRound;
        String firstHall = state.firstHall;
        String secondHall = state.secondHall;
        userSelectionStates.remove(userId);
        
        discordLog.logInfo(String.format("User %s comparing halls: %s vs %s (rounds: %s)", 
            userId, firstHall, secondHall, selectedRound));
        telegramLog.logInfo(String.format("User %s comparing halls: %s vs %s (rounds: %s)", 
            userId, firstHall, secondHall, selectedRound));
        
        try {
            // Generate comparison
            return generateComparison(firstHall, secondHall, selectedRound);
        } catch (Exception e) {
            String errorMsg = "❌ Error generating comparison: " + e.getMessage();
            discordLog.logError("Hall comparison error: " + e.getMessage());
            telegramLog.logError("Hall comparison error: " + e.getMessage());
            e.printStackTrace();
            return new CompareResponse(errorMsg, null, null);
        }
    }
    
    /**
     * Handles cancellation
     */
    public CompareResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new CompareResponse("ℹ️ Hall comparison cancelled.", null, null);
    }
    
    /**
     * Fetches available halls from database
     */
    private List<String> fetchAvailableHalls() {
        List<String> halls = new ArrayList<>();
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            String sql = "SELECT DISTINCT hall FROM A1_PlayerStats WHERE active = 1 ORDER BY hall";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    halls.add(rs.getString("hall"));
                }
            }
        } catch (SQLException e) {
            discordLog.logError("Database error fetching halls: " + e.getMessage());
            telegramLog.logError("Database error fetching halls: " + e.getMessage());
        }
        
        return halls;
    }
    
    /**
     * Gets available rounds from database
     */
    private List<String> getAvailableRounds() {
        Set<String> roundsSet = new HashSet<>();
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            // Check each round column for non-null values
            for (String round : ROUND_SEQUENCE) {
                String col = "trueElo" + getRoundSuffix(round);
                String sql = "SELECT COUNT(*) as cnt FROM A1_PlayerStats WHERE " + col + " IS NOT NULL AND active = 1";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next() && rs.getInt("cnt") > 0) {
                        roundsSet.add(round);
                    }
                }
            }
        } catch (SQLException e) {
            discordLog.logError("Database error fetching rounds: " + e.getMessage());
            telegramLog.logError("Database error fetching rounds: " + e.getMessage());
        }
        
        // Return in sequence order
        return ROUND_SEQUENCE.stream()
            .filter(roundsSet::contains)
            .collect(Collectors.toList());
    }
    
    /**
     * Sorts halls by numbers first (1, 2, 3...9, 10, 11), then alphabetically (HallA, HallB)
     */
    private void sortHalls(List<String> halls) {
        halls.sort((h1, h2) -> {
            Integer num1 = extractNumber(h1);
            Integer num2 = extractNumber(h2);
            
            // Both have numbers - compare numerically
            if (num1 != null && num2 != null) {
                return Integer.compare(num1, num2);
            }
            
            // Only h1 has number - h1 comes first
            if (num1 != null) {
                return -1;
            }
            
            // Only h2 has number - h2 comes first
            if (num2 != null) {
                return 1;
            }
            
            // Neither has number - compare alphabetically
            return h1.compareToIgnoreCase(h2);
        });
    }
    
    /**
     * Extracts number from hall name
     */
    private Integer extractNumber(String hall) {
        try {
            return Integer.parseInt(hall);
        } catch (NumberFormatException e) {
            StringBuilder digits = new StringBuilder();
            for (char c : hall.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                }
            }
            
            if (digits.length() > 0) {
                try {
                    return Integer.parseInt(digits.toString());
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            return null;
        }
    }
    
    /**
     * Formats hall name for image with "Hall" prefix for numbers, suffix for non-numbers
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
    
    /**
     * Generates complete comparison data
     */
    private CompareResponse generateComparison(String hall1, String hall2, String selectedRound) throws Exception {
        // Determine which rounds to include
        List<String> roundsToInclude = selectedRound.equals("all") ? 
            ROUND_SEQUENCE : ROUND_SEQUENCE.subList(0, ROUND_SEQUENCE.indexOf(selectedRound) + 1);
        
        // Fetch hall data
        HallData data1 = fetchHallData(hall1, roundsToInclude);
        HallData data2 = fetchHallData(hall2, roundsToInclude);
        
        // Calculate winning probability
        double winProbability = calculateWinningProbability(data1, data2);
        
        // Generate text output
        String textOutput = generateTextOutput(data1, data2, winProbability, selectedRound);
        
        // Generate image
        Path imagePath = generateImage(data1, data2, winProbability, selectedRound);
        
        discordLog.logSuccess(String.format("Generated comparison: %s vs %s (rounds: %s)", 
            hall1, hall2, selectedRound));
        telegramLog.logSuccess(String.format("Generated comparison: %s vs %s (rounds: %s)", 
            hall1, hall2, selectedRound));
        
        return new CompareResponse(textOutput, imagePath, null);
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
        Map<String, Integer> eloByRound;  // ELO per round for calculating global rank
        Map<String, Integer> seatByRound;
        Map<String, Integer> outcomeByRound;
        Map<String, String> oppNameByRound;
        Map<String, String> oppHallByRound;
        double avgSeat;  // Average seat number
        
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
                avgSeat = 999;  // Put players with no seats at end
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
        Double oppHallElo;  // Opponent hall ELO
        
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
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            // Build column list
            List<String> columns = new ArrayList<>();
            columns.add("name");
            columns.add("capped");
            for (String round : ROUND_SEQUENCE) {
                String suffix = getRoundSuffix(round);
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
                    
                    for (int i = ROUND_SEQUENCE.size() - 1; i >= 0; i--) {
                        String round = ROUND_SEQUENCE.get(i);
                        if (!roundsToInclude.contains(round)) continue;
                        
                        String colName = "trueElo" + getRoundSuffix(round);
                        Integer elo = (Integer) rs.getObject(colName);
                        if (elo != null) {
                            lastElo = elo;
                            lastRound = round;
                            if (hallData.lastRound == null || 
                                ROUND_SEQUENCE.indexOf(round) > ROUND_SEQUENCE.indexOf(hallData.lastRound)) {
                                hallData.lastRound = round;
                            }
                            break;
                        }
                    }
                    
                    if (lastElo == null) continue;
                    
                    PlayerData player = new PlayerData(playerName, hallName, lastElo, capped);
                    
                    // Load seating, outcomes, opponents, and ELOs for included rounds
                    for (String round : roundsToInclude) {
                        String suffix = getRoundSuffix(round);
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
            // Need to reopen connection for rank calculations
            try (Connection rankConn = DriverManager.getConnection(jdbcUrl)) {
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
     * Gets round suffix for column names
     */
    private String getRoundSuffix(String round) {
        if (round.startsWith("t")) {
            return "T" + round.substring(1).toUpperCase();
        }
        return "R" + round;
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
     * Calculates it the same way as hall ELO (average of top 5 players)
     */
    private Double fetchOpponentHallElo(Connection conn, String oppHallName, String round) throws SQLException {
        String roundSuffix = getRoundSuffix(round);
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
     * Same logic as CommandRankHalls
     */
    private void calculateHallEloPerRound(HallData hallData, Connection conn) throws SQLException {
        for (String round : hallData.roundsIncluded) {
            String roundSuffix = getRoundSuffix(round);
            
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
     * Compares this hall's ELO against all other halls' ELOs in the same round
     */
    private int calculateHallRankForRound(Connection conn, String round, double hallElo) throws SQLException {
        String roundSuffix = getRoundSuffix(round);
        
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
            elos.sort((a, b) -> Integer.compare(b, a));  // Descending
            
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
     * Uses same logic as CommandComparePlayers - ranks among ALL players who have played up to that round
     */
    private int calculateGlobalRankForRound(Connection conn, String round, int playerElo) throws SQLException {
        int currentRoundIndex = ROUND_SEQUENCE.indexOf(round);
        if (currentRoundIndex == -1) {
            return 0;
        }
        
        // Build list of round suffixes up to current round
        List<String> roundSuffixes = new ArrayList<>();
        for (int i = 0; i <= currentRoundIndex; i++) {
            String suffix = getRoundSuffix(ROUND_SEQUENCE.get(i));
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
                return rs.getInt("rank") + 1;
            }
        }
        return 0;
    }
    
    /**
     * Calculates winning probability with capped player filtering
     */
    private double calculateWinningProbability(HallData hall1, HallData hall2) {
        List<PlayerData> team1 = selectTeamWithCappedFilter(hall1.players);
        List<PlayerData> team2 = selectTeamWithCappedFilter(hall2.players);
        
        if (team1.isEmpty() || team2.isEmpty()) return 0.0;
        
        int totalPermutations = 0;
        int hall1Wins = 0;
        
        List<int[]> permutations = generatePermutations(team2.size());
        
        for (int[] perm : permutations) {
            int matchWins = 0;
            for (int i = 0; i < Math.min(team1.size(), team2.size()); i++) {
                if (team1.get(i).elo > team2.get(perm[i]).elo) {
                    matchWins++;
                }
            }
            totalPermutations++;
            if (matchWins > team2.size() / 2.0) {
                hall1Wins++;
            }
        }
        
        return totalPermutations > 0 ? (hall1Wins * 100.0 / totalPermutations) : 50.0;
    }
    
    /**
     * Selects team of 5 players with capped player filtering:
     * 1. If ≤5 players total: use all
     * 2. Take top 5 by ELO
     * 3. If >2 capped: Remove lowest capped until 2 remain
     * 4. Backfill with uncapped to reach 5
     * 5. If still <5: Add lowest capped until 5 reached
     */
    private List<PlayerData> selectTeamWithCappedFilter(List<PlayerData> allPlayers) {
        if (allPlayers.size() <= 5) {
            return new ArrayList<>(allPlayers);
        }
        
        // Take top 5 by ELO
        List<PlayerData> top5 = allPlayers.stream()
            .limit(5)
            .collect(Collectors.toList());
        
        // Count capped players in top 5
        long cappedCount = top5.stream()
            .filter(p -> p.capped)
            .count();
        
        // If 2 or fewer capped, use top 5 as-is
        if (cappedCount <= 2) {
            return top5;
        }
        
        // Need to remove excess capped players
        List<PlayerData> team = new ArrayList<>();
        
        // Add top 2 capped players by ELO
        List<PlayerData> cappedFromTop5 = top5.stream()
            .filter(p -> p.capped)
            .sorted(Comparator.comparing((PlayerData p) -> p.elo).reversed())
            .limit(2)
            .collect(Collectors.toList());
        team.addAll(cappedFromTop5);
        
        // Add all uncapped from top 5
        List<PlayerData> uncappedFromTop5 = top5.stream()
            .filter(p -> !p.capped)
            .collect(Collectors.toList());
        team.addAll(uncappedFromTop5);
        
        // If we have 5, we're done
        if (team.size() >= 5) {
            return team.stream().limit(5).collect(Collectors.toList());
        }
        
        // Backfill with uncapped players beyond top 5
        List<PlayerData> uncappedBeyondTop5 = allPlayers.stream()
            .skip(5)
            .filter(p -> !p.capped)
            .limit(5 - team.size())
            .collect(Collectors.toList());
        team.addAll(uncappedBeyondTop5);
        
        // If still not enough, add lowest capped players
        if (team.size() < 5) {
            Set<PlayerData> teamSet = new HashSet<>(team);
            List<PlayerData> remainingCapped = allPlayers.stream()
                .filter(p -> p.capped && !teamSet.contains(p))
                .sorted(Comparator.comparing(p -> p.elo))
                .limit(5 - team.size())
                .collect(Collectors.toList());
            team.addAll(remainingCapped);
        }
        
        return team;
    }
    
    private List<int[]> generatePermutations(int n) {
        List<int[]> result = new ArrayList<>();
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;
        permute(arr, 0, result);
        return result;
    }
    
    private void permute(int[] arr, int start, List<int[]> result) {
        if (start == arr.length) {
            result.add(arr.clone());
            return;
        }
        for (int i = start; i < arr.length; i++) {
            swap(arr, start, i);
            permute(arr, start + 1, result);
            swap(arr, start, i);
        }
    }
    
    private void swap(int[] arr, int i, int j) {
        int temp = arr[i];
        arr[i] = arr[j];
        arr[j] = temp;
    }
    
    /**
     * Generates text output
     */
    private String generateTextOutput(HallData hall1, HallData hall2, double winProbability, String selectedRound) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("**🏛️ Hall Comparison**\n\n");
        sb.append(String.format("**%s** vs **%s**\n\n", hall1.hallName, hall2.hallName));
        sb.append(String.format("📊 **Winning Probability:** %s has **%.1f%%** chance to win\n", 
            hall1.hallName, winProbability));
        sb.append(String.format("📅 **Rounds:** %s\n\n", selectedRound.equals("all") ? "All" : VictoryRecordCalculator.getRoundDisplayName(selectedRound)));
        
        // Hall 1 details
        sb.append(generateHallDetails(hall1));
        sb.append("\n");
        
        // Hall 2 details
        sb.append(generateHallDetails(hall2));
        
        return sb.toString();
    }
    
    /**
     * Generates details for one hall (text)
     */
    private String generateHallDetails(HallData data) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(String.format("━━━ **%s** ━━━\n\n", data.hallName));
        
        // Hall Elo per round with deltas
        sb.append("**🏛️ Hall Elo:**\n```\n");
        sb.append(String.format("%-4s %-8s %-10s %-6s %-10s\n", "Rnd", "Elo", "ΔElo", "Rank", "ΔRank"));
        sb.append(String.format("%-4s %-8s %-10s %-6s %-10s\n", "----", "--------", "----------", "------", "----------"));
        
        Double prevElo = null;
        Integer prevRank = null;
        Double lastKnownElo = null;
        Integer lastKnownRank = null;
        boolean hasStarted = false;
        
        for (String round : data.roundsIncluded) {
            Double elo = data.hallEloByRound.get(round);
            Integer rank = data.hallRankByRound.get(round);
            
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
                
                // Calculate deltas
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
                    int rankDiff = prevRank - rank;  // Positive = improved (lower rank number)
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
                
                sb.append(String.format("%-4s %-8s %-10s %-6s %-10s\n",
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    eloStr, eloChange, rankStr, rankChange));
                
                // Update prevElo/prevRank only if we have actual data for this round
                if (elo != null) {
                    prevElo = elo;
                }
                if (rank != null) {
                    prevRank = rank;
                }
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
        for (PlayerData p : data.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            sb.append(String.format("%-8d %-8d %-6d %-7s %-20s\n", 
                p.rank, p.globalRank, p.elo, p.capped ? "Yes" : "No", name));
        }
        sb.append("```\n\n");
        
        // Seating arrangements (sorted by average seat, with headers)
        sb.append("**🪑 Seating Arrangements:**\n```\n");
        
        // Sort players by average seat
        List<PlayerData> sortedPlayers = new ArrayList<>(data.players);
        sortedPlayers.sort((a, b) -> Double.compare(a.avgSeat, b.avgSeat));
        
        // Header row
        sb.append(String.format("%-4s %-15s: ", "Avg", "Name"));
        for (String round : data.roundsIncluded) {
            sb.append(String.format("%-3s|", VictoryRecordCalculator.getRoundDisplayName(round)));
        }
        sb.append("\n");
        sb.append(String.format("%-4s %-15s  ", "----", "---------------"));
        for (int i = 0; i < data.roundsIncluded.size(); i++) {
            sb.append("---|");
        }
        sb.append("\n");
        
        // Player rows
        for (PlayerData p : sortedPlayers) {
            String name = p.name.length() > 15 ? p.name.substring(0, 12) + "..." : p.name;
            String avgStr = p.avgSeat < 999 ? String.format("%.1f", p.avgSeat) : "-";
            sb.append(String.format("%-4s %-15s: ", avgStr, name));
            for (String round : data.roundsIncluded) {
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
        for (String round : data.roundsIncluded) {
            HallVictoryRecord record = data.victoryRecords.get(round);
            if (record != null) {
                // Get ELO values for this round
                Double hallElo = data.hallEloByRound.get(round);
                String hallEloStr = hallElo != null ? String.format("(%.1f)", hallElo) : "";
                String oppEloStr = record.oppHallElo != null ? String.format("(%.1f)", record.oppHallElo) : "";
                
                // Format hall names
                String formattedHall = formatHallNameForImage(data.hallName);
                String formattedOppHall = "WALKOVER".equalsIgnoreCase(record.oppHall) ? "WALKOVER" : formatHallNameForImage(record.oppHall);
                
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
        sb.append("```\n\n");
        
        return sb.toString();
    }
    
    /**
     * Generates comparison image
     */
    private Path generateImage(HallData hall1, HallData hall2, double winProbability, String selectedRound) throws Exception {
        // Prepare metadata
        String lastRound = hall1.lastRound != null ? VictoryRecordCalculator.getRoundDisplayName(hall1.lastRound) : 
                          (hall2.lastRound != null ? VictoryRecordCalculator.getRoundDisplayName(hall2.lastRound) : "N/A");
        String description = String.format("%s vs %s", hall1.hallName, hall2.hallName);
        ComparisonImageGenerator.ImageMetadata metadata = new ComparisonImageGenerator.ImageMetadata(
            "Hall Comparison", description, lastRound);
        
        // Prepare left side data
        List<ComparisonImageGenerator.Section> sections1 = new ArrayList<>();
        
        // Hall Elo per round
        List<String> hallEloLines1 = new ArrayList<>();
        hallEloLines1.add(String.format("%-4s %-6s %-8s %-8s %-8s", "Rnd", "Rank", "ΔRank", "Elo", "ΔElo"));
        
        Double prevElo1 = null;
        Integer prevRank1 = null;
        Double lastKnownElo1 = null;
        Integer lastKnownRank1 = null;
        boolean hasStarted1 = false;
        
        for (String round : hall1.roundsIncluded) {
            Double elo = hall1.hallEloByRound.get(round);
            Integer rank = hall1.hallRankByRound.get(round);
            
            // Mark as started once we have first data
            if (elo != null && rank != null) {
                hasStarted1 = true;
                lastKnownElo1 = elo;
                lastKnownRank1 = rank;
            }
            
            // Only display rounds after hall has started
            if (hasStarted1) {
                // Use current values if available, otherwise last known
                Double displayElo = (elo != null) ? elo : lastKnownElo1;
                Integer displayRank = (rank != null) ? rank : lastKnownRank1;
                
                String eloStr = String.format("%.1f", displayElo);
                String rankStr = String.valueOf(displayRank);
                
                String eloChange;
                String rankChange;
                if (prevElo1 != null && elo != null) {
                    double eloDiff = elo - prevElo1;
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
                if (prevRank1 != null && rank != null) {
                    int rankDiff = prevRank1 - rank;
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
                
                hallEloLines1.add(String.format("%-4s %-6s %-8s %-8s %-8s",
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    rankStr, rankChange, eloStr, eloChange));
                
                // Update prevElo/prevRank only if we have actual data for this round
                if (elo != null) {
                    prevElo1 = elo;
                }
                if (rank != null) {
                    prevRank1 = rank;
                }
            } else {
                hallEloLines1.add(String.format("%-4s %-6s %-8s %-8s %-8s",
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    "-", "-", "-", "-"));
            }
        }
        sections1.add(new ComparisonImageGenerator.Section("Hall Elo", hallEloLines1));
        
        // Player stats with Hall Rank and Global Rank
        List<String> statsLines1 = new ArrayList<>();
        statsLines1.add(String.format("%-8s %-8s %-6s %-7s %-20s", "HallRank", "GlobRank", "ELO", "Capped", "Name"));
        for (PlayerData p : hall1.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            statsLines1.add(String.format("%-8d %-8d %-6d %-7s %-20s", 
                p.rank, p.globalRank, p.elo, p.capped ? "Yes" : "No", name));
        }
        sections1.add(new ComparisonImageGenerator.Section("Player Stats", statsLines1));
        
        // Seating (sorted by average seat, with headers)
        List<PlayerData> sortedPlayers1 = new ArrayList<>(hall1.players);
        sortedPlayers1.sort((a, b) -> Double.compare(a.avgSeat, b.avgSeat));
        
        List<String> seatLines1 = new ArrayList<>();
        // Header
        StringBuilder headerSb = new StringBuilder(String.format("%-4s %-15s: ", "Avg", "Name"));
        for (String round : hall1.roundsIncluded) {
            headerSb.append(String.format("%-3s|", VictoryRecordCalculator.getRoundDisplayName(round)));
        }
        seatLines1.add(headerSb.toString());
        
        // Player rows
        for (PlayerData p : sortedPlayers1) {
            String name = p.name.length() > 15 ? p.name.substring(0, 12) + "..." : p.name;
            String avgStr = p.avgSeat < 999 ? String.format("%.1f", p.avgSeat) : "-";
            StringBuilder line = new StringBuilder(String.format("%-4s %-15s: ", avgStr, name));
            for (String round : hall1.roundsIncluded) {
                Integer seat = p.seatByRound.get(round);
                line.append(String.format("%-3s|", seat != null ? seat.toString() : "-"));
            }
            seatLines1.add(line.toString());
        }
        sections1.add(new ComparisonImageGenerator.Section("Seating", seatLines1));
        
        // Victory record - use structured data
        List<ComparisonImageGenerator.HallVictoryEntry> victoryEntries1 = new ArrayList<>();
        for (String round : hall1.roundsIncluded) {
            HallVictoryRecord record = hall1.victoryRecords.get(round);
            if (record != null) {
                // Get hall ELO for this round
                Double hallElo = hall1.hallEloByRound.get(round);
                String hallEloStr = hallElo != null ? String.format("%.1f", hallElo) : "?";
                
                // Get opponent hall ELO from the fetched data
                String oppEloStr = record.oppHallElo != null ? String.format("%.1f", record.oppHallElo) : "?";
                
                // Get emojis
                String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(record.outcome);
                Integer oppOutcome = record.outcome == 0 ? 0 : -record.outcome;
                String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
                
                // Format hall name for image
                String hallFormatted = formatHallNameForImage(hall1.hallName);
                
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
                
                // Create structured entry
                victoryEntries1.add(new ComparisonImageGenerator.HallVictoryEntry(
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    hallEmoji,
                    hallEloStr,
                    hallFormatted,
                    score,
                    oppHallFormatted,
                    oppEloStr,
                    oppEmoji
                ));
            } else {
                victoryEntries1.add(new ComparisonImageGenerator.HallVictoryEntry(
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    true  // isNA
                ));
            }
        }
        sections1.add(ComparisonImageGenerator.Section.forHallVictory("Victory Record", victoryEntries1));
        
        // Win probability
        sections1.add(new ComparisonImageGenerator.Section("Win Probability", 
            Arrays.asList(String.format("%.1f%%", winProbability)), true, false));
        
        // Prepare right side data (similar structure)
        List<ComparisonImageGenerator.Section> sections2 = new ArrayList<>();
        
        // Hall Elo per round
        List<String> hallEloLines2 = new ArrayList<>();
        hallEloLines2.add(String.format("%-4s %-6s %-8s %-8s %-8s", "Rnd", "Rank", "ΔRank", "Elo", "ΔElo"));
        
        Double prevElo2 = null;
        Integer prevRank2 = null;
        Double lastKnownElo2 = null;
        Integer lastKnownRank2 = null;
        boolean hasStarted2 = false;
        
        for (String round : hall2.roundsIncluded) {
            Double elo = hall2.hallEloByRound.get(round);
            Integer rank = hall2.hallRankByRound.get(round);
            
            // Mark as started once we have first data
            if (elo != null && rank != null) {
                hasStarted2 = true;
                lastKnownElo2 = elo;
                lastKnownRank2 = rank;
            }
            
            // Only display rounds after hall has started
            if (hasStarted2) {
                // Use current values if available, otherwise last known
                Double displayElo = (elo != null) ? elo : lastKnownElo2;
                Integer displayRank = (rank != null) ? rank : lastKnownRank2;
                
                String eloStr = String.format("%.1f", displayElo);
                String rankStr = String.valueOf(displayRank);
                
                String eloChange;
                String rankChange;
                if (prevElo2 != null && elo != null) {
                    double eloDiff = elo - prevElo2;
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
                if (prevRank2 != null && rank != null) {
                    int rankDiff = prevRank2 - rank;
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
                
                hallEloLines2.add(String.format("%-4s %-6s %-8s %-8s %-8s",
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    rankStr, rankChange, eloStr, eloChange));
                
                // Update prevElo/prevRank only if we have actual data for this round
                if (elo != null) {
                    prevElo2 = elo;
                }
                if (rank != null) {
                    prevRank2 = rank;
                }
            } else {
                hallEloLines2.add(String.format("%-4s %-6s %-8s %-8s %-8s",
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    "-", "-", "-", "-"));
            }
        }
        sections2.add(new ComparisonImageGenerator.Section("Hall Elo", hallEloLines2));
        
        // Player stats with Hall Rank and Global Rank
        List<String> statsLines2 = new ArrayList<>();
        statsLines2.add(String.format("%-8s %-8s %-6s %-7s %-20s", "HallRank", "GlobRank", "ELO", "Capped", "Name"));
        for (PlayerData p : hall2.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            statsLines2.add(String.format("%-8d %-8d %-6d %-7s %-20s",
                p.rank, p.globalRank, p.elo, p.capped ? "Yes" : "No", name));
        }
        sections2.add(new ComparisonImageGenerator.Section("Player Stats", statsLines2));
        
        // Seating (sorted)
        List<PlayerData> sortedPlayers2 = new ArrayList<>(hall2.players);
        sortedPlayers2.sort((a, b) -> Double.compare(a.avgSeat, b.avgSeat));
        
        List<String> seatLines2 = new ArrayList<>();
        // Header
        StringBuilder headerSb2 = new StringBuilder(String.format("%-4s %-15s: ", "Avg", "Name"));
        for (String round : hall2.roundsIncluded) {
            headerSb2.append(String.format("%-3s|", VictoryRecordCalculator.getRoundDisplayName(round)));
        }
        seatLines2.add(headerSb2.toString());
        
        // Player rows
        for (PlayerData p : sortedPlayers2) {
            String name = p.name.length() > 15 ? p.name.substring(0, 12) + "..." : p.name;
            String avgStr = p.avgSeat < 999 ? String.format("%.1f", p.avgSeat) : "-";
            StringBuilder line = new StringBuilder(String.format("%-4s %-15s: ", avgStr, name));
            for (String round : hall2.roundsIncluded) {
                Integer seat = p.seatByRound.get(round);
                line.append(String.format("%-3s|", seat != null ? seat.toString() : "-"));
            }
            seatLines2.add(line.toString());
        }
        sections2.add(new ComparisonImageGenerator.Section("Seating", seatLines2));
        
        // Victory record - use structured data
        List<ComparisonImageGenerator.HallVictoryEntry> victoryEntries2 = new ArrayList<>();
        for (String round : hall2.roundsIncluded) {
            HallVictoryRecord record = hall2.victoryRecords.get(round);
            if (record != null) {
                // Get hall ELO for this round
                Double hallElo = hall2.hallEloByRound.get(round);
                String hallEloStr = hallElo != null ? String.format("%.1f", hallElo) : "?";
                
                // Get opponent hall ELO from the fetched data
                String oppEloStr = record.oppHallElo != null ? String.format("%.1f", record.oppHallElo) : "?";
                
                // Get emojis
                String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(record.outcome);
                Integer oppOutcome = record.outcome == 0 ? 0 : -record.outcome;
                String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
                
                // Format hall name for image
                String hallFormatted = formatHallNameForImage(hall2.hallName);
                
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
                
                // Create structured entry
                victoryEntries2.add(new ComparisonImageGenerator.HallVictoryEntry(
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    hallEmoji,
                    hallEloStr,
                    hallFormatted,
                    score,
                    oppHallFormatted,
                    oppEloStr,
                    oppEmoji
                ));
            } else {
                victoryEntries2.add(new ComparisonImageGenerator.HallVictoryEntry(
                    VictoryRecordCalculator.getRoundDisplayName(round),
                    true  // isNA
                ));
            }
        }
        sections2.add(ComparisonImageGenerator.Section.forHallVictory("Victory Record", victoryEntries2));
        
        // Win probability
        sections2.add(new ComparisonImageGenerator.Section("Win Probability",
            Arrays.asList(String.format("%.2f%%", 100.0 - winProbability)), true, false));
        
        // Equalize section sizes - add empty rows to sections with fewer rows
        equalizeSectionSizes(sections1, sections2);
        
        ComparisonImageGenerator.ComparisonData data1 = new ComparisonImageGenerator.ComparisonData(
            hall1.hallName, hall1.hallName, sections1);
        ComparisonImageGenerator.ComparisonData data2 = new ComparisonImageGenerator.ComparisonData(
            hall2.hallName, hall2.hallName, sections2);
        
        return ComparisonImageGenerator.generateComparisonImage("Hall Comparison", data1, data2, metadata);
    }
    
    /**
     * Equalizes section sizes between two halls by adding empty rows to sections with fewer rows.
     * This ensures that sections like "Player Stats" and "Seating" align horizontally.
     */
    private void equalizeSectionSizes(List<ComparisonImageGenerator.Section> sections1,
                                     List<ComparisonImageGenerator.Section> sections2) {
        // Both should have same number of sections
        int sectionCount = Math.min(sections1.size(), sections2.size());
        
        for (int i = 0; i < sectionCount; i++) {
            ComparisonImageGenerator.Section s1 = sections1.get(i);
            ComparisonImageGenerator.Section s2 = sections2.get(i);
            
            // Get size based on what type of data the section contains
            int size1 = getSectionSize(s1);
            int size2 = getSectionSize(s2);
            
            if (size1 < size2) {
                // Add empty rows to section 1
                addEmptyRows(s1, size2 - size1);
            } else if (size2 < size1) {
                // Add empty rows to section 2
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
