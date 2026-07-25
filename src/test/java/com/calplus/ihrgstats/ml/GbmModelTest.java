package com.calplus.ihrgstats.ml;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GbmModel} tests: exact prediction symmetry (including through the
 * NaN missing-seat path), determinism, and the model-class capability the
 * logistic yardstick provably lacks - a three-way sign-parity interaction
 * (parity-of-3), the antisymmetric generalization of XOR.
 *
 * Why parity-of-3 and not a simple two-signal XOR: any boolean function of
 * exactly TWO antisymmetric (sign-flipping) inputs u,v is invariant, not
 * antisymmetric, under the joint negation u,v -> -u,-v that a full A/B
 * swap performs (XOR(-u,-v) = XOR(u,v), same for XNOR) - so a two-signal
 * XOR ground truth is fundamentally impossible for ANY correctly
 * antisymmetric matchup model to represent (the antisymmetrizing wrapper
 * f(x) = 0.5*(raw(x)-raw(-x)) provably zeroes out exactly this kind of
 * swap-invariant pattern, by construction - confirmed by hand before
 * settling on this test). Parity of an ODD count of sign-flipping inputs
 * (here 3: rating sign, seat sign, career sign) IS antisymmetric under
 * a full swap and is the standard example of an interaction no linear
 * model can represent, so it is exactly the right shape for this test.
 */
public class GbmModelTest {

    private static long lcg(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }

    static FeatureExtractor.Side side(String id, double rating, Integer seat, int career) {
        return new FeatureExtractor.Side(id, 1, seat, rating, 100.0, career, 0, career * 0.5, Math.min(career, 5), rating,
                0.0, 0.0, 0.0, career, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0, 0.1, 0.5);
    }

    static FeatureExtractor.RawBoard board(int matchId, int roundSeq, FeatureExtractor.Side a,
                                           FeatureExtractor.Side b, double outcomeA) {
        return new FeatureExtractor.RawBoard(matchId, roundSeq, roundSeq, 2025, roundSeq + 1, a, b, outcomeA, false, false, 0.0);
    }

    /**
     * Three independent antisymmetric sign signals - rating (anti[0]/[1]),
     * seat (anti[2]), career (anti[3]) - each individually ~uncorrelated
     * with the label (marginal accuracy ~50%), but their PRODUCT decides
     * it exactly: outcomeA = 1 iff an EVEN number of the three signs are
     * negative. A linear-in-features model sums independent per-feature
     * weights and provably cannot represent a 3-way product; a tree with
     * depth <= 3 represents it exactly (up to 8 leaves for 8 sign
     * combinations).
     */
    private static List<FeatureExtractor.RawBoard> parity3Data() {
        List<FeatureExtractor.RawBoard> data = new ArrayList<>();
        // Three INDEPENDENT LCG streams (not three chained outputs of one
        // stream - consecutive outputs of a single LCG are known to be
        // correlated, lying near low-dimensional hyperplanes, which let
        // logistic exploit leftover structure in an earlier version of
        // this test instead of the signals being genuinely independent).
        long stateU = 777;
        long stateV = 88888;
        long stateW = 123456789;
        int matchId = 0;
        for (int round = 0; round < 80; round++) {
            for (int b = 0; b < 4; b++) {
                stateU = lcg(stateU);
                boolean uPos = ((stateU >>> 20) & 1) == 0; // rating sign
                stateV = lcg(stateV);
                boolean vPos = ((stateV >>> 20) & 1) == 0; // seat sign
                stateW = lcg(stateW);
                boolean wPos = ((stateW >>> 20) & 1) == 0; // career sign

                double ratingA = uPos ? 1200 : 900;
                double ratingB = uPos ? 900 : 1200;
                int seatA = vPos ? 1 : 5;
                int seatB = vPos ? 5 : 1;
                int careerA = wPos ? 30 : 5;
                int careerB = wPos ? 5 : 30;

                int negatives = (uPos ? 0 : 1) + (vPos ? 0 : 1) + (wPos ? 0 : 1);
                double outcome = (negatives % 2 == 0) ? 1.0 : 0.0;

                data.add(board(matchId, round, side("A" + matchId, ratingA, seatA, careerA),
                        side("B" + matchId, ratingB, seatB, careerB), outcome));
                matchId++;
            }
        }
        return data;
    }

