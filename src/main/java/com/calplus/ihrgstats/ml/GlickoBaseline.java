package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.E17_MlModels;
import com.google.gson.Gson;

import java.util.List;

/**
 * The free yardstick every ML model must beat: plain Glicko-2 expected
 * score from the raw forward-pass ratings (no seat prior, no covariates),
 * draw-augmented with the historical draw base rate.
 *
 * E = Glicko expected score (includes draws at half weight), so with a
 * draw probability d: P(win) = E - d/2 clamped into [0, 1-d].
 */
public class GlickoBaseline implements MatchupPredictor {

    /** Gson-serializable parameter set. */
    public static class Params {
        public double drawRate;
    }

    private static final double DEFAULT_DRAW_RATE = 0.05;

    private final double drawRate;

    public GlickoBaseline(double drawRate) {
        this.drawRate = drawRate;
    }

    /** Fits the only parameter - the draw base rate - from training boards. */
    public static GlickoBaseline fit(List<FeatureExtractor.RawBoard> train) {
        if (train.isEmpty()) {
            return new GlickoBaseline(DEFAULT_DRAW_RATE);
        }
        long draws = train.stream().filter(FeatureExtractor.RawBoard::isDraw).count();
        return new GlickoBaseline((double) draws / train.size());
    }

    @Override
    public Probs predict(FeatureExtractor.RawBoard board) {
        double logit = FeatureExtractor.combinedG(board.a.rd, board.b.rd)
                * (board.a.rating - board.b.rating) / 173.7178;
        double expectedScore = 1.0 / (1.0 + Math.exp(-logit));
        double pDraw = drawRate;
        double pWin = Math.max(0.0, Math.min(1.0 - pDraw, expectedScore - pDraw / 2.0));
        double pLoss = 1.0 - pDraw - pWin;
        return new Probs(pWin, pDraw, pLoss);
    }

    @Override
    public String family() {
        return E17_MlModels.FAMILY_GLICKO_BASELINE;
    }

    public String toParamsJson() {
        Params p = new Params();
        p.drawRate = drawRate;
        return new Gson().toJson(p);
    }

    public static GlickoBaseline fromParamsJson(String json) {
        Params p = new Gson().fromJson(json, Params.class);
        return new GlickoBaseline(p.drawRate);
    }
}
