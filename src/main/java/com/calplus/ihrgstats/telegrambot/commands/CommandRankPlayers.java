package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.MatchScoreUtils;
import com.calplus.ihrgstats.telegrambot.utils.RankingQueryHelper;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;
import com.calplus.ihrgstats.utils.TableFormatter.Alignment;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

/**
 * Command handler for /rankplayers command.
 * Displays a ranked list of all players by their TrueElo rating for the
 * current year (settings.currentYear).
 */
public class CommandRankPlayers {
    private final LogHelper logHelper;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final RankingQueryHelper rankingQueryHelper = new RankingQueryHelper();

    public CommandRankPlayers() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        this.logHelper = new LogHelper();
    }

    /** Represents a player's ranking data */
    private static class PlayerRankData {
        String name;
        String hall;
        String lastRound;
        double trueElo;
        boolean isCapped;

        PlayerRankData(String name, String hall, String lastRound, double trueElo, boolean isCapped) {
            this.name = name;
            this.hall = hall;
            this.lastRound = lastRound;
            this.trueElo = trueElo;
            this.isCapped = isCapped;
        }
    }

    public RankResponse handleCommand(String userId) {
        logHelper.logInfo("Processing /rankplayers command for user " + userId);

        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            return new RankResponse("⚠️ No current year set. An admin must set `settings.currentYear` first.", (Path) null);
        }

        List<A1_Rounds.Round> availableRounds;
        try {
            availableRounds = rounds.getRoundsForYear(year);
        } catch (SQLException e) {
            logHelper.logError("Error fetching rounds: " + e.getMessage());
            return new RankResponse("❌ Database error fetching rounds.", (Path) null);
        }

        if (availableRounds.isEmpty()) {
            return new RankResponse("ℹ️ No rounds with data found for " + year + ".", (Path) null);
        }

        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();

        labels.add("All Rounds");
        callbacks.add("rankplayers_round_all");

        for (A1_Rounds.Round round : availableRounds) {
            labels.add(round.roundLabel);
            callbacks.add("rankplayers_round_" + round.roundOrder);
        }

        labels.add("❌ Cancel");
        callbacks.add("rankplayers_cancel");

        String message = "🏆 **Player Rankings** (" + year + ")\n\nSelect which round to rank players up to:";

        return new RankResponse(message, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }

    public RankResponse handleRoundSelection(String userId, String selectedRound) {
        logHelper.logInfo(com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId) + " selected round: " + selectedRound);

        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            return new RankResponse("⚠️ No current year set.", (Path) null);
        }

        List<PlayerRankData> players;
        try {
            players = fetchPlayerData(year, selectedRound);
        } catch (SQLException e) {
            logHelper.logError("Error fetching player data: " + e.getMessage());
            return new RankResponse("❌ Database error fetching player data.", (Path) null);
        }

        if (players.isEmpty()) {
            String errorMsg = "ℹ️ No player data found for round " + selectedRound + ".";
            return new RankResponse(errorMsg, (Path) null);
        }

        players.sort((p1, p2) -> Double.compare(p2.trueElo, p1.trueElo));

        String homeHall = PropertyResolver.getProperty("settings.homeHall", "");
        String table = formatPlayersTable(players, homeHall);

        String roundDisplay = selectedRound.equalsIgnoreCase("all") ? "All Rounds" : "Round " + selectedRound;
        String message = "🏆 **Player Rankings** (" + roundDisplay + ", " + year + ")\n\n" +
                "Players ranked by TrueElo rating\n\n" + table;

        Path imagePath = null;
        try {
            Set<Integer> highlightRows = new HashSet<>();
            if (!homeHall.isEmpty()) {
                for (int i = 0; i < players.size(); i++) {
                    if (players.get(i).hall.equalsIgnoreCase(homeHall)) {
                        highlightRows.add(i);
                    }
                }
            }
            imagePath = generatePlayersImage(players, highlightRows, selectedRound, year);
        } catch (Exception e) {
            logHelper.logWarning("Failed to generate table image: " + e.getMessage());
        }

        logHelper.logSuccess(String.format("Ranked %d players", players.size()));

        return new RankResponse(message, imagePath);
    }

    public RankResponse handleCancel(String userId) {
        return new RankResponse("❌ Player ranking cancelled.", (Path) null);
    }

    private List<PlayerRankData> fetchPlayerData(int year, String selectedRound) throws SQLException {
        int roundOrderLimit = selectedRound.equalsIgnoreCase("all") ? Integer.MAX_VALUE : Integer.parseInt(selectedRound);
        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            return new ArrayList<>();
        }

        Map<String, D11_PlayerRatings.Rating> ratingsByPlayer = rankingQueryHelper.getLatestRatingsUpToRound(year, roundOrderLimit, trueEloTypeId);

        List<PlayerRankData> players = new ArrayList<>();
        for (Map.Entry<String, D11_PlayerRatings.Rating> entry : ratingsByPlayer.entrySet()) {
            String playerId = entry.getKey();
            D11_PlayerRatings.Rating rating = entry.getValue();

            B6_PlayerYearStatus.Status status = playerYearStatus.getStatus(playerId, year);
            if (status == null) continue;

            A3_Halls.Hall hall = halls.getHallById(status.hallId);
            A1_Rounds.Round round = rounds.getRoundById(rating.roundId);
            String name = playerNames.getNameForYear(playerId, year);

            players.add(new PlayerRankData(
                name != null ? name : playerId,
                hall != null ? hall.hallName : "?",
                round != null ? round.roundLabel : "?",
                rating.ratingValue,
                status.capped));
        }

        return players;
    }

    private String formatPlayersTable(List<PlayerRankData> players, String homeHall) {
        String[] headers = {"Rank", "Elo", "Hall", "LR", "Cap", "Name"};
        Alignment[] alignments = {Alignment.RIGHT, Alignment.RIGHT, Alignment.CENTER, Alignment.CENTER, Alignment.CENTER, Alignment.LEFT};
        int[] columnWidths = {4, 4, 4, 3, 3, 20};

        List<String[]> rows = new ArrayList<>();
        int rank = 1;
        for (PlayerRankData player : players) {
            rows.add(new String[]{
                String.valueOf(rank),
                String.format("%.0f", player.trueElo),
                TableFormatter.shortenHallName(player.hall),
                TableFormatter.shortenRoundName(player.lastRound),
                player.isCapped ? "*" : "",
                TableFormatter.shortenPlayerName(player.name, 20)
            });
            rank++;
        }

        String table = TableFormatter.formatTable(headers, rows, alignments, columnWidths);

        if (!homeHall.isEmpty()) {
            String[] lines = table.split("\n");
            StringBuilder result = new StringBuilder();
            int rowIndex = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (i < 3) {
                    result.append(line).append("\n");
                } else if (line.contains("----")) {
                    result.append(line).append("\n");
                } else if (line.trim().equals("```")) {
                    result.append(line);
                } else {
                    if (rowIndex < players.size() && players.get(rowIndex).hall.equalsIgnoreCase(homeHall)) {
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

    private Path generatePlayersImage(List<PlayerRankData> players, Set<Integer> highlightRows, String selectedRound, int year) throws Exception {
        String[] headers = {"Rank", "Elo", "Hall", "LR", "Cap", "Name"};
        Alignment[] alignments = {Alignment.RIGHT, Alignment.RIGHT, Alignment.CENTER, Alignment.CENTER, Alignment.CENTER, Alignment.LEFT};
        int[] columnWidths = {4, 4, 4, 3, 3, 20};

        List<String[]> rows = new ArrayList<>();
        int rank = 1;
        for (PlayerRankData player : players) {
            rows.add(new String[]{
                String.valueOf(rank),
                String.format("%.0f", player.trueElo),
                TableFormatter.shortenHallName(player.hall),
                TableFormatter.shortenRoundName(player.lastRound),
                player.isCapped ? "*" : "",
                TableFormatter.shortenPlayerName(player.name, 20)
            });
            rank++;
        }

        String lastRoundForMetadata = selectedRound.equalsIgnoreCase("all")
            ? MatchScoreUtils.latestRoundLabel(rounds, year)
            : selectedRound;

        TableImageGenerator.ImageMetadata metadata = new TableImageGenerator.ImageMetadata(
            "Player Rankings", "Players ranked by TrueElo rating", lastRoundForMetadata);

        String entityName = lastRoundForMetadata != null ? lastRoundForMetadata : "unknown";
        return TableImageGenerator.generatePlayerTable(headers, rows, alignments, columnWidths, metadata, highlightRows, "RankPlayers", entityName);
    }

    public static class RankResponse extends CommandResponse {
        public RankResponse(String message, Path imagePath) {
            super(message, imagePath);
        }

        public RankResponse(String message, ButtonConfig buttonConfig) {
            super(message, buttonConfig);
        }
    }
}
