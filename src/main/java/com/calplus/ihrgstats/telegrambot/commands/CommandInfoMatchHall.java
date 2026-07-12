package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.MatchScoreUtils;
import com.calplus.ihrgstats.telegrambot.utils.RankingQueryHelper;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

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

    private static final Map<String, MatchHallSelectionState> userSelectionStates = new HashMap<>();

    private static class MatchHallSelectionState extends SelectionState {
        int hallId;
        String hallName;
    }

    public CommandInfoMatchHall() {
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

        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        for (A3_Halls.Hall hall : allHalls) {
            if (hall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) continue;
            labels.add(hall.hallName);
            callbacks.add("infomatchhall_hall_" + hall.id);
        }
        labels.add("❌ Cancel");
        callbacks.add("infomatchhall_cancel");

        String message = "**🏛️ Hall Match Information**\n\nSelect the **hall**:";
        return new InfoResponse(message, (Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
    }

    public InfoResponse handleHallSelection(String userId, int hallId) {
        MatchHallSelectionState state = userSelectionStates.getOrDefault(userId, new MatchHallSelectionState());
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
            for (A1_Rounds.Round round : availableRounds) {
                labels.add(round.roundLabel);
                callbacks.add("infomatchhall_round_" + round.roundOrder);
            }
            labels.add("❌ Cancel");
            callbacks.add("infomatchhall_cancel");

            String message = String.format("**🏛️ Hall Match Information**\n\nHall: **%s**\nSelect the **round**:", state.hallName);
            return new InfoResponse(message, (Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0])));
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
        Integer year = YearContext.getCurrentYear();
        userSelectionStates.remove(userId);
        if (year == null) {
            return new InfoResponse("⚠️ No current year set.", (Path) null, null);
        }

        try {
            int roundOrder = Integer.parseInt(selectedRound);
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
            return new InfoResponse(String.format("ℹ️ No players from %s found in %s.", hallName, round.roundLabel), (Path) null, null);
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

        for (B6_PlayerYearStatus.Status status : statuses) {
            D11_PlayerRatings.Rating rating = rankingQueryHelper.getPointInTimeRating(status.playerId, round.id, trueEloTypeId);
            if (rating == null) continue; // no data this round

            HallPlayerData player = new HallPlayerData();
            String name = playerNames.getNameForYear(status.playerId, year);
            player.name = name != null ? name : status.playerId;
            player.hall = hallName;
            player.currentElo = (int) Math.round(rating.ratingValue);

            Map<String, D11_PlayerRatings.Rating> allRatings = rankingQueryHelper.getLatestRatingsUpToRound(year, round.roundOrder, trueEloTypeId);
            player.currentRank = rankingQueryHelper.calculateRank(allRatings, rating.ratingValue);

            if (prevRound != null) {
                D11_PlayerRatings.Rating prevRating = rankingQueryHelper.getPointInTimeRating(status.playerId, prevRound.id, trueEloTypeId);
                if (prevRating != null) {
                    player.prevElo = (int) Math.round(prevRating.ratingValue);
                    Map<String, D11_PlayerRatings.Rating> allPrevRatings = rankingQueryHelper.getLatestRatingsUpToRound(year, prevRound.roundOrder, trueEloTypeId);
                    player.prevRank = rankingQueryHelper.calculateRank(allPrevRatings, prevRating.ratingValue);
                }
            }

            C9_MatchParticipants.Participant me = participants.getParticipantForPlayerAndRound(status.playerId, round.id);
            if (me != null) {
                player.seat = me.hallSeatNumber;
                player.outcome = VictoryRecordCalculator.toLegacyOutcome(me.outcome);
                player.score = me.score;

                C9_MatchParticipants.Participant opp = participants.getOpponentParticipant(me.matchId, status.playerId);
                if (opp != null) {
                    player.oppScore = opp.score;
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

    /** Returns the first non-WALKOVER opponent hall found, or "WALKOVER" if all opponents were walkovers. */
    private String getOpponentHall(List<HallPlayerData> players) {
        for (HallPlayerData player : players) {
            if (player.oppHall != null && !player.oppHall.equalsIgnoreCase("WALKOVER")) {
                return player.oppHall;
            }
        }
        return "WALKOVER";
    }

    /**
     * Aggregates +1 win / +0.5 draw per player into a "X-Y" match score
     * string. If EVERY player faced a WALKOVER this round (a full-team
     * sweep, not just some boards), normalizes to the "3-2" convention -
     * derived from the hall's actual observed board count, matching
     * CommandInfoMatch.calculateCumulativeScores. Partial-team walkovers
     * (some boards real, some walkover) are left as a raw sum.
     */
    private String calculateMatchScore(List<HallPlayerData> players) {
        double hallScore = 0.0;
        double oppScore = 0.0;
        int countedPlayers = 0;
        int walkoverCount = 0;

        for (HallPlayerData player : players) {
            if (player.outcome == null) continue;
            countedPlayers++;
            if ("WALKOVER".equalsIgnoreCase(player.oppName)) {
                walkoverCount++;
            }
            if (player.outcome == 1) {
                hallScore += 1.0;
            } else if (player.outcome == 0) {
                hallScore += 0.5;
                oppScore += 0.5;
            } else if (player.outcome == -1) {
                oppScore += 1.0;
            }
        }

        if (walkoverCount > 0 && walkoverCount == countedPlayers) {
            double winner = MatchScoreUtils.computeWalkoverDefaultScore(walkoverCount);
            hallScore = winner;
            oppScore = walkoverCount - winner;
        }

        String hallScoreStr = (hallScore % 1 == 0) ? String.format("%.0f", hallScore) : String.format("%.1f", hallScore);
        String oppScoreStr = (oppScore % 1 == 0) ? String.format("%.0f", oppScore) : String.format("%.1f", oppScore);
        return hallScoreStr + "-" + oppScoreStr;
    }

    private String generateTextOutput(String hallName, A1_Rounds.Round round, List<HallPlayerData> players) {
        StringBuilder sb = new StringBuilder();
        String opponentHall = getOpponentHall(players);
        String matchScore = calculateMatchScore(players);

        sb.append("**🏛️ Hall Match Information**\n\n");
        sb.append(String.format("**Hall:** %s vs %s\n", hallName, opponentHall));
        sb.append(String.format("**Round:** %s\n", round.roundLabel));
        sb.append(String.format("**Score:** %s\n\n", matchScore));

        sb.append("**📊 Player ELO Stats:**\n```\n");
        sb.append(String.format("%-18s %-6s %-10s %-6s %-10s\n", "Name", "Rank", "ΔRank", "ELO", "ΔELO"));
        sb.append(String.format("%-18s %-6s %-10s %-6s %-10s\n", "------------------", "------", "----------", "------", "----------"));
        for (HallPlayerData player : players) {
            if (player.currentRank == null || player.currentElo == null) continue;
            String deltaRank = player.prevRank == null ? "-" : deltaString(player.prevRank - player.currentRank);
            String deltaElo = player.prevElo == null ? "-" : deltaString(player.currentElo - player.prevElo);
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

            String score = formatScorePair(player.score, player.oppScore);

            String line = String.format("%-3d %s %-2s %-4s %-16s %s %-16s %-4s %-2s %s",
                    player.seat != null ? player.seat : 0, emoji, playerHallFormatted, playerEloStr, player.name,
                    score, oppName != null ? oppName : "?", oppEloStr, oppHallFormatted, oppEmoji);

            sb.append(line).append("\n");
        }
        sb.append("```\n");

        return sb.toString();
    }

    private Path generateImage(String hallName, A1_Rounds.Round round, List<HallPlayerData> players) throws Exception {
        String opponentHall = getOpponentHall(players);
        String matchScore = calculateMatchScore(players);

        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Hall Match Information";
        metadata.subtitle = String.format("%s vs %s - %s", hallName, opponentHall, round.roundLabel);
        metadata.description = String.format("Score: %s", matchScore);
        metadata.lastRound = round.roundLabel;
        metadata.secondHallIdentifier = opponentHall.equalsIgnoreCase("WALKOVER") ? "unknown" : opponentHall;

        List<InfoImageGenerator.Section> sections = new ArrayList<>();

        InfoImageGenerator.Section eloSection = new InfoImageGenerator.Section("Player ELO Stats");
        eloSection.addMonospacedRow(String.format("%-18s %-6s %-10s %-6s %-10s", "Name", "Rank", "ΔRank", "ELO", "ΔELO"));
        for (HallPlayerData player : players) {
            if (player.currentRank == null || player.currentElo == null) continue;
            String deltaRank = player.prevRank == null ? "-" : deltaString(player.prevRank - player.currentRank);
            String deltaElo = player.prevElo == null ? "-" : deltaString(player.currentElo - player.prevElo);
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

            String score = formatScorePair(player.score, player.oppScore);

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

    private static String deltaString(int change) {
        if (change > 0) return "+" + change;
        if (change < 0) return "-" + Math.abs(change);
        return "=";
    }

    /** Formats "myScore-oppScore" - both sides' raw scores are stored directly now, no formula derivation needed. */
    private String formatScorePair(Double myScore, Double oppScore) {
        String myStr = myScore != null ? VictoryRecordCalculator.formatScore(myScore) : "?";
        String oppStr = oppScore != null ? VictoryRecordCalculator.formatScore(oppScore) : "0";
        return myStr + "-" + oppStr;
    }
}
