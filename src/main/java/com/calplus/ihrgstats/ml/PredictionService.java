package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.E17_MlModels;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the currently-crowned champion model and serves predictions from
 * it. Thin wrapper so callers (the upload pipeline's prediction-logging
 * hook, and commands like {@code /predict}/{@code /lineup}) don't each
 * re-implement "load champion row -> decode -> predict".
 */
public class PredictionService {

    private final E17_MlModels mlModels = new E17_MlModels();

    /**
     * Decoded-champion cache. Decoding parses the (potentially large)
     * params JSON on every load, and the upload's prediction hook plus
     * /predict and /lineup all load the champion per call. The key embeds
     * the database path (tests swap user.dir) and the champion row's
     * model_version - which itself embeds a hash of the parameters (see
     * ModelTrainer.buildVersion) - so a retrain that crowns or refits a
     * champion invalidates naturally on the next load. Predictors are
     * immutable after fitting, so sharing one decoded instance is safe.
     */
    private static volatile CachedChampion cachedChampion;

    private static final class CachedChampion {
        final String key;
        final MatchupPredictor predictor;

        CachedChampion(String key, MatchupPredictor predictor) {
            this.key = key;
            this.predictor = predictor;
        }
    }

    /** The current champion, or null if no training run has ever completed (empty/too-thin database). */
    public MatchupPredictor loadChampion() throws SQLException {
        E17_MlModels.MlModel champion = mlModels.getChampion();
        if (champion == null) {
            return null;
        }
        String key = com.calplus.ihrgstats.utils.DatabaseHelper.getDefaultDatabasePathString()
                + "|" + champion.id + "|" + champion.modelVersion;
        CachedChampion cached = cachedChampion;
        if (cached != null && cached.key.equals(key)) {
            return cached.predictor;
        }
        MatchupPredictor decoded = ModelCodec.decode(champion.family, champion.paramsJson);
        cachedChampion = new CachedChampion(key, decoded);
        return decoded;
    }

    /** The champion's stored model_version, or null if there is no champion yet. */
    public String championVersion() throws SQLException {
        E17_MlModels.MlModel champion = mlModels.getChampion();
        return champion != null ? champion.modelVersion : null;
    }

    /**
     * Every player's most-recently-played Side snapshot, keyed by player_id
     * - one extraction pass shared across a whole roster (the lineup
     * optimizer and opponent model both need this for many players at
     * once). "Most recent" means the state ENTERING that player's last
     * rated round - up to one round short of perfectly live, since it
     * doesn't yet include their own most recent result, but the closest
     * available without a dedicated live-state sweep. A player who has
     * never played simply has no entry - callers fall back to
     * {@link #defaultSide}.
     */
    public Map<String, FeatureExtractor.Side> latestSides() throws SQLException {
        Map<String, FeatureExtractor.Side> latest = new HashMap<>();
        Map<String, Integer> latestSeq = new HashMap<>();
        for (FeatureExtractor.RawBoard rb : new FeatureExtractor().extractAll()) {
            considerLatest(latest, latestSeq, rb.a, rb.roundSeq);
            considerLatest(latest, latestSeq, rb.b, rb.roundSeq);
        }
        return latest;
    }

    private static void considerLatest(Map<String, FeatureExtractor.Side> latest, Map<String, Integer> latestSeq,
                                       FeatureExtractor.Side side, int seq) {
        Integer prev = latestSeq.get(side.playerId);
        if (prev == null || seq > prev) {
            latest.put(side.playerId, side);
            latestSeq.put(side.playerId, seq);
        }
    }

    /** {@code latestSides()}, falling back to a neutral debutant Side for any playerId with no history. */
    public FeatureExtractor.Side latestSideOrDefault(Map<String, FeatureExtractor.Side> latestSides, String playerId) {
        FeatureExtractor.Side side = latestSides.get(playerId);
        return side != null ? side : defaultSide(playerId);
    }

    /**
     * Builds a hypothetical "if these two played right now" board for
     * {@code /predict}. Sides are combined across what may be two
     * DIFFERENT rounds/opponent contexts (each reflects that player's own
     * real history, not a shared as-of moment) - an accepted
     * simplification for an ad-hoc scouting tool, not a training-grade
     * guarantee. matchId/roundId are dummy placeholders (-1); predict()
     * never reads them.
     */
    public FeatureExtractor.RawBoard buildHypotheticalBoard(String playerAId, String playerBId, int fallbackYear) throws SQLException {
        // One extraction serves both the per-player latest sides and the
        // next-round context (this method previously ran extractAll twice).
        List<FeatureExtractor.RawBoard> all = new FeatureExtractor().extractAll();
        Map<String, FeatureExtractor.Side> latest = new HashMap<>();
        Map<String, Integer> latestSeq = new HashMap<>();
        for (FeatureExtractor.RawBoard rb : all) {
            considerLatest(latest, latestSeq, rb.a, rb.roundSeq);
            considerLatest(latest, latestSeq, rb.b, rb.roundSeq);
        }
        FeatureExtractor.Side sideA = latestSideOrDefault(latest, playerAId);
        FeatureExtractor.Side sideB = latestSideOrDefault(latest, playerBId);
        int roundOrder = all.isEmpty() ? 1 : all.get(all.size() - 1).roundOrder + 1;
        int year = all.isEmpty() ? fallbackYear : all.get(all.size() - 1).year;
        return new FeatureExtractor.RawBoard(-1, Integer.MAX_VALUE, -1, year, roundOrder, sideA, sideB, 0.0, false, false, 0.0);
    }

    /** Fits a fresh Glicko baseline (draw rate) from the full stored history - the free "side by side" comparator. */
    public GlickoBaseline fitGlickoBaseline() throws SQLException {
        return GlickoBaseline.fit(new FeatureExtractor().extractAll());
    }

    /** Mirrors FeatureExtractor's own debutant conventions exactly (default rating/RD, neutral covariates). */
    public static FeatureExtractor.Side defaultSide(String playerId) {
        return new FeatureExtractor.Side(playerId, -1, null, FeatureExtractor.DEFAULT_RATING, FeatureExtractor.DEFAULT_RD,
                0, 0, 0.0, 0, FeatureExtractor.DEFAULT_RATING,
                0.0, 0.0, 0.0, 0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0, 0.1, 0.5);
    }
}
