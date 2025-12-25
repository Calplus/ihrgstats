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
 * Command handler for /rankplayers command.
 * Displays a ranked list of all players by their TrueElo rating.
 */
public class CommandRankPlayers {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final String dbPath;

    // Round sequence to find last played round
    private static final List<String> ROUND_SEQUENCE = Arrays.asList("1", "2", "3", "4", "5", "6", "t16", "t8", "t4", "t2");

    public CommandRankPlayers() {
        // Load environment variables
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.dbPath = Paths.get(System.getProperty("user.dir"), "database", "core", "default.db").toString();
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
     * Handles the /rankplayers command
     * @return Response with message and image
     */
    public RankResponse handleCommand() {
        discordLog.logInfo("Processing /rankplayers command");
        telegramLog.logInfo("Processing /rankplayers command");

        List<PlayerRankData> players = fetchPlayerData();

        if (players.isEmpty()) {
            String errorMsg = "ℹ️ No player data found in database.";
            discordLog.logWarning("No players found for ranking");
            telegramLog.logWarning("No players found for ranking");
            return new RankResponse(errorMsg, null);
        }

        // Sort by TrueElo descending
        players.sort((p1, p2) -> Integer.compare(p2.trueElo, p1.trueElo));

        // Get home hall setting for asterisk in text
        String homeHallForText = PropertyResolver.getProperty("settings.homeHall", "");

        // Format as table
        String table = formatPlayersTable(players, homeHallForText);

        String message = "🏆 **Player Rankings**\n\n" +
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
            
            imagePath = generatePlayersImage(players, highlightRows);
        } catch (Exception e) {
            discordLog.logWarning("Failed to generate table image: " + e.getMessage());
            telegramLog.logWarning("Failed to generate table image: " + e.getMessage());
        }

        discordLog.logSuccess(String.format("Ranked %d players", players.size()));
        telegramLog.logSuccess(String.format("Ranked %d players", players.size()));

        return new RankResponse(message, imagePath);
    }

    /**
     * Fetches player data from database
     */
    private List<PlayerRankData> fetchPlayerData() {
        List<PlayerRankData> players = new ArrayList<>();

        try {
            String jdbcUrl = "jdbc:sqlite:" + dbPath;
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                String sql = "SELECT name, hall FROM A1_PlayerStats WHERE active = 1";
                
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {

                    while (rs.next()) {
                        String name = rs.getString("name");
                        String hall = rs.getString("hall");

                        // Find last round played and corresponding TrueElo
                        LastRoundData lastRoundData = findLastRoundPlayed(conn, name);
                        
                        // Check if player is capped
                        boolean isCapped = isPlayerCapped(conn, name);
                        
                        if (lastRoundData != null && lastRoundData.trueElo != null) {
                            players.add(new PlayerRankData(name, hall, 
                                lastRoundData.roundName, lastRoundData.trueElo, isCapped));
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
     * Finds the last round a player actually played
     */
    private LastRoundData findLastRoundPlayed(Connection conn, String playerName) throws SQLException {
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

                        // Player actually played if they have an opponent (not null and not empty)
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
    private Path generatePlayersImage(List<PlayerRankData> players, Set<Integer> highlightRows) throws Exception {
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

        // Extract last round from player data for metadata
        String lastRoundForMetadata = !players.isEmpty() ? players.get(0).lastRound : null;
        
        // Create metadata with title, description, and last round
        TableImageGenerator.ImageMetadata metadata = new TableImageGenerator.ImageMetadata(
            "Player Rankings",
            "Players ranked by TrueElo rating",
            lastRoundForMetadata
        );

        return TableImageGenerator.generatePlayerTable(headers, rows, alignments, columnWidths, metadata, highlightRows);
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
