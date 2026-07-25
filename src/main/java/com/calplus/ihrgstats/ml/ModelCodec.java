package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.E17_MlModels;

/**
 * Single place that maps an {@code ml_models} row (family + params_json)
 * back to a live {@link MatchupPredictor}, and predictors to their JSON.
 * New families added in later segments (GBM, GBM_EMB) register here.
 */
public final class ModelCodec {

    private ModelCodec() {
    }

    public static String encode(MatchupPredictor predictor) {
        if (predictor instanceof GlickoBaseline baseline) {
            return baseline.toParamsJson();
        }
        if (predictor instanceof LogisticModel logistic) {
            return logistic.toParamsJson();
        }
        throw new IllegalArgumentException("No codec for predictor family: " + predictor.family());
    }

    public static MatchupPredictor decode(String family, String paramsJson) {
        switch (family) {
            case E17_MlModels.FAMILY_GLICKO_BASELINE:
                return GlickoBaseline.fromParamsJson(paramsJson);
            case E17_MlModels.FAMILY_LOGISTIC:
                return LogisticModel.fromParamsJson(paramsJson);
            default:
                throw new IllegalArgumentException("Unknown ml_models family: " + family);
        }
    }
}
