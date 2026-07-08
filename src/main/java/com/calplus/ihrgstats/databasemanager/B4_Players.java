package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code players} table - the permanent
 * cross-year identity registry. player_id is a 6-char business key
 * (2-char hall code of the player's ORIGINAL registering hall + 4-digit
 * zero-padded sequence, e.g. "040001"), generated via
 * {@link #generateNewPlayerId}. The hall code never changes even if the
 * player later transfers halls.
 */
public class B4_Players {

    /** Reserved sentinel player_id used for the absent side of a walkover. */
    public static final String WALKOVER_PLAYER_ID = "WLKOVR";

    private final A3_Halls halls = new A3_Halls();

    /** Seeds the WLKOVR sentinel row. Idempotent. */
    public void seedDefaults(String nowTimestamp) throws SQLException {
        if (!playerExists(WALKOVER_PLAYER_ID)) {
            createPlayer(WALKOVER_PLAYER_ID, nowTimestamp);
        }
    }

    public boolean playerExists(String playerId) throws SQLException {
        String sql = "SELECT 1 FROM players WHERE player_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void createPlayer(String playerId, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO players (player_id, created_dttm, updated_dttm) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setString(2, nowTimestamp);
            ps.setString(3, nowTimestamp);
            ps.executeUpdate();
        }
    }

    /**
     * Generates a brand-new permanent player_id for a player first
     * registering under the given hall code, and creates their row.
     */
    public String generateNewPlayerId(String hallCode, String nowTimestamp) throws SQLException {
        int seq = halls.getAndIncrementPlayerSeq(hallCode);
        String playerId = hallCode + String.format("%04d", seq);
        createPlayer(playerId, nowTimestamp);
        return playerId;
    }

    /** All real player_ids (excludes the WLKOVR sentinel). */
    public List<String> getAllPlayerIds() throws SQLException {
        String sql = "SELECT player_id FROM players WHERE player_id != ? ORDER BY player_id ASC";
        List<String> ids = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WALKOVER_PLAYER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("player_id"));
                }
            }
        }
        return ids;
    }
}
