package com.calplus.ihrgstats.telegrambot.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins down the shared quote-aware CSV parser - both the round upload and
 * the capped-list upload parse every data row through this one method, and
 * the sample corpus contains quoted comma-names (e.g. "Nightingale, Florence").
 */
public class CsvLineParserTest {

    @Test
    void plainFields_splitOnCommas_preservingEmptyFields() {
        assertArrayEquals(new String[]{"Aurelia Nightshade", "1", "", "WALKOVER", "", ""},
                CsvLineParser.parseLine("Aurelia Nightshade,1,,WALKOVER,,"));
    }

    @Test
    void quotedField_keepsItsEmbeddedComma() {
        assertArrayEquals(new String[]{"Nightingale, Florence", "1", "10"},
                CsvLineParser.parseLine("\"Nightingale, Florence\",1,10"));
    }

    @Test
    void escapedDoubleQuote_insideQuotedField_becomesOneQuote() {
        assertArrayEquals(new String[]{"The \"Great\" One", "2"},
                CsvLineParser.parseLine("\"The \"\"Great\"\" One\",2"));
    }

    @Test
    void apostrophesAndUnicode_passThroughUnchanged() {
        assertArrayEquals(new String[]{"D'Artagnan O'Neill", "3", "7"},
                CsvLineParser.parseLine("D'Artagnan O'Neill,3,7"));
    }

    @Test
    void singleField_returnsOneElement() {
        assertArrayEquals(new String[]{"solo"}, CsvLineParser.parseLine("solo"));
    }

    @Test
    void emptyLine_returnsOneEmptyField() {
        assertArrayEquals(new String[]{""}, CsvLineParser.parseLine(""));
    }
}
