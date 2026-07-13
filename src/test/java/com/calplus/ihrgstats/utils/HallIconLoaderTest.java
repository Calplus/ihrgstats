package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for A38: hall icons used to be re-opened/re-decoded from
 * the classpath on every draw call with the InputStream never closed.
 * loadRawIcon now caches the decoded icon per hall identifier.
 */
public class HallIconLoaderTest {

    @Test
    void loadRawIcon_knownHall_returnsANonNullImage() {
        BufferedImage icon = HallIconLoader.loadRawIcon("Banyan");
        assertNotNull(icon, "A real hall icon resource should decode successfully");
    }

    @Test
    void loadRawIcon_isCaseInsensitive_andCachesTheSameInstance() {
        BufferedImage first = HallIconLoader.loadRawIcon("Banyan");
        BufferedImage second = HallIconLoader.loadRawIcon("BANYAN");
        assertSame(first, second, "Different-cased lookups for the same hall must hit the same cache entry, not re-decode");
    }

    @Test
    void loadRawIcon_unknownHall_fallsBackToUnknownIcon() {
        BufferedImage icon = HallIconLoader.loadRawIcon("ThisHallDoesNotExist12345");
        assertNotNull(icon, "An unrecognized hall identifier should still resolve via the unknown.png fallback");
    }
}
