package com.calplus.ihrgstats.telegrambot.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link MatchScoreUtils#computeWalkoverDefaultScore(double)}
 * as applied to TEAM-LEVEL walkover-sweep normalization (the "3-2 not 5-0"
 * convention) - the same formula used for a single board's default walkover
 * score (see RoundCsvProcessorTest), generalized to the hall's actual
 * observed board count for a round instead of a hardcoded assumption of 5.
 */
public class MatchScoreUtilsTest {

    @Test
    void standardFiveBoardTeam_normalizesToThreeTwo() {
        double winner = MatchScoreUtils.computeWalkoverDefaultScore(5);
        assertEquals(3.0, winner, 0.0001);
        assertEquals(2.0, 5 - winner, 0.0001);
    }

    @Test
    void smallerFourBoardTeam_normalizesToThreeOne() {
        // 4 boards -> half=2.0 already an integer -> 2.0 + 1 = 3.0 winner, 1.0 loser
        double winner = MatchScoreUtils.computeWalkoverDefaultScore(4);
        assertEquals(3.0, winner, 0.0001);
        assertEquals(1.0, 4 - winner, 0.0001);
    }

    @Test
    void largerSixBoardTeam_normalizesToFourTwo() {
        // 6 boards -> half=3.0 already an integer -> 3.0 + 1 = 4.0 winner, 2.0 loser
        double winner = MatchScoreUtils.computeWalkoverDefaultScore(6);
        assertEquals(4.0, winner, 0.0001);
        assertEquals(2.0, 6 - winner, 0.0001);
    }

    @Test
    void oddSevenBoardTeam_roundsUpFromHalf() {
        // 7 boards -> half=3.5 not an integer -> ceil(3.5) = 4.0 winner, 3.0 loser
        double winner = MatchScoreUtils.computeWalkoverDefaultScore(7);
        assertEquals(4.0, winner, 0.0001);
        assertEquals(3.0, 7 - winner, 0.0001);
    }
}
