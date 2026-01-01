package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CommandInfoMatch: Shows match information for a specific round
 * - Table 1: Match Info (all hall matchups for that round with results)
 * - Table 2: Scores (cumulative match wins and board wins up to that round)
 */
public class CommandInfoMatch {
    private final String dbPath;
    private final LogHelper logHelper;
    private static final Map<String, SelectionState> userSelectionStates = new ConcurrentHashMap<>();

    public CommandInfoMatch() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.logHelper = new LogHelper();
        this.dbPath = DatabaseHelper.getDefaultDatabasePathString();
    }

    /**
     * Selection state for match info command
     */
    private static class MatchInfoSelectionState extends SelectionState {
        String selectedRound;
    }

    /**
     * Response container
     */
    public static class MatchResponse extends CommandResponse {
        public MatchResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            super(message, imagePath, buttonConfig);
        }
    }

    /**
     * Handles the /infomatch command (initial call)
     */
    public MatchResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("User %s requested /infomatch command", userId));
        
        // Clear any existing state
        userSelectionStates.put(userId, new MatchInfoSelectionState());
        
        // Cleanup old states
        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        
        // Fetch available rounds
        List<String> availableRounds = RoundDetector.getAvailableRounds(dbPath);
        
        if (availableRounds.isEmpty()) {
            String errorMsg = "ℹ️ No rounds found in database. Please upload round data first.";
            logHelper.logWarning("No rounds available");
            return new MatchResponse(errorMsg, null, null);
        }
        
        // Create button layout (4 columns)
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        
        // Add "Latest Round" option (NO "All Rounds" for infomatch)
        String latestRound = availableRounds.get(availableRounds.size() - 1);
        labels.add("⏱️ Latest Round (" + VictoryRecordCalculator.getRoundDisplayName(latestRound) + ")");
        callbacks.add("infomatch_round_latest");
        
        // Add individual rounds
        for (String round : availableRounds) {
            String displayName = VictoryRecordCalculator.getRoundDisplayName(round);
            labels.add(displayName);
            callbacks.add("infomatch_round_" + round);
        }
        
        // Add cancel button
        labels.add("❌ Cancel");
        callbacks.add("infomatch_cancel");
        
        String message = "**⚔️ Match Information**\n\n" +
                        "Select a **round**:";
        
        return new MatchResponse(message, null, 
            new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }

    /**
     * Handles round selection and generates match info
     */
    public MatchResponse handleRoundSelection(String userId, String selectedRound) {
        logHelper.logInfo(String.format("User %s selected round: %s", userId, selectedRound));
        
        MatchInfoSelectionState state = (MatchInfoSelectionState) userSelectionStates.get(userId);
        if (state == null) state = new MatchInfoSelectionState();
        
        // Handle "latest" special case
        if (selectedRound.equals("latest")) {
            List<String> availableRounds = RoundDetector.getAvailableRounds(dbPath);
            selectedRound = availableRounds.get(availableRounds.size() - 1);
        }
        
        state.selectedRound = selectedRound;
        userSelectionStates.put(userId, state);
        
        try {
            MatchResponse response = generateMatchInfo(selectedRound);
            userSelectionStates.remove(userId);
            return response;
        } catch (Exception e) {
            logHelper.logError("Failed to generate match info: " + e.getMessage());
            return new MatchResponse("❌ Error generating match information. Please try again.", null, null);
        }
    }

    /**
     * Handles cancel button
     */
    public MatchResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new MatchResponse("❌ Match information request cancelled.", null, null);
    }

    /**
     * Generates complete match info output
     */
    private MatchResponse generateMatchInfo(String selectedRound) throws Exception {
        List<String> availableRounds = RoundDetector.getAvailableRounds(dbPath);
        
        // Get rounds up to selected round for cumulative scores
        int selectedIndex = Constants.ROUND_SEQUENCE.indexOf(selectedRound);
        List<String> roundsUpToSelected = availableRounds.stream()
            .filter(r -> Constants.ROUND_SEQUENCE.indexOf(r) <= selectedIndex)
            .collect(Collectors.toList());
        
        // Fetch match data
        List<MatchupData> matchups = fetchMatchupsForRound(selectedRound);
        
        // Calculate cumulative scores
        List<HallScoreData> scores = calculateCumulativeScores(roundsUpToSelected);
        
        // Generate text output
        String textOutput = generateTextOutput(matchups, scores, selectedRound);
        
        // Generate image
        Path imagePath = generateImage(matchups, scores, selectedRound);
        
        logHelper.logSuccess(String.format("Generated match info for round: %s", selectedRound));
        
        return new MatchResponse(textOutput, imagePath, null);
    }

    /**
     * Matchup data container
     */
    private static class MatchupData {
        String hall1;
        String hall2;
        double hall1Score;
        double hall2Score;
        int outcome; // 1 = hall1 win, 0 = draw, -1 = hall1 loss
        Double hall1Elo;
        Double hall2Elo;
        
        MatchupData(String hall1, String hall2, double hall1Score, double hall2Score, 
                   int outcome, Double hall1Elo, Double hall2Elo) {
            this.hall1 = hall1;
            this.hall2 = hall2;
            this.hall1Score = hall1Score;
            this.hall2Score = hall2Score;
            this.outcome = outcome;
            this.hall1Elo = hall1Elo;
            this.hall2Elo = hall2Elo;
        }
    }

    /**
     * Hall score data container
     */
    private static class HallScoreData {
        String hall;
        double matchWins;  // +1 for win, +0.5 for draw per round
        double boardWins;  // Sum of all player wins (+1 per player win, +0.5 per draw)
        int rank;
        
        HallScoreData(String hall, double matchWins, double boardWins) {
            this.hall = hall;
            this.matchWins = matchWins;
            this.boardWins = boardWins;
        }
    }

    /**
     * Fetches all matchups (hall vs hall) for a specific round
     */
    private List<MatchupData> fetchMatchupsForRound(String round) throws SQLException {
        List<MatchupData> matchups = new ArrayList<>();
        String roundSuffix = RoundUtils.getRoundColumnSuffix(round);
        
        try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
            // Get all players who played this round, grouped by hall
            String sql = "SELECT hall, outcome" + roundSuffix + ", oppHall" + roundSuffix + 
                        " FROM A1_PlayerStats WHERE outcome" + roundSuffix + " IS NOT NULL AND active = 1";
            
            Map<String, Map<String, Double>> hallScores = new HashMap<>(); // hall -> opponent hall -> score
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                ResultSet rs = pstmt.executeQuery();
                
                while (rs.next()) {
                    String hall = rs.getString("hall");
                    int outcome = rs.getInt("outcome" + roundSuffix);
                    String oppHall = rs.getString("oppHall" + roundSuffix);
                    
                    // Include WALKOVER opponents
                    if (oppHall == null) {
                        continue; // Skip if no opponent at all
                    }
                    
                    double points = VictoryRecordCalculator.outcomeToPoints(outcome);
                    
                    hallScores.putIfAbsent(hall, new HashMap<>());
                    hallScores.get(hall).put(oppHall, 
                        hallScores.get(hall).getOrDefault(oppHall, 0.0) + points);
                }
            }
            
            // Build matchup list
            Set<String> processedPairs = new HashSet<>();
            
            for (Map.Entry<String, Map<String, Double>> entry : hallScores.entrySet()) {
                String hall1 = entry.getKey();
                
                for (Map.Entry<String, Double> oppEntry : entry.getValue().entrySet()) {
                    String hall2 = oppEntry.getKey();
                    double hall1Score = oppEntry.getValue();
                    
                    // Handle WALKOVER specially - always include
                    boolean isWalkover = "WALKOVER".equalsIgnoreCase(hall2);
                    
                    // Create canonical pair key (alphabetically sorted)
                    String pairKey = hall1.compareTo(hall2) < 0 ? hall1 + "_" + hall2 : hall2 + "_" + hall1;
                    
                    if (!isWalkover && processedPairs.contains(pairKey)) {
                        continue; // Already processed this matchup (non-walkover)
                    }
                    processedPairs.add(pairKey);
                    
                    // Get hall2's score (0 for WALKOVER)
                    double hall2Score = isWalkover ? 0.0 : 
                        hallScores.getOrDefault(hall2, new HashMap<>()).getOrDefault(hall1, 0.0);
                    
                    // Determine outcome
                    int outcome = hall1Score > hall2Score ? 1 : (hall1Score < hall2Score ? -1 : 0);
                    
                    // Fetch hall ELOs for this round (null for WALKOVER)
                    Double hall1Elo = fetchHallElo(conn, hall1, round);
                    Double hall2Elo = isWalkover ? null : fetchHallElo(conn, hall2, round);
                    
                    matchups.add(new MatchupData(hall1, hall2, hall1Score, hall2Score, outcome, hall1Elo, hall2Elo));
                }
            }
        }
        
        // Sort matchups by hall1 name
        matchups.sort(Comparator.comparing(m -> m.hall1));
        
        return matchups;
    }

    /**
     * Fetches hall ELO for a specific round (average of top 5 players)
     */
    private Double fetchHallElo(Connection conn, String hall, String round) throws SQLException {
        String roundSuffix = RoundUtils.getRoundColumnSuffix(round);
        String sql = "SELECT trueElo" + roundSuffix + " FROM A1_PlayerStats WHERE hall = ? AND trueElo" + roundSuffix + 
                    " IS NOT NULL AND active = 1 ORDER BY trueElo" + roundSuffix + " DESC LIMIT 5";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, hall);
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
     * Calculates cumulative scores for all halls up to selected round
     */
    private List<HallScoreData> calculateCumulativeScores(List<String> roundsUpToSelected) throws SQLException {
        Map<String, HallScoreData> hallScores = new HashMap<>();
        
        try (Connection conn = DatabaseHelper.getConnection(dbPath)) {
            for (String round : roundsUpToSelected) {
                String roundSuffix = RoundUtils.getRoundColumnSuffix(round);
                
                // Get all players who played this round
                String sql = "SELECT hall, outcome" + roundSuffix + ", oppHall" + roundSuffix + 
                            " FROM A1_PlayerStats WHERE outcome" + roundSuffix + " IS NOT NULL AND active = 1";
                
                Map<String, Map<String, Double>> roundHallScores = new HashMap<>(); // hall -> opponent hall -> score
                
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    ResultSet rs = pstmt.executeQuery();
                    
                    while (rs.next()) {
                        String hall = rs.getString("hall");
                        int outcome = rs.getInt("outcome" + roundSuffix);
                        String oppHall = rs.getString("oppHall" + roundSuffix);
                        
                        double points = VictoryRecordCalculator.outcomeToPoints(outcome);
                        
                        // Add to board wins (player-level score)
                        hallScores.putIfAbsent(hall, new HallScoreData(hall, 0, 0));
                        hallScores.get(hall).boardWins += points;
                        
                        // Track opponent for match wins
                        if (oppHall != null && !"WALKOVER".equalsIgnoreCase(oppHall)) {
                            roundHallScores.putIfAbsent(hall, new HashMap<>());
                            roundHallScores.get(hall).put(oppHall, 
                                roundHallScores.get(hall).getOrDefault(oppHall, 0.0) + points);
                        }
                    }
                }
                
                // Calculate match wins for this round (hall vs hall level)
                Set<String> processedPairs = new HashSet<>();
                
                for (Map.Entry<String, Map<String, Double>> entry : roundHallScores.entrySet()) {
                    String hall1 = entry.getKey();
                    
                    for (Map.Entry<String, Double> oppEntry : entry.getValue().entrySet()) {
                        String hall2 = oppEntry.getKey();
                        double hall1Score = oppEntry.getValue();
                        
                        // Create canonical pair key
                        String pairKey = hall1.compareTo(hall2) < 0 ? hall1 + "_" + hall2 : hall2 + "_" + hall1;
                        
                        if (processedPairs.contains(pairKey)) {
                            continue;
                        }
                        processedPairs.add(pairKey);
                        
                        // Get hall2's score against hall1
                        double hall2Score = roundHallScores.getOrDefault(hall2, new HashMap<>()).getOrDefault(hall1, 0.0);
                        
                        // Determine match outcome
                        if (hall1Score > hall2Score) {
                            hallScores.get(hall1).matchWins += 1.0;
                        } else if (hall1Score < hall2Score) {
                            hallScores.putIfAbsent(hall2, new HallScoreData(hall2, 0, 0));
                            hallScores.get(hall2).matchWins += 1.0;
                        } else {
                            // Draw
                            hallScores.get(hall1).matchWins += 0.5;
                            hallScores.putIfAbsent(hall2, new HallScoreData(hall2, 0, 0));
                            hallScores.get(hall2).matchWins += 0.5;
                        }
                    }
                }
            }
        }
        
        // Convert to list and sort by match wins (desc), then board wins (desc)
        List<HallScoreData> scoreList = new ArrayList<>(hallScores.values());
        scoreList.sort((a, b) -> {
            int cmp = Double.compare(b.matchWins, a.matchWins);
            if (cmp == 0) {
                cmp = Double.compare(b.boardWins, a.boardWins);
            }
            return cmp;
        });
        
        // Assign ranks
        for (int i = 0; i < scoreList.size(); i++) {
            scoreList.get(i).rank = i + 1;
        }
        
        return scoreList;
    }

    /**
     * Generates text output for match info
     */
    private String generateTextOutput(List<MatchupData> matchups, List<HallScoreData> scores, String round) {
        // Get home hall for asterisk marking
        String homeHall = PropertyResolver.getProperty("settings.homeHall", "");
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**⚔️ Match Information - %s**\n\n", VictoryRecordCalculator.getRoundDisplayName(round)));
        
        // Table 1: Match Info (following victory record format without round column)
        sb.append("**📋 Match Info:**\n```\n");
        if (matchups.isEmpty()) {
            sb.append("No matches found for this round\n");
        } else {
            // Format: emoji ELO Hall1 score Hall2 ELO emoji (monospaced)
            for (MatchupData m : matchups) {
                String emoji1 = VictoryRecordCalculator.getOutcomeEmoji(m.outcome);
                Integer oppOutcome = m.outcome == 0 ? 0 : -m.outcome;
                String emoji2 = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);
                
                // For WALKOVER opponents, show "-" instead of "?"
                String elo1 = m.hall1Elo != null ? String.format("%.0f", m.hall1Elo) : ("WALKOVER".equalsIgnoreCase(m.hall1) ? "-" : "?");
                String elo2 = m.hall2Elo != null ? String.format("%.0f", m.hall2Elo) : ("WALKOVER".equalsIgnoreCase(m.hall2) ? "-" : "?");
                
                // Remove "Hall" prefix - just show number/name
                String hall1Display = "WALKOVER".equalsIgnoreCase(m.hall1) ? "WALKOVER" : m.hall1;
                String hall2Display = "WALKOVER".equalsIgnoreCase(m.hall2) ? "WALKOVER" : m.hall2;
                
                // Format score - show as int if not 0.5 increments
                String scoreStr;
                if (m.hall1Score == Math.floor(m.hall1Score) && m.hall2Score == Math.floor(m.hall2Score)) {
                    scoreStr = String.format("%.0f-%.0f", m.hall1Score, m.hall2Score);
                } else {
                    scoreStr = String.format("%.1f-%.1f", m.hall1Score, m.hall2Score);
                }
                
                // Build line with monospaced format matching image generation
                String line = String.format("%s %-4s %-15s %s %-15s %-4s %s",
                    emoji1, elo1, hall1Display, scoreStr, hall2Display, elo2, emoji2);
                
                // Add asterisk if line contains home hall
                if (!homeHall.isEmpty() && (m.hall1.equals(homeHall) || m.hall2.equals(homeHall))) {
                    line += "*";
                }
                
                sb.append(line).append("\n");
            }
        }
        sb.append("```\n\n");
        
        // Table 2: Cumulative Scores
        sb.append(String.format("**📊 Cumulative Scores (up to %s):**\n```\n", VictoryRecordCalculator.getRoundDisplayName(round)));
        sb.append(String.format("%-4s %-8s %-10s %-10s\n", "Rank", "Hall", "Match Wins", "Board Wins"));
        sb.append(String.format("%-4s %-8s %-10s %-10s\n", "----", "--------", "----------", "----------"));
        for (HallScoreData s : scores) {
            String hallName = s.hall;
            sb.append(String.format("%-4d %-8s %-10.1f %-10.1f\n", 
                s.rank, hallName, s.matchWins, s.boardWins));
        }
        sb.append("```");
        
        return sb.toString();
    }

    /**
     * Generates match information image using InfoImageGenerator
     */
    private Path generateImage(List<MatchupData> matchups, List<HallScoreData> scores, String round) throws Exception {
        // Get home hall for highlighting
        String homeHall = PropertyResolver.getProperty("settings.homeHall", "");
        
        // Prepare metadata
        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Match Information";
        metadata.description = "Match results for the round";
        metadata.lastRound = VictoryRecordCalculator.getRoundDisplayName(round);
        
        // Prepare sections
        List<InfoImageGenerator.Section> sections = new ArrayList<>();
        
        // Section 1: Match Info (use VictoryEntry for sophisticated layout)
        InfoImageGenerator.Section matchInfoSection = new InfoImageGenerator.Section("Match Info");
        
        for (MatchupData m : matchups) {
            String emoji1 = VictoryRecordCalculator.getOutcomeEmoji(m.outcome);
            String emoji2 = VictoryRecordCalculator.getOutcomeEmoji(m.outcome == 0 ? 0 : -m.outcome);
            
            // For WALKOVER opponents, show "-" instead of "?"
            String elo1 = m.hall1Elo != null ? String.format("%.0f", m.hall1Elo) : ("WALKOVER".equalsIgnoreCase(m.hall1) ? "-" : "?");
            String elo2 = m.hall2Elo != null ? String.format("%.0f", m.hall2Elo) : ("WALKOVER".equalsIgnoreCase(m.hall2) ? "-" : "?");
            
            // Format hall names according to requirements:
            // - "n hall" for non-numbered halls (e.g., "RC hall")
            // - "Hall n" for numbered halls (e.g., "Hall 4")
            // - Just "WALKOVER" for walkovers
            String hall1Display = formatHallNameForMatch(m.hall1);
            String hall2Display = formatHallNameForMatch(m.hall2);
            
            // Format score - show as int if not 0.5 increments
            String scoreStr;
            if (m.hall1Score == Math.floor(m.hall1Score) && m.hall2Score == Math.floor(m.hall2Score)) {
                scoreStr = String.format("%.0f-%.0f", m.hall1Score, m.hall2Score);
            } else {
                scoreStr = String.format("%.1f-%.1f", m.hall1Score, m.hall2Score);
            }
            
            // Create VictoryEntry for sophisticated layout:
            // Left: hall1 emote+elo, Right: hall2 elo+emote (flush right)
            // Center: score, hall names adjacent to score
            InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
            entry.round = "";  // No round column for match info
            entry.hallEmoji = emoji1;
            entry.playerElo = elo1;
            entry.playerName = hall1Display;  // Hall name left of score
            entry.playerHall = "";  // No hall abbreviation needed
            entry.score = scoreStr;
            entry.opponentName = hall2Display;  // Hall name right of score
            entry.opponentElo = elo2;
            entry.opponentHall = "";  // No hall abbreviation needed
            entry.oppEmoji = emoji2;
            
            // Set highlighting for home hall
            entry.highlightPlayer = !homeHall.isEmpty() && m.hall1.equals(homeHall);
            entry.highlightOpponent = !homeHall.isEmpty() && m.hall2.equals(homeHall);
            
            matchInfoSection.addVictoryEntry(entry);
        }
        sections.add(matchInfoSection);
        
        // Section 2: Cumulative Scores
        InfoImageGenerator.Section scoresSection = new InfoImageGenerator.Section(
            String.format("Cumulative Scores (up to %s)", VictoryRecordCalculator.getRoundDisplayName(round))
        );
        
        // Header row with monospaced format
        scoresSection.addMonospacedRow(String.format("%-4s %-20s %-10s %-10s", "Rank", "Hall", "MatchWins", "BoardWins"));
        
        // Score rows with monospaced format and highlighting for home hall
        for (HallScoreData s : scores) {
            boolean isHomeHall = !homeHall.isEmpty() && s.hall.equals(homeHall);
            scoresSection.addMonospacedRow(String.format("%-4d %-20s %-10.1f %-10.1f", 
                s.rank, s.hall, s.matchWins, s.boardWins), isHomeHall);
        }
        sections.add(scoresSection);
        
        // Generate image (no hall identifier needed for match info)
        return InfoImageGenerator.generateInfoImage(metadata, sections, null);
    }
    
    /**
     * Formats hall name for Match Info display according to requirements:
     * - "n hall" for non-numbered halls (e.g., "RC hall")
     * - "Hall n" for numbered halls (e.g., "hall 4")
     * - Just "WALKOVER" for walkovers (not "WALKOVER Hall")
     */
    private String formatHallNameForMatch(String hallName) {
        if ("WALKOVER".equalsIgnoreCase(hallName)) {
            return "WALKOVER";
        }
        
        try {
            // If it's a number, format as "Hall n"
            int num = Integer.parseInt(hallName);
            return "Hall " + num;
        } catch (NumberFormatException e) {
            // If it's not a number, format as "n hall"
            return hallName + " Hall";
        }
    }
}