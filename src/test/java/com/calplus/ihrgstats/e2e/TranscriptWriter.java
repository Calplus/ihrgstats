package com.calplus.ihrgstats.e2e;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders what happened in one scenario - the updates the driver injected and
 * every API call the bot made - as a readable, token-free text file. Empty
 * successful long polls are collapsed into a count.
 */
public final class TranscriptWriter {

    /** Bot-token shape: digits, colon, 20+ url-safe characters (plus the fake token itself). */
    private static final Pattern TOKEN = Pattern.compile("(?<![0-9])\\d{3,}:[A-Za-z0-9_-]{4,}");

    private TranscriptWriter() {
    }

    /** Replaces anything shaped like a bot token (and the fake token) with &lt;TOKEN&gt;. */
    public static String redact(String s) {
        if (s == null) {
            return null;
        }
        return TOKEN.matcher(s.replace(FakeTelegramServer.FAKE_TOKEN, "<TOKEN>")).replaceAll("<TOKEN>");
    }

    /** Builds the transcript text. */
    public static String render(String title, FakeTelegramServer fake) {
        List<Object[]> events = new ArrayList<>();
        for (FakeTelegramServer.Inbound in : fake.inbound()) {
            events.add(new Object[]{in.seq(), in.at(), in});
        }
        for (RecordedCall c : fake.calls()) {
            events.add(new Object[]{c.seq, c.at, c});
        }
        events.sort(Comparator.comparingLong(e -> (Long) e[0]));
        Instant t0 = events.isEmpty() ? Instant.now() : (Instant) events.get(0)[1];

        StringBuilder sb = new StringBuilder();
        sb.append("# Transcript: ").append(title).append('\n');
        sb.append("# fake=").append(fake.baseUrl()).append(" calls=").append(fake.callCount())
                .append(" violations=").append(fake.violations().size()).append('\n');
        int emptyPolls = 0;
        for (Object[] e : events) {
            if (e[2] instanceof RecordedCall c && c.method.equals("getUpdates") && c.isOk()
                    && c.responseBody != null && c.responseBody.contains("\"result\":[]") && c.violations.isEmpty()) {
                emptyPolls++;
                continue;
            }
            if (emptyPolls > 0) {
                sb.append("        (").append(emptyPolls).append(" empty getUpdates)\n");
                emptyPolls = 0;
            }
            double rel = (((Instant) e[1]).toEpochMilli() - t0.toEpochMilli()) / 1000.0;
            String stamp = String.format(java.util.Locale.ROOT, "[%04d +%7.3fs] ", (Long) e[0], rel);
            if (e[2] instanceof FakeTelegramServer.Inbound in) {
                sb.append(stamp).append(">> ").append(describeInbound(in)).append('\n');
            } else {
                RecordedCall c = (RecordedCall) e[2];
                sb.append(stamp).append("<< ").append(describeCall(c)).append('\n');
            }
        }
        if (emptyPolls > 0) {
            sb.append("        (").append(emptyPolls).append(" empty getUpdates)\n");
        }
        if (!fake.violations().isEmpty()) {
            sb.append("\n# Violations\n");
            for (RecordedCall.Violation v : fake.violations()) {
                sb.append("  ").append(v).append('\n');
            }
        }
        return redact(sb.toString());
    }

    /** Writes the transcript to {@code dir/<name>.txt} and returns the path. */
    public static Path write(Path dir, String name, FakeTelegramServer fake) throws IOException {
        Files.createDirectories(dir);
        Path out = dir.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt");
        Files.writeString(out, render(name, fake), StandardCharsets.UTF_8);
        return out;
    }

    private static String describeInbound(FakeTelegramServer.Inbound in) {
        JsonObject u = in.update();
        if (u.has("callback_query")) {
            JsonObject cq = u.getAsJsonObject("callback_query");
            return "update " + in.updateId() + " CLICK by " + who(cq.getAsJsonObject("from"))
                    + " data=\"" + str(cq, "data") + "\""
                    + (cq.has("message") ? " on message " + cq.getAsJsonObject("message").get("message_id") : "");
        }
        if (u.has("message")) {
            JsonObject m = u.getAsJsonObject("message");
            String where = " chat " + m.getAsJsonObject("chat").get("id")
                    + (m.has("message_thread_id") ? " thread " + m.get("message_thread_id") : "");
            if (m.has("document")) {
                JsonObject d = m.getAsJsonObject("document");
                return "update " + in.updateId() + " UPLOAD by " + who(m.getAsJsonObject("from")) + where
                        + " file=\"" + str(d, "file_name") + "\" (" + str(d, "file_size") + " bytes)";
            }
            return "update " + in.updateId() + " TEXT by " + who(m.getAsJsonObject("from")) + where
                    + " \"" + oneLine(str(m, "text"), 200) + "\"";
        }
        return "update " + in.updateId() + " " + u;
    }

