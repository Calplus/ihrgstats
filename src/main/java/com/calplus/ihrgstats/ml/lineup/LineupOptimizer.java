package com.calplus.ihrgstats.ml.lineup;

import com.calplus.ihrgstats.ml.FeatureExtractor;
import com.calplus.ihrgstats.ml.GlickoBaseline;
import com.calplus.ihrgstats.ml.MatchupPredictor;
import com.calplus.ihrgstats.ml.PredictionService;
import com.calplus.ihrgstats.ml.ReliabilityScore;
import com.calplus.ihrgstats.utils.Constants;

import java.util.*;

/**
 * The exact lineup optimizer: given our available roster and an opponent
 * captain profile, enumerates every legal 5-player lineup and seat order,
 * scores each via a two-team DP over the champion model's per-board win
 * probabilities, and returns the best-response lineup, the maximin
 * ("safe") lineup, and a named strategy-archetype table so the captain
 * sees whether a Tian-Ji-style sacrifice actually wins here - with
 * numbers, not a black-box permutation.
 *
 * Board pairing is seat-for-seat (our seat i faces their seat i), the
 * real structure of this tournament format.
 */
public class LineupOptimizer {

    public static final int LINEUP_SIZE = Constants.Validation.MAX_PLAYERS_PER_HALL; // 5
    private static final int PRUNE_THRESHOLD = 16;
    /** Public so /lineup's "Roster pruned to top N" message can never drift from the actual cap. */
    public static final int PRUNE_TARGET = 12;

    /** Both models' win/draw/loss for one board, our seat-player's perspective - the pairing table's "side by side". */
    public static class PairingProbs {
        public final MatchupPredictor.Probs model;
        public final MatchupPredictor.Probs glicko;

        public PairingProbs(MatchupPredictor.Probs model, MatchupPredictor.Probs glicko) {
            this.model = model;
            this.glicko = glicko;
        }
    }

    /** Our team's result distribution for one full 5-board tie, against either one opponent ordering or their whole distribution. */
    public static class TeamResult {
        public final double pWin;
        public final double pTie;
        public final double pLoss;

        public TeamResult(double pWin, double pTie, double pLoss) {
            this.pWin = pWin;
            this.pTie = pTie;
            this.pLoss = pLoss;
        }
    }

    public static class LineupCandidate {
        public final List<String> playerIdsBySeat;
        public final TeamResult expectedResult;  // vs the opponent's full (renormalized top-K) ordering distribution
        public final TeamResult worstCaseResult; // vs the single worst opponent ordering for THIS lineup

        public LineupCandidate(List<String> playerIdsBySeat, TeamResult expectedResult, TeamResult worstCaseResult) {
            this.playerIdsBySeat = playerIdsBySeat;
            this.expectedResult = expectedResult;
            this.worstCaseResult = worstCaseResult;
        }
    }

    public static class ArchetypeResult {
        public final String name;
        public final List<String> playerIdsBySeat;
        public final TeamResult expectedResult;

        public ArchetypeResult(String name, List<String> playerIdsBySeat, TeamResult expectedResult) {
            this.name = name;
            this.playerIdsBySeat = playerIdsBySeat;
            this.expectedResult = expectedResult;
        }
    }

    public static class Result {
        public final List<String> ourNominal5; // strength-sorted top 5 of the (possibly pruned) candidate pool
        public final LineupCandidate bestResponse;
        public final LineupCandidate maximin;
        public final List<ArchetypeResult> archetypes; // strength-order, mirror, single-sacrifice, double-sacrifice, free optimum
        public final List<OpponentModel.Ordering> opponentTopOrderings;
        public final String predictorFamily;
        public final Map<String, ReliabilityScore> reliability;
        public final boolean rosterPruned;
        public final int candidatesConsidered;
        public final List<PairingProbs> bestResponsePairingVsTopOpponentOrder; // size LINEUP_SIZE, one per seat

