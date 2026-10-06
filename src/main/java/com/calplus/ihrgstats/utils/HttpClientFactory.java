package com.calplus.ihrgstats.utils;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Central factory for the app's HTTP clients and the standard request
 * timeouts.
 *
 * java.net.http has NO default connect or response timeout: a silently
 * dropped connection (NAT/firewall drop, network flap) leaves
 * {@code HttpClient.send} blocked forever. For the Telegram polling thread
 * that means the bot goes permanently deaf - while the status heartbeat,
 * running on its own executor, keeps reporting it online. Every client must
 * come from {@link #newClient()} and every request must set one of the
 * timeout constants below.
 */
public final class HttpClientFactory {

    /** Connect timeout applied to every client built here. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Request timeout for ordinary API calls (sends, lookups, small JSON). */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Request timeout for the Telegram getUpdates long poll. Must comfortably
     * exceed the 30s server-side hold requested in the URL, so it only fires
     * on a genuinely dead connection - never on a healthy empty poll.
     */
    public static final Duration LONG_POLL_TIMEOUT = Duration.ofSeconds(45);

    /**
     * Request timeout for requests that carry a file payload in either
     * direction (multipart photo/document uploads, database exports sent to
     * DMs, Telegram file downloads) - sized for multi-MB bodies on a slow
     * connection, not for the small-JSON case.
     */
    public static final Duration FILE_TRANSFER_TIMEOUT = Duration.ofSeconds(120);

    /*
     * Test-only overrides, in milliseconds. Production never sets them, so the
     * accessors below return exactly the constants above. They let a harness
     * exercise a timeout path against a local fake server in milliseconds
     * instead of sleeping for the real 10/30/45/120 seconds. Read on every
     * call, never cached, so they can change between scenarios in one JVM.
     */
    public static final String CONNECT_TIMEOUT_PROPERTY = "ihrgstats.http.connectTimeoutMs";
    public static final String REQUEST_TIMEOUT_PROPERTY = "ihrgstats.http.requestTimeoutMs";
    public static final String LONG_POLL_TIMEOUT_PROPERTY = "ihrgstats.http.longPollTimeoutMs";
    public static final String FILE_TRANSFER_TIMEOUT_PROPERTY = "ihrgstats.http.fileTransferTimeoutMs";

    private HttpClientFactory() {
    }

    /** New client with the standard connect timeout. */
    public static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout())
                .build();
    }

    /** {@link #CONNECT_TIMEOUT}, unless overridden by {@link #CONNECT_TIMEOUT_PROPERTY}. */
    public static Duration connectTimeout() {
        return override(CONNECT_TIMEOUT_PROPERTY, CONNECT_TIMEOUT);
    }

    /** {@link #REQUEST_TIMEOUT}, unless overridden by {@link #REQUEST_TIMEOUT_PROPERTY}. */
    public static Duration requestTimeout() {
        return override(REQUEST_TIMEOUT_PROPERTY, REQUEST_TIMEOUT);
    }

    /** {@link #LONG_POLL_TIMEOUT}, unless overridden by {@link #LONG_POLL_TIMEOUT_PROPERTY}. */
    public static Duration longPollTimeout() {
        return override(LONG_POLL_TIMEOUT_PROPERTY, LONG_POLL_TIMEOUT);
    }

    /** {@link #FILE_TRANSFER_TIMEOUT}, unless overridden by {@link #FILE_TRANSFER_TIMEOUT_PROPERTY}. */
    public static Duration fileTransferTimeout() {
        return override(FILE_TRANSFER_TIMEOUT_PROPERTY, FILE_TRANSFER_TIMEOUT);
    }

    /** A positive whole number of milliseconds from the property, else the default. */
    private static Duration override(String property, Duration defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long millis = Long.parseLong(value.trim());
            return millis > 0 ? Duration.ofMillis(millis) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
