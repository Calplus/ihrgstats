package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code rounds} table.
 * One row per (year, round_order). Thin CRUD only - no business logic,
 * no admin-facing logging (that belongs to the orchestration layer in
 * telegrambot/utils).
 */
public class A1_Rounds {

    /** Plain data holder for a rounds row. */
    public static class Round {
        public final int id;
        public final int year;
        public final int roundOrder;
        public final String roundLabel;
        public final String roundDatetime; // nullable
        public final String createdDttm;
        public final String updatedDttm;

        public Round(int id, int year, int roundOrder, String roundLabel, String roundDatetime,
                     String createdDttm, String updatedDttm) {
            this.id = id;
            this.year = year;
            this.roundOrder = roundOrder;
            this.roundLabel = roundLabel;
            this.roundDatetime = roundDatetime;
            this.createdDttm = createdDttm;
            this.updatedDttm = updatedDttm;
        }
    }

    private Round mapRow(ResultSet rs) throws SQLException {
        return new Round(
            rs.getInt("id"),
            rs.getInt("year"),
            rs.getInt("round_order"),
            rs.getString("round_label"),
            rs.getString("round_datetime"),
            rs.getString("created_dttm"),
            rs.getString("updated_dttm")
        );
    }

    /** Returns the round row for (year, roundOrder), or null if it doesn't exist. */
    public Round getRoundByYearAndOrder(int year, int roundOrder) throws SQLException {
        String sql = "SELECT * FROM rounds WHERE year = ? AND round_order = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.setInt(2, roundOrder);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public Round getRoundById(int roundId) throws SQLException {
        String sql = "SELECT * FROM rounds WHERE id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /**
     * Returns the round row for (year, roundOrder), creating it with default
     * metadata (round_label="Round {N}", round_datetime=NULL) if it doesn't
     * exist yet. Metadata of an already-existing round is left untouched -
     * this is what lets admin-set round_datetime/match_type survive a
     * re-upload/reprocess of the same round.
     */
    public Round getOrCreateRound(int year, int roundOrder, String nowTimestamp) throws SQLException {
        Round existing = getRoundByYearAndOrder(year, roundOrder);
        if (existing != null) {
            return existing;
        }
        String insertSql = "INSERT INTO rounds (year, round_order, round_label, round_datetime, created_dttm, updated_dttm) " +
                "VALUES (?, ?, ?, NULL, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setInt(1, year);
            ps.setInt(2, roundOrder);
            ps.setString(3, "Round " + roundOrder);
            ps.setString(4, nowTimestamp);
            ps.setString(5, nowTimestamp);
            ps.executeUpdate();
        }
        return getRoundByYearAndOrder(year, roundOrder);
    }

    /**
     * Updates round_label/round_datetime for an existing round. Pass null
     * for a field to leave it unchanged.
     */
    public void updateRoundMetadata(int roundId, String roundLabel, String roundDatetime, String nowTimestamp) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE rounds SET updated_dttm = ?");
        List<Object> params = new ArrayList<>();
        params.add(nowTimestamp);
        if (roundLabel != null) {
            sql.append(", round_label = ?");
            params.add(roundLabel);
        }
        if (roundDatetime != null) {
            sql.append(", round_datetime = ?");
            params.add(roundDatetime);
        }
        sql.append(" WHERE id = ?");
        params.add(roundId);

        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
        }
    }

    /** Returns the highest round_order processed for a given year, or 0 if none exist yet. */
    public int getLatestRoundOrder(int year) throws SQLException {
        String sql = "SELECT MAX(round_order) AS maxOrder FROM rounds WHERE year = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int maxOrder = rs.getInt("maxOrder");
                    return rs.wasNull() ? 0 : maxOrder;
                }
                return 0;
            }
        }
    }

    /** Returns all rounds for a year, ordered by round_order ascending. */
    public List<Round> getRoundsForYear(int year) throws SQLException {
        String sql = "SELECT * FROM rounds WHERE year = ? ORDER BY round_order ASC";
        List<Round> rounds = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rounds.add(mapRow(rs));
                }
            }
        }
        return rounds;
    }

    /** Returns all distinct years that have at least one round. */
    public List<Integer> getAllYears() throws SQLException {
        String sql = "SELECT DISTINCT year FROM rounds ORDER BY year ASC";
        List<Integer> years = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                years.add(rs.getInt("year"));
            }
        }
        return years;
    }

    /**
     * Deletes all rounds for a year with round_order STRICTLY greater than
     * fromOrderExclusive. Cascades (via FK ON DELETE CASCADE) to matches,
     * match_participants, and player_ratings for those rounds. Used by the
     * re-upload/reprocess confirmation flow - the round being reprocessed
     * itself is NOT deleted here (its own matches/ratings are cleared
     * separately via C8_Matches/D11_PlayerRatings so its admin-set metadata
     * survives).
     */
    public void deleteFutureRounds(int year, int fromOrderExclusive) throws SQLException {
        String sql = "DELETE FROM rounds WHERE year = ? AND round_order > ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.setInt(2, fromOrderExclusive);
            ps.executeUpdate();
        }
    }
}
