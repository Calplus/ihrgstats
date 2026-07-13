package com.calplus.ihrgstats.telegrambot.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link TelegramListener#parseCappedlistFilename(String)} (A19).
 */
public class TelegramListenerTest {

    @Test
    void parseCappedlistFilename_withYearPrefix_extractsThatYear() {
        // Regression test for A19: the year prefix was accepted by the
        // filename pattern but silently ignored - always processed under
        // whatever the current-year setting happened to be, regardless of
        // what the filename actually said.
        TelegramListener.ParsedCappedlistFilename parsed = TelegramListener.parseCappedlistFilename("2024_cappedlist.csv");
        assertTrue(parsed.matched);
        assertEquals(2024, parsed.year, "The year in the filename must be honored, not silently discarded");
    }

    @Test
    void parseCappedlistFilename_withoutYearPrefix_yearIsNull() {
        TelegramListener.ParsedCappedlistFilename parsed = TelegramListener.parseCappedlistFilename("cappedlist.csv");
        assertTrue(parsed.matched);
        assertNull(parsed.year, "No prefix present - caller falls back to the current-year setting");
    }

    @Test
    void parseCappedlistFilename_unrelatedFilename_doesNotMatch() {
        TelegramListener.ParsedCappedlistFilename parsed = TelegramListener.parseCappedlistFilename("2024_round_1.csv");
        assertFalse(parsed.matched);
    }
}
