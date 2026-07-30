package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.ml.*;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.utils.SelectionKeyboards;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.SelectionState;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command handler for /predict (admin-only): a hall -> player -> hall ->
 * player wizard, mirroring {@link CommandComparePlayers}, that ends in a
 * side-by-side model-vs-Glicko-baseline forecast for a hypothetical
 * matchup between the two chosen players' CURRENT states. Every step is
 * independently admin-gated (matching {@link CommandMatchTypes}'s
 * fail-closed-per-step pattern), not just the entry point.
 */
public class CommandPredict {

    private final LogHelper logHelper = new LogHelper();
    private final F16_Admins admins = new F16_Admins();
    private final A3_Halls halls = new A3_Halls();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final PredictionService predictionService = new PredictionService();

    private static final Map<String, PredictSelectionState> userSelectionStates = new ConcurrentHashMap<>();

    /** Anti-feature labels, in the EXACT order of {@link FeatureExtractor#assemble}'s anti[] array. */
    private static final String[] ANTI_LABELS = {
            "Rating edge (Glicko-scaled)", "Rating edge", "Seat difference", "Experience (career boards)",
            "Timeout rate", "Recent form", "Rating edge (RD-damped)", "Rating trajectory",
            "Hall-relative rating bias", "Season boards", "Opponent quality faced (SOS)", "Graph insularity",
            "Rounds missed this season", "Seat trend", "Margin form", "Blowout rate",
            "Walkovers received", "Forced-timeout rate", "Record vs opponent's hall", "Rating stability",
    };

    private static class PredictSelectionState extends SelectionState {
        int firstHallId;
        String firstHallName;
        String firstPlayerId;
        String firstPlayerName;
        int secondHallId;
        String secondHallName;
    }

