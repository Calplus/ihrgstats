package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.E17_MlModels;

import java.sql.SQLException;

/**
 * Loads the currently-crowned champion model and serves predictions from
 * it. Thin wrapper so callers (the upload pipeline's prediction-logging
 * hook, and future commands like {@code /predict}) don't each re-implement
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
}