        public Result(List<String> ourNominal5, LineupCandidate bestResponse, LineupCandidate maximin,
                      List<ArchetypeResult> archetypes, List<OpponentModel.Ordering> opponentTopOrderings,
                      String predictorFamily, Map<String, ReliabilityScore> reliability, boolean rosterPruned,
                      int candidatesConsidered, List<PairingProbs> bestResponsePairingVsTopOpponentOrder) {
            this.ourNominal5 = ourNominal5;
            this.bestResponse = bestResponse;
            this.maximin = maximin;
            this.archetypes = archetypes;
            this.opponentTopOrderings = opponentTopOrderings;
            this.predictorFamily = predictorFamily;
            this.reliability = reliability;
            this.rosterPruned = rosterPruned;
            this.candidatesConsidered = candidatesConsidered;
            this.bestResponsePairingVsTopOpponentOrder = bestResponsePairingVsTopOpponentOrder;
        }
    }

    private final PredictionService predictionService = new PredictionService();

    /**
     * Runs the full optimization.
     *
     * @throws IllegalArgumentException if our available roster or the
     *         opponent's known roster has fewer than {@link #LINEUP_SIZE}
     *         players - there is no valid 5-a-side lineup to compute.
     */
    public Result optimize(List<String> ourAvailableRoster, OpponentModel.Profile opponentProfile,
                           MatchupPredictor predictor, GlickoBaseline baseline, int fallbackYear) throws java.sql.SQLException {
        if (ourAvailableRoster.size() < LINEUP_SIZE) {
            throw new IllegalArgumentException("At least " + LINEUP_SIZE + " available players are needed, got " + ourAvailableRoster.size());
        }
        if (opponentProfile.expectedRoster.size() < LINEUP_SIZE) {
            throw new IllegalArgumentException("Not enough recorded history for this opponent's roster (need " + LINEUP_SIZE
                    + " players, have " + opponentProfile.expectedRoster.size() + ")");
        }

        Map<String, FeatureExtractor.Side> latestSides = predictionService.latestSides();

        boolean pruned = false;
        List<String> pool = ourAvailableRoster;
        if (pool.size() > PRUNE_THRESHOLD) {
            pruned = true;
            pool = sortByRatingDesc(pool, latestSides).subList(0, PRUNE_TARGET);
        }

        // Renormalized opponent ordering distribution (top-K support only).
        List<OpponentModel.Ordering> theirOrderings = opponentProfile.topOrderings;
        double theirTotalProb = theirOrderings.stream().mapToDouble(o -> o.probability).sum();

        // Precompute the full (ourPlayer x theirPlayer x seat) probability tensor once.
        List<String> theirRoster = opponentProfile.expectedRoster;
        Map<String, Map<String, PairingProbs[]>> tensor = buildTensor(pool, theirRoster, latestSides, predictor, baseline, fallbackYear);

        // Exact enumeration: every 5-subset of pool, every seat ordering of that subset.
        LineupCandidate bestResponse = null;
        LineupCandidate maximin = null;
        int candidatesConsidered = 0;
        for (List<String> subset : combinations(pool, LINEUP_SIZE)) {
            for (List<String> ordering : permutations(subset)) {
                candidatesConsidered++;
                double expectedWin = 0.0, expectedTie = 0.0, expectedLoss = 0.0;
                double worstWin = Double.POSITIVE_INFINITY;
                TeamResult worst = null;
                for (OpponentModel.Ordering theirOrdering : theirOrderings) {
                    TeamResult r = teamResult(ordering, theirOrdering.playerIdsBySeat, tensor);
                    double w = theirTotalProb > 0 ? theirOrdering.probability / theirTotalProb : 1.0 / theirOrderings.size();
                    expectedWin += r.pWin * w;
                    expectedTie += r.pTie * w;
                    expectedLoss += r.pLoss * w;
                    if (r.pWin < worstWin) {
                        worstWin = r.pWin;
                        worst = r;
                    }
                }
                LineupCandidate candidate = new LineupCandidate(ordering,
                        new TeamResult(expectedWin, expectedTie, expectedLoss), worst);
                if (bestResponse == null || candidate.expectedResult.pWin > bestResponse.expectedResult.pWin) {
                    bestResponse = candidate;
                }
                if (maximin == null || candidate.worstCaseResult.pWin > maximin.worstCaseResult.pWin) {
                    maximin = candidate;
                }
            }
        }

        List<String> nominal5 = sortByRatingDesc(pool, latestSides).subList(0, LINEUP_SIZE);
        List<ArchetypeResult> archetypes = buildArchetypes(nominal5, opponentProfile, latestSides, tensor, theirOrderings, theirTotalProb);
        archetypes.add(new ArchetypeResult("Free optimum", bestResponse.playerIdsBySeat, bestResponse.expectedResult));

        Map<String, ReliabilityScore> reliability = new LinkedHashMap<>();
        for (String playerId : bestResponse.playerIdsBySeat) {
            reliability.put(playerId, ReliabilityScore.compute(predictionService.latestSideOrDefault(latestSides, playerId)));
        }

        List<PairingProbs> pairingRow = new ArrayList<>();
        if (!theirOrderings.isEmpty()) {
            List<String> theirTop = theirOrderings.get(0).playerIdsBySeat;
            for (int seat = 0; seat < LINEUP_SIZE; seat++) {
                pairingRow.add(tensor.get(bestResponse.playerIdsBySeat.get(seat)).get(theirTop.get(seat))[seat]);
            }
        }

        return new Result(nominal5, bestResponse, maximin, archetypes, theirOrderings, predictor.family(),
                reliability, pruned, candidatesConsidered, pairingRow);
    }

