package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.PlayerStatsBuilder;
import com.calplus.ihrgstats.telegrambot.utils.PlayerStatsBuilder.PlayerData;
import com.calplus.ihrgstats.telegrambot.utils.PlayerStatsBuilder.YearSummary;
import com.calplus.ihrgstats.telegrambot.utils.SelectionKeyboards;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;
import com.calplus.ihrgstats.utils.TelegramHtml;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Command handler for /compareplayers command.
 * Allows comparison of two players with detailed statistics, scoped to the
 * current year (settings.currentYear).
 */
public class CommandComparePlayers {
    private final LogHelper logHelper;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final PlayerStatsBuilder playerStatsBuilder = new PlayerStatsBuilder();

    private static final Map<String, PlayerCompareSelectionState> userSelectionStates = new ConcurrentHashMap<>();

    private static class PlayerCompareSelectionState extends SelectionState {
        int firstHallId;
        String firstHallName;
        String firstPlayerId;
        String firstPlayerName;
        int secondHallId;
        String secondHallName;
        String secondPlayerId;
        String secondPlayerName;
    }

    public CommandComparePlayers() {
        EnvironmentManager.ensureSystemPropertiesLoaded();
        this.logHelper = new LogHelper();
    }

    public static class CompareResponse extends CommandResponse {
        public CompareResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            super(message, imagePath, buttonConfig);
        }
    }

    public CompareResponse handleCommand(String userId) {
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested /compareplayers command", userInfo));

        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.put(userId, new PlayerCompareSelectionState());

        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            return new CompareResponse("\u26A0\uFE0F No current year set. An admin must set `settings.currentYear` first.", null, null);
        }

        List<A3_Halls.Hall> allHalls;
        try {
            allHalls = halls.getAllHalls();
        } catch (SQLException e) {
            logHelper.logError("Database error fetching halls: " + e.getMessage());
            return new CompareResponse("\u274C Database error fetching halls.", null, null);
        }

        String message = "**\uD83D\uDC65 Player Comparison**\n\nSelect the **first player's hall**:";
        return new CompareResponse(message, null, SelectionKeyboards.hallButtons(allHalls, "compareplayers_selecthall1_", "compareplayers_cancel"));
    }

    public CompareResponse handleFirstHallSelection(String userId, int firstHallId) {
        PlayerCompareSelectionState state = userSelectionStates.getOrDefault(userId, new PlayerCompareSelectionState());
        state.firstHallId = firstHallId;
        userSelectionStates.put(userId, state);

        Integer year = YearContext.getCurrentYear();
        if (year == null) return new CompareResponse("\u26A0\uFE0F No current year set.", null, null);

        try {
            A3_Halls.Hall hall = halls.getHallById(firstHallId);
            state.firstHallName = hall != null ? hall.hallName : "?";

            List<B6_PlayerYearStatus.Status> statuses = playerYearStatus.getStatusesForHallAndYear(firstHallId, year);
            if (statuses.isEmpty()) {
                userSelectionStates.remove(userId);
                return new CompareResponse("\u2139\uFE0F No players found in hall " + VictoryRecordCalculator.formatHallName(state.firstHallName) + ".", null, null);
            }

            Integer nameYear = year;
            String message = String.format("**\uD83D\uDC65 Player Comparison**\n\nFirst player's hall: **%s**\nSelect the **first player**:", VictoryRecordCalculator.formatHallName(state.firstHallName));
            return new CompareResponse(message, null, SelectionKeyboards.playerButtons(statuses, pid -> {
                String name = playerNames.getNameForYear(pid, nameYear);
                return name != null ? name : pid;
            }, "compareplayers_selectplayer1_", "compareplayers_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new CompareResponse("\u274C Database error.", null, null);
        }
    }

    public CompareResponse handleFirstPlayerSelection(String userId, String firstPlayerId) {
        PlayerCompareSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstHallName == null) {
            return new CompareResponse("\u274C Session expired. Please use /compareplayers to start again.", null, null);
        }
        state.firstPlayerId = firstPlayerId;

        Integer year = YearContext.getCurrentYear();
        if (year == null) return new CompareResponse("\u26A0\uFE0F No current year set.", null, null);

        try {
            state.firstPlayerName = playerNames.getNameForYear(firstPlayerId, year);

            List<A3_Halls.Hall> allHalls = halls.getAllHalls();
            String message = String.format("**\uD83D\uDC65 Player Comparison**\n\nFirst player: **%s** (%s)\nSelect the **second player's hall**:",
                    TelegramHtml.escape(state.firstPlayerName), VictoryRecordCalculator.formatHallName(state.firstHallName));
            return new CompareResponse(message, null, SelectionKeyboards.hallButtons(allHalls, "compareplayers_selecthall2_", "compareplayers_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new CompareResponse("\u274C Database error.", null, null);
        }
    }

    public CompareResponse handleSecondHallSelection(String userId, int secondHallId) {
        PlayerCompareSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstPlayerId == null) {
            return new CompareResponse("\u274C Session expired. Please use /compareplayers to start again.", null, null);
        }
        state.secondHallId = secondHallId;

        Integer year = YearContext.getCurrentYear();
        if (year == null) return new CompareResponse("\u26A0\uFE0F No current year set.", null, null);

        try {
            A3_Halls.Hall hall = halls.getHallById(secondHallId);
            state.secondHallName = hall != null ? hall.hallName : "?";

            List<B6_PlayerYearStatus.Status> statuses = playerYearStatus.getStatusesForHallAndYear(secondHallId, year);
            statuses.removeIf(s -> s.playerId.equals(state.firstPlayerId));

            if (statuses.isEmpty()) {
                userSelectionStates.remove(userId);
                return new CompareResponse("\u2139\uFE0F No other players available in hall " + VictoryRecordCalculator.formatHallName(state.secondHallName) + ".", null, null);
            }

            Integer nameYear = year;
            String message = String.format("**\uD83D\uDC65 Player Comparison**\n\nFirst player: **%s** (%s)\nSecond player's hall: **%s**\nSelect the **second player**:",
                    TelegramHtml.escape(state.firstPlayerName), VictoryRecordCalculator.formatHallName(state.firstHallName), VictoryRecordCalculator.formatHallName(state.secondHallName));
            return new CompareResponse(message, null, SelectionKeyboards.playerButtons(statuses, pid -> {
                String name = playerNames.getNameForYear(pid, nameYear);
                return name != null ? name : pid;
            }, "compareplayers_selectplayer2_", "compareplayers_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new CompareResponse("\u274C Database error.", null, null);
        }
    }

    public CompareResponse handleSecondPlayerSelection(String userId, String secondPlayerId) {
        PlayerCompareSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstPlayerId == null || state.secondHallName == null) {
            return new CompareResponse("\u274C Session expired. Please use /compareplayers to start again.", null, null);
        }
        state.secondPlayerId = secondPlayerId;

        Integer year = YearContext.getCurrentYear();
        if (year == null) return new CompareResponse("\u26A0\uFE0F No current year set.", null, null);

        try {
            state.secondPlayerName = playerNames.getNameForYear(secondPlayerId, year);

            List<A1_Rounds.Round> availableRounds = rounds.getRoundsForYear(year);
            if (availableRounds.isEmpty()) {
                userSelectionStates.remove(userId);
                return new CompareResponse("\u2139\uFE0F No round data available for " + year + ".", null, null);
            }

            String message = String.format("**\uD83D\uDC65 Player Comparison**\n\nFirst player: **%s** (%s)\nSecond player: **%s** (%s)\n\nSelect rounds to compare:",
                    TelegramHtml.escape(state.firstPlayerName), VictoryRecordCalculator.formatHallName(state.firstHallName),
                    TelegramHtml.escape(state.secondPlayerName), VictoryRecordCalculator.formatHallName(state.secondHallName));
            return new CompareResponse(message, null, SelectionKeyboards.roundButtons(availableRounds, "compareplayers_selectround_", "compareplayers_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new CompareResponse("\u274C Database error.", null, null);
        }
    }

    public CompareResponse handleRoundSelection(String userId, String selectedRound) {
        PlayerCompareSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstPlayerId == null || state.secondPlayerId == null) {
            return new CompareResponse("\u274C Session expired. Please use /compareplayers to start again.", null, null);
        }
        userSelectionStates.remove(userId);

        boolean allYears = selectedRound.equalsIgnoreCase("allyears");
        Integer year = null;
        if (!allYears) {
            year = YearContext.getCurrentYear();
            if (year == null) return new CompareResponse("\u26A0\uFE0F No current year set.", null, null);
        }

        try {
            return allYears
                ? generateComparisonAllYears(state.firstPlayerId, state.firstPlayerName, state.firstHallName,
                        state.secondPlayerId, state.secondPlayerName, state.secondHallName)
                : generateComparison(state.firstPlayerId, state.firstPlayerName, state.firstHallName,
                        state.secondPlayerId, state.secondPlayerName, state.secondHallName, year, selectedRound);
        } catch (Exception e) {
            logHelper.logError("Player comparison error: " + e.getMessage());
            e.printStackTrace();
            return new CompareResponse("\u274C Error generating comparison: " + e.getMessage(), null, null);
        }
    }

    public CompareResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new CompareResponse("\u2139\uFE0F Player comparison cancelled.", null, null);
    }

    // PlayerData / YearSummary now live in the shared PlayerStatsBuilder
    // (imported by name above) - /infoplayer renders from the same carriers.

    private CompareResponse generateComparison(String player1Id, String player1Name, String player1Hall,
                                                String player2Id, String player2Name, String player2Hall,
                                                int year, String selectedRound) throws Exception {
        List<A1_Rounds.Round> availableRounds = rounds.getRoundsForYear(year);
        int selectedOrder = selectedRound.equalsIgnoreCase("all") ? Integer.MAX_VALUE : Integer.parseInt(selectedRound);
        List<A1_Rounds.Round> roundsToInclude = availableRounds.stream()
                .filter(r -> r.roundOrder <= selectedOrder)
                .collect(Collectors.toList());

        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
        }

        PlayerData data1 = playerStatsBuilder.fetchPlayerData(player1Id, player1Name, player1Hall, year, roundsToInclude, trueEloTypeId);
        PlayerData data2 = playerStatsBuilder.fetchPlayerData(player2Id, player2Name, player2Hall, year, roundsToInclude, trueEloTypeId);

        if (data1.eloByRound.isEmpty()) throw new Exception("Player " + data1.name + " has no data for " + year);
        if (data2.eloByRound.isEmpty()) throw new Exception("Player " + data2.name + " has no data for " + year);

        List<Integer> orders1 = new ArrayList<>(data1.roundLabelByOrder.keySet());
        List<Integer> orders2 = new ArrayList<>(data2.roundLabelByOrder.keySet());

        String textOutput = generateTextOutput(data1, orders1, data2, orders2);
        Path imagePath = generateImage(data1, orders1, data2, orders2);

        logHelper.logSuccess(String.format("Generated player comparison: %s (%s) vs %s (%s) (rounds: %s)",
                data1.name, data1.hall, data2.name, data2.hall, selectedRound));

        return new CompareResponse(textOutput, imagePath, null);
    }

    private CompareResponse generateComparisonAllYears(String player1Id, String player1Name, String player1Hall,
                                                         String player2Id, String player2Name, String player2Hall) throws Exception {
        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
        }

        List<YearSummary> summaries1 = playerStatsBuilder.buildYearSummaries(player1Id, player1Name, player1Hall, trueEloTypeId);
        List<YearSummary> summaries2 = playerStatsBuilder.buildYearSummaries(player2Id, player2Name, player2Hall, trueEloTypeId);

        if (summaries1.isEmpty()) throw new Exception("Player " + player1Name + " has no data for any year");
        if (summaries2.isEmpty()) throw new Exception("Player " + player2Name + " has no data for any year");

        String textOutput = generateTextOutputAllYears(player1Name, player1Hall, summaries1, player2Name, player2Hall, summaries2);
        Path imagePath = generateImageAllYears(player1Name, player1Hall, summaries1, player2Name, player2Hall, summaries2);

        logHelper.logSuccess(String.format("Generated player comparison: %s (%s) vs %s (%s) (All Years)", player1Name, player1Hall, player2Name, player2Hall));
        return new CompareResponse(textOutput, imagePath, null);
    }

    private String generateTextOutputAllYears(String player1Name, String player1Hall, List<YearSummary> summaries1,
                                               String player2Name, String player2Hall, List<YearSummary> summaries2) {
        StringBuilder sb = new StringBuilder();
        sb.append("**👥 Player Comparison (All Years)**\n\n");
        sb.append(String.format("**%s** (%s) vs **%s** (%s)\n\n", TelegramHtml.escape(player1Name), VictoryRecordCalculator.formatHallName(player1Hall), TelegramHtml.escape(player2Name), VictoryRecordCalculator.formatHallName(player2Hall)));
        sb.append(generatePlayerDetailsAllYears(player1Name, player1Hall, summaries1));
        sb.append("\n");
        sb.append(generatePlayerDetailsAllYears(player2Name, player2Hall, summaries2));
        return sb.toString();
    }

    private String generatePlayerDetailsAllYears(String playerName, String hallName, List<YearSummary> yearSummaries) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("━━━ **%s (%s)** ━━━\n\n", TelegramHtml.escape(playerName), VictoryRecordCalculator.formatHallName(hallName)));
        PlayerStatsBuilder.appendYearSummaryBlocks(sb, yearSummaries);
        sb.append("\n\n");
        return sb.toString();
    }

    private Path generateImageAllYears(String player1Name, String player1Hall, List<YearSummary> summaries1,
                                        String player2Name, String player2Hall, List<YearSummary> summaries2) throws Exception {
        String description = String.format("%s (%s) vs %s (%s) - All Years", player1Name, VictoryRecordCalculator.formatHallName(player1Hall), player2Name, VictoryRecordCalculator.formatHallName(player2Hall));
        String lastYearLabel = summaries1.isEmpty() ? null : String.valueOf(summaries1.get(summaries1.size() - 1).year);
        ComparisonImageGenerator.ImageMetadata metadata = new ComparisonImageGenerator.ImageMetadata("Player Comparison", description, lastYearLabel);

        List<ComparisonImageGenerator.Section> sections1 = buildSectionsAllYears(summaries1);
        List<ComparisonImageGenerator.Section> sections2 = buildSectionsAllYears(summaries2);

        ComparisonImageGenerator.ComparisonData data1 = new ComparisonImageGenerator.ComparisonData(player1Name, player1Hall, sections1);
        ComparisonImageGenerator.ComparisonData data2 = new ComparisonImageGenerator.ComparisonData(player2Name, player2Hall, sections2);

        return ComparisonImageGenerator.generateComparisonImage(metadata.title, data1, data2, metadata,
                "ComparePlayers", player1Name, player2Name);
    }

    private List<ComparisonImageGenerator.Section> buildSectionsAllYears(List<YearSummary> yearSummaries) {
        List<ComparisonImageGenerator.Section> sections = new ArrayList<>();

        List<String> statsLines = new ArrayList<>();
        statsLines.add(String.format("%-6s %-6s %-10s %-6s %-10s", "Year", "Rank", "ΔRank", "ELO", "ΔELO"));
        statsLines.addAll(PlayerStatsBuilder.statsPerYearLines(yearSummaries));
        sections.add(new ComparisonImageGenerator.Section("Stats Per Year", statsLines));

        sections.add(new ComparisonImageGenerator.Section("Seating (Avg by Yr)", PlayerStatsBuilder.avgSeatByYearLines(yearSummaries)));
        sections.add(new ComparisonImageGenerator.Section("Season Record", PlayerStatsBuilder.seasonRecordLines(yearSummaries)));

        return sections;
    }

    private String generateTextOutput(PlayerData player1, List<Integer> orders1, PlayerData player2, List<Integer> orders2) {
        StringBuilder sb = new StringBuilder();
        sb.append("**\uD83D\uDC65 Player Comparison**\n\n");
        sb.append(String.format("**%s** (%s) vs **%s** (%s)\n\n", TelegramHtml.escape(player1.name), VictoryRecordCalculator.formatHallName(player1.hall), TelegramHtml.escape(player2.name), VictoryRecordCalculator.formatHallName(player2.hall)));
        sb.append(generatePlayerDetails(player1, orders1));
        sb.append("\n");
        sb.append(generatePlayerDetails(player2, orders2));
        return sb.toString();
    }

    private String generatePlayerDetails(PlayerData player, List<Integer> roundOrders) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\u2501\u2501\u2501 **%s (%s)** \u2501\u2501\u2501\n\n", TelegramHtml.escape(player.name), VictoryRecordCalculator.formatHallName(player.hall)));
        PlayerStatsBuilder.appendPlayerDetailBlocks(sb, player, roundOrders);
        sb.append("\n\n");
        return sb.toString();
    }

    private Path generateImage(PlayerData player1, List<Integer> orders1, PlayerData player2, List<Integer> orders2) throws Exception {
        String lastRoundLabel = null;
        if (player1.lastRoundOrder != null) lastRoundLabel = player1.roundLabelByOrder.get(player1.lastRoundOrder);
        if (player2.lastRoundOrder != null) {
            int p1Order = player1.lastRoundOrder != null ? player1.lastRoundOrder : -1;
            if (player2.lastRoundOrder > p1Order) {
                lastRoundLabel = player2.roundLabelByOrder.get(player2.lastRoundOrder);
            }
        }

        String description = String.format("%s (%s) vs %s (%s)", player1.name, VictoryRecordCalculator.formatHallName(player1.hall), player2.name, VictoryRecordCalculator.formatHallName(player2.hall));
        ComparisonImageGenerator.ImageMetadata metadata = new ComparisonImageGenerator.ImageMetadata("Player Comparison", description, lastRoundLabel);

        List<ComparisonImageGenerator.Section> sections1 = buildSections(player1, orders1);
        List<ComparisonImageGenerator.Section> sections2 = buildSections(player2, orders2);

        ComparisonImageGenerator.ComparisonData data1 = new ComparisonImageGenerator.ComparisonData(player1.name, player1.hall, sections1);
        ComparisonImageGenerator.ComparisonData data2 = new ComparisonImageGenerator.ComparisonData(player2.name, player2.hall, sections2);

        return ComparisonImageGenerator.generateComparisonImage(metadata.title, data1, data2, metadata,
                "ComparePlayers", player1.name, player2.name);
    }

    private List<ComparisonImageGenerator.Section> buildSections(PlayerData player, List<Integer> roundOrders) {
        List<ComparisonImageGenerator.Section> sections = new ArrayList<>();

        sections.add(new ComparisonImageGenerator.Section("Stats Per Round", PlayerStatsBuilder.statsPerRoundLines(player, roundOrders)));
        sections.add(new ComparisonImageGenerator.Section("Seating", PlayerStatsBuilder.seatingLines(player, roundOrders)));

        List<ComparisonImageGenerator.PlayerVictoryEntry> victoryEntries = new ArrayList<>();
        for (int order : roundOrders) {
            String roundName = player.roundLabelByOrder.get(order);
            Integer outcome = player.outcomeByRound.get(order);
            if (outcome == null) {
                if (player.eloByRound.containsKey(order)) {
                    victoryEntries.add(new ComparisonImageGenerator.PlayerVictoryEntry(roundName, true));
                }
                continue;
            }

            String oppName = player.oppNameByRound.get(order);
            String oppHall = player.oppHallByRound.get(order);
            String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(outcome);
            Integer oppOutcome = outcome == 0 ? 0 : -outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);

            String playerHallFormatted = TableFormatter.shortenHallName(player.hall);
            String oppHallFormatted = oppHall != null ? TableFormatter.shortenHallName(oppHall) : "??";

            Integer playerElo = player.eloByRound.get(order);
            String playerEloStr = playerElo != null ? String.valueOf(playerElo) : "?";
            Integer oppElo = player.oppEloByRound.get(order);
            String oppEloStr = oppElo != null ? String.valueOf(oppElo) : "?";
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
                oppEloStr = "-";
            }

            String score = VictoryRecordCalculator.formatScorePair(player.scoreByRound.get(order), player.oppScoreByRound.get(order), Boolean.TRUE.equals(player.selfTimeoutByRound.get(order)), Boolean.TRUE.equals(player.oppTimeoutByRound.get(order)));

            ComparisonImageGenerator.PlayerVictoryEntry entry = new ComparisonImageGenerator.PlayerVictoryEntry(
                    roundName, hallEmoji, playerHallFormatted, playerEloStr, player.name, score,
                    oppName != null ? oppName : "?", oppEloStr, oppHallFormatted, oppEmoji, outcome, oppOutcome);
            victoryEntries.add(entry);
        }
        sections.add(ComparisonImageGenerator.Section.forPlayerVictory("Victory Record", victoryEntries));

        return sections;
    }

}
