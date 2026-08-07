package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.PlayerStatsBuilder;
import com.calplus.ihrgstats.telegrambot.utils.PlayerStatsBuilder.PlayerData;
import com.calplus.ihrgstats.telegrambot.utils.PlayerStatsBuilder.YearSummary;
import com.calplus.ihrgstats.telegrambot.utils.SelectionKeyboards;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final PlayerStatsBuilder playerStatsBuilder = new PlayerStatsBuilder();

    private static final Map<String, PlayerInfoSelectionState> userSelectionStates = new ConcurrentHashMap<>();

    private static class PlayerInfoSelectionState extends SelectionState {
        int hallId;
        String hallName;
        String playerId;
        String playerName;
    }

    public CommandInfoPlayer() {
        EnvironmentManager.ensureSystemPropertiesLoaded();
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

        String message = "**👤 Player Information**\n\nSelect the **player's hall**:";
        return new InfoResponse(message, (Path) null, SelectionKeyboards.hallButtons(allHalls, "infoplayer_hall_", "infoplayer_cancel"));
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
                return new InfoResponse(String.format("ℹ️ No active players found in %s for %d.", VictoryRecordCalculator.formatHallName(state.hallName), year), (Path) null, null);
            }

            Integer nameYear = year;
            String message = String.format("**👤 Player Information**\n\nHall: **%s**\nSelect the **player**:", VictoryRecordCalculator.formatHallName(state.hallName));
            return new InfoResponse(message, (Path) null, SelectionKeyboards.playerButtons(statuses, pid -> {
                String name = playerNames.getNameForYear(pid, nameYear);
                return name != null ? name : pid;
            }, "infoplayer_player_", "infoplayer_cancel"));
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

            String message = String.format("**👤 Player Information**\n\nPlayer: **%s** (%s)\n\nSelect rounds to display:",
                    TelegramHtml.escape(state.playerName), VictoryRecordCalculator.formatHallName(state.hallName));
            return new InfoResponse(message, (Path) null, SelectionKeyboards.roundButtons(availableRounds, "infoplayer_round_", "infoplayer_cancel"));
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
        userSelectionStates.remove(userId);

        boolean allYears = selectedRound.equalsIgnoreCase("allyears");
        Integer year = null;
        if (!allYears) {
            year = YearContext.getCurrentYear();
            if (year == null) {
                return new InfoResponse("⚠️ No current year set.", (Path) null, null);
            }
        }

        try {
            return allYears
                ? generatePlayerInfoAllYears(state.playerId, state.playerName, state.hallName)
                : generatePlayerInfo(state.playerId, state.playerName, state.hallName, year, selectedRound);
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

    // PlayerData / YearSummary now live in the shared PlayerStatsBuilder
    // (imported by name above) - /compareplayers renders from the same
    // carriers.

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

        PlayerData player = playerStatsBuilder.fetchPlayerData(playerId, playerName, hallName, year, roundsToInclude, trueEloTypeId);

        if (player.eloByRound.isEmpty()) {
            throw new IllegalStateException("Player " + player.name + " has no data for " + year);
        }

        List<Integer> roundOrders = new ArrayList<>(player.roundLabelByOrder.keySet());

        String textOutput = generateTextOutput(player, roundOrders);
        Path imagePath = generateImage(player, roundOrders, selectedRound);

        logHelper.logSuccess(String.format("Generated player info: %s (%s) (rounds: %s)", player.name, player.hall, selectedRound));
        return new InfoResponse(textOutput, imagePath, null);
    }

    /**
     * Collapses each year down to a single summary row via the shared
     * builder - the round axis becomes the year axis, avoiding the
     * per-round width/height budgets exploding once a player has multiple
     * years of history.
     */
    private InfoResponse generatePlayerInfoAllYears(String playerId, String playerName, String hallName) throws Exception {
        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
        }

        List<YearSummary> yearSummaries = playerStatsBuilder.buildYearSummaries(playerId, playerName, hallName, trueEloTypeId);

        if (yearSummaries.isEmpty()) {
            throw new IllegalStateException("Player " + (playerName != null ? playerName : playerId) + " has no data for any year");
        }

        String displayName = playerName != null ? playerName : playerId;
        String textOutput = generateTextOutputAllYears(displayName, hallName, yearSummaries);
        Path imagePath = generateImageAllYears(displayName, hallName, yearSummaries);

        logHelper.logSuccess(String.format("Generated player info: %s (%s) (All Years)", displayName, hallName));
        return new InfoResponse(textOutput, imagePath, null);
    }

    private String generateTextOutputAllYears(String playerName, String hallName, List<YearSummary> yearSummaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("**👤 Player Information (All Years)**\n\n");
        sb.append(String.format("**%s** (%s)\n\n", TelegramHtml.escape(playerName), VictoryRecordCalculator.formatHallName(hallName)));

        sb.append("**📊 Stats Per Year:**\n```\n");
        sb.append(String.format("%-6s %-6s %-10s %-6s %-10s\n", "Year", "Rank", "ΔRank", "ELO", "ΔELO"));
        sb.append(String.format("%-6s %-6s %-10s %-6s %-10s\n", "------", "------", "----------", "------", "----------"));
        Integer prevRank = null;
        Integer prevElo = null;
        for (YearSummary s : yearSummaries) {
            if (s.finalRank == null || s.finalElo == null) continue;
            String deltaRank = prevRank == null ? "-" : VictoryRecordCalculator.deltaString(prevRank - s.finalRank);
            String deltaElo = prevElo == null ? "-" : VictoryRecordCalculator.deltaString(s.finalElo - prevElo);
            sb.append(String.format("%-6d %-6d %-10s %-6d %-10s\n", s.year, s.finalRank, deltaRank, s.finalElo, deltaElo));
            prevRank = s.finalRank;
            prevElo = s.finalElo;
        }
        sb.append("```\n\n");

        sb.append("**🪑 Avg Seat by Year:**\n```\n");
        StringBuilder yearsLine = new StringBuilder("Year:");
        StringBuilder seatsLine = new StringBuilder("Seat:");
        for (YearSummary s : yearSummaries) {
            yearsLine.append(String.format(" %-6d|", s.year));
            seatsLine.append(String.format(" %-6s|", s.avgSeat < 999 ? String.format("%.1f", s.avgSeat) : "-"));
        }
        sb.append(yearsLine).append("\n").append(seatsLine).append("\n```\n\n");

        sb.append("**🏆 Season Record (wins-losses per year):**\n```\n");
        for (YearSummary s : yearSummaries) {
            sb.append(String.format("%-6d %s\n", s.year, VictoryRecordCalculator.formatScorePair(s.wins, s.losses)));
        }
        sb.append("```\n");

        return sb.toString();
    }

    private Path generateImageAllYears(String playerName, String hallName, List<YearSummary> yearSummaries) throws Exception {
        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Player Information";
        metadata.subtitle = String.format("%s (%s) - All Years", playerName, VictoryRecordCalculator.formatHallName(hallName));
        metadata.description = "Player statistics across every year";
        metadata.lastRound = yearSummaries.isEmpty() ? null : String.valueOf(yearSummaries.get(yearSummaries.size() - 1).year);

        List<InfoImageGenerator.Section> sections = new ArrayList<>();

        InfoImageGenerator.Section statsSection = new InfoImageGenerator.Section("Stats Per Year");
        statsSection.addMonospacedRow(String.format("%-6s %-6s %-10s %-6s %-10s", "Year", "Rank", "ΔRank", "ELO", "ΔELO"));
        Integer prevRank = null;
        Integer prevElo = null;
        for (YearSummary s : yearSummaries) {
            if (s.finalRank == null || s.finalElo == null) continue;
            String deltaRank = prevRank == null ? "-" : VictoryRecordCalculator.deltaString(prevRank - s.finalRank);
            String deltaElo = prevElo == null ? "-" : VictoryRecordCalculator.deltaString(s.finalElo - prevElo);
            statsSection.addMonospacedRow(String.format("%-6d %-6d %-10s %-6d %-10s", s.year, s.finalRank, deltaRank, s.finalElo, deltaElo));
            prevRank = s.finalRank;
            prevElo = s.finalElo;
        }
        sections.add(statsSection);

        InfoImageGenerator.Section seatSection = new InfoImageGenerator.Section("Avg Seat by Year");
        StringBuilder yearsLine = new StringBuilder("Year:");
        StringBuilder seatsLine = new StringBuilder("Seat:");
        for (YearSummary s : yearSummaries) {
            yearsLine.append(String.format(" %-6d|", s.year));
            seatsLine.append(String.format(" %-6s|", s.avgSeat < 999 ? String.format("%.1f", s.avgSeat) : "-"));
        }
        seatSection.addMonospacedRow(yearsLine.toString());
        seatSection.addMonospacedRow(seatsLine.toString());
        sections.add(seatSection);

        InfoImageGenerator.Section seasonSection = new InfoImageGenerator.Section("Season Record");
        for (YearSummary s : yearSummaries) {
            seasonSection.addMonospacedRow(String.format("%-6d %s", s.year, VictoryRecordCalculator.formatScorePair(s.wins, s.losses)));
        }
        sections.add(seasonSection);

        return InfoImageGenerator.generateInfoImage(metadata, sections, hallName, "InfoPlayer", playerName);
    }

    private String generateTextOutput(PlayerData player, List<Integer> roundOrders) {
        StringBuilder sb = new StringBuilder();
        sb.append("**👤 Player Information**\n\n");
        sb.append(String.format("**%s** (%s)\n\n", TelegramHtml.escape(player.name), VictoryRecordCalculator.formatHallName(player.hall)));

        sb.append("**📊 Stats Per Round:**\n```\n");
        sb.append(String.format("%-4s %-6s %-10s %-6s %-10s\n", "Rnd", "Rank", "ΔRank", "ELO", "ΔELO"));
        sb.append(String.format("%-4s %-6s %-10s %-6s %-10s\n", "----", "------", "----------", "------", "----------"));

        Integer prevRank = null;
        Integer prevElo = null;
        for (int order : roundOrders) {
            Integer rank = player.rankByRound.get(order);
            Integer elo = player.eloByRound.get(order);
            if (rank == null || elo == null) continue;

            String deltaRank = prevRank == null ? "-" : VictoryRecordCalculator.deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : VictoryRecordCalculator.deltaString(elo - prevElo);

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

            String score = VictoryRecordCalculator.formatScorePair(player.scoreByRound.get(order), player.oppScoreByRound.get(order), Boolean.TRUE.equals(player.selfTimeoutByRound.get(order)), Boolean.TRUE.equals(player.oppTimeoutByRound.get(order)));

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
        metadata.subtitle = String.format("%s (%s)", player.name, VictoryRecordCalculator.formatHallName(player.hall));
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
            String deltaRank = prevRank == null ? "-" : VictoryRecordCalculator.deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : VictoryRecordCalculator.deltaString(elo - prevElo);
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

            String score = VictoryRecordCalculator.formatScorePair(player.scoreByRound.get(order), player.oppScoreByRound.get(order), Boolean.TRUE.equals(player.selfTimeoutByRound.get(order)), Boolean.TRUE.equals(player.oppTimeoutByRound.get(order)));

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
}
