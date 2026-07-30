package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;

/**
 * Data-access helper for the {@code player_rolling_cache} table.
 * Reserved for the future real-time AI prediction loop (Speed Loop) - not
 * populated or read by any current feature.
 */
public class E13_PlayerRollingCache {

    public static class RollingCache {
        public final String playerId;
        public final int currentStreak;
        public final double avgMarginLast5Matches;
        public final int matchesPlayedToday;

        public RollingCache(String playerId, int currentStreak, double avgMarginLast5Matches, int matchesPlayedToday) {
            this.playerId = playerId;
            this.currentStreak = currentStreak;
            this.avgMarginLast5Matches = avgMarginLast5Matches;
            this.matchesPlayedToday = matchesPlayedToday;
        }
    }

    public void upsertCache(String playerId, int currentStreak, double avgMarginLast5Matches, int matchesPlayedToday, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO player_rolling_cache (player_id, current_streak, avg_margin_last_5_matches, matches_played_today, created_dttm, updated_dttm) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(player_id) DO UPDATE SET current_streak = excluded.current_streak, " +
                "avg_margin_last_5_matches = excluded.avg_margin_last_5_matches, " +
                "matches_played_today = excluded.matches_played_today, updated_dttm = excluded.updated_dttm";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, currentStreak);
            ps.setDouble(3, avgMarginLast5Matches);
            ps.setInt(4, matchesPlayedToday);
            ps.setString(5, nowTimestamp);
            ps.setString(6, nowTimestamp);
            ps.executeUpdate();
        }
    }

    /** One player's freshly computed cache values for {@link #upsertCacheBatch}. */
    public static class CacheRow {
        public final String playerId;
        public final int currentStreak;
        public final double avgMarginLast5Matches;
        public final int matchesPlayedToday;

        public CacheRow(String playerId, int currentStreak, double avgMarginLast5Matches, int matchesPlayedToday) {
            this.playerId = playerId;
            this.currentStreak = currentStreak;
            this.avgMarginLast5Matches = avgMarginLast5Matches;
            this.matchesPlayedToday = matchesPlayedToday;
        }
    }

    /**
     * Upserts a whole roster's cache rows on one connection in one
     * transaction (RollingCacheUpdater previously opened a fresh
     * connection per player).
     */
    public void upsertCacheBatch(java.util.List<CacheRow> rows, String nowTimestamp) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO player_rolling_cache (player_id, current_streak, avg_margin_last_5_matches, matches_played_today, created_dttm, updated_dttm) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(player_id) DO UPDATE SET current_streak = excluded.current_streak, " +
                "avg_margin_last_5_matches = excluded.avg_margin_last_5_matches, " +
                "matches_played_today = excluded.matches_played_today, updated_dttm = excluded.updated_dttm";
        try (Connection conn = DatabaseHelper.getDefaultConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (CacheRow row : rows) {
                    ps.setString(1, row.playerId);
                    ps.setInt(2, row.currentStreak);
                    ps.setDouble(3, row.avgMarginLast5Matches);
                    ps.setInt(4, row.matchesPlayedToday);
                    ps.setString(5, nowTimestamp);
                    ps.setString(6, nowTimestamp);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // rollback failure must not mask the original error
                }
                throw e;
            }
        }
    }

    public RollingCache getCache(String playerId) throws SQLException {
        String sql = "SELECT * FROM player_rolling_cache WHERE player_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new RollingCache(rs.getString("player_id"), rs.getInt("current_streak"),
                        rs.getDouble("avg_margin_last_5_matches"), rs.getInt("matches_played_today"));
            }
        }
    }
}
