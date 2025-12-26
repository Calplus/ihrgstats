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
        List<String> roundsIncluded;
        
        HallData(String hallName, List<String> roundsIncluded) {
            this.hallName = hallName;
            this.players = new ArrayList<>();
            this.victoryRecords = new HashMap<>();
            this.roundsIncluded = roundsIncluded;
        }
    }
    
    /**
     * Player data container
     */
    private static class PlayerData {
        String name;
        int rank;
        int elo;
        boolean capped;
        Map<String, Integer> seatByRound;
        Map<String, Integer> outcomeByRound;
        Map<String, String> oppNameByRound;
        Map<String, String> oppHallByRound;
        double avgSeat;  // Average seat number
        
        PlayerData(String name, int elo, boolean capped) {
            this.name = name;
            this.elo = elo;
            this.capped = capped;
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
        
        HallVictoryRecord(double hallScore, double oppScore, String oppHall, int outcome) {
            this.hallScore = hallScore;
            this.oppScore = oppScore;
            this.oppHall = oppHall;
            this.outcome = outcome;
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
                    
                    PlayerData player = new PlayerData(playerName, lastElo, capped);
                    
                    // Load seating, outcomes, and opponents for included rounds
                    for (String round : roundsToInclude) {
                        String suffix = getRoundSuffix(round);
                        Integer seat = (Integer) rs.getObject("seat" + suffix);
                        Integer outcome = (Integer) rs.getObject("outcome" + suffix);
                        String oppName = rs.getString("oppName" + suffix);
                        String oppHall = rs.getString("oppHall" + suffix);
                        
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
        }
        
        // Sort players by ELO (descending)
        hallData.players.sort((a, b) -> Integer.compare(b.elo, a.elo));
        
        // Assign ranks
        for (int i = 0; i < hallData.players.size(); i++) {
            hallData.players.get(i).rank = i + 1;
        }
        
        // Calculate victory records
        calculateHallVictoryRecords(hallData);
        
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
    private void calculateHallVictoryRecords(HallData hallData) {
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
                hallData.victoryRecords.put(round, new HallVictoryRecord(hallScore, oppScore, primaryOppHall, outcome));
            }
        }
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
        
        // Player stats table
        sb.append("**📋 Player Stats:**\n```\n");
        sb.append(String.format("%-4s %-6s %-7s %-20s\n", "Rank", "ELO", "Capped", "Name"));
        sb.append(String.format("%-4s %-6s %-7s %-20s\n", "----", "------", "-------", "--------------------"));
        for (PlayerData p : data.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            sb.append(String.format("%-4d %-6d %-7s %-20s\n", 
                p.rank, p.elo, p.capped ? "Yes" : "No", name));
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
                String line = VictoryRecordCalculator.formatHallVictoryRecord(
                    round, data.hallName, record.hallScore, record.oppHall, record.oppScore, record.outcome);
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
        
        // Player stats
        List<String> statsLines1 = new ArrayList<>();
        statsLines1.add(String.format("%-4s %-6s %-7s %-20s", "Rank", "ELO", "Capped", "Name"));
        for (PlayerData p : hall1.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            statsLines1.add(String.format("%-4d %-6d %-7s %-20s", 
                p.rank, p.elo, p.capped ? "Yes" : "No", name));
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
        
        // Victory record (centered, emojis flushed)
        List<String> victoryLines1 = new ArrayList<>();
        for (String round : hall1.roundsIncluded) {
            HallVictoryRecord record = hall1.victoryRecords.get(round);
            if (record != null) {
                victoryLines1.add(VictoryRecordCalculator.formatHallVictoryRecord(
                    round, hall1.hallName, record.hallScore, record.oppHall, record.oppScore, record.outcome));
            } else {
                victoryLines1.add(String.format("%-3s  -NA-", VictoryRecordCalculator.getRoundDisplayName(round)));
            }
        }
        sections1.add(new ComparisonImageGenerator.Section("Victory Record", victoryLines1, false, true));
        
        // Win probability
        sections1.add(new ComparisonImageGenerator.Section("Win Probability", 
            Arrays.asList(String.format("%.1f%%", winProbability)), true, false));
        
        // Prepare right side data (similar structure)
        List<ComparisonImageGenerator.Section> sections2 = new ArrayList<>();
        
        // Player stats
        List<String> statsLines2 = new ArrayList<>();
        statsLines2.add(String.format("%-4s %-6s %-7s %-20s", "Rank", "ELO", "Capped", "Name"));
        for (PlayerData p : hall2.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            statsLines2.add(String.format("%-4d %-6d %-7s %-20s",
                p.rank, p.elo, p.capped ? "Yes" : "No", name));
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
        
        // Victory record
        List<String> victoryLines2 = new ArrayList<>();
        for (String round : hall2.roundsIncluded) {
            HallVictoryRecord record = hall2.victoryRecords.get(round);
            if (record != null) {
                victoryLines2.add(VictoryRecordCalculator.formatHallVictoryRecord(
                    round, hall2.hallName, record.hallScore, record.oppHall, record.oppScore, record.outcome));
            } else {
                victoryLines2.add(String.format("%-3s  -NA-", VictoryRecordCalculator.getRoundDisplayName(round)));
            }
        }
        sections2.add(new ComparisonImageGenerator.Section("Victory Record", victoryLines2, false, true));
        
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
            
            int size1 = s1.lines.size();
            int size2 = s2.lines.size();
            
            if (size1 < size2) {
                // Add empty rows to section 1
                for (int j = 0; j < size2 - size1; j++) {
                    s1.lines.add("");
                }
            } else if (size2 < size1) {
                // Add empty rows to section 2
                for (int j = 0; j < size1 - size2; j++) {
                    s2.lines.add("");
                }
            }
        }
    }
}
