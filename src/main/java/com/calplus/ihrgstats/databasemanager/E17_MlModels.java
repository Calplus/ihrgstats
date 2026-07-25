package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code ml_models} table - the registry of
 * trained matchup-model runs (Segment A of the AI/ML plan). One row per
 * training run: serialized parameters ({@code params_json}) plus the
 * walk-forward backtest metrics that scored it ({@code metrics_json}).
 * At most one row carries {@code is_champion} = 1: the model served to
 * prediction/lineup features. The plain Glicko baseline is persisted as
 * a run too (family GLICKO_BASELINE), so "no model beats Glicko yet" is
 * a recorded outcome rather than an absence of rows.
 */
public class E17_MlModels {

    public static final String FAMILY_GLICKO_BASELINE = "GLICKO_BASELINE";
    public static final String FAMILY_LOGISTIC = "LOGISTIC";
    public static final String FAMILY_GBM = "GBM";
    public static final String FAMILY_GBM_EMB = "GBM_EMB";

    public static class MlModel {
        public final int id;
        public final String modelVersion;
        public final String family;
        public final String paramsJson;
        public final String metricsJson;
        public final int trainedBoards;
        public final boolean isChampion;
        public final String createdDttm;

        public MlModel(int id, String modelVersion, String family, String paramsJson,
                       String metricsJson, int trainedBoards, boolean isChampion, String createdDttm) {
            this.id = id;
            this.modelVersion = modelVersion;
            this.family = family;
            this.paramsJson = paramsJson;
            this.metricsJson = metricsJson;
            this.trainedBoards = trainedBoards;
            this.isChampion = isChampion;
            this.createdDttm = createdDttm;
        }
    }

    private MlModel mapRow(ResultSet rs) throws SQLException {
        return new MlModel(
            rs.getInt("id"),
            rs.getString("model_version"),
            rs.getString("family"),
            rs.getString("params_json"),
            rs.getString("metrics_json"),
            rs.getInt("trained_boards"),
            rs.getBoolean("is_champion"),
            rs.getString("created_dttm")
        );
    }

    /** Inserts a run, or refreshes an existing one with the same version (deterministic retrains reproduce versions). */
    public void upsertModel(String modelVersion, String family, String paramsJson, String metricsJson,
                            int trainedBoards, boolean isChampion, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO ml_models (model_version, family, params_json, metrics_json, trained_boards, is_champion, created_dttm, updated_dttm) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(model_version) DO UPDATE SET family = excluded.family, params_json = excluded.params_json, " +
                "metrics_json = excluded.metrics_json, trained_boards = excluded.trained_boards, " +
                "is_champion = excluded.is_champion, updated_dttm = excluded.updated_dttm";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, modelVersion);
            ps.setString(2, family);
            ps.setString(3, paramsJson);
            ps.setString(4, metricsJson);
            ps.setInt(5, trainedBoards);
            ps.setBoolean(6, isChampion);
            ps.setString(7, nowTimestamp);
            ps.setString(8, nowTimestamp);
            ps.executeUpdate();
        }
    }

    /** Clears every champion flag - called by the trainer right before crowning the new champion. */
    public void clearChampionFlags(String nowTimestamp) throws SQLException {
        String sql = "UPDATE ml_models SET is_champion = 0, updated_dttm = ? WHERE is_champion = 1";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nowTimestamp);
            ps.executeUpdate();
        }
    }

    /** The currently served model, or null if no training run has ever completed. */
    public MlModel getChampion() throws SQLException {
        String sql = "SELECT * FROM ml_models WHERE is_champion = 1 ORDER BY id DESC LIMIT 1";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? mapRow(rs) : null;
        }
    }

    public MlModel getByVersion(String modelVersion) throws SQLException {
        String sql = "SELECT * FROM ml_models WHERE model_version = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, modelVersion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /** Most recent runs, newest first (leaderboard views). */
    public List<MlModel> getRecent(int limit) throws SQLException {
        String sql = "SELECT * FROM ml_models ORDER BY id DESC LIMIT ?";
        List<MlModel> models = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    models.add(mapRow(rs));
                }
            }
        }
        return models;
    }
}
