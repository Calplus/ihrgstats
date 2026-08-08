package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the HTTP-timeout fix: java.net.http has NO default
 * connect timeout, and a client built without one can block forever on a
 * silently dropped connection. Every client in the app comes from this
 * factory, so the factory itself must actually apply the timeout.
 */
public class HttpClientFactoryTest {

    @Test
    void newClient_hasTheConnectTimeoutApplied() {
        HttpClient client = HttpClientFactory.newClient();

        assertTrue(client.connectTimeout().isPresent(),
                "a client without a connect timeout can hang forever on a dead connection");
        assertEquals(HttpClientFactory.CONNECT_TIMEOUT, client.connectTimeout().get());
    }

    /**
     * The long-poll request timeout must comfortably exceed the 30s
     * server-side hold requested in the getUpdates URL - if it ever shrank
     * to 30s or below, every healthy-but-empty poll would time out.
     */
    @Test
    void longPollTimeout_exceedsTheServerSideHold() {
        assertTrue(HttpClientFactory.LONG_POLL_TIMEOUT.compareTo(Duration.ofSeconds(30)) > 0,
                "the long-poll timeout must be strictly greater than the 30s server-side hold, got "
                        + HttpClientFactory.LONG_POLL_TIMEOUT);
    }
}
