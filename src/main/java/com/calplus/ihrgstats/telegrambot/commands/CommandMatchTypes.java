package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.A2_MatchTypes;
import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command handler for /matchtypes command (admin-only).
 * CRUD for the {@code match_types} table: create, list, and edit match
 * types (type name, max score, time limit, description). Needed since
 * round processing now requires an existing match type to be assigned
 * whenever a walkover is present in an uploaded round.
 */
public class CommandMatchTypes {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final String adminUserId;
    private final A2_MatchTypes matchTypes = new A2_MatchTypes();

    private static final Map<String, MatchTypeSelectionState> userSelectionStates = new HashMap<>();

    private static class MatchTypeSelectionState extends SelectionState {
        String step; // "typeName", "maxScore", "timeLimitMinutes", "description"
        Integer editingId; // null = creating new, non-null = editing an existing match type
        String typeName;
        Double maxScore;
        Integer timeLimitMinutes;
    }

    public CommandMatchTypes() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();

        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.adminUserId = PropertyResolver.getProperty("telegram.admin.userId", "");
    }

    public boolean isAdmin(String userId) {
        return !adminUserId.isEmpty() && adminUserId.equals(userId);
    }

    public CommandResponse handleCommand(String userId) {
        String userInfo = TelegramListener.formatUserInfo(userId);
        discordLog.logInfo(String.format("%s requested /matchtypes command", userInfo));
        telegramLog.logInfo(String.format("%s requested /matchtypes command", userInfo));

        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can manage match types.";
            discordLog.logWarning(String.format("Non-admin %s attempted to use /matchtypes", userInfo));
            telegramLog.logWarning(String.format("Non-admin %s attempted to use /matchtypes", userInfo));
            return new CommandResponse(errorMsg, (java.nio.file.Path) null, null);
        }

        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.remove(userId);

        String message = "🎮 **Match Types**\n\nManage the match types assigned to rounds (name, max score, time limit, description).";
        String[] labels = {"➕ Create New", "📋 List All", "✏️ Edit Existing", "❌ Cancel"};
        String[] callbacks = {"matchtypes_create", "matchtypes_list", "matchtypes_editselect", "matchtypes_cancel"};

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

            StringBuilder sb = new StringBuilder("🎮 **Match Types**\n\n");
            for (A2_MatchTypes.MatchType mt : all) {
                sb.append(String.format("**#%d - %s**\n", mt.id, mt.typeName));
                sb.append(String.format("  Max Score: %s\n", formatNumber(mt.maxScore)));
                sb.append(String.format("  Time Limit: %s\n", mt.timeLimitMinutes != null ? mt.timeLimitMinutes + " min" : "Not set"));
                sb.append(String.format("  Description: %s\n\n", mt.description != null && !mt.description.isEmpty() ? mt.description : "Not set"));
            }

            return new CommandResponse(sb.toString(), (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            discordLog.logError("Database error listing match types: " + e.getMessage());
            telegramLog.logError("Database error listing match types: " + e.getMessage());
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

        String message = "➕ **Create New Match Type**\n\nPlease reply with the **type name** (e.g., \"Weiqi Standard\", \"Othello\").";
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

            String message = "✏️ **Edit Match Type**\n\nSelect a match type to edit:";
            return new CommandResponse(message, (java.nio.file.Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]), 1));
        } catch (SQLException e) {
            discordLog.logError("Database error fetching match types: " + e.getMessage());
            telegramLog.logError("Database error fetching match types: " + e.getMessage());
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

            String message = String.format("✏️ **Editing Match Type #%d (%s)**\n\nPlease reply with the new **type name**, or send the same value to keep it (current: **%s**).",
                    existing.id, existing.typeName, existing.typeName);
            return new CommandResponse(message, (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            discordLog.logError("Database error fetching match type: " + e.getMessage());
            telegramLog.logError("Database error fetching match type: " + e.getMessage());
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
                return new CommandResponse("Please reply with the **max score** for this match type (e.g., 368.5, 64).", (java.nio.file.Path) null, null);
            }
            case "maxScore": {
                double maxScore;
                try {
                    maxScore = Double.parseDouble(input);
                } catch (NumberFormatException e) {
                    return new CommandResponse("❌ Invalid input: Max score must be a valid positive number. Please try again.", (java.nio.file.Path) null, null);
                }
                if (maxScore <= 0) {
                    return new CommandResponse("❌ Invalid input: Max score must be a positive number. Please try again.", (java.nio.file.Path) null, null);
                }
                state.maxScore = maxScore;
                state.step = "timeLimitMinutes";
                return new CommandResponse("Please reply with the **time limit in minutes** (or reply \"none\" if not applicable).", (java.nio.file.Path) null, null);
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
                return new CommandResponse("Please reply with a **description** (or reply \"none\" to leave it blank).", (java.nio.file.Path) null, null);
            }
            case "description": {
                String description = (input.equalsIgnoreCase("none") || input.isEmpty()) ? "" : input;
                return saveMatchType(userId, state, description);
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
                String successMsg = String.format("✅ Created match type **#%d - %s** (max score: %s, time limit: %s).",
                        newId, state.typeName, formatNumber(state.maxScore),
                        state.timeLimitMinutes != null ? state.timeLimitMinutes + " min" : "not set");
                discordLog.logSuccess(String.format("Admin %s created match type #%d (%s)", userId, newId, state.typeName));
                telegramLog.logSuccess(String.format("Admin %s created match type #%d (%s)", userId, newId, state.typeName));
                return new CommandResponse(successMsg, (java.nio.file.Path) null, null);
            } else {
                matchTypes.updateMatchType(state.editingId, state.typeName, state.maxScore, state.timeLimitMinutes, description, nowTimestamp);
                String successMsg = String.format("✅ Updated match type **#%d - %s** (max score: %s, time limit: %s).",
                        state.editingId, state.typeName, formatNumber(state.maxScore),
                        state.timeLimitMinutes != null ? state.timeLimitMinutes + " min" : "not set");
                discordLog.logSuccess(String.format("Admin %s updated match type #%d (%s)", userId, state.editingId, state.typeName));
                telegramLog.logSuccess(String.format("Admin %s updated match type #%d (%s)", userId, state.editingId, state.typeName));
                return new CommandResponse(successMsg, (java.nio.file.Path) null, null);
            }
        } catch (SQLException e) {
            discordLog.logError("Database error saving match type: " + e.getMessage());
            telegramLog.logError("Database error saving match type: " + e.getMessage());
            return new CommandResponse("❌ Database error saving match type: " + e.getMessage(), (java.nio.file.Path) null, null);
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
