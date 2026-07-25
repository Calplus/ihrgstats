package com.calplus.ihrgstats.ml.lineup;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.C8_Matches;
import com.calplus.ihrgstats.databasemanager.C9_MatchParticipants;
import com.calplus.ihrgstats.utils.Constants;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Models an opposing hall's captain from their seat-assignment history:
 * the expected 5-player roster, each player's recency-weighted seat
 * distribution, a probability-ranked list of the most likely full
 * lineup orderings (top-K support), and a captain profile (ordering
 * entropy - "fixed vs random" - and reactivity to rematches).
 *
 * Simplifications, documented rather than hidden: the recency-decay
 * half-life is a fixed 10-round default (~one season), not
 * backtest-fitted per the original plan sketch - a fixed, reasonable
 * constant given the effort/value tradeoff at this data scale. A round's
 * opponent hall is read from its first board only (this tournament
 * format always pairs the same two halls for an entire round, so this
 * is exact in practice, not an approximation).
 */
public class OpponentModel {

    private static final int LINEUP_SIZE = Constants.Validation.MAX_PLAYERS_PER_HALL; // 5
    private static final int TOP_K = 24;
    private static final double HALF_LIFE_ROUNDS = 10.0;
    private static final double DECAY = Math.log(2) / HALF_LIFE_ROUNDS;
    private static final double SEAT_SMOOTHING = 0.02;

    private final A1_Rounds rounds = new A1_Rounds();
    private final C8_Matches matches = new C8_Matches();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();

    public static class SeatDistribution {
        public final String playerId;
        public final double[] probBySeat; // index 0 = seat 1 ... index (LINEUP_SIZE-1) = seat LINEUP_SIZE

        public SeatDistribution(String playerId, double[] probBySeat) {
            this.playerId = playerId;
            this.probBySeat = probBySeat;
        }
    }

    public static class Ordering {
        public final List<String> playerIdsBySeat; // size LINEUP_SIZE, index i = player at seat i+1
        public final double probability; // normalized among the returned top-K set

        public Ordering(List<String> playerIdsBySeat, double probability) {
            this.playerIdsBySeat = playerIdsBySeat;
            this.probability = probability;
        }
    }

    public static class CaptainProfile {
        public final double meanSeatEntropyBits; // 0 = perfectly fixed seating, higher = more random
        public final Double reactivity; // fraction of rematches where the seat mapping changed; null = insufficient data
        public final int rematchesObserved;

        public CaptainProfile(double meanSeatEntropyBits, Double reactivity, int rematchesObserved) {
            this.meanSeatEntropyBits = meanSeatEntropyBits;
            this.reactivity = reactivity;
            this.rematchesObserved = rematchesObserved;
        }

        public String consistencyLabel() {
            if (meanSeatEntropyBits < 0.5) return "very fixed";
            if (meanSeatEntropyBits < 1.3) return "mostly consistent";
            return "variable/random";
        }
    }

    public static class Profile {
        public final int hallId;
        public final List<String> expectedRoster;
        public final Map<String, SeatDistribution> seatDistributions;
        public final List<Ordering> topOrderings;
        public final CaptainProfile captainProfile;

        public Profile(int hallId, List<String> expectedRoster, Map<String, SeatDistribution> seatDistributions,
                       List<Ordering> topOrderings, CaptainProfile captainProfile) {
            this.hallId = hallId;
            this.expectedRoster = expectedRoster;
            this.seatDistributions = seatDistributions;
            this.topOrderings = topOrderings;
            this.captainProfile = captainProfile;
        }

        public boolean hasHistory() {
            return !expectedRoster.isEmpty();
        }
    }

    public Profile buildProfile(int hallId) throws SQLException {
        List<A1_Rounds.Round> allRounds = rounds.getAllRounds();
        Map<Integer, Integer> roundIdToSeq = new HashMap<>();
        for (int i = 0; i < allRounds.size(); i++) {
            roundIdToSeq.put(allRounds.get(i).id, i);
        }
        int latestSeq = allRounds.size() - 1;

        Map<Integer, List<C9_MatchParticipants.Participant>> byRoundSeq = new TreeMap<>();
        Map<Integer, Integer> matchIdToRoundId = new HashMap<>();
        for (C9_MatchParticipants.Participant p : participants.getParticipantsForHall(hallId)) {
            if (B4_Players.WALKOVER_PLAYER_ID.equals(p.playerId)) {
                continue;
            }
            Integer roundId = matchIdToRoundId.get(p.matchId);
            if (roundId == null) {
                roundId = matches.getRoundIdForMatch(p.matchId);
                if (roundId != null) {
                    matchIdToRoundId.put(p.matchId, roundId);
                }
            }
            if (roundId == null) {
                continue;
            }
            Integer seq = roundIdToSeq.get(roundId);
            if (seq == null) {
                continue;
            }
            byRoundSeq.computeIfAbsent(seq, k -> new ArrayList<>()).add(p);
        }

        Map<String, double[]> seatWeights = new HashMap<>();
        Map<String, Integer> lastPlayedSeq = new HashMap<>();
        for (Map.Entry<Integer, List<C9_MatchParticipants.Participant>> entry : byRoundSeq.entrySet()) {
            int seq = entry.getKey();
            double weight = Math.exp(-DECAY * (latestSeq - seq));
            for (C9_MatchParticipants.Participant p : entry.getValue()) {
                if (p.hallSeatNumber == null || p.hallSeatNumber < 1 || p.hallSeatNumber > LINEUP_SIZE) {
                    continue;
                }
                double[] w = seatWeights.computeIfAbsent(p.playerId, k -> new double[LINEUP_SIZE]);
                w[p.hallSeatNumber - 1] += weight;
                lastPlayedSeq.merge(p.playerId, seq, Math::max);
            }
        }

        List<String> expectedRoster = lastPlayedSeq.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(Map.Entry::getKey)
                .limit(LINEUP_SIZE)
                .collect(Collectors.toList());

        Map<String, SeatDistribution> seatDistributions = new HashMap<>();
        for (Map.Entry<String, double[]> entry : seatWeights.entrySet()) {
            seatDistributions.put(entry.getKey(), normalize(entry.getKey(), entry.getValue()));
        }

        List<Ordering> topOrderings = computeTopOrderings(expectedRoster, seatDistributions);
        CaptainProfile captainProfile = computeCaptainProfile(seatDistributions, expectedRoster, byRoundSeq);

        return new Profile(hallId, expectedRoster, seatDistributions, topOrderings, captainProfile);
    }

