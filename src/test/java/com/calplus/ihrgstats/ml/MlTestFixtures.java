package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared fixture builder for the ML test suite. Seeds the core reference
 * data (halls, WLKOVR sentinel) and provides terse helpers to lay down
 * rounds and boards through the real DAOs - so extractor tests exercise
 * exactly the production read path.
 */
class MlTestFixtures {

    static final String NOW = "2026-01-01 00:00:00.000";

    final A1_Rounds rounds = new A1_Rounds();
    final A3_Halls halls = new A3_Halls();
    final B4_Players players = new B4_Players();
    final C8_Matches matches = new C8_Matches();
    final C9_MatchParticipants participants = new C9_MatchParticipants();

    int hallA;
    int hallB;

    /** Seeds halls + WLKOVR and captures two real (non-ZZ) hall ids. */
    void seedCore() throws SQLException {
        halls.seedDefaults(NOW);
        players.seedDefaults(NOW);
        List<Integer> realHalls = new ArrayList<>();
        for (A3_Halls.Hall hall : halls.getAllHalls()) {
            if (!A3_Halls.UNKNOWN_HALL_CODE.equals(hall.hallCode)) {
                realHalls.add(hall.id);
            }
        }
        if (realHalls.size() < 2) {
            throw new IllegalStateException("Fixture needs at least two real halls from seedDefaults");
        }
        hallA = realHalls.get(0);
        hallB = realHalls.get(1);
    }

    void createPlayers(String... playerIds) throws SQLException {
        for (String id : playerIds) {
            if (!players.playerExists(id)) {
                players.createPlayer(id, NOW);
            }
        }
    }

    int createRound(int year, int order) throws SQLException {
        return rounds.getOrCreateRound(year, order, NOW).id;
    }

    /** A standard rated board: p1 vs p2 with the given seats and p1-outcome. */
    int addBoard(int roundId, String p1, int hall1, Integer seat1,
                 String p2, int hall2, Integer seat2, double outcome1) throws SQLException {
        return addBoard(roundId, p1, hall1, seat1, C9_MatchParticipants.PARTICIPATION_STANDARD, outcome1,
                p2, hall2, seat2, C9_MatchParticipants.PARTICIPATION_STANDARD, 1.0 - outcome1);
    }

    /** Fully explicit board: both participation types and outcomes supplied. */
    int addBoard(int roundId, String p1, int hall1, Integer seat1, String type1, double outcome1,
                 String p2, int hall2, Integer seat2, String type2, double outcome2) throws SQLException {
        int matchId = matches.createMatch(roundId, null, null, null, NOW);
        participants.insertParticipant(matchId, p1, hall1, seat1, type1, outcome1 * 5.0, outcome1, NOW);
        participants.insertParticipant(matchId, p2, hall2, seat2, type2, outcome2 * 5.0, outcome2, NOW);
        return matchId;
    }

    /** A walkover board: the real player faced the WLKOVR sentinel. */
    int addWalkoverBoard(int roundId, String realPlayer, int realHall, Integer seat, int absentHall) throws SQLException {
        int matchId = matches.createMatch(roundId, null, null, null, NOW);
        participants.insertParticipant(matchId, realPlayer, realHall, seat,
                C9_MatchParticipants.PARTICIPATION_STANDARD, 3.0, 1.0, NOW);
        participants.insertParticipant(matchId, B4_Players.WALKOVER_PLAYER_ID, absentHall, null,
                C9_MatchParticipants.PARTICIPATION_WALKOVER, 2.0, 0.0, NOW);
        return matchId;
    }
}
