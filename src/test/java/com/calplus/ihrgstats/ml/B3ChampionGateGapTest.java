package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.E17_MlModels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lane b3 gap test for mutant M16: a candidate with too few walk-forward
 * predictions is never champion-eligible, however good its Brier score
 * (a lucky score on a handful of boards is not evidence). Same-package so it
 * can build BacktestHarness.Result directly, like GbmEmbModelTest does.
 */
public class B3ChampionGateGapTest {

    private static BacktestHarness.Result result(String name, String family, int predictedBoards, double brier) {
        return new BacktestHarness.Result(name, family, predictedBoards, 5, brier, 1.0, 0.1, 0.6, new double[10][3], Map.of());
    }

    @Test
    void candidateBelowMinPredictedBoards_isNeverChampion() {
        List<BacktestHarness.Result> results = List.of(
                result("glicko-baseline", E17_MlModels.FAMILY_GLICKO_BASELINE, 100, 0.50),
                result("gbm thin", E17_MlModels.FAMILY_GBM, 49, 0.10),   // far better, but only 49 predictions
                result("logistic", E17_MlModels.FAMILY_LOGISTIC, 100, 0.45));
        assertEquals(2, ModelTrainer.pickChampion(results, 50),
                "the 49-board candidate must be ineligible; the eligible logistic beats the baseline");
        assertEquals(0, ModelTrainer.pickChampion(results.subList(0, 2), 50),
                "with only the thin candidate the baseline stays champion");
    }
}