    @Test
    void capturesParity3InteractionThatDefeatsLogistic() {
        List<FeatureExtractor.RawBoard> data = parity3Data();

        LogisticModel logistic = LogisticModel.fit(data, 6.0, 1.0);
        GbmModel gbm = GbmModel.fit(data, 6.0, 1.0);

        int logisticHits = 0;
        int gbmHits = 0;
        double logisticBrier = 0.0;
        double gbmBrier = 0.0;
        for (FeatureExtractor.RawBoard rb : data) {
            MatchupPredictor.Probs lp = logistic.predict(rb);
            MatchupPredictor.Probs gp = gbm.predict(rb);
            boolean lHit = (rb.outcomeA == 1.0) == (lp.pWin > lp.pLoss);
            boolean gHit = (rb.outcomeA == 1.0) == (gp.pWin > gp.pLoss);
            if (lHit) logisticHits++;
            if (gHit) gbmHits++;
            logisticBrier += sq(lp.pWin - (rb.outcomeA == 1.0 ? 1 : 0)) + sq(lp.pLoss - (rb.outcomeA == 0.0 ? 1 : 0));
            gbmBrier += sq(gp.pWin - (rb.outcomeA == 1.0 ? 1 : 0)) + sq(gp.pLoss - (rb.outcomeA == 0.0 ? 1 : 0));
        }
        double logisticAcc = (double) logisticHits / data.size();
        double gbmAcc = (double) gbmHits / data.size();

        assertTrue(logisticAcc < 0.65,
                "logistic should be near chance on a parity-3 interaction it cannot represent, got " + logisticAcc);
        assertTrue(gbmAcc > 0.9,
                "GBM should recover the parity-3 interaction via depth-3 splits, got " + gbmAcc);
        assertTrue(gbmBrier < logisticBrier * 0.5,
                String.format("expected a decisive Brier win, got gbm=%.3f logistic=%.3f", gbmBrier, logisticBrier));
    }

    private static double sq(double x) {
        return x * x;
    }

    @Test
    void predictionsAreExactlySymmetric() {
        GbmModel model = GbmModel.fit(parity3Data(), 6.0, 1.0);
        FeatureExtractor.RawBoard probe = board(999, 40, side("X", 1100, 2, 15), side("Y", 950, 4, 8), 1.0);
        MatchupPredictor.Probs p = model.predict(probe);
        MatchupPredictor.Probs q = model.predict(FeatureExtractor.swapped(probe));
        assertEquals(p.pWin, q.pLoss, 1e-12);
        assertEquals(p.pLoss, q.pWin, 1e-12);
        assertEquals(p.pDraw, q.pDraw, 1e-12);
        assertEquals(1.0, p.pWin + p.pDraw + p.pLoss, 1e-9);
    }

    @Test
    void symmetryHoldsThroughTheMissingSeatPath() {
        GbmModel model = GbmModel.fit(parity3Data(), 6.0, 1.0);
        FeatureExtractor.RawBoard probe = board(999, 40, side("X", 1100, null, 15), side("Y", 950, 4, 8), 1.0);
        MatchupPredictor.Probs p = model.predict(probe);
        MatchupPredictor.Probs q = model.predict(FeatureExtractor.swapped(probe));
        assertTrue(Double.isFinite(p.pWin) && Double.isFinite(p.pDraw) && Double.isFinite(p.pLoss));
        assertEquals(p.pWin, q.pLoss, 1e-12);
        assertEquals(p.pDraw, q.pDraw, 1e-12);
        assertEquals(1.0, p.pWin + p.pDraw + p.pLoss, 1e-9);
    }

    @Test
    void refittingIsByteIdentical() {
        List<FeatureExtractor.RawBoard> data = parity3Data();
        String first = GbmModel.fit(data, 6.0, 1.0).toParamsJson();
        String second = GbmModel.fit(data, 6.0, 1.0).toParamsJson();
        assertEquals(first, second);
    }

    @Test
    void codecRoundTripPreservesPredictions() {
        GbmModel model = GbmModel.fit(parity3Data(), 6.0, 1.0);
        FeatureExtractor.RawBoard probe = board(999, 40, side("X", 1080, 2, 12), side("Y", 1020, 3, 9), 1.0);
        MatchupPredictor decoded = ModelCodec.decode(model.family(), ModelCodec.encode(model));
        MatchupPredictor.Probs p = model.predict(probe);
        MatchupPredictor.Probs q = decoded.predict(probe);
        assertEquals(p.pWin, q.pWin, 1e-12);
        assertEquals(p.pDraw, q.pDraw, 1e-12);
    }

    @Test
    void emptyTrainingDataYieldsNeutralModel() {
        GbmModel model = GbmModel.fit(List.of(), 6.0, 1.0);
        FeatureExtractor.RawBoard probe = board(1, 0, side("X", 1000, 1, 0), side("Y", 1000, 5, 0), 1.0);
        MatchupPredictor.Probs p = model.predict(probe);
        assertEquals(1.0, p.pWin + p.pDraw + p.pLoss, 1e-9);
        assertEquals(p.pWin, p.pLoss, 1e-9);
    }
}
