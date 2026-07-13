package com.calplus.ihrgstats.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Escaping helper for Telegram's HTML parse mode. Any dynamic content
 * (player names, hall labels, match-type names/descriptions, admin-set
 * round labels) that gets interpolated into an HTML-formatted Telegram
 * message must be escaped first - an unescaped "&", "<", or ">" in a name
 * (plausible from free-text CSV input, or an admin-typed match-type
 * description) breaks Telegram's HTML entity parsing and the send fails
 * with an API 400.
 *
 * Also provides {@link #prepareForSending(String)}, a compatibility layer
 * that converts the legacy ad-hoc "**bold**" / ```-fenced-table" message
 * style (still used by most commands) into Telegram-HTML at the point
 * messages are actually sent - centralizing the fix here means every send
 * path benefits without needing every command's message-building code
 * rewritten by hand, and it composes cleanly with commands that already
 * build native HTML (they simply contain no "**"/```` ``` ```` for this to match).
 */
public final class TelegramHtml {

    private TelegramHtml() {}

    // Non-greedy, single-line (no DOTALL) so a pair can only match within
    // one label/line, never accidentally span across unrelated content.
    private static final Pattern BOLD_MARKDOWN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern CODE_FENCE = Pattern.compile("```\\n?([\\s\\S]*?)```");

    /** Escapes the three characters Telegram's HTML parse mode treats specially. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    /**
     * Converts every legacy-Markdown-style ```-fenced monospace block into
     * an HTML-safe {@code <pre>} block. The fenced content is escaped as a
     * whole (safe even for table cells built with fixed-width padding -
     * Telegram decodes entities back to single characters before display,
     * so escaping doesn't shift column alignment). Text outside the
     * fences is left untouched.
     */
    public static String convertCodeFencesToPre(String message) {
        Matcher matcher = CODE_FENCE.matcher(message);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(message, lastEnd, matcher.start());
            result.append("<pre>").append(escape(matcher.group(1))).append("</pre>");
            lastEnd = matcher.end();
        }
        result.append(message, lastEnd, message.length());
        return result.toString();
    }

    /**
     * Converts every "**bold**" span in {@code message} into {@code <b>bold</b>}.
     * {@link #prepareForSending(String)} only ever calls this on text segments
     * OUTSIDE of ``` fences - never on a fence's own content - since Telegram's
     * HTML parser rejects a nested entity inside a {@code <pre>} block.
     */
    public static String convertMarkdownBoldToHtml(String message) {
        return BOLD_MARKDOWN.matcher(message).replaceAll("<b>$1</b>");
    }

    /**
     * Applies both compatibility conversions, in the required order. Call
     * this on every outgoing message text immediately before deciding
     * parse_mode / building the send payload.
     *
     * Bold-conversion is applied ONLY to the text segments outside ```
     * fences, never to a fence's own content - Telegram's HTML parser does
     * not allow a nested entity (e.g. {@code <b>}) inside a {@code <pre>}
     * block, so a literal "**" that happens to appear inside a fenced table
     * cell must stay untouched, not become an invalid {@code <pre><b>...}
     * nesting (A11).
     */
    public static String prepareForSending(String message) {
        if (message == null) {
            return null;
        }

        Matcher matcher = CODE_FENCE.matcher(message);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(convertMarkdownBoldToHtml(message.substring(lastEnd, matcher.start())));
            result.append("<pre>").append(escape(matcher.group(1))).append("</pre>");
            lastEnd = matcher.end();
        }
        result.append(convertMarkdownBoldToHtml(message.substring(lastEnd)));
        return result.toString();
    }
}
