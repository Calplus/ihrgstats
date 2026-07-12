package com.calplus.ihrgstats.telegrambot.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link MatchScoreUtils#computeWalkoverDefaultScore(double)}
 * (moved here from RoundCsvProcessor so the same formula can be reused for
 * team-level walkover-sweep normalization - see MatchScoreUtilsTest).
 * Replaces the old margin-based ScoreCalculationTest.java - scores are now
 * stored raw (no win-margin formula) except for this one remaining formula:
 * the default score awarded to a walkover winner when the CSV row leaves
 * both score fields blank.
 *
 * Formula: winner gets ceil(maxScore/2), or maxScore/2+1 if that's already
 * an integer (matches the legacy WALKOVER scoring convention exactly).
 */
public class RoundCsvProcessorTest {

    @Test
    void evenMaxScore_addsOneToHalf() {
        // maxScore=10 -> half=5.0 is already an integer -> 5.0 + 1 = 6.0
        assertEquals(6.0, MatchScoreUtils.computeWalkoverDefaultScore(10.0), 0.0001);
    }

    @Test
    void oddMaxScore_roundsUpFromHalf() {
        // maxScore=361 -> half=180.5 is not an integer -> ceil(180.5) = 181.0
        assertEquals(181.0, MatchScoreUtils.computeWalkoverDefaultScore(361.0), 0.0001);
    }

    @Test
    void oddDecimalMaxScore_roundsUpFromHalf() {
        // maxScore=368.5 -> half=184.25 is not an integer -> ceil(184.25) = 185.0
        assertEquals(185.0, MatchScoreUtils.computeWalkoverDefaultScore(368.5), 0.0001);
    }

    @Test
    void smallEvenMaxScore_addsOneToHalf() {
        // maxScore=64 (Othello) -> half=32.0 is already an integer -> 32.0 + 1 = 33.0
        assertEquals(33.0, MatchScoreUtils.computeWalkoverDefaultScore(64.0), 0.0001);
    }
}
