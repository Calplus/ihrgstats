package com.calplus.ihrgstats.ml;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scientific guards, run on synthetic data (no DB):
 * - signal recovery: when seats genuinely decide outcomes, the covariate
 *   model must beat the Glicko baseline in walk-forward;
 * - noise guard: when outcomes are pure noise, the champion must REMAIN
 *   the baseline - no false "ML wins" on luck-sized evidence.
 * Both datasets are generated with a fixed deterministic LCG, so these
 * tests can never flake.
 */
public class BacktestHarnessTest {

    /** Tiny deterministic LCG - keeps the harness free of java.util.Random seeds drifting across JDKs. */
    private static long lcg(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }

    private static FeatureExtractor.Side side(String id, int seat) {
        return new FeatureExtractor.Side(id, 1, seat, 1000.0, 100.0, 20, 0, 2.5, 5, 1000.0,
                0.0, 0.0, 0.0, 20, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0, 0.1, 0.5);
    }

    /** 40 rounds x 3 boards; equal ratings everywhere; seat difference decides (equal seats draw). */
    private static List<FeatureExtractor.RawBoard> seatSignalData() {
        List<FeatureExtractor.RawBoard> data = new ArrayList<>();
        long state = 42;
        int matchId = 0;
        for (int round = 0; round < 40; round++) {
            for (int b = 0; b < 3; b++) {
                state = lcg(state);
                int seatA = (int) Math.floorMod(state >> 16, 5) + 1;
                state = lcg(state);
                int seatB = (int) Math.floorMod(state >> 16, 5) + 1;
                double outcome = seatA < seatB ? 1.0 : (seatA > seatB ? 0.0 : 0.5);
                data.add(new FeatureExtractor.RawBoard(matchId, round, round, 2025, round + 1,
                        side("A" + matchId, seatA), side("B" + matchId, seatB), outcome, false, false, 0.0));
                matchId++;
            }
        }
        return data;
    }

    /** Same shape, but outcomes are coin flips unrelated to any feature. */
    private static List<FeatureExtractor.RawBoard> noiseData() {
        List<FeatureExtractor.RawBoard> data = new ArrayList<>();
        long state = 1234567;
        int matchId = 0;
        for (int round = 0; round < 40; round++) {
            for (int b = 0; b < 3; b++) {
                state = lcg(state);
                int seatA = (int) Math.floorMod(state >> 16, 5) + 1;
                state = lcg(state);
                int seatB = (int) Math.floorMod(state >> 16, 5) + 1;
                state = lcg(state);
                long roll = Math.floorMod(state >> 16, 20);
                double outcome = roll == 0 ? 0.5 : (roll % 2 == 0 ? 1.0 : 0.0);
                data.add(new FeatureExtractor.RawBoard(matchId, round, round, 2025, round + 1,
                        side("A" + matchId, seatA), side("B" + matchId, seatB), outcome, false, false, 0.0));
                matchId++;
            }
        }
        return data;
    }

    @Test
    void recoversRealSeatSignal() {
        List<BacktestHarness.Result> results =
                BacktestHarness.runAll(seatSignalData(), BacktestHarness.segmentACandidates(), 10);
        BacktestHarness.Result baseline = results.get(0);
        int champion = ModelTrainer.pickChampion(results, 50);
        assertNotEquals(0, champion, "with a genuine seat signal the logistic model must dethrone the baseline");
        assertTrue(results.get(champion).brier < baseline.brier - 0.05,
                String.format("expected a clear Brier win, got %.5f vs baseline %.5f",
                        results.get(champion).brier, baseline.brier));
        assertTrue(results.get(champion).roundsBetterThanBaseline > results.get(champion).roundsWorseThanBaseline);
    }

    @Test
    void doesNotCrownAModelOnNoise() {
        List<BacktestHarness.Result> results =
                BacktestHarness.runAll(noiseData(), BacktestHarness.segmentACandidates(), 10);
        int champion = ModelTrainer.pickChampion(results, 50);
        assertEquals(0, champion,
                "outcomes were pure noise - the baseline must remain champion, not a lucky config");
    }

    @Test
    void burnInDefaultsToFirstYearWhenLonger() {
        List<FeatureExtractor.RawBoard> data = new ArrayList<>();
        for (int round = 0; round < 14; round++) {
            int year = round < 12 ? 2024 : 2025; // 12 first-year rounds > the 10 floor
            data.add(new FeatureExtractor.RawBoard(round, round, round, year, round + 1,
                    side("A", 1), side("B", 2), 1.0, false, false, 0.0));
        }
        assertEquals(12, BacktestHarness.defaultBurnIn(data));
        assertEquals(10, BacktestHarness.defaultBurnIn(data.subList(12, 14))); // tiny history -> floor of 10
    }

    /**
     * Regression test: a SINGLE-year history's "first year's round count"
     * always equals the running total, so the multi-year rule would make
     * burn-in chase the total upward forever and never predict a single
     * round, no matter how large the first (only) season grew - silently
     * disabling training for any club in its first-ever season. A lone
     * year must fall back to the fixed 10-round floor instead.
     */
    @Test
    void burnInStaysAtTheFixedFloorForASingleYearNoMatterHowManyRounds() {
        List<FeatureExtractor.RawBoard> data = new ArrayList<>();
        for (int round = 0; round < 50; round++) {
            data.add(new FeatureExtractor.RawBoard(round, round, round, 2026, round + 1,
                    side("A", 1), side("B", 2), 1.0, false, false, 0.0));
        }
        assertEquals(10, BacktestHarness.defaultBurnIn(data),
                "50 rounds, all in one single year, must not push burn-in past the fixed floor");

        // And walk-forward must actually be able to predict rounds beyond that floor.
        BacktestHarness.Result result = BacktestHarness.run(data,
                BacktestHarness.segmentACandidates().get(0), BacktestHarness.defaultBurnIn(data));
        assertEquals(40, result.predictedRounds, "rounds 11-50 (40 rounds) should be predictable");
        assertTrue(result.predictedBoards > 0);
    }

    @Test
    void perCandidateMetricsAreInternallyConsistent() {
        List<BacktestHarness.Result> results =
                BacktestHarness.runAll(seatSignalData(), BacktestHarness.segmentACandidates(), 10);
        for (BacktestHarness.Result r : results) {
            assertEquals(30, r.predictedRounds);   // 40 rounds - 10 burn-in
            assertEquals(90, r.predictedBoards);   // x3 boards
            assertTrue(r.brier >= 0 && r.brier <= 2.0);
            assertTrue(r.accuracy >= 0 && r.accuracy <= 1.0);
            long calibCount = 0;
            for (double[] bucket : r.calibration) {
                calibCount += (long) bucket[2];
            }
            assertEquals(90, calibCount, "every predicted board lands in exactly one calibration bucket");
        }
    }
}
