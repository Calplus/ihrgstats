package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;

/**
 * Data-access helper for the {@code player_profiles} table.
 * Reserved for the future AI/embedding layer (Deep Loop) - not populated
 * or read by any current feature.
 */
public class E12_PlayerProfiles {

    public static class Profile {
        public final String playerId;
        public final String playstyleVector;
        public final int lastCalculatedYear;

        public Profile(String playerId, String playstyleVector, int lastCalculatedYear) {
            this.playerId = playerId;
            this.playstyleVector = playstyleVector;
            this.lastCalculatedYear = lastCalculatedYear;
        }
    }

    public void upsertProfile(String playerId, String playstyleVector, int lastCalculatedYear, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO player_profiles (player_id, playstyle_vector, last_calculated_year, created_dttm, updated_dttm) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(player_id) DO UPDATE SET playstyle_vector = excluded.playstyle_vector, " +
                "last_calculated_year = excluded.last_calculated_year, updated_dttm = excluded.updated_dttm";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setString(2, playstyleVector);
            ps.setInt(3, lastCalculatedYear);
            ps.setString(4, nowTimestamp);
            ps.setString(5, nowTimestamp);
            ps.executeUpdate();
        }
    }

    public Profile getProfile(String playerId) throws SQLException {
        String sql = "SELECT * FROM player_profiles WHERE player_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Profile(rs.getString("player_id"), rs.getString("playstyle_vector"), rs.getInt("last_calculated_year"));
            }
        }
    }
}
