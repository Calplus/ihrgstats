package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.A2_MatchTypes;
import com.calplus.ihrgstats.databasemanager.C8_Matches;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;
import com.calplus.ihrgstats.utils.TelegramHtml;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command handler for /matchtypes command (admin-only).
 * CRUD for the {@code match_types} table: create, list, and edit match
 * types (type name, max score, time limit, description). Needed since
 * round processing now requires an existing match type to be assigned
 * whenever a walkover is present in an uploaded round.
 */
public class CommandMatchTypes {
    private final LogHelper logHelper;
    private final com.calplus.ihrgstats.databasemanager.F16_Admins admins = new com.calplus.ihrgstats.databasemanager.F16_Admins();
    private final A2_MatchTypes matchTypes = new A2_MatchTypes();
    private final A1_Rounds rounds = new A1_Rounds();
    private final C8_Matches matches = new C8_Matches();

    private static final Map<String, MatchTypeSelectionState> userSelectionStates = new ConcurrentHashMap<>();

    private static class MatchTypeSelectionState extends SelectionState {
        String step; // "typeName", "maxScore", "timeLimitMinutes", "description", "assignRound"
        Integer editingId; // null = creating new, non-null = editing an existing match type
        String typeName;
        Double maxScore;
        Integer timeLimitMinutes;
        Integer assigningMatchTypeId; // set while awaiting a "{year} {round}" reply for the assign-to-round flow
    }

    public CommandMatchTypes() {
        EnvironmentManager.ensureSystemPropertiesLoaded();

        this.logHelper = new LogHelper();
    }

    /** Fails closed (denies) on a database error rather than risking a false "admin". */
    public boolean isAdmin(String userId) {
        try {
            return admins.isAdmin(com.calplus.ihrgstats.databasemanager.F16_Admins.PLATFORM_TELEGRAM, userId);
        } catch (java.sql.SQLException e) {
            logHelper.logError("Database error checking admin status: " + e.getMessage());
            return false;
        }
    }

    public CommandResponse handleCommand(String userId) {
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested /matchtypes command", userInfo));

        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can manage match types.";
            logHelper.logWarning(String.format("Non-admin %s attempted to use /matchtypes", userInfo));
            return new CommandResponse(errorMsg, (java.nio.file.Path) null, null);
        }

        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.remove(userId);

        String message = "🎮 <b>Match Types</b>\n\nManage the match types assigned to rounds (name, max score, time limit, description).";
        String[] labels = {"➕ Create New", "📋 List All", "✏️ Edit Existing", "🔧 Assign to Round", "❌ Cancel"};
        String[] callbacks = {"matchtypes_create", "matchtypes_list", "matchtypes_editselect", "matchtypes_assignselect", "matchtypes_cancel"};

