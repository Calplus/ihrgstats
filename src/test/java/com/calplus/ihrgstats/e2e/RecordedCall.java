package com.calplus.ihrgstats.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One request the bot made to the {@link FakeTelegramServer}, with what the
 * fake answered. Immutable once recorded.
 */
public final class RecordedCall {

    /** One rule broken by the request. {@code rejected} = the fake answered 400 for it (Telegram would). */
    public record Violation(String method, String rule, String detail, boolean rejected) {
        @Override
        public String toString() {
            return (rejected ? "REJECT " : "NOTE ") + method + " " + rule + ": " + detail;
        }
    }

    /** One uploaded file part of a multipart request. */
    public record FilePart(String field, String fileName, String contentType, byte[] bytes, int width, int height) {
        public int size() {
            return bytes.length;
        }
    }

    /** One inline keyboard button. */
    public record Button(String text, String callbackData, int row) {
    }

    /** Where the request's parameters came from. */
    public enum Source { NONE, QUERY, JSON, FORM, MULTIPART }

    public final long seq;
    public final Instant at;
    /** Bot API method name as it appeared in the path, or "discord.createMessage" / "file.download" / "unknown-path". */
    public final String method;
    public final String httpMethod;
    /** Request path with the token replaced by &lt;TOKEN&gt;. */
    public final String path;
    public final Source source;
    /** All parameters (query string merged in). JSON bodies keep their JSON types; form/multipart values are strings. */
    public final JsonObject params;
    public final List<FilePart> files;
    /** Request headers of interest (Content-Type, Upgrade, Connection, HTTP2-Settings, User-Agent). */
    public final Map<String, String> headers;
    public final int status;
    public final String responseBody;
    /** The Message object the fake returned (successful send*), else null. */
    public final JsonObject result;
    public final List<Violation> violations;
    /** The scripted fault applied, or null. */
    public final Fault fault;
    public final boolean unknownMethod;
    /** True when the fake checked the token in the path and it was not the expected one. */
    public final boolean wrongToken;

    RecordedCall(long seq, Instant at, String method, String httpMethod, String path, Source source,
                 JsonObject params, List<FilePart> files, Map<String, String> headers, int status,
                 String responseBody, JsonObject result, List<Violation> violations, Fault fault,
                 boolean unknownMethod, boolean wrongToken) {
        this.seq = seq;
        this.at = at;
        this.method = method;
        this.httpMethod = httpMethod;
        this.path = path;
        this.source = source;
        this.params = params;
        this.files = Collections.unmodifiableList(files);
        this.headers = Collections.unmodifiableMap(headers);
        this.status = status;
        this.responseBody = responseBody;
        this.result = result;
        this.violations = Collections.unmodifiableList(violations);
        this.fault = fault;
        this.unknownMethod = unknownMethod;
        this.wrongToken = wrongToken;
    }

    /** True for the methods that put a new message into a chat. */
    public boolean isSend() {
        return method.equalsIgnoreCase("sendMessage") || method.equalsIgnoreCase("sendPhoto")
                || method.equalsIgnoreCase("sendDocument");
    }

    public boolean isOk() {
        return status == 200;
    }

    /** A string parameter, or null. */
    public String param(String name) {
        JsonElement e = params.get(name);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public String text() {
        return param("text");
    }

    public String chatId() {
        return param("chat_id");
    }

    public String threadId() {
        return param("message_thread_id");
    }

    public String parseMode() {
        return param("parse_mode");
    }

    /** message_id of the message the fake created for this send, or -1. */
    public long messageId() {
        return result != null && result.has("message_id") ? result.get("message_id").getAsLong() : -1;
    }

    /** The inline keyboard buttons of this send, in row order (empty when none). */
    public List<Button> buttons() {
        List<Button> out = new ArrayList<>();
        JsonObject markup = replyMarkup();
        if (markup == null || !markup.has("inline_keyboard") || !markup.get("inline_keyboard").isJsonArray()) {
            return out;
        }
        JsonArray rows = markup.getAsJsonArray("inline_keyboard");
        for (int r = 0; r < rows.size(); r++) {
            if (!rows.get(r).isJsonArray()) {
                continue;
            }
            for (JsonElement b : rows.get(r).getAsJsonArray()) {
                if (!b.isJsonObject()) {
                    continue;
                }
                JsonObject bo = b.getAsJsonObject();
                out.add(new Button(bo.has("text") ? bo.get("text").getAsString() : "",
                        bo.has("callback_data") ? bo.get("callback_data").getAsString() : null, r));
            }
        }
        return out;
    }

    public boolean hasKeyboard() {
        return !buttons().isEmpty();
    }

    /** reply_markup as an object (JSON bodies carry it as an object, form bodies as a JSON string). */
    public JsonObject replyMarkup() {
        JsonElement e = params.get("reply_markup");
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonObject()) {
            return e.getAsJsonObject();
        }
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(e.getAsString());
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The first uploaded file, or null. */
    public FilePart file() {
        return files.isEmpty() ? null : files.get(0);
    }

    @Override
    public String toString() {
        String t = text();
        String shown = t == null ? "" : " text=\"" + (t.length() > 60 ? t.substring(0, 60) + "..." : t).replace("\n", "\\n") + "\"";
        return "#" + seq + " " + method + " -> " + status + shown;
    }
}
