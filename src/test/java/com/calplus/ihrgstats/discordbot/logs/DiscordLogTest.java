package com.calplus.ihrgstats.discordbot.logs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests: an unbounded accumulated INFO batch combined with a
 * terminal (SUCCESS/ERROR/WARNING) message must still be split before
 * sending, the same way the separate batch buffer already is - otherwise an
 * over-limit combined message (much easier to hit here than in TelegramLog,
 * since Discord's limit is 2000 characters vs Telegram's 4096) failed to
 * send and silently dropped the whole message, including the terminal log
 * it decorated. Mirrors {@link com.calplus.ihrgstats.telegrambot.logs.TelegramLogTest}.
 */
public class DiscordLogTest {

    @Test
    void splitForLimit_leavesShortContentAsOneChunk() {
        List<String> chunks = DiscordLog.splitForLimit("short message", 2000);

        assertEquals(1, chunks.size());
        assertEquals("short message", chunks.get(0));
    }

    @Test
    void splitForLimit_splitsOverLimitContent_intoChunksWithinLimit() {
        String content = "A".repeat(3000);

        List<String> chunks = DiscordLog.splitForLimit(content, 2000);

        assertTrue(chunks.size() > 1, "Content over the limit must be split into more than one chunk");
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 2000, "Every chunk must be within the limit: " + chunk.length());
        }
        assertEquals(content, String.join("", chunks), "Rejoining the chunks must reproduce the original content");
    }

    @Test
    void splitForLimit_prefersBreakingAtANewlineNearTheLimit() {
        // A newline sits just before the limit - the split should land there
        // instead of cutting the line that follows it in half.
        String firstLine = "X".repeat(1990);
        String secondLine = "second line of content";
        String content = firstLine + "\n" + secondLine;

        List<String> chunks = DiscordLog.splitForLimit(content, 2000);

        assertEquals(firstLine, chunks.get(0), "The first chunk should break exactly at the newline, not mid-line");
        assertEquals(secondLine, chunks.get(1));
    }
}
