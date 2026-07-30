package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.SelectionKeyboards;
import com.calplus.ihrgstats.telegrambot.utils.MatchScoreUtils;
import com.calplus.ihrgstats.telegrambot.utils.RankingQueryHelper;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command handler for /infomatchhall command.
 * Shows detailed match information for a specific hall in a specific round,
 * scoped to the current year (settings.currentYear).
 * Displays 3 tables: Player ELO stats, Seating arrangement, and Match details.
 */
public class CommandInfoMatchHall {
    private final LogHelper logHelper;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final RankingQueryHelper rankingQueryHelper = new RankingQueryHelper();

    private static final Map<String, MatchHallSelectionState> userSelectionStates = new ConcurrentHashMap<>();

    private static class MatchHallSelectionState extends SelectionState {
        int hallId;
        String hallName;
    }

    public CommandInfoMatchHall() {
        EnvironmentManager.ensureSystemPropertiesLoaded();
        this.logHelper = new LogHelper();
    }

    public static class InfoResponse extends CommandResponse {
        public InfoResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            super(message, imagePath, buttonConfig);
        }
    }

    public InfoResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("%s requested /infomatchhall command", com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId)));

        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.put(userId, new MatchHallSelectionState());

        List<A3_Halls.Hall> allHalls;
        try {
            allHalls = halls.getAllHalls();
        } catch (SQLException e) {
            logHelper.logError("Database error fetching halls: " + e.getMessage());
            return new InfoResponse("❌ Database error fetching halls.", (Path) null, null);
        }

        String message = "**🏛️ Hall Match Information**\n\nSelect the **hall**:";
        return new InfoResponse(message, (Path) null, SelectionKeyboards.hallButtons(allHalls, "infomatchhall_hall_", "infomatchhall_cancel"));
    }

    public InfoResponse handleHallSelection(String userId, int hallId) {
        MatchHallSelectionState state = userSelectionStates.getOrDefault(userId, new MatchHallSelectionState());
        state.hallId = hallId;
        userSelectionStates.put(userId, state);

        try {
            A3_Halls.Hall hall = halls.getHallById(hallId);
            state.hallName = hall != null ? hall.hallName : "?";

            // Round picker spans every year (not just the current one) -
            // round numbers repeat across years, so each button's
            // label/callback must disambiguate by year too.
            List<A1_Rounds.Round> availableRounds = rounds.getAllRounds();
            if (availableRounds.isEmpty()) {
                userSelectionStates.remove(userId);
                return new InfoResponse("ℹ️ No round data available.", (Path) null, null);
            }

            String message = String.format("**🏛️ Hall Match Information**\n\nHall: **%s**\nSelect the **round**:", VictoryRecordCalculator.formatHallName(state.hallName));
            return new InfoResponse(message, (Path) null, SelectionKeyboards.yearRoundButtons(availableRounds, "infomatchhall_round_", "infomatchhall_cancel", null, null));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new InfoResponse("❌ Database error.", (Path) null, null);
        }
    }

    public InfoResponse handleRoundSelection(String userId, String selectedRound) {
        MatchHallSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.hallName == null) {
            return new InfoResponse("❌ Session expired. Please use /infomatchhall to start again.", (Path) null, null);
        }
        userSelectionStates.remove(userId);

        try {
            // Encoded as "{year}_{roundOrder}" by the round picker above -
            // round numbers repeat across years, so the year must travel
            // with the selection instead of being assumed from settings.
            String[] parts = selectedRound.split("_", 2);
            int year = Integer.parseInt(parts[0]);
            int roundOrder = Integer.parseInt(parts[1]);
            return generateMatchHallInfo(state.hallId, state.hallName, year, roundOrder);
        } catch (Exception e) {
            logHelper.logError("Error generating match hall information: " + e.getMessage());
            e.printStackTrace();
            return new InfoResponse("❌ Error generating match hall information: " + e.getMessage(), (Path) null, null);
        }
    }

    public InfoResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new InfoResponse("ℹ️ Hall match information request cancelled.", (Path) null, null);
    }

    private static class HallPlayerData {
        String name;
        String hall;
        Integer seat;
        Integer currentElo;
        Integer prevElo;
        Integer currentRank;
        Integer prevRank;
        Integer outcome; // legacy 1/0/-1
        String oppName;
        String oppHall;
        Integer oppElo;
        Double score;
        Double oppScore;
        boolean selfTimeout;
        boolean oppTimeout;
    }

    private InfoResponse generateMatchHallInfo(int hallId, String hallName, int year, int roundOrder) throws Exception {
        A1_Rounds.Round round = rounds.getRoundByYearAndOrder(year, roundOrder);
        if (round == null) {
            throw new IllegalStateException("Round " + roundOrder + " not found for " + year);
        }
        A1_Rounds.Round prevRound = roundOrder > 1 ? rounds.getRoundByYearAndOrder(year, roundOrder - 1) : null;

        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
        }

        List<HallPlayerData> players = fetchHallPlayersForRound(hallId, hallName, year, round, prevRound, trueEloTypeId);

        if (players.isEmpty()) {
            return new InfoResponse(String.format("ℹ️ No players from %s found in %s.", VictoryRecordCalculator.formatHallName(hallName), round.roundLabel), (Path) null, null);
        }

        players.sort(Comparator.comparingInt(p -> p.seat != null ? p.seat : 999));

        String textOutput = generateTextOutput(hallName, round, players);
        Path imagePath = generateImage(hallName, round, players);

        logHelper.logSuccess(String.format("Generated hall match info: %s, round %s", hallName, round.roundLabel));
        return new InfoResponse(textOutput, imagePath, null);
    }

    private List<HallPlayerData> fetchHallPlayersForRound(int hallId, String hallName, int year, A1_Rounds.Round round,
                                                           A1_Rounds.Round prevRound, int trueEloTypeId) throws SQLException {
        List<HallPlayerData> players = new ArrayList<>();
        List<B6_PlayerYearStatus.Status> statuses = playerYearStatus.getStatusesForHallAndYear(hallId, year);

        // Rank context is identical for every player in this loop - fetch the
        // full this-round (and previous-round) rating maps ONCE, not once per
        // player (each fetch is itself a per-active-player query series, so
        // the previous inside-the-loop placement cost O(players^2) queries).
        Map<String, D11_PlayerRatings.Rating> allRatings =
                rankingQueryHelper.getLatestRatingsUpToRound(year, round.roundOrder, trueEloTypeId);
        Map<String, D11_PlayerRatings.Rating> allPrevRatings = prevRound != null
                ? rankingQueryHelper.getLatestRatingsUpToRound(year, prevRound.roundOrder, trueEloTypeId)
                : null;

        for (B6_PlayerYearStatus.Status status : statuses) {
            D11_PlayerRatings.Rating rating = rankingQueryHelper.getPointInTimeRating(status.playerId, round.id, trueEloTypeId);
            if (rating == null) continue; // no data this round

            HallPlayerData player = new HallPlayerData();
            String name = playerNames.getNameForYear(status.playerId, year);
            player.name = name != null ? name : status.playerId;
            player.hall = hallName;
            player.currentElo = (int) Math.round(rating.ratingValue);

            player.currentRank = rankingQueryHelper.calculateRank(allRatings, rating.ratingValue);

            if (prevRound != null) {
                D11_PlayerRatings.Rating prevRating = rankingQueryHelper.getPointInTimeRating(status.playerId, prevRound.id, trueEloTypeId);
                if (prevRating != null) {
                    player.prevElo = (int) Math.round(prevRating.ratingValue);
                    player.prevRank = rankingQueryHelper.calculateRank(allPrevRatings, prevRating.ratingValue);
                }
            }

            C9_MatchParticipants.Participant me = participants.getParticipantForPlayerAndRound(status.playerId, round.id);
            if (me != null) {
                player.seat = me.hallSeatNumber;
                player.outcome = VictoryRecordCalculator.toLegacyOutcome(me.outcome);
                player.score = me.score;
                player.selfTimeout = C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(me.participationType);

                C9_MatchParticipants.Participant opp = participants.getOpponentParticipant(me.matchId, status.playerId);
                if (opp != null) {
                    player.oppScore = opp.score;
                    player.oppTimeout = C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(opp.participationType);
                    if (opp.playerId.equals(B4_Players.WALKOVER_PLAYER_ID)) {
                        player.oppName = "WALKOVER";
                    } else {
                        String oppName = playerNames.getNameForYear(opp.playerId, year);
                        player.oppName = oppName != null ? oppName : opp.playerId;
                        D11_PlayerRatings.Rating oppRating = rankingQueryHelper.getPointInTimeRating(opp.playerId, round.id, trueEloTypeId);
                        if (oppRating != null) player.oppElo = (int) Math.round(oppRating.ratingValue);
                    }
                    A3_Halls.Hall oppHall = halls.getHallById(opp.hallId);
                    if (oppHall != null && !oppHall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) {
                        player.oppHall = oppHall.hallName;
                    }
                }
            }

            players.add(player);
        }

        return players;
    }

    /** Per-opponent-hall board tally result: the PRIMARY opponent (the hall actually faced on the most boards this round) and that pairing's own score. */
    private static class OpponentTally {
        String primaryOppHall;
        double hallScore;
        double oppScore;
    }

    /**
     * Tallies this round's players PER OPPONENT HALL, not as one combined
     * total - a hall can face more than one opponent hall in the same round
     * (boards paired independently) or pick up a bonus walkover win
     * alongside a real match, and mixing those into a single pair of totals
     * let the displayed "vs Hall X" score/opponent reflect points that had
     * nothing to do with Hall X (the same bug already fixed in
     * CommandInfoHall/CommandCompareHalls's calculateHallVictoryRecords -
     * this is that identical fix, ported to this single-round view). The
     * primary opponent is whichever hall was faced on the most boards (the
     * normal case is exactly one opponent hall). If every scored board was a
     * walkover (a full-team sweep, not just some boards), the score is
     * normalized to the "3-2" convention, matching
     * CommandInfoMatch.calculateCumulativeScores.
     */
    private OpponentTally tallyByOpponent(List<HallPlayerData> players) {
        Map<String, Double> myScoreByOpp = new HashMap<>();
        Map<String, Double> oppScoreByOpp = new HashMap<>();
        Map<String, Integer> boardsByOpp = new HashMap<>();
        int walkoverCount = 0;
        boolean anyWalkover = false;

        for (HallPlayerData player : players) {
            if (player.outcome == null) continue;
            Double points = VictoryRecordCalculator.outcomeToPoints(player.outcome);
            if (points == null) continue;

            if ("WALKOVER".equalsIgnoreCase(player.oppName)) {
                if (player.oppHall != null) {
                    // The forfeiting side's hall IS known (the uploader
                    // specified it) - fold this board into that hall's own
                    // tally exactly like a real board, instead of dropping it
                    // into the unattributed walkoverCount bucket below. Without
                    // this, a round with real boards AND a walkover against the
                    // SAME opponent silently underreported the score (e.g. a
                    // true 3-2 sweep displayed as 2-0, missing the walkover win).
                    myScoreByOpp.merge(player.oppHall, points, Double::sum);
                    oppScoreByOpp.merge(player.oppHall, 1.0 - points, Double::sum);
                    boardsByOpp.merge(player.oppHall, 1, Integer::sum);
                    continue;
                }
                anyWalkover = true;
                walkoverCount++;
                continue; // opponent hall genuinely unknown - no specific hall to attribute this to
            }
            if (player.oppHall == null) continue;

            myScoreByOpp.merge(player.oppHall, points, Double::sum);
            oppScoreByOpp.merge(player.oppHall, 1.0 - points, Double::sum);
            boardsByOpp.merge(player.oppHall, 1, Integer::sum);
        }

        String primaryOppHall = boardsByOpp.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        OpponentTally tally = new OpponentTally();
        if (primaryOppHall != null) {
            tally.primaryOppHall = primaryOppHall;
            tally.hallScore = myScoreByOpp.getOrDefault(primaryOppHall, 0.0);
            tally.oppScore = oppScoreByOpp.getOrDefault(primaryOppHall, 0.0);
        } else if (anyWalkover) {
            // The forfeiting hall is unknown for every walkover board this
            // round - by right the losing (walkover) side gets no points at
            // all, not the "walkoverCount - winner" minimum-margin convention.
            double winner = MatchScoreUtils.computeWalkoverDefaultScore(walkoverCount);
            tally.primaryOppHall = "WALKOVER";
            tally.hallScore = winner;
            tally.oppScore = 0.0;
        } else {
            tally.primaryOppHall = "WALKOVER";
            tally.hallScore = 0.0;
            tally.oppScore = 0.0;
        }
        return tally;
    }

    private String formatScore(double hallScore, double oppScore) {
        String hallScoreStr = (hallScore % 1 == 0) ? String.format("%.0f", hallScore) : String.format("%.1f", hallScore);
        String oppScoreStr = (oppScore % 1 == 0) ? String.format("%.0f", oppScore) : String.format("%.1f", oppScore);
        return hallScoreStr + "-" + oppScoreStr;
    }

    private String generateTextOutput(String hallName, A1_Rounds.Round round, List<HallPlayerData> players) {
        StringBuilder sb = new StringBuilder();
        OpponentTally tally = tallyByOpponent(players);
        String opponentHall = tally.primaryOppHall;
        String matchScore = formatScore(tally.hallScore, tally.oppScore);

        sb.append("**🏛️ Hall Match Information**\n\n");
        sb.append(String.format("**Hall:** %s vs %s\n", VictoryRecordCalculator.formatHallName(hallName), VictoryRecordCalculator.formatHallName(opponentHall)));
        sb.append(String.format("**Round:** %s\n", round.roundLabel));
        sb.append(String.format("**Score:** %s\n\n", matchScore));

        sb.append("**📊 Player ELO Stats:**\n```\n");
        sb.append(String.format("%-18s %-6s %-10s %-6s %-10s\n", "Name", "Rank", "ΔRank", "ELO", "ΔELO"));
        sb.append(String.format("%-18s %-6s %-10s %-6s %-10s\n", "------------------", "------", "----------", "------", "----------"));
        for (HallPlayerData player : players) {
            if (player.currentRank == null || player.currentElo == null) continue;
            String deltaRank = player.prevRank == null ? "-" : VictoryRecordCalculator.deltaString(player.prevRank - player.currentRank);
            String deltaElo = player.prevElo == null ? "-" : VictoryRecordCalculator.deltaString(player.currentElo - player.prevElo);
            sb.append(String.format("%-18s %-6d %-10s %-6d %-10s\n", player.name, player.currentRank, deltaRank, player.currentElo, deltaElo));
        }
        sb.append("```\n\n");

        sb.append("**🪑 Seating:**\n```\n");
        sb.append(String.format("%-6s %-18s\n", "Seat", "Name"));
        sb.append(String.format("%-6s %-18s\n", "------", "------------------"));
        for (HallPlayerData player : players) {
            if (player.seat != null) {
                sb.append(String.format("%-6d %-18s\n", player.seat, player.name));
            }
        }
        sb.append("```\n\n");

        sb.append("**🏆 Match Details:**\n```\n");
        for (HallPlayerData player : players) {
            if (player.outcome == null) {
                if (player.seat != null) {
                    sb.append(String.format("%-3d  -NA-\n", player.seat));
                }
                continue;
            }

            String oppName = player.oppName;
            String playerEloStr = player.currentElo != null ? String.valueOf(player.currentElo) : "?";
            String oppEloStr = player.oppElo != null ? String.valueOf(player.oppElo) : "?";

            String emoji = VictoryRecordCalculator.getOutcomeEmoji(player.outcome);
            int oppOutcome = player.outcome == 0 ? 0 : -player.outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);

            String playerHallFormatted = TableFormatter.shortenHallName(player.hall);
            String oppHallFormatted;
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                oppHallFormatted = "";
                oppEloStr = "-";
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
            } else {
                oppHallFormatted = player.oppHall != null ? TableFormatter.shortenHallName(player.oppHall) : "??";
            }

            String score = VictoryRecordCalculator.formatScorePair(player.score, player.oppScore, player.selfTimeout, player.oppTimeout);

            String line = String.format("%-3d %s %-2s %-4s %-16s %s %-16s %-4s %-2s %s",
                    player.seat != null ? player.seat : 0, emoji, playerHallFormatted, playerEloStr, player.name,
                    score, oppName != null ? oppName : "?", oppEloStr, oppHallFormatted, oppEmoji);

            sb.append(line).append("\n");
        }
        sb.append("```\n");

        return sb.toString();
    }

    private Path generateImage(String hallName, A1_Rounds.Round round, List<HallPlayerData> players) throws Exception {
        OpponentTally tally = tallyByOpponent(players);
        String opponentHall = tally.primaryOppHall;
        String matchScore = formatScore(tally.hallScore, tally.oppScore);

        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Hall Match Information";
        metadata.subtitle = String.format("%s vs %s - %s",
                VictoryRecordCalculator.formatHallName(hallName), VictoryRecordCalculator.formatHallName(opponentHall), round.roundLabel);
        metadata.description = String.format("Score: %s", matchScore);
        metadata.lastRound = round.roundLabel;
        metadata.secondHallIdentifier = opponentHall.equalsIgnoreCase("WALKOVER") ? "unknown" : opponentHall;

        List<InfoImageGenerator.Section> sections = new ArrayList<>();

        InfoImageGenerator.Section eloSection = new InfoImageGenerator.Section("Player ELO Stats");
        eloSection.addMonospacedRow(String.format("%-18s %-6s %-10s %-6s %-10s", "Name", "Rank", "ΔRank", "ELO", "ΔELO"));
        for (HallPlayerData player : players) {
            if (player.currentRank == null || player.currentElo == null) continue;
            String deltaRank = player.prevRank == null ? "-" : VictoryRecordCalculator.deltaString(player.prevRank - player.currentRank);
            String deltaElo = player.prevElo == null ? "-" : VictoryRecordCalculator.deltaString(player.currentElo - player.prevElo);
            eloSection.addMonospacedRow(String.format("%-18s %-6d %-10s %-6d %-10s", player.name, player.currentRank, deltaRank, player.currentElo, deltaElo));
        }
        sections.add(eloSection);

        InfoImageGenerator.Section seatSection = new InfoImageGenerator.Section("Seating");
        seatSection.addMonospacedRow(String.format("%-6s %-18s", "Seat", "Name"));
        for (HallPlayerData player : players) {
            if (player.seat != null) {
                seatSection.addMonospacedRow(String.format("%-6d %-18s", player.seat, player.name));
            }
        }
        sections.add(seatSection);

        InfoImageGenerator.Section matchSection = new InfoImageGenerator.Section("Match Details");
        for (HallPlayerData player : players) {
            if (player.outcome == null) {
                if (player.seat != null) {
                    InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
                    entry.round = String.valueOf(player.seat);
                    entry.isNA = true;
                    matchSection.addVictoryEntry(entry);
                }
                continue;
            }

            String oppName = player.oppName;
            String hallEmoji = VictoryRecordCalculator.getOutcomeEmoji(player.outcome);
            int oppOutcome = player.outcome == 0 ? 0 : -player.outcome;
            String oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(oppOutcome);

            String playerHallFormatted = TableFormatter.shortenHallName(player.hall);
            String oppHallFormatted = player.oppHall != null ? TableFormatter.shortenHallName(player.oppHall) : "??";

            String playerEloStr = player.currentElo != null ? String.valueOf(player.currentElo) : "?";
            String oppEloStr = player.oppElo != null ? String.valueOf(player.oppElo) : "?";
            if ("WALKOVER".equalsIgnoreCase(oppName)) {
                oppEmoji = VictoryRecordCalculator.getOutcomeEmoji(-1);
                oppEloStr = "-";
            }

            String score = VictoryRecordCalculator.formatScorePair(player.score, player.oppScore, player.selfTimeout, player.oppTimeout);

            InfoImageGenerator.VictoryEntry entry = new InfoImageGenerator.VictoryEntry();
            entry.round = player.seat != null ? String.valueOf(player.seat) : "?";
            entry.hallEmoji = hallEmoji;
            entry.hallOutcome = player.outcome;
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
            matchSection.addVictoryEntry(entry);
        }
        sections.add(matchSection);

        String fileName = String.format("%s_%s", hallName, round.roundLabel);
        return InfoImageGenerator.generateInfoImage(metadata, sections, hallName, "InfoMatchHall", fileName);
    }
}
