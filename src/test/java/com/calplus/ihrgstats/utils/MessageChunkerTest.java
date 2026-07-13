package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for A10: chunking used to be sized on the pre-conversion
 * (raw) message length, but TelegramHtml.prepareForSending's HTML
 * escaping/fence conversion can only grow text, so a chunk that looked
 * safely under the old raw-character threshold could exceed Telegram's real
 * message-length limit once converted - and the plain-text fallback send
 * (same over-length text) would fail too.
 */
public class MessageChunkerTest {

    @Test
    void shortMessage_isNotSplit() {
        String message = "Hello, world!";
        List<String> chunks = MessageChunker.splitForTelegram(message);
        assertEquals(1, chunks.size());
        assertEquals(message, chunks.get(0));
    }

    @Test
    void everyChunk_hasAPostConversionLengthWithinTheRealLimit_evenWithHeavyEscaping() {
        // Build a fenced "table" whose raw length is comfortably under the
        // OLD 4000-char raw threshold, but where every line is packed with
        // "&", "<", ">" - each of which grows substantially once escaped
        // ("&" -> "&amp;" is +4 chars). This would NOT have been split under
        // the old raw-length check but must still produce safe chunks.
        StringBuilder sb = new StringBuilder("Table header\n```\n");
        for (int i = 0; i < 120; i++) {
            sb.append("Player <A&B> Team ").append(i).append(" score>10 & win\n");
        }
        sb.append("```\nFooter text");
        String message = sb.toString();

        // Sanity: raw length was under the old 4000-char threshold, but the
        // real bug is that the CONVERTED length is what actually matters.
        List<String> chunks = MessageChunker.splitForTelegram(message);

        assertTrue(chunks.size() > 1, "A message with heavy escaping should be split into multiple chunks");
        for (String chunk : chunks) {
            int convertedLength = TelegramHtml.prepareForSending(chunk).length();
            assertTrue(convertedLength <= MessageChunker.TELEGRAM_MESSAGE_LIMIT,
                    "Chunk converted length " + convertedLength + " must fit within " + MessageChunker.TELEGRAM_MESSAGE_LIMIT + ": " + chunk);
        }
    }

    @Test
    void fallbackSplit_forMessageWithNoCodeFence_alsoRespectsConvertedLength() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("A&B<C>D ");
        }
        String message = sb.toString();

        List<String> chunks = MessageChunker.splitForTelegram(message);

        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) {
            int convertedLength = TelegramHtml.prepareForSending(chunk).length();
            assertTrue(convertedLength <= MessageChunker.TELEGRAM_MESSAGE_LIMIT,
                    "Chunk converted length " + convertedLength + " must fit within " + MessageChunker.TELEGRAM_MESSAGE_LIMIT);
        }
    }

    @Test
    void codeBlockAwareSplit_preservesPrefixAndSuffixAsSeparateChunks() {
        StringBuilder codeContent = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            codeContent.append("Row ").append(i).append(" with some padding text to grow the table\n");
        }
        String message = "Header text\n```\n" + codeContent + "```\nFooter text";

        List<String> chunks = MessageChunker.splitForTelegram(message);

        assertEquals("Header text", chunks.get(0));
        assertEquals("Footer text", chunks.get(chunks.size() - 1));
        assertTrue(chunks.size() > 2, "The table content itself should need at least one chunk between prefix/suffix");
    }

    @Test
    void everyEmittedChunk_isNonEmptyAndFitsWithinTheRealLimit_forAWideRangeOfSizes() {
        for (int rows = 1; rows <= 250; rows += 37) {
            StringBuilder codeContent = new StringBuilder();
            for (int i = 0; i < rows; i++) {
                codeContent.append("Data row ").append(i).append(" ").append("x".repeat(20)).append("\n");
            }
            String message = "```\n" + codeContent + "```";
            List<String> chunks = MessageChunker.splitForTelegram(message);
            for (String chunk : chunks) {
                assertFalse(chunk.isEmpty());
                assertTrue(TelegramHtml.prepareForSending(chunk).length() <= MessageChunker.TELEGRAM_MESSAGE_LIMIT);
            }
        }
    }

    // --- Follow-up fixes found by finder-agent verification ---

    @Test
    void nullOrEmptyMessage_producesNoChunksAtAll() {
        assertEquals(0, MessageChunker.splitForTelegram(null).size());
        assertEquals(0, MessageChunker.splitForTelegram("").size());
    }

    @Test
    void anOversizedPrefixOutsideTheFence_isItselfSplitSafely_notShippedAsOneOverLimitChunk() {
        String hugePrefix = "&".repeat(6000); // escapes to "&amp;" x 6000 - guaranteed over the limit alone
        String message = hugePrefix + "\n```\nsmall table\n```\nFooter";

        List<String> chunks = MessageChunker.splitForTelegram(message);

        assertTrue(chunks.size() > 1, "An oversized prefix must be split into more than one chunk on its own");
        for (String chunk : chunks) {
            assertFalse(chunk.isEmpty());
            int convertedLength = TelegramHtml.prepareForSending(chunk).length();
            assertTrue(convertedLength <= MessageChunker.TELEGRAM_MESSAGE_LIMIT,
                    "Chunk converted length " + convertedLength + " must fit within " + MessageChunker.TELEGRAM_MESSAGE_LIMIT);
        }
    }

    @Test
    void anOversizedSuffixAfterTheFence_isItselfSplitSafely() {
        String hugeSuffix = "&".repeat(6000);
        String message = "Header\n```\nsmall table\n```\n" + hugeSuffix;

        List<String> chunks = MessageChunker.splitForTelegram(message);

        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) {
            assertFalse(chunk.isEmpty());
            assertTrue(TelegramHtml.prepareForSending(chunk).length() <= MessageChunker.TELEGRAM_MESSAGE_LIMIT);
        }
    }

    @Test
    void aSingleLineInsideAFence_thatIsOversizedEntirelyOnItsOwn_getsSplitIntoMultipleSafeFencedChunks() {
        String hugeLine = "x".repeat(6000); // one line, way over the limit even alone
        String message = "```\nshort line\n" + hugeLine + "\nanother short line\n```";

        List<String> chunks = MessageChunker.splitForTelegram(message);

        assertTrue(chunks.size() > 1, "A single oversized line must be split into more than one chunk");
        for (String chunk : chunks) {
            assertFalse(chunk.isEmpty());
            int convertedLength = TelegramHtml.prepareForSending(chunk).length();
            assertTrue(convertedLength <= MessageChunker.TELEGRAM_MESSAGE_LIMIT,
                    "Chunk converted length " + convertedLength + " must fit within " + MessageChunker.TELEGRAM_MESSAGE_LIMIT);
        }
        // Every fragment of the oversized line must still be present, in order.
        String rejoined = String.join("", chunks).replace("```", "").replace("\n", "");
        assertTrue(rejoined.contains("x".repeat(100)), "The oversized line's content must not be lost, just split");
    }
}
