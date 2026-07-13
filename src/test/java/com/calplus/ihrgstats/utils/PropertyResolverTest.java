package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for A12: PropertyResolver used to reopen and re-parse
 * application.properties from the classpath on every single call. It now
 * caches the RAW (unresolved) parsed properties per resource name - the
 * resource itself is a compiled-in file that never changes during a running
 * process - while still resolving ${VAR:default} placeholders against
 * System properties fresh on every call, so a runtime settings change (via
 * /settings) is still picked up immediately.
 */
public class PropertyResolverTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("SETTINGS_TIMEZONE");
    }

    @Test
    void getProperty_resolvesARealKnownProperty() {
        String value = PropertyResolver.getProperty("settings.timezone");
        assertNotNull(value, "settings.timezone is a real key in application.properties and must resolve to something");
    }

    @Test
    void getProperty_reflectsASystemPropertyChange_immediately_despiteRawTemplateCaching() {
        System.setProperty("SETTINGS_TIMEZONE", "7");
        assertEquals("7", PropertyResolver.getProperty("settings.timezone"));

        System.setProperty("SETTINGS_TIMEZONE", "9");
        assertEquals("9", PropertyResolver.getProperty("settings.timezone"),
                "Changing the underlying system property must be reflected on the very next call - "
                        + "only the raw ${VAR:default} template is cached, never the resolved value");
    }

    @Test
    void loadAndResolve_returnsAFreshResolvedObjectEachCall_withConsistentKeys() throws IOException {
        Properties first = PropertyResolver.loadAndResolve("application.properties");
        Properties second = PropertyResolver.loadAndResolve("application.properties");

        assertNotSame(first, second, "Each call must return its own resolved Properties object, not a shared mutable instance");
        assertEquals(first.stringPropertyNames(), second.stringPropertyNames());
    }

    @Test
    void loadAndResolve_unknownResource_stillThrowsIOException_everyCall() {
        assertThrows(IOException.class, () -> PropertyResolver.loadAndResolve("this-file-does-not-exist.properties"));
        // A second call must fail the same way, not incorrectly serve a cached failure/empty result.
        assertThrows(IOException.class, () -> PropertyResolver.loadAndResolve("this-file-does-not-exist.properties"));
    }
}
