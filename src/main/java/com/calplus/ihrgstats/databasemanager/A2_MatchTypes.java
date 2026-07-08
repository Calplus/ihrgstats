package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code match_types} table.
 * No seed data - rows are created by admins via a dedicated Telegram
 * command, since max_score has no sensible default and must always be
 * set explicitly.
 */
public class A2_MatchTypes {

    public static class MatchType {
        public final int id;
        public final String typeName;
        public final double maxScore;
        public final Integer timeLimitMinutes; // nullable
        public final String description; // nullable

        public MatchType(int id, String typeName, double maxScore, Integer timeLimitMinutes, String description) {
            this.id = id;
            this.typeName = typeName;
            this.maxScore = maxScore;
            this.timeLimitMinutes = timeLimitMinutes;
            this.description = description;
        }
    }

    private MatchType mapRow(ResultSet rs) throws SQLException {
        int timeLimit = rs.getInt("time_limit_minutes");
        boolean timeLimitNull = rs.wasNull();
        return new MatchType(
            rs.getInt("id"),
            rs.getString("type_name"),
            rs.getDouble("max_score"),
            timeLimitNull ? null : timeLimit,
            rs.getString("description")
        );
    }

    public int createMatchType(String typeName, double maxScore, Integer timeLimitMinutes, String description, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO match_types (type_name, max_score, time_limit_minutes, description, created_dttm, updated_dttm) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, typeName);
            ps.setDouble(2, maxScore);
            if (timeLimitMinutes != null) ps.setInt(3, timeLimitMinutes); else ps.setNull(3, Types.INTEGER);
            ps.setString(4, description);
            ps.setString(5, nowTimestamp);
            ps.setString(6, nowTimestamp);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public void updateMatchType(int id, String typeName, double maxScore, Integer timeLimitMinutes, String description, String nowTimestamp) throws SQLException {
        String sql = "UPDATE match_types SET type_name = ?, max_score = ?, time_limit_minutes = ?, description = ?, updated_dttm = ? WHERE id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, typeName);
            ps.setDouble(2, maxScore);
            if (timeLimitMinutes != null) ps.setInt(3, timeLimitMinutes); else ps.setNull(3, Types.INTEGER);
            ps.setString(4, description);
            ps.setString(5, nowTimestamp);
            ps.setInt(6, id);
            ps.executeUpdate();
        }
    }

    public MatchType getMatchTypeById(int id) throws SQLException {
        String sql = "SELECT * FROM match_types WHERE id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public List<MatchType> getAllMatchTypes() throws SQLException {
        String sql = "SELECT * FROM match_types ORDER BY id ASC";
        List<MatchType> types = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                types.add(mapRow(rs));
            }
        }
        return types;
    }
}