    private Map<String, Map<String, PairingProbs[]>> buildTensor(List<String> ourPool, List<String> theirRoster,
                                                                  Map<String, FeatureExtractor.Side> latestSides,
                                                                  MatchupPredictor predictor, GlickoBaseline baseline,
                                                                  int fallbackYear) {
        Map<String, Map<String, PairingProbs[]>> tensor = new HashMap<>();
        for (String ourPlayer : ourPool) {
            Map<String, PairingProbs[]> row = new HashMap<>();
            FeatureExtractor.Side ourBase = predictionService.latestSideOrDefault(latestSides, ourPlayer);
            for (String theirPlayer : theirRoster) {
                FeatureExtractor.Side theirBase = predictionService.latestSideOrDefault(latestSides, theirPlayer);
                PairingProbs[] bySeat = new PairingProbs[LINEUP_SIZE];
                for (int seat = 1; seat <= LINEUP_SIZE; seat++) {
                    FeatureExtractor.Side ourSeated = FeatureExtractor.withSeat(ourBase, seat);
                    FeatureExtractor.Side theirSeated = FeatureExtractor.withSeat(theirBase, seat);
                    FeatureExtractor.RawBoard board = new FeatureExtractor.RawBoard(-1, Integer.MAX_VALUE, -1,
                            fallbackYear, seat, ourSeated, theirSeated, 0.0, false, false, 0.0);
                    bySeat[seat - 1] = new PairingProbs(predictor.predict(board), baseline.predict(board));
                }
                row.put(theirPlayer, bySeat);
            }
            tensor.put(ourPlayer, row);
        }
        return tensor;
    }

    /** Exact DP over half-point team totals (11 states: 0, 0.5, ..., 5) using the MODEL's probabilities. */
    private static TeamResult teamResult(List<String> ourOrdering, List<String> theirOrdering,
                                         Map<String, Map<String, PairingProbs[]>> tensor) {
        double[] dist = new double[2 * LINEUP_SIZE + 1];
        dist[0] = 1.0;
        for (int seat = 0; seat < LINEUP_SIZE; seat++) {
            MatchupPredictor.Probs p = tensor.get(ourOrdering.get(seat)).get(theirOrdering.get(seat))[seat].model;
            double[] next = new double[dist.length];
            for (int s = 0; s < dist.length; s++) {
                if (dist[s] == 0.0) continue;
                next[s] += dist[s] * p.pLoss;
                if (s + 1 < next.length) next[s + 1] += dist[s] * p.pDraw;
                if (s + 2 < next.length) next[s + 2] += dist[s] * p.pWin;
            }
            dist = next;
        }
        double win = 0.0, tie = 0.0, loss = 0.0;
        int tieIndex = LINEUP_SIZE; // score 2.5 == index LINEUP_SIZE (half-point units)
        for (int s = 0; s < dist.length; s++) {
            if (s > tieIndex) win += dist[s];
            else if (s == tieIndex) tie += dist[s];
            else loss += dist[s];
        }
        return new TeamResult(win, tie, loss);
    }

