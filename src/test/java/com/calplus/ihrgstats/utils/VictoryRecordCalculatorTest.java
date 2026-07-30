package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins down VictoryRecordCalculator's conversion and formatting contracts -
 * since the structural dedup, five commands render scores/deltas/hall names
 * through this one class, so a change here shifts every report at once.
 */
public class VictoryRecordCalculatorTest {

    @Test
    void outcomeToPoints_mapsLegacyConventionAndRejectsJunk() {
        assertEquals(1.0, VictoryRecordCalculator.outcomeToPoints(1));
        assertEquals(0.5, VictoryRecordCalculator.outcomeToPoints(0));
        assertEquals(0.0, VictoryRecordCalculator.outcomeToPoints(-1));
        assertNull(VictoryRecordCalculator.outcomeToPoints(null));
        assertNull(VictoryRecordCalculator.outcomeToPoints(2));
    }

    @Test
    void toLegacyOutcome_isTheExactInverse_forStoredOutcomeValues() {
        assertEquals(1, VictoryRecordCalculator.toLegacyOutcome(1.0));
        assertEquals(0, VictoryRecordCalculator.toLegacyOutcome(0.5));
        assertEquals(-1, VictoryRecordCalculator.toLegacyOutcome(0.0));
        assertNull(VictoryRecordCalculator.toLegacyOutcome(null));
        assertNull(VictoryRecordCalculator.toLegacyOutcome(0.75), "only the three stored outcome values are legal");
    }

    @Test
    void getOutcomeEmoji_coversWinDrawLossAndUnknown() {
        assertEquals("✅", VictoryRecordCalculator.getOutcomeEmoji(1));
        assertEquals("🟰", VictoryRecordCalculator.getOutcomeEmoji(0));
        assertEquals("❌", VictoryRecordCalculator.getOutcomeEmoji(-1));
        assertEquals("❓", VictoryRecordCalculator.getOutcomeEmoji(null));
        assertEquals("❓", VictoryRecordCalculator.getOutcomeEmoji(7));
    }

    @Test
    void formatScore_dropsDecimalsForWholeNumbers_keepsTwoOtherwise() {
        assertEquals("7", VictoryRecordCalculator.formatScore(7.0));
        assertEquals("0", VictoryRecordCalculator.formatScore(0.0));
        assertEquals("0.50", VictoryRecordCalculator.formatScore(0.5));
        assertEquals("2.25", VictoryRecordCalculator.formatScore(2.25));
    }

    @Test
    void deltaString_signsChangesAndMarksNoChange() {
        assertEquals("+3", VictoryRecordCalculator.deltaString(3));
        assertEquals("-2", VictoryRecordCalculator.deltaString(-2));
        assertEquals("=", VictoryRecordCalculator.deltaString(0));
    }

    @Test
    void deltaDoubleString_usesOneDecimalAndSigns() {
        assertEquals("+1.5", VictoryRecordCalculator.deltaDoubleString(1.5));
        assertEquals("-0.3", VictoryRecordCalculator.deltaDoubleString(-0.3));
        assertEquals("=", VictoryRecordCalculator.deltaDoubleString(0.0));
    }

    @Test
    void playerScorePair_rendersTimeoutsAndMissingScores() {
        assertEquals("7-5", VictoryRecordCalculator.formatScorePair(7.0, 5.0, false, false));
        assertEquals("TIMEOUT-5", VictoryRecordCalculator.formatScorePair(7.0, 5.0, true, false));
        assertEquals("7-TIMEOUT", VictoryRecordCalculator.formatScorePair(7.0, 5.0, false, true));
        // A missing own score renders "?", a missing opponent score renders "0".
        assertEquals("?-5", VictoryRecordCalculator.formatScorePair(null, 5.0, false, false));
        assertEquals("7-0", VictoryRecordCalculator.formatScorePair(7.0, null, false, false));
    }

    @Test
    void hallScorePair_wholeNumbersWithoutDecimals_fractionalWithOne() {
        assertEquals("3-1", VictoryRecordCalculator.formatScorePair(3.0, 1.0));
        assertEquals("2.5-1.5", VictoryRecordCalculator.formatScorePair(2.5, 1.5));
    }

    @Test
    void formatHallName_numericGetsHallPrefix_namedGetsHallSuffix_walkoverUntouched() {
        assertEquals("Hall 4", VictoryRecordCalculator.formatHallName("4"));
        assertEquals("Binjai Hall", VictoryRecordCalculator.formatHallName("Binjai"));
        assertEquals("WALKOVER", VictoryRecordCalculator.formatHallName("WALKOVER"));
        assertEquals("WALKOVER", VictoryRecordCalculator.formatHallName("walkover"));
    }
}
