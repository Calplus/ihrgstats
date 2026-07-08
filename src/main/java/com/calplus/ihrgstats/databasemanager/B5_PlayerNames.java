package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code player_names} table - tracks every
 * distinct name spelling/variant used for a player over the years, so
 * typos/renames can be resolved back to one permanent player_id.
 *
 * NOTE: the same name string can legitimately belong to MORE THAN ONE
 * player_id (different real people who happen to share a name across
 * different halls/years) - callers must disambiguate further, primarily
 * by hall (see B6_PlayerYearStatus), falling back to fuzzy matching only
 * when no exact match resolves cleanly.
 */
public class B5_PlayerNames {

    public static class NameRecord {
        public final String playerId;
        public final String name;
        public final int firstSeenYear;
        public final int lastSeenYear;

        public NameRecord(String playerId, String name, int firstSeenYear, int lastSeenYear) {
            this.playerId = playerId;
            this.name = name;
            this.firstSeenYear = firstSeenYear;
            this.lastSeenYear = lastSeenYear;
        }
    }

    private NameRecord mapRow(ResultSet rs) throws SQLException {
        return new NameRecord(rs.getString("player_id"), rs.getString("name"), rs.getInt("first_seen_year"), rs.getInt("last_seen_year"));
    }

    /**
     * Records that `name` was used by `playerId` in `year`. If this exact
     * (player_id, name) pair already has a row, extends first/last_seen_year
     * to include this year; otherwise inserts a new row.
     */
    public void addOrUpdateName(String playerId, String name, int year, String nowTimestamp) throws SQLException {
        String upsertSql =
            "INSERT INTO player_names (player_id, name, first_seen_year, last_seen_year, created_dttm, updated_dttm) " +
            "VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(player_id, name) DO UPDATE SET " +
            "first_seen_year = MIN(first_seen_year, excluded.first_seen_year), " +
            "last_seen_year = MAX(last_seen_year, excluded.last_seen_year), " +
            "updated_dttm = excluded.updated_dttm";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setString(1, playerId);
            ps.setString(2, name);
            ps.setInt(3, year);
            ps.setInt(4, year);
            ps.setString(5, nowTimestamp);
            ps.setString(6, nowTimestamp);
            ps.executeUpdate();
        }
    }

    public List<NameRecord> getNamesForPlayer(String playerId) throws SQLException {
        String sql = "SELECT * FROM player_names WHERE player_id = ? ORDER BY last_seen_year DESC";
        List<NameRecord> names = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(mapRow(rs));
                }
            }
        }
        return names;
    }

    /**
     * Returns the name a player used during a given year (first_seen_year
     * <= year <= last_seen_year), falling back to their most recently used
     * name overall if none is exactly valid for that year. Returns null if
     * the player has no name records at all.
     */
    public String getNameForYear(String playerId, int year) throws SQLException {
        String sql = "SELECT name FROM player_names WHERE player_id = ? AND first_seen_year <= ? AND last_seen_year >= ? " +
                "ORDER BY last_seen_year DESC LIMIT 1";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, year);
            ps.setInt(3, year);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("name");
                }
            }
        }
        String fallbackSql = "SELECT name FROM player_names WHERE player_id = ? ORDER BY last_seen_year DESC LIMIT 1";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(fallbackSql)) {
            ps.setString(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("name") : null;
            }
        }
    }

    /**
     * Returns every player_id that has EVER used exactly this name
     * (case-insensitive), most-recently-used first.
     */
    public List<NameRecord> findCandidatesByExactName(String name) throws SQLException {
        String sql = "SELECT * FROM player_names WHERE LOWER(name) = LOWER(?) ORDER BY last_seen_year DESC";
        List<NameRecord> candidates = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(mapRow(rs));
                }
            }
        }
        return candidates;
    }

    /**
     * Returns all name records valid during a given year (across all
     * players) - used to gather fuzzy-match candidates (Levenshtein/partial
     * match) when no exact name match is found.
     */
    public List<NameRecord> getAllNamesForYear(int year) throws SQLException {
        String sql = "SELECT * FROM player_names WHERE first_seen_year <= ? AND last_seen_year >= ?";
        List<NameRecord> names = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(mapRow(rs));
                }
            }
        }
        return names;
    }

    /**
     * Returns ALL known name records (every player, every year) - the
     * broadest fuzzy-match candidate pool, used when year-scoped lookup
     * isn't sufficient (e.g. a player's very first appearance in any year).
     */
    public List<NameRecord> getAllNames() throws SQLException {
        String sql = "SELECT * FROM player_names";
        List<NameRecord> names = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                names.add(mapRow(rs));
            }
        }
        return names;
    }
}
