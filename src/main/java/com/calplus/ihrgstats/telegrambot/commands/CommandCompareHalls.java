package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.HallStatsBuilder;
import com.calplus.ihrgstats.telegrambot.utils.HallStatsBuilder.HallData;
import com.calplus.ihrgstats.telegrambot.utils.HallStatsBuilder.HallVictoryRecord;
import com.calplus.ihrgstats.telegrambot.utils.HallStatsBuilder.PlayerData;
import com.calplus.ihrgstats.telegrambot.utils.SelectionKeyboards;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Command handler for /comparehalls command.
 * Allows comparison of two halls with detailed statistics, scoped to the
 * current year (settings.currentYear).
 */
public class CommandCompareHalls {
    private final LogHelper logHelper;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final HallStatsBuilder hallStatsBuilder = new HallStatsBuilder();

    private static final Map<String, HallCompareSelectionState> userSelectionStates = new ConcurrentHashMap<>();

    private static class HallCompareSelectionState extends SelectionState {
        int firstHallId;
        String firstHallName;
        int secondHallId;
        String secondHallName;
    }

    public CommandCompareHalls() {
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
        logHelper.logInfo(String.format("%s requested /comparehalls command", userInfo));

        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.put(userId, new HallCompareSelectionState());

        List<A3_Halls.Hall> allHalls;
        try {
            allHalls = halls.getAllHalls();
        } catch (SQLException e) {
            logHelper.logError("Database error fetching halls: " + e.getMessage());
            return new CompareResponse("❌ Database error fetching halls.", null, null);
        }
        allHalls.removeIf(h -> h.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE));

        if (allHalls.size() < 2) {
            return new CompareResponse("ℹ️ At least 2 halls are required for comparison. Current halls: " + allHalls.size(), null, null);
        }

