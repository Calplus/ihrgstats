package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;

/**
 * Data-access helper for the {@code ai_predictions} table.
 * Reserved for the future prediction engine - not populated or read by
 * any current feature.
 */
public class E14_AiPredictions {

    public static class Prediction {
        public final int matchId;
        public final String predictedWinnerPlayerId; // nullable
        public final double predictedWinProbability;
        public final String modelVersion;

        public Prediction(int matchId, String predictedWinnerPlayerId, double predictedWinProbability, String modelVersion) {
            this.matchId = matchId;
            this.predictedWinnerPlayerId = predictedWinnerPlayerId;
            this.predictedWinProbability = predictedWinProbability;
            this.modelVersion = modelVersion;
        }
    }

    public void insertPrediction(int matchId, String predictedWinnerPlayerId, double predictedWinProbability, String modelVersion, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO ai_predictions (match_id, predicted_winner_player_id, predicted_win_probability, model_version, created_dttm) " +
                "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId);
            ps.setString(2, predictedWinnerPlayerId);
            ps.setDouble(3, predictedWinProbability);
            ps.setString(4, modelVersion);
            ps.setString(5, nowTimestamp);
            ps.executeUpdate();
        }
    }

    public Prediction getPrediction(int matchId) throws SQLException {
        String sql = "SELECT * FROM ai_predictions WHERE match_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Prediction(rs.getInt("match_id"), rs.getString("predicted_winner_player_id"),
                        rs.getDouble("predicted_win_probability"), rs.getString("model_version"));
            }
        }
    }
}
