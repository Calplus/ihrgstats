package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.utils.RankingQueryHelper;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
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
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final RankingQueryHelper rankingQueryHelper = new RankingQueryHelper();

    private static final Map<String, PlayerCompareSelectionState> userSelectionStates = new HashMap<>();

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
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
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

        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        for (A3_Halls.Hall hall : allHalls) {
            if (hall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) continue;
            labels.add(hall.hallName);
            callbacks.add("compareplayers_selecthall1_" + hall.id);
        }
        labels.add("\u274C Cancel");
        callbacks.add("compareplayers_cancel");

        String message = "**\uD83D\uDC65 Player Comparison**\n\nSelect the **first player's hall**:";
        return new CompareResponse(message, null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
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
                return new CompareResponse("\u2139\uFE0F No players found in hall " + state.firstHallName + ".", null, null);
            }

            List<String> labels = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            for (B6_PlayerYearStatus.Status status : statuses) {
                String name = playerNames.getNameForYear(status.playerId, year);
                labels.add(name != null ? name : status.playerId);
                callbacks.add("compareplayers_selectplayer1_" + status.playerId);
            }
            labels.add("\u274C Cancel");
            callbacks.add("compareplayers_cancel");

            String message = String.format("**\uD83D\uDC65 Player Comparison**\n\nFirst player's hall: **%s**\nSelect the **first player**:", state.firstHallName);
            return new CompareResponse(message, null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]), 1));
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
            List<String> labels = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            for (A3_Halls.Hall hall : allHalls) {
                if (hall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) continue;
                labels.add(hall.hallName);
                callbacks.add("compareplayers_selecthall2_" + hall.id);
            }
            labels.add("\u274C Cancel");
            callbacks.add("compareplayers_cancel");

            String message = String.format("**\uD83D\uDC65 Player Comparison**\n\nFirst player: **%s** (%s)\nSelect the **second player's hall**:",
                    state.firstPlayerName, state.firstHallName);
            return new CompareResponse(message, null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
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
                return new CompareResponse("\u2139\uFE0F No other players available in hall " + state.secondHallName + ".", null, null);
            }

            List<String> labels = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            for (B6_PlayerYearStatus.Status status : statuses) {
                String name = playerNames.getNameForYear(status.playerId, year);
                labels.add(name != null ? name : status.playerId);
                callbacks.add("compareplayers_selectplayer2_" + status.playerId);
            }
            labels.add("\u274C Cancel");
            callbacks.add("compareplayers_cancel");

            String message = String.format("**\uD83D\uDC65 Player Comparison**\n\nFirst player: **%s** (%s)\nSecond player's hall: **%s**\nSelect the **second player**:",
                    state.firstPlayerName, state.firstHallName, state.secondHallName);
            return new CompareResponse(message, null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]), 1));
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

            List<String> labels = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            labels.add("All Rounds");
            callbacks.add("compareplayers_selectround_all");
            for (A1_Rounds.Round round : availableRounds) {
                labels.add(round.roundLabel);
                callbacks.add("compareplayers_selectround_" + round.roundOrder);
            }
            labels.add("\u274C Cancel");
            callbacks.add("compareplayers_cancel");

            String message = String.format("**\uD83D\uDC65 Player Comparison**\n\nFirst player: **%s** (%s)\nSecond player: **%s** (%s)\n\nSelect rounds to compare:",
                    state.firstPlayerName, state.firstHallName, state.secondPlayerName, state.secondHallName);
            return new CompareResponse(message, null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
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
        Integer year = YearContext.getCurrentYear();
        userSelectionStates.remove(userId);
        if (year == null) return new CompareResponse("\u26A0\uFE0F No current year set.", null, null);

        try {
            return generateComparison(state.firstPlayerId, state.firstPlayerName, state.firstHallName,
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

    /** Per-round player data, keyed by round_order for display. */
    private static class PlayerData {
        String playerId;
        String name;
        String hall;
        Map<Integer, String> roundLabelByOrder = new TreeMap<>();
        Map<Integer, Integer> rankByRound = new TreeMap<>();
        Map<Integer, Integer> eloByRound = new TreeMap<>();
        Map<Integer, Integer> seatByRound = new TreeMap<>();
        Map<Integer, Integer> outcomeByRound = new TreeMap<>();
        Map<Integer, String> oppNameByRound = new TreeMap<>();
        Map<Integer, String> oppHallByRound = new TreeMap<>();
        Map<Integer, Integer> oppEloByRound = new TreeMap<>();
        Map<Integer, Double> scoreByRound = new TreeMap<>();
        Map<Integer, Double> oppScoreByRound = new TreeMap<>();
        Integer lastRoundOrder;
    }

    private PlayerData fetchPlayerData(String playerId, String name, String hall, int year, List<A1_Rounds.Round> roundsToInclude, int trueEloTypeId) throws SQLException {
        PlayerData player = new PlayerData();
        player.playerId = playerId;
        player.name = name != null ? name : playerId;
        player.hall = hall;

        for (A1_Rounds.Round round : roundsToInclude) {
            D11_PlayerRatings.Rating rating = rankingQueryHelper.getPointInTimeRating(playerId, round.id, trueEloTypeId);
            if (rating == null) continue;

            int elo = (int) Math.round(rating.ratingValue);
            player.eloByRound.put(round.roundOrder, elo);
            player.roundLabelByOrder.put(round.roundOrder, round.roundLabel);
            player.lastRoundOrder = round.roundOrder;

            Map<String, D11_PlayerRatings.Rating> allRatings = rankingQueryHelper.getLatestRatingsUpToRound(year, round.roundOrder, trueEloTypeId);
            player.rankByRound.put(round.roundOrder, rankingQueryHelper.calculateRank(allRatings, rating.ratingValue));

            C9_MatchParticipants.Participant me = participants.getParticipantForPlayerAndRound(playerId, round.id);
            if (me != null) {
                if (me.hallSeatNumber != null) player.seatByRound.put(round.roundOrder, me.hallSeatNumber);
                player.outcomeByRound.put(round.roundOrder, VictoryRecordCalculator.toLegacyOutcome(me.outcome));
                player.scoreByRound.put(round.roundOrder, me.score);

                C9_MatchParticipants.Participant opp = participants.getOpponentParticipant(me.matchId, playerId);
                if (opp != null) {
                    player.oppScoreByRound.put(round.roundOrder, opp.score);
                    if (opp.playerId.equals(B4_Players.WALKOVER_PLAYER_ID)) {
                        player.oppNameByRound.put(round.roundOrder, "WALKOVER");
                    } else {
                        String oppName = playerNames.getNameForYear(opp.playerId, year);
                        player.oppNameByRound.put(round.roundOrder, oppName != null ? oppName : opp.playerId);
                        D11_PlayerRatings.Rating oppRating = rankingQueryHelper.getPointInTimeRating(opp.playerId, round.id, trueEloTypeId);
                        if (oppRating != null) {
                            player.oppEloByRound.put(round.roundOrder, (int) Math.round(oppRating.ratingValue));
                        }
                    }
                    A3_Halls.Hall oppHall = halls.getHallById(opp.hallId);
                    if (oppHall != null && !oppHall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) {
                        player.oppHallByRound.put(round.roundOrder, oppHall.hallName);
                    }
                }
            }
        }

        return player;
    }

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

        PlayerData data1 = fetchPlayerData(player1Id, player1Name, player1Hall, year, roundsToInclude, trueEloTypeId);
        PlayerData data2 = fetchPlayerData(player2Id, player2Name, player2Hall, year, roundsToInclude, trueEloTypeId);

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

    private String generateTextOutput(PlayerData player1, List<Integer> orders1, PlayerData player2, List<Integer> orders2) {
        StringBuilder sb = new StringBuilder();
        sb.append("**\uD83D\uDC65 Player Comparison**\n\n");
        sb.append(String.format("**%s** (%s) vs **%s** (%s)\n\n", player1.name, player1.hall, player2.name, player2.hall));
        sb.append(generatePlayerDetails(player1, orders1));
        sb.append("\n");
        sb.append(generatePlayerDetails(player2, orders2));
        return sb.toString();
    }

    private String generatePlayerDetails(PlayerData player, List<Integer> roundOrders) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\u2501\u2501\u2501 **%s (%s)** \u2501\u2501\u2501\n\n", player.name, player.hall));

        sb.append("**\uD83D\uDCCA Stats Per Round:**\n```\n");
        sb.append(String.format("%-4s %-6s %-10s %-6s %-10s\n", "Rnd", "Rank", "\u0394Rank", "ELO", "\u0394ELO"));
        sb.append(String.format("%-4s %-6s %-10s %-6s %-10s\n", "----", "------", "----------", "------", "----------"));

        Integer prevRank = null;
        Integer prevElo = null;
        for (int order : roundOrders) {
            Integer rank = player.rankByRound.get(order);
            Integer elo = player.eloByRound.get(order);
            if (rank == null || elo == null) continue;
            String deltaRank = prevRank == null ? "-" : deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : deltaString(elo - prevElo);
            sb.append(String.format("%-4s %-6d %-10s %-6d %-10s\n", player.roundLabelByOrder.get(order), rank, deltaRank, elo, deltaElo));
            prevRank = rank;
            prevElo = elo;
        }
        sb.append("```\n\n");

        sb.append("**\uD83E\uDE91 Seating Arrangement:**\n```\n");
        StringBuilder roundsLine = new StringBuilder("Rnd: ");
        StringBuilder seatsLine = new StringBuilder("Seat:");
        for (int order : roundOrders) {
            roundsLine.append(String.format("%-4s", player.roundLabelByOrder.get(order))).append("|");
            Integer seat = player.seatByRound.get(order);
            seatsLine.append(String.format(" %-3s", seat != null ? String.valueOf(seat) : "-")).append("|");
        }
        sb.append(roundsLine).append("\n").append(seatsLine).append("\n```\n\n");

        sb.append("**\uD83C\uDFC6 Victory Record:**\n```\n");
        for (int order : roundOrders) {
            String roundName = player.roundLabelByOrder.get(order);
            Integer outcome = player.outcomeByRound.get(order);
            if (outcome == null) {
                if (player.eloByRound.containsKey(order)) {
                    sb.append(String.format("%-3s  -NA-\n", roundName));
                }
                continue;
            }

            String oppName = player.oppNameByRound.get(order);
            String oppHall = player.oppHallByRound.get(order);
            Integer playerElo = player.eloByRound.get(order);
            Integer oppElo = player.oppEloByRound.get(order);
            String playerEloStr = playerElo != null ? String.valueOf(playerElo) : "?";
            String oppEloStr = oppElo != null ? String.valueOf(oppElo) : "?";

            String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(outcome);
            Integer oppOutcome = outcome == 0 ? 0 : -outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);

            String playerHallFormatted = TableFormatter.shortenHallName(player.hall);
            String oppHallFormatted;
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                oppHallFormatted = "";
                oppEloStr = "-";
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
            } else {
                oppHallFormatted = oppHall != null ? TableFormatter.shortenHallName(oppHall) : "??";
            }

            String score = formatScorePair(player.scoreByRound.get(order), player.oppScoreByRound.get(order));

            String line = String.format("%-3s %s %-2s %-4s %-16s %s %-16s %-4s %-2s %s",
                    roundName, hallEmoji, playerHallFormatted, playerEloStr, player.name, score,
                    oppName != null ? oppName : "?", oppEloStr, oppHallFormatted, oppEmoji);
            sb.append(line).append("\n");
        }
        sb.append("```\n\n");

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

        String description = String.format("%s (%s) vs %s (%s)", player1.name, player1.hall, player2.name, player2.hall);
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

        List<String> statsLines = new ArrayList<>();
        statsLines.add(String.format("%-4s %-6s %-10s %-6s %-10s", "Rnd", "Rank", "\u0394Rank", "ELO", "\u0394ELO"));
        Integer prevRank = null;
        Integer prevElo = null;
        for (int order : roundOrders) {
            Integer rank = player.rankByRound.get(order);
            Integer elo = player.eloByRound.get(order);
            if (rank == null || elo == null) continue;
            String deltaRank = prevRank == null ? "-" : deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : deltaString(elo - prevElo);
            statsLines.add(String.format("%-4s %-6d %-10s %-6d %-10s", player.roundLabelByOrder.get(order), rank, deltaRank, elo, deltaElo));
            prevRank = rank;
            prevElo = elo;
        }
        sections.add(new ComparisonImageGenerator.Section("Stats Per Round", statsLines));

        List<String> seatLines = new ArrayList<>();
        StringBuilder seatHeader = new StringBuilder("Rnd: ");
        StringBuilder seatData = new StringBuilder("Seat:");
        for (int order : roundOrders) {
            seatHeader.append(String.format("%-3s|", player.roundLabelByOrder.get(order)));
            Integer seat = player.seatByRound.get(order);
            seatData.append(String.format("%-3s|", seat != null ? seat : "-"));
        }
        seatLines.add(seatHeader.toString());
        seatLines.add(seatData.toString());
        sections.add(new ComparisonImageGenerator.Section("Seating", seatLines));

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

            String score = formatScorePair(player.scoreByRound.get(order), player.oppScoreByRound.get(order));

            ComparisonImageGenerator.PlayerVictoryEntry entry = new ComparisonImageGenerator.PlayerVictoryEntry(
                    roundName, hallEmoji, playerHallFormatted, playerEloStr, player.name, score,
                    oppName != null ? oppName : "?", oppEloStr, oppHallFormatted, oppEmoji, outcome, oppOutcome);
            victoryEntries.add(entry);
        }
        sections.add(ComparisonImageGenerator.Section.forPlayerVictory("Victory Record", victoryEntries));

        return sections;
    }

    private static String deltaString(int change) {
        if (change > 0) return "+" + change;
        if (change < 0) return "-" + Math.abs(change);
        return "=";
    }

    private String formatScorePair(Double myScore, Double oppScore) {
        String myStr = myScore != null ? VictoryRecordCalculator.formatScore(myScore) : "?";
        String oppStr = oppScore != null ? VictoryRecordCalculator.formatScore(oppScore) : "0";
        return myStr + "-" + oppStr;
    }
}
