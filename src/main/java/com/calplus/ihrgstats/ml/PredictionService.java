package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.E17_MlModels;

import java.sql.SQLException;
import java.util.List;

/**
 * Loads the currently-crowned champion model and serves predictions from
 * it. Thin wrapper so callers (the upload pipeline's prediction-logging
 * hook, and commands like {@code /predict}) don't each re-implement
 * "load champion row -> decode -> predict".
 */
public class PredictionService {

    private final E17_MlModels mlModels = new E17_MlModels();

    /** The current champion, or null if no training run has ever completed (empty/too-thin database). */
    public MatchupPredictor loadChampion() throws SQLException {
        E17_MlModels.MlModel champion = mlModels.getChampion();
        if (champion == null) {
            return null;
        }
        return ModelCodec.decode(champion.family, champion.paramsJson);
    }

    /** The champion's stored model_version, or null if there is no champion yet. */
    public String championVersion() throws SQLException {
        E17_MlModels.MlModel champion = mlModels.getChampion();
        return champion != null ? champion.modelVersion : null;
    }

    /**
     * Builds a hypothetical "if these two played right now" board for
     * {@code /predict}: each side's features come from that PLAYER's own
     * most recently played rated board (their state entering their last
     * round - one round short of perfectly live, since it doesn't yet
     * include their own most recent result, but the closest available
     * without a dedicated live-state sweep). A player who has never
     * played gets neutral defaults, matching {@link FeatureExtractor}'s
     * own debutant conventions exactly.
     *
     * Sides are combined across what may be two DIFFERENT rounds/opponent
     * contexts (each reflects that player's own real history, not a
     * shared as-of moment) - an accepted simplification for an ad-hoc
     * scouting tool, not a training-grade guarantee. matchId/roundId are
     * dummy placeholders (-1); predict() never reads them.
     */
    public FeatureExtractor.RawBoard buildHypotheticalBoard(String playerAId, String playerBId, int fallbackYear) throws SQLException {
        List<FeatureExtractor.RawBoard> all = new FeatureExtractor().extractAll();
        FeatureExtractor.Side sideA = latestSideFor(all, playerAId);
        FeatureExtractor.Side sideB = latestSideFor(all, playerBId);
        int roundOrder = all.isEmpty() ? 1 : all.get(all.size() - 1).roundOrder + 1;
        int year = all.isEmpty() ? fallbackYear : all.get(all.size() - 1).year;
        return new FeatureExtractor.RawBoard(-1, Integer.MAX_VALUE, -1, year, roundOrder, sideA, sideB, 0.0, false, false, 0.0);
    }

    private static FeatureExtractor.Side latestSideFor(List<FeatureExtractor.RawBoard> all, String playerId) {
        FeatureExtractor.RawBoard latest = null;
        for (FeatureExtractor.RawBoard rb : all) {
            if (rb.a.playerId.equals(playerId) || rb.b.playerId.equals(playerId)) {
                if (latest == null || rb.roundSeq > latest.roundSeq) {
                    latest = rb;
                }
            }
        }
        if (latest == null) {
            return defaultSide(playerId);
        }
        return latest.a.playerId.equals(playerId) ? latest.a : latest.b;
    }

    /** Fits a fresh Glicko baseline (draw rate) from the full stored history - the free "side by side" comparator. */
    public GlickoBaseline fitGlickoBaseline() throws SQLException {
        return GlickoBaseline.fit(new FeatureExtractor().extractAll());
    }

    /** Mirrors FeatureExtractor's own debutant conventions exactly (default rating/RD, neutral covariates). */
    private static FeatureExtractor.Side defaultSide(String playerId) {
        return new FeatureExtractor.Side(playerId, -1, null, FeatureExtractor.DEFAULT_RATING, FeatureExtractor.DEFAULT_RD,
                0, 0, 0.0, 0, FeatureExtractor.DEFAULT_RATING,
                0.0, 0.0, 0.0, 0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0, 0.1, 0.5);
    }
}