    private static SeatDistribution normalize(String playerId, double[] raw) {
        double[] smoothed = new double[LINEUP_SIZE];
        double sum = 0.0;
        for (int i = 0; i < LINEUP_SIZE; i++) {
            smoothed[i] = raw[i] + SEAT_SMOOTHING;
            sum += smoothed[i];
        }
        for (int i = 0; i < LINEUP_SIZE; i++) {
            smoothed[i] /= sum;
        }
        return new SeatDistribution(playerId, smoothed);
    }

    private List<Ordering> computeTopOrderings(List<String> roster, Map<String, SeatDistribution> dists) {
        if (roster.isEmpty()) {
            return List.of();
        }
        List<List<String>> perms = new ArrayList<>();
        permute(new ArrayList<>(roster), 0, perms);

        double[] weights = new double[perms.size()];
        double total = 0.0;
        for (int i = 0; i < perms.size(); i++) {
            List<String> perm = perms.get(i);
            double w = 1.0;
            for (int seat = 0; seat < perm.size(); seat++) {
                SeatDistribution d = dists.get(perm.get(seat));
                w *= (d != null ? d.probBySeat[seat] : 1.0 / roster.size());
            }
            weights[i] = w;
            total += w;
        }

        List<Ordering> scored = new ArrayList<>();
        for (int i = 0; i < perms.size(); i++) {
            double p = total > 0 ? weights[i] / total : 1.0 / perms.size();
            scored.add(new Ordering(perms.get(i), p));
        }
        scored.sort((a, b) -> Double.compare(b.probability, a.probability));
        return scored.subList(0, Math.min(TOP_K, scored.size()));
    }

    private static void permute(List<String> remaining, int k, List<List<String>> out) {
        if (k == remaining.size()) {
            out.add(new ArrayList<>(remaining));
            return;
        }
        for (int i = k; i < remaining.size(); i++) {
            Collections.swap(remaining, k, i);
            permute(remaining, k + 1, out);
            Collections.swap(remaining, k, i);
        }
    }

    private CaptainProfile computeCaptainProfile(Map<String, SeatDistribution> dists, List<String> roster,
                                                 Map<Integer, List<C9_MatchParticipants.Participant>> byRoundSeq) throws SQLException {
        double entropySum = 0.0;
        int entropyCount = 0;
        for (String playerId : roster) {
            SeatDistribution d = dists.get(playerId);
            if (d == null) {
                continue;
            }
            double h = 0.0;
            for (double p : d.probBySeat) {
                if (p > 0) {
                    h -= p * (Math.log(p) / Math.log(2));
                }
            }
            entropySum += h;
            entropyCount++;
        }
        double meanEntropy = entropyCount > 0 ? entropySum / entropyCount : 0.0;

        Map<Integer, List<Integer>> roundSeqsByOpponentHall = new HashMap<>();
        for (Map.Entry<Integer, List<C9_MatchParticipants.Participant>> entry : byRoundSeq.entrySet()) {
            List<C9_MatchParticipants.Participant> roundParticipants = entry.getValue();
            if (roundParticipants.isEmpty()) {
                continue;
            }
            C9_MatchParticipants.Participant first = roundParticipants.get(0);
            C9_MatchParticipants.Participant opponent = participants.getOpponentParticipant(first.matchId, first.playerId);
            if (opponent == null) {
                continue;
            }
            roundSeqsByOpponentHall.computeIfAbsent(opponent.hallId, k -> new ArrayList<>()).add(entry.getKey());
        }

        int changed = 0;
        int totalPairs = 0;
        for (List<Integer> seqs : roundSeqsByOpponentHall.values()) {
            Collections.sort(seqs);
            for (int i = 1; i < seqs.size(); i++) {
                Map<Integer, String> prevMap = seatMapForRound(byRoundSeq.get(seqs.get(i - 1)));
                Map<Integer, String> currMap = seatMapForRound(byRoundSeq.get(seqs.get(i)));
                if (!prevMap.equals(currMap)) {
                    changed++;
                }
                totalPairs++;
            }
        }
        Double reactivity = totalPairs > 0 ? (double) changed / totalPairs : null;

        return new CaptainProfile(meanEntropy, reactivity, totalPairs);
    }

    private static Map<Integer, String> seatMapForRound(List<C9_MatchParticipants.Participant> roundParticipants) {
        Map<Integer, String> map = new HashMap<>();
        for (C9_MatchParticipants.Participant p : roundParticipants) {
            if (p.hallSeatNumber != null) {
                map.put(p.hallSeatNumber, p.playerId);
            }
        }
        return map;
    }
}
