package com.calplus.ihrgstats.ml.embed;

import com.calplus.ihrgstats.ml.FeatureExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EmbeddingNet} tests: hand-derived backprop checked against a
 * numeric finite-difference gradient (the same discipline
 * {@code LogisticModel.newtonLogistic} gets via
 * {@code LogisticModelTest.newtonReachesStationaryPoint}), exact
 * antisymmetry of the served interaction features, seeded determinism, and
 * cold-start behavior for a player/hall never seen in training.
 */
public class EmbeddingNetTest {

    private static FeatureExtractor.Side side(String id, int hallId) {
        return new FeatureExtractor.Side(id, hallId, 3, 1000.0, 100.0, 20, 0, 2.5, 5, 1000.0,
                0.0, 0.0, 0.0, 20, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0, 0.1, 0.5);
    }

    private static FeatureExtractor.RawBoard board(int matchId, int roundSeq,
                                                    FeatureExtractor.Side a, FeatureExtractor.Side b, double outcomeA) {
        return new FeatureExtractor.RawBoard(matchId, roundSeq, roundSeq, 2025, roundSeq + 1, a, b, outcomeA, false, false, 0.0);
    }

    private static List<FeatureExtractor.RawBoard> tinyTrainingSet() {
        return List.of(
                board(1, 0, side("P1", 1), side("P2", 2), 1.0),
                board(2, 1, side("P2", 2), side("P3", 1), 0.0),
                board(3, 2, side("P3", 1), side("P1", 2), 1.0)
        );
    }

    /**
     * The core correctness check for the hand-rolled backprop: for a fixed
     * (untrained) parameter set, the analytic gradient {@code accumulate}
     * produces for a single example must match a central-difference numeric
     * gradient of that same example's loss, for both an embedding
     * dimension and an MLP weight - the two very different code paths
     * (embedding lookup -> concat vs. matrix multiply -> tanh) a
     * mis-derived chain rule could get wrong independently.
     */
    @Test
    void analyticGradientMatchesNumericFiniteDifferenceForEmbeddingAndWeight() {
        EmbeddingNet.Params p = EmbeddingNet.initParams(tinyTrainingSet(), 3);
        EmbeddingNet.Example ex = new EmbeddingNet.Example("P1", 1, "P2", 2, 1.0);

        EmbeddingNet.GradAccum g = new EmbeddingNet.GradAccum(p);
        EmbeddingNet.accumulate(p, ex, g);

        double eps = 1e-6;

        // Embedding dimension 0 of player P1.
        double[] embP1 = p.playerEmb.get("P1");
        double analyticEmb = g.playerGrad.get("P1")[0];
        double numericEmb = numericGradient(p, ex, () -> embP1[0], v -> embP1[0] = v, eps);
        assertEquals(numericEmb, analyticEmb, 1e-4, "embedding-dimension gradient mismatch");

        // One MLP first-layer weight.
        double analyticW1 = g.gw1[0][0];
        double numericW1 = numericGradient(p, ex, () -> p.w1[0][0], v -> p.w1[0][0] = v, eps);
        assertEquals(numericW1, analyticW1, 1e-4, "w1 weight gradient mismatch");

        // Output bias.
        double analyticB2 = g.gb2;
        double numericB2 = numericGradient(p, ex, () -> p.b2, v -> p.b2 = v, eps);
        assertEquals(numericB2, analyticB2, 1e-4, "b2 gradient mismatch");
    }

    private interface Getter {
        double get();
    }

    private interface Setter {
        void set(double v);
    }

    private static double numericGradient(EmbeddingNet.Params p, EmbeddingNet.Example ex, Getter get, Setter set, double eps) {
        double orig = get.get();
        set.set(orig + eps);
        double lossPlus = EmbeddingNet.meanLoss(p, List.of(ex));
        set.set(orig - eps);
        double lossMinus = EmbeddingNet.meanLoss(p, List.of(ex));
        set.set(orig);
        return (lossPlus - lossMinus) / (2 * eps);
    }

    @Test
    void interactionScoreIsExactlyAntisymmetric() {
        EmbeddingNet.Params p = EmbeddingNet.fit(tinyTrainingSet(), 3);
        double ab = EmbeddingNet.interactionScore(p, "P1", 1, "P2", 2);
        double ba = EmbeddingNet.interactionScore(p, "P2", 2, "P1", 1);
        assertEquals(ab, -ba, 1e-12);
    }

    @Test
    void interactionFeaturesAreExactlyAntisymmetric() {
        EmbeddingNet.Params p = EmbeddingNet.fit(tinyTrainingSet(), 3);
        double[] ab = EmbeddingNet.interactionFeatures(p, "P1", 1, "P2", 2);
        double[] ba = EmbeddingNet.interactionFeatures(p, "P2", 2, "P1", 1);
        assertEquals(ab.length, ba.length);
        for (int i = 0; i < ab.length; i++) {
            assertEquals(ab[i], -ba[i], 1e-12, "dim " + i + " not antisymmetric");
        }
        assertEquals(EmbeddingNet.featureCount(p), ab.length);
    }

    @Test
    void refittingIsByteIdenticalGivenTheSameData() {
        List<FeatureExtractor.RawBoard> data = tinyTrainingSet();
        EmbeddingNet.Params first = EmbeddingNet.fit(data, 4);
        EmbeddingNet.Params second = EmbeddingNet.fit(data, 4);
        assertEquals(first.playerEmb.keySet(), second.playerEmb.keySet());
        for (String id : first.playerEmb.keySet()) {
            assertArrayEquals(first.playerEmb.get(id), second.playerEmb.get(id), 0.0, "player " + id);
        }
        assertEquals(first.b2, second.b2, 0.0);
        for (int u = 0; u < first.hiddenUnits; u++) {
            assertArrayEquals(first.w1[u], second.w1[u], 0.0);
        }
    }

    @Test
    void unseenPlayerAndHallFallBackToZeroEmbeddingsAndScoreZero() {
        EmbeddingNet.Params p = EmbeddingNet.fit(tinyTrainingSet(), 3);
        // Both sides entirely unseen -> identical zero input either way round -> raw(A,B) == raw(B,A) -> interaction is exactly 0.
        double score = EmbeddingNet.interactionScore(p, "NEVER-SEEN-1", 99, "NEVER-SEEN-2", 98);
        assertEquals(0.0, score, 1e-12);
        double[] features = EmbeddingNet.interactionFeatures(p, "NEVER-SEEN-1", 99, "NEVER-SEEN-2", 98);
        for (double f : features) {
            assertEquals(0.0, f, 1e-12);
        }
    }

    @Test
    void emptyTrainingDataYieldsUsableZeroedNet() {
        EmbeddingNet.Params p = EmbeddingNet.fit(List.of(), 4);
        assertTrue(p.playerEmb.isEmpty());
        assertTrue(p.hallEmb.isEmpty());
        assertEquals(0.0, EmbeddingNet.interactionScore(p, "X", 1, "Y", 2), 1e-12);
    }
}
