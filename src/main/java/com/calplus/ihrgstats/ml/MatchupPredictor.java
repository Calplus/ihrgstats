package com.calplus.ihrgstats.ml;

/**
 * A per-board 3-outcome matchup predictor: given one board's as-of feature
 * snapshot ({@link FeatureExtractor.RawBoard}), produce P(A wins / draw /
 * B wins) from player A's perspective. Implementations must be exactly
 * symmetric: predicting the swapped board must return mirrored
 * probabilities (pWin and pLoss exchanged, pDraw identical).
 */
public interface MatchupPredictor {

    /** Win/draw/loss probabilities from player A's perspective. Always sums to 1. */
    class Probs {
        public final double pWin;
        public final double pDraw;
        public final double pLoss;

        public Probs(double pWin, double pDraw, double pLoss) {
            this.pWin = pWin;
            this.pDraw = pDraw;
            this.pLoss = pLoss;
        }

        /** Expected score for A (Glicko-comparable): P(win) + 0.5 * P(draw). */
        public double expectedScore() {
            return pWin + 0.5 * pDraw;
        }
    }

    Probs predict(FeatureExtractor.RawBoard board);

    /** Short family identifier persisted to ml_models.family (see E17_MlModels constants). */
    String family();
}
