package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.E14_AiPredictions;
import com.calplus.ihrgstats.databasemanager.E17_MlModels;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.ml.FeatureExtractor;
import com.calplus.ihrgstats.ml.MatchupPredictor;
import com.calplus.ihrgstats.ml.ModelCodec;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.LogHelper;
import com.calplus.ihrgstats.utils.TableFormatter;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.sql.SQLException;
import java.util.*;

/**
 * Command handler for /modelstats (admin-only): the AI model trust
 * dashboard - current champion, a leaderboard comparing every trained
 * family against the Glicko baseline, and a live scorecard measuring the
 * champion's actual pre-round predictions against what really happened.
 * Read-only, no wizard.
 */
public class CommandModelStats {

    private final LogHelper logHelper = new LogHelper();
    private final F16_Admins admins = new F16_Admins();
    private final E17_MlModels mlModels = new E17_MlModels();
    private final E14_AiPredictions predictions = new E14_AiPredictions();
    private final Gson gson = new Gson();

    /** Fails closed (denies) on a database error rather than risking a false "admin". */
    public boolean isAdmin(String userId) {
        try {
            return admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, userId);
        } catch (SQLException e) {
            logHelper.logError("Database error checking admin status: " + e.getMessage());
            return false;
        }
    }

    public CommandResponse handleCommand(String userId) {
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested /modelstats", userInfo));

        if (!isAdmin(userId)) {
            logHelper.logWarning(String.format("Non-admin %s attempted to use /modelstats", userInfo));
            return new CommandResponse("❌ Access Denied: Only administrators can view AI model statistics.", (java.nio.file.Path) null, null);
        }

        try {
            List<E17_MlModels.MlModel> recent = mlModels.getRecent(200);
            if (recent.isEmpty()) {
                return new CommandResponse("🟡 No AI models have been trained yet. Upload enough rounds (or run /recalculate) to trigger training.",
                        (java.nio.file.Path) null, null);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🤖 <b>AI Model Stats</b>\n\n");

            E17_MlModels.MlModel champion = mlModels.getChampion();
            appendChampionSummary(sb, champion);
            appendLeaderboard(sb, recent);
            appendLiveScorecard(sb);

            sb.append("\n<i>Honest limits: ~12 boards/player/year is a hard ceiling on individual rating precision - ")
              .append("the model targets better priors and calibrated uncertainty, not oracle ratings. ")
              .append("A model only replaces the Glicko baseline in the leaderboard above if it measurably beat it in walk-forward backtesting.</i>");

            logHelper.logSuccess(String.format("%s viewed /modelstats", userInfo));
            return new CommandResponse(sb.toString(), (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            logHelper.logError("Database error generating /modelstats: " + e.getMessage());
            return new CommandResponse("❌ Database error generating model stats.", (java.nio.file.Path) null, null);
        }
    }

    private void appendChampionSummary(StringBuilder sb, E17_MlModels.MlModel champion) {
        if (champion == null) {
            sb.append("<b>Champion:</b> none crowned yet\n\n");
            return;
        }
        JsonObject metrics = gson.fromJson(champion.metricsJson, JsonObject.class);
        sb.append("<b>Champion:</b> ").append(champion.family).append(" (<code>").append(champion.modelVersion).append("</code>)\n");
        sb.append(String.format("Trained on %d boards. Walk-forward Brier %.5f (baseline %.5f, delta %+.5f).%n%n",
                champion.trainedBoards, getDouble(metrics, "brier"), getDouble(metrics, "baselineBrier"), getDouble(metrics, "brierDeltaVsBaseline")));
    }

    /** One row per family - the most recent run of each (recent is already newest-first). */
    private void appendLeaderboard(StringBuilder sb, List<E17_MlModels.MlModel> recent) {
        Map<String, E17_MlModels.MlModel> latestByFamily = new LinkedHashMap<>();
        for (E17_MlModels.MlModel m : recent) {
            latestByFamily.putIfAbsent(m.family, m);
        }

        List<String[]> rows = new ArrayList<>();
        for (E17_MlModels.MlModel m : latestByFamily.values()) {
            JsonObject metrics = gson.fromJson(m.metricsJson, JsonObject.class);
            rows.add(new String[]{
                    m.family + (m.isChampion ? " *" : ""),
                    String.format(Locale.ROOT, "%.5f", getDouble(metrics, "brier")),
                    String.format(Locale.ROOT, "%.5f", getDouble(metrics, "logLoss")),
                    String.format(Locale.ROOT, "%+.5f", getDouble(metrics, "brierDeltaVsBaseline")),
            });
        }
        sb.append("<b>Leaderboard</b> (best run per family, * = champion):\n");
        sb.append(TableFormatter.formatTable(
                new String[]{"Family", "Brier", "LogLoss", "vsBase"},
                rows,
                new TableFormatter.Alignment[]{TableFormatter.Alignment.LEFT, TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.RIGHT},
                new int[]{16, 9, 9, 9}));
        sb.append("\n");
    }

    /**
     * Scores every logged pre-round prediction against what actually
     * happened: predicted-winner hit rate, and the mean probability each
     * board's ORIGINAL model (decoded from its stored model_version, not
     * necessarily today's champion) assigned to the outcome that actually
     * occurred - a live, honest calibration check, not a backtest.
     */
    private void appendLiveScorecard(StringBuilder sb) throws SQLException {
        List<E14_AiPredictions.Prediction> logged = predictions.getAllPredictions();
        if (logged.isEmpty()) {
            sb.append("<b>Live scorecard:</b> no predictions logged yet.\n");
            return;
        }

        Map<Integer, FeatureExtractor.RawBoard> boardsByMatchId = new HashMap<>();
        for (FeatureExtractor.RawBoard rb : new FeatureExtractor().extractAll()) {
            boardsByMatchId.put(rb.matchId, rb);
        }

        Map<String, MatchupPredictor> modelCache = new HashMap<>();
        int scored = 0;
        int winnerHits = 0;
        int decisiveCount = 0; // excludes boards the model called as a draw (no winner to compare)
        double sumRealizedP = 0.0;
        int realizedPCount = 0;

        for (E14_AiPredictions.Prediction p : logged) {
            FeatureExtractor.RawBoard rb = boardsByMatchId.get(p.matchId);
            if (rb == null) {
                continue; // stale/reprocessed match - FK cascade should prevent this, but be defensive
            }
            scored++;

            String actualWinner = rb.outcomeA == 1.0 ? rb.a.playerId : (rb.outcomeA == 0.0 ? rb.b.playerId : null);
            if (p.predictedWinnerPlayerId != null) {
                decisiveCount++;
                if (Objects.equals(p.predictedWinnerPlayerId, actualWinner)) {
                    winnerHits++;
                }
            } else if (actualWinner == null) {
                winnerHits++; // predicted a draw and it was a draw
                decisiveCount++;
            }

            MatchupPredictor model = modelCache.computeIfAbsent(p.modelVersion, version -> {
                try {
                    E17_MlModels.MlModel row = mlModels.getByVersion(version);
                    return row != null ? ModelCodec.decode(row.family, row.paramsJson) : null;
                } catch (SQLException e) {
                    return null;
                }
            });
            if (model != null) {
                MatchupPredictor.Probs probs = model.predict(rb);
                double pRealized = rb.outcomeA == 1.0 ? probs.pWin : (rb.outcomeA == 0.0 ? probs.pLoss : probs.pDraw);
                sumRealizedP += pRealized;
                realizedPCount++;
            }
        }

        sb.append(String.format(Locale.ROOT, "<b>Live scorecard</b> (%d boards scored):%n", scored));
        sb.append(String.format(Locale.ROOT, "Predicted-outcome hit rate: %d/%d (%.1f%%)%n", winnerHits, decisiveCount,
                decisiveCount > 0 ? 100.0 * winnerHits / decisiveCount : 0.0));
        if (realizedPCount > 0) {
            sb.append(String.format(Locale.ROOT, "Mean probability assigned to what actually happened: %.3f%n", sumRealizedP / realizedPCount));
        }
    }

    private static double getDouble(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : Double.NaN;
    }
}
