package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code player_ratings_snapshot} table - the
 * immutable point-in-time record of what each player's rating was for a
 * round AS COMPUTED WHEN THAT ROUND WAS ORIGINALLY PROCESSED.
 *
 * Unlike {@code player_ratings} (which the whole-history recalculation
 * rewrites across all rounds/years whenever new results arrive), snapshot
 * rows are never touched by recalculation. A round's snapshots are
 * replaced only when that round itself is re-uploaded/reprocessed (the
 * underlying match data changed). "Rankings as of round N" queries read
 * this table so historical published standings stay reproducible.
 */
public class D15_PlayerRatingSnapshots {

    public static class Snapshot {
        public final String playerId;
        public final int roundId;
        public final int ratingTypeId;
        public final double ratingValue;
        public final double ratingDeviation;
        public final double volatility;

        public Snapshot(String playerId, int roundId, int ratingTypeId, double ratingValue, double ratingDeviation, double volatility) {
            this.playerId = playerId;
            this.roundId = roundId;
            this.ratingTypeId = ratingTypeId;
            this.ratingValue = ratingValue;
            this.ratingDeviation = ratingDeviation;
            this.volatility = volatility;
        }
    }

    private Snapshot mapRow(ResultSet rs) throws SQLException {
        return new Snapshot(
            rs.getString("player_id"),
            rs.getInt("round_id"),
            rs.getInt("rating_type_id"),
            rs.getDouble("rating_value"),
            rs.getDouble("rating_deviation"),
            rs.getDouble("volatility")
        );
    }

    public void insertSnapshot(String playerId, int roundId, int ratingTypeId, double value, double rd, double volatility, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO player_ratings_snapshot (player_id, round_id, rating_type_id, rating_value, rating_deviation, volatility, created_dttm, updated_dttm) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(player_id, round_id, rating_type_id) DO UPDATE SET " +
                "rating_value = excluded.rating_value, rating_deviation = excluded.rating_deviation, " +
                "volatility = excluded.volatility, updated_dttm = excluded.updated_dttm";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, roundId);
            ps.setInt(3, ratingTypeId);
            ps.setDouble(4, value);
            ps.setDouble(5, rd);
            ps.setDouble(6, volatility);
            ps.setString(7, nowTimestamp);
            ps.setString(8, nowTimestamp);
            ps.executeUpdate();
        }
    }

    public Snapshot getSnapshot(String playerId, int roundId, int ratingTypeId) throws SQLException {
        String sql = "SELECT * FROM player_ratings_snapshot WHERE player_id = ? AND round_id = ? AND rating_type_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, roundId);
            ps.setInt(3, ratingTypeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /**
     * A player's most recent snapshot AT OR BEFORE a given round within the
     * SAME year - the point-in-time analogue of
     * {@link D11_PlayerRatings#getLatestRatingUpToRound}.
     */
    public Snapshot getLatestSnapshotUpToRound(String playerId, int year, int roundOrder, int ratingTypeId) throws SQLException {
        String sql = "SELECT prs.* FROM player_ratings_snapshot prs " +
                "JOIN rounds r ON prs.round_id = r.id " +
                "WHERE prs.player_id = ? AND prs.rating_type_id = ? AND r.year = ? AND r.round_order <= ? " +
                "ORDER BY r.round_order DESC LIMIT 1";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, ratingTypeId);
            ps.setInt(3, year);
            ps.setInt(4, roundOrder);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /** All players' snapshots for a specific round (used for "as of round N" rankings). */
    public List<Snapshot> getSnapshotsForRound(int roundId, int ratingTypeId) throws SQLException {
        String sql = "SELECT * FROM player_ratings_snapshot WHERE round_id = ? AND rating_type_id = ?";
        List<Snapshot> snapshots = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setInt(2, ratingTypeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    snapshots.add(mapRow(rs));
                }
            }
        }
        return snapshots;
    }

    /**
     * One-time migration safety net: copies player_ratings rows into the
     * snapshot table for every round that has ratings but NO snapshot rows
     * yet. Rounds processed before the snapshot feature existed get their
     * currently-stored (as-published) values preserved as their point-in-time
     * record BEFORE the first whole-history recalculation rewrites them.
     * Idempotent - rounds that already have any snapshot row are untouched.
     *
     * @return number of snapshot rows inserted
     */
    public int backfillMissingRounds(String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO player_ratings_snapshot " +
                "(player_id, round_id, rating_type_id, rating_value, rating_deviation, volatility, created_dttm, updated_dttm) " +
                "SELECT pr.player_id, pr.round_id, pr.rating_type_id, pr.rating_value, pr.rating_deviation, pr.volatility, ?, ? " +
                "FROM player_ratings pr " +
                "WHERE pr.round_id NOT IN (SELECT DISTINCT round_id FROM player_ratings_snapshot)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nowTimestamp);
            ps.setString(2, nowTimestamp);
            return ps.executeUpdate();
        }
    }

    /** Deletes a round's snapshots (used only when that round itself is reprocessed). */
    public void deleteSnapshotsForRound(int roundId) throws SQLException {
        String sql = "DELETE FROM player_ratings_snapshot WHERE round_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.executeUpdate();
        }
    }
}
