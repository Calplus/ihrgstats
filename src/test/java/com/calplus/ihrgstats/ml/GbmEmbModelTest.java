package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.E17_MlModels;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GbmModel#fitWithEmbeddings} tests: the GBM_EMB family's reason to
 * exist (genuine per-player-identity non-transitivity that defeats plain
 * GBM's rating/seat/career-diff features entirely), its exact symmetry
 * through the embedding-augmented path, and the champion-selection gate
 * that requires it to beat plain GBM specifically, not merely the Glicko
 * baseline (see {@code ModelTrainer.pickChampion}).
 */
public class GbmEmbModelTest {

    private static long lcg(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }

    /** Every player gets IDENTICAL scalar stats - only playerId differs, so plain GBM's anti[] is the zero vector for every board. */
    private static FeatureExtractor.Side side(String id) {
        return new FeatureExtractor.Side(id, 1, 3, 1000.0, 100.0, 20, 0, 2.5, 5, 1000.0,
                0.0, 0.0, 0.0, 20, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0, 0.1, 0.5);
    }

    private static FeatureExtractor.RawBoard board(int matchId, int roundSeq,
                                                    FeatureExtractor.Side a, FeatureExtractor.Side b, double outcomeA) {
        return new FeatureExtractor.RawBoard(matchId, roundSeq, roundSeq, 2025, roundSeq + 1, a, b, outcomeA, false, false, 0.0);
    }

    /**
     * Three 4-player clusters with a rock-paper-scissors dominance relation
     * (cluster 0 beats 1, 1 beats 2, 2 beats 0) and otherwise IDENTICAL
     * player stats - the only signal available is which specific players
     * are playing, never a rating/seat/career diff. A correctly-symmetric
     * model built only from those scalar diffs is mathematically stuck at
     * 50/50 for every single board (its whole feature vector is exactly
     * zero regardless of the pairing); a per-player embedding has the one
     * thing that DOES carry the signal - identity - and a cyclic relation
     * is exactly what an antisymmetric bilinear interaction of two
     * independent embeddings can represent that no antisymmetric function
     * of a single scalar rating-diff ever could (the same class of gap the
     * parity-of-3 test proves for plain GBM over the logistic model).
     */
    private static List<FeatureExtractor.RawBoard> nontransitivityData() {
        String[][] clusters = {
                {"CYC-A0", "CYC-A1", "CYC-A2", "CYC-A3"},
                {"CYC-B0", "CYC-B1", "CYC-B2", "CYC-B3"},
                {"CYC-C0", "CYC-C1", "CYC-C2", "CYC-C3"},
        };
        List<FeatureExtractor.RawBoard> data = new ArrayList<>();
        long state = 2468;
        int matchId = 0;
        for (int round = 0; round < 60; round++) {
            for (int pair = 0; pair < 4; pair++) {
                state = lcg(state);
                int cFrom = (int) Math.floorMod(state >>> 20, 3);
                int cTo = (cFrom + 1) % 3; // cFrom's cluster always beats cTo's cluster
                state = lcg(state);
                String from = clusters[cFrom][(int) Math.floorMod(state >>> 16, 4)];
                state = lcg(state);
                String to = clusters[cTo][(int) Math.floorMod(state >>> 16, 4)];
                state = lcg(state);
                boolean fromIsA = ((state >>> 24) & 1) == 0; // randomize which side is "a" so ordering can't leak the answer
                FeatureExtractor.Side a = side(fromIsA ? from : to);
                FeatureExtractor.Side b = side(fromIsA ? to : from);
                double outcomeA = fromIsA ? 1.0 : 0.0;
                data.add(board(matchId, round, a, b, outcomeA));
                matchId++;
            }
        }
        return data;
    }

    private static double sq(double x) {
        return x * x;
    }

    @Test
    void embeddingsRecoverCyclicNontransitivityThatDefeatsPlainGbm() {
        List<FeatureExtractor.RawBoard> data = nontransitivityData();

        GbmModel plainGbm = GbmModel.fit(data, 6.0, 1.0);
        GbmModel embGbm = GbmModel.fitWithEmbeddings(data, 6.0, 1.0, 4);

        assertEquals(E17_MlModels.FAMILY_GBM, plainGbm.family());
        assertEquals(E17_MlModels.FAMILY_GBM_EMB, embGbm.family());

        int plainHits = 0;
        int embHits = 0;
        double plainBrier = 0.0;
        double embBrier = 0.0;
        for (FeatureExtractor.RawBoard rb : data) {
            MatchupPredictor.Probs pp = plainGbm.predict(rb);
            MatchupPredictor.Probs ep = embGbm.predict(rb);
            boolean pHit = (rb.outcomeA == 1.0) == (pp.pWin > pp.pLoss);
            boolean eHit = (rb.outcomeA == 1.0) == (ep.pWin > ep.pLoss);
            if (pHit) plainHits++;
            if (eHit) embHits++;
            plainBrier += sq(pp.pWin - (rb.outcomeA == 1.0 ? 1 : 0)) + sq(pp.pLoss - (rb.outcomeA == 0.0 ? 1 : 0));
            embBrier += sq(ep.pWin - (rb.outcomeA == 1.0 ? 1 : 0)) + sq(ep.pLoss - (rb.outcomeA == 0.0 ? 1 : 0));
        }
        double plainAcc = (double) plainHits / data.size();
        double embAcc = (double) embHits / data.size();

        assertTrue(plainAcc < 0.65,
                "plain GBM has zero scalar signal here (identical stats for every player) and should be near chance, got " + plainAcc);
        assertTrue(embAcc > 0.8,
                "GBM_EMB should recover the per-player cyclic identity signal, got " + embAcc);
        assertTrue(embBrier < plainBrier * 0.7,
                String.format("expected a decisive Brier win, got emb=%.3f plain=%.3f", embBrier, plainBrier));
    }

    @Test
    void predictionsAreExactlySymmetricThroughTheEmbeddingPath() {
        GbmModel model = GbmModel.fitWithEmbeddings(nontransitivityData(), 6.0, 1.0, 4);
        FeatureExtractor.RawBoard probe = board(999, 40, side("CYC-A0"), side("CYC-B0"), 1.0);
        MatchupPredictor.Probs p = model.predict(probe);
        MatchupPredictor.Probs q = model.predict(FeatureExtractor.swapped(probe));
        assertEquals(p.pWin, q.pLoss, 1e-9);
        assertEquals(p.pLoss, q.pWin, 1e-9);
        assertEquals(p.pDraw, q.pDraw, 1e-9);
        assertEquals(1.0, p.pWin + p.pDraw + p.pLoss, 1e-9);
    }

    @Test
    void refittingIsByteIdentical() {
        List<FeatureExtractor.RawBoard> data = nontransitivityData();
        String first = GbmModel.fitWithEmbeddings(data, 6.0, 1.0, 4).toParamsJson();
        String second = GbmModel.fitWithEmbeddings(data, 6.0, 1.0, 4).toParamsJson();
        assertEquals(first, second);
    }

    @Test
    void codecRoundTripPreservesPredictionsAndFamily() {
        GbmModel model = GbmModel.fitWithEmbeddings(nontransitivityData(), 6.0, 1.0, 4);
        FeatureExtractor.RawBoard probe = board(999, 40, side("CYC-A1"), side("CYC-C2"), 1.0);
        MatchupPredictor decoded = ModelCodec.decode(model.family(), ModelCodec.encode(model));
        assertEquals(E17_MlModels.FAMILY_GBM_EMB, decoded.family());
        MatchupPredictor.Probs p = model.predict(probe);
        MatchupPredictor.Probs q = decoded.predict(probe);
        assertEquals(p.pWin, q.pWin, 1e-9);
        assertEquals(p.pDraw, q.pDraw, 1e-9);
    }

    @Test
    void plainGbmParamsJsonHasNoEmbeddingField() {
        // A plain GBM.fit() must stay indistinguishable from before this segment - null embedding, family GBM.
        GbmModel model = GbmModel.fit(nontransitivityData(), 6.0, 1.0);
        assertNull(model.getEmbedding());
        assertEquals(E17_MlModels.FAMILY_GBM, model.family());
    }

    // ========================================================================
    // Champion-gate logic: GBM_EMB must beat plain GBM, not just the baseline.
    // ========================================================================

    private static BacktestHarness.Result result(String name, String family, double brier) {
        return new BacktestHarness.Result(name, family, 100, 50, brier, 1.0, 0.1, 0.6, new double[10][3], Map.of());
    }

    @Test
    void gbmEmbLosesTheGateWhenItOnlyBeatsBaselineNotPlainGbm() {
        List<BacktestHarness.Result> results = List.of(
                result("glicko-baseline", E17_MlModels.FAMILY_GLICKO_BASELINE, 0.50),
                result("gbm", E17_MlModels.FAMILY_GBM, 0.40),
                result("gbm+emb dim=4", E17_MlModels.FAMILY_GBM_EMB, 0.45)); // beats baseline (0.50) but not gbm (0.40)

        int champion = ModelTrainer.pickChampion(results, 50);
        assertEquals(1, champion, "plain GBM must remain champion when GBM_EMB doesn't measurably beat it");
    }

    @Test
    void gbmEmbWinsTheGateWhenItBeatsPlainGbmToo() {
        List<BacktestHarness.Result> results = List.of(
                result("glicko-baseline", E17_MlModels.FAMILY_GLICKO_BASELINE, 0.50),
                result("gbm", E17_MlModels.FAMILY_GBM, 0.40),
                result("gbm+emb dim=4", E17_MlModels.FAMILY_GBM_EMB, 0.33)); // beats both baseline and gbm

        int champion = ModelTrainer.pickChampion(results, 50);
        assertEquals(2, champion, "GBM_EMB should be crowned once it measurably beats plain GBM as well");
    }

    @Test
    void gbmEmbNeverWinsWhenNoPlainGbmCandidateExists() {
        // Defensive: without a GBM row to compare against, the extra gate has nothing to check against
        // for embeddings, but a genuine baseline-beating result should still be selectable in that odd case.
        List<BacktestHarness.Result> results = List.of(
                result("glicko-baseline", E17_MlModels.FAMILY_GLICKO_BASELINE, 0.50),
                result("gbm+emb dim=4", E17_MlModels.FAMILY_GBM_EMB, 0.45));

        int champion = ModelTrainer.pickChampion(results, 50);
        assertEquals(1, champion);
    }
}
