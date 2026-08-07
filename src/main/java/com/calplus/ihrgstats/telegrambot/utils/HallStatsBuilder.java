package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.B5_PlayerNames;
import com.calplus.ihrgstats.databasemanager.B6_PlayerYearStatus;
import com.calplus.ihrgstats.databasemanager.C9_MatchParticipants;
import com.calplus.ihrgstats.databasemanager.D11_PlayerRatings;
import com.calplus.ihrgstats.utils.VictoryRecordCalculator;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds the per-hall roster/round statistics that the hall report bodies
 * (/infohall, /comparehalls) render - previously two near-identical private
 * copies maintained in parallel, one of which (/comparehalls) never received
 * the bulk-loading fix and re-queried the point-in-time rating, the FULL
 * rank map, the participant row, the opponent row and the opponent's hall
 * once per player per round.
 *
 * The heavy per-round context ({@link RoundContext}) is built once per
 * (year, rounds) and shared - a comparison of two halls builds it once, not
 * once per hall.
 */
public class HallStatsBuilder {

    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final A3_Halls halls = new A3_Halls();
    private final RankingQueryHelper rankingQueryHelper = new RankingQueryHelper();

    /** Per-round player data within a hall, keyed by round_order for display. */
    public static class PlayerData {
        public String playerId;
        public String name;
        public boolean capped;
        public int hallRank;
        public int globalRank;
        public int elo; // last known TrueElo within the included rounds
        public double avgSeat = 999;
        public Map<Integer, String> roundLabelByOrder = new TreeMap<>();
        public Map<Integer, Integer> eloByRound = new TreeMap<>();
        public Map<Integer, Integer> seatByRound = new TreeMap<>();
        public Map<Integer, Integer> outcomeByRound = new TreeMap<>(); // legacy 1/0/-1 convention
        public Map<Integer, String> oppNameByRound = new TreeMap<>();
        public Map<Integer, String> oppHallByRound = new TreeMap<>();
        public Integer lastRoundOrder;
    }

    /** Aggregate hall-level data, built from the roster's PlayerData. */
    public static class HallData {
        public int hallId;
        public String hallName;
        public List<PlayerData> players = new ArrayList<>();
        public Integer lastRoundOrder;
        public String lastRoundLabel;
        public Map<Integer, Double> hallEloByRound = new TreeMap<>();
        public Map<Integer, Integer> hallRankByRound = new TreeMap<>();
        public Map<Integer, HallVictoryRecord> victoryRecords = new TreeMap<>();
    }

    public static class HallVictoryRecord {
        public double hallScore;
        public double oppScore;
        public String oppHallName;
        public int outcome; // legacy 1/0/-1
        public Double oppHallElo;
    }

    /**
     * The per-round context every hall build reads from, bulk-loaded ONCE
     * per round - previously the point-in-time rating, the full rank map,
     * the player's participant row, the opponent row and the opponent's
     * hall were each fetched per player per round (hundreds of queries per
     * rendered view).
     */
    public static class RoundContext {
        final Map<Integer, Map<String, D11_PlayerRatings.Rating>> pointInTimeByRound = new HashMap<>();
        final Map<Integer, Map<String, D11_PlayerRatings.Rating>> rankMapByRound = new HashMap<>();
        final Map<Integer, Map<String, C9_MatchParticipants.Participant>> participantByPlayerByRound = new HashMap<>();
        final Map<Integer, Map<Integer, List<C9_MatchParticipants.Participant>>> participantsByMatchByRound = new HashMap<>();
        final Map<Integer, A3_Halls.Hall> hallById = new HashMap<>();
    }

    public RoundContext buildRoundContext(int year, List<A1_Rounds.Round> roundsToInclude, int ratingTypeId) throws SQLException {
        RoundContext ctx = new RoundContext();
        for (A1_Rounds.Round round : roundsToInclude) {
            ctx.pointInTimeByRound.put(round.roundOrder, rankingQueryHelper.getPointInTimeRatingsForRound(round.id, ratingTypeId));
            ctx.rankMapByRound.put(round.roundOrder, rankingQueryHelper.getLatestRatingsUpToRound(year, round.roundOrder, ratingTypeId));
            Map<String, C9_MatchParticipants.Participant> byPlayer = new HashMap<>();
            Map<Integer, List<C9_MatchParticipants.Participant>> byMatch = new HashMap<>();
            for (C9_MatchParticipants.Participant p : participants.getParticipantsForRound(round.id)) {
                byPlayer.put(p.playerId, p); // a real player has at most one board per round (enforced at ingestion)
                byMatch.computeIfAbsent(p.matchId, k -> new ArrayList<>()).add(p);
            }
            ctx.participantByPlayerByRound.put(round.roundOrder, byPlayer);
            ctx.participantsByMatchByRound.put(round.roundOrder, byMatch);
        }
        for (A3_Halls.Hall hall : halls.getAllHalls()) {
            ctx.hallById.put(hall.id, hall);
        }
        return ctx;
    }

