package com.calplus.ihrgstats.calculations;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JUnit 5 tests for {@link EloCalculator#calculateGlicko2TrueElo} and
 * {@link EloCalculator#calculateWholeHistory}.
 *
 * calculateGlicko2TrueElo is a single forward pass (exactly what the live
 * round-upload path calls, once per upload); calculateWholeHistory adds the
 * WHR-style multi-pass refinement used by the whole-history recalculation.
 */
public class EloCalculationTest {

    private static final double DEFAULT_RD = 350.0;

    private static Map<String, EloCalculator.Glicko2Rating> defaultRatings(Set<String> players) {
        Map<String, EloCalculator.Glicko2Rating> ratings = new HashMap<>();
        for (String player : players) {
            ratings.put(player, new EloCalculator.Glicko2Rating());
        }
        return ratings;
    }

    @Test
    void twoPlayers_winnerEndsUpWithHigherRatingThanLoser() {
        Set<String> players = new HashSet<>(Arrays.asList("player_a", "player_b"));

        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("player_a", "player_b", 1.0, 1));
        games.add(new EloCalculator.Game("player_a", "player_b", 1.0, 2));

        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
                games, players, defaultRatings(players), Arrays.asList(1, 2));

        double aFinal = result.ratingsByRound.get(2).get("player_a").rating;
        double bFinal = result.ratingsByRound.get(2).get("player_b").rating;

        assertTrue(aFinal > bFinal, "Player A (2 wins) should end up rated higher than Player B (2 losses)");
    }

    @Test
    void fourPlayers_undefeatedPlayerHasHighestRating_winlessPlayerHasLowest() {
        Set<String> players = new HashSet<>(Arrays.asList("a", "b", "c", "d"));

        // Round 1: a beats b, c beats d
        // Round 2: a beats c, b beats d
        // Round 3: a beats d, b beats c
        // -> "a" wins all 3 games, "d" loses all 3 games
        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("a", "b", 1.0, 1));
        games.add(new EloCalculator.Game("c", "d", 1.0, 1));
        games.add(new EloCalculator.Game("a", "c", 1.0, 2));
        games.add(new EloCalculator.Game("b", "d", 1.0, 2));
        games.add(new EloCalculator.Game("a", "d", 1.0, 3));
        games.add(new EloCalculator.Game("b", "c", 1.0, 3));

        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
                games, players, defaultRatings(players), Arrays.asList(1, 2, 3));

        Map<String, EloCalculator.Glicko2Rating> finalRound = result.ratingsByRound.get(3);
        double aRating = finalRound.get("a").rating;
        double bRating = finalRound.get("b").rating;
        double cRating = finalRound.get("c").rating;
        double dRating = finalRound.get("d").rating;

        assertTrue(aRating > bRating && aRating > cRating && aRating > dRating,
                "Undefeated player 'a' should have the highest rating");
        assertTrue(dRating < bRating && dRating < cRating && dRating < aRating,
                "Winless player 'd' should have the lowest rating");
    }

    @Test
    void ratingDeviation_growsForPlayerWithNoGamesThatRound() {
        // A player active in the year but absent from round 2 should still get
        // a rating row with a non-shrinking rating deviation, reflecting
        // growing uncertainty - a real Glicko-2 requirement.
        Set<String> players = new HashSet<>(Arrays.asList("player_a", "player_b", "absent_player"));

        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("player_a", "player_b", 1.0, 1));
        // absent_player has no games in round 1 or round 2 at all

        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
                games, players, defaultRatings(players), Arrays.asList(1, 2));

        double rdRound1 = result.ratingsByRound.get(1).get("absent_player").rd;
        double rdRound2 = result.ratingsByRound.get(2).get("absent_player").rd;

        assertTrue(rdRound2 > rdRound1 - 0.0001,
                "Rating deviation for an inactive player should not shrink between rounds (should grow or stay level)");
    }

    /**
     * Regression test for the historical RD-reset bug: the engine used to
     * force every player's RD back to 350 at the start of every round, so a
     * player whose RD had converged well below 350 would have it destroyed
     * the moment they sat out (or faced a walkover). The fixed engine must
     * carry the converged RD forward, growing it only slightly per idle round.
     */
    @Test
    void convergedRd_isNotResetTo350_whenPlayerSitsOut() {
        Set<String> players = new HashSet<>(Arrays.asList("veteran", "sparring"));

        // Rounds 1-5: alternating results, so both players play every round
        // and their RDs converge far below the 350 default.
        List<EloCalculator.Game> games = new ArrayList<>();
        for (int round = 1; round <= 5; round++) {
            games.add(new EloCalculator.Game("veteran", "sparring", round % 2 == 0 ? 0.0 : 1.0, round));
        }
        // Round 6: nobody plays (e.g. veteran's only match was a walkover).

        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
                games, players, defaultRatings(players), Arrays.asList(1, 2, 3, 4, 5, 6));

        EloCalculator.Glicko2Rating afterRound5 = result.ratingsByRound.get(5).get("veteran");
        EloCalculator.Glicko2Rating afterRound6 = result.ratingsByRound.get(6).get("veteran");

        assertTrue(afterRound5.rd < 300.0,
                "Precondition: 5 played rounds should converge RD well below 350 (was " + afterRound5.rd + ")");
        assertTrue(afterRound6.rd > afterRound5.rd,
                "Idle round should GROW the rating deviation");
        assertTrue(afterRound6.rd < afterRound5.rd + 10.0,
                "Idle-round RD growth must be a small inactivity step, not a reset toward 350 (was "
                        + afterRound5.rd + " -> " + afterRound6.rd + ")");
        assertEquals(afterRound5.rating, afterRound6.rating, 1e-9,
                "An idle player's rating VALUE must carry over exactly unchanged");
        assertEquals(afterRound5.volatility, afterRound6.volatility, 1e-9,
                "An idle player's volatility must carry over exactly unchanged");
    }

    /**
     * The whole-history multi-pass calculation must let later rounds refine
     * earlier rounds' estimates (WHR-style back-propagation), and must be
     * deterministic.
     */
    @Test
    void wholeHistory_multiPass_backPropagatesLaterResultsIntoEarlierRounds() {
        Set<String> players = new HashSet<>(Arrays.asList("a", "b", "c", "d"));

        // Round 1: a beats b. Rounds 2-3: b sweeps c and d, revealing that b
        // is much stronger than round 1 alone suggested - so a's round-1 win
        // over b should be worth more once the full history is considered.
        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("a", "b", 1.0, 1));
        games.add(new EloCalculator.Game("b", "c", 1.0, 2));
        games.add(new EloCalculator.Game("b", "d", 1.0, 3));

        List<Integer> rounds = Arrays.asList(1, 2, 3);

        EloCalculator.Glicko2Result onePass = EloCalculator.calculateWholeHistory(
                games, players, defaultRatings(players), rounds, 1);
        EloCalculator.Glicko2Result fivePass = EloCalculator.calculateWholeHistory(
                games, players, defaultRatings(players), rounds, 5);
        EloCalculator.Glicko2Result fivePassAgain = EloCalculator.calculateWholeHistory(
                games, players, defaultRatings(players), rounds, 5);

        double aRound1OnePass = onePass.ratingsByRound.get(1).get("a").rating;
        double aRound1FivePass = fivePass.ratingsByRound.get(1).get("a").rating;

        assertTrue(Math.abs(aRound1FivePass - aRound1OnePass) > 1e-6,
                "5-pass whole-history must revise round-1 estimates using later rounds' information "
                        + "(1-pass: " + aRound1OnePass + ", 5-pass: " + aRound1FivePass + ")");

        for (int round : rounds) {
            for (String player : players) {
                assertEquals(fivePass.ratingsByRound.get(round).get(player).rating,
                        fivePassAgain.ratingsByRound.get(round).get(player).rating, 1e-12,
                        "Whole-history recalculation must be deterministic");
            }
        }
    }

    /** New players still start at the 350 default RD - only the reset of already-converged RDs was a bug. */
    @Test
    void newPlayer_startsAtDefaultRd() {
        Set<String> players = new HashSet<>(Arrays.asList("rookie", "opponent"));
        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("rookie", "opponent", 1.0, 1));

        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
                games, players, new HashMap<>(), List.of(1));

        double rookieRd = result.ratingsByRound.get(1).get("rookie").rd;
        assertTrue(rookieRd < DEFAULT_RD,
                "A new player's RD should shrink below the 350 default after their first played round");
    }
}