        String message = "**🏛️ Hall Comparison**\n\nSelect the **first hall** to compare:";
        return new CompareResponse(message, null, SelectionKeyboards.hallButtons(allHalls, "comparehalls_select1_", "comparehalls_cancel"));
    }

    public CompareResponse handleFirstHallSelection(String userId, int firstHallId) {
        HallCompareSelectionState state = userSelectionStates.getOrDefault(userId, new HallCompareSelectionState());
        state.firstHallId = firstHallId;
        userSelectionStates.put(userId, state);

        try {
            A3_Halls.Hall hall = halls.getHallById(firstHallId);
            state.firstHallName = hall != null ? hall.hallName : "?";

            List<A3_Halls.Hall> allHalls = halls.getAllHalls();
            allHalls.removeIf(h -> h.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE) || h.id == firstHallId);

            if (allHalls.isEmpty()) {
                userSelectionStates.remove(userId);
                return new CompareResponse("ℹ️ No other halls available for comparison.", null, null);
            }

            String message = String.format("**🏛️ Hall Comparison**\n\nFirst hall: **%s**\nSelect the **second hall** to compare:", VictoryRecordCalculator.formatHallName(state.firstHallName));
            return new CompareResponse(message, null, SelectionKeyboards.hallButtons(allHalls, "comparehalls_select2_", "comparehalls_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new CompareResponse("❌ Database error.", null, null);
        }
    }

    public CompareResponse handleSecondHallSelection(String userId, int secondHallId) {
        HallCompareSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstHallName == null) {
            return new CompareResponse("❌ Session expired. Please use /comparehalls to start again.", null, null);
        }
        state.secondHallId = secondHallId;

        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            return new CompareResponse("⚠️ No current year set. An admin must set `settings.currentYear` first.", null, null);
        }

        try {
            A3_Halls.Hall hall = halls.getHallById(secondHallId);
            state.secondHallName = hall != null ? hall.hallName : "?";

            List<A1_Rounds.Round> availableRounds = rounds.getRoundsForYear(year);
            if (availableRounds.isEmpty()) {
                userSelectionStates.remove(userId);
                return new CompareResponse("ℹ️ No round data available for " + year + ".", null, null);
            }

            String message = String.format("**🏛️ Hall Comparison**\n\nFirst hall: **%s**\nSecond hall: **%s**\n\nSelect rounds to compare:",
                    VictoryRecordCalculator.formatHallName(state.firstHallName), VictoryRecordCalculator.formatHallName(state.secondHallName));
            return new CompareResponse(message, null, SelectionKeyboards.roundButtons(availableRounds, "comparehalls_selectround_", "comparehalls_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new CompareResponse("❌ Database error.", null, null);
        }
    }

    public CompareResponse handleRoundSelection(String userId, String selectedRound) {
        HallCompareSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstHallName == null || state.secondHallName == null) {
            return new CompareResponse("❌ Session expired. Please use /comparehalls to start again.", null, null);
        }
        userSelectionStates.remove(userId);

        boolean allYears = selectedRound.equalsIgnoreCase("allyears");
        Integer year = null;
        if (!allYears) {
            year = YearContext.getCurrentYear();
            if (year == null) {
                return new CompareResponse("⚠️ No current year set.", null, null);
            }
        }

        try {
            return allYears
                ? generateComparisonAllYears(state.firstHallId, state.firstHallName, state.secondHallId, state.secondHallName)
                : generateComparison(state.firstHallId, state.firstHallName, state.secondHallId, state.secondHallName, year, selectedRound);
        } catch (Exception e) {
            logHelper.logError("Hall comparison error: " + e.getMessage());
            e.printStackTrace();
            return new CompareResponse("❌ Error generating comparison: " + e.getMessage(), null, null);
        }
    }

    public CompareResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new CompareResponse("ℹ️ Hall comparison cancelled.", null, null);
    }

    // PlayerData / HallData / HallVictoryRecord now live in the shared
    // HallStatsBuilder (imported by name above) - /infohall renders from the
    // same carriers, and tests hand-build rosters with them.

    private CompareResponse generateComparison(int hall1Id, String hall1Name, int hall2Id, String hall2Name, int year, String selectedRound) throws Exception {
        List<A1_Rounds.Round> availableRounds = rounds.getRoundsForYear(year);
        int selectedOrder = selectedRound.equalsIgnoreCase("all") ? Integer.MAX_VALUE : Integer.parseInt(selectedRound);
        List<A1_Rounds.Round> roundsToInclude = availableRounds.stream()
                .filter(r -> r.roundOrder <= selectedOrder)
                .collect(Collectors.toList());

        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
        }

        // One shared bulk context + top-5 table for BOTH halls - previously
        // each hall re-ran the per-player-per-round query pattern
        // (point-in-time rating, FULL rank map, participant, opponent, hall
        // rows - thousands of queries per rendered view on a full season).
        HallStatsBuilder.RoundContext ctx = hallStatsBuilder.buildRoundContext(year, roundsToInclude, trueEloTypeId);
        Map<Integer, Map<Integer, Double>> top5AvgByRoundThenHall = hallStatsBuilder.computeTop5AvgByHallPerRound(year, roundsToInclude, ctx);

        HallData data1 = buildHallData(hall1Id, hall1Name, year, roundsToInclude, ctx, top5AvgByRoundThenHall);
        HallData data2 = buildHallData(hall2Id, hall2Name, year, roundsToInclude, ctx, top5AvgByRoundThenHall);

        if (data1.players.isEmpty()) throw new Exception(VictoryRecordCalculator.formatHallName(hall1Name) + " has no data for " + year);
        if (data2.players.isEmpty()) throw new Exception(VictoryRecordCalculator.formatHallName(hall2Name) + " has no data for " + year);

        double winProbability = calculateWinningProbability(data1, data2);

        String textOutput = generateTextOutput(data1, data2, roundsToInclude, winProbability, selectedRound);
        Path imagePath = generateImage(data1, data2, roundsToInclude, winProbability);

        logHelper.logSuccess(String.format("Generated hall comparison: %s vs %s (rounds: %s)", hall1Name, hall2Name, selectedRound));
        return new CompareResponse(textOutput, imagePath, null);
    }

    /** One year's collapsed summary row, for the "All Years" view. Package-private (not private) so this can be unit-tested directly. */
    static class YearSummary {
        int year;
        Double finalHallElo;
        Integer finalHallRank;
        double totalHallScore;
        double totalOppScore;
        Map<String, Double> avgSeatByPlayerId = new HashMap<>();
        Map<String, String> playerNameById = new HashMap<>();
    }

    /**
     * Composes one hall's full report data from the shared builder: roster
     * stats plus hall-level elo/rank and victory records.
     */
    private HallData buildHallData(int hallId, String hallName, int year, List<A1_Rounds.Round> roundsToInclude,
            HallStatsBuilder.RoundContext ctx, Map<Integer, Map<Integer, Double>> top5AvgByRoundThenHall) throws SQLException {
        HallData hallData = hallStatsBuilder.buildHallStats(hallId, hallName, year, roundsToInclude, ctx);
        hallStatsBuilder.calculateHallEloAndRank(hallData, top5AvgByRoundThenHall);
        hallStatsBuilder.calculateHallVictoryRecords(hallData, roundsToInclude, top5AvgByRoundThenHall, ctx);
        return hallData;
    }

    /** Heavy per-year context shared by BOTH halls of an All-Years comparison. */
    private static class AllYearsContext {
        final int year;
        final List<A1_Rounds.Round> yearRounds;
        final HallStatsBuilder.RoundContext ctx;
        final Map<Integer, Map<Integer, Double>> top5Avg;

        AllYearsContext(int year, List<A1_Rounds.Round> yearRounds, HallStatsBuilder.RoundContext ctx, Map<Integer, Map<Integer, Double>> top5Avg) {
            this.year = year;
            this.yearRounds = yearRounds;
            this.ctx = ctx;
            this.top5Avg = top5Avg;
        }
    }

    /**
     * Builds each year's bulk round context and top-5 table ONCE - both
     * halls' summary passes consume the same contexts instead of each
     * re-running the heavy per-year queries.
     */
    private List<AllYearsContext> buildYearContexts(int trueEloTypeId) throws SQLException {
        List<AllYearsContext> contexts = new ArrayList<>();
        for (int year : rounds.getAllYears()) {
            List<A1_Rounds.Round> yearRounds = rounds.getRoundsForYear(year);
            HallStatsBuilder.RoundContext ctx = hallStatsBuilder.buildRoundContext(year, yearRounds, trueEloTypeId);
            contexts.add(new AllYearsContext(year, yearRounds, ctx, hallStatsBuilder.computeTop5AvgByHallPerRound(year, yearRounds, ctx)));
        }
        return contexts;
    }

    /**
     * Reuses the shared builder's per-round computation once per year
     * (rather than re-deriving new aggregation math), returning both the
     * per-year summaries and the most recent year's full HallData (for the
     * "current" Player Stats section and win-probability calc).
     */
    private List<YearSummary> buildYearSummaries(int hallId, String hallName, List<AllYearsContext> yearContexts, HallData[] latestYearDataOut) throws SQLException {
        List<YearSummary> yearSummaries = new ArrayList<>();
        for (AllYearsContext yearContext : yearContexts) {
            HallData yearData = buildHallData(hallId, hallName, yearContext.year, yearContext.yearRounds, yearContext.ctx, yearContext.top5Avg);
            if (yearData.players.isEmpty()) continue;

            YearSummary summary = new YearSummary();
            summary.year = yearContext.year;
            if (yearData.lastRoundOrder != null) {
                summary.finalHallElo = yearData.hallEloByRound.get(yearData.lastRoundOrder);
                summary.finalHallRank = yearData.hallRankByRound.get(yearData.lastRoundOrder);
            }
            for (HallVictoryRecord record : yearData.victoryRecords.values()) {
                summary.totalHallScore += record.hallScore;
                summary.totalOppScore += record.oppScore;
            }
            for (PlayerData p : yearData.players) {
                // Keyed by playerId (not display name) - a player renamed
                // across years is one row, and two different players who
                // happen to share a name never merge. Same convention as
                // InfoPlayer/InfoHall's All-Years views.
                summary.avgSeatByPlayerId.put(p.playerId, p.avgSeat);
                summary.playerNameById.put(p.playerId, p.name);
            }
            yearSummaries.add(summary);
            latestYearDataOut[0] = yearData; // getAllYears() is ascending, so the last iteration is the most recent
        }
        return yearSummaries;
    }

    private CompareResponse generateComparisonAllYears(int hall1Id, String hall1Name, int hall2Id, String hall2Name) throws Exception {
        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
        }

        List<AllYearsContext> yearContexts = buildYearContexts(trueEloTypeId);
        HallData[] latest1Holder = new HallData[1];
        HallData[] latest2Holder = new HallData[1];
        List<YearSummary> summaries1 = buildYearSummaries(hall1Id, hall1Name, yearContexts, latest1Holder);
        List<YearSummary> summaries2 = buildYearSummaries(hall2Id, hall2Name, yearContexts, latest2Holder);

        if (latest1Holder[0] == null) throw new Exception(VictoryRecordCalculator.formatHallName(hall1Name) + " has no data for any year");
        if (latest2Holder[0] == null) throw new Exception(VictoryRecordCalculator.formatHallName(hall2Name) + " has no data for any year");

        double winProbability = calculateWinningProbability(latest1Holder[0], latest2Holder[0]);
        double hall2WinProbability = calculateWinningProbability(latest2Holder[0], latest1Holder[0]);

        String textOutput = generateTextOutputAllYears(hall1Name, summaries1, hall2Name, summaries2, winProbability);
        Path imagePath = generateImageAllYears(hall1Name, summaries1, latest1Holder[0], winProbability,
                hall2Name, summaries2, latest2Holder[0], hall2WinProbability);

        logHelper.logSuccess(String.format("Generated hall comparison: %s vs %s (All Years)", hall1Name, hall2Name));
        return new CompareResponse(textOutput, imagePath, null);
    }

    private String generateTextOutputAllYears(String hall1Name, List<YearSummary> summaries1, String hall2Name, List<YearSummary> summaries2, double winProbability) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**🏛️ Hall Comparison (All Years)**\n\n%s vs %s\n\n", VictoryRecordCalculator.formatHallName(hall1Name), VictoryRecordCalculator.formatHallName(hall2Name)));

        sb.append(String.format("**%s - Season Record (by year):**\n```\n", VictoryRecordCalculator.formatHallName(hall1Name)));
        for (YearSummary s : summaries1) {
            sb.append(String.format("%-6d %s\n", s.year, VictoryRecordCalculator.formatScorePair(s.totalHallScore, s.totalOppScore)));
        }
        sb.append("```\n\n");

        sb.append(String.format("**%s - Season Record (by year):**\n```\n", VictoryRecordCalculator.formatHallName(hall2Name)));
        for (YearSummary s : summaries2) {
            sb.append(String.format("%-6d %s\n", s.year, VictoryRecordCalculator.formatScorePair(s.totalHallScore, s.totalOppScore)));
        }
        sb.append("```\n\n");

        sb.append(String.format("**Win Probability (most recent year's roster):** %.1f%%", winProbability));

        return sb.toString();
    }

    private Path generateImageAllYears(String hall1Name, List<YearSummary> summaries1, HallData latest1, double winProbability1,
                                        String hall2Name, List<YearSummary> summaries2, HallData latest2, double winProbability2) throws Exception {
        String description = String.format("%s vs %s (All Years)", VictoryRecordCalculator.formatHallName(hall1Name), VictoryRecordCalculator.formatHallName(hall2Name));
        String lastYearLabel = summaries1.isEmpty() ? null : String.valueOf(summaries1.get(summaries1.size() - 1).year);
        ComparisonImageGenerator.ImageMetadata metadata = new ComparisonImageGenerator.ImageMetadata("Hall Comparison", description, lastYearLabel);

        List<ComparisonImageGenerator.Section> sections1 = buildSectionsAllYears(hall1Name, summaries1, latest1, winProbability1);
        List<ComparisonImageGenerator.Section> sections2 = buildSectionsAllYears(hall2Name, summaries2, latest2, winProbability2);

        ComparisonImageGenerator.ComparisonData data1 = new ComparisonImageGenerator.ComparisonData(hall1Name, hall1Name, sections1);
        ComparisonImageGenerator.ComparisonData data2 = new ComparisonImageGenerator.ComparisonData(hall2Name, hall2Name, sections2);

        return ComparisonImageGenerator.generateComparisonImage(metadata.title, data1, data2, metadata,
                "CompareHalls", hall1Name, hall2Name);
    }

    private List<ComparisonImageGenerator.Section> buildSectionsAllYears(String hallName, List<YearSummary> yearSummaries, HallData latestYearData, double winProbability) {
        List<ComparisonImageGenerator.Section> sections = new ArrayList<>();

        List<String> hallEloLines = new ArrayList<>();
        hallEloLines.add(String.format("%-6s %-6s %-8s %-8s %-8s", "Year", "Rank", "ΔRank", "Elo", "ΔElo"));
        Double prevElo = null;
        Integer prevRank = null;
        for (YearSummary s : yearSummaries) {
            if (s.finalHallElo == null || s.finalHallRank == null) {
                hallEloLines.add(String.format("%-6d %-6s %-8s %-8s %-8s", s.year, "-", "-", "-", "-"));
                continue;
            }
            String deltaRank = prevRank == null ? "-" : VictoryRecordCalculator.deltaString(prevRank - s.finalHallRank);
            String deltaElo = prevElo == null ? "-" : VictoryRecordCalculator.deltaDoubleString(s.finalHallElo - prevElo);
            hallEloLines.add(String.format("%-6d %-6d %-8s %-8s %-8s", s.year, s.finalHallRank, deltaRank, String.format("%.1f", s.finalHallElo), deltaElo));
            prevElo = s.finalHallElo;
            prevRank = s.finalHallRank;
        }
        sections.add(new ComparisonImageGenerator.Section("Hall Elo (Yr)", hallEloLines));

        List<String> statsLines = new ArrayList<>();
        statsLines.add(String.format("%-8s %-8s %-6s %-7s %-20s", "HallRank", "GlobRank", "ELO", "Capped", "Name"));
        for (PlayerData p : latestYearData.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            statsLines.add(String.format("%-8d %-8d %-6d %-7s %-20s", p.hallRank, p.globalRank, p.elo, p.capped ? "Yes" : "No", name));
        }
        sections.add(new ComparisonImageGenerator.Section("Player Stats (Latest Yr)", statsLines));

        List<String> seatLines = new ArrayList<>();
        Map<String, String> lastKnownName = new HashMap<>();
        List<String> allPlayerIds = collectAllPlayerIdsByRecency(yearSummaries, lastKnownName);
        StringBuilder header = new StringBuilder(String.format("%-20s: ", "Name"));
        for (YearSummary s : yearSummaries) header.append(String.format("%-6s|", s.year));
        seatLines.add(header.toString());
        for (String playerId : allPlayerIds) {
            String name = lastKnownName.get(playerId);
            String displayName = name.length() > 20 ? name.substring(0, 17) + "..." : name;
            StringBuilder line = new StringBuilder(String.format("%-20s: ", displayName));
            for (YearSummary s : yearSummaries) {
                Double avgSeat = s.avgSeatByPlayerId.get(playerId);
                line.append(String.format("%-6s|", avgSeat != null && avgSeat < 999 ? String.format("%.1f", avgSeat) : "-"));
            }
            seatLines.add(line.toString());
        }
        sections.add(new ComparisonImageGenerator.Section("Seating (Avg by Yr)", seatLines));

        List<String> seasonLines = new ArrayList<>();
        for (YearSummary s : yearSummaries) {
            seasonLines.add(String.format("%-6d %s", s.year, VictoryRecordCalculator.formatScorePair(s.totalHallScore, s.totalOppScore)));
        }
        sections.add(new ComparisonImageGenerator.Section("Season Record", seasonLines));

        sections.add(new ComparisonImageGenerator.Section("Win Probability", Arrays.asList(String.format("%.1f%%", winProbability)), true, false));

        return sections;
    }

    /**
     * Every playerId that appeared in any year's summary, ordered by most
     * recent year of appearance (newest first), then last-known name, then
     * id (fully deterministic). Fills {@code lastKnownNameOut} with each
     * id's most recent display name - summaries are ascending by year, so
     * the last write wins.
     * Package-private (not private) so this can be unit-tested directly.
     */
    static List<String> collectAllPlayerIdsByRecency(List<YearSummary> yearSummaries, Map<String, String> lastKnownNameOut) {
        Map<String, Integer> mostRecentYearByPlayer = new LinkedHashMap<>();
        for (YearSummary s : yearSummaries) {
            for (Map.Entry<String, String> e : s.playerNameById.entrySet()) {
                mostRecentYearByPlayer.put(e.getKey(), s.year);
                lastKnownNameOut.put(e.getKey(), e.getValue());
            }
        }
        List<String> ids = new ArrayList<>(mostRecentYearByPlayer.keySet());
        ids.sort((a, b) -> {
            int byYear = Integer.compare(mostRecentYearByPlayer.get(b), mostRecentYearByPlayer.get(a));
            if (byYear != 0) return byYear;
            int byName = lastKnownNameOut.get(a).compareTo(lastKnownNameOut.get(b));
            if (byName != 0) return byName;
            return a.compareTo(b);
        });
        return ids;
    }



    /**
     * Calculates winning probability with capped player filtering, via
     * exhaustive permutation of possible seatings between the two teams' top 5.
     */
    /** Package-private (not private) so this can be unit-tested directly with hand-built rosters. */
    double calculateWinningProbability(HallData hall1, HallData hall2) {
        List<PlayerData> team1 = selectTeamWithCappedFilter(hall1.players);
        List<PlayerData> team2 = selectTeamWithCappedFilter(hall2.players);

        if (team1.isEmpty() || team2.isEmpty()) return 0.0;

        // The capped-filter's >2-capped branch returns its team capped-first,
        // not strongest-first. When the rosters differ in size only team1's
        // FIRST comparedBoards entries play, so both teams are sorted
        // elo-desc here to make that subset "the strongest available boards"
        // (team order is otherwise irrelevant: equal sizes compare every
        // board, and team2's orderings are exhaustively permuted anyway).
        Comparator<PlayerData> eloDesc = Comparator.comparing((PlayerData p) -> p.elo).reversed();
        team1.sort(eloDesc);
        team2.sort(eloDesc);

        int totalPermutations = 0;
        int hall1Wins = 0;
        int comparedBoards = Math.min(team1.size(), team2.size());

        List<int[]> permutations = generatePermutations(team2.size());

        for (int[] perm : permutations) {
            // A tie in elo is a drawn board (0.5 credit), not a loss for
            // team1 - the old strict `>` gave team1 zero credit for any
            // board it merely matched, which is why two evenly-matched
            // rosters could render as a flat 0%/100% split.
            double matchWins = 0;
            for (int i = 0; i < comparedBoards; i++) {
                int cmp = Integer.compare(team1.get(i).elo, team2.get(perm[i]).elo);
                if (cmp > 0) {
                    matchWins += 1.0;
                } else if (cmp == 0) {
                    matchWins += 0.5;
                }
            }
            totalPermutations++;
            // Denominator must be the number of boards actually COMPARED
            // (comparedBoards), not team2.size() - when the rosters differ
            // in size, only comparedBoards boards are ever decided, so
            // dividing by the larger roster's size set an unreachable bar.
            if (matchWins > comparedBoards / 2.0) {
                hall1Wins++;
            }
        }

        return totalPermutations > 0 ? (hall1Wins * 100.0 / totalPermutations) : 50.0;
    }

    /**
     * Selects team of 5 players with capped player filtering:
     * 1. If <=5 players total: use all
     * 2. Take top 5 by ELO
     * 3. If >2 capped: Remove lowest capped until 2 remain
     * 4. Backfill with uncapped to reach 5
     * 5. If still <5: Add lowest capped until 5 reached
     */
    private List<PlayerData> selectTeamWithCappedFilter(List<PlayerData> allPlayers) {
        if (allPlayers.size() <= 5) {
            return new ArrayList<>(allPlayers);
        }

        List<PlayerData> top5 = allPlayers.stream().limit(5).collect(Collectors.toList());

        long cappedCount = top5.stream().filter(p -> p.capped).count();
        if (cappedCount <= 2) {
            return top5;
        }

        List<PlayerData> team = new ArrayList<>();

        List<PlayerData> cappedFromTop5 = top5.stream()
                .filter(p -> p.capped)
                .sorted(Comparator.comparing((PlayerData p) -> p.elo).reversed())
                .limit(2)
                .collect(Collectors.toList());
        team.addAll(cappedFromTop5);

        List<PlayerData> uncappedFromTop5 = top5.stream().filter(p -> !p.capped).collect(Collectors.toList());
        team.addAll(uncappedFromTop5);

        if (team.size() >= 5) {
            return team.stream().limit(5).collect(Collectors.toList());
        }

        List<PlayerData> uncappedBeyondTop5 = allPlayers.stream()
                .skip(5)
                .filter(p -> !p.capped)
                .limit(5 - team.size())
                .collect(Collectors.toList());
        team.addAll(uncappedBeyondTop5);

        if (team.size() < 5) {
            Set<PlayerData> teamSet = new HashSet<>(team);
            List<PlayerData> remainingCapped = allPlayers.stream()
                    .filter(p -> p.capped && !teamSet.contains(p))
                    .sorted(Comparator.comparing(p -> p.elo))
                    .limit(5 - team.size())
                    .collect(Collectors.toList());
            team.addAll(remainingCapped);
        }

        return team;
    }

    private List<int[]> generatePermutations(int n) {
        List<int[]> result = new ArrayList<>();
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;
        permute(arr, 0, result);
        return result;
    }

    private void permute(int[] arr, int start, List<int[]> result) {
        if (start == arr.length) {
            result.add(arr.clone());
            return;
        }
        for (int i = start; i < arr.length; i++) {
            swap(arr, start, i);
            permute(arr, start + 1, result);
            swap(arr, start, i);
        }
    }

    private void swap(int[] arr, int i, int j) {
        int temp = arr[i];
        arr[i] = arr[j];
        arr[j] = temp;
    }

    private String generateTextOutput(HallData hall1, HallData hall2, List<A1_Rounds.Round> roundsToInclude, double winProbability, String selectedRound) {
        StringBuilder sb = new StringBuilder();
        sb.append("**🏛️ Hall Comparison**\n\n");
        sb.append(String.format("**%s** vs **%s**\n\n", VictoryRecordCalculator.formatHallName(hall1.hallName), VictoryRecordCalculator.formatHallName(hall2.hallName)));
        sb.append(String.format("📊 **Winning Probability:** %s has **%.1f%%** chance to win\n", VictoryRecordCalculator.formatHallName(hall1.hallName), winProbability));
        sb.append(String.format("📅 **Rounds:** %s\n\n", selectedRound.equalsIgnoreCase("all") ? "All" : selectedRound));

        sb.append(generateHallDetails(hall1, roundsToInclude));
        sb.append("\n");
        sb.append(generateHallDetails(hall2, roundsToInclude));

        return sb.toString();
    }

    private String generateHallDetails(HallData hall, List<A1_Rounds.Round> roundsToInclude) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("━━━ **%s** ━━━\n\n", VictoryRecordCalculator.formatHallName(hall.hallName)));

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
        sb.append("```\n\n");

        return sb.toString();
    }

    /**
     * The TRUE latest round label across BOTH halls (max comparison), not
     * just whichever hall happened to be passed as hall1 - a hall that
     * stopped playing earlier must not freeze this label if the OTHER hall
     * played on longer (the same drift {@link MatchScoreUtils#latestRoundLabel}
     * was extracted to eliminate for the rank commands). Package-private so
     * this can be unit-tested directly with hand-built HallData instances.
     */
    static String latestRoundLabelAcrossBothHalls(HallData hall1, HallData hall2) {
        if (hall1.lastRoundOrder == null) return hall2.lastRoundLabel;
        if (hall2.lastRoundOrder == null) return hall1.lastRoundLabel;
        return hall1.lastRoundOrder >= hall2.lastRoundOrder ? hall1.lastRoundLabel : hall2.lastRoundLabel;
    }

    private Path generateImage(HallData hall1, HallData hall2, List<A1_Rounds.Round> roundsToInclude, double winProbability) throws Exception {
        String lastRoundLabel = latestRoundLabelAcrossBothHalls(hall1, hall2);
        String description = String.format("%s vs %s", VictoryRecordCalculator.formatHallName(hall1.hallName), VictoryRecordCalculator.formatHallName(hall2.hallName));
        ComparisonImageGenerator.ImageMetadata metadata = new ComparisonImageGenerator.ImageMetadata("Hall Comparison", description, lastRoundLabel);

        // hall2's own win probability is computed independently (not
        // 100 - winProbability) - calculateWinningProbability only counts
        // permutations hall1 STRICTLY wins, so an exact-tie permutation
        // (possible whenever comparedBoards is even) counts toward neither
        // hall; treating "100 - P(hall1 wins)" as "P(hall2 wins)" silently
        // folded every tied permutation into hall2's side, so the two
        // displayed percentages were not a symmetric, honest pair of
        // measures (they need not sum to 100 - the gap is the tie chance).
        double hall2WinProbability = calculateWinningProbability(hall2, hall1);

        List<ComparisonImageGenerator.Section> sections1 = buildSections(hall1, roundsToInclude, winProbability);
        List<ComparisonImageGenerator.Section> sections2 = buildSections(hall2, roundsToInclude, hall2WinProbability);

        ComparisonImageGenerator.ComparisonData data1 = new ComparisonImageGenerator.ComparisonData(hall1.hallName, hall1.hallName, sections1);
        ComparisonImageGenerator.ComparisonData data2 = new ComparisonImageGenerator.ComparisonData(hall2.hallName, hall2.hallName, sections2);

        return ComparisonImageGenerator.generateComparisonImage(metadata.title, data1, data2, metadata,
                "CompareHalls", hall1.hallName, hall2.hallName);
    }

    private List<ComparisonImageGenerator.Section> buildSections(HallData hall, List<A1_Rounds.Round> roundsToInclude, double winProbability) {
        List<ComparisonImageGenerator.Section> sections = new ArrayList<>();

        List<String> hallEloLines = new ArrayList<>();
        hallEloLines.add(String.format("%-4s %-6s %-8s %-8s %-8s", "Rnd", "Rank", "ΔRank", "Elo", "ΔElo"));
        Double prevElo = null;
        Integer prevRank = null;
        for (A1_Rounds.Round round : roundsToInclude) {
            Double elo = hall.hallEloByRound.get(round.roundOrder);
            Integer rank = hall.hallRankByRound.get(round.roundOrder);
            if (elo == null || rank == null) {
                hallEloLines.add(String.format("%-4s %-6s %-8s %-8s %-8s", round.roundLabel, "-", "-", "-", "-"));
                continue;
            }
            String deltaRank = prevRank == null ? "-" : VictoryRecordCalculator.deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : VictoryRecordCalculator.deltaDoubleString(elo - prevElo);
            hallEloLines.add(String.format("%-4s %-6d %-8s %-8s %-8s", round.roundLabel, rank, deltaRank, String.format("%.1f", elo), deltaElo));
            prevElo = elo;
            prevRank = rank;
        }
        sections.add(new ComparisonImageGenerator.Section("Hall Elo", hallEloLines));

        List<String> statsLines = new ArrayList<>();
        statsLines.add(String.format("%-8s %-8s %-6s %-7s %-20s", "HallRank", "GlobRank", "ELO", "Capped", "Name"));
        for (PlayerData p : hall.players) {
            String name = p.name.length() > 20 ? p.name.substring(0, 17) + "..." : p.name;
            statsLines.add(String.format("%-8d %-8d %-6d %-7s %-20s", p.hallRank, p.globalRank, p.elo, p.capped ? "Yes" : "No", name));
        }
        sections.add(new ComparisonImageGenerator.Section("Player Stats", statsLines));

        List<PlayerData> sortedBySeat = new ArrayList<>(hall.players);
        sortedBySeat.sort((a, b) -> Double.compare(a.avgSeat, b.avgSeat));

        List<String> seatLines = new ArrayList<>();
        StringBuilder header = new StringBuilder(String.format("%-4s %-15s: ", "Avg", "Name"));
        for (A1_Rounds.Round round : roundsToInclude) header.append(String.format("%-3s|", round.roundLabel));
        seatLines.add(header.toString());
        for (PlayerData p : sortedBySeat) {
            String name = p.name.length() > 15 ? p.name.substring(0, 12) + "..." : p.name;
            String avgStr = p.avgSeat < 999 ? String.format("%.1f", p.avgSeat) : "-";
            StringBuilder line = new StringBuilder(String.format("%-4s %-15s: ", avgStr, name));
            for (A1_Rounds.Round round : roundsToInclude) {
                Integer seat = p.seatByRound.get(round.roundOrder);
                line.append(String.format("%-3s|", seat != null ? seat.toString() : "-"));
            }
            seatLines.add(line.toString());
        }
        sections.add(new ComparisonImageGenerator.Section("Seating", seatLines));

        List<ComparisonImageGenerator.HallVictoryEntry> victoryEntries = new ArrayList<>();
        for (A1_Rounds.Round round : roundsToInclude) {
            HallVictoryRecord record = hall.victoryRecords.get(round.roundOrder);
            if (record == null) {
                victoryEntries.add(new ComparisonImageGenerator.HallVictoryEntry(round.roundLabel, true));
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

            victoryEntries.add(new ComparisonImageGenerator.HallVictoryEntry(
                    round.roundLabel, hallEmoji, hallEloStr, hallFormatted, score, oppHallFormatted, oppEloStr, oppEmoji,
                    record.outcome, oppOutcome));
        }
        sections.add(ComparisonImageGenerator.Section.forHallVictory("Victory Record", victoryEntries));

        sections.add(new ComparisonImageGenerator.Section("Win Probability", Arrays.asList(String.format("%.1f%%", winProbability)), true, false));

        return sections;
    }


}
