package com.calplus.ihrgstats.corpus;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.utils.Constants;
import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared corpus integrity battery: structural invariants every properly
 * ingested season must satisfy, checked against the CURRENT default
 * database. Used by {@link CorpusIngestionTest} over the committed
 * fictional sample corpus.
 *
 * Deliberately queries via DAOs plus raw SQL orphan sweeps - it validates
 * what actually landed in the tables, not what the ingestion code claims
 * it wrote.
 */
public final class CorpusIntegrityChecks {

    private CorpusIntegrityChecks() {}

    /** Runs the full battery for one ingested year. */
    public static void runAll(int year) throws Exception {
        assertMatchStructure(year);
        assertOutcomeScoreConsistency(year);
        assertNoDuplicatePlayerPerRound(year);
        assertMaxPlayersPerHallPerRound(year);
        assertMaxRosterPerHallPerYear(year);
        assertPlayedImpliesRated(year);
        assertRatingRowsConsistent(year);
        assertYearStatusConsistency(year);
        assertNoOrphanRows();
    }

    /**
     * No hall may field more than {@link Constants.Validation#MAX_PLAYERS_PER_HALL}
     * real players in a single round - the stored data must never be in the
     * state the ingestion warning exists to flag.
     */
    public static void assertMaxPlayersPerHallPerRound(int year) throws SQLException {
        A1_Rounds rounds = new A1_Rounds();
        C9_MatchParticipants participants = new C9_MatchParticipants();
        for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
            Map<Integer, Integer> fieldedPerHall = new HashMap<>();
            for (C9_MatchParticipants.Participant p : participants.getParticipantsForRound(round.id)) {
                if (B4_Players.WALKOVER_PLAYER_ID.equals(p.playerId)) continue;
                fieldedPerHall.merge(p.hallId, 1, Integer::sum);
            }
            for (Map.Entry<Integer, Integer> e : fieldedPerHall.entrySet()) {
                assertTrue(e.getValue() <= Constants.Validation.MAX_PLAYERS_PER_HALL,
                        "Round " + round.roundOrder + ": hall id " + e.getKey() + " fielded " + e.getValue()
                                + " players, exceeding the max of " + Constants.Validation.MAX_PLAYERS_PER_HALL);
            }
        }
    }

    /** No hall may use more than 7 distinct real players across a whole year. */
    public static void assertMaxRosterPerHallPerYear(int year) throws SQLException {
        A1_Rounds rounds = new A1_Rounds();
        C9_MatchParticipants participants = new C9_MatchParticipants();
        Map<Integer, Set<String>> rosterPerHall = new HashMap<>();
        for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
            for (C9_MatchParticipants.Participant p : participants.getParticipantsForRound(round.id)) {
                if (B4_Players.WALKOVER_PLAYER_ID.equals(p.playerId)) continue;
                rosterPerHall.computeIfAbsent(p.hallId, k -> new HashSet<>()).add(p.playerId);
            }
        }
        for (Map.Entry<Integer, Set<String>> e : rosterPerHall.entrySet()) {
            assertTrue(e.getValue().size() <= 7,
                    "Year " + year + ": hall id " + e.getKey() + " used " + e.getValue().size()
                            + " distinct players, exceeding the yearly roster max of 7");
        }
    }

    /** Every match in every round of the year has exactly two participant rows. */
    public static void assertMatchStructure(int year) throws SQLException {
        A1_Rounds rounds = new A1_Rounds();
        C8_Matches matches = new C8_Matches();
        C9_MatchParticipants participants = new C9_MatchParticipants();
        for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
            List<C8_Matches.Match> roundMatches = matches.getMatchesForRound(round.id);
            assertFalse(roundMatches.isEmpty(), "Round " + round.roundOrder + " has no matches");
            for (C8_Matches.Match match : roundMatches) {
                assertEquals(2, participants.getParticipantsForMatch(match.id).size(),
                        "Match " + match.id + " in round " + round.roundOrder + " must have exactly 2 participants");
            }
        }
    }

    /**
     * Per-match outcome/score coherence, per participation type:
     * WALKOVER sentinel loses 0-vs-default to a STANDARD winner; a TIMEOUT
     * side always has score 0 and outcome 0 against an outcome-1 opponent;
     * a standard-vs-standard board's outcome pair must match the score
     * comparison (higher wins, equal is a 0.5/0.5 draw).
     */
    public static void assertOutcomeScoreConsistency(int year) throws SQLException {
        A1_Rounds rounds = new A1_Rounds();
        C8_Matches matches = new C8_Matches();
        C9_MatchParticipants participants = new C9_MatchParticipants();
        for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
            for (C8_Matches.Match match : matches.getMatchesForRound(round.id)) {
                List<C9_MatchParticipants.Participant> pair = participants.getParticipantsForMatch(match.id);
                C9_MatchParticipants.Participant a = pair.get(0);
                C9_MatchParticipants.Participant b = pair.get(1);
                String where = "match " + match.id + " (round " + round.roundOrder + ")";

                boolean legalPair = (a.outcome == 1.0 && b.outcome == 0.0)
                        || (a.outcome == 0.0 && b.outcome == 1.0)
                        || (a.outcome == 0.5 && b.outcome == 0.5);
                assertTrue(legalPair, where + ": illegal outcome pair " + a.outcome + "/" + b.outcome);

                for (C9_MatchParticipants.Participant p : pair) {
                    C9_MatchParticipants.Participant opp = (p == a) ? b : a;
                    if (C9_MatchParticipants.PARTICIPATION_WALKOVER.equals(p.participationType)) {
                        assertEquals(B4_Players.WALKOVER_PLAYER_ID, p.playerId,
                                where + ": WALKOVER participation must be the sentinel player");
                        assertEquals(0.0, p.outcome, where + ": walkover side must lose");
                        assertEquals(0.0, p.score, where + ": walkover side must score 0");
                        assertEquals(1.0, opp.outcome, where + ": walkover opponent must win");
                        assertEquals(C9_MatchParticipants.PARTICIPATION_STANDARD, opp.participationType,
                                where + ": walkover opponent must be a standard participant");
                    } else if (C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(p.participationType)) {
                        assertEquals(0.0, p.score, where + ": timed-out side must score 0");
                        assertEquals(0.0, p.outcome, where + ": timed-out side must lose");
                        assertEquals(1.0, opp.outcome, where + ": timeout opponent must win");
                    } else {
                        assertEquals(C9_MatchParticipants.PARTICIPATION_STANDARD, p.participationType,
                                where + ": unknown participation type " + p.participationType);
                    }
                }

                if (C9_MatchParticipants.PARTICIPATION_STANDARD.equals(a.participationType)
                        && C9_MatchParticipants.PARTICIPATION_STANDARD.equals(b.participationType)) {
                    if (a.score > b.score) {
                        assertEquals(1.0, a.outcome, where + ": higher score must win");
                    } else if (a.score < b.score) {
                        assertEquals(1.0, b.outcome, where + ": higher score must win");
                    } else {
                        assertEquals(0.5, a.outcome, where + ": equal scores must be a draw");
                    }
                }
            }
        }
    }

    /** No real player may appear more than once within a single round. */
    public static void assertNoDuplicatePlayerPerRound(int year) throws SQLException {
        A1_Rounds rounds = new A1_Rounds();
        C9_MatchParticipants participants = new C9_MatchParticipants();
        for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
            Set<String> seen = new HashSet<>();
            for (C9_MatchParticipants.Participant p : participants.getParticipantsForRound(round.id)) {
                if (B4_Players.WALKOVER_PLAYER_ID.equals(p.playerId)) continue;
                assertTrue(seen.add(p.playerId),
                        "Player " + p.playerId + " appears twice in round " + round.roundOrder);
            }
        }
    }

    /**
     * Every real participant of a round must have a TrueElo rating row for
     * that round (players who played are always in the rated set), and if
     * ExpElo rows exist for a round they must cover exactly the same
     * players as the TrueElo rows (distiller parity).
     */
    public static void assertPlayedImpliesRated(int year) throws SQLException {
        A1_Rounds rounds = new A1_Rounds();
        C9_MatchParticipants participants = new C9_MatchParticipants();
        D11_PlayerRatings ratings = new D11_PlayerRatings();
        D10_RatingTypes ratingTypes = new D10_RatingTypes();
        int trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        int expEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO);

        for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
            Set<String> trueElo = new HashSet<>();
            for (D11_PlayerRatings.Rating r : ratings.getRatingsForRound(round.id, trueEloTypeId)) {
                trueElo.add(r.playerId);
            }
            for (C9_MatchParticipants.Participant p : participants.getParticipantsForRound(round.id)) {
                if (B4_Players.WALKOVER_PLAYER_ID.equals(p.playerId)) continue;
                assertTrue(trueElo.contains(p.playerId),
                        "Player " + p.playerId + " played round " + round.roundOrder + " but has no TrueElo row");
            }
            Set<String> expElo = new HashSet<>();
            for (D11_PlayerRatings.Rating r : ratings.getRatingsForRound(round.id, expEloTypeId)) {
                expElo.add(r.playerId);
            }
            if (!expElo.isEmpty()) {
                assertEquals(trueElo, expElo,
                        "Round " + round.roundOrder + ": ExpElo rows must cover exactly the TrueElo rated set");
            }
        }
    }

    /** All rating rows of the year's rounds hold finite, sane Glicko values. */
    public static void assertRatingRowsConsistent(int year) throws SQLException {
        A1_Rounds rounds = new A1_Rounds();
        D11_PlayerRatings ratings = new D11_PlayerRatings();
        D10_RatingTypes ratingTypes = new D10_RatingTypes();
        int[] typeIds = {
                ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO),
                ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO)
        };
        for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
            for (int typeId : typeIds) {
                for (D11_PlayerRatings.Rating r : ratings.getRatingsForRound(round.id, typeId)) {
                    String where = "rating row " + r.playerId + "/round " + round.roundOrder;
                    assertTrue(Double.isFinite(r.ratingValue) && r.ratingValue > 0, where + ": bad rating " + r.ratingValue);
                    assertTrue(Double.isFinite(r.ratingDeviation) && r.ratingDeviation > 0, where + ": bad RD " + r.ratingDeviation);
                    assertTrue(Double.isFinite(r.volatility) && r.volatility > 0, where + ": bad volatility " + r.volatility);
                }
            }
        }
    }

    /**
     * Every real participant has a player_year_status row for the year,
     * and the hall stored on the participant row matches that status hall
     * (identity resolution guarantees a single hall per player per year).
     */
    public static void assertYearStatusConsistency(int year) throws SQLException {
        A1_Rounds rounds = new A1_Rounds();
        C9_MatchParticipants participants = new C9_MatchParticipants();
        B6_PlayerYearStatus statuses = new B6_PlayerYearStatus();
        Map<String, B6_PlayerYearStatus.Status> cache = new HashMap<>();
        for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
            for (C9_MatchParticipants.Participant p : participants.getParticipantsForRound(round.id)) {
                if (B4_Players.WALKOVER_PLAYER_ID.equals(p.playerId)) continue;
                B6_PlayerYearStatus.Status status = cache.computeIfAbsent(p.playerId, id -> {
                    try {
                        return statuses.getStatus(id, year);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
                assertNotNull(status, "Player " + p.playerId + " played in " + year + " but has no year status row");
                assertEquals(status.hallId, p.hallId,
                        "Player " + p.playerId + " round " + round.roundOrder
                                + ": participant hall differs from the year-status hall");
            }
        }
    }

    /** Raw-SQL foreign-key sweeps: no orphan rows anywhere in the corpus tables. */
    public static void assertNoOrphanRows() throws SQLException {
        assertOrphanCount("match_participants without match",
                "SELECT COUNT(*) FROM match_participants mp LEFT JOIN matches m ON m.id = mp.match_id WHERE m.id IS NULL");
        assertOrphanCount("match_participants without player",
                "SELECT COUNT(*) FROM match_participants mp LEFT JOIN players p ON p.player_id = mp.player_id WHERE p.player_id IS NULL");
        assertOrphanCount("matches without round",
                "SELECT COUNT(*) FROM matches m LEFT JOIN rounds r ON r.id = m.round_id WHERE r.id IS NULL");
        assertOrphanCount("player_ratings without round",
                "SELECT COUNT(*) FROM player_ratings pr LEFT JOIN rounds r ON r.id = pr.round_id WHERE r.id IS NULL");
        assertOrphanCount("player_ratings without player",
                "SELECT COUNT(*) FROM player_ratings pr LEFT JOIN players p ON p.player_id = pr.player_id WHERE p.player_id IS NULL");
        assertOrphanCount("player_ratings without rating type",
                "SELECT COUNT(*) FROM player_ratings pr LEFT JOIN rating_types rt ON rt.id = pr.rating_type_id WHERE rt.id IS NULL");
        assertOrphanCount("player_names without player",
                "SELECT COUNT(*) FROM player_names pn LEFT JOIN players p ON p.player_id = pn.player_id WHERE p.player_id IS NULL");
        assertOrphanCount("player_year_status without player",
                "SELECT COUNT(*) FROM player_year_status pys LEFT JOIN players p ON p.player_id = pys.player_id WHERE p.player_id IS NULL");
        assertOrphanCount("player_year_status without hall",
                "SELECT COUNT(*) FROM player_year_status pys LEFT JOIN halls h ON h.id = pys.hall_id WHERE h.id IS NULL");
        assertOrphanCount("mapped capped_imports without player",
                "SELECT COUNT(*) FROM capped_imports ci LEFT JOIN players p ON p.player_id = ci.player_id "
                        + "WHERE ci.player_id IS NOT NULL AND p.player_id IS NULL");
        assertOrphanCount("rating snapshots without round",
                "SELECT COUNT(*) FROM player_ratings_snapshot prs LEFT JOIN rounds r ON r.id = prs.round_id WHERE r.id IS NULL");
    }

    private static void assertOrphanCount(String description, String sql) throws SQLException {
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Orphan rows found: " + description);
        }
    }
}
