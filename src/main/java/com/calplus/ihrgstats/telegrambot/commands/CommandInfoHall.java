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
 * Command handler for /infohall command.
 * Shows detailed information for a single hall, scoped to the current
 * year (settings.currentYear).
 */
public class CommandInfoHall {
    private final LogHelper logHelper;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final RankingQueryHelper rankingQueryHelper = new RankingQueryHelper();

    private static final Map<String, HallInfoSelectionState> userSelectionStates = new HashMap<>();

    private static class HallInfoSelectionState extends SelectionState {
        int hallId;
        String hallName;
    }

    public CommandInfoHall() {
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

        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        for (A3_Halls.Hall hall : allHalls) {
            if (hall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) continue;
            labels.add(hall.hallName);
            callbacks.add("infohall_hall_" + hall.id);
        }
        labels.add("❌ Cancel");
        callbacks.add("infohall_cancel");

        String message = "**🏛️ Hall Information**\n\nSelect a **hall**:";
        return new InfoResponse(message, (Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
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

            List<String> labels = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            labels.add("All Rounds");
            callbacks.add("infohall_round_all");
            for (A1_Rounds.Round round : availableRounds) {
                labels.add(round.roundLabel);
                callbacks.add("infohall_round_" + round.roundOrder);
            }
            labels.add("❌ Cancel");
            callbacks.add("infohall_cancel");

            String message = String.format("**🏛️ Hall Information**\n\nHall: **%s**\n\nSelect a **round**:", state.hallName);
            return new InfoResponse(message, (Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
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
        Integer year = YearContext.getCurrentYear();
        userSelectionStates.remove(userId);
        if (year == null) {
            return new InfoResponse("⚠️ No current year set.", (Path) null, null);
        }

        try {
            return generateHallInfo(state.hallId, state.hallName, year, selectedRound);
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

    /** Per-round player data within a hall, keyed by round_order for display. */
    private static class PlayerData {
        String playerId;
        String name;
        boolean capped;
        int hallRank;
        int globalRank;
        int elo; // last known TrueElo within the included rounds
        double avgSeat = 999;
        Map<Integer, String> roundLabelByOrder = new TreeMap<>();
        Map<Integer, Integer> eloByRound = new TreeMap<>();
        Map<Integer, Integer> seatByRound = new TreeMap<>();
        Map<Integer, Integer> outcomeByRound = new TreeMap<>(); // legacy 1/0/-1 convention
        Map<Integer, String> oppNameByRound = new TreeMap<>();
        Map<Integer, String> oppHallByRound = new TreeMap<>();
        Integer lastRoundOrder;
    }

    /** Aggregate hall-level data, built from the roster's PlayerData. */
    private static class HallData {
        int hallId;
        String hallName;
        List<PlayerData> players = new ArrayList<>();
        Integer lastRoundOrder;
        String lastRoundLabel;
        Map<Integer, Double> hallEloByRound = new TreeMap<>();
        Map<Integer, Integer> hallRankByRound = new TreeMap<>();
        Map<Integer, HallVictoryRecord> victoryRecords = new TreeMap<>();
    }

    private static class HallVictoryRecord {
        double hallScore;
        double oppScore;
        String oppHallName;
        int outcome; // legacy 1/0/-1
        Double oppHallElo;
    }

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
            throw new IllegalStateException("Hall " + hallName + " has no player data for " + year);
        }

        String textOutput = generateTextOutput(hallData, roundsToInclude);
        Path imagePath = generateImage(hallData, roundsToInclude);

        logHelper.logSuccess(String.format("Generated hall info: %s (rounds: %s)", hallName, selectedRound));
        return new InfoResponse(textOutput, imagePath, null);
    }

    private HallData fetchHallData(int hallId, String hallName, int year, List<A1_Rounds.Round> roundsToInclude, int trueEloTypeId) throws SQLException {
        HallData hallData = new HallData();
        hallData.hallId = hallId;
        hallData.hallName = hallName;

        List<B6_PlayerYearStatus.Status> statuses = playerYearStatus.getStatusesForHallAndYear(hallId, year);
        for (B6_PlayerYearStatus.Status status : statuses) {
            PlayerData player = new PlayerData();
            player.playerId = status.playerId;
            String name = playerNames.getNameForYear(status.playerId, year);
            player.name = name != null ? name : status.playerId;
            player.capped = status.capped;

            for (A1_Rounds.Round round : roundsToInclude) {
                D11_PlayerRatings.Rating rating = rankingQueryHelper.getPointInTimeRating(status.playerId, round.id, trueEloTypeId);
                if (rating == null) continue;

                int elo = (int) Math.round(rating.ratingValue);
                player.eloByRound.put(round.roundOrder, elo);
                player.roundLabelByOrder.put(round.roundOrder, round.roundLabel);
                player.lastRoundOrder = round.roundOrder;
                player.elo = elo;

                Map<String, D11_PlayerRatings.Rating> allRatings = rankingQueryHelper.getLatestRatingsUpToRound(year, round.roundOrder, trueEloTypeId);
                player.globalRank = rankingQueryHelper.calculateRank(allRatings, rating.ratingValue);

                C9_MatchParticipants.Participant me = participants.getParticipantForPlayerAndRound(status.playerId, round.id);
                if (me != null) {
                    if (me.hallSeatNumber != null) player.seatByRound.put(round.roundOrder, me.hallSeatNumber);
                    player.outcomeByRound.put(round.roundOrder, VictoryRecordCalculator.toLegacyOutcome(me.outcome));

                    C9_MatchParticipants.Participant opp = participants.getOpponentParticipant(me.matchId, status.playerId);
                    if (opp != null) {
                        if (opp.playerId.equals(B4_Players.WALKOVER_PLAYER_ID)) {
                            player.oppNameByRound.put(round.roundOrder, "WALKOVER");
                        } else {
                            String oppName = playerNames.getNameForYear(opp.playerId, year);
                            player.oppNameByRound.put(round.roundOrder, oppName != null ? oppName : opp.playerId);
                        }
                        A3_Halls.Hall oppHall = halls.getHallById(opp.hallId);
                        if (oppHall != null && !oppHall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) {
                            player.oppHallByRound.put(round.roundOrder, oppHall.hallName);
                        }
                    }
                }
            }

            if (player.eloByRound.isEmpty()) continue; // never had data - exclude, matching legacy behavior

            calculateAvgSeat(player, roundsToInclude);
            hallData.players.add(player);

            if (hallData.lastRoundOrder == null || (player.lastRoundOrder != null && player.lastRoundOrder > hallData.lastRoundOrder)) {
                hallData.lastRoundOrder = player.lastRoundOrder;
                hallData.lastRoundLabel = player.roundLabelByOrder.get(player.lastRoundOrder);
            }
        }

        // Sort by last-known elo descending, assign hall-local rank
        hallData.players.sort((a, b) -> Integer.compare(b.elo, a.elo));
        for (int i = 0; i < hallData.players.size(); i++) {
            hallData.players.get(i).hallRank = i + 1;
        }

        Map<Integer, Map<Integer, Double>> top5AvgByRoundThenHall = computeTop5AvgByHallPerRound(year, roundsToInclude, trueEloTypeId);
        calculateHallEloAndRank(hallData, top5AvgByRoundThenHall);
        calculateHallVictoryRecords(hallData, roundsToInclude, top5AvgByRoundThenHall);

        return hallData;
    }

    private void calculateAvgSeat(PlayerData player, List<A1_Rounds.Round> roundsToInclude) {
        List<Integer> seats = new ArrayList<>();
        for (A1_Rounds.Round round : roundsToInclude) {
            Integer seat = player.seatByRound.get(round.roundOrder);
            if (seat != null) seats.add(seat);
        }
        player.avgSeat = seats.isEmpty() ? 999 : seats.stream().mapToInt(Integer::intValue).average().orElse(999);
    }

    /** Computes each hall's average TrueElo of its top 5 players, per round, across ALL halls (needed for ranking). */
    private Map<Integer, Map<Integer, Double>> computeTop5AvgByHallPerRound(int year, List<A1_Rounds.Round> roundsToInclude, int trueEloTypeId) throws SQLException {
        List<B6_PlayerYearStatus.Status> allStatuses = playerYearStatus.getActiveStatusesForYear(year);
        Map<Integer, Map<Integer, Double>> result = new HashMap<>();

        for (A1_Rounds.Round round : roundsToInclude) {
            Map<Integer, List<Double>> elosByHall = new HashMap<>();
            for (B6_PlayerYearStatus.Status status : allStatuses) {
                D11_PlayerRatings.Rating rating = rankingQueryHelper.getPointInTimeRating(status.playerId, round.id, trueEloTypeId);
                if (rating == null) continue;
                elosByHall.computeIfAbsent(status.hallId, k -> new ArrayList<>()).add(rating.ratingValue);
            }

            Map<Integer, Double> avgByHall = new HashMap<>();
            for (Map.Entry<Integer, List<Double>> entry : elosByHall.entrySet()) {
                List<Double> elos = entry.getValue();
                elos.sort(Collections.reverseOrder());
                int count = Math.min(5, elos.size());
                double sum = 0;
                for (int i = 0; i < count; i++) sum += elos.get(i);
                avgByHall.put(entry.getKey(), sum / count);
            }
            result.put(round.roundOrder, avgByHall);
        }
        return result;
    }

    private void calculateHallEloAndRank(HallData hallData, Map<Integer, Map<Integer, Double>> top5AvgByRoundThenHall) {
        for (Map.Entry<Integer, Map<Integer, Double>> entry : top5AvgByRoundThenHall.entrySet()) {
            int roundOrder = entry.getKey();
            Map<Integer, Double> avgByHall = entry.getValue();
            Double myAvg = avgByHall.get(hallData.hallId);
            if (myAvg == null) continue;

            hallData.hallEloByRound.put(roundOrder, myAvg);
            long higherCount = avgByHall.entrySet().stream()
                    .filter(e -> !e.getKey().equals(hallData.hallId) && e.getValue() > myAvg)
                    .count();
            hallData.hallRankByRound.put(roundOrder, (int) higherCount + 1);
        }
    }

    private void calculateHallVictoryRecords(HallData hallData, List<A1_Rounds.Round> roundsToInclude, Map<Integer, Map<Integer, Double>> top5AvgByRoundThenHall) throws SQLException {
        Map<String, Integer> hallNameToId = new HashMap<>();
        for (A3_Halls.Hall hall : halls.getAllHalls()) {
            hallNameToId.put(hall.hallName, hall.id);
        }

        for (A1_Rounds.Round round : roundsToInclude) {
            int roundOrder = round.roundOrder;
            List<PlayerData> playingPlayers = hallData.players.stream()
                    .filter(p -> p.seatByRound.containsKey(roundOrder))
                    .collect(Collectors.toList());
            if (playingPlayers.isEmpty()) continue;

            double hallScore = 0.0;
            Map<String, Double> oppScores = new HashMap<>();

            for (PlayerData player : playingPlayers) {
                Integer outcome = player.outcomeByRound.get(roundOrder);
                String oppHallName = player.oppHallByRound.get(roundOrder);
                String oppName = player.oppNameByRound.get(roundOrder);
                if (outcome == null) continue;

                Double points = VictoryRecordCalculator.outcomeToPoints(outcome);
                if (points == null) continue;
                hallScore += points;

                if (oppHallName != null && !"WALKOVER".equalsIgnoreCase(oppHallName) && !"WALKOVER".equalsIgnoreCase(oppName)) {
                    oppScores.merge(oppHallName, 1.0 - points, Double::sum);
                }
            }

            String primaryOppHall = null;
            if (!oppScores.isEmpty()) {
                primaryOppHall = oppScores.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);
            }

            boolean allWalkovers = playingPlayers.stream()
                    .allMatch(p -> "WALKOVER".equalsIgnoreCase(p.oppNameByRound.get(roundOrder)));
            if (allWalkovers) {
                primaryOppHall = "WALKOVER";
            }

            if (primaryOppHall != null) {
                double oppScore = oppScores.getOrDefault(primaryOppHall, 0.0);
                HallVictoryRecord record = new HallVictoryRecord();
                record.hallScore = hallScore;
                record.oppScore = oppScore;
                record.oppHallName = primaryOppHall;
                record.outcome = hallScore > oppScore ? 1 : (hallScore < oppScore ? -1 : 0);

                if (!"WALKOVER".equalsIgnoreCase(primaryOppHall)) {
                    Integer oppHallId = hallNameToId.get(primaryOppHall);
                    Map<Integer, Double> avgByHall = top5AvgByRoundThenHall.get(roundOrder);
                    if (oppHallId != null && avgByHall != null) {
                        record.oppHallElo = avgByHall.get(oppHallId);
                    }
                }

                hallData.victoryRecords.put(roundOrder, record);
            }
        }
    }

    private String generateTextOutput(HallData hall, List<A1_Rounds.Round> roundsToInclude) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**🏛️ Hall %s Information**\n\n", hall.hallName));
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
            String deltaRank = prevRank == null ? "-" : deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : deltaDoubleString(elo - prevElo);
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

            String formattedHall = formatHallNameForImage(hall.hallName);
            String formattedOppHall = "WALKOVER".equalsIgnoreCase(record.oppHallName) ? "WALKOVER" : formatHallNameForImage(record.oppHallName);
            if ("WALKOVER".equalsIgnoreCase(record.oppHallName)) oppEloStr = "-";

            String score = formatScorePair(record.hallScore, record.oppScore);

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
        metadata.subtitle = formatHallNameForImage(hall.hallName);
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
            String deltaRank = prevRank == null ? "-" : deltaString(prevRank - rank);
            String deltaElo = prevElo == null ? "-" : deltaDoubleString(elo - prevElo);
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

            String hallFormatted = formatHallNameForImage(hall.hallName);
            String oppHallFormatted;
            if ("WALKOVER".equalsIgnoreCase(record.oppHallName)) {
                oppHallFormatted = "WALKOVER";
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
                oppEloStr = "-";
            } else {
                oppHallFormatted = formatHallNameForImage(record.oppHallName);
            }

            String score = formatScorePair(record.hallScore, record.oppScore);

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

    private String formatHallNameForImage(String hallName) {
        try {
            Integer.parseInt(hallName);
            return "Hall " + hallName;
        } catch (NumberFormatException e) {
            return hallName + " Hall";
        }
    }

    private static String deltaString(int change) {
        if (change > 0) return "+" + change;
        if (change < 0) return "-" + Math.abs(change);
        return "=";
    }

    private static String deltaDoubleString(double change) {
        if (change > 0) return String.format("+%.1f", change);
        if (change < 0) return String.format("-%.1f", Math.abs(change));
        return "=";
    }

    private String formatScorePair(double hallScore, double oppScore) {
        if (hallScore == Math.floor(hallScore) && oppScore == Math.floor(oppScore)) {
            return String.format("%d-%d", (int) hallScore, (int) oppScore);
        }
        return String.format("%.1f-%.1f", hallScore, oppScore);
    }
}
