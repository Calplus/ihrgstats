package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link TableFormatter#shortenRoundName(String)}. The
 * default round label ("Round {N}") must shorten to fit the 3-character-wide
 * "LR" column in /rankplayers' text table, instead of the old no-op
 * uppercase-only behavior that got hard-truncated to garbage (e.g. "ROU").
 */
public class TableFormatterTest {

    @Test
    void defaultRoundLabel_shortensToRPlusNumber() {
        assertEquals("R1", TableFormatter.shortenRoundName("Round 1"));
        assertEquals("R12", TableFormatter.shortenRoundName("Round 12"));
    }

    @Test
    void defaultRoundLabel_caseInsensitivePrefix() {
        assertEquals("R3", TableFormatter.shortenRoundName("round 3"));
        assertEquals("R3", TableFormatter.shortenRoundName("ROUND 3"));
    }

    @Test
    void customAdminRenamedLabel_isJustUppercased() {
        assertEquals("FINALS", TableFormatter.shortenRoundName("Finals"));
    }

    @Test
    void nullOrEmpty_returnsEmptyString() {
        assertEquals("", TableFormatter.shortenRoundName(null));
        assertEquals("", TableFormatter.shortenRoundName(""));
    }
}
