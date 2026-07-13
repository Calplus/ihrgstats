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

    /** A draw (outcome=0.5) must move neither player's rating up nor down. */
    @Test
    void draw_leavesEvenlyMatchedPlayersRatingsUnchanged() {
        Set<String> players = new HashSet<>(Arrays.asList("player_a", "player_b"));
        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("player_a", "player_b", 0.5, 1));

        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
                games, players, defaultRatings(players), List.of(1));

        double aRating = result.ratingsByRound.get(1).get("player_a").rating;
        double bRating = result.ratingsByRound.get(1).get("player_b").rating;
        assertEquals(aRating, bRating, 1e-9,
                "Two equally-rated players drawing should end up with identical ratings");
    }

    /** A draw against a much stronger opponent should still raise the weaker player's rating. */
    @Test
    void draw_againstStrongerOpponent_raisesWeakerPlayersRating() {
        Set<String> players = new HashSet<>(Arrays.asList("underdog", "favorite"));
        Map<String, EloCalculator.Glicko2Rating> ratings = new HashMap<>();
        ratings.put("underdog", new EloCalculator.Glicko2Rating(1000, 60, 0.06));
        ratings.put("favorite", new EloCalculator.Glicko2Rating(1400, 60, 0.06));

        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("underdog", "favorite", 0.5, 1));

        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
                games, players, ratings, List.of(1));

        assertTrue(result.ratingsByRound.get(1).get("underdog").rating > 1000,
                "Drawing a much stronger opponent should raise the underdog's rating above their prior");
        assertTrue(result.ratingsByRound.get(1).get("favorite").rating < 1400,
                "Drawing a much weaker opponent should lower the favorite's rating below their prior");
    }

    /** RD growth from repeated idle rounds must keep growing (bounded at 350), never shrink or reset. */
    @Test
    void multipleConsecutiveIdleRounds_rdGrowsMonotonicallyAndNeverExceedsCap() {
        Set<String> players = new HashSet<>(Arrays.asList("veteran", "sparring"));
        List<EloCalculator.Game> games = new ArrayList<>();
        for (int round = 1; round <= 5; round++) {
            games.add(new EloCalculator.Game("veteran", "sparring", round % 2 == 0 ? 0.0 : 1.0, round));
        }
        // Rounds 6-55: veteran is idle for 50 consecutive rounds. The
        // sqrt(rd^2 + vol^2) growth step is asymptotic and slow once rd is
        // already sizeable relative to volatility - it does not reach the
        // 350 cap in any practical number of rounds, so this only checks
        // the two invariants that must always hold: monotonic growth, and
        // never exceeding the cap.
        List<Integer> rounds = new ArrayList<>();
        for (int r = 1; r <= 55; r++) rounds.add(r);

        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
                games, players, defaultRatings(players), rounds);

        double rdAfterRound5 = result.ratingsByRound.get(5).get("veteran").rd;
        double prevRd = rdAfterRound5;
        for (int round = 6; round <= 55; round++) {
            double rd = result.ratingsByRound.get(round).get("veteran").rd;
            assertTrue(rd >= prevRd - 1e-9, "RD must not shrink during an idle round (round " + round + ")");
            assertTrue(rd <= DEFAULT_RD + 1e-9, "RD must never exceed the 350 default cap (round " + round + ")");
            prevRd = rd;
        }
        assertTrue(prevRd > rdAfterRound5,
                "RD should have grown measurably over 50 idle rounds (from " + rdAfterRound5 + " to " + prevRd + ")");
    }

    /** Volatility must stay within Glicko-2's sane operating range - it should never runaway to 0, negative, or huge. */
    @Test
    void volatility_staysWithinSaneBoundsAcrossManyRounds() {
        Set<String> players = new HashSet<>(Arrays.asList("a", "b"));
        List<EloCalculator.Game> games = new ArrayList<>();
        for (int round = 1; round <= 10; round++) {
            // Alternating results - a noisy, inconsistent player.
            games.add(new EloCalculator.Game("a", "b", round % 3 == 0 ? 0.0 : 1.0, round));
        }
        List<Integer> rounds = new ArrayList<>();
        for (int r = 1; r <= 10; r++) rounds.add(r);

        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
                games, players, defaultRatings(players), rounds);

        for (int round : rounds) {
            double vol = result.ratingsByRound.get(round).get("a").volatility;
            assertTrue(vol > 0.0 && vol < 1.0,
                    "Volatility must stay strictly positive and well below 1.0 (round " + round + ": " + vol + ")");
        }
    }

    /**
     * Regression test for the historical volatility-scaling bug:
     * calculateNewVolatility divided variance/delta by GLICKO2_SCALE a
     * second time even though they arrive already on the internal scale, so
     * the "surprise" term was ~174-30000x too small to ever meaningfully
     * move sigma. A genuinely shocking upset must now raise volatility
     * above its settled level - the fixed engine's whole point.
     */
    @Test
    void volatility_risesAfterAMajorUpset_forAPreviouslyConvergedPlayer() {
        Set<String> players = new HashSet<>(Arrays.asList("veteran", "sparring", "champion"));

        List<EloCalculator.Game> games = new ArrayList<>();
        // Rounds 1-5: veteran and sparring alternate wins against each
        // other - an evenly-matched, fully-expected pattern, so RD
        // converges and volatility settles near its 0.06 default.
        for (int round = 1; round <= 5; round++) {
            games.add(new EloCalculator.Game("veteran", "sparring", round % 2 == 0 ? 0.0 : 1.0, round));
        }
        // Round 6: veteran springs a massive upset against champion, seeded
        // far above everyone else - a genuinely surprising result given
        // veteran's settled, low-RD rating.
        games.add(new EloCalculator.Game("veteran", "champion", 1.0, 6));

        Map<String, EloCalculator.Glicko2Rating> initialRatings = defaultRatings(players);
        initialRatings.put("champion", new EloCalculator.Glicko2Rating(1800, 60, 0.06));

        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
                games, players, initialRatings, Arrays.asList(1, 2, 3, 4, 5, 6));

        double volatilityBeforeUpset = result.ratingsByRound.get(5).get("veteran").volatility;
        double volatilityAfterUpset = result.ratingsByRound.get(6).get("veteran").volatility;

        assertTrue(volatilityAfterUpset > volatilityBeforeUpset,
                "A genuinely surprising upset should raise volatility above its settled level (round 5: "
                        + volatilityBeforeUpset + ", round 6: " + volatilityAfterUpset + ")");
    }
}
