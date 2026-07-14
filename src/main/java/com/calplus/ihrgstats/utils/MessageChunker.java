package com.calplus.ihrgstats.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Splits an outgoing Telegram message into chunks that each fit within
 * Telegram's real message-length limit AFTER {@link TelegramHtml#prepareForSending}
 * has run - HTML escaping/conversion can only grow raw text (an "&" becomes
 * "&amp;", a "```" fence becomes "&lt;pre&gt;...&lt;/pre&gt;" etc.), so
 * chunking based on the pre-conversion length risked a chunk that looked
 * safely under the limit expanding past it once escaped, causing the send
 * (and its plain-text fallback, which is the same over-length text) to fail.
 *
 * Every chunk this class emits is guaranteed both non-empty and within
 * {@link #TELEGRAM_MESSAGE_LIMIT} once converted - including the prefix/
 * suffix text surrounding a single ``` fence, and even a single line INSIDE
 * a fence that's already too long entirely on its own.
 */
public final class MessageChunker {

    public static final int TELEGRAM_MESSAGE_LIMIT = 4096;

    private static final Function<String, String> PLAIN = s -> s;
    private static final Function<String, String> FENCED = s -> "```\n" + s + "\n```";

    private MessageChunker() {}

    /**
     * Splits {@code message} into one or more chunks, each guaranteed to
     * have a post-conversion length within {@link #TELEGRAM_MESSAGE_LIMIT}
     * (and to be non-empty). Prefers splitting along each ``` -fenced
     * block's lines (preserving the surrounding/interleaved plain-text
     * segments as their own chunks) when present; otherwise falls back to a
     * plain, safely-sized split. Returns an empty list for a null/empty input.
     */
    public static List<String> splitForTelegram(String message) {
        List<String> chunks = new ArrayList<>();

        if (message == null || message.isEmpty()) {
            return chunks;
        }

        if (TelegramHtml.prepareForSending(message).length() <= TELEGRAM_MESSAGE_LIMIT) {
            chunks.add(message);
            return chunks;
        }

        // Walk every ```...``` fence pair in the message, not just "first ```
        // to last ```" - the latter treated everything between a 2nd fence
        // pair and beyond (including the plain text between them, and their
        // own ``` markers) as one giant, wrongly-delimited code block.
        List<int[]> fenceRanges = new ArrayList<>(); // [start, end) index pairs, pointing at each ``` marker itself
        int searchFrom = 0;
        while (true) {
            int start = message.indexOf("```", searchFrom);
            if (start < 0) break;
            int end = message.indexOf("```", start + 3);
            if (end < 0) break; // unterminated trailing fence - leave it as plain text below
            fenceRanges.add(new int[]{start, end});
            searchFrom = end + 3;
        }

        if (fenceRanges.isEmpty()) {
            addSafely(chunks, message, PLAIN);
            return chunks;
        }

        int cursor = 0;
        for (int[] range : fenceRanges) {
            addSafely(chunks, message.substring(cursor, range[0]).trim(), PLAIN);
            addFencedContent(chunks, message.substring(range[0] + 3, range[1]));
            cursor = range[1] + 3;
        }
        addSafely(chunks, message.substring(cursor).trim(), PLAIN);

        return chunks;
    }

    /**
     * Splits one ```-fenced block's content into one or more safely-sized
     * fenced chunks, line by line (extracted from {@link #splitForTelegram}
     * so it applies identically to every fence pair found, not just one).
     */
    private static void addFencedContent(List<String> chunks, String codeContent) {
        String[] lines = codeContent.split("\n");
        StringBuilder currentChunk = new StringBuilder("```\n");
        for (String line : lines) {
            // Would closing the chunk right now (with this line included)
            // exceed the real limit once converted? Check before
            // appending, so every emitted chunk is already safe.
            String candidateClosed = currentChunk + line + "\n```";
            if (currentChunk.length() > "```\n".length()
                    && TelegramHtml.prepareForSending(candidateClosed).length() > TELEGRAM_MESSAGE_LIMIT) {
                currentChunk.append("```");
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder("```\n");
            }

            // A single line can be long enough to exceed the limit even
            // entirely on its own (fenced by itself, nothing else in the
            // chunk) - split the line itself in that case, bypassing
            // currentChunk entirely for it, rather than letting an
            // over-limit chunk ride along unsplit.
            if (TelegramHtml.prepareForSending(FENCED.apply(line)).length() > TELEGRAM_MESSAGE_LIMIT) {
                addSafely(chunks, line, FENCED);
            } else {
                currentChunk.append(line).append("\n");
            }
        }
        if (currentChunk.length() > "```\n".length()) {
            currentChunk.append("```");
            chunks.add(currentChunk.toString());
        }
    }

    /**
     * Adds {@code text} to {@code chunks} as one or more safely-sized pieces,
     * each wrapped by {@code wrapper} (identity for plain text, or fence
     * markers for a single oversized line) before being measured against the
     * real limit. Does nothing for empty text - this is what keeps an empty
     * prefix/suffix (or an empty overall message) from producing a spurious
     * empty chunk.
     */
    private static void addSafely(List<String> chunks, String text, Function<String, String> wrapper) {
        if (text.isEmpty()) {
            return;
        }
        if (TelegramHtml.prepareForSending(wrapper.apply(text)).length() <= TELEGRAM_MESSAGE_LIMIT) {
            chunks.add(wrapper.apply(text));
            return;
        }
        int i = 0;
        while (i < text.length()) {
            int end = findSafeChunkEnd(text, i, wrapper);
            chunks.add(wrapper.apply(text.substring(i, end)));
            i = end;
        }
    }

    /**
     * Binary-searches the largest end index (&gt; start) such that
     * {@code wrapper.apply(text.substring(start, end))}'s converted length
     * still fits within the real limit.
     */
    private static int findSafeChunkEnd(String text, int start, Function<String, String> wrapper) {
        int lo = start + 1;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (TelegramHtml.prepareForSending(wrapper.apply(text.substring(start, mid))).length() <= TELEGRAM_MESSAGE_LIMIT) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
