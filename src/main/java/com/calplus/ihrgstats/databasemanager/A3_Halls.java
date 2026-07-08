package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-access helper for the {@code halls} table.
 *
 * IMPORTANT: hall_name stores the RAW canonical form used in uploaded CSVs
 * and throughout the rest of the app (e.g. "4", "Banyan") - NOT a display
 * form like "Hall 4"/"Binjai Hall" (that transformation happens at display
 * time in VictoryRecordCalculator/TableFormatter). Numeric halls are
 * UNPADDED ("1".."16") to match raw CSV values, which is different from
 * hall_code (zero-padded "01".."16", used only for player_id generation).
 */
public class A3_Halls {

    /** Reserved hall for match_participants rows where the CSV left the
     *  (optional) walkover-side hall blank. Matches the existing
     *  halls/unknown.png icon convention. */
    public static final String UNKNOWN_HALL_CODE = "ZZ";
    public static final String UNKNOWN_HALL_NAME = "unknown";

    public static class Hall {
        public final int id;
        public final String hallCode;
        public final String hallName;
        public final int nextPlayerSeq;

        public Hall(int id, String hallCode, String hallName, int nextPlayerSeq) {
            this.id = id;
            this.hallCode = hallCode;
            this.hallName = hallName;
            this.nextPlayerSeq = nextPlayerSeq;
        }
    }

    private Hall mapRow(ResultSet rs) throws SQLException {
        return new Hall(rs.getInt("id"), rs.getString("hall_code"), rs.getString("hall_name"), rs.getInt("next_player_seq"));
    }

    /**
     * Seeds the 16 numeric halls (1-16), the 7 named halls, and the
     * reserved "unknown" fallback hall. Idempotent - does nothing for
     * halls that already exist.
     */
    public void seedDefaults(String nowTimestamp) throws SQLException {
        Map<String, String> seeds = new LinkedHashMap<>(); // hallCode -> hallName
        for (int i = 1; i <= 16; i++) {
            seeds.put(String.format("%02d", i), String.valueOf(i));
        }
        seeds.put("BY", "Banyan");
        seeds.put("BJ", "Binjai");
        seeds.put("CS", "Crescent");
        seeds.put("PR", "Pioneer");
        seeds.put("SC", "Saraca");
        seeds.put("TM", "Tamarind");
        seeds.put("TJ", "Tanjong");
        seeds.put(UNKNOWN_HALL_CODE, UNKNOWN_HALL_NAME);

        String insertSql = "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) " +
                "SELECT ?, ?, 1, ?, ? WHERE NOT EXISTS (SELECT 1 FROM halls WHERE hall_code = ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (Map.Entry<String, String> entry : seeds.entrySet()) {
                ps.setString(1, entry.getKey());
                ps.setString(2, entry.getValue());
                ps.setString(3, nowTimestamp);
                ps.setString(4, nowTimestamp);
                ps.setString(5, entry.getKey());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public Hall getHallByCode(String hallCode) throws SQLException {
        String sql = "SELECT * FROM halls WHERE hall_code = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hallCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /**
     * Looks up a hall by its raw name as it appears in uploaded CSVs
     * (case-insensitive, e.g. "4", "banyan", "BANYAN" all match).
     */
    public Hall getHallByName(String hallName) throws SQLException {
        String sql = "SELECT * FROM halls WHERE LOWER(hall_name) = LOWER(?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hallName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public Hall getHallById(int id) throws SQLException {
        String sql = "SELECT * FROM halls WHERE id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public List<Hall> getAllHalls() throws SQLException {
        String sql = "SELECT * FROM halls ORDER BY hall_name ASC";
        List<Hall> halls = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                halls.add(mapRow(rs));
            }
        }
        return halls;
    }

    /**
     * Atomically increments and returns the NEXT player sequence number
     * for a hall (the value to use for a newly-generated player_id, before
     * incrementing). Uses SQLite's RETURNING clause so the read-then-write
     * is a single atomic statement, avoiding gaps/races.
     */
    public int getAndIncrementPlayerSeq(String hallCode) throws SQLException {
        String sql = "UPDATE halls SET next_player_seq = next_player_seq + 1 WHERE hall_code = ? RETURNING next_player_seq - 1 AS seq";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hallCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Hall not found: " + hallCode);
                }
                return rs.getInt("seq");
            }
        }
    }
}
