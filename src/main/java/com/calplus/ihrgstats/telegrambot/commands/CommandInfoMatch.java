package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.MatchScoreUtils;
import com.calplus.ihrgstats.telegrambot.utils.RankingQueryHelper;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CommandInfoMatch: Shows match information for a specific round, scoped to
 * the current year (settings.currentYear).
 * - Table 1: Match Info (all hall matchups for that round with results)
 * - Table 2: Scores (cumulative match wins and board wins up to that round)
 */
public class CommandInfoMatch {
    private final LogHelper logHelper;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final RankingQueryHelper rankingQueryHelper = new RankingQueryHelper();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();

    private static final Map<String, SelectionState> userSelectionStates = new ConcurrentHashMap<>();

    private static class MatchInfoSelectionState extends SelectionState {
        String selectedRound;
    }

    public CommandInfoMatch() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        this.logHelper = new LogHelper();
    }

    public static class MatchResponse extends CommandResponse {
        public MatchResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            super(message, imagePath, buttonConfig);
        }
    }

    public MatchResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("%s requested /infomatch command", com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId)));

        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.put(userId, new MatchInfoSelectionState());

        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            return new MatchResponse("⚠️ No current year set. An admin must set `settings.currentYear` first.", null, null);
        }

        List<A1_Rounds.Round> availableRounds;
        try {
            availableRounds = rounds.getAllRounds();
        } catch (SQLException e) {
            logHelper.logError("Database error fetching rounds: " + e.getMessage());
            return new MatchResponse("❌ Database error fetching rounds.", null, null);
        }

        if (availableRounds.isEmpty()) {
            return new MatchResponse("ℹ️ No rounds found. Please upload round data first.", null, null);
        }

        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();

        // Round picker spans every year (not just the current one) - round
        // numbers repeat across years, so each button's label/callback must
        // disambiguate by year too.
        A1_Rounds.Round latest = availableRounds.get(availableRounds.size() - 1);
        labels.add("⏱️ Latest Round (" + latest.year + " · " + latest.roundLabel + ")");
        callbacks.add("infomatch_round_latest");

        for (A1_Rounds.Round round : availableRounds) {
            labels.add(round.year + " · " + round.roundLabel);
            callbacks.add("infomatch_round_" + round.year + "_" + round.roundOrder);
        }

        labels.add("❌ Cancel");
        callbacks.add("infomatch_cancel");

        String message = "**⚔️ Match Information**\n\nSelect a **round**:";
        return new MatchResponse(message, null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }

    public MatchResponse handleRoundSelection(String userId, String selectedRound) {
        logHelper.logInfo(String.format("%s selected round: %s", com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId), selectedRound));

        userSelectionStates.remove(userId);

        try {
            int year;
            int roundOrder;
            if (selectedRound.equalsIgnoreCase("latest")) {
                List<A1_Rounds.Round> allRounds = rounds.getAllRounds();
                if (allRounds.isEmpty()) {
                    return new MatchResponse("ℹ️ No rounds found.", null, null);
                }
                A1_Rounds.Round latest = allRounds.get(allRounds.size() - 1);
                year = latest.year;
                roundOrder = latest.roundOrder;
            } else {
                // Encoded as "{year}_{roundOrder}" by the round picker above -
                // round numbers repeat across years, so the year must travel
                // with the selection instead of being assumed from settings.
                String[] parts = selectedRound.split("_", 2);
                year = Integer.parseInt(parts[0]);
                roundOrder = Integer.parseInt(parts[1]);
            }

            MatchResponse response = generateMatchInfo(year, roundOrder);
            return response;
        } catch (Exception e) {
            logHelper.logError("Failed to generate match info: " + e.getMessage());
            e.printStackTrace();
            return new MatchResponse("❌ Error generating match information: " + e.getMessage(), null, null);
        }
    }

    public MatchResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new MatchResponse("❌ Match information request cancelled.", null, null);
    }

    private static class MatchupData {
        String hall1Name;
        String hall2Name; // "WALKOVER" possible
        double hall1Score;
        double hall2Score;
        int outcome; // 1 = hall1 win, 0 = draw, -1 = hall1 loss
        Double hall1Elo;
        Double hall2Elo;
    }

    private static class HallScoreData {
        int hallId;
        String hallName;
        double matchWins;
        double boardWins;
        int rank;

        HallScoreData(int hallId, String hallName) {
            this.hallId = hallId;
            this.hallName = hallName;
        }
    }

    private MatchResponse generateMatchInfo(int year, int roundOrder) throws Exception {
        A1_Rounds.Round round = rounds.getRoundByYearAndOrder(year, roundOrder);
        if (round == null) {
            throw new IllegalStateException("Round " + roundOrder + " not found for " + year);
        }

        List<A1_Rounds.Round> availableRounds = rounds.getRoundsForYear(year);
        List<A1_Rounds.Round> roundsUpToSelected = availableRounds.stream()
                .filter(r -> r.roundOrder <= roundOrder)
                .collect(Collectors.toList());

        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
        }

        Map<Integer, Double> hallEloForRound = computeTop5AvgByHallForRound(year, round.id, trueEloTypeId);

        List<MatchupData> matchups = fetchMatchupsForRound(round.id, hallEloForRound);
        List<HallScoreData> scores = calculateCumulativeScores(roundsUpToSelected);

        String textOutput = generateTextOutput(matchups, scores, round);
        Path imagePath = generateImage(matchups, scores, round);

        logHelper.logSuccess(String.format("Generated match info for round: %s", round.roundLabel));
        return new MatchResponse(textOutput, imagePath, null);
    }

    /** Average TrueElo of each hall's top 5 players, for one specific round. */
    private Map<Integer, Double> computeTop5AvgByHallForRound(int year, int roundId, int trueEloTypeId) throws SQLException {
        List<B6_PlayerYearStatus.Status> allStatuses = playerYearStatus.getActiveStatusesForYear(year);
        Map<Integer, List<Double>> elosByHall = new HashMap<>();
        for (B6_PlayerYearStatus.Status status : allStatuses) {
            D11_PlayerRatings.Rating rating = rankingQueryHelper.getPointInTimeRating(status.playerId, roundId, trueEloTypeId);
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
        return avgByHall;
    }

    /**
     * Groups this round's match_participants by (hall, opponent hall) pair to
     * reconstruct team-level matchups. WALKOVER opponents are detected via the
     * WLKOVR sentinel player_id (not via a blank hall column, as legacy did).
     */
    private List<MatchupData> fetchMatchupsForRound(int roundId, Map<Integer, Double> hallEloForRound) throws SQLException {
        List<C9_MatchParticipants.Participant> allParticipants = participants.getParticipantsForRound(roundId);

        Map<Integer, Map<Integer, Double>> hallVsHallPoints = new HashMap<>();
        Map<Integer, Double> hallVsWalkoverPoints = new HashMap<>();

        for (C9_MatchParticipants.Participant p : allParticipants) {
            if (p.playerId.equals(B4_Players.WALKOVER_PLAYER_ID)) continue;
            C9_MatchParticipants.Participant opp = participants.getOpponentParticipant(p.matchId, p.playerId);
            if (opp == null) continue;

            double points = p.outcome; // schema stores outcome as 1.0/0.5/0.0 already
            if (opp.playerId.equals(B4_Players.WALKOVER_PLAYER_ID)) {
                hallVsWalkoverPoints.merge(p.hallId, points, Double::sum);
            } else {
                hallVsHallPoints.computeIfAbsent(p.hallId, k -> new HashMap<>()).merge(opp.hallId, points, Double::sum);
            }
        }

        Map<Integer, String> hallNames = new HashMap<>();
        for (A3_Halls.Hall hall : halls.getAllHalls()) hallNames.put(hall.id, hall.hallName);

        List<MatchupData> matchups = new ArrayList<>();
        Set<String> processedPairs = new HashSet<>();

        for (Map.Entry<Integer, Map<Integer, Double>> entry : hallVsHallPoints.entrySet()) {
            int hallId1 = entry.getKey();
            for (Map.Entry<Integer, Double> oppEntry : entry.getValue().entrySet()) {
                int hallId2 = oppEntry.getKey();
                String pairKey = hallId1 < hallId2 ? hallId1 + "_" + hallId2 : hallId2 + "_" + hallId1;
                if (processedPairs.contains(pairKey)) continue;
                processedPairs.add(pairKey);

                double hall1Score = oppEntry.getValue();
                double hall2Score = hallVsHallPoints.getOrDefault(hallId2, Collections.emptyMap()).getOrDefault(hallId1, 0.0);
                int outcome = hall1Score > hall2Score ? 1 : (hall1Score < hall2Score ? -1 : 0);

                MatchupData m = new MatchupData();
                m.hall1Name = hallNames.get(hallId1);
                m.hall2Name = hallNames.get(hallId2);
                m.hall1Score = hall1Score;
                m.hall2Score = hall2Score;
                m.outcome = outcome;
                m.hall1Elo = hallEloForRound.get(hallId1);
                m.hall2Elo = hallEloForRound.get(hallId2);
                matchups.add(m);
            }
        }

        for (Map.Entry<Integer, Double> entry : hallVsWalkoverPoints.entrySet()) {
            int hallId1 = entry.getKey();
            MatchupData m = new MatchupData();
            m.hall1Name = hallNames.get(hallId1);
            m.hall2Name = "WALKOVER";
            m.hall1Score = entry.getValue();
            m.hall2Score = 0.0;
            m.outcome = m.hall1Score > m.hall2Score ? 1 : (m.hall1Score < m.hall2Score ? -1 : 0);
            m.hall1Elo = hallEloForRound.get(hallId1);
            m.hall2Elo = null;
            matchups.add(m);
        }

        matchups.sort(Comparator.comparing(m -> m.hall1Name));
        return matchups;
    }

    /**
     * Calculates cumulative match wins and board wins across all rounds up to
     * (and including) the selected round.
     */
    private List<HallScoreData> calculateCumulativeScores(List<A1_Rounds.Round> roundsUpToSelected) throws SQLException {
        Map<Integer, HallScoreData> hallScores = new HashMap<>();
        Map<Integer, String> hallNames = new HashMap<>();
        for (A3_Halls.Hall hall : halls.getAllHalls()) hallNames.put(hall.id, hall.hallName);

        for (A1_Rounds.Round round : roundsUpToSelected) {
            List<C9_MatchParticipants.Participant> allParticipants = participants.getParticipantsForRound(round.id);

            Map<Integer, Map<Integer, Double>> roundHallVsHallPoints = new HashMap<>();
            Map<Integer, Integer> walkoverCountPerHall = new HashMap<>();
            Map<Integer, Integer> totalParticipantsPerHall = new HashMap<>();

            for (C9_MatchParticipants.Participant p : allParticipants) {
                if (p.playerId.equals(B4_Players.WALKOVER_PLAYER_ID)) continue;
                C9_MatchParticipants.Participant opp = participants.getOpponentParticipant(p.matchId, p.playerId);
                if (opp == null) continue;

                double points = p.outcome;
                HallScoreData hsd = hallScores.computeIfAbsent(p.hallId, k -> new HallScoreData(p.hallId, hallNames.get(p.hallId)));
                hsd.boardWins += points;
                totalParticipantsPerHall.merge(p.hallId, 1, Integer::sum);

                if (opp.playerId.equals(B4_Players.WALKOVER_PLAYER_ID)) {
                    walkoverCountPerHall.merge(p.hallId, 1, Integer::sum);
                } else {
                    roundHallVsHallPoints.computeIfAbsent(p.hallId, k -> new HashMap<>()).merge(opp.hallId, points, Double::sum);
                }
            }

            // If an entire team faced WALKOVER this round (every board this hall
            // played this round was a walkover, not just some of them), normalize
            // to the "3-2" convention - derived from the hall's ACTUAL observed
            // board count this round, not a hardcoded assumption of 5 boards.
            for (Map.Entry<Integer, Integer> entry : walkoverCountPerHall.entrySet()) {
                int hallId = entry.getKey();
                int walkoverCount = entry.getValue();
                int totalForHall = totalParticipantsPerHall.getOrDefault(hallId, 0);
                if (walkoverCount > 0 && walkoverCount == totalForHall) {
                    double winnerNormalized = MatchScoreUtils.computeWalkoverDefaultScore(walkoverCount);
                    HallScoreData hsd = hallScores.get(hallId);
                    hsd.boardWins -= (walkoverCount - winnerNormalized);
                    hsd.matchWins += 1.0;
                }
            }

            Set<String> processedPairs = new HashSet<>();
            for (Map.Entry<Integer, Map<Integer, Double>> entry : roundHallVsHallPoints.entrySet()) {
                int hallId1 = entry.getKey();
                for (Map.Entry<Integer, Double> oppEntry : entry.getValue().entrySet()) {
                    int hallId2 = oppEntry.getKey();
                    String pairKey = hallId1 < hallId2 ? hallId1 + "_" + hallId2 : hallId2 + "_" + hallId1;
                    if (processedPairs.contains(pairKey)) continue;
                    processedPairs.add(pairKey);

                    double hall1Score = oppEntry.getValue();
                    double hall2Score = roundHallVsHallPoints.getOrDefault(hallId2, Collections.emptyMap()).getOrDefault(hallId1, 0.0);

                    if (hall1Score > hall2Score) {
                        hallScores.get(hallId1).matchWins += 1.0;
                    } else if (hall1Score < hall2Score) {
                        hallScores.computeIfAbsent(hallId2, k -> new HallScoreData(hallId2, hallNames.get(hallId2))).matchWins += 1.0;
                    } else {
                        hallScores.get(hallId1).matchWins += 0.5;
                        hallScores.computeIfAbsent(hallId2, k -> new HallScoreData(hallId2, hallNames.get(hallId2))).matchWins += 0.5;
                    }
                }
            }
        }

        List<HallScoreData> scoreList = new ArrayList<>(hallScores.values());
        scoreList.sort((a, b) -> {
            int cmp = Double.compare(b.matchWins, a.matchWins);
            if (cmp == 0) cmp = Double.compare(b.boardWins, a.boardWins);
            return cmp;
        });
        for (int i = 0; i < scoreList.size(); i++) scoreList.get(i).rank = i + 1;

        return scoreList;
    }

    private String generateTextOutput(List<MatchupData> matchups, List<HallScoreData> scores, A1_Rounds.Round round) {
        String homeHall = PropertyResolver.getProperty("settings.homeHall", "");

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**⚔️ Match Information - %s**\n\n", round.roundLabel));

        sb.append("**📋 Match Info:**\n```\n");
        if (matchups.isEmpty()) {
            sb.append("No matches found for this round\n");
        } else {
            for (MatchupData m : matchups) {
                String emoji1 = VictoryRecordCalculator.getOutcomeEmoji(m.outcome);
                int oppOutcome = m.outcome == 0 ? 0 : -m.outcome;
                String emoji2 = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);

                String elo1 = m.hall1Elo != null ? String.format("%.0f", m.hall1Elo) : ("WALKOVER".equalsIgnoreCase(m.hall1Name) ? "-" : "?");
                String elo2 = m.hall2Elo != null ? String.format("%.0f", m.hall2Elo) : ("WALKOVER".equalsIgnoreCase(m.hall2Name) ? "-" : "?");

                double displayScore1 = m.hall1Score;
                double displayScore2 = m.hall2Score;
                if ("WALKOVER".equalsIgnoreCase(m.hall2Name)) {
                    // By right the losing (walkover) side gets no points at
                    // all - not the "hallScore - winner" minimum-margin
                    // convention.
                    displayScore1 = MatchScoreUtils.computeWalkoverDefaultScore(m.hall1Score);
                    displayScore2 = 0.0;
                } else if ("WALKOVER".equalsIgnoreCase(m.hall1Name)) {
                    displayScore2 = MatchScoreUtils.computeWalkoverDefaultScore(m.hall2Score);
                    displayScore1 = 0.0;
                }

                String scoreStr = (displayScore1 == Math.floor(displayScore1) && displayScore2 == Math.floor(displayScore2))
                        ? String.format("%.0f-%.0f", displayScore1, displayScore2)
                        : String.format("%.1f-%.1f", displayScore1, displayScore2);

                String line = String.format("%s %-4s %-15s %s %-15s %-4s %s",
                        emoji1, elo1, m.hall1Name, scoreStr, m.hall2Name, elo2, emoji2);

                if (!homeHall.isEmpty() && (m.hall1Name.equals(homeHall) || m.hall2Name.equals(homeHall))) {
                    line += "*";
                }

                sb.append(line).append("\n");
            }
        }
        sb.append("```\n\n");

        sb.append(String.format("**📊 Cumulative Scores (up to %s):**\n```\n", round.roundLabel));
        sb.append(String.format("%-4s %-8s %-10s %-10s\n", "Rank", "Hall", "Match Wins", "Board Wins"));
        sb.append(String.format("%-4s %-8s %-10s %-10s\n", "----", "--------", "----------", "----------"));
        for (HallScoreData s : scores) {
            sb.append(String.format("%-4d %-8s %-10.1f %-10.1f\n", s.rank, s.hallName, s.matchWins, s.boardWins));
        }
        sb.append("```");

        return sb.toString();
    }

    private Path generateImage(List<MatchupData> matchups, List<HallScoreData> scores, A1_Rounds.Round round) throws Exception {
        String homeHall = PropertyResolver.getProperty("settings.homeHall", "");

        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Match Information";
        metadata.description = "Match results for the round";
        metadata.lastRound = round.roundLabel;

        List<InfoImageGenerator.Section> sections = new ArrayList<>();

        InfoImageGenerator.Section matchInfoSection = new InfoImageGenerator.Section("Match Info");
        for (MatchupData m : matchups) {
            String emoji1 = VictoryRecordCalculator.getOutcomeEmoji(m.outcome);
            String emoji2 = VictoryRecordCalculator.getOutcomeEmoji(m.outcome == 0 ? 0 : -m.outcome);

            String elo1 = m.hall1Elo != null ? String.format("%.0f", m.hall1Elo) : ("WALKOVER".equalsIgnoreCase(m.hall1Name) ? "-" : "?");
            String elo2 = m.hall2Elo != null ? String.format("%.0f", m.hall2Elo) : ("WALKOVER".equalsIgnoreCase(m.hall2Name) ? "-" : "?");

            String hall1Display = formatHallNameForMatch(m.hall1Name);
            String hall2Display = formatHallNameForMatch(m.hall2Name);

            double displayScore1 = m.hall1Score;
            double displayScore2 = m.hall2Score;
            if ("WALKOVER".equalsIgnoreCase(m.hall2Name)) {
                // By right the losing (walkover) side gets no points at all -
                // not the "hallScore - winner" minimum-margin convention.
                displayScore1 = MatchScoreUtils.computeWalkoverDefaultScore(m.hall1Score);
                displayScore2 = 0.0;
            } else if ("WALKOVER".equalsIgnoreCase(m.hall1Name)) {
                displayScore2 = MatchScoreUtils.computeWalkoverDefaultScore(m.hall2Score);
                displayScore1 = 0.0;
            }

            String scoreStr = (displayScore1 == Math.floor(displayScore1) && displayScore2 == Math.floor(displayScore2))
                    ? String.format("%.0f-%.0f", displayScore1, displayScore2)
                    : String.format("%.1f-%.1f", displayScore1, displayScore2);

            InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
            entry.round = "";
            entry.hallEmoji = emoji1;
            entry.hallOutcome = m.outcome;
            entry.playerElo = elo1;
            entry.playerName = hall1Display;
            entry.playerHall = "";
            entry.score = scoreStr;
            entry.opponentName = hall2Display;
            entry.opponentElo = elo2;
            entry.opponentHall = "";
            entry.oppEmoji = emoji2;
            entry.oppOutcome = m.outcome == 0 ? 0 : -m.outcome;
            entry.highlightPlayer = !homeHall.isEmpty() && m.hall1Name.equals(homeHall);
            entry.highlightOpponent = !homeHall.isEmpty() && m.hall2Name.equals(homeHall);

            matchInfoSection.addVictoryEntry(entry);
        }
        sections.add(matchInfoSection);

        InfoImageGenerator.Section scoresSection = new InfoImageGenerator.Section(
                String.format("Cumulative Scores (up to %s)", round.roundLabel));
        scoresSection.addMonospacedRow(String.format("%-4s %-20s %-10s %-10s", "Rank", "Hall", "MatchWins", "BoardWins"));
        for (HallScoreData s : scores) {
            boolean isHomeHall = !homeHall.isEmpty() && s.hallName.equals(homeHall);
            scoresSection.addMonospacedRow(String.format("%-4d %-20s %-10.1f %-10.1f", s.rank, s.hallName, s.matchWins, s.boardWins), isHomeHall);
        }
        sections.add(scoresSection);

        return InfoImageGenerator.generateInfoImage(metadata, sections, null, "InfoMatch", round.roundLabel);
    }

    private String formatHallNameForMatch(String hallName) {
        if ("WALKOVER".equalsIgnoreCase(hallName)) {
            return "WALKOVER";
        }
        try {
            int num = Integer.parseInt(hallName);
            return "Hall " + num;
        } catch (NumberFormatException e) {
            return hallName + " Hall";
        }
    }
}