    private static String describeCall(RecordedCall c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.method).append(" -> ").append(c.status < 0 ? "no response" : String.valueOf(c.status));
        if (c.fault != null) {
            sb.append(" [fault: ").append(c.fault).append(']');
        }
        if (c.chatId() != null) {
            sb.append(" chat=").append(c.chatId());
        }
        if (c.threadId() != null) {
            sb.append(" thread=").append(c.threadId());
        }
        if (c.parseMode() != null) {
            sb.append(" parse_mode=").append(c.parseMode());
        }
        if (c.method.equals("getUpdates")) {
            sb.append(" ").append(c.path).append(c.params.has("offset") ? " offset=" + c.param("offset") : "");
            if (c.responseBody != null && !c.isOk()) {
                sb.append(" body=").append(oneLine(c.responseBody, 200));
            } else if (c.responseBody != null) {
                sb.append(" result=").append(oneLine(c.responseBody, 160));
            }
            return sb.toString();
        }
        String text = c.text();
        if (text != null) {
            sb.append(" len=").append(text.length()).append("\n        text: ").append(indent(text, 1500));
        }
        if (c.param("caption") != null) {
            sb.append("\n        caption: ").append(oneLine(c.param("caption"), 300));
        }
        for (RecordedCall.FilePart f : c.files) {
            sb.append("\n        file: field=").append(f.field()).append(" name=").append(f.fileName())
                    .append(" type=").append(f.contentType()).append(' ').append(f.size()).append(" bytes");
            if (f.width() > 0) {
                sb.append(' ').append(f.width()).append('x').append(f.height());
            }
        }
        List<RecordedCall.Button> buttons = c.buttons();
        if (!buttons.isEmpty()) {
            sb.append("\n        keyboard (").append(buttons.size()).append("):");
            int row = -1;
            for (RecordedCall.Button b : buttons) {
                if (b.row() != row) {
                    sb.append("\n          ");
                    row = b.row();
                } else {
                    sb.append(" | ");
                }
                sb.append('[').append(b.text()).append(" -> ").append(b.callbackData()).append(']');
            }
        } else if (c.params.has("reply_markup") && !c.isSend()) {
            sb.append(" reply_markup=").append(oneLine(String.valueOf(c.params.get("reply_markup")), 120));
        }
        if (c.method.equalsIgnoreCase("editMessageReplyMarkup") || c.method.equalsIgnoreCase("deleteMessage")
                || c.method.equalsIgnoreCase("editMessageText")) {
            sb.append(" message_id=").append(c.param("message_id"));
        }
        if (c.method.equalsIgnoreCase("answerCallbackQuery")) {
            sb.append(" id=").append(c.param("callback_query_id"));
            if (c.param("text") != null) {
                sb.append(" text=\"").append(oneLine(c.param("text"), 200)).append('"');
            }
        }
        if (c.method.equals(FakeTelegramServer.DISCORD_CREATE_MESSAGE) && c.param("content") != null) {
            sb.append(" content: ").append(oneLine(c.param("content"), 300));
        }
        if (!c.isOk() && c.responseBody != null) {
            sb.append("\n        response: ").append(oneLine(c.responseBody, 300));
        }
        for (RecordedCall.Violation v : c.violations) {
            sb.append("\n        !! ").append(v);
        }
        return sb.toString();
    }

    private static String who(JsonObject from) {
        if (from == null) {
            return "?";
        }
        return from.get("id") + (from.has("username") ? " (@" + from.get("username").getAsString() + ")" : "");
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static String oneLine(String s, int max) {
        String t = s.replace("\r", "").replace("\n", "\\n");
        return t.length() > max ? t.substring(0, max) + "...(" + t.length() + " chars)" : t;
    }

    private static String indent(String s, int max) {
        String t = s.length() > max ? s.substring(0, max) + "\n...(" + s.length() + " chars total)" : s;
        return t.replace("\n", "\n              ");
    }
}
