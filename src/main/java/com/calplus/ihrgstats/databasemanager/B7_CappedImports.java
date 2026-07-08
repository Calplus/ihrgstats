package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code capped_imports} table - a per-year
 * staging/reconciliation table populated from cappedlist.csv uploads
 * (replaces the legacy A2_CappedPlayers table).
 */
public class B7_CappedImports {

    public static class ImportRow {
        public final int id;
        public final int year;
        public final String name;
        public final String prevHall;
        public final String playerId; // nullable
        public final boolean mapped;

        public ImportRow(int id, int year, String name, String prevHall, String playerId, boolean mapped) {
            this.id = id;
            this.year = year;
            this.name = name;
            this.prevHall = prevHall;
            this.playerId = playerId;
            this.mapped = mapped;
        }
    }

    private ImportRow mapRow(ResultSet rs) throws SQLException {
        return new ImportRow(
            rs.getInt("id"),
            rs.getInt("year"),
            rs.getString("name"),
            rs.getString("prev_hall"),
            rs.getString("player_id"),
            rs.getInt("mapped") != 0
        );
    }

    public int insertImportRow(int year, String name, String prevHall, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO capped_imports (year, name, prev_hall, player_id, mapped, created_dttm, updated_dttm) " +
                "VALUES (?, ?, ?, NULL, 0, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, year);
            ps.setString(2, name);
            ps.setString(3, prevHall);
            ps.setString(4, nowTimestamp);
            ps.setString(5, nowTimestamp);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public void markMapped(int importId, String playerId, String nowTimestamp) throws SQLException {
        String sql = "UPDATE capped_imports SET player_id = ?, mapped = 1, updated_dttm = ? WHERE id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setString(2, nowTimestamp);
            ps.setInt(3, importId);
            ps.executeUpdate();
        }
    }

    public List<ImportRow> getImportsForYear(int year) throws SQLException {
        String sql = "SELECT * FROM capped_imports WHERE year = ?";
        List<ImportRow> rows = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
            }
        }
        return rows;
    }

    /**
     * Finds capped_imports rows for a year whose name matches
     * (case-insensitive) - used by RoundCsvProcessor to check for a
     * pending capped designation whenever it creates a NEW
     * player_year_status row, mirroring legacy's checkCappedStatus()
     * running on every round upload (not just at cappedlist upload time).
     */
    public List<ImportRow> findByYearAndName(int year, String name) throws SQLException {
        String sql = "SELECT * FROM capped_imports WHERE year = ? AND LOWER(name) = LOWER(?)";
        List<ImportRow> rows = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
            }
        }
        return rows;
    }

    /** Full replace semantics - clears all rows for a year before a fresh cappedlist.csv upload is processed. */
    public void clearImportsForYear(int year) throws SQLException {
        String sql = "DELETE FROM capped_imports WHERE year = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.executeUpdate();
        }
    }
}