    /**
     * Builds one hall's roster stats from the shared context: per-round
     * elo/seat/outcome/opponent data per player, avg seat, the hall's true
     * latest round, and the elo-desc hall-local ranking. Hall-level elo/rank
     * and victory records are applied separately ({@link #calculateHallEloAndRank},
     * {@link #calculateHallVictoryRecords}) so a comparison can share one
     * top-5 table between both halls.
     */
    public HallData buildHallStats(int hallId, String hallName, int year, List<A1_Rounds.Round> roundsToInclude, RoundContext ctx) throws SQLException {
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
                D11_PlayerRatings.Rating rating = ctx.pointInTimeByRound.get(round.roundOrder).get(status.playerId);
                if (rating == null) continue;

                int elo = (int) Math.round(rating.ratingValue);
                player.eloByRound.put(round.roundOrder, elo);
                player.roundLabelByOrder.put(round.roundOrder, round.roundLabel);
                player.lastRoundOrder = round.roundOrder;
                player.elo = elo;

                Map<String, D11_PlayerRatings.Rating> allRatings = ctx.rankMapByRound.get(round.roundOrder);
                player.globalRank = rankingQueryHelper.calculateRank(allRatings, rating.ratingValue);

                C9_MatchParticipants.Participant me = ctx.participantByPlayerByRound.get(round.roundOrder).get(status.playerId);
                if (me != null) {
                    if (me.hallSeatNumber != null) player.seatByRound.put(round.roundOrder, me.hallSeatNumber);
                    player.outcomeByRound.put(round.roundOrder, VictoryRecordCalculator.toLegacyOutcome(me.outcome));

                    C9_MatchParticipants.Participant opp = null;
                    for (C9_MatchParticipants.Participant candidate : ctx.participantsByMatchByRound.get(round.roundOrder).getOrDefault(me.matchId, List.of())) {
                        if (!candidate.playerId.equals(status.playerId)) {
                            opp = candidate;
                            break;
                        }
                    }
                    if (opp != null) {
                        if (opp.playerId.equals(B4_Players.WALKOVER_PLAYER_ID)) {
                            player.oppNameByRound.put(round.roundOrder, "WALKOVER");
                        } else {
                            String oppName = playerNames.getNameForYear(opp.playerId, year);
                            player.oppNameByRound.put(round.roundOrder, oppName != null ? oppName : opp.playerId);
                        }
                        A3_Halls.Hall oppHall = ctx.hallById.get(opp.hallId);
                        if (oppHall != null && !oppHall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) {
                            player.oppHallByRound.put(round.roundOrder, oppHall.hallName);
                        }
                    }
                }
            }

            if (player.eloByRound.isEmpty()) continue; // never had data - exclude, matching legacy behavior

            calculateAvgSeat(player, roundsToInclude);
            hallData.players.add(player);

