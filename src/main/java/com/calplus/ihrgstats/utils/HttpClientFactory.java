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

    private HttpClientFactory() {
    }

    /** New client with the standard connect timeout. */
    public static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }
}
