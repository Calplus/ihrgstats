package com.calplus.ihrgstats.e2e;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The documented Bot API limits the fake server enforces, as pure functions so
 * they can be unit-tested and reused by other detectors.
 *
 * Sources: Bot API docs "Formatting options / HTML style", sendMessage (text
 * 1-4096 characters after entities parsing), sendPhoto/sendDocument (caption
 * 0-1024 characters after entities parsing; photo at most 10 MB, width plus
 * height at most 10000, ratio at most 20), InlineKeyboardButton (callback_data
 * 1-64 bytes), and the 100-button cap on inline keyboards.
 */
public final class BotApiRules {

    public static final int MAX_TEXT = 4096;
    public static final int MAX_CAPTION = 1024;
    public static final int MAX_BUTTONS = 100;
    public static final int MAX_CALLBACK_DATA_BYTES = 64;
    public static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;
    public static final long MAX_DOCUMENT_BYTES = 50L * 1024 * 1024;
    public static final int MAX_PHOTO_WIDTH_PLUS_HEIGHT = 10000;
    public static final double MAX_PHOTO_RATIO = 20.0;
    public static final int MAX_CALLBACK_ANSWER_TEXT = 200;

    /** Tags Telegram's HTML parse mode accepts. */
    public static final Set<String> ALLOWED_TAGS = Set.of(
            "a", "b", "strong", "i", "em", "u", "ins", "s", "strike", "del",
            "span", "tg-spoiler", "tg-emoji", "code", "pre", "blockquote");

    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{1,10});");
    private static final Pattern TAG_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9-]*");

    private BotApiRules() {
    }

    /** Result of parsing a parse_mode=HTML text. */
    public static final class HtmlCheck {
        /** Problems Telegram rejects the whole message for ("can't parse entities"). */
        public final List<String> errors = new ArrayList<>();
        /** Nesting Telegram silently repairs (not a rejection). */
        public final List<String> notes = new ArrayList<>();
        /** The visible text after tags are removed and entities decoded. */
        public String plainText = "";
    }

    /**
     * Parses {@code html} the way Telegram's HTML parse mode does: every '&lt;'
     * must open or close an allowed tag, end tags must match the innermost open
     * tag, every opened tag must be closed, and a span needs class="tg-spoiler".
     * A '&amp;' that is not a recognised entity is kept literally (as Telegram does).
     */
    public static HtmlCheck checkHtml(String html) {
        HtmlCheck check = new HtmlCheck();
        StringBuilder plain = new StringBuilder();
        Deque<String> open = new ArrayDeque<>();
        int i = 0;
        int n = html.length();
        while (i < n) {
            char c = html.charAt(i);
            if (c == '<') {
                int close = html.indexOf('>', i + 1);
                if (close < 0) {
                    check.errors.add("unclosed '<' at char " + i);
                    break;
                }
                String inner = html.substring(i + 1, close);
                if (inner.startsWith("/")) {
                    String name = inner.substring(1).trim().toLowerCase(Locale.ROOT);
                    if (open.isEmpty()) {
                        check.errors.add("unmatched end tag </" + name + "> at char " + i);
                    } else if (!open.peek().equals(name)) {
                        check.errors.add("end tag </" + name + "> at char " + i + " does not match open <" + open.peek() + ">");
                        open.pop();
                    } else {
                        open.pop();
                    }
                } else {
                    Matcher m = TAG_NAME.matcher(inner);
                    if (!m.lookingAt()) {
                        check.errors.add("'<' does not start a tag at char " + i);
                        i = close + 1;
                        continue;
                    }
                    String name = m.group().toLowerCase(Locale.ROOT);
                    String attrs = inner.substring(m.end());
                    if (!ALLOWED_TAGS.contains(name)) {
                        check.errors.add("unsupported tag <" + name + "> at char " + i);
                    } else {
                        if (name.equals("span") && !attrs.replace(" ", "").contains("class=\"tg-spoiler\"")
                                && !attrs.replace(" ", "").contains("class='tg-spoiler'")) {
                            check.errors.add("<span> without class=\"tg-spoiler\" at char " + i);
                        }
                        noteNesting(check, open, name, i);
                    }
                    if (!attrs.trim().endsWith("/")) {
                        open.push(name);
                    } else {
                        check.errors.add("self-closing tag <" + name + "/> at char " + i);
                    }
                }
                i = close + 1;
            } else if (c == '&') {
                Matcher m = ENTITY.matcher(html);
                m.region(i, n);
                if (m.lookingAt()) {
                    String decoded = decodeEntity(m.group(1));
                    if (decoded != null) {
                        plain.append(decoded);
                        i = m.end();
                        continue;
                    }
                }
                plain.append('&');
                i++;
            } else {
                plain.append(c);
                i++;
            }
        }
        while (!open.isEmpty()) {
            check.errors.add("no end tag for <" + open.pop() + ">");
        }
        check.plainText = plain.toString();
        return check;
    }

    private static void noteNesting(HtmlCheck check, Deque<String> open, String name, int at) {
        if (open.contains("code")) {
            check.notes.add("<" + name + "> inside <code> at char " + at);
        } else if (open.contains("pre") && !name.equals("code")) {
            check.notes.add("<" + name + "> inside <pre> at char " + at);
        } else if (Set.of("a", "pre").contains(name) && (open.contains("a") || open.contains("pre"))) {
            check.notes.add("<" + name + "> nested in a link/pre at char " + at);
        } else if (name.equals("blockquote") && open.contains("blockquote")) {
            check.notes.add("nested <blockquote> at char " + at);
        }
    }

    private static String decodeEntity(String body) {
        if (body.startsWith("#x") || body.startsWith("#X")) {
            return codePoint(Integer.parseInt(body.substring(2), 16));
        }
        if (body.startsWith("#")) {
            return codePoint(Integer.parseInt(body.substring(1)));
        }
        switch (body) {
            case "lt": return "<";
            case "gt": return ">";
            case "amp": return "&";
            case "quot": return "\"";
            default: return null; // unknown named entity: Telegram keeps it literally
        }
    }

    private static String codePoint(int cp) {
        return Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : null;
    }

    /** Length as Telegram counts it (UTF-16 code units). */
    public static int telegramLength(String text) {
        return text.length();
    }

    /** UTF-8 byte length (the unit of the callback_data limit). */
    public static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Photo dimension rule; returns null when acceptable, else the reason. */
    public static String photoDimensionProblem(int width, int height) {
        if (width <= 0 || height <= 0) {
            return "photo has no size";
        }
        if (width + height > MAX_PHOTO_WIDTH_PLUS_HEIGHT) {
            return "width+height " + (width + height) + " > " + MAX_PHOTO_WIDTH_PLUS_HEIGHT;
        }
        double ratio = Math.max(width, height) / (double) Math.min(width, height);
        if (ratio > MAX_PHOTO_RATIO) {
            return String.format(Locale.ROOT, "aspect ratio %.2f > %.0f", ratio, MAX_PHOTO_RATIO);
        }
        return null;
    }
}
