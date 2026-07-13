package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for A32: the .env.properties writer must reject control
 * characters (e.g. a newline), since the file is a plain key=value-per-line
 * format and an unescaped newline in a written value would let one property
 * update silently forge a second, attacker-chosen property line.
 */
public class EnvironmentManagerTest {

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void setProperty_rejectsNewlineInValue_insteadOfInjectingAnExtraLine() {
        EnvironmentManager envManager = new EnvironmentManager();
        assertThrows(IllegalArgumentException.class, () ->
                envManager.setProperty("SETTINGS_HOMEHALL", "Banyan\nTELEGRAM_BOT_TOKEN=forged"));
    }

    @Test
    void setProperty_rejectsControlCharacterInKey() {
        EnvironmentManager envManager = new EnvironmentManager();
        assertThrows(IllegalArgumentException.class, () ->
                envManager.setProperty("SOME_KEY\r\nFORGED_KEY", "value"));
    }

    @Test
    void setProperties_rejectsControlCharacterInAnyEntry_beforeWritingAnyOfThem() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.setProperty("SETTINGS_HOMEHALL", "Banyan");

        assertThrows(IllegalArgumentException.class, () -> envManager.setProperties(Map.of(
                "SETTINGS_TIMEZONE", "8",
                "SETTINGS_CURRENTYEAR", "2026\nFORGED=1"
        )));

        // Neither entry should have been applied - the bad one must be caught
        // before any write happens, not partway through.
        EnvironmentManager reloaded = new EnvironmentManager();
        assertNull(reloaded.getProperty("SETTINGS_TIMEZONE"));
        assertEquals("Banyan", reloaded.getProperty("SETTINGS_HOMEHALL"), "Unrelated, already-saved properties must be untouched");
    }

    @Test
    void setProperty_allowsOrdinaryValues() {
        EnvironmentManager envManager = new EnvironmentManager();
        assertDoesNotThrow(() -> envManager.setProperty("SETTINGS_HOMEHALL", "Banyan"));
        assertEquals("Banyan", envManager.getProperty("SETTINGS_HOMEHALL"));
    }
}
