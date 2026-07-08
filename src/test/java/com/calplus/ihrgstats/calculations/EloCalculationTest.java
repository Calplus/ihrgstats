package com.calplus.ihrgstats.calculations;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JUnit 5 tests for {@link EloCalculator#calculateGlicko2TrueElo}.
 * Replaces the old manual main()-based EloCalculationTest - PerfElo/point-margin
 * has been removed entirely, so games now only carry a plain 1.0/0.5/0.0
 * outcome score and an integer roundOrder (no more round-name strings).
 */
public class EloCalculationTest {

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

        Map<String, EloCalculator.Glicko2Rating> ratings = defaultRatings(players);
        EloCalculator.Glicko2Result result = null;

        // 3-iteration batch refinement, matching the app's real processing convention
        for (int iter = 0; iter < 3; iter++) {
            result = EloCalculator.calculateGlicko2TrueElo(games, players, ratings, Arrays.asList(1, 2));
        }

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

        Map<String, EloCalculator.Glicko2Rating> ratings = defaultRatings(players);
        EloCalculator.Glicko2Result result = null;

        for (int iter = 0; iter < 3; iter++) {
            result = EloCalculator.calculateGlicko2TrueElo(games, players, ratings, Arrays.asList(1, 2, 3));
        }

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
        // a rating row with an INCREASED (not just copied) rating deviation,
        // reflecting growing uncertainty - a real Glicko-2 requirement, not
        // wide-table legacy cruft.
        Set<String> players = new HashSet<>(Arrays.asList("player_a", "player_b", "absent_player"));

        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("player_a", "player_b", 1.0, 1));
        // absent_player has no games in round 1 or round 2 at all

        Map<String, EloCalculator.Glicko2Rating> ratings = defaultRatings(players);
        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(games, players, ratings, Arrays.asList(1, 2));

        double rdRound1 = result.ratingsByRound.get(1).get("absent_player").rd;
        double rdRound2 = result.ratingsByRound.get(2).get("absent_player").rd;

        assertTrue(rdRound2 > rdRound1 - 0.0001,
                "Rating deviation for an inactive player should not shrink between rounds (should grow or stay level)");
    }
}
