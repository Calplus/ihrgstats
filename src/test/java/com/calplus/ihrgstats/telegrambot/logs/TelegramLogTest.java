package com.calplus.ihrgstats.telegrambot.logs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests: formatMessage used to interpolate raw message/filename
 * text into a string always sent with parse_mode=HTML - an unescaped
 * "&"/"<"/">" (plausible in an exception message or a file path) would break
 * Telegram's HTML parsing and silently drop the whole log message, with no
 * fallback retry in this class (unlike TelegramListener's outgoing messages).
 */
public class TelegramLogTest {

    @Test
    void formatMessage_escapesHtmlSpecialCharactersInMessageAndFilename() {
        TelegramLog telegramLog = new TelegramLog();
        String formatted = telegramLog.formatMessage("🔴", "ERROR", "A & B <script> failed", "file<1>.java");

        assertTrue(formatted.contains("A &amp; B &lt;script&gt; failed"), formatted);
        assertTrue(formatted.contains("file&lt;1&gt;.java"), formatted);
        assertFalse(formatted.contains("<script>"), "raw, unescaped tag must not survive: " + formatted);
    }

    @Test
    void formatMessage_leavesOrdinaryTextUnchanged() {
        TelegramLog telegramLog = new TelegramLog();
        String formatted = telegramLog.formatMessage("🔵", "INFO", "Everything is fine", "Main.java");

        assertTrue(formatted.contains("Everything is fine"));
        assertTrue(formatted.contains("Main.java"));
    }

    // --- Regression tests: an unbounded accumulated INFO batch combined with
    // a terminal (SUCCESS/ERROR/WARNING) message must still be split before
    // sending, the same way the separate batch buffer already is - otherwise
    // an over-limit combined message got a 400 from Telegram and silently
    // dropped the whole message, including the terminal log it decorated. ---

    @Test
    void splitForLimit_leavesShortContentAsOneChunk() {
        List<String> chunks = TelegramLog.splitForLimit("short message", 4096);

        assertEquals(1, chunks.size());
        assertEquals("short message", chunks.get(0));
    }

    @Test
    void splitForLimit_splitsOverLimitContent_intoChunksWithinLimit() {
        String content = "A".repeat(5000);

        List<String> chunks = TelegramLog.splitForLimit(content, 4096);

        assertTrue(chunks.size() > 1, "Content over the limit must be split into more than one chunk");
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 4096, "Every chunk must be within the limit: " + chunk.length());
        }
        assertEquals(content, String.join("", chunks), "Rejoining the chunks must reproduce the original content");
    }

    @Test
    void splitForLimit_prefersBreakingAtANewlineNearTheLimit() {
        // A newline sits just before the limit - the split should land there
        // instead of cutting the line that follows it in half.
        String firstLine = "X".repeat(4090);
        String secondLine = "second line of content";
        String content = firstLine + "\n" + secondLine;

        List<String> chunks = TelegramLog.splitForLimit(content, 4096);

        assertEquals(firstLine, chunks.get(0), "The first chunk should break exactly at the newline, not mid-line");
        assertEquals(secondLine, chunks.get(1));
    }
}
