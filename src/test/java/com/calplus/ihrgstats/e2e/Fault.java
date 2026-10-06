package com.calplus.ihrgstats.e2e;

import com.google.gson.JsonObject;

import java.time.Duration;

/**
 * One scripted failure the {@link FakeTelegramServer} applies to a matching
 * request instead of (or before) its normal handling. Build with the static
 * factories, register with {@code failNext / failTimes / failAlways / failWhen}.
 *
 * <ul>
 *   <li>{@link #http}, {@link #apiError} and the named shortcuts answer with that
 *       status and body;</li>
 *   <li>{@link #delay} waits, then handles the request normally;</li>
 *   <li>{@link #dropConnection} closes the TCP connection without any response
 *       (the client sees an IOException; it is a FIN, not a true RST);</li>
 *   <li>{@link #hang} never answers until the server closes - pair it with the
 *       HttpClientFactory timeout overrides to exercise request timeouts.</li>
 * </ul>
 * {@link #after(Duration)} adds a delay before any of the above.
 */
public final class Fault {

    public enum Kind { RESPOND, DELAY, DROP_CONNECTION, HANG }

    final Kind kind;
    final int status;
    final String body;
    final long delayMs;
    final String label;

    private Fault(Kind kind, int status, String body, long delayMs, String label) {
        this.kind = kind;
        this.status = status;
        this.body = body;
        this.delayMs = delayMs;
        this.label = label;
    }

    /** Answers with exactly this status and raw body (Content-Type application/json). */
    public static Fault http(int status, String rawBody) {
        return new Fault(Kind.RESPOND, status, rawBody, 0, "http " + status);
    }

    /** Answers like the Bot API does for an error: {"ok":false,"error_code":code,"description":...}. */
    public static Fault apiError(int errorCode, String description) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("error_code", errorCode);
        body.addProperty("description", description);
        return new Fault(Kind.RESPOND, errorCode, body.toString(), 0, errorCode + " " + description);
    }

    /** 429 with parameters.retry_after, as Telegram sends it. */
    public static Fault tooManyRequests(int retryAfterSeconds) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("error_code", 429);
        body.addProperty("description", "Too Many Requests: retry after " + retryAfterSeconds);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("retry_after", retryAfterSeconds);
        body.add("parameters", parameters);
        return new Fault(Kind.RESPOND, 429, body.toString(), 0, "429 retry_after=" + retryAfterSeconds);
    }

    /** 401, what Telegram answers once a token is revoked. */
    public static Fault unauthorized() {
        return apiError(401, "Unauthorized");
    }

    /** 409, what Telegram answers to a poll when a second instance polls with the same token. */
    public static Fault conflict() {
        return apiError(409, "Conflict: terminated by other getUpdates request; make sure that only one bot instance is running");
    }

    /** 500 Internal Server Error. */
    public static Fault serverError() {
        return apiError(500, "Internal Server Error");
    }

    /** 400 "Bad Request: &lt;description&gt;". */
    public static Fault badRequest(String description) {
        return apiError(400, "Bad Request: " + description);
    }

    /** Waits, then handles the request normally. */
    public static Fault delay(Duration delay) {
        return new Fault(Kind.DELAY, 0, null, delay.toMillis(), "delay " + delay.toMillis() + "ms");
    }

    /** Closes the connection without sending any response. */
    public static Fault dropConnection() {
        return new Fault(Kind.DROP_CONNECTION, 0, null, 0, "drop connection");
    }

    /** Holds the request without answering until the server is closed. */
    public static Fault hang() {
        return new Fault(Kind.HANG, 0, null, 0, "hang");
    }

    /** The same fault, applied after waiting {@code delay} first. */
    public Fault after(Duration delay) {
        return new Fault(kind, status, body, delayMs + delay.toMillis(), label + " after " + delay.toMillis() + "ms");
    }

    @Override
    public String toString() {
        return label;
    }
}
