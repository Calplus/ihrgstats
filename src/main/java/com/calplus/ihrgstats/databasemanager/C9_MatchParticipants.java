package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-access helper for the {@code match_participants} table - one row
 * per player per match (always exactly 2 rows per match, one of which may
 * be the WLKOVR sentinel for a walkover).
 */
public class C9_MatchParticipants {

    public static final String PARTICIPATION_STANDARD = "STANDARD";
    public static final String PARTICIPATION_WALKOVER = "WALKOVER";
    public static final String PARTICIPATION_TIMEOUT = "TIMEOUT";

    public static class Participant {
        public final int matchId;
        public final String playerId;
        public final int hallId;
        public final Integer hallSeatNumber; // nullable
        public final String participationType;
        public final double score;
        public final double outcome; // 1.0 win / 0.5 draw / 0.0 loss

        public Participant(int matchId, String playerId, int hallId, Integer hallSeatNumber,
                            String participationType, double score, double outcome) {
            this.matchId = matchId;
            this.playerId = playerId;
            this.hallId = hallId;
            this.hallSeatNumber = hallSeatNumber;
            this.participationType = participationType;
            this.score = score;
            this.outcome = outcome;
        }
    }

    private Participant mapRow(ResultSet rs) throws SQLException {
        int seat = rs.getInt("hall_seat_number");
        boolean seatNull = rs.wasNull();
        return new Participant(
            rs.getInt("match_id"),
            rs.getString("player_id"),
            rs.getInt("hall_id"),
            seatNull ? null : seat,
            rs.getString("participation_type"),
            rs.getDouble("score"),
            rs.getDouble("outcome")
        );
    }

    public void insertParticipant(int matchId, String playerId, int hallId, Integer hallSeatNumber,
                                   String participationType, double score, double outcome, String nowTimestamp) throws SQLException {
        String sql = "INSERT INTO match_participants " +
                "(match_id, player_id, hall_id, hall_seat_number, participation_type, score, outcome, created_dttm, updated_dttm) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId);
            ps.setString(2, playerId);
            ps.setInt(3, hallId);
            if (hallSeatNumber != null) ps.setInt(4, hallSeatNumber); else ps.setNull(4, Types.INTEGER);
            ps.setString(5, participationType);
            ps.setDouble(6, score);
            ps.setDouble(7, outcome);
            ps.setString(8, nowTimestamp);
            ps.setString(9, nowTimestamp);
            ps.executeUpdate();
        }
    }

    public List<Participant> getParticipantsForMatch(int matchId) throws SQLException {
        String sql = "SELECT * FROM match_participants WHERE match_id = ?";
        List<Participant> participants = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    participants.add(mapRow(rs));
                }
            }
        }
        return participants;
    }

    /** Returns the other participant in the same match (the opponent). */
    public Participant getOpponentParticipant(int matchId, String excludePlayerId) throws SQLException {
        String sql = "SELECT * FROM match_participants WHERE match_id = ? AND player_id != ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId);
            ps.setString(2, excludePlayerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /** All of a player's match_participants rows across all years, most recent round first. */
    public List<Participant> getParticipantsForPlayer(String playerId) throws SQLException {
        String sql = "SELECT mp.* FROM match_participants mp " +
                "JOIN matches m ON mp.match_id = m.id " +
                "JOIN rounds r ON m.round_id = r.id " +
                "WHERE mp.player_id = ? ORDER BY r.year DESC, r.round_order DESC";
        List<Participant> participants = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    participants.add(mapRow(rs));
                }
            }
        }
        return participants;
    }

    /** All of a hall's match_participants rows across all years, chronological (oldest first) - backs the lineup optimizer's opponent-captain seat-history model. */
    public List<Participant> getParticipantsForHall(int hallId) throws SQLException {
        String sql = "SELECT mp.* FROM match_participants mp " +
                "JOIN matches m ON mp.match_id = m.id " +
                "JOIN rounds r ON m.round_id = r.id " +
                "WHERE mp.hall_id = ? ORDER BY r.year ASC, r.round_order ASC";
        List<Participant> participants = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, hallId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    participants.add(mapRow(rs));
                }
            }
        }
        return participants;
    }

    /** A player's match_participants rows scoped to a single year, ordered by round ascending. */
    public List<Participant> getParticipantsForPlayerAndYear(String playerId, int year) throws SQLException {
        String sql = "SELECT mp.* FROM match_participants mp " +
                "JOIN matches m ON mp.match_id = m.id " +
                "JOIN rounds r ON m.round_id = r.id " +
                "WHERE mp.player_id = ? AND r.year = ? ORDER BY r.round_order ASC";
        List<Participant> participants = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    participants.add(mapRow(rs));
                }
            }
        }
        return participants;
    }

    /** A specific player's participant row for a specific round, if they played it. */
    public Participant getParticipantForPlayerAndRound(String playerId, int roundId) throws SQLException {
        String sql = "SELECT mp.* FROM match_participants mp JOIN matches m ON mp.match_id = m.id " +
                "WHERE mp.player_id = ? AND m.round_id = ?";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);
            ps.setInt(2, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /** All participants for every match in a round (used for hall-level round aggregation). */
    public List<Participant> getParticipantsForRound(int roundId) throws SQLException {
        String sql = "SELECT mp.* FROM match_participants mp JOIN matches m ON mp.match_id = m.id WHERE m.round_id = ?";
        List<Participant> participants = new ArrayList<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    participants.add(mapRow(rs));
                }
            }
        }
        return participants;
    }
}
