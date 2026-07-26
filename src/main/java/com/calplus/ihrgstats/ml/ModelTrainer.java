package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.E12_PlayerProfiles;
import com.calplus.ihrgstats.databasemanager.E17_MlModels;
import com.calplus.ihrgstats.ml.embed.EmbeddingNet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrates one full training cycle: extract features, walk-forward
 * every candidate, persist every run to {@code ml_models}, and crown the
 * champion. Called after each round upload's whole-history recalculation
 * and by /recalculate (wired in Segment B) - and safe to run standalone.
 *
 * Champion rule: the Glicko baseline is champion unless a model config
 * (a) had at least {@link #minPredictedBoards} walk-forward predictions
 * and (b) achieved a strictly lower walk-forward Brier score. Among
 * qualifying models, lowest Brier wins. "The champion is still plain
 * Glicko" is a legitimate, recorded outcome - the noise-guard tests
 * depend on this rule refusing to crown a model on luck-sized evidence.
 *
 * Fully deterministic: same database -> same versions, same champion.
 */
public class ModelTrainer {

    /** Below this many rated boards there is nothing meaningful to train or score. */
    public static final int MIN_BOARDS_TO_TRAIN = 20;
    /** A model needs at least this many walk-forward predictions to be champion-eligible. */
    public static final int DEFAULT_MIN_PREDICTED_BOARDS = 50;

    private final E17_MlModels mlModels = new E17_MlModels();
    private final int burnInOverride;      // -1 = auto (BacktestHarness.defaultBurnIn)
    private final int minPredictedBoards;

    public ModelTrainer() {
        this(-1, DEFAULT_MIN_PREDICTED_BOARDS);
    }

    /** Test hook: small fixtures need a smaller burn-in and eligibility floor. */
    public ModelTrainer(int burnInOverride, int minPredictedBoards) {
        this.burnInOverride = burnInOverride;
        this.minPredictedBoards = minPredictedBoards;
    }

    /** Summary of one training cycle. */
    public static class TrainOutcome {
        public final boolean trained;
        public final String championVersion; // null when not trained
        public final String championFamily;
        public final int runsPersisted;
        public final String note;

        TrainOutcome(boolean trained, String championVersion, String championFamily, int runsPersisted, String note) {
            this.trained = trained;
            this.championVersion = championVersion;
            this.championFamily = championFamily;
            this.runsPersisted = runsPersisted;
            this.note = note;
        }
    }

    /**
     * One full cycle. Never throws on "not enough data" - that is a
     * normal outcome, reported in the returned note.
     */
    public TrainOutcome retrainAndSelect(String nowTimestamp) throws SQLException {
        List<FeatureExtractor.RawBoard> all = new FeatureExtractor().extractAll();
        if (all.size() < MIN_BOARDS_TO_TRAIN) {
            return new TrainOutcome(false, null, null, 0,
                    String.format(Locale.ROOT, "Only %d rated boards (< %d) - training skipped.",
                            all.size(), MIN_BOARDS_TO_TRAIN));
        }

        int burnIn = burnInOverride >= 0 ? burnInOverride : BacktestHarness.defaultBurnIn(all);
        List<BacktestHarness.ModelFactory> factories = BacktestHarness.allCandidates();
        List<BacktestHarness.Result> results = BacktestHarness.runAll(all, factories, burnIn);

        // No rounds beyond burn-in -> zero walk-forward predictions -> every
        // metric is NaN and a "champion" would be crowned on no evidence.
        // Persisting that would be misleading (and NaN isn't strict JSON);
        // treat it like the too-little-data case instead.
        if (results.get(0).predictedBoards == 0) {
            return new TrainOutcome(false, null, null, 0,
                    String.format(Locale.ROOT,
                            "%d rated boards but no rounds beyond the %d-round burn-in - no walk-forward evidence; training skipped.",
                            all.size(), burnIn));
        }

        int championIdx = pickChampion(results, minPredictedBoards);

        // Persist every run; full-history fit supplies the served parameters.
        int totalRounds = (int) all.stream().mapToInt(rb -> rb.roundSeq).distinct().count();
        mlModels.clearChampionFlags(nowTimestamp);
        String championVersion = null;
        String championFamily = null;
        EmbeddingNet.Params embeddingForExport = null;
        Gson gson = new Gson();
        for (int i = 0; i < results.size(); i++) {
            BacktestHarness.Result result = results.get(i);
            MatchupPredictor fullFit = factories.get(i).fit(all);
            String paramsJson = ModelCodec.encode(fullFit);
            String version = buildVersion(result.family, totalRounds, paramsJson);
            String metricsJson = metricsJson(gson, result, results.get(0));
            boolean isChampion = i == championIdx;
            mlModels.upsertModel(version, result.family, paramsJson, metricsJson, all.size(), isChampion, nowTimestamp);
            if (isChampion) {
                championVersion = version;
                championFamily = result.family;
            }
            // Last GBM_EMB config wins (highest embedding dim, per gbmEmbCandidates order) - a
            // best-effort visibility export into the reserved player_profiles table, independent
            // of whether embeddings actually won the champion gate this cycle.
            if (fullFit instanceof GbmModel gbm && gbm.getEmbedding() != null) {
                embeddingForExport = gbm.getEmbedding();
            }
        }
        if (embeddingForExport != null) {
            exportEmbeddingsToProfiles(embeddingForExport, all, nowTimestamp);
        }

        String note = String.format(Locale.ROOT,
                "Trained on %d boards / %d rounds (burn-in %d). Champion: %s (walk-forward Brier %.5f vs baseline %.5f).",
                all.size(), totalRounds, burnIn, results.get(championIdx).name,
                results.get(championIdx).brier, results.get(0).brier);
        return new TrainOutcome(true, championVersion, championFamily, results.size(), note);
    }

    /**
     * Champion selection - static and side-effect-free so the noise-guard
     * test can exercise it directly. results.get(0) must be the baseline.
     *
     * GBM_EMB gets a stricter bar than every other family: it only
     * qualifies if it ALSO strictly beats the best plain-GBM result, not
     * merely the Glicko baseline. Embeddings add real complexity (a whole
     * trained neural net per candidate) for a feature the plan explicitly
     * says to keep "only if it measurably beats plain GBM" - this is that
     * gate, enforced structurally rather than trusted to the grid search
     * happening to prefer it on Brier alone (a GBM_EMB config could beat
     * the baseline by luck without beating GBM, and the baseline-only
     * comparison would still crown it).
     */
    public static int pickChampion(List<BacktestHarness.Result> results, int minPredictedBoards) {
        double bestGbmBrier = Double.POSITIVE_INFINITY;
        for (BacktestHarness.Result r : results) {
            if (E17_MlModels.FAMILY_GBM.equals(r.family) && !Double.isNaN(r.brier) && r.brier < bestGbmBrier) {
                bestGbmBrier = r.brier;
            }
        }

        int best = 0;
        double baselineBrier = results.get(0).brier;
        double bestBrier = baselineBrier;
        for (int i = 1; i < results.size(); i++) {
            BacktestHarness.Result r = results.get(i);
            if (Double.isNaN(r.brier) || r.predictedBoards < minPredictedBoards) {
                continue;
            }
            if (E17_MlModels.FAMILY_GBM_EMB.equals(r.family) && !(r.brier < bestGbmBrier)) {
                continue;
            }
            if (r.brier < baselineBrier && r.brier < bestBrier) {
                best = i;
                bestBrier = r.brier;
            }
        }
        return best;
    }

    /**
     * Writes the trained player embeddings into the reserved
     * {@code player_profiles.playstyle_vector} slot - the first thing that
     * has ever populated that table. {@code all} is chronological (see
     * {@link FeatureExtractor#extractAll}), so its last board's year is
     * "as of" this training cycle.
     */
    private void exportEmbeddingsToProfiles(EmbeddingNet.Params embedding, List<FeatureExtractor.RawBoard> all,
                                            String nowTimestamp) throws SQLException {
        int year = all.get(all.size() - 1).year;
        Gson gson = new Gson();
        E12_PlayerProfiles profiles = new E12_PlayerProfiles();
        for (Map.Entry<String, double[]> e : embedding.playerEmb.entrySet()) {
            profiles.upsertProfile(e.getKey(), gson.toJson(e.getValue()), year, nowTimestamp);
        }
    }

    /** Deterministic version string: family, data extent, and a hash of the exact parameters. */
    static String buildVersion(String family, int totalRounds, String paramsJson) {
        return String.format(Locale.ROOT, "%s.r%d.%s",
                family.toLowerCase(Locale.ROOT), totalRounds, sha8(paramsJson));
    }

    private static String metricsJson(Gson gson, BacktestHarness.Result result, BacktestHarness.Result baseline) {
        JsonObject o = new JsonObject();
        o.addProperty("name", result.name);
        o.addProperty("family", result.family);
        o.addProperty("predictedBoards", result.predictedBoards);
        o.addProperty("predictedRounds", result.predictedRounds);
        o.addProperty("brier", result.brier);
        o.addProperty("logLoss", result.logLoss);
        o.addProperty("expectedScoreMse", result.expectedScoreMse);
        o.addProperty("accuracy", result.accuracy);
        o.addProperty("baselineBrier", baseline.brier);
        o.addProperty("brierDeltaVsBaseline", result.brier - baseline.brier);
        o.addProperty("roundsBetterThanBaseline", result.roundsBetterThanBaseline);
        o.addProperty("roundsWorseThanBaseline", result.roundsWorseThanBaseline);
        o.add("calibration", gson.toJsonTree(result.calibration));
        return gson.toJson(o);
    }

    private static String sha8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
