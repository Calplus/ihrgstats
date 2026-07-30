package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.ml.*;
import com.calplus.ihrgstats.ml.lineup.LineupExplainer;
import com.calplus.ihrgstats.ml.lineup.LineupOptimizer;
import com.calplus.ihrgstats.ml.lineup.OpponentModel;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.utils.SelectionKeyboards;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TableFormatter.Alignment;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;

import java.sql.SQLException;
import java.util.*;

/**
 * Command handler for /lineup (admin-only): the app's actual stated
 * purpose. Picks an opponent hall, then runs the full exact optimizer
 * (best-response, maximin, strategy archetypes, per-board pairing table,
 * reliability flags, deterministic explanation) against our own home
 * hall's currently active roster.
 *
 * v1 scope, deliberately trimmed and documented (not silently dropped):
 * uses the FULL active roster of our home hall as "available" - no
 * manual availability ticking, seat-locking, or opponent-roster
 * adjustment UI yet (those are genuine captain-in-the-loop refinements
 * the plan calls out, left for a follow-up checkpoint). The optimizer
 * and opponent model underneath are the full, real, rigorously-tested
 * engine - only the wizard is simplified.
 */
public class CommandLineup {

    private final LogHelper logHelper = new LogHelper();
    private final F16_Admins admins = new F16_Admins();
    private final A3_Halls halls = new A3_Halls();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final PredictionService predictionService = new PredictionService();
    private final OpponentModel opponentModel = new OpponentModel();
    private final LineupOptimizer optimizer = new LineupOptimizer();

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
        logHelper.logWarning(String.format("Non-admin %s attempted to use /lineup", TelegramListener.formatUserInfo(userId)));
        return new CommandResponse("❌ Access Denied: Only administrators can use /lineup.", (java.nio.file.Path) null, null);
    }

    public CommandResponse handleCommand(String userId) {
        if (!isAdmin(userId)) {
            return accessDenied(userId);
        }
        logHelper.logInfo(String.format("%s started /lineup", TelegramListener.formatUserInfo(userId)));

        try {
            String homeHallName = PropertyResolver.getProperty("settings.homeHall", "");
            A3_Halls.Hall ourHall = homeHallName.isEmpty() ? null : halls.getHallByName(homeHallName);

            List<A3_Halls.Hall> allHalls = halls.getAllHalls();
            allHalls.removeIf(hall -> ourHall != null && hall.id == ourHall.id); // can't play yourself

            return new CommandResponse("🎯 <b>Lineup Optimizer</b>\n\nSelect the <b>opponent hall</b>:",
                    SelectionKeyboards.hallButtons(allHalls, "lineup_selectopponent_", "lineup_cancel"));
        } catch (SQLException e) {
            logHelper.logError("Database error fetching halls: " + e.getMessage());
            return new CommandResponse("❌ Database error fetching halls.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleCancel(String userId) {
        return new CommandResponse("ℹ️ Lineup computation cancelled.", (java.nio.file.Path) null, null);
    }

    public CommandResponse handleOpponentHallSelection(String userId, int opponentHallId) {
        if (!isAdmin(userId)) {
            return accessDenied(userId);
        }
        try {
            String message = computeAndFormat(opponentHallId);
            logHelper.logSuccess(String.format("%s: /lineup vs hall %d", TelegramListener.formatUserInfo(userId), opponentHallId));
            return new CommandResponse(message, (java.nio.file.Path) null, null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new CommandResponse("🟡 " + e.getMessage(), (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            logHelper.logError("Database error computing /lineup: " + e.getMessage());
            return new CommandResponse("❌ Database error computing lineup: " + e.getMessage(), (java.nio.file.Path) null, null);
        }
    }

    // ========================================================================
    // Computation + formatting
    // ========================================================================

    private String computeAndFormat(int opponentHallId) throws SQLException {
        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            throw new IllegalStateException("No current year set. An admin must set settings.currentYear first.");
        }

        String homeHallName = PropertyResolver.getProperty("settings.homeHall", "");
        if (homeHallName.isEmpty()) {
            throw new IllegalStateException("No home hall configured. An admin must set settings.homeHall via /settings first.");
        }
        A3_Halls.Hall ourHall = halls.getHallByName(homeHallName);
        if (ourHall == null) {
            throw new IllegalStateException("Configured home hall '" + homeHallName + "' was not found.");
        }
        A3_Halls.Hall opponentHall = halls.getHallById(opponentHallId);
        if (opponentHall == null) {
            throw new IllegalStateException("Opponent hall not found.");
        }
        if (opponentHall.id == ourHall.id) {
            throw new IllegalArgumentException("Opponent hall must be different from your home hall (" + ourHall.hallName + ").");
        }

        List<String> ourRoster = playerYearStatus.getStatusesForHallAndYear(ourHall.id, year).stream()
                .filter(s -> s.active)
                .map(s -> s.playerId)
                .toList();
        if (ourRoster.size() < LineupOptimizer.LINEUP_SIZE) {
            throw new IllegalArgumentException("Your home hall (" + ourHall.hallName + ") only has " + ourRoster.size()
                    + " active player(s) for " + year + " - need at least " + LineupOptimizer.LINEUP_SIZE + ".");
        }

        OpponentModel.Profile profile = opponentModel.buildProfile(opponentHallId);
        if (!profile.hasHistory()) {
            throw new IllegalArgumentException("No recorded seating history for " + opponentHall.hallName + " yet - can't model their captain.");
        }

        MatchupPredictor champion = predictionService.loadChampion();
        if (champion == null) {
            throw new IllegalStateException("No AI model has been trained yet. Upload more rounds (or run /recalculate) first.");
        }
        GlickoBaseline baseline = predictionService.fitGlickoBaseline();

        LineupOptimizer.Result result = optimizer.optimize(ourRoster, profile, champion, baseline, year);

        return format(result, profile, ourHall, opponentHall, year);
    }

    private String format(LineupOptimizer.Result result, OpponentModel.Profile profile,
                          A3_Halls.Hall ourHall, A3_Halls.Hall opponentHall, int year) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎯 <b>Lineup vs ").append(TelegramHtml.escape(opponentHall.hallName)).append("</b>\n\n");
        sb.append("Predictor: <b>").append(result.predictorFamily).append("</b>");
        if (result.rosterPruned) {
            sb.append(" | Roster pruned to top ").append(result.ourNominal5.size() >= 5 ? 12 : result.ourNominal5.size())
              .append(" by rating (").append(result.candidatesConsidered).append(" lineups considered)");
        } else {
            sb.append(" | ").append(result.candidatesConsidered).append(" lineups considered");
        }
        sb.append("\n\n");

        sb.append("<b>Opponent captain profile</b> (").append(TelegramHtml.escape(opponentHall.hallName)).append("):\n");
        sb.append("Expected roster: ").append(names(profile.expectedRoster, year)).append("\n");
        sb.append(String.format(Locale.ROOT, "Seating consistency: %s (entropy %.2f bits)",
                profile.captainProfile.consistencyLabel(), profile.captainProfile.meanSeatEntropyBits));
        if (profile.captainProfile.reactivity != null) {
            sb.append(String.format(Locale.ROOT, "; reorders %.0f%% of rematches (%d observed)",
                    profile.captainProfile.reactivity * 100, profile.captainProfile.rematchesObserved));
        } else {
            sb.append("; not enough rematches to measure reactivity");
        }
        sb.append("\n\n");

        appendLineup(sb, "🏆 Best response", result.bestResponse.playerIdsBySeat, result.bestResponse.expectedResult, year);
        sb.append(String.format(Locale.ROOT, "(worst case vs their plausible orders: %.0f%% win)%n%n", result.bestResponse.worstCaseResult.pWin * 100));

        appendLineup(sb, "🛡 Maximin (safe)", result.maximin.playerIdsBySeat, result.maximin.expectedResult, year);
        sb.append(String.format(Locale.ROOT, "(worst case: %.0f%% win)%n%n", result.maximin.worstCaseResult.pWin * 100));

        appendArchetypeTable(sb, result);
        appendPairingTable(sb, result, profile, year);
        appendReliability(sb, result, year);

        sb.append("\n<b>Why this lineup:</b>\n");
        sb.append(LineupExplainer.explain(result, id -> nameFor(id, year)));

        return sb.toString();
    }

    private void appendLineup(StringBuilder sb, String label, List<String> seats, LineupOptimizer.TeamResult r, int year) {
        sb.append("<b>").append(label).append(":</b>\n");
        for (int i = 0; i < seats.size(); i++) {
            sb.append("Board ").append(i + 1).append(": ").append(TelegramHtml.escape(nameFor(seats.get(i), year))).append("\n");
        }
        sb.append(String.format(Locale.ROOT, "Expected: Win %.0f%% / Tie %.0f%% / Loss %.0f%%%n", r.pWin * 100, r.pTie * 100, r.pLoss * 100));
    }

    private void appendArchetypeTable(StringBuilder sb, LineupOptimizer.Result result) {
        sb.append("<b>Strategy archetypes</b>:\n");
        List<String[]> rows = new ArrayList<>();
        for (LineupOptimizer.ArchetypeResult a : result.archetypes) {
            rows.add(new String[]{a.name, String.format(Locale.ROOT, "%.1f%%", a.expectedResult.pWin * 100)});
        }
        sb.append(TableFormatter.formatTable(new String[]{"Strategy", "P(win)"}, rows,
                new Alignment[]{Alignment.LEFT, Alignment.RIGHT}, new int[]{28, 8}));
        sb.append("\n");
    }

    private void appendPairingTable(StringBuilder sb, LineupOptimizer.Result result, OpponentModel.Profile profile, int year) {
        if (result.bestResponsePairingVsTopOpponentOrder.isEmpty() || profile.topOrderings.isEmpty()) {
            return;
        }
        sb.append("<b>Per-board pairing</b> (best response vs their #1 predicted order, model / Glicko):\n");
        List<String> theirTop = profile.topOrderings.get(0).playerIdsBySeat;
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < result.bestResponsePairingVsTopOpponentOrder.size(); i++) {
            LineupOptimizer.PairingProbs p = result.bestResponsePairingVsTopOpponentOrder.get(i);
            rows.add(new String[]{
                    "B" + (i + 1),
                    shorten(nameFor(result.bestResponse.playerIdsBySeat.get(i), year)),
                    shorten(nameFor(theirTop.get(i), year)),
                    String.format(Locale.ROOT, "%.0f%%", p.model.pWin * 100),
                    String.format(Locale.ROOT, "%.0f%%", p.glicko.expectedScore() * 100),
            });
        }
        sb.append(TableFormatter.formatTable(new String[]{"Bd", "Us", "Them", "Model", "Glicko"}, rows,
                new Alignment[]{Alignment.LEFT, Alignment.LEFT, Alignment.LEFT, Alignment.RIGHT, Alignment.RIGHT},
                new int[]{3, 12, 12, 6, 6}));
        sb.append("\n");
    }

    private void appendReliability(StringBuilder sb, LineupOptimizer.Result result, int year) {
        sb.append("<b>Reliability</b> (best response lineup):\n");
        for (String playerId : result.bestResponse.playerIdsBySeat) {
            ReliabilityScore rs = result.reliability.get(playerId);
            if (rs == null) continue;
            sb.append(TelegramHtml.escape(nameFor(playerId, year))).append(": ").append(rs.tier())
              .append(" (").append(rs.score).append("/100)\n");
        }
    }

    private static String shorten(String name) {
        return TableFormatter.shortenPlayerName(name, 12);
    }

    private List<String> namesList(List<String> ids, int year) {
        List<String> out = new ArrayList<>();
        for (String id : ids) out.add(nameFor(id, year));
        return out;
    }

    private String names(List<String> ids, int year) {
        return String.join(", ", namesList(ids, year).stream().map(TelegramHtml::escape).toList());
    }

    private String nameFor(String playerId, int year) {
        try {
            String name = playerNames.getNameForYear(playerId, year);
            return name != null ? name : playerId;
        } catch (SQLException e) {
            return playerId;
        }
    }
}