            // Track the TRUE latest round across the whole hall roster (max
            // comparison), not just whichever player happened to be
            // processed first - a player eliminated/absent early must not
            // freeze this label if the hall itself played on longer.
            if (hallData.lastRoundOrder == null || (player.lastRoundOrder != null && player.lastRoundOrder > hallData.lastRoundOrder)) {
                hallData.lastRoundOrder = player.lastRoundOrder;
                hallData.lastRoundLabel = player.roundLabelByOrder.get(player.lastRoundOrder);
            }
        }

        // Sort by last-known elo descending, assign hall-local rank. Name
        // then playerId tiebreak keeps equal-elo rows from swapping order
        // between runs (input order comes from the status query).
        hallData.players.sort((a, b) -> {
            int byElo = Integer.compare(b.elo, a.elo);
            if (byElo != 0) return byElo;
            int byName = a.name.compareTo(b.name);
            if (byName != 0) return byName;
            return a.playerId.compareTo(b.playerId);
        });
        for (int i = 0; i < hallData.players.size(); i++) {
            hallData.players.get(i).hallRank = i + 1;
        }

        return hallData;
    }

    private static void calculateAvgSeat(PlayerData player, List<A1_Rounds.Round> roundsToInclude) {
        List<Integer> seats = new ArrayList<>();
        for (A1_Rounds.Round round : roundsToInclude) {
            Integer seat = player.seatByRound.get(round.roundOrder);
            if (seat != null) seats.add(seat);
        }
        player.avgSeat = seats.isEmpty() ? 999 : seats.stream().mapToInt(Integer::intValue).average().orElse(999);
    }

    /** Computes each hall's average TrueElo of its top 5 players, per round, across ALL halls (needed for ranking + opponent lookups). */
    public Map<Integer, Map<Integer, Double>> computeTop5AvgByHallPerRound(int year, List<A1_Rounds.Round> roundsToInclude, RoundContext ctx) throws SQLException {
        List<B6_PlayerYearStatus.Status> allStatuses = playerYearStatus.getActiveStatusesForYear(year);
        Map<Integer, Map<Integer, Double>> result = new HashMap<>();

        for (A1_Rounds.Round round : roundsToInclude) {
            Map<Integer, List<Double>> elosByHall = new HashMap<>();
            for (B6_PlayerYearStatus.Status status : allStatuses) {
                D11_PlayerRatings.Rating rating = ctx.pointInTimeByRound.get(round.roundOrder).get(status.playerId);
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

    public void calculateHallEloAndRank(HallData hallData, Map<Integer, Map<Integer, Double>> top5AvgByRoundThenHall) {
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

    public void calculateHallVictoryRecords(HallData hallData, List<A1_Rounds.Round> roundsToInclude,
            Map<Integer, Map<Integer, Double>> top5AvgByRoundThenHall, RoundContext ctx) {
        Map<String, Integer> hallNameToId = new HashMap<>();
        for (A3_Halls.Hall hall : ctx.hallById.values()) {
            hallNameToId.put(hall.hallName, hall.id);
        }

        for (A1_Rounds.Round round : roundsToInclude) {
            int roundOrder = round.roundOrder;
            List<PlayerData> playingPlayers = hallData.players.stream()
                    .filter(p -> p.seatByRound.containsKey(roundOrder))
                    .collect(Collectors.toList());
            if (playingPlayers.isEmpty()) continue;

            // Tally hall score AND opponent score PER OPPONENT HALL, not as
            // one combined total for "us" against one combined total for
            // "them" - a hall can face more than one opponent hall in the
            // same round (boards paired independently) or pick up a bonus
            // walkover win alongside a real match, and mixing those into a
            // single pair of totals let the displayed score/outcome for
            // "vs Hall X" reflect points that had nothing to do with Hall X.
            // Same-hall pairings (two of this hall's own players paired
            // together) are skipped entirely so a hall never appears as its
            // own opponent.
            Map<String, Double> myScoreByOpp = new HashMap<>();
            Map<String, Double> oppScoreByOpp = new HashMap<>();
            Map<String, Integer> boardsByOpp = new HashMap<>();
            double walkoverScore = 0.0;
            boolean anyWalkover = false;

            for (PlayerData player : playingPlayers) {
                Integer outcome = player.outcomeByRound.get(roundOrder);
                String oppHallName = player.oppHallByRound.get(roundOrder);
                String oppName = player.oppNameByRound.get(roundOrder);
                if (outcome == null) continue;

                Double points = VictoryRecordCalculator.outcomeToPoints(outcome);
                if (points == null) continue;

                if ("WALKOVER".equalsIgnoreCase(oppName)) {
                    if (oppHallName != null && !oppHallName.equalsIgnoreCase(hallData.hallName)) {
                        // The forfeiting side's hall IS known - fold this
                        // board into that hall's own tally like a real board,
                        // instead of dropping it into the unattributed
                        // walkoverScore bucket below. Without this, a round
                        // with real boards AND a walkover against the SAME
                        // opponent silently underreported the score.
                        myScoreByOpp.merge(oppHallName, points, Double::sum);
                        oppScoreByOpp.merge(oppHallName, 1.0 - points, Double::sum);
                        boardsByOpp.merge(oppHallName, 1, Integer::sum);
                        continue;
                    }
                    anyWalkover = true;
                    walkoverScore += points;
                    continue; // opponent hall genuinely unknown - no specific hall to attribute this to
                }
                if (oppHallName == null || oppHallName.equalsIgnoreCase(hallData.hallName)) {
                    continue; // unknown hall, or a same-hall pairing - never our own opponent
                }

                myScoreByOpp.merge(oppHallName, points, Double::sum);
                oppScoreByOpp.merge(oppHallName, 1.0 - points, Double::sum);
                boardsByOpp.merge(oppHallName, 1, Integer::sum);
            }

            // Primary opponent = whichever hall we faced on the most boards
            // this round (the normal case is exactly one opponent hall).
            String primaryOppHall = boardsByOpp.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (primaryOppHall == null && anyWalkover) {
                primaryOppHall = "WALKOVER";
            }

            if (primaryOppHall != null) {
                HallVictoryRecord record = new HallVictoryRecord();
                record.oppHallName = primaryOppHall;
                if ("WALKOVER".equalsIgnoreCase(primaryOppHall)) {
                    record.hallScore = walkoverScore;
                    record.oppScore = 0.0;
                } else {
                    record.hallScore = myScoreByOpp.getOrDefault(primaryOppHall, 0.0);
                    record.oppScore = oppScoreByOpp.getOrDefault(primaryOppHall, 0.0);
                }
                record.outcome = record.hallScore > record.oppScore ? 1 : (record.hallScore < record.oppScore ? -1 : 0);

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
}
