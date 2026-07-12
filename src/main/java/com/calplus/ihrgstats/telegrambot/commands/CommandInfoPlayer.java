package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.RankingQueryHelper;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Command handler for /infoplayer command.
 * Shows detailed information for a single player, scoped to the current
 * year (settings.currentYear).
 */
public class CommandInfoPlayer {
    private final LogHelper logHelper;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final RankingQueryHelper rankingQueryHelper = new RankingQueryHelper();

    private static final Map<String, PlayerInfoSelectionState> userSelectionStates = new HashMap<>();

    private static class PlayerInfoSelectionState extends SelectionState {
        int hallId;
        String hallName;
        String playerId;
        String playerName;
    }

    public CommandInfoPlayer() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        this.logHelper = new LogHelper();
    }

    public static class InfoResponse extends CommandResponse {
        public InfoResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            super(message, imagePath, buttonConfig);
        }
    }

    public InfoResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("%s requested /infoplayer command", com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId)));

        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.put(userId, new PlayerInfoSelectionState());

        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            return new InfoResponse("⚠️ No current year set. An admin must set `settings.currentYear` first.", (Path) null, null);
        }

        List<A3_Halls.Hall> allHalls;
        try {
            allHalls = halls.getAllHalls();
        } catch (SQLException e) {
            logHelper.logError("Database error fetching halls: " + e.getMessage());
            return new InfoResponse("❌ Database error fetching halls.", (Path) null, null);
        }

        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        for (A3_Halls.Hall hall : allHalls) {
            if (hall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) continue;
            labels.add(hall.hallName);
            callbacks.add("infoplayer_hall_" + hall.id);
        }
        labels.add("❌ Cancel");
        callbacks.add("infoplayer_cancel");

        String message = "**👤 Player Information**\n\nSelect the **player's hall**:";
        return new InfoResponse(message, (Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }

    public InfoResponse handleHallSelection(String userId, int hallId) {
        PlayerInfoSelectionState state = userSelectionStates.getOrDefault(userId, new PlayerInfoSelectionState());
        state.hallId = hallId;
        userSelectionStates.put(userId, state);

        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            return new InfoResponse("⚠️ No current year set.", (Path) null, null);
        }

        try {
            A3_Halls.Hall hall = halls.getHallById(hallId);
            state.hallName = hall != null ? hall.hallName : "?";

            List<B6_PlayerYearStatus.Status> statuses = playerYearStatus.getStatusesForHallAndYear(hallId, year);
            if (statuses.isEmpty()) {
                userSelectionStates.remove(userId);
                return new InfoResponse(String.format("ℹ️ No active players found in %s for %d.", state.hallName, year), (Path) null, null);
            }

            List<String> labels = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            for (B6_PlayerYearStatus.Status status : statuses) {
                String name = playerNames.getNameForYear(status.playerId, year);
                labels.add(name != null ? name : status.playerId);
                callbacks.add("infoplayer_player_" + status.playerId);
            }
            labels.add("❌ Cancel");
            callbacks.add("infoplayer_cancel");

            String message = String.format("**👤 Player Information**\n\nHall: **%s**\nSelect the **player**:", state.hallName);
            return new InfoResponse(message, (Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]), 1));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new InfoResponse("❌ Database error.", (Path) null, null);
        }
    }

    public InfoResponse handlePlayerSelection(String userId, String playerId) {
        PlayerInfoSelectionState state = userSelectionStates.get(userId);
        if (state == null) {
            return new InfoResponse("❌ Session expired. Please use /infoplayer to start again.", (Path) null, null);
        }
        state.playerId = playerId;

        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            return new InfoResponse("⚠️ No current year set.", (Path) null, null);
        }

        try {
            state.playerName = playerNames.getNameForYear(playerId, year);

            List<A1_Rounds.Round> availableRounds = rounds.getRoundsForYear(year);
            if (availableRounds.isEmpty()) {
                userSelectionStates.remove(userId);
                return new InfoResponse("ℹ️ No round data available for " + year + ".", (Path) null, null);
            }

            List<String> labels = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            labels.add("All Rounds");
            callbacks.add("infoplayer_round_all");
            for (A1_Rounds.Round round : availableRounds) {
                labels.add(round.roundLabel);
                callbacks.add("infoplayer_round_" + round.roundOrder);
            }
            labels.add("❌ Cancel");
            callbacks.add("infoplayer_cancel");

            String message = String.format("**👤 Player Information**\n\nPlayer: **%s** (%s)\n\nSelect rounds to display:",
                    TelegramHtml.escape(state.playerName), state.hallName);
            return new InfoResponse(message, (Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new InfoResponse("❌ Database error.", (Path) null, null);
        }
    }

    public InfoResponse handleRoundSelection(String userId, String selectedRound) {
        PlayerInfoSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.playerId == null) {
            return new InfoResponse("❌ Session expired. Please use /infoplayer to start again.", (Path) null, null);
        }
        Integer year = YearContext.getCurrentYear();
        userSelectionStates.remove(userId);
        if (year == null) {
            return new InfoResponse("⚠️ No current year set.", (Path) null, null);
        }

        try {
            return generatePlayerInfo(state.playerId, state.playerName, state.hallName, year, selectedRound);
        } catch (Exception e) {
            logHelper.logError("Player info error: " + e.getMessage());
            e.printStackTrace();
            return new InfoResponse("❌ Error generating player info: " + e.getMessage(), (Path) null, null);
        }
    }

    public InfoResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new InfoResponse("ℹ️ Player information request cancelled.", (Path) null, null);
    }

    /** Per-round player data, keyed by round_order for display. */
    static class PlayerData {
        String name;
        String hall;
        Map<Integer, String> roundLabelByOrder = new TreeMap<>();
        Map<Integer, Integer> rankByRound = new TreeMap<>();
        Map<Integer, Integer> eloByRound = new TreeMap<>();
        Map<Integer, Integer> seatByRound = new TreeMap<>();
        Map<Integer, Integer> outcomeByRound = new TreeMap<>(); // legacy 1/0/-1 convention
        Map<Integer, String> oppNameByRound = new TreeMap<>();
        Map<Integer, String> oppHallByRound = new TreeMap<>();
        Map<Integer, Integer> oppEloByRound = new TreeMap<>();
        Map<Integer, Double> scoreByRound = new TreeMap<>();
        Map<Integer, Double> oppScoreByRound = new TreeMap<>();
        Map<Integer, Boolean> selfTimeoutByRound = new TreeMap<>();
        Map<Integer, Boolean> oppTimeoutByRound = new TreeMap<>();
        Integer lastRoundOrder;
    }

    private InfoResponse generatePlayerInfo(String playerId, String playerName, String hallName, int year, String selectedRound) throws Exception {
        List<A1_Rounds.Round> availableRounds = rounds.getRoundsForYear(year);
        int selectedOrder = selectedRound.equalsIgnoreCase("all") ? Integer.MAX_VALUE : Integer.parseInt(selectedRound);
        List<A1_Rounds.Round> roundsToInclude = availableRounds.stream()
                .filter(r -> r.roundOrder <= selectedOrder)
                .collect(Collectors.toList());

        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
        }

        PlayerData player = new PlayerData();
        player.name = playerName != null ? playerName : playerId;
        player.hall = hallName;

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
                player.selfTimeoutByRound.put(round.roundOrder, C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(me.participationType));

                C9_MatchParticipants.Participant opp = participants.getOpponentParticipant(me.matchId, playerId);
                if (opp != null) {
                    player.oppScoreByRound.put(round.roundOrder, opp.score);
                    player.oppTimeoutByRound.put(round.roundOrder, C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(opp.participationType));
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

        if (player.eloByRound.isEmpty()) {
            throw new IllegalStateException("Player " + player.name + " has no data for " + year);
        }

        List<Integer> roundOrders = new ArrayList<>(player.roundLabelByOrder.keySet());

        String textOutput = generateTextOutput(player, roundOrders);
        Path imagePath = generateImage(player, roundOrders, selectedRound);

        logHelper.logSuccess(String.format("Generated player info: %s (%s) (rounds: %s)", player.name, player.hall, selectedRound));
        return new InfoResponse(textOutput, imagePath, null);
    }

    private String generateTextOutput(PlayerData player, List<Integer> roundOrders) {
        StringBuilder sb = new StringBuilder();
        sb.append("**👤 Player Information**\n\n");
        sb.append(String.format("**%s** (%s)\n\n", TelegramHtml.escape(player.name), player.hall));

        sb.append("**📊 Stats Per Round:**\n```\n");
        sb.append(String.format("%-4s %-6s %-10s %-6s %-10s\n", "Rnd", "Rank", "ΔRank", "ELO", "ΔELO"));
        sb.append(String.format("%-4s %-6s %-10s %-6s %-10s\n", "----", "------", "----------", "------", "----------"));

        Integer prevRank = null;
        Integer prevElo = null;
        for (int order : roundOrders) {
            Integer rank = player.rankByRound.get(order);
            Integer elo = player.eloByRound.get(order);
            if (rank == null || elo == null) continue;

            String deltaRank = prevRank == null ? "-" : deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : deltaString(elo - prevElo);

            sb.append(String.format("%-4s %-6d %-10s %-6d %-10s\n",
                    player.roundLabelByOrder.get(order), rank, deltaRank, elo, deltaElo));

            prevRank = rank;
            prevElo = elo;
        }
        sb.append("```\n\n");

        sb.append("**🪑 Seating Arrangement:**\n```\n");
        StringBuilder roundsLine = new StringBuilder("Rnd: ");
        StringBuilder seatsLine = new StringBuilder("Seat:");
        for (int order : roundOrders) {
            String roundName = player.roundLabelByOrder.get(order);
            Integer seat = player.seatByRound.get(order);
            roundsLine.append(String.format("%-4s", roundName)).append("|");
            seatsLine.append(String.format(" %-3s", seat != null ? String.valueOf(seat) : "-")).append("|");
        }
        sb.append(roundsLine).append("\n").append(seatsLine).append("\n```\n\n");

        sb.append("**🏆 Victory Record:**\n```\n");
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

            String emoji = VictoryRecordCalculator.getOutcomeEmoji(outcome);
            Integer oppOutcome = outcome == 0 ? 0 : -outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);

            String playerHallFormatted = TableFormatter.shortenHallName(player.hall);
            String oppHallFormatted;
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                oppHallFormatted = "";
                oppEloStr = "-";
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
            } else if (oppHall != null) {
                oppHallFormatted = TableFormatter.shortenHallName(oppHall);
            } else {
                oppHallFormatted = "??";
            }

            String score = formatScorePair(player.scoreByRound.get(order), player.oppScoreByRound.get(order), Boolean.TRUE.equals(player.selfTimeoutByRound.get(order)), Boolean.TRUE.equals(player.oppTimeoutByRound.get(order)));

            String line = String.format("%-3s %s %-2s %-4s %-16s %s %-16s %-4s %-2s %s",
                    roundName, emoji, playerHallFormatted, playerEloStr, player.name, score,
                    oppName != null ? oppName : "?", oppEloStr, oppHallFormatted, oppEmoji);
            sb.append(line).append("\n");
        }
        sb.append("```\n");

        return sb.toString();
    }

    private Path generateImage(PlayerData player, List<Integer> roundOrders, String selectedRound) throws Exception {
        String lastRoundLabel = player.lastRoundOrder != null ? player.roundLabelByOrder.get(player.lastRoundOrder) : null;

        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Player Information";
        metadata.subtitle = String.format("%s (Hall %s)", player.name, player.hall);
        metadata.description = "Player statistics and performance";
        metadata.lastRound = lastRoundLabel;

        List<InfoImageGenerator.Section> sections = new ArrayList<>();

        InfoImageGenerator.Section statsSection = new InfoImageGenerator.Section("Stats Per Round");
        statsSection.addMonospacedRow(String.format("%-4s %-6s %-10s %-6s %-10s", "Rnd", "Rank", "ΔRank", "ELO", "ΔELO"));
        Integer prevRank = null;
        Integer prevElo = null;
        for (int order : roundOrders) {
            Integer rank = player.rankByRound.get(order);
            Integer elo = player.eloByRound.get(order);
            if (rank == null || elo == null) continue;
            String deltaRank = prevRank == null ? "-" : deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : deltaString(elo - prevElo);
            statsSection.addMonospacedRow(String.format("%-4s %-6d %-10s %-6d %-10s",
                    player.roundLabelByOrder.get(order), rank, deltaRank, elo, deltaElo));
            prevRank = rank;
            prevElo = elo;
        }
        sections.add(statsSection);

        InfoImageGenerator.Section seatSection = new InfoImageGenerator.Section("Seating");
        StringBuilder seatHeader = new StringBuilder("Rnd: ");
        StringBuilder seatData = new StringBuilder("Seat:");
        for (int order : roundOrders) {
            seatHeader.append(String.format("%-3s|", player.roundLabelByOrder.get(order)));
            Integer seat = player.seatByRound.get(order);
            seatData.append(String.format("%-3s|", seat != null ? seat : "-"));
        }
        seatSection.addMonospacedRow(seatHeader.toString());
        seatSection.addMonospacedRow(seatData.toString());
        sections.add(seatSection);

        InfoImageGenerator.Section victorySection = new InfoImageGenerator.Section("Victory Record");
        for (int order : roundOrders) {
            String roundName = player.roundLabelByOrder.get(order);
            Integer outcome = player.outcomeByRound.get(order);
            if (outcome == null) {
                if (player.eloByRound.containsKey(order)) {
                    InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
                    entry.round = roundName;
                    entry.isNA = true;
                    victorySection.addVictoryEntry(entry);
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

            String score = formatScorePair(player.scoreByRound.get(order), player.oppScoreByRound.get(order), Boolean.TRUE.equals(player.selfTimeoutByRound.get(order)), Boolean.TRUE.equals(player.oppTimeoutByRound.get(order)));

            InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
            entry.round = roundName;
            entry.hallEmoji = hallEmoji;
            entry.hallOutcome = outcome;
            entry.playerHall = playerHallFormatted;
            entry.playerElo = playerEloStr;
            entry.playerName = player.name;
            entry.score = score;
            entry.opponentName = oppName != null ? oppName : "?";
            entry.opponentElo = oppEloStr;
            entry.opponentHall = oppHallFormatted;
            entry.oppEmoji = oppEmoji;
            entry.oppOutcome = oppOutcome;
            entry.isNA = false;
            victorySection.addVictoryEntry(entry);
        }
        sections.add(victorySection);

        return InfoImageGenerator.generateInfoImage(metadata, sections, player.hall, "InfoPlayer", player.name);
    }

    private static String deltaString(int change) {
        if (change > 0) return "+" + change;
        if (change < 0) return "-" + Math.abs(change);
        return "=";
    }

    /** Formats "myScore-oppScore" - both sides' raw scores are stored directly now, no formula derivation needed. */
    private String formatScorePair(Double myScore, Double oppScore, boolean selfTimeout, boolean oppTimeout) {
        String myStr = selfTimeout ? "TIMEOUT" : (myScore != null ? VictoryRecordCalculator.formatScore(myScore) : "?");
        String oppStr = oppTimeout ? "TIMEOUT" : (oppScore != null ? VictoryRecordCalculator.formatScore(oppScore) : "0");
        return myStr + "-" + oppStr;
    }
}