    private List<ArchetypeResult> buildArchetypes(List<String> nominal5, OpponentModel.Profile opponentProfile,
                                                  Map<String, FeatureExtractor.Side> latestSides,
                                                  Map<String, Map<String, PairingProbs[]>> tensor,
                                                  List<OpponentModel.Ordering> theirOrderings, double theirTotalProb) {
        List<String> theirTop = theirOrderings.isEmpty() ? opponentProfile.expectedRoster : theirOrderings.get(0).playerIdsBySeat;
        List<String> theirByRatingDesc = sortByRatingDesc(opponentProfile.expectedRoster, latestSides);
        // theirSeatByRank.get(r) = seat index (0-based) of the opponent's r-th strongest predicted player.
        List<Integer> theirSeatByRank = new ArrayList<>();
        for (String playerId : theirByRatingDesc) {
            int seatIdx = theirTop.indexOf(playerId);
            theirSeatByRank.add(seatIdx >= 0 ? seatIdx : theirSeatByRank.size() % LINEUP_SIZE);
        }

        List<ArchetypeResult> archetypes = new ArrayList<>();
        archetypes.add(scoreArchetype("Strength order", nominal5, tensor, theirOrderings, theirTotalProb));

        int[] mirrorMap = {0, 1, 2, 3, 4};
        archetypes.add(scoreArchetype("Mirror (match their predicted rank)",
                bySeatMap(nominal5, theirSeatByRank, mirrorMap), tensor, theirOrderings, theirTotalProb));

        // Our weakest (rank 4) faces their strongest (rank 0); our ace shifts down to face their rank 1.
        int[] singleSacrifice = {1, 2, 3, 4, 0};
        archetypes.add(scoreArchetype("Single sacrifice",
                bySeatMap(nominal5, theirSeatByRank, singleSacrifice), tensor, theirOrderings, theirTotalProb));

        // Our two weakest (ranks 3,4) face their two strongest (ranks 0,1).
        int[] doubleSacrifice = {2, 3, 4, 1, 0};
        archetypes.add(scoreArchetype("Double sacrifice",
                bySeatMap(nominal5, theirSeatByRank, doubleSacrifice), tensor, theirOrderings, theirTotalProb));

        return archetypes;
    }

    /** ourRankToTheirRank[r] = which opponent rank our r-th strongest player is deliberately matched against. */
    private static List<String> bySeatMap(List<String> nominal5, List<Integer> theirSeatByRank, int[] ourRankToTheirRank) {
        String[] seats = new String[LINEUP_SIZE];
        for (int r = 0; r < LINEUP_SIZE; r++) {
            int theirRank = ourRankToTheirRank[r];
            int seat = theirSeatByRank.get(theirRank);
            seats[seat] = nominal5.get(r);
        }
        return Arrays.asList(seats);
    }

    private static ArchetypeResult scoreArchetype(String name, List<String> ourOrdering,
                                                  Map<String, Map<String, PairingProbs[]>> tensor,
                                                  List<OpponentModel.Ordering> theirOrderings, double theirTotalProb) {
        double expectedWin = 0.0, expectedTie = 0.0, expectedLoss = 0.0;
        for (OpponentModel.Ordering theirOrdering : theirOrderings) {
            TeamResult r = teamResult(ourOrdering, theirOrdering.playerIdsBySeat, tensor);
            double w = theirTotalProb > 0 ? theirOrdering.probability / theirTotalProb : 1.0 / theirOrderings.size();
            expectedWin += r.pWin * w;
            expectedTie += r.pTie * w;
            expectedLoss += r.pLoss * w;
        }
        return new ArchetypeResult(name, ourOrdering, new TeamResult(expectedWin, expectedTie, expectedLoss));
    }

    private List<String> sortByRatingDesc(List<String> playerIds, Map<String, FeatureExtractor.Side> latestSides) {
        List<String> sorted = new ArrayList<>(playerIds);
        sorted.sort((a, b) -> Double.compare(
                predictionService.latestSideOrDefault(latestSides, b).rating,
                predictionService.latestSideOrDefault(latestSides, a).rating));
        return sorted;
    }

    private static List<List<String>> combinations(List<String> items, int k) {
        List<List<String>> out = new ArrayList<>();
        combine(items, k, 0, new ArrayList<>(), out);
        return out;
    }

    private static void combine(List<String> items, int k, int start, List<String> current, List<List<String>> out) {
        if (current.size() == k) {
            out.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < items.size(); i++) {
            current.add(items.get(i));
            combine(items, k, i + 1, current, out);
            current.remove(current.size() - 1);
        }
    }

    private static List<List<String>> permutations(List<String> items) {
        List<List<String>> out = new ArrayList<>();
        permute(new ArrayList<>(items), 0, out);
        return out;
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
}
