package com.calplus.ihrgstats.ml.lineup;

import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.ml.FeatureExtractor;
import com.calplus.ihrgstats.ml.GlickoBaseline;
import com.calplus.ihrgstats.ml.MatchupPredictor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LineupOptimizer}'s pure combinatorial/DP correctness,
 * isolated from real ratings via a hand-specified stub predictor and
 * relying on {@code sortByRatingDesc}'s stable-sort tie-preservation (all
 * players share the identical default rating with no history, so the
 * input list order deterministically becomes the rank order) - no real
 * match history needed for any test in this file except the shared
 * empty-schema database PredictionService reads through.
 */
public class LineupOptimizerTest {

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        new DatabaseSchema().createDatabase("default.db");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    /** Deterministic (no draws) stub: exact P(a beats b) from a hand-specified table, defaulting to a coin flip for unlisted pairs. */
    private static class StubPredictor implements MatchupPredictor {
        private final Map<String, Double> table = new HashMap<>();

        void set(String a, String b, double pAWins) {
            table.put(a + "|" + b, pAWins);
            table.put(b + "|" + a, 1.0 - pAWins);
        }

        @Override
        public Probs predict(FeatureExtractor.RawBoard board) {
            Double p = table.get(board.a.playerId + "|" + board.b.playerId);
            if (p == null) {
                return new Probs(0.5, 0.0, 0.5);
            }
            return new Probs(p, 0.0, 1.0 - p);
        }

        @Override
        public String family() {
            return "STUB";
        }
    }

    private static OpponentModel.Ordering ordering(List<String> seats, double prob) {
        return new OpponentModel.Ordering(seats, prob);
    }

    private static OpponentModel.Profile singleOrderingProfile(int hallId, List<String> roster) {
        return new OpponentModel.Profile(hallId, roster, Map.of(),
                List.of(ordering(roster, 1.0)),
                new OpponentModel.CaptainProfile(0.0, null, 0));
    }

    // ------------------------------------------------------------------
    // DP correctness: cross-check against an independent brute-force
    // enumeration of all 32 board-outcome combinations.
    // ------------------------------------------------------------------

