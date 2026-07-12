package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link TelegramHtml}. These cover the compatibility layer
 * that converts the legacy ad-hoc "**bold**" / ```-fenced-table message
 * style into Telegram-safe HTML at send time (see
 * {@link TelegramHtml#prepareForSending(String)}), since this is what
 * makes malformed dynamic content (an unescaped "&"/"<"/">" in a free-text
 * player name) degrade safely instead of breaking message delivery.
 */
public class TelegramHtmlTest {

    @Test
    void escape_replacesAllThreeSpecialCharacters() {
        assertEquals("A &amp; B &lt;tag&gt;", TelegramHtml.escape("A & B <tag>"));
    }

    @Test
    void escape_nullReturnsEmptyString() {
        assertEquals("", TelegramHtml.escape(null));
    }

    @Test
    void convertMarkdownBoldToHtml_convertsPairedAsterisks() {
        assertEquals("Hello <b>World</b>!", TelegramHtml.convertMarkdownBoldToHtml("Hello **World**!"));
    }

    @Test
    void convertMarkdownBoldToHtml_convertsMultipleSpansIndependently() {
        assertEquals("<b>A</b> normal <b>B</b>", TelegramHtml.convertMarkdownBoldToHtml("**A** normal **B**"));
    }

    @Test
    void convertCodeFencesToPre_wrapsAndEscapesFencedContent() {
        String input = "Header\n```\nName <script>\n```\nFooter";
        String result = TelegramHtml.convertCodeFencesToPre(input);
        assertEquals("Header\n<pre>Name &lt;script&gt;\n</pre>\nFooter", result);
    }

    @Test
    void convertCodeFencesToPre_handlesMultipleFencesIndependently() {
        String input = "```\nA\n``` middle ```\nB\n```";
        String result = TelegramHtml.convertCodeFencesToPre(input);
        assertEquals("<pre>A\n</pre> middle <pre>B\n</pre>", result);
    }

    @Test
    void prepareForSending_appliesFencesBeforeBold_andLeavesPlainTextAlone() {
        String input = "**Match Info:**\n```\nWon <b> the game\n```";
        String result = TelegramHtml.prepareForSending(input);
        assertEquals("<b>Match Info:</b>\n<pre>Won &lt;b&gt; the game\n</pre>", result);
    }

    @Test
    void prepareForSending_plainTextMessageIsUnchanged() {
        String plain = "No formatting here, just plain text.";
        assertEquals(plain, TelegramHtml.prepareForSending(plain));
    }
}
