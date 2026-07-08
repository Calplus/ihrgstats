package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code rating_types} table.
 * Seeds exactly 2 rows: "TrueElo" (the only currently-functional rating,
 * computed via Batch Glicko-2) and "ExpElo" (a placeholder reserved for
 * future experimentation - renamed from the legacy "PerfElo", which has
 * been removed entirely along with all of its calculation code).
 */
public class D10_RatingTypes {

    public static final String TRUE_ELO = "TrueElo";
    public static final String EXP_ELO = "ExpElo";

    public static class RatingType {
        public final int id;
        public final String ratingName;

        public RatingType(int id, String ratingName) {
            this.id = id;
            this.ratingName = ratingName;
        }
    }

    public void seedDefaults(String nowTimestamp) throws SQLException {
        String insertSql = "INSERT INTO rating_types (rating_name, created_dttm, updated_dttm) " +
                "SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM rating_types WHERE rating_name = ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (String name : new String[]{TRUE_ELO, EXP_ELO}) {
                ps.setString(1, name);
                ps.setString(2, nowTimestamp);
                ps.setString(3, nowTimestamp);
                ps.setString(4, name);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public Integer getRatingTypeId(String ratingName) throws SQLException {
        String sql = "SELECT id FROM rating_types WHERE rating_name = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ratingName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    public List<RatingType> getAllRatingTypes() throws SQLException {
        String sql = "SELECT * FROM rating_types ORDER BY id ASC";
        List<RatingType> types = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                types.add(new RatingType(rs.getInt("id"), rs.getString("rating_name")));
            }
        }
        return types;
    }
}
