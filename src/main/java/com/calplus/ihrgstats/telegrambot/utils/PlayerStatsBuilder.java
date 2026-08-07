package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.B5_PlayerNames;
import com.calplus.ihrgstats.databasemanager.C9_MatchParticipants;
import com.calplus.ihrgstats.databasemanager.D11_PlayerRatings;
import com.calplus.ihrgstats.utils.VictoryRecordCalculator;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the per-player round statistics that the player report bodies
 * (/infoplayer, /compareplayers) render - previously two byte-identical
 * private copies maintained in parallel.
 *
 * A builder instance caches the heavy per-round lookups (the full rank map
 * and the round's point-in-time ratings) for its lifetime - one command
 * execution - so a comparison of two players computes each round's rank map
 * once, not once per player, and opponent ratings come from the same
 * per-round map as the player's own.
 */
public class PlayerStatsBuilder {

    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final RankingQueryHelper rankingQueryHelper = new RankingQueryHelper();

    // Per-view caches (a builder lives for one command execution). Keyed by
    // "year:roundOrder" / roundId respectively.
    private final Map<String, Map<String, D11_PlayerRatings.Rating>> rankMapCache = new HashMap<>();
    private final Map<Integer, Map<String, D11_PlayerRatings.Rating>> pointInTimeCache = new HashMap<>();

    /** Per-round player data, keyed by round_order for display. */
    public static class PlayerData {
        public String name;
        public String hall;
        public Map<Integer, String> roundLabelByOrder = new TreeMap<>();
        public Map<Integer, Integer> rankByRound = new TreeMap<>();
        public Map<Integer, Integer> eloByRound = new TreeMap<>();
        public Map<Integer, Integer> seatByRound = new TreeMap<>();
        public Map<Integer, Integer> outcomeByRound = new TreeMap<>(); // legacy 1/0/-1 convention
        public Map<Integer, String> oppNameByRound = new TreeMap<>();
        public Map<Integer, String> oppHallByRound = new TreeMap<>();
        public Map<Integer, Integer> oppEloByRound = new TreeMap<>();
        public Map<Integer, Double> scoreByRound = new TreeMap<>();
        public Map<Integer, Double> oppScoreByRound = new TreeMap<>();
        public Map<Integer, Boolean> selfTimeoutByRound = new TreeMap<>();
        public Map<Integer, Boolean> oppTimeoutByRound = new TreeMap<>();
        public Integer lastRoundOrder;
    }

    /** One year's collapsed summary row, for the "All Years" views. */
    public static class YearSummary {
        public int year;
        public Integer finalRank;
        public Integer finalElo;
        public double avgSeat = 999;
        public double wins;
        public double losses;
    }

    public PlayerData fetchPlayerData(String playerId, String playerName, String hallName, int year,
                                      List<A1_Rounds.Round> roundsToInclude, int trueEloTypeId) throws SQLException {
        PlayerData player = new PlayerData();
        player.name = playerName != null ? playerName : playerId;
        player.hall = hallName;

        for (A1_Rounds.Round round : roundsToInclude) {
            D11_PlayerRatings.Rating rating = pointInTimeRatings(round.id, trueEloTypeId).get(playerId);
            if (rating == null) continue;

            int elo = (int) Math.round(rating.ratingValue);
            player.eloByRound.put(round.roundOrder, elo);
            player.roundLabelByOrder.put(round.roundOrder, round.roundLabel);
            player.lastRoundOrder = round.roundOrder;

            Map<String, D11_PlayerRatings.Rating> allRatings = rankMap(year, round.roundOrder, trueEloTypeId);
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
                        D11_PlayerRatings.Rating oppRating = pointInTimeRatings(round.id, trueEloTypeId).get(opp.playerId);
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

    /**
     * Collapses each year the player appeared down to a single summary row
     * (final rank/elo, average seat, W/L points tally) - the round axis
     * becomes the year axis for the "All Years" views. Years with no data
     * are skipped; an empty result means the player never had data.
     */
    public List<YearSummary> buildYearSummaries(String playerId, String playerName, String hallName, int trueEloTypeId) throws SQLException {
        List<YearSummary> yearSummaries = new ArrayList<>();
        for (int year : rounds.getAllYears()) {
            List<A1_Rounds.Round> yearRounds = rounds.getRoundsForYear(year);
            PlayerData yearData = fetchPlayerData(playerId, playerName, hallName, year, yearRounds, trueEloTypeId);
            if (yearData.eloByRound.isEmpty()) continue;

            YearSummary summary = new YearSummary();
            summary.year = year;
            summary.finalRank = yearData.rankByRound.get(yearData.lastRoundOrder);
            summary.finalElo = yearData.eloByRound.get(yearData.lastRoundOrder);

            List<Integer> seats = new ArrayList<>(yearData.seatByRound.values());
            summary.avgSeat = seats.isEmpty() ? 999 : seats.stream().mapToInt(Integer::intValue).average().orElse(999);

            for (Integer outcome : yearData.outcomeByRound.values()) {
                if (outcome == null) continue;
                Double points = VictoryRecordCalculator.outcomeToPoints(outcome);
                if (points == null) continue;
                summary.wins += points;
                summary.losses += (1.0 - points);
            }

            yearSummaries.add(summary);
        }
        return yearSummaries;
    }

    private Map<String, D11_PlayerRatings.Rating> pointInTimeRatings(int roundId, int ratingTypeId) throws SQLException {
        Map<String, D11_PlayerRatings.Rating> cached = pointInTimeCache.get(roundId);
        if (cached == null) {
            cached = rankingQueryHelper.getPointInTimeRatingsForRound(roundId, ratingTypeId);
            pointInTimeCache.put(roundId, cached);
        }
        return cached;
    }

    private Map<String, D11_PlayerRatings.Rating> rankMap(int year, int roundOrder, int ratingTypeId) throws SQLException {
        String key = year + ":" + roundOrder;
        Map<String, D11_PlayerRatings.Rating> cached = rankMapCache.get(key);
        if (cached == null) {
            cached = rankingQueryHelper.getLatestRatingsUpToRound(year, roundOrder, ratingTypeId);
            rankMapCache.put(key, cached);
        }
        return cached;
    }
}
