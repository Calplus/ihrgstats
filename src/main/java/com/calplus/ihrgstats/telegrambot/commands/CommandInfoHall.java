package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.HallStatsBuilder;
import com.calplus.ihrgstats.telegrambot.utils.HallStatsBuilder.HallData;
import com.calplus.ihrgstats.telegrambot.utils.HallStatsBuilder.HallVictoryRecord;
import com.calplus.ihrgstats.telegrambot.utils.HallStatsBuilder.PlayerData;
import com.calplus.ihrgstats.telegrambot.utils.SelectionKeyboards;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Command handler for /infohall command.
 * Shows detailed information for a single hall, scoped to the current
 * year (settings.currentYear).
 */
public class CommandInfoHall {
    private final LogHelper logHelper;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final HallStatsBuilder hallStatsBuilder = new HallStatsBuilder();

    private static final Map<String, HallInfoSelectionState> userSelectionStates = new ConcurrentHashMap<>();

    private static class HallInfoSelectionState extends SelectionState {
        int hallId;
        String hallName;
    }

    public CommandInfoHall() {
        EnvironmentManager.ensureSystemPropertiesLoaded();
        this.logHelper = new LogHelper();
    }

    public static class InfoResponse extends CommandResponse {
        public InfoResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            super(message, imagePath, buttonConfig);
        }
    }

    public InfoResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("%s requested /infohall command", com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId)));

        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.put(userId, new HallInfoSelectionState());

        List<A3_Halls.Hall> allHalls;
        try {
            allHalls = halls.getAllHalls();
        } catch (SQLException e) {
            logHelper.logError("Database error fetching halls: " + e.getMessage());
            return new InfoResponse("❌ Database error fetching halls.", (Path) null, null);
        }

        String message = "**🏛️ Hall Information**\n\nSelect a **hall**:";
        return new InfoResponse(message, (Path) null, SelectionKeyboards.hallButtons(allHalls, "infohall_hall_", "infohall_cancel"));
    }

    public InfoResponse handleHallSelection(String userId, int hallId) {
        HallInfoSelectionState state = userSelectionStates.getOrDefault(userId, new HallInfoSelectionState());
        state.hallId = hallId;
        userSelectionStates.put(userId, state);

        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            return new InfoResponse("⚠️ No current year set. An admin must set `settings.currentYear` first.", (Path) null, null);
        }

        try {
            A3_Halls.Hall hall = halls.getHallById(hallId);
            state.hallName = hall != null ? hall.hallName : "?";

            List<A1_Rounds.Round> availableRounds = rounds.getRoundsForYear(year);
            if (availableRounds.isEmpty()) {
                userSelectionStates.remove(userId);
                return new InfoResponse("ℹ️ No round data available for " + year + ".", (Path) null, null);
            }

            String message = String.format("**🏛️ Hall Information**\n\nHall: **%s**\n\nSelect a **round**:", VictoryRecordCalculator.formatHallName(state.hallName));
            return new InfoResponse(message, (Path) null, SelectionKeyboards.roundButtons(availableRounds, "infohall_round_", "infohall_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new InfoResponse("❌ Database error.", (Path) null, null);
        }
    }

    public InfoResponse handleRoundSelection(String userId, String selectedRound) {
        HallInfoSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.hallName == null) {
            return new InfoResponse("❌ Session expired. Please run /infohall again.", (Path) null, null);
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
                ? generateHallInfoAllYears(state.hallId, state.hallName)
                : generateHallInfo(state.hallId, state.hallName, year, selectedRound);
        } catch (Exception e) {
            logHelper.logError("Failed to generate hall info: " + e.getMessage());
            e.printStackTrace();
            return new InfoResponse("❌ Error generating hall information: " + e.getMessage(), (Path) null, null);
        }
    }

    public InfoResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new InfoResponse("❌ Hall information request cancelled.", (Path) null, null);
    }

    // PlayerData / HallData / HallVictoryRecord now live in the shared
    // HallStatsBuilder (imported by name above) - /comparehalls renders from
    // the same carriers.

    private InfoResponse generateHallInfo(int hallId, String hallName, int year, String selectedRound) throws Exception {
        List<A1_Rounds.Round> availableRounds = rounds.getRoundsForYear(year);
        int selectedOrder = selectedRound.equalsIgnoreCase("all") ? Integer.MAX_VALUE : Integer.parseInt(selectedRound);
        List<A1_Rounds.Round> roundsToInclude = availableRounds.stream()
                .filter(r -> r.roundOrder <= selectedOrder)
                .collect(Collectors.toList());

        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
        }

        HallData hallData = fetchHallData(hallId, hallName, year, roundsToInclude, trueEloTypeId);

        if (hallData.players.isEmpty()) {
            throw new IllegalStateException(VictoryRecordCalculator.formatHallName(hallName) + " has no player data for " + year);
        }

        String textOutput = generateTextOutput(hallData, roundsToInclude);
        Path imagePath = generateImage(hallData, roundsToInclude);

        logHelper.logSuccess(String.format("Generated hall info: %s (rounds: %s)", hallName, selectedRound));
        return new InfoResponse(textOutput, imagePath, null);
    }

    /** One year's collapsed summary row, for the "All Years" view. */
    private static class YearSummary {
        int year;
        Double finalHallElo;
        Integer finalHallRank;
        double totalHallScore;
        double totalOppScore;
        Map<String, Double> avgSeatByPlayerId = new HashMap<>();
        Map<String, String> playerNameById = new HashMap<>();
    }

    /**
     * All-time view: reuses fetchHallData's existing per-round computation
     * once per year (rather than re-deriving new aggregation math), then
     * collapses each year down to a single summary row per section - the
     * round axis becomes the year axis, avoiding the per-round width/height
     * budgets exploding once a hall has multiple years of history.
     */
    private InfoResponse generateHallInfoAllYears(int hallId, String hallName) throws Exception {
        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
        }

        List<Integer> allYears = rounds.getAllYears();
        List<YearSummary> yearSummaries = new ArrayList<>();
        HallData latestYearData = null;

        for (int year : allYears) {
            List<A1_Rounds.Round> yearRounds = rounds.getRoundsForYear(year);
            HallData yearData = fetchHallData(hallId, hallName, year, yearRounds, trueEloTypeId);
            if (yearData.players.isEmpty()) continue;

            YearSummary summary = new YearSummary();
            summary.year = year;
            if (yearData.lastRoundOrder != null) {
                summary.finalHallElo = yearData.hallEloByRound.get(yearData.lastRoundOrder);
                summary.finalHallRank = yearData.hallRankByRound.get(yearData.lastRoundOrder);
            }
            for (HallVictoryRecord record : yearData.victoryRecords.values()) {
                summary.totalHallScore += record.hallScore;
                summary.totalOppScore += record.oppScore;
            }
            for (PlayerData p : yearData.players) {
                summary.avgSeatByPlayerId.put(p.playerId, p.avgSeat);
                summary.playerNameById.put(p.playerId, p.name);
            }
            yearSummaries.add(summary);
            latestYearData = yearData; // getAllYears() is ascending, so the last iteration is the most recent
        }

        if (latestYearData == null) {
            throw new IllegalStateException(VictoryRecordCalculator.formatHallName(hallName) + " has no player data for any year");
        }

        String textOutput = generateTextOutputAllYears(hallName, yearSummaries, latestYearData);
        Path imagePath = generateImageAllYears(hallName, yearSummaries, latestYearData);

        logHelper.logSuccess(String.format("Generated hall info: %s (All Years)", hallName));
        return new InfoResponse(textOutput, imagePath, null);
    }

    private String generateTextOutputAllYears(String hallName, List<YearSummary> yearSummaries, HallData latestYearData) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**🏛️ %s Information (All Years)**\n\n", VictoryRecordCalculator.formatHallName(hallName)));

        sb.append("**🏛️ Hall Elo (by year):**\n```\n");
        sb.append(String.format("%-6s %-6s %-10s %-8s %-10s\n", "Year", "Rank", "ΔRank", "Elo", "ΔElo"));
        sb.append(String.format("%-6s %-6s %-10s %-8s %-10s\n", "------", "------", "----------", "--------", "----------"));
        Double prevElo = null;
        Integer prevRank = null;
        for (YearSummary s : yearSummaries) {
            if (s.finalHallElo == null || s.finalHallRank == null) {
                sb.append(String.format("%-6d %-6s %-10s %-8s %-10s\n", s.year, "-", "-", "-", "-"));
                continue;
            }
            String deltaRank = prevRank == null ? "-" : VictoryRecordCalculator.deltaString(prevRank - s.finalHallRank);
            String deltaElo = prevElo == null ? "-" : VictoryRecordCalculator.deltaDoubleString(s.finalHallElo - prevElo);
            sb.append(String.format("%-6d %-6d %-10s %-8s %-10s\n", s.year, s.finalHallRank, deltaRank, String.format("%.1f", s.finalHallElo), deltaElo));
            prevElo = s.finalHallElo;
            prevRank = s.finalHallRank;
        }
        sb.append("```\n\n");

        sb.append("**📋 Player Stats (most recent year):**\n```\n");
        sb.append(String.format("%-8s %-8s %-6s %-7s %-20s\n", "HallRank", "GlobRank", "ELO", "Capped", "Name"));
        sb.append(String.format("%-8s %-8s %-6s %-7s %-20s\n", "--------", "--------", "------", "-------", "--------------------"));
        for (PlayerData p : latestYearData.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            sb.append(String.format("%-8d %-8d %-6d %-7s %-20s\n", p.hallRank, p.globalRank, p.elo, p.capped ? "Yes" : "No", name));
        }
        sb.append("```\n\n");

        sb.append("**🪑 Seating Arrangements (avg seat by year):**\n```\n");
        List<String> allPlayerIds = collectAllPlayerIdsByRecency(yearSummaries);
        StringBuilder header = new StringBuilder(String.format("%-20s: ", "Name"));
        for (YearSummary s : yearSummaries) header.append(String.format("%-6s|", s.year));
        sb.append(header).append("\n");
        for (String playerId : allPlayerIds) {
            String name = lastKnownName(yearSummaries, playerId);
            String displayName = name.length() > 20 ? name.substring(0, 17) + "..." : name;
            StringBuilder line = new StringBuilder(String.format("%-20s: ", displayName));
            for (YearSummary s : yearSummaries) {
                Double avgSeat = s.avgSeatByPlayerId.get(playerId);
                line.append(String.format("%-6s|", avgSeat != null && avgSeat < 999 ? String.format("%.1f", avgSeat) : "-"));
            }
            sb.append(line).append("\n");
        }
        sb.append("```\n\n");

        sb.append("**🏆 Season Record (total boards won-lost per year):**\n```\n");
        for (YearSummary s : yearSummaries) {
            sb.append(String.format("%-6d %s\n", s.year, VictoryRecordCalculator.formatScorePair(s.totalHallScore, s.totalOppScore)));
        }
        sb.append("```");

        return sb.toString();
    }

    private Path generateImageAllYears(String hallName, List<YearSummary> yearSummaries, HallData latestYearData) throws Exception {
        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Hall Information";
        metadata.subtitle = VictoryRecordCalculator.formatHallName(hallName) + " (All Years)";
        metadata.description = "Hall statistics and performance across every year";
        metadata.lastRound = yearSummaries.isEmpty() ? null : String.valueOf(yearSummaries.get(yearSummaries.size() - 1).year);

        List<InfoImageGenerator.Section> sections = new ArrayList<>();

        InfoImageGenerator.Section hallEloSection = new InfoImageGenerator.Section("Hall Elo (by Year)");
        hallEloSection.addMonospacedRow(String.format("%-6s %-6s %-8s %-8s %-8s", "Year", "Rank", "ΔRank", "Elo", "ΔElo"));
        Double prevElo = null;
        Integer prevRank = null;
        for (YearSummary s : yearSummaries) {
            if (s.finalHallElo == null || s.finalHallRank == null) {
                hallEloSection.addMonospacedRow(String.format("%-6d %-6s %-8s %-8s %-8s", s.year, "-", "-", "-", "-"));
                continue;
            }
            String deltaRank = prevRank == null ? "-" : VictoryRecordCalculator.deltaString(prevRank - s.finalHallRank);
            String deltaElo = prevElo == null ? "-" : VictoryRecordCalculator.deltaDoubleString(s.finalHallElo - prevElo);
            hallEloSection.addMonospacedRow(String.format("%-6d %-6d %-8s %-8s %-8s", s.year, s.finalHallRank, deltaRank, String.format("%.1f", s.finalHallElo), deltaElo));
            prevElo = s.finalHallElo;
            prevRank = s.finalHallRank;
        }
        sections.add(hallEloSection);

        InfoImageGenerator.Section playersSection = new InfoImageGenerator.Section("Player Stats (Most Recent Year)");
        playersSection.addMonospacedRow(String.format("%-8s %-8s %-6s %-7s %-20s", "HallRank", "GlobRank", "ELO", "Capped", "Name"));
        for (PlayerData p : latestYearData.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            playersSection.addMonospacedRow(String.format("%-8s %-8s %-6s %-7s %-20s",
                    String.valueOf(p.hallRank), String.valueOf(p.globalRank), String.valueOf(p.elo), p.capped ? "Yes" : "No", name));
        }
        sections.add(playersSection);

        InfoImageGenerator.Section seatingSection = new InfoImageGenerator.Section("Seating (Avg Seat by Year)");
        List<String> allPlayerIds = collectAllPlayerIdsByRecency(yearSummaries);
        StringBuilder header = new StringBuilder(String.format("%-20s: ", "Name"));
        for (YearSummary s : yearSummaries) header.append(String.format("%-6s|", s.year));
        seatingSection.addMonospacedRow(header.toString());
        for (String playerId : allPlayerIds) {
            String name = lastKnownName(yearSummaries, playerId);
            String displayName = name.length() > 20 ? name.substring(0, 17) + "..." : name;
            StringBuilder line = new StringBuilder(String.format("%-20s: ", displayName));
            for (YearSummary s : yearSummaries) {
                Double avgSeat = s.avgSeatByPlayerId.get(playerId);
                line.append(String.format("%-6s|", avgSeat != null && avgSeat < 999 ? String.format("%.1f", avgSeat) : "-"));
            }
            seatingSection.addMonospacedRow(line.toString());
        }
        sections.add(seatingSection);

        InfoImageGenerator.Section seasonSection = new InfoImageGenerator.Section("Season Record");
        for (YearSummary s : yearSummaries) {
            seasonSection.addMonospacedRow(String.format("%-6d %s", s.year, VictoryRecordCalculator.formatScorePair(s.totalHallScore, s.totalOppScore)));
        }
        sections.add(seasonSection);

        return InfoImageGenerator.generateInfoImage(metadata, sections, hallName, "InfoHall", hallName);
    }

    /** Every player who appeared in any year's summary, ordered by their most recent year of appearance (newest first), then name. */
    private static List<String> collectAllPlayerIdsByRecency(List<YearSummary> yearSummaries) {
        Map<String, Integer> mostRecentYearByPlayer = new LinkedHashMap<>();
        for (YearSummary s : yearSummaries) {
            for (String playerId : s.avgSeatByPlayerId.keySet()) {
                mostRecentYearByPlayer.put(playerId, s.year);
            }
        }
        List<String> playerIds = new ArrayList<>(mostRecentYearByPlayer.keySet());
        playerIds.sort((a, b) -> Integer.compare(mostRecentYearByPlayer.get(b), mostRecentYearByPlayer.get(a)));
        return playerIds;
    }

    private static String lastKnownName(List<YearSummary> yearSummaries, String playerId) {
        for (int i = yearSummaries.size() - 1; i >= 0; i--) {
            String name = yearSummaries.get(i).playerNameById.get(playerId);
            if (name != null) return name;
        }
        return playerId;
    }

    private HallData fetchHallData(int hallId, String hallName, int year, List<A1_Rounds.Round> roundsToInclude, int trueEloTypeId) throws SQLException {
        HallStatsBuilder.RoundContext ctx = hallStatsBuilder.buildRoundContext(year, roundsToInclude, trueEloTypeId);
        HallData hallData = hallStatsBuilder.buildHallStats(hallId, hallName, year, roundsToInclude, ctx);
        Map<Integer, Map<Integer, Double>> top5AvgByRoundThenHall = hallStatsBuilder.computeTop5AvgByHallPerRound(year, roundsToInclude, ctx);
        hallStatsBuilder.calculateHallEloAndRank(hallData, top5AvgByRoundThenHall);
        hallStatsBuilder.calculateHallVictoryRecords(hallData, roundsToInclude, top5AvgByRoundThenHall, ctx);
        return hallData;
    }

    private String generateTextOutput(HallData hall, List<A1_Rounds.Round> roundsToInclude) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**🏛️ %s Information**\n\n", VictoryRecordCalculator.formatHallName(hall.hallName)));
        sb.append(String.format("**Last Round:** %s\n\n", hall.lastRoundLabel != null ? hall.lastRoundLabel : "N/A"));

        sb.append("**🏛️ Hall Elo:**\n```\n");
        sb.append(String.format("%-4s %-6s %-10s %-8s %-10s\n", "Rnd", "Rank", "ΔRank", "Elo", "ΔElo"));
        sb.append(String.format("%-4s %-6s %-10s %-8s %-10s\n", "----", "------", "----------", "--------", "----------"));

        Double prevElo = null;
        Integer prevRank = null;
        for (A1_Rounds.Round round : roundsToInclude) {
            Double elo = hall.hallEloByRound.get(round.roundOrder);
            Integer rank = hall.hallRankByRound.get(round.roundOrder);
            if (elo == null || rank == null) {
                sb.append(String.format("%-4s %-6s %-10s %-8s %-10s\n", round.roundLabel, "-", "-", "-", "-"));
                continue;
            }
            String deltaRank = prevRank == null ? "-" : VictoryRecordCalculator.deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : VictoryRecordCalculator.deltaDoubleString(elo - prevElo);
            sb.append(String.format("%-4s %-6d %-10s %-8s %-10s\n", round.roundLabel, rank, deltaRank, String.format("%.1f", elo), deltaElo));
            prevElo = elo;
            prevRank = rank;
        }
        sb.append("```\n\n");

        sb.append("**📋 Player Stats:**\n```\n");
        sb.append(String.format("%-8s %-8s %-6s %-7s %-20s\n", "HallRank", "GlobRank", "ELO", "Capped", "Name"));
        sb.append(String.format("%-8s %-8s %-6s %-7s %-20s\n", "--------", "--------", "------", "-------", "--------------------"));
        for (PlayerData p : hall.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            sb.append(String.format("%-8d %-8d %-6d %-7s %-20s\n", p.hallRank, p.globalRank, p.elo, p.capped ? "Yes" : "No", name));
        }
        sb.append("```\n\n");

        sb.append("**🪑 Seating Arrangements:**\n```\n");
        List<PlayerData> sortedBySeat = new ArrayList<>(hall.players);
        sortedBySeat.sort((a, b) -> Double.compare(a.avgSeat, b.avgSeat));

        StringBuilder header = new StringBuilder(String.format("%-4s %-15s: ", "Avg", "Name"));
        for (A1_Rounds.Round round : roundsToInclude) header.append(String.format("%-3s|", round.roundLabel));
        sb.append(header).append("\n");

        for (PlayerData p : sortedBySeat) {
            String name = p.name.length() > 15 ? p.name.substring(0, 12) + "..." : p.name;
            String avgStr = p.avgSeat < 999 ? String.format("%.1f", p.avgSeat) : "-";
            StringBuilder line = new StringBuilder(String.format("%-4s %-15s: ", avgStr, name));
            for (A1_Rounds.Round round : roundsToInclude) {
                Integer seat = p.seatByRound.get(round.roundOrder);
                line.append(String.format("%-3s|", seat != null ? seat.toString() : "-"));
            }
            sb.append(line).append("\n");
        }
        sb.append("```\n\n");

        sb.append("**🏆 Victory Record:**\n```\n");
        for (A1_Rounds.Round round : roundsToInclude) {
            HallVictoryRecord record = hall.victoryRecords.get(round.roundOrder);
            if (record == null) {
                sb.append(String.format("%-3s -NA-\n", round.roundLabel));
                continue;
            }

            Double hallElo = hall.hallEloByRound.get(round.roundOrder);
            String hallEloStr = hallElo != null ? String.format("%.1f", hallElo) : "?";
            String oppEloStr = record.oppHallElo != null ? String.format("%.1f", record.oppHallElo) : "?";

            String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(record.outcome);
            int oppOutcome = record.outcome == 0 ? 0 : -record.outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);

            String formattedHall = VictoryRecordCalculator.formatHallName(hall.hallName);
            String formattedOppHall = "WALKOVER".equalsIgnoreCase(record.oppHallName) ? "WALKOVER" : VictoryRecordCalculator.formatHallName(record.oppHallName);
            if ("WALKOVER".equalsIgnoreCase(record.oppHallName)) oppEloStr = "-";

            String score = VictoryRecordCalculator.formatScorePair(record.hallScore, record.oppScore);

            String line = String.format("%-3s %s %-4s %-15s %s %-15s %-4s %s",
                    round.roundLabel, hallEmoji, hallEloStr, formattedHall, score, formattedOppHall, oppEloStr, oppEmoji);
            sb.append(line).append("\n");
        }
        sb.append("```");

        return sb.toString();
    }

    private Path generateImage(HallData hall, List<A1_Rounds.Round> roundsToInclude) throws Exception {
        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Hall Information";
        metadata.subtitle = VictoryRecordCalculator.formatHallName(hall.hallName);
        metadata.description = "Hall statistics and performance";
        metadata.lastRound = hall.lastRoundLabel;

        List<InfoImageGenerator.Section> sections = new ArrayList<>();

        InfoImageGenerator.Section hallEloSection = new InfoImageGenerator.Section("Hall Elo");
        hallEloSection.addMonospacedRow(String.format("%-4s %-6s %-8s %-8s %-8s", "Rnd", "Rank", "ΔRank", "Elo", "ΔElo"));
        Double prevElo = null;
        Integer prevRank = null;
        for (A1_Rounds.Round round : roundsToInclude) {
            Double elo = hall.hallEloByRound.get(round.roundOrder);
            Integer rank = hall.hallRankByRound.get(round.roundOrder);
            if (elo == null || rank == null) {
                hallEloSection.addMonospacedRow(String.format("%-4s %-6s %-8s %-8s %-8s", round.roundLabel, "-", "-", "-", "-"));
                continue;
            }
            String deltaRank = prevRank == null ? "-" : VictoryRecordCalculator.deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : VictoryRecordCalculator.deltaDoubleString(elo - prevElo);
            hallEloSection.addMonospacedRow(String.format("%-4s %-6d %-8s %-8s %-8s", round.roundLabel, rank, deltaRank, String.format("%.1f", elo), deltaElo));
            prevElo = elo;
            prevRank = rank;
        }
        sections.add(hallEloSection);

        InfoImageGenerator.Section playersSection = new InfoImageGenerator.Section("Player Stats");
        playersSection.addMonospacedRow(String.format("%-8s %-8s %-6s %-7s %-20s", "HallRank", "GlobRank", "ELO", "Capped", "Name"));
        for (PlayerData p : hall.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            playersSection.addMonospacedRow(String.format("%-8s %-8s %-6s %-7s %-20s",
                    String.valueOf(p.hallRank), String.valueOf(p.globalRank), String.valueOf(p.elo), p.capped ? "Yes" : "No", name));
        }
        sections.add(playersSection);

        InfoImageGenerator.Section seatingSection = new InfoImageGenerator.Section("Seating");
        List<PlayerData> sortedBySeat = new ArrayList<>(hall.players);
        sortedBySeat.sort((a, b) -> Double.compare(a.avgSeat, b.avgSeat));

        StringBuilder header = new StringBuilder(String.format("%-4s %-15s: ", "Avg", "Name"));
        for (A1_Rounds.Round round : roundsToInclude) header.append(String.format("%-3s|", round.roundLabel));
        seatingSection.addMonospacedRow(header.toString());
        for (PlayerData p : sortedBySeat) {
            String name = p.name.length() > 15 ? p.name.substring(0, 12) + "..." : p.name;
            String avgStr = p.avgSeat < 999 ? String.format("%.1f", p.avgSeat) : "-";
            StringBuilder line = new StringBuilder(String.format("%-4s %-15s: ", avgStr, name));
            for (A1_Rounds.Round round : roundsToInclude) {
                Integer seat = p.seatByRound.get(round.roundOrder);
                line.append(String.format("%-3s|", seat != null ? seat.toString() : "-"));
            }
            seatingSection.addMonospacedRow(line.toString());
        }
        sections.add(seatingSection);

        InfoImageGenerator.Section victorySection = new InfoImageGenerator.Section("Victory Record");
        for (A1_Rounds.Round round : roundsToInclude) {
            HallVictoryRecord record = hall.victoryRecords.get(round.roundOrder);
            InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
            entry.round = round.roundLabel;
            if (record == null) {
                entry.isNA = true;
                victorySection.addVictoryEntry(entry);
                continue;
            }

            Double hallElo = hall.hallEloByRound.get(round.roundOrder);
            String hallEloStr = hallElo != null ? String.format("%.1f", hallElo) : "?";
            String oppEloStr = record.oppHallElo != null ? String.format("%.1f", record.oppHallElo) : "?";

            String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(record.outcome);
            int oppOutcome = record.outcome == 0 ? 0 : -record.outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);

            String hallFormatted = VictoryRecordCalculator.formatHallName(hall.hallName);
            String oppHallFormatted;
            if ("WALKOVER".equalsIgnoreCase(record.oppHallName)) {
                oppHallFormatted = "WALKOVER";
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
                oppEloStr = "-";
            } else {
                oppHallFormatted = VictoryRecordCalculator.formatHallName(record.oppHallName);
            }

            String score = VictoryRecordCalculator.formatScorePair(record.hallScore, record.oppScore);

            entry.hallEmoji = hallEmoji;
            entry.hallOutcome = record.outcome;
            entry.playerHall = hallFormatted;
            entry.playerElo = hallEloStr;
            entry.playerName = "";
            entry.score = score;
            entry.opponentName = "";
            entry.opponentElo = oppEloStr;
            entry.opponentHall = oppHallFormatted;
            entry.oppEmoji = oppEmoji;
            entry.oppOutcome = oppOutcome;
            entry.isNA = false;
            victorySection.addVictoryEntry(entry);
        }
        sections.add(victorySection);

        return InfoImageGenerator.generateInfoImage(metadata, sections, hall.hallName, "InfoHall", hall.hallName);
    }


}