    /** Fails closed (denies) on a database error rather than risking a false "admin". */
    public boolean isAdmin(String userId) {
        try {
            return admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, userId);
        } catch (SQLException e) {
            logHelper.logError("Database error checking admin status: " + e.getMessage());
            return false;
        }
    }

    private CommandResponse accessDenied(String userId) {
        userSelectionStates.remove(userId);
        logHelper.logWarning(String.format("Non-admin %s attempted to use /predict", TelegramListener.formatUserInfo(userId)));
        return new CommandResponse("❌ Access Denied: Only administrators can use /predict.", (java.nio.file.Path) null, null);
    }

    public CommandResponse handleCommand(String userId) {
        if (!isAdmin(userId)) {
            return accessDenied(userId);
        }
        logHelper.logInfo(String.format("%s started /predict", TelegramListener.formatUserInfo(userId)));
        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.put(userId, new PredictSelectionState());

        try {
            List<A3_Halls.Hall> allHalls = halls.getAllHalls();
            return new CommandResponse("🔮 <b>Predict Matchup</b>\n\nSelect the <b>first player's hall</b>:",
                    SelectionKeyboards.hallButtons(allHalls, "predict_selecthall1_", "predict_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error fetching halls: " + e.getMessage());
            return new CommandResponse("❌ Database error fetching halls.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleFirstHallSelection(String userId, int hallId) {
        if (!isAdmin(userId)) {
            return accessDenied(userId);
        }
        PredictSelectionState state = userSelectionStates.getOrDefault(userId, new PredictSelectionState());
        state.firstHallId = hallId;
        userSelectionStates.put(userId, state);

        try {
            A3_Halls.Hall hall = halls.getHallById(hallId);
            state.firstHallName = hall != null ? hall.hallName : "?";

            List<B6_PlayerYearStatus.Status> statuses = statusesForHall(hallId);
            if (statuses.isEmpty()) {
                userSelectionStates.remove(userId);
                return new CommandResponse("ℹ️ No players found in hall " + VictoryRecordCalculator.formatHallName(state.firstHallName) + ".", (java.nio.file.Path) null, null);
            }

            String message = String.format("🔮 <b>Predict Matchup</b>\n\nFirst player's hall: <b>%s</b>\nSelect the <b>first player</b>:",
                    VictoryRecordCalculator.formatHallName(state.firstHallName));
            return new CommandResponse(message, SelectionKeyboards.playerButtons(statuses, this::nameFor, "predict_selectplayer1_", "predict_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new CommandResponse("❌ Database error.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleFirstPlayerSelection(String userId, String playerId) {
        if (!isAdmin(userId)) {
            return accessDenied(userId);
        }
        PredictSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstHallName == null) {
            return new CommandResponse("❌ Session expired. Please use /predict to start again.", (java.nio.file.Path) null, null);
        }
        state.firstPlayerId = playerId;
        state.firstPlayerName = nameFor(playerId);

        try {
            List<A3_Halls.Hall> allHalls = halls.getAllHalls();
            String message = String.format("🔮 <b>Predict Matchup</b>\n\nFirst player: <b>%s</b> (%s)\nSelect the <b>second player's hall</b>:",
                    TelegramHtml.escape(state.firstPlayerName), VictoryRecordCalculator.formatHallName(state.firstHallName));
            return new CommandResponse(message, SelectionKeyboards.hallButtons(allHalls, "predict_selecthall2_", "predict_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new CommandResponse("❌ Database error.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleSecondHallSelection(String userId, int hallId) {
        if (!isAdmin(userId)) {
            return accessDenied(userId);
        }
        PredictSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstPlayerId == null) {
            return new CommandResponse("❌ Session expired. Please use /predict to start again.", (java.nio.file.Path) null, null);
        }
        state.secondHallId = hallId;

        try {
            A3_Halls.Hall hall = halls.getHallById(hallId);
            state.secondHallName = hall != null ? hall.hallName : "?";

            List<B6_PlayerYearStatus.Status> statuses = statusesForHall(hallId);
            statuses.removeIf(s -> s.playerId.equals(state.firstPlayerId));
            if (statuses.isEmpty()) {
                userSelectionStates.remove(userId);
                return new CommandResponse("ℹ️ No other players available in hall " + VictoryRecordCalculator.formatHallName(state.secondHallName) + ".", (java.nio.file.Path) null, null);
            }

            String message = String.format("🔮 <b>Predict Matchup</b>\n\nFirst player: <b>%s</b> (%s)\nSecond player's hall: <b>%s</b>\nSelect the <b>second player</b>:",
                    TelegramHtml.escape(state.firstPlayerName), VictoryRecordCalculator.formatHallName(state.firstHallName), VictoryRecordCalculator.formatHallName(state.secondHallName));
            return new CommandResponse(message, SelectionKeyboards.playerButtons(statuses, this::nameFor, "predict_selectplayer2_", "predict_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error: " + e.getMessage());
            return new CommandResponse("❌ Database error.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleSecondPlayerSelection(String userId, String playerId) {
        if (!isAdmin(userId)) {
            return accessDenied(userId);
        }
        PredictSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.firstPlayerId == null || state.secondHallName == null) {
            return new CommandResponse("❌ Session expired. Please use /predict to start again.", (java.nio.file.Path) null, null);
        }
        userSelectionStates.remove(userId);
        String secondPlayerName = nameFor(playerId);

        try {
            String message = generatePrediction(state.firstPlayerId, state.firstPlayerName, state.firstHallName,
                    playerId, secondPlayerName, state.secondHallName);
            logHelper.logSuccess(String.format("%s: /predict %s vs %s", TelegramListener.formatUserInfo(userId), state.firstPlayerName, secondPlayerName));
            return new CommandResponse(message, (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            logHelper.logError("Database error generating prediction: " + e.getMessage());
            return new CommandResponse("❌ Database error generating prediction: " + e.getMessage(), (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new CommandResponse("ℹ️ Prediction cancelled.", (java.nio.file.Path) null, null);
    }

    // ========================================================================
    // Prediction generation
    // ========================================================================

    private String generatePrediction(String player1Id, String player1Name, String player1Hall,
                                      String player2Id, String player2Name, String player2Hall) throws SQLException {
        Integer year = YearContext.getCurrentYear();
        FeatureExtractor.RawBoard board = predictionService.buildHypotheticalBoard(player1Id, player2Id, year != null ? year : 0);
        MatchupPredictor champion = predictionService.loadChampion();
        GlickoBaseline baseline = predictionService.fitGlickoBaseline();
        MatchupPredictor.Probs baselineProbs = baseline.predict(board);

        StringBuilder sb = new StringBuilder();
        sb.append("🔮 <b>Predicted Matchup</b>\n\n");
        sb.append(String.format("<b>%s</b> (%s) vs <b>%s</b> (%s)\n\n",
                TelegramHtml.escape(player1Name), VictoryRecordCalculator.formatHallName(player1Hall),
                TelegramHtml.escape(player2Name), VictoryRecordCalculator.formatHallName(player2Hall)));

        if (champion != null) {
            MatchupPredictor.Probs modelProbs = champion.predict(board);
            sb.append(String.format("<b>Model</b> (%s):%n", champion.family()));
            sb.append(String.format(Locale.ROOT, "Win %.1f%% / Draw %.1f%% / Loss %.1f%%%n%n",
                    modelProbs.pWin * 100, modelProbs.pDraw * 100, modelProbs.pLoss * 100));
        } else {
            sb.append("<b>Model:</b> no trained model yet - showing the Glicko baseline only.\n\n");
        }

        sb.append("<b>Glicko baseline</b> (side by side):\n");
        sb.append(String.format(Locale.ROOT, "Win %.1f%% / Draw %.1f%% / Loss %.1f%% (expected score %.3f)%n%n",
                baselineProbs.pWin * 100, baselineProbs.pDraw * 100, baselineProbs.pLoss * 100, baselineProbs.expectedScore()));

        if (champion != null) {
            sb.append(topFeatureContributions(board, n0For(champion), player1Name, player2Name));
        }

        sb.append("<b>Reliability:</b>\n");
        sb.append(reliabilityNote(board.a, player1Name));
        sb.append(reliabilityNote(board.b, player2Name));

        sb.append("\n<i>Each side's features come from that player's own most recently played round - up to one round short of perfectly live.</i>");
        return sb.toString();
    }

    /** Top-3 anti[] features by magnitude - a direct, honest view of what's driving the model's number. */
    private String topFeatureContributions(FeatureExtractor.RawBoard board, double n0, String name1, String name2) {
        FeatureExtractor.Vectors v = FeatureExtractor.assemble(board, n0);
        Integer[] order = new Integer[v.anti.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(Math.abs(v.anti[b]), Math.abs(v.anti[a])));

        StringBuilder sb = new StringBuilder("<b>Top factors:</b>\n");
        for (int rank = 0; rank < 3 && rank < order.length; rank++) {
            int i = order[rank];
            if (v.anti[i] == 0.0) continue;
            String favors = v.anti[i] > 0 ? name1 : name2;
            sb.append(String.format(Locale.ROOT, "%d. %s - favors %s (%.2f)%n", rank + 1, ANTI_LABELS[i], TelegramHtml.escape(favors), Math.abs(v.anti[i])));
        }
        sb.append("\n");
        return sb.toString();
    }

    private String reliabilityNote(FeatureExtractor.Side side, String name) {
        List<String> flags = new ArrayList<>();
        if (side.rd > 200) flags.add("high uncertainty (RD " + Math.round(side.rd) + ")");
        if (side.careerBoards < 5) flags.add("very little history (" + side.careerBoards + " boards)");
        if (side.graphInsularity > 0.6) flags.add("mostly faced one hall");
        String flagsStr = flags.isEmpty() ? "" : " ⚠ " + String.join(", ", flags);
        return String.format("%s: RD %.0f, %d career boards%s%n", TelegramHtml.escape(name), side.rd, side.careerBoards, flagsStr);
    }

    private static double n0For(MatchupPredictor model) {
        if (model instanceof LogisticModel logistic) return logistic.getParams().n0;
        if (model instanceof GbmModel gbm) return gbm.getParams().n0;
        return 6.0;
    }

    private List<B6_PlayerYearStatus.Status> statusesForHall(int hallId) throws SQLException {
        Integer year = YearContext.getCurrentYear();
        return year != null ? playerYearStatus.getStatusesForHallAndYear(hallId, year) : List.of();
    }

    private String nameFor(String playerId) {
        try {
            Integer year = YearContext.getCurrentYear();
            String name = year != null ? playerNames.getNameForYear(playerId, year) : null;
            return name != null ? name : playerId;
        } catch (SQLException e) {
            return playerId;
        }
    }
}
