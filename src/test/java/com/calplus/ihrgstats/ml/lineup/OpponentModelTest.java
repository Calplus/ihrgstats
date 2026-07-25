package com.calplus.ihrgstats.ml.lineup;

import com.calplus.ihrgstats.databasemanager.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed tests for {@link OpponentModel}: a captain who fields the
 * exact same seat order every round must have that order dominate the
 * top-K support with low ordering entropy, while a captain who visibly
 * varies their order must show materially higher entropy.
 */
public class OpponentModelTest {

    private static final String NOW = "2026-01-01 00:00:00.000";

    private String originalUserDir;
    private final A1_Rounds rounds = new A1_Rounds();
    private final C8_Matches matches = new C8_Matches();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final B4_Players players = new B4_Players();
    private int ourHallId;
    private int opponentHallId;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);

        List<A3_Halls.Hall> realHalls = new A3_Halls().getAllHalls().stream()
                .filter(h -> !A3_Halls.UNKNOWN_HALL_CODE.equals(h.hallCode)).toList();
        ourHallId = realHalls.get(0).id;
        opponentHallId = realHalls.get(1).id;
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    private void createPlayer(String id) throws SQLException {
        if (!players.playerExists(id)) {
            players.createPlayer(id, NOW);
        }
    }

    /** One round: the given hall fields players[i] at seat i+1, each facing a distinct opponent-hall player. */
    private void fieldRound(int order, int hallId, String[] players_, int opponentHall, String[] opponentPlayers) throws SQLException {
        int roundId = rounds.getOrCreateRound(2025, order, NOW).id;
        for (int seat = 0; seat < players_.length; seat++) {
            createPlayer(players_[seat]);
            createPlayer(opponentPlayers[seat]);
            int matchId = matches.createMatch(roundId, null, null, null, NOW);
            participants.insertParticipant(matchId, players_[seat], hallId, seat + 1, C9_MatchParticipants.PARTICIPATION_STANDARD, 1.0, 1.0, NOW);
            participants.insertParticipant(matchId, opponentPlayers[seat], opponentHall, seat + 1, C9_MatchParticipants.PARTICIPATION_STANDARD, 0.0, 0.0, NOW);
        }
    }

    @Test
    void fixedOrderCaptain_dominatesTopK_withLowEntropy() throws Exception {
        String[] fixedOrder = {"F1", "F2", "F3", "F4", "F5"};
        String[] opp = {"X1", "X2", "X3", "X4", "X5"};
        for (int round = 1; round <= 8; round++) {
            fieldRound(round, ourHallId, fixedOrder, opponentHallId, opp);
        }

        OpponentModel model = new OpponentModel();
        OpponentModel.Profile profile = model.buildProfile(ourHallId);

        assertTrue(profile.hasHistory());
        assertEquals(5, profile.expectedRoster.size());
        assertTrue(new java.util.HashSet<>(profile.expectedRoster).containsAll(List.of(fixedOrder)));

        assertFalse(profile.topOrderings.isEmpty());
        OpponentModel.Ordering top = profile.topOrderings.get(0);
        assertEquals(List.of(fixedOrder), top.playerIdsBySeat, "the one order this captain has ever used must be the top prediction");
        assertTrue(top.probability > 0.9, "a perfectly consistent captain's actual order should dominate the top-K, got " + top.probability);

        assertTrue(profile.captainProfile.meanSeatEntropyBits < 0.3,
                "a perfectly fixed seating captain should have near-zero entropy, got " + profile.captainProfile.meanSeatEntropyBits);
    }

    @Test
    void variableOrderCaptain_showsHigherEntropyThanFixedOrderCaptain() throws Exception {
        String[] order1 = {"V1", "V2", "V3", "V4", "V5"};
        String[] order2 = {"V5", "V4", "V3", "V2", "V1"};
        String[] order3 = {"V3", "V1", "V5", "V2", "V4"};
        String[] opp = {"Y1", "Y2", "Y3", "Y4", "Y5"};

        fieldRound(1, ourHallId, order1, opponentHallId, opp);
        fieldRound(2, ourHallId, order2, opponentHallId, opp);
        fieldRound(3, ourHallId, order3, opponentHallId, opp);
        fieldRound(4, ourHallId, order1, opponentHallId, opp);
        fieldRound(5, ourHallId, order2, opponentHallId, opp);
        fieldRound(6, ourHallId, order3, opponentHallId, opp);

        String[] fixedOrder = {"F1", "F2", "F3", "F4", "F5"};
        for (int round = 1; round <= 6; round++) {
            fieldRound(round, opponentHallId, fixedOrder, ourHallId, new String[]{"Z1", "Z2", "Z3", "Z4", "Z5"});
        }

        OpponentModel model = new OpponentModel();
        OpponentModel.Profile variableProfile = model.buildProfile(ourHallId);
        OpponentModel.Profile fixedProfile = model.buildProfile(opponentHallId);

        assertTrue(variableProfile.captainProfile.meanSeatEntropyBits > fixedProfile.captainProfile.meanSeatEntropyBits,
                String.format("variable captain entropy (%.3f) should exceed fixed captain entropy (%.3f)",
                        variableProfile.captainProfile.meanSeatEntropyBits, fixedProfile.captainProfile.meanSeatEntropyBits));
    }

    @Test
    void noHistory_returnsEmptyProfile_notAnError() throws Exception {
        OpponentModel model = new OpponentModel();
        OpponentModel.Profile profile = model.buildProfile(ourHallId);
        assertFalse(profile.hasHistory());
        assertTrue(profile.expectedRoster.isEmpty());
        assertTrue(profile.topOrderings.isEmpty());
    }
}