        return new CommandResponse(message, (java.nio.file.Path) null, new ButtonConfig(labels, callbacks, 1));
    }

    public CommandResponse handleList(String userId) {
        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage match types.", (java.nio.file.Path) null, null);
        }

        try {
            List<A2_MatchTypes.MatchType> all = matchTypes.getAllMatchTypes();
            if (all.isEmpty()) {
                return new CommandResponse("ℹ️ No match types have been created yet. Use \"➕ Create New\" to add one.", (java.nio.file.Path) null, null);
            }

            StringBuilder sb = new StringBuilder("🎮 <b>Match Types</b>\n\n");
            for (A2_MatchTypes.MatchType mt : all) {
                sb.append(String.format("<b>#%d - %s</b>\n", mt.id, TelegramHtml.escape(mt.typeName)));
                sb.append(String.format("  Max Score: %s\n", formatNumber(mt.maxScore)));
                sb.append(String.format("  Time Limit: %s\n", mt.timeLimitMinutes != null ? mt.timeLimitMinutes + " min" : "Not set"));
                sb.append(String.format("  Description: %s\n\n", TelegramHtml.escape(mt.description != null && !mt.description.isEmpty() ? mt.description : "Not set")));
            }

            return new CommandResponse(sb.toString(), (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            logHelper.logError("Database error listing match types: " + e.getMessage());
            return new CommandResponse("❌ Database error listing match types.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleCreateNew(String userId) {
        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage match types.", (java.nio.file.Path) null, null);
        }

        MatchTypeSelectionState state = new MatchTypeSelectionState();
        state.step = "typeName";
        state.editingId = null;
        userSelectionStates.put(userId, state);

        String message = "➕ <b>Create New Match Type</b>\n\nPlease reply with the <b>type name</b> (e.g., \"Weiqi Standard\", \"Othello\").";
        return new CommandResponse(message, (java.nio.file.Path) null, null);
    }

    public CommandResponse handleEditSelection(String userId) {
        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage match types.", (java.nio.file.Path) null, null);
        }

        try {
            List<A2_MatchTypes.MatchType> all = matchTypes.getAllMatchTypes();
            if (all.isEmpty()) {
                return new CommandResponse("ℹ️ No match types exist yet. Use \"➕ Create New\" to add one.", (java.nio.file.Path) null, null);
            }

            List<String> labels = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            for (A2_MatchTypes.MatchType mt : all) {
                labels.add(mt.typeName);
                callbacks.add("matchtypes_edit_" + mt.id);
            }
            labels.add("❌ Cancel");
            callbacks.add("matchtypes_cancel");

            String message = "✏️ <b>Edit Match Type</b>\n\nSelect a match type to edit:";
            return new CommandResponse(message, (java.nio.file.Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]), 1));
        } catch (SQLException e) {
            logHelper.logError("Database error fetching match types: " + e.getMessage());
            return new CommandResponse("❌ Database error fetching match types.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleEditStart(String userId, int matchTypeId) {
        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage match types.", (java.nio.file.Path) null, null);
        }

        try {
            A2_MatchTypes.MatchType existing = matchTypes.getMatchTypeById(matchTypeId);
            if (existing == null) {
                return new CommandResponse("❌ Match type not found.", (java.nio.file.Path) null, null);
            }

            MatchTypeSelectionState state = new MatchTypeSelectionState();
            state.step = "typeName";
            state.editingId = matchTypeId;
            userSelectionStates.put(userId, state);

            String message = String.format("✏️ <b>Editing Match Type #%d (%s)</b>\n\nPlease reply with the new <b>type name</b>, or send the same value to keep it (current: <b>%s</b>).",
                    existing.id, TelegramHtml.escape(existing.typeName), TelegramHtml.escape(existing.typeName));
            return new CommandResponse(message, (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            logHelper.logError("Database error fetching match type: " + e.getMessage());
            return new CommandResponse("❌ Database error fetching match type.", (java.nio.file.Path) null, null);
        }
    }

    /**
     * Lists match types to pick from for the "assign to round" repair flow -
     * lets an admin recover a round whose match_type_id ended up NULL (e.g.
     * a reprocess with no walkover in the new data - see the reprocess-order
     * fix in RoundCsvProcessor) without needing a walkover to trigger the
     * prompt again.
     */
    public CommandResponse handleAssignSelection(String userId) {
        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage match types.", (java.nio.file.Path) null, null);
        }

        try {
            List<A2_MatchTypes.MatchType> all = matchTypes.getAllMatchTypes();
            if (all.isEmpty()) {
                return new CommandResponse("ℹ️ No match types exist yet. Use \"➕ Create New\" to add one first.", (java.nio.file.Path) null, null);
            }

            List<String> labels = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            for (A2_MatchTypes.MatchType mt : all) {
                labels.add(mt.typeName);
                callbacks.add("matchtypes_assignmt_" + mt.id);
            }
            labels.add("❌ Cancel");
            callbacks.add("matchtypes_cancel");

            String message = "🔧 <b>Assign Match Type to a Round</b>\n\nSelect the match type to assign:";
            return new CommandResponse(message, (java.nio.file.Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]), 1));
        } catch (SQLException e) {
            logHelper.logError("Database error fetching match types: " + e.getMessage());
            return new CommandResponse("❌ Database error fetching match types.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleAssignStart(String userId, int matchTypeId) {
        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage match types.", (java.nio.file.Path) null, null);
        }

        try {
            A2_MatchTypes.MatchType existing = matchTypes.getMatchTypeById(matchTypeId);
            if (existing == null) {
                return new CommandResponse("❌ Match type not found.", (java.nio.file.Path) null, null);
            }

            MatchTypeSelectionState state = new MatchTypeSelectionState();
            state.step = "assignRound";
            state.assigningMatchTypeId = matchTypeId;
            userSelectionStates.put(userId, state);

            String message = String.format(
                "🔧 <b>Assign \"%s\" to a Round</b>\n\nReply with <code>{year} {round}</code> (e.g. <code>2025 3</code>) to assign this match type to that round's matches.",
                TelegramHtml.escape(existing.typeName));
            return new CommandResponse(message, (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            logHelper.logError("Database error fetching match type: " + e.getMessage());
            return new CommandResponse("❌ Database error fetching match type.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new CommandResponse("ℹ️ Match type management cancelled.", (java.nio.file.Path) null, null);
    }

    /**
     * Handles free-text replies for the create/edit wizard. Returns null if
     * the user isn't currently in a match-type text-input flow (caller
     * should then treat the message as unrelated to this command).
     */
    public CommandResponse handleTextInput(String userId, String text) {
        MatchTypeSelectionState state = userSelectionStates.get(userId);
        if (state == null || state.step == null) {
            return null;
        }

        if (!isAdmin(userId)) {
            userSelectionStates.remove(userId);
            return new CommandResponse("❌ Access Denied: Only administrators can manage match types.", (java.nio.file.Path) null, null);
        }

        String input = text.trim();

        switch (state.step) {
            case "typeName": {
                if (input.isEmpty()) {
                    return new CommandResponse("❌ Invalid input: Type name cannot be empty. Please try again.", (java.nio.file.Path) null, null);
                }
                state.typeName = input;
                state.step = "maxScore";
                return new CommandResponse("Please reply with the <b>max score</b> for this match type (e.g., 368.5, 64).", (java.nio.file.Path) null, null);
            }
            case "maxScore": {
                double maxScore;
                try {
                    maxScore = Double.parseDouble(input);
                } catch (NumberFormatException e) {
                    return new CommandResponse("❌ Invalid input: Max score must be a valid positive number. Please try again.", (java.nio.file.Path) null, null);
                }
                // isFinite: Double.parseDouble accepts "NaN"/"Infinity"/"1e999",
                // none of which are caught by the <= 0 range check, and a
                // non-finite max score would flow into walkover default scores
                // unvalidated (CSV rows have their own finite-score checks;
                // this wizard is the only other way a score bound enters).
                if (!Double.isFinite(maxScore) || maxScore <= 0) {
                    return new CommandResponse("❌ Invalid input: Max score must be a positive number. Please try again.", (java.nio.file.Path) null, null);
                }
                state.maxScore = maxScore;
                state.step = "timeLimitMinutes";
                return new CommandResponse("Please reply with the <b>time limit in minutes</b> (or reply \"none\" if not applicable).", (java.nio.file.Path) null, null);
            }
            case "timeLimitMinutes": {
                if (input.equalsIgnoreCase("none") || input.isEmpty()) {
                    state.timeLimitMinutes = null;
                } else {
                    try {
                        int minutes = Integer.parseInt(input);
                        if (minutes <= 0) {
                            return new CommandResponse("❌ Invalid input: Time limit must be a positive whole number, or \"none\". Please try again.", (java.nio.file.Path) null, null);
                        }
                        state.timeLimitMinutes = minutes;
                    } catch (NumberFormatException e) {
                        return new CommandResponse("❌ Invalid input: Time limit must be a whole number of minutes, or \"none\". Please try again.", (java.nio.file.Path) null, null);
                    }
                }
                state.step = "description";
                return new CommandResponse("Please reply with a <b>description</b> (or reply \"none\" to leave it blank).", (java.nio.file.Path) null, null);
            }
            case "description": {
                String description = (input.equalsIgnoreCase("none") || input.isEmpty()) ? "" : input;
                return saveMatchType(userId, state, description);
            }
            case "assignRound": {
                return assignMatchTypeToRound(userId, state, input);
            }
            default:
                userSelectionStates.remove(userId);
                return new CommandResponse("❌ Something went wrong. Please use /matchtypes to start again.", (java.nio.file.Path) null, null);
        }
    }

    private CommandResponse saveMatchType(String userId, MatchTypeSelectionState state, String description) {
        userSelectionStates.remove(userId);
        String nowTimestamp = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");

        try {
            if (state.editingId == null) {
                int newId = matchTypes.createMatchType(state.typeName, state.maxScore, state.timeLimitMinutes, description, nowTimestamp);
                String successMsg = String.format("✅ Created match type <b>#%d - %s</b> (max score: %s, time limit: %s).",
                        newId, TelegramHtml.escape(state.typeName), formatNumber(state.maxScore),
                        state.timeLimitMinutes != null ? state.timeLimitMinutes + " min" : "not set");
                logHelper.logSuccess(String.format("Admin %s created match type #%d (%s)", userId, newId, state.typeName));
                return new CommandResponse(successMsg, (java.nio.file.Path) null, null);
            } else {
                matchTypes.updateMatchType(state.editingId, state.typeName, state.maxScore, state.timeLimitMinutes, description, nowTimestamp);
                String successMsg = String.format("✅ Updated match type <b>#%d - %s</b> (max score: %s, time limit: %s).",
                        state.editingId, TelegramHtml.escape(state.typeName), formatNumber(state.maxScore),
                        state.timeLimitMinutes != null ? state.timeLimitMinutes + " min" : "not set");
                logHelper.logSuccess(String.format("Admin %s updated match type #%d (%s)", userId, state.editingId, state.typeName));
                return new CommandResponse(successMsg, (java.nio.file.Path) null, null);
            }
        } catch (SQLException e) {
            logHelper.logError("Database error saving match type: " + e.getMessage());
            return new CommandResponse("❌ Database error saving match type: " + e.getMessage(), (java.nio.file.Path) null, null);
        }
    }

    /**
     * Parses a "{year} {round}" reply and assigns the pending match type to
     * that round's matches via {@link C8_Matches#updateMatchTypeForRound} -
     * the repair path for a round that ended up with match_type_id = NULL
     * (e.g. a reprocess with no walkover in the new data).
     */
    private CommandResponse assignMatchTypeToRound(String userId, MatchTypeSelectionState state, String input) {
        userSelectionStates.remove(userId);
        int matchTypeId = state.assigningMatchTypeId;

        String[] parts = input.trim().split("\\s+");
        if (parts.length != 2) {
            return new CommandResponse("❌ Invalid input: reply with `{year} {round}`, e.g. `2025 3`.", (java.nio.file.Path) null, null);
        }

        int year;
        int roundOrder;
        try {
            year = Integer.parseInt(parts[0]);
            roundOrder = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return new CommandResponse("❌ Invalid input: both year and round must be whole numbers, e.g. `2025 3`.", (java.nio.file.Path) null, null);
        }

        try {
            A1_Rounds.Round round = rounds.getRoundByYearAndOrder(year, roundOrder);
            if (round == null) {
                return new CommandResponse(String.format("❌ Round %d of %d does not exist.", roundOrder, year), (java.nio.file.Path) null, null);
            }

            A2_MatchTypes.MatchType matchType = matchTypes.getMatchTypeById(matchTypeId);
            String nowTimestamp = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
            matches.updateMatchTypeForRound(round.id, matchTypeId, nowTimestamp);

            String successMsg = String.format("✅ Assigned match type <b>%s</b> to round %d of %d.",
                    TelegramHtml.escape(matchType != null ? matchType.typeName : ("#" + matchTypeId)), roundOrder, year);
            logHelper.logSuccess(String.format("Admin %s assigned match type #%d to round %d/%d", userId, matchTypeId, roundOrder, year));
            return new CommandResponse(successMsg, (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            logHelper.logError("Database error assigning match type to round: " + e.getMessage());
            return new CommandResponse("❌ Database error assigning match type to round.", (java.nio.file.Path) null, null);
        }
    }

    /** Checks whether a given user is currently mid-wizard (used by the listener to route free-text replies here). */
    public boolean isAwaitingTextInput(String userId) {
        MatchTypeSelectionState state = userSelectionStates.get(userId);
        return state != null && state.step != null;
    }

    private String formatNumber(double value) {
        return (value == Math.floor(value)) ? String.format("%.0f", value) : String.valueOf(value);
    }
}
