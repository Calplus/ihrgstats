package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;
import com.calplus.ihrgstats.utils.PropertyResolver;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code admins} table - the multi-admin,
 * multi-platform access-control registry. Replaces the old pattern of every
 * admin-gated command independently comparing {@code userId} against a
 * single hardcoded {@code telegram.admin.userId} property.
 *
 * {@code platform_user_id} is always the platform's stable numeric ID
 * (Telegram user ID / Discord snowflake) - never a mutable @username, matching
 * how identity is resolved everywhere else in this app. {@code display_name}
 * is a friendly label for display only and is never consulted by
 * {@link #isAdmin}.
 */
public class F16_Admins {

    public static final String PLATFORM_TELEGRAM = "TELEGRAM";
    public static final String PLATFORM_DISCORD = "DISCORD";

    public static class Admin {
        public final int id;
        public final String platform;
        public final String platformUserId;
        public final String displayName; // nullable

        public Admin(int id, String platform, String platformUserId, String displayName) {
            this.id = id;
            this.platform = platform;
            this.platformUserId = platformUserId;
            this.displayName = displayName;
        }
    }

    private Admin mapRow(ResultSet rs) throws SQLException {
        return new Admin(
            rs.getInt("id"),
            rs.getString("platform"),
            rs.getString("platform_user_id"),
            rs.getString("display_name")
        );
    }

    /**
     * Bootstraps the initial admin(s) from the existing
     * {@code telegram.admin.userId}/{@code discord.admin.userId} properties,
     * so upgrading to the table requires zero manual migration step for an
     * already-configured deployment. Only runs while the table is still
     * completely empty - once any admin exists (whether from this seed or
     * from {@code /admins}), this is a no-op, so explicitly removing an
     * admin via {@code /admins} sticks across restarts instead of the
     * still-configured property silently re-adding them every boot.
     */
    public void seedDefaults(String nowTimestamp) throws SQLException {
        if (countAdmins() > 0) {
            return;
        }
        String telegramAdminId = PropertyResolver.getProperty("telegram.admin.userId", "").trim();
        if (!telegramAdminId.isEmpty()) {
            addAdminIfAbsent(PLATFORM_TELEGRAM, telegramAdminId, null, nowTimestamp);
        }
        String discordAdminId = PropertyResolver.getProperty("discord.admin.userId", "").trim();
        if (!discordAdminId.isEmpty()) {
            addAdminIfAbsent(PLATFORM_DISCORD, discordAdminId, null, nowTimestamp);
        }
    }

    private void addAdminIfAbsent(String platform, String platformUserId, String displayName, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO admins (platform, platform_user_id, display_name, created_dttm, updated_dttm) " +
                "SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM admins WHERE platform = ? AND platform_user_id = ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, platform);
            ps.setString(2, platformUserId);
            ps.setString(3, displayName);
            ps.setString(4, nowTimestamp);
            ps.setString(5, nowTimestamp);
            ps.setString(6, platform);
            ps.setString(7, platformUserId);
            ps.executeUpdate();
        }
    }

    public boolean isAdmin(String platform, String platformUserId) throws SQLException {
        String sql = "SELECT 1 FROM admins WHERE platform = ? AND platform_user_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, platform);
            ps.setString(2, platformUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Same as {@link #isAdmin} but fails closed (returns false) on a
     * database error instead of throwing, for callers that just need a
     * plain boolean gate and don't have their own logging wired up (prefer
     * {@link #isAdmin} directly when the caller wants to log the specific
     * error with its own context).
     */
    public boolean isAdminSafe(String platform, String platformUserId) {
        try {
            return isAdmin(platform, platformUserId);
        } catch (SQLException e) {
            System.err.println("Database error checking admin status: " + e.getMessage());
            return false;
        }
    }

    public void addAdmin(String platform, String platformUserId, String displayName, String nowTimestamp) throws SQLException {
        addAdminIfAbsent(platform, platformUserId, displayName, nowTimestamp);
    }

    public void removeAdmin(String platform, String platformUserId) throws SQLException {
        String sql = "DELETE FROM admins WHERE platform = ? AND platform_user_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, platform);
            ps.setString(2, platformUserId);
            ps.executeUpdate();
        }
    }

    public List<Admin> getAllAdmins() throws SQLException {
        String sql = "SELECT * FROM admins ORDER BY platform ASC, id ASC";
        List<Admin> admins = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                admins.add(mapRow(rs));
            }
        }
        return admins;
    }

    public int countAdmins() throws SQLException {
        String sql = "SELECT COUNT(*) FROM admins";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
