package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code matches} table.
 */
public class C8_Matches {

    public static class Match {
        public final int id;
        public final int roundId;
        public final Integer matchTypeId; // nullable
        public final Integer tableNumber; // nullable
        public final String matchTimestamp; // nullable

        public Match(int id, int roundId, Integer matchTypeId, Integer tableNumber, String matchTimestamp) {
            this.id = id;
            this.roundId = roundId;
            this.matchTypeId = matchTypeId;
            this.tableNumber = tableNumber;
            this.matchTimestamp = matchTimestamp;
        }
    }

    private Match mapRow(ResultSet rs) throws SQLException {
        int matchTypeId = rs.getInt("match_type_id");
        boolean matchTypeNull = rs.wasNull();
        int tableNumber = rs.getInt("table_number");
        boolean tableNumberNull = rs.wasNull();
        return new Match(
            rs.getInt("id"),
            rs.getInt("round_id"),
            matchTypeNull ? null : matchTypeId,
            tableNumberNull ? null : tableNumber,
            rs.getString("match_timestamp")
        );
    }

    public int createMatch(int roundId, Integer matchTypeId, Integer tableNumber, String matchTimestamp, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO matches (round_id, match_type_id, table_number, match_timestamp, created_dttm, updated_dttm) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, roundId);
            if (matchTypeId != null) ps.setInt(2, matchTypeId); else ps.setNull(2, Types.INTEGER);
            if (tableNumber != null) ps.setInt(3, tableNumber); else ps.setNull(3, Types.INTEGER);
            if (matchTimestamp != null) ps.setString(4, matchTimestamp); else ps.setNull(4, Types.VARCHAR);
            ps.setString(5, nowTimestamp);
            ps.setString(6, nowTimestamp);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public List<Match> getMatchesForRound(int roundId) throws SQLException {
        String sql = "SELECT * FROM matches WHERE round_id = ?";
        List<Match> matches = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches.add(mapRow(rs));
                }
            }
        }
        return matches;
    }

    /**
     * Returns the match_type_id already assigned to this round's matches
     * (from a previous upload/reprocess of the same round), or null if
     * none of its matches have one assigned yet.
     */
    public Integer getMatchTypeIdForRound(int roundId) throws SQLException {
        String sql = "SELECT match_type_id FROM matches WHERE round_id = ? AND match_type_id IS NOT NULL LIMIT 1";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /** The round_id a specific match belongs to, or null if the match_id doesn't exist. */
    public Integer getRoundIdForMatch(int matchId) throws SQLException {
        String sql = "SELECT round_id FROM matches WHERE id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /**
     * Deletes all matches for a round (cascades to match_participants and
     * ai_predictions via FK). Used when reprocessing a round - the round
     * row itself is kept (via A1_Rounds) so admin-set metadata survives.
     */
    public void deleteMatchesForRound(int roundId) throws SQLException {
        String sql = "DELETE FROM matches WHERE round_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.executeUpdate();
        }
    }

    public void updateMatchTimestamp(int matchId, String matchTimestamp, String nowTimestamp) throws SQLException {
        String sql = "UPDATE matches SET match_timestamp = ?, updated_dttm = ? WHERE id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, matchTimestamp);
            ps.setString(2, nowTimestamp);
            ps.setInt(3, matchId);
            ps.executeUpdate();
        }
    }

    /** Bulk-updates match_timestamp for every match in a round (keeps matches.match_timestamp in sync with rounds.round_datetime). */
    public void updateMatchTimestampForRound(int roundId, String matchTimestamp, String nowTimestamp) throws SQLException {
        String sql = "UPDATE matches SET match_timestamp = ?, updated_dttm = ? WHERE round_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, matchTimestamp);
            ps.setString(2, nowTimestamp);
            ps.setInt(3, roundId);
            ps.executeUpdate();
        }
    }

    /** Bulk-updates match_type_id for every match in a round. */
    public void updateMatchTypeForRound(int roundId, int matchTypeId, String nowTimestamp) throws SQLException {
        String sql = "UPDATE matches SET match_type_id = ?, updated_dttm = ? WHERE round_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchTypeId);
            ps.setString(2, nowTimestamp);
            ps.setInt(3, roundId);
            ps.executeUpdate();
        }
    }
}
