package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command handler for /admins (admin-only) - manages the {@code admins}
 * table (list/add/remove) so it doesn't require direct SQLite edits to
 * maintain. Refuses to remove the last remaining admin (lockout guard).
 */
public class CommandAdmins {
    private final LogHelper logHelper;
    private final F16_Admins admins = new F16_Admins();

    private static final Map<String, AdminsSelectionState> userSelectionStates = new ConcurrentHashMap<>();

    private static class AdminsSelectionState extends SelectionState {
        boolean awaitingAdd;
    }

    public CommandAdmins() {
        EnvironmentManager.ensureSystemPropertiesLoaded();

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

    public CommandResponse handleCommand(String userId) {
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested /admins command", userInfo));

        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can manage admins.";
            logHelper.logWarning(String.format("Non-admin %s attempted to use /admins", userInfo));
            return new CommandResponse(errorMsg, (java.nio.file.Path) null, null);
        }

        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        userSelectionStates.remove(userId);

        String message = "🔑 <b>Admins</b>\n\nManage who can run admin-only commands.";
        String[] labels = {"📋 List Admins", "➕ Add Admin", "➖ Remove Admin", "❌ Cancel"};
        String[] callbacks = {"admins_list", "admins_addstart", "admins_removeselect", "admins_cancel"};

        return new CommandResponse(message, (java.nio.file.Path) null, new ButtonConfig(labels, callbacks, 1));
    }

    public CommandResponse handleList(String userId) {
        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage admins.", (java.nio.file.Path) null, null);
        }

