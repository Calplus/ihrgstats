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
 * Command handler for /rankhalls command.
 * Displays hall rankings based on average TrueElo of top 5 players (or all if less than 5),
 * for the current year (settings.currentYear).
 */
public class CommandRankHalls {
    private final LogHelper logHelper;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final RankingQueryHelper rankingQueryHelper = new RankingQueryHelper();

    public CommandRankHalls() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        this.logHelper = new LogHelper();
    }

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

    private static class PlayerEloData {
        String hall;
        double trueElo;
        boolean capped;

        PlayerEloData(String hall, double trueElo, boolean capped) {
            this.hall = hall;
            this.trueElo = trueElo;
            this.capped = capped;
        }
    }

    public RankResponse handleCommand(String userId) {
        logHelper.logInfo("Processing /rankhalls command for user " + userId);

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
        callbacks.add("rankhalls_round_all");

        labels.add("🌐 All Years");
        callbacks.add("rankhalls_round_allyears");

        for (A1_Rounds.Round round : availableRounds) {
            labels.add(round.roundLabel);
            callbacks.add("rankhalls_round_" + round.roundOrder);
        }

        labels.add("❌ Cancel");
        callbacks.add("rankhalls_cancel");

        String message = "🏆 **Hall Rankings** (" + year + ")\n\nSelect which round to rank halls up to:";

        return new RankResponse(message, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }

    public RankResponse handleRoundSelection(String userId, String selectedRound) {
        logHelper.logInfo(com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId) + " selected round: " + selectedRound);

        boolean allYears = selectedRound.equalsIgnoreCase("allyears");
        Integer year = null;
        if (!allYears) {
            year = YearContext.getCurrentYear();
            if (year == null) {
                return new RankResponse("⚠️ No current year set.", (Path) null);
            }
        }

        List<PlayerEloData> players;
        try {
            players = allYears ? fetchPlayerDataAllYears() : fetchPlayerData(year, selectedRound);
        } catch (SQLException e) {
            logHelper.logError("Error fetching player data: " + e.getMessage());
            return new RankResponse("❌ Database error fetching player data.", (Path) null);
        }

        if (players.isEmpty()) {
            String errorMsg = "ℹ️ No player data found for " + (allYears ? "any year" : "round " + selectedRound) + ".";
            return new RankResponse(errorMsg, (Path) null);
        }

        List<HallRankData> hallRankings = calculateHallRankings(players);
        hallRankings.sort((h1, h2) -> Double.compare(h2.averageElo, h1.averageElo));

        String homeHall = PropertyResolver.getProperty("settings.homeHall", "");
        String table = formatHallsTable(hallRankings, homeHall);

        String roundDisplay = allYears ? "All Years" : (selectedRound.equalsIgnoreCase("all") ? "All Rounds" : "Round " + selectedRound);
        String yearDisplay = allYears ? "" : (", " + year);
        String message = "🏆 **Hall Rankings** (" + roundDisplay + yearDisplay + ")\n\n" +
                "Halls ranked by average TrueElo of top 5 players\n\n" + table;

        Path imagePath = null;
        try {
            Set<Integer> highlightRows = new HashSet<>();
            if (!homeHall.isEmpty()) {
                for (int i = 0; i < hallRankings.size(); i++) {
                    if (hallRankings.get(i).hallName.equalsIgnoreCase(homeHall)) {
                        highlightRows.add(i);
                        break;
                    }
                }
            }
            imagePath = allYears
                ? generateHallsImageAllYears(hallRankings, highlightRows)
                : generateHallsImage(hallRankings, highlightRows, selectedRound, year);
        } catch (Exception e) {
            logHelper.logWarning("Failed to generate table image: " + e.getMessage());
        }

        logHelper.logSuccess(String.format("Ranked %d halls", hallRankings.size()));

        return new RankResponse(message, imagePath);
    }

    public RankResponse handleCancel(String userId) {
        return new RankResponse("❌ Hall ranking cancelled.", (Path) null);
    }

    private List<PlayerEloData> fetchPlayerData(int year, String selectedRound) throws SQLException {
        int roundOrderLimit = selectedRound.equalsIgnoreCase("all") ? Integer.MAX_VALUE : Integer.parseInt(selectedRound);
        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            return new ArrayList<>();
        }

        Map<String, D11_PlayerRatings.Rating> ratingsByPlayer = rankingQueryHelper.getLatestRatingsUpToRound(year, roundOrderLimit, trueEloTypeId);

        List<PlayerEloData> players = new ArrayList<>();
        for (Map.Entry<String, D11_PlayerRatings.Rating> entry : ratingsByPlayer.entrySet()) {
            B6_PlayerYearStatus.Status status = playerYearStatus.getStatus(entry.getKey(), year);
            if (status == null) continue;
            A3_Halls.Hall hall = halls.getHallById(status.hallId);
            if (hall == null) continue;
            players.add(new PlayerEloData(hall.hallName, entry.getValue().ratingValue, status.capped));
        }
        return players;
    }

    /**
     * All-time roster: every player who has EVER played, each shown under
     * their own single most recent hall/capped status - ratings are already
     * whole-history cumulative, so only the roster (not the rating value)
     * differs from the current-year view.
     */
    private List<PlayerEloData> fetchPlayerDataAllYears() throws SQLException {
        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            return new ArrayList<>();
        }

        Map<String, D11_PlayerRatings.Rating> ratingsByPlayer = rankingQueryHelper.getLatestRatingsAllYears(trueEloTypeId);

        List<PlayerEloData> players = new ArrayList<>();
        for (B6_PlayerYearStatus.Status status : playerYearStatus.getMostRecentStatusForEachPlayer()) {
            D11_PlayerRatings.Rating rating = ratingsByPlayer.get(status.playerId);
            if (rating == null) continue;
            A3_Halls.Hall hall = halls.getHallById(status.hallId);
            if (hall == null) continue;
            players.add(new PlayerEloData(hall.hallName, rating.ratingValue, status.capped));
        }
        return players;
    }

    private List<HallRankData> calculateHallRankings(List<PlayerEloData> players) {
        Map<String, List<PlayerEloData>> hallGroups = new HashMap<>();
        for (PlayerEloData player : players) {
            hallGroups.computeIfAbsent(player.hall, k -> new ArrayList<>()).add(player);
        }

        List<HallRankData> hallRankings = new ArrayList<>();
        for (Map.Entry<String, List<PlayerEloData>> entry : hallGroups.entrySet()) {
            List<PlayerEloData> hallPlayers = entry.getValue();
            hallPlayers.sort((p1, p2) -> Double.compare(p2.trueElo, p1.trueElo));

            int count = Math.min(5, hallPlayers.size());
            double sum = 0;
            for (int i = 0; i < count; i++) {
                sum += hallPlayers.get(i).trueElo;
            }
            double average = sum / count;
            int cappedCount = (int) hallPlayers.stream().filter(p -> p.capped).count();

            hallRankings.add(new HallRankData(entry.getKey(), average, count, cappedCount));
        }
        return hallRankings;
    }

    private String formatHallsTable(List<HallRankData> halls, String homeHall) {
        String[] headers = {"Rank", "Hall", "Cap", "Avg Elo"};
        Alignment[] alignments = {Alignment.RIGHT, Alignment.LEFT, Alignment.RIGHT, Alignment.RIGHT};
        int[] columnWidths = {4, 10, 3, 7};

        List<String[]> rows = new ArrayList<>();
        int rank = 1;
        for (HallRankData hall : halls) {
            rows.add(new String[]{String.valueOf(rank), hall.hallName, String.valueOf(hall.cappedCount), String.format("%.1f", hall.averageElo)});
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
                    if (rowIndex < halls.size() && halls.get(rowIndex).hallName.equalsIgnoreCase(homeHall)) {
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

    private static final String[] HALL_TABLE_HEADERS = {"Rank", "Hall", "Cap", "Avg Elo"};
    private static final Alignment[] HALL_TABLE_ALIGNMENTS = {Alignment.RIGHT, Alignment.LEFT, Alignment.RIGHT, Alignment.RIGHT};
    private static final int[] HALL_TABLE_COLUMN_WIDTHS = {4, 10, 3, 7};

    private static List<String[]> buildHallRows(List<HallRankData> hallRankings, List<String> hallNamesOut) {
        List<String[]> rows = new ArrayList<>();
        int rank = 1;
        for (HallRankData hall : hallRankings) {
            rows.add(new String[]{String.valueOf(rank), hall.hallName, String.valueOf(hall.cappedCount), String.format("%.1f", hall.averageElo)});
            if (hallNamesOut != null) hallNamesOut.add(hall.hallName);
            rank++;
        }
        return rows;
    }

    private Path generateHallsImage(List<HallRankData> hallRankings, Set<Integer> highlightRows, String selectedRound, int year) throws Exception {
        List<String> hallNames = new ArrayList<>();
        List<String[]> rows = buildHallRows(hallRankings, hallNames);

        String lastRoundForMetadata;
        if (selectedRound.equalsIgnoreCase("all")) {
            lastRoundForMetadata = MatchScoreUtils.latestRoundLabel(rounds, year);
        } else {
            A1_Rounds.Round round = rounds.getRoundByYearAndOrder(year, Integer.parseInt(selectedRound));
            lastRoundForMetadata = round != null ? round.roundLabel : selectedRound;
        }

        TableImageGenerator.ImageMetadata metadata = new TableImageGenerator.ImageMetadata(
            "Hall Rankings", "Halls ranked by average\nTrueElo (top 5 players)", lastRoundForMetadata);

        String entityName = lastRoundForMetadata != null ? lastRoundForMetadata : "unknown";
        return TableImageGenerator.generateHallTable(HALL_TABLE_HEADERS, rows, hallNames, HALL_TABLE_ALIGNMENTS,
            HALL_TABLE_COLUMN_WIDTHS, metadata, highlightRows, "RankHalls", entityName);
    }

    private Path generateHallsImageAllYears(List<HallRankData> hallRankings, Set<Integer> highlightRows) throws Exception {
        List<String> hallNames = new ArrayList<>();
        List<String[]> rows = buildHallRows(hallRankings, hallNames);

        List<A1_Rounds.Round> allRounds = rounds.getAllRounds();
        String lastRoundForMetadata = allRounds.isEmpty() ? null : allRounds.get(allRounds.size() - 1).roundLabel;

        TableImageGenerator.ImageMetadata metadata = new TableImageGenerator.ImageMetadata(
            "Hall Rankings", "Halls ranked by average\nTrueElo (top 5 players, All Years)", lastRoundForMetadata);

        return TableImageGenerator.generateHallTable(HALL_TABLE_HEADERS, rows, hallNames, HALL_TABLE_ALIGNMENTS,
            HALL_TABLE_COLUMN_WIDTHS, metadata, highlightRows, "RankHalls", "AllYears");
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
