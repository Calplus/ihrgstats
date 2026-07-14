package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code player_ratings} table - one row per
 * (player, round, rating_type), replacing every trueEloR1..T2, rd..., vol...,
 * base... column from the legacy wide table.
 *
 * A row is written for EVERY player active in a given year for EVERY round
 * of that year, even if they personally sat out that specific round (as
 * long as their hall played) - this isn't wide-table legacy cruft, it's a
 * real Glicko-2 requirement: an inactive player's rating deviation must
 * still grow to reflect increasing uncertainty.
 */
public class D11_PlayerRatings {

    public static class Rating {
        public final String playerId;
        public final int roundId;
        public final int ratingTypeId;
        public final double ratingValue;
        public final double ratingDeviation;
        public final double volatility;

        public Rating(String playerId, int roundId, int ratingTypeId, double ratingValue, double ratingDeviation, double volatility) {
            this.playerId = playerId;
            this.roundId = roundId;
            this.ratingTypeId = ratingTypeId;
            this.ratingValue = ratingValue;
            this.ratingDeviation = ratingDeviation;
            this.volatility = volatility;
        }
    }

    private Rating mapRow(ResultSet rs) throws SQLException {
        return new Rating(
            rs.getString("player_id"),
            rs.getInt("round_id"),
            rs.getInt("rating_type_id"),
            rs.getDouble("rating_value"),
            rs.getDouble("rating_deviation"),
            rs.getDouble("volatility")
        );
    }

    public void insertRating(String playerId, int roundId, int ratingTypeId, double value, double rd, double volatility, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO player_ratings (player_id, round_id, rating_type_id, rating_value, rating_deviation, volatility, created_dttm, updated_dttm) " +
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

    public Rating getRating(String playerId, int roundId, int ratingTypeId) throws SQLException {
        String sql = "SELECT * FROM player_ratings WHERE player_id = ? AND round_id = ? AND rating_type_id = ?";
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
     * Returns a player's most recent rating from any year STRICTLY before
     * the given year (their starting point for a new year's Glicko-2
     * calculation), or null if they've never played before (caller should
     * fall back to default rating/RD/volatility in that case).
     */
    public Rating getLatestRatingBeforeYear(String playerId, int year, int ratingTypeId) throws SQLException {
        String sql = "SELECT pr.* FROM player_ratings pr " +
                "JOIN rounds r ON pr.round_id = r.id " +
                "WHERE pr.player_id = ? AND pr.rating_type_id = ? AND r.year < ? " +
                "ORDER BY r.year DESC, r.round_order DESC LIMIT 1";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, ratingTypeId);
            ps.setInt(3, year);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /**
     * Returns a player's single most recent rating across EVERY year -
     * backs the "All Years" ranking views. Ratings are already whole-history
     * cumulative regardless of year, so this is simply the chronologically
     * last row, not a recomputation.
     */
    public Rating getLatestRatingOverall(String playerId, int ratingTypeId) throws SQLException {
        String sql = "SELECT pr.* FROM player_ratings pr " +
                "JOIN rounds r ON pr.round_id = r.id " +
                "WHERE pr.player_id = ? AND pr.rating_type_id = ? " +
                "ORDER BY r.year DESC, r.round_order DESC LIMIT 1";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, ratingTypeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /**
     * Returns a player's most recent rating AT OR BEFORE a given round
     * within the SAME year (used for rankings "as of round N").
     */
    public Rating getLatestRatingUpToRound(String playerId, int year, int roundOrder, int ratingTypeId) throws SQLException {
        String sql = "SELECT pr.* FROM player_ratings pr " +
                "JOIN rounds r ON pr.round_id = r.id " +
                "WHERE pr.player_id = ? AND pr.rating_type_id = ? AND r.year = ? AND r.round_order <= ? " +
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

    /** All players' ratings for a specific round (used for rankings). */
    public List<Rating> getRatingsForRound(int roundId, int ratingTypeId) throws SQLException {
        String sql = "SELECT * FROM player_ratings WHERE round_id = ? AND rating_type_id = ?";
        List<Rating> ratings = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setInt(2, ratingTypeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ratings.add(mapRow(rs));
                }
            }
        }
        return ratings;
    }

    /** A player's full rating history for a rating type, ordered oldest-first. */
    public List<Rating> getRatingHistoryForPlayer(String playerId, int ratingTypeId) throws SQLException {
        String sql = "SELECT pr.* FROM player_ratings pr JOIN rounds r ON pr.round_id = r.id " +
                "WHERE pr.player_id = ? AND pr.rating_type_id = ? ORDER BY r.year ASC, r.round_order ASC";
        List<Rating> ratings = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, ratingTypeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ratings.add(mapRow(rs));
                }
            }
        }
        return ratings;
    }

    /** Deletes all rating rows for a round (used when reprocessing a round - kept separate from round row deletion). */
    public void deleteRatingsForRound(int roundId) throws SQLException {
        String sql = "DELETE FROM player_ratings WHERE round_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes one rating type's rows for a round. Used by the whole-history
     * recalculation so each recalculated round's stored rows exactly mirror
     * the recalculated player set (players dropped from the set don't leave
     * stale rows behind), without touching other rating types.
     */
    public void deleteRatingsForRoundAndType(int roundId, int ratingTypeId) throws SQLException {
        String sql = "DELETE FROM player_ratings WHERE round_id = ? AND rating_type_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setInt(2, ratingTypeId);
            ps.executeUpdate();
        }
    }
}
