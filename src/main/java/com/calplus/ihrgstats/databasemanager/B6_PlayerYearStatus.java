package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code player_year_status} table - one row
 * per (player, year) tracking their hall/capped/active status for that
 * year's tournament.
 *
 * Rows are only ever created when a player is actually observed playing
 * that year (directly, or carried forward within the SAME year when their
 * hall played but they personally sat out a round) - never pre-created
 * across a year boundary. As a consequence, `active` is effectively always
 * true for any row that exists; it's kept for query-compatibility and
 * future-proofing (e.g. a future pre-season registration feature) even
 * though no current code path creates an active=false row.
 */
public class B6_PlayerYearStatus {

    public static class Status {
        public final String playerId;
        public final int year;
        public final int hallId;
        public final boolean capped;
        public final boolean active;

        public Status(String playerId, int year, int hallId, boolean capped, boolean active) {
            this.playerId = playerId;
            this.year = year;
            this.hallId = hallId;
            this.capped = capped;
            this.active = active;
        }
    }

    private Status mapRow(ResultSet rs) throws SQLException {
        return new Status(
            rs.getString("player_id"),
            rs.getInt("year"),
            rs.getInt("hall_id"),
            rs.getInt("capped") != 0,
            rs.getInt("active") != 0
        );
    }

    public Status getStatus(String playerId, int year) throws SQLException {
        String sql = "SELECT * FROM player_year_status WHERE player_id = ? AND year = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /**
     * Returns this player's most recent status row from any year STRICTLY
     * before the given year (their last known hall/status before this
     * year), or null if they have never played before. Used to detect
     * cross-year hall changes when a player first appears in a new year.
     */
    public Status getMostRecentStatusBeforeYear(String playerId, int year) throws SQLException {
        String sql = "SELECT * FROM player_year_status WHERE player_id = ? AND year < ? ORDER BY year DESC LIMIT 1";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /** Creates or fully replaces a player's status row for a year. */
    public void upsertStatus(String playerId, int year, int hallId, boolean capped, boolean active, String nowTimestamp) throws SQLException {
        String sql =
            "INSERT INTO player_year_status (player_id, year, hall_id, capped, active, created_dttm, updated_dttm) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(player_id, year) DO UPDATE SET " +
            "hall_id = excluded.hall_id, capped = excluded.capped, active = excluded.active, updated_dttm = excluded.updated_dttm";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, year);
            ps.setInt(3, hallId);
            ps.setInt(4, capped ? 1 : 0);
            ps.setInt(5, active ? 1 : 0);
            ps.setString(6, nowTimestamp);
            ps.setString(7, nowTimestamp);
            ps.executeUpdate();
        }
    }

    public void setCapped(String playerId, int year, boolean capped, String nowTimestamp) throws SQLException {
        String sql = "UPDATE player_year_status SET capped = ?, updated_dttm = ? WHERE player_id = ? AND year = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, capped ? 1 : 0);
            ps.setString(2, nowTimestamp);
            ps.setString(3, playerId);
            ps.setInt(4, year);
            ps.executeUpdate();
        }
    }

    /**
     * Clears the capped flag for every currently-capped player in a year.
     * Used by a full cappedlist.csv re-upload (itself full-replace semantics
     * for the capped_imports staging table) so a player removed from the new
     * list is actually un-capped, instead of permanently keeping whatever
     * capped flag an earlier upload set.
     */
    public void clearCappedForYear(int year, String nowTimestamp) throws SQLException {
        String sql = "UPDATE player_year_status SET capped = 0, updated_dttm = ? WHERE year = ? AND capped = 1";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nowTimestamp);
            ps.setInt(2, year);
            ps.executeUpdate();
        }
    }

    /** Replaces the legacy "WHERE active = 1" filter pattern. */
    public List<Status> getActiveStatusesForYear(int year) throws SQLException {
        String sql = "SELECT * FROM player_year_status WHERE year = ? AND active = 1";
        List<Status> statuses = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    statuses.add(mapRow(rs));
                }
            }
        }
        return statuses;
    }

    public List<Status> getStatusesForHallAndYear(int hallId, int year) throws SQLException {
        String sql = "SELECT * FROM player_year_status WHERE hall_id = ? AND year = ? AND active = 1";
        List<Status> statuses = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, hallId);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    statuses.add(mapRow(rs));
                }
            }
        }
        return statuses;
    }

    /** Every year-status row ever recorded for one player, ordered oldest-first. Used for exports. */
    public List<Status> getStatusesForPlayer(String playerId) throws SQLException {
        String sql = "SELECT * FROM player_year_status WHERE player_id = ? ORDER BY year ASC";
        List<Status> statuses = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    statuses.add(mapRow(rs));
                }
            }
        }
        return statuses;
    }
}