        try {
            List<F16_Admins.Admin> all = admins.getAllAdmins();
            if (all.isEmpty()) {
                return new CommandResponse("ℹ️ No admins are configured.", (java.nio.file.Path) null, null);
            }

            StringBuilder sb = new StringBuilder("🔑 <b>Admins</b>\n\n");
            for (F16_Admins.Admin admin : all) {
                sb.append(String.format("<b>%s</b> — <code>%s</code>", TelegramHtml.escape(admin.platform), TelegramHtml.escape(admin.platformUserId)));
                if (admin.displayName != null && !admin.displayName.isEmpty()) {
                    sb.append(" (").append(TelegramHtml.escape(admin.displayName)).append(")");
                }
                sb.append("\n");
            }
            return new CommandResponse(sb.toString(), (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            logHelper.logError("Database error listing admins: " + e.getMessage());
            return new CommandResponse("❌ Database error listing admins.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleAddStart(String userId) {
        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage admins.", (java.nio.file.Path) null, null);
        }

        AdminsSelectionState state = new AdminsSelectionState();
        state.awaitingAdd = true;
        userSelectionStates.put(userId, state);

        String message = "➕ <b>Add Admin</b>\n\nReply with <code>{PLATFORM} {id} {label}</code>, e.g. <code>TELEGRAM 123456789 Alex</code> or <code>DISCORD 987654321098765432 Alex</code>. The label is optional.";
        return new CommandResponse(message, (java.nio.file.Path) null, null);
    }

    public CommandResponse handleRemoveSelect(String userId) {
        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage admins.", (java.nio.file.Path) null, null);
        }

        try {
            List<F16_Admins.Admin> all = admins.getAllAdmins();
            if (all.isEmpty()) {
                return new CommandResponse("ℹ️ No admins are configured.", (java.nio.file.Path) null, null);
            }

            List<String> labels = new ArrayList<>();
            List<String> callbacks = new ArrayList<>();
            for (F16_Admins.Admin admin : all) {
                String label = admin.platform + " " + admin.platformUserId + (admin.displayName != null && !admin.displayName.isEmpty() ? " (" + admin.displayName + ")" : "");
                labels.add(label);
                callbacks.add("admins_remove_" + admin.id);
            }
            labels.add("❌ Cancel");
            callbacks.add("admins_cancel");

            String message = "➖ <b>Remove Admin</b>\n\nSelect an admin to remove:";
            return new CommandResponse(message, (java.nio.file.Path) null, new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]), 1));
        } catch (SQLException e) {
            logHelper.logError("Database error fetching admins: " + e.getMessage());
            return new CommandResponse("❌ Database error fetching admins.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleRemoveConfirm(String userId, int adminRowId) {
        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage admins.", (java.nio.file.Path) null, null);
        }

        try {
            List<F16_Admins.Admin> all = admins.getAllAdmins();

            F16_Admins.Admin target = null;
            for (F16_Admins.Admin admin : all) {
                if (admin.id == adminRowId) {
                    target = admin;
                    break;
                }
            }
            if (target == null) {
                return new CommandResponse("❌ Admin not found - it may have already been removed.", (java.nio.file.Path) null, null);
            }

            // Guard per platform, not the combined total - a lone Telegram
            // admin plus a lone Discord admin must not let the Telegram
            // admin remove themselves just because the combined count is 2.
            String targetPlatform = target.platform;
            long sameplatformCount = all.stream().filter(a -> a.platform.equals(targetPlatform)).count();
            if (sameplatformCount <= 1) {
                return new CommandResponse(String.format(
                        "⚠️ Refusing to remove the last remaining %s admin - that would lock everyone out on that platform.",
                        target.platform), (java.nio.file.Path) null, null);
            }

            admins.removeAdmin(target.platform, target.platformUserId);

            String successMsg = String.format("✅ Removed admin <b>%s %s</b>.", TelegramHtml.escape(target.platform), TelegramHtml.escape(target.platformUserId));
            logHelper.logSuccess(String.format("Admin %s removed admin %s/%s", userId, target.platform, target.platformUserId));
            return new CommandResponse(successMsg, (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            logHelper.logError("Database error removing admin: " + e.getMessage());
            return new CommandResponse("❌ Database error removing admin.", (java.nio.file.Path) null, null);
        }
    }

    public CommandResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new CommandResponse("ℹ️ Admin management cancelled.", (java.nio.file.Path) null, null);
    }

    /**
     * Handles the free-text "{PLATFORM} {id} {label}" reply for the add-admin
     * flow. Returns null if the user isn't currently in that flow (caller
     * should then treat the message as unrelated to this command).
     */
    public CommandResponse handleTextInput(String userId, String text) {
        AdminsSelectionState state = userSelectionStates.get(userId);
        if (state == null || !state.awaitingAdd) {
            return null;
        }

        userSelectionStates.remove(userId);

        if (!isAdmin(userId)) {
            return new CommandResponse("❌ Access Denied: Only administrators can manage admins.", (java.nio.file.Path) null, null);
        }

        String[] parts = text.trim().split("\\s+", 3);
        if (parts.length < 2) {
            return new CommandResponse("❌ Invalid input: reply with `{PLATFORM} {id} {label}`, e.g. `TELEGRAM 123456789 Alex`.", (java.nio.file.Path) null, null);
        }

        String platform = parts[0].trim().toUpperCase();
        if (!F16_Admins.PLATFORM_TELEGRAM.equals(platform) && !F16_Admins.PLATFORM_DISCORD.equals(platform)) {
            return new CommandResponse("❌ Invalid input: platform must be `TELEGRAM` or `DISCORD`.", (java.nio.file.Path) null, null);
        }

        String platformUserId = parts[1].trim();
        if (platformUserId.isEmpty()) {
            return new CommandResponse("❌ Invalid input: id cannot be empty.", (java.nio.file.Path) null, null);
        }

        String displayName = parts.length > 2 ? parts[2].trim() : null;

        try {
            String nowTimestamp = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
            admins.addAdmin(platform, platformUserId, displayName, nowTimestamp);

            String successMsg = String.format("✅ Added admin <b>%s %s</b>%s.",
                    TelegramHtml.escape(platform), TelegramHtml.escape(platformUserId),
                    displayName != null && !displayName.isEmpty() ? " (" + TelegramHtml.escape(displayName) + ")" : "");
            logHelper.logSuccess(String.format("Admin %s added admin %s/%s", userId, platform, platformUserId));
            return new CommandResponse(successMsg, (java.nio.file.Path) null, null);
        } catch (SQLException e) {
            logHelper.logError("Database error adding admin: " + e.getMessage());
            return new CommandResponse("❌ Database error adding admin.", (java.nio.file.Path) null, null);
        }
    }

    /** Checks whether a given user is currently mid-wizard (used by the listener to route free-text replies here). */
    public boolean isAwaitingTextInput(String userId) {
        AdminsSelectionState state = userSelectionStates.get(userId);
        return state != null && state.awaitingAdd;
    }
}
