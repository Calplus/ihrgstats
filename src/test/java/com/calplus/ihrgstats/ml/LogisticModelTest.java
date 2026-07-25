package com.calplus.ihrgstats.ml;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-math tests for {@link LogisticModel}: exact prediction symmetry,
 * convergence on separable data, byte-identical determinism, the Newton
 * optimizer's stationarity, and the linear solver.
 */
public class LogisticModelTest {

    // ------------------------------------------------------------------
    // Synthetic board builders (no DB)
    // ------------------------------------------------------------------

    static FeatureExtractor.Side side(String id, double rating, double rd, Integer seat,
                                      int career, int timeouts, double sum5, int cnt5, double prior) {
        return new FeatureExtractor.Side(id, 1, seat, rating, rd, career, timeouts, sum5, cnt5, prior);
    }

    static FeatureExtractor.RawBoard board(int matchId, int roundSeq, FeatureExtractor.Side a,
                                           FeatureExtractor.Side b, double outcomeA) {
        return new FeatureExtractor.RawBoard(matchId, roundSeq, roundSeq, 2025, roundSeq + 1, a, b, outcomeA, false, false);
    }

    /** Rating gap decides everything; includes a sprinkle of draws for stage D. */
    static List<FeatureExtractor.RawBoard> separableData() {
        List<FeatureExtractor.RawBoard> data = new ArrayList<>();
        int matchId = 0;
        for (int round = 0; round < 30; round++) {
            for (int i = 0; i < 4; i++) {
                double strong = 1200 + 10 * i;
                double weak = 900 - 10 * i;
                boolean strongIsA = (round + i) % 2 == 0; // alternate sides so anti features vary sign
                FeatureExtractor.Side s = side("S" + i, strong, 80, 1, 20, 0, 4.0, 5, 1000);
                FeatureExtractor.Side w = side("W" + i, weak, 80, 5, 20, 0, 1.0, 5, 1000);
                if ((round * 4 + i) % 15 == 0) {
                    data.add(board(matchId++, round, s, w, 0.5)); // occasional draw
                } else if (strongIsA) {
                    data.add(board(matchId++, round, s, w, 1.0));
                } else {
                    data.add(board(matchId++, round, w, s, 0.0));
                }
            }
        }
        return data;
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void predictionsAreExactlySymmetric() {
        LogisticModel model = LogisticModel.fit(separableData(), 6.0, 1.0);
        FeatureExtractor.RawBoard probe = board(999, 40,
                side("X", 1100, 120, 2, 8, 1, 2.5, 5, 1020),
                side("Y", 980, 200, 4, 3, 0, 1.0, 3, 990), 1.0);
        MatchupPredictor.Probs p = model.predict(probe);
        MatchupPredictor.Probs q = model.predict(FeatureExtractor.swapped(probe));
        assertEquals(p.pWin, q.pLoss, 1e-12);
        assertEquals(p.pLoss, q.pWin, 1e-12);
        assertEquals(p.pDraw, q.pDraw, 1e-12);
        assertEquals(1.0, p.pWin + p.pDraw + p.pLoss, 1e-12);
    }

    @Test
    void convergesOnSeparableData() {
        LogisticModel model = LogisticModel.fit(separableData(), 6.0, 1.0);
        FeatureExtractor.RawBoard probe = board(999, 40,
                side("S", 1250, 80, 1, 20, 0, 4.0, 5, 1000),
                side("W", 850, 80, 5, 20, 0, 1.0, 5, 1000), 1.0);
        MatchupPredictor.Probs p = model.predict(probe);
        assertTrue(p.pWin > 0.8, "clearly stronger side should be a heavy favorite, got pWin=" + p.pWin);
    }

    @Test
    void refittingIsByteIdentical() {
        List<FeatureExtractor.RawBoard> data = separableData();
        String first = LogisticModel.fit(data, 6.0, 1.0).toParamsJson();
        String second = LogisticModel.fit(data, 6.0, 1.0).toParamsJson();
        assertEquals(first, second);
    }

    @Test
    void codecRoundTripPreservesPredictions() {
        LogisticModel model = LogisticModel.fit(separableData(), 3.0, 0.3);
        FeatureExtractor.RawBoard probe = board(999, 40,
                side("X", 1080, 150, 3, 6, 2, 3.0, 5, 1010),
                side("Y", 1020, 90, 2, 15, 0, 2.0, 5, 995), 1.0);
        MatchupPredictor decoded = ModelCodec.decode(model.family(), ModelCodec.encode(model));
        MatchupPredictor.Probs p = model.predict(probe);
        MatchupPredictor.Probs q = decoded.predict(probe);
        assertEquals(p.pWin, q.pWin, 1e-12);
        assertEquals(p.pDraw, q.pDraw, 1e-12);
    }

    /** At the Newton solution the penalized score must be (numerically) stationary. */
    @Test
    void newtonReachesStationaryPoint() {
        double[][] x = {
                {1.0, 0.2, -1.1}, {1.0, 1.4, 0.3}, {1.0, -0.7, 0.8}, {1.0, 0.1, 0.1},
                {1.0, -1.2, -0.4}, {1.0, 0.9, 1.2}, {1.0, 0.4, -0.6}, {1.0, -0.3, 0.5}
        };
        double[] y = {1, 1, 0, 1, 0, 1, 0, 0};
        double[] ridge = {0.01, 1.0, 1.0};
        double[] w = LogisticModel.newtonLogistic(x, y, ridge);
        double[] grad = new double[3];
        for (int i = 0; i < x.length; i++) {
            double z = 0;
            for (int j = 0; j < 3; j++) {
                z += w[j] * x[i][j];
            }
            double pi = 1.0 / (1.0 + Math.exp(-z));
            for (int j = 0; j < 3; j++) {
                grad[j] += (y[i] - pi) * x[i][j];
            }
        }
        for (int j = 0; j < 3; j++) {
            grad[j] -= ridge[j] * w[j];
            assertEquals(0.0, grad[j], 1e-6, "penalized gradient component " + j + " not ~0");
        }
    }

    @Test
    void linearSolverSolvesKnownSystem() {
        double[][] a = {{4, 1}, {1, 3}};
        double[] b = {1, 2};
        double[] x = LogisticModel.solve(a, b);
        assertEquals(1.0 / 11.0, x[0], 1e-12);
        assertEquals(7.0 / 11.0, x[1], 1e-12);
    }

    @Test
    void emptyAndAllDrawInputsYieldNeutralModel() {
        LogisticModel empty = LogisticModel.fit(List.of(), 6.0, 1.0);
        FeatureExtractor.RawBoard probe = board(1, 0,
                side("X", 1000, 350, 1, 0, 0, 0, 0, 1000),
                side("Y", 1000, 350, 5, 0, 0, 0, 0, 1000), 1.0);
        MatchupPredictor.Probs p = empty.predict(probe);
        assertEquals(1.0, p.pWin + p.pDraw + p.pLoss, 1e-12);
        assertEquals(p.pWin, p.pLoss, 1e-12); // identical unknown players -> even odds

        // All-draw training: stage W has zero rows and must stay neutral, not crash.
        List<FeatureExtractor.RawBoard> allDraws = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            allDraws.add(board(i, i, side("X", 1000, 100, 1, 5, 0, 2.5, 5, 1000),
                    side("Y", 1000, 100, 2, 5, 0, 2.5, 5, 1000), 0.5));
        }
        MatchupPredictor.Probs q = LogisticModel.fit(allDraws, 6.0, 1.0).predict(probe);
        assertEquals(q.pWin, q.pLoss, 1e-12);
        assertTrue(q.pDraw > 0.5, "trained on only draws, draw should dominate");
    }
}