    @Test
    void bestResponseExpectedResult_matchesIndependentBruteForceCalculation() throws Exception {
        List<String> our = List.of("O1", "O2", "O3", "O4", "O5");
        List<String> their = List.of("T1", "T2", "T3", "T4", "T5");

        StubPredictor predictor = new StubPredictor();
        // Arbitrary but non-trivial win probabilities, seat-independent for this test.
        double[][] p = {
                {0.9, 0.6, 0.3, 0.2, 0.1},
                {0.7, 0.5, 0.4, 0.3, 0.2},
                {0.6, 0.5, 0.5, 0.4, 0.3},
                {0.5, 0.4, 0.3, 0.3, 0.2},
                {0.4, 0.3, 0.2, 0.1, 0.1},
        };
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                predictor.set(our.get(i), their.get(j), p[i][j]);
            }
        }

        OpponentModel.Profile profile = singleOrderingProfile(1, their);
        LineupOptimizer optimizer = new LineupOptimizer();
        LineupOptimizer.Result result = optimizer.optimize(our, profile, predictor, new GlickoBaseline(0.05), 2025);

        // Independent brute-force: enumerate all 2^5 win/loss combinations for the
        // WINNING lineup's actual seat pairing (their side is fixed - only one ordering).
        List<String> ourSeats = result.bestResponse.playerIdsBySeat;
        double[] pairwise = new double[5];
        for (int seat = 0; seat < 5; seat++) {
            int ourIdx = our.indexOf(ourSeats.get(seat));
            pairwise[seat] = p[ourIdx][seat]; // their seat i is always their.get(i) - single ordering
        }
        double bruteForceWinProb = 0.0;
        for (int mask = 0; mask < 32; mask++) {
            double prob = 1.0;
            int wins = 0;
            for (int seat = 0; seat < 5; seat++) {
                boolean weWin = (mask & (1 << seat)) != 0;
                prob *= weWin ? pairwise[seat] : (1 - pairwise[seat]);
                if (weWin) wins++;
            }
            if (wins >= 3) {
                bruteForceWinProb += prob;
            }
        }

        assertEquals(bruteForceWinProb, result.bestResponse.expectedResult.pWin, 1e-9,
                "the optimizer's DP-computed win probability must match an independent brute-force enumeration");
    }

    // ------------------------------------------------------------------
    // The Tian Ji test: sacrifice strictly dominates strength order.
    // ------------------------------------------------------------------

    @Test
    void tianJiSacrificeStrictlyDominatesStrengthOrder() throws Exception {
        // Rank r (0=best..4=worst) on each side. Our rank i beats their rank j
        // with certainty iff j > i (one tier weaker), loses iff j <= i.
        List<String> our = List.of("O1", "O2", "O3", "O4", "O5"); // O1=our best .. O5=our worst
        List<String> their = List.of("T1", "T2", "T3", "T4", "T5"); // T1=their best .. T5=their worst

        StubPredictor predictor = new StubPredictor();
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                predictor.set(our.get(i), their.get(j), j > i ? 1.0 : 0.0);
            }
        }

        OpponentModel.Profile profile = singleOrderingProfile(1, their);
        LineupOptimizer optimizer = new LineupOptimizer();
        LineupOptimizer.Result result = optimizer.optimize(our, profile, predictor, new GlickoBaseline(0.05), 2025);

        Map<String, LineupOptimizer.ArchetypeResult> byName = new HashMap<>();
        for (LineupOptimizer.ArchetypeResult a : result.archetypes) {
            byName.put(a.name, a);
        }

        // Strength order (seat i = our rank i vs their rank i): every board is a guaranteed loss.
        assertEquals(0.0, byName.get("Strength order").expectedResult.pWin, 1e-9,
                "under naive strength order every same-rank board is a certain loss");

        // Single sacrifice (our weakest vs their strongest, rest shift up one): 4 certain wins, 1 certain loss = a team win.
        assertEquals(1.0, byName.get("Single sacrifice").expectedResult.pWin, 1e-9,
                "the sacrifice rotation should win 4 of 5 boards with certainty here, a guaranteed team win");

        // The optimizer's own free search must find at least as good as the best named archetype.
        assertTrue(result.bestResponse.expectedResult.pWin >= byName.get("Single sacrifice").expectedResult.pWin - 1e-9,
                "the free optimum must be at least as good as the best archetype it's compared against");
        assertTrue(result.bestResponse.expectedResult.pWin > byName.get("Strength order").expectedResult.pWin,
                "the optimizer must not recommend a strictly dominated strategy");
    }

    // ------------------------------------------------------------------
    // maximin <= best-response invariants.
    // ------------------------------------------------------------------

    @Test
    void maximin_neverBeatsBestResponseInExpectation_butNeverWorseInWorstCase() throws Exception {
        List<String> our = List.of("O1", "O2", "O3", "O4", "O5");
        List<String> theirA = List.of("T1", "T2", "T3", "T4", "T5");
        List<String> theirB = List.of("T5", "T4", "T3", "T2", "T1"); // a second plausible ordering, reversed

        StubPredictor predictor = new StubPredictor();
        long state = 42424242L;
        for (String o : our) {
            for (String t : List.of("T1", "T2", "T3", "T4", "T5")) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                double p = 0.15 + 0.7 * (Math.floorMod(state >>> 16, 1000) / 1000.0);
                predictor.set(o, t, p);
            }
        }

        OpponentModel.Profile profile = new OpponentModel.Profile(1, theirA, Map.of(),
                List.of(ordering(theirA, 0.5), ordering(theirB, 0.5)),
                new OpponentModel.CaptainProfile(1.0, 0.3, 4));

        LineupOptimizer.Result result = new LineupOptimizer().optimize(our, profile, predictor, new GlickoBaseline(0.05), 2025);

        assertTrue(result.maximin.expectedResult.pWin <= result.bestResponse.expectedResult.pWin + 1e-9,
                "best-response is defined as the expected-value maximizer - nothing can beat it in expectation");
        assertTrue(result.maximin.worstCaseResult.pWin >= result.bestResponse.worstCaseResult.pWin - 1e-9,
                "maximin is defined as the worst-case maximizer - it must never have a worse floor than best-response");
    }

    // ------------------------------------------------------------------
    // Locks/excludes/insufficient-roster guards.
    // ------------------------------------------------------------------

    @Test
    void tooFewAvailablePlayers_throwsClearError() {
        LineupOptimizer optimizer = new LineupOptimizer();
        OpponentModel.Profile profile = singleOrderingProfile(1, List.of("T1", "T2", "T3", "T4", "T5"));
        List<String> tooFew = List.of("O1", "O2", "O3");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> optimizer.optimize(tooFew, profile, new StubPredictor(), new GlickoBaseline(0.05), 2025));
        assertTrue(ex.getMessage().contains("5"));
    }

    @Test
    void tooFewOpponentHistoryPlayers_throwsClearError() {
        LineupOptimizer optimizer = new LineupOptimizer();
        OpponentModel.Profile thinProfile = singleOrderingProfile(1, List.of("T1", "T2"));
        List<String> our = List.of("O1", "O2", "O3", "O4", "O5");
        assertThrows(IllegalArgumentException.class,
                () -> optimizer.optimize(our, thinProfile, new StubPredictor(), new GlickoBaseline(0.05), 2025));
    }

    // ------------------------------------------------------------------
    // Performance budget: 12 available x K=24 opponent orderings completes quickly.
    // ------------------------------------------------------------------

    @Test
    void performanceBudget_twelveRosterWithTopK24_completesUnderTwoSeconds() throws Exception {
        List<String> our = new ArrayList<>();
        for (int i = 0; i < 12; i++) our.add("O" + i);
        List<String> their = List.of("T0", "T1", "T2", "T3", "T4");

        StubPredictor predictor = new StubPredictor();
        for (String o : our) {
            for (String t : their) {
                predictor.set(o, t, 0.5);
            }
        }

        List<List<String>> perms = new ArrayList<>();
        permute(new ArrayList<>(their), 0, perms);
        List<OpponentModel.Ordering> orderings = new ArrayList<>();
        for (int i = 0; i < Math.min(24, perms.size()); i++) {
            orderings.add(ordering(perms.get(i), 1.0 / 24));
        }
        OpponentModel.Profile profile = new OpponentModel.Profile(1, their, Map.of(), orderings,
                new OpponentModel.CaptainProfile(2.0, 0.5, 3));

        long start = System.nanoTime();
        LineupOptimizer.Result result = new LineupOptimizer().optimize(our, profile, predictor, new GlickoBaseline(0.05), 2025);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(result.bestResponse);
        assertTrue(elapsedMs < 2000, "expected under 2000ms for a 12-roster x K=24 search, took " + elapsedMs + "ms");
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
