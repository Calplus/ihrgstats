package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.PropertyResolver;
import com.calplus.ihrgstats.utils.TableFormatter;
import com.calplus.ihrgstats.utils.TableFormatter.Alignment;
import com.calplus.ihrgstats.utils.TableImageGenerator;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;

/**
 * Command handler for /rankhalls command.
 * Displays hall rankings based on average TrueElo of top 5 players (or all if less than 5).
 */
public class CommandRankHalls {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final String dbPath;

    // Round sequence to find last played round
    private static final List<String> ROUND_SEQUENCE = Arrays.asList("1", "2", "3", "4", "5", "6", "t16", "t8", "t4", "t2");

    public CommandRankHalls() {
        // Load environment variables
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.dbPath = Paths.get(System.getProperty("user.dir"), "database", "core", "default.db").toString();
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
     * Handles the /rankhalls command
     * @return Response with message and image
     */
    public RankResponse handleCommand() {
        discordLog.logInfo("Processing /rankhalls command");
        telegramLog.logInfo("Processing /rankhalls command");

        // Fetch all players with their Elo
        List<PlayerEloData> players = fetchPlayerData();

        if (players.isEmpty()) {
            String errorMsg = "ℹ️ No player data found in database.";
            discordLog.logWarning("No players found for hall ranking");
            telegramLog.logWarning("No players found for hall ranking");
            return new RankResponse(errorMsg, null);
        }

        // Group by hall and calculate averages
        List<HallRankData> halls = calculateHallRankings(players);

        if (halls.isEmpty()) {
            String errorMsg = "ℹ️ No hall rankings could be calculated.";
            discordLog.logWarning("No hall rankings calculated");
            telegramLog.logWarning("No hall rankings calculated");
            return new RankResponse(errorMsg, null);
        }

        // Sort by average Elo descending
        halls.sort((h1, h2) -> Double.compare(h2.averageElo, h1.averageElo));

        // Get home hall setting for asterisk in text
        String homeHallForText = PropertyResolver.getProperty("settings.homeHall", "");

        // Format as table
        String table = formatHallsTable(halls, homeHallForText);

        String message = "🏆 **Hall Rankings**\n\n" +
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
            
            imagePath = generateHallsImage(halls, players, highlightRows);
        } catch (Exception e) {
            discordLog.logWarning("Failed to generate table image: " + e.getMessage());
            telegramLog.logWarning("Failed to generate table image: " + e.getMessage());
        }

        discordLog.logSuccess(String.format("Ranked %d halls", halls.size()));
        telegramLog.logSuccess(String.format("Ranked %d halls", halls.size()));

        return new RankResponse(message, imagePath);
    }

    /**
     * Fetches player data from database
     */
    private List<PlayerEloData> fetchPlayerData() {
        List<PlayerEloData> players = new ArrayList<>();

        try {
            String jdbcUrl = "jdbc:sqlite:" + dbPath;
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                String sql = "SELECT name, hall, capped FROM A1_PlayerStats WHERE active = 1";
                
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {

                    while (rs.next()) {
                        String name = rs.getString("name");
                        String hall = rs.getString("hall");
                        boolean capped = rs.getInt("capped") == 1;

                        // Find last round played and corresponding TrueElo
                        LastRoundData lastRound = findLastRoundData(conn, name);
                        
                        if (lastRound != null && lastRound.trueElo != null) {
                            players.add(new PlayerEloData(name, hall, lastRound.trueElo, lastRound.roundName, capped));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            discordLog.logError("Error fetching player data: " + e.getMessage());
            telegramLog.logError("Error fetching player data: " + e.getMessage());
            e.printStackTrace();
        }

        return players;
    }

    /**
     * Finds the last round data (round name and TrueElo) for a player
     */
    private LastRoundData findLastRoundData(Connection conn, String playerName) throws SQLException {
        // Check rounds in reverse order
        for (int i = ROUND_SEQUENCE.size() - 1; i >= 0; i--) {
            String round = ROUND_SEQUENCE.get(i);
            String roundCol = getRoundColumnName("trueElo", round);
            String oppNameCol = getRoundColumnName("oppName", round);

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
                            return new LastRoundData(round, trueElo);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Gets the database column name for a round
     */
    private String getRoundColumnName(String prefix, String round) {
        if (round.matches("[1-6]")) {
            return prefix + "R" + round;
        } else {
            // t16, t8, t4, t2
            return prefix + round.toUpperCase();
        }
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
    private Path generateHallsImage(List<HallRankData> halls, List<PlayerEloData> players, Set<Integer> highlightRows) throws Exception {
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

        // Extract last round from player data for metadata
        // Find the most recent round from all players
        String lastRoundForMetadata = null;
        if (!players.isEmpty()) {
            // Get the first player's last round (all should be same or very close)
            lastRoundForMetadata = players.get(0).lastRound;
        }
        
        // Create metadata with title, description, and last round
        TableImageGenerator.ImageMetadata metadata = new TableImageGenerator.ImageMetadata(
            "Hall Rankings",
            "Halls ranked by average\nTrueElo (top 5 players)",
            lastRoundForMetadata
        );

        return TableImageGenerator.generateHallTable(headers, rows, hallNames, alignments, columnWidths, metadata, highlightRows);
    }
    
    /**
     * Response object containing message and image path
     */
    public static class RankResponse {
        public final String message;
        public final Path imagePath;
        
        public RankResponse(String message, Path imagePath) {
            this.message = message;
            this.imagePath = imagePath;
        }
    }
}
