package com.calplus.ihrgstats.telegrambot.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link RoundCsvProcessor#computeWalkoverDefaultScore(double)}.
 * Replaces the old margin-based ScoreCalculationTest.java - scores are now
 * stored raw (no win-margin formula) except for this one remaining formula:
 * the default score awarded to a walkover winner when the CSV row leaves
 * both score fields blank.
 *
 * Formula: winner gets ceil(maxScore/2), or maxScore/2+1 if that's already
 * an integer (matches the legacy WALKOVER scoring convention exactly).
 */
public class RoundCsvProcessorTest {

    private final RoundCsvProcessor processor = new RoundCsvProcessor();

    @Test
    void evenMaxScore_addsOneToHalf() {
        // maxScore=10 -> half=5.0 is already an integer -> 5.0 + 1 = 6.0
        assertEquals(6.0, processor.computeWalkoverDefaultScore(10.0), 0.0001);
    }

    @Test
    void oddMaxScore_roundsUpFromHalf() {
        // maxScore=361 -> half=180.5 is not an integer -> ceil(180.5) = 181.0
        assertEquals(181.0, processor.computeWalkoverDefaultScore(361.0), 0.0001);
    }

    @Test
    void oddDecimalMaxScore_roundsUpFromHalf() {
        // maxScore=368.5 -> half=184.25 is not an integer -> ceil(184.25) = 185.0
        assertEquals(185.0, processor.computeWalkoverDefaultScore(368.5), 0.0001);
    }

    @Test
    void smallEvenMaxScore_addsOneToHalf() {
        // maxScore=64 (Othello) -> half=32.0 is already an integer -> 32.0 + 1 = 33.0
        assertEquals(33.0, processor.computeWalkoverDefaultScore(64.0), 0.0001);
    }
}
