package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.calculations.RatingRecalculator;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.LogHelper;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;

import java.sql.SQLException;

/**
 * Command handler for the /recalculate admin command.
 *
 * Runs the whole-history (WHR-style) rating recalculation on demand: every
 * stored round across every year is replayed through the Glicko-2 engine in
 * {@link RatingRecalculator#PASSES} passes and {@code player_ratings} is
 * rewritten. The point-in-time {@code player_ratings_snapshot} records are
 * never touched. The same recalculation also runs automatically after every
 * round-CSV upload - this command exists to refresh stored ratings without
 * re-uploading anything (e.g. after a rating-engine fix, or after a failed
 * automatic recalculation).
 *
 * The confirmation button flow is orchestrated by TelegramListener (which
 * owns the pending-confirmation futures); this class supplies the messages
 * and performs the actual recalculation.
 */
public class CommandRecalculate {
    private final LogHelper logHelper;
    private final F16_Admins admins = new F16_Admins();

    public CommandRecalculate() {
        this.logHelper = new LogHelper();
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

    /** Confirmation prompt shown with Start/Cancel buttons. Plain text (button messages skip parse modes). */
    public String buildConfirmationMessage() {
        return "⚠️ Whole-History Rating Recalculation\n\n" +
               "This will replay EVERY stored round across EVERY year through the rating engine (" +
               RatingRecalculator.PASSES + " passes) and rewrite all current ratings.\n\n" +
               "- Current ratings (player_ratings) are recalculated for all rounds and years\n" +
               "- Point-in-time snapshots (\"rankings as of round N\") are NOT touched\n" +
               "- Match data is only read, never modified\n\n" +
               "Do you want to continue?";
    }

    /** Runs the recalculation and formats the outcome for the commands channel. */
    public CommandResponse execute(String userId) {
        String userInfo = TelegramListener.formatUserInfo(userId);

        if (!isAdmin(userId)) {
            logHelper.logWarning(String.format("Non-admin %s attempted to run /recalculate", userInfo));
            return new CommandResponse("❌ Access Denied: Only administrators can run /recalculate.", (java.nio.file.Path) null);
        }

        logHelper.logInfo(String.format("%s started /recalculate", userInfo));

        try {
            String nowTimestamp = com.calplus.ihrgstats.utils.TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
            RatingRecalculator.RecalcResult result = new RatingRecalculator().recalculateAll(nowTimestamp);

            if (result.roundsRecalculated == 0) {
                logHelper.logInfo(String.format("%s ran /recalculate on an empty database", userInfo));
                return new CommandResponse("🟡 Nothing to recalculate - no rounds have been processed yet.", (java.nio.file.Path) null);
            }

            String message = "🟢 <b>Whole-History Recalculation Complete</b>\n\n" +
                    "<b>Rounds recalculated:</b> " + result.roundsRecalculated + " (all years)\n" +
                    "<b>Players rated:</b> " + result.playersRated + "\n" +
                    "<b>Rating rows written:</b> " + result.ratingRowsWritten + "\n" +
                    "<b>Passes:</b> " + result.passes + "\n\n" +
                    "<i>Point-in-time snapshots were left untouched.</i>";
            logHelper.logSuccess(String.format("%s completed /recalculate: %d rounds, %d rows",
                    userInfo, result.roundsRecalculated, result.ratingRowsWritten));
            return new CommandResponse(message, (java.nio.file.Path) null);

        } catch (Exception e) {
            String errorMsg = String.format("Recalculation failed: %s", e.getMessage());
            logHelper.logError(String.format("%s /recalculate failed: %s", userInfo, e.getMessage()));
            return new CommandResponse("🔴 " + errorMsg, (java.nio.file.Path) null);
        }
    }
}
