package com.calplus.ihrgstats.telegrambot.listener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link TelegramListener#parseCappedlistFilename(String)} (A19)
 * and for the settings.allowNonAdminUploads / settings.allowAllChannelsProcessing
 * live-reload getters (M1).
 */
public class TelegramListenerTest {

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        // Non-empty, so isAllowAllChannelsProcessing() actually depends on the
        // settings property below instead of being forced true by an empty chat ID.
        System.setProperty("TELEGRAM_PUBLIC_CHATID", "12345");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("TELEGRAM_PUBLIC_CHATID");
        System.clearProperty("SETTINGS_ALLOWALLCHANNELSPROCESSING");
        System.clearProperty("SETTINGS_ALLOWNONADMINUPLOADS");
    }

    @Test
    void isAllowAllChannelsProcessing_reflectsSettingChange_withoutReconstructingListener() {
        // Regression test for M1: this used to be cached in a field read once
        // at startup, so a /settings toggle had no effect until the bot was
        // restarted - despite /help promising it takes effect immediately.
        TelegramListener listener = new TelegramListener();

        System.setProperty("SETTINGS_ALLOWALLCHANNELSPROCESSING", "false");
        assertFalse(listener.isAllowAllChannelsProcessing(),
                "toggling the setting must take effect on the existing listener instance, not just at startup");

        System.setProperty("SETTINGS_ALLOWALLCHANNELSPROCESSING", "true");
        assertTrue(listener.isAllowAllChannelsProcessing(),
                "flipping the setting back must also take effect immediately, without reconstructing the listener");
    }

    @Test
    void isAllowNonAdminUploads_reflectsSettingChange_withoutReconstructingListener() {
        TelegramListener listener = new TelegramListener();

        System.setProperty("SETTINGS_ALLOWNONADMINUPLOADS", "true");
        assertTrue(listener.isAllowNonAdminUploads(),
                "toggling the setting must take effect on the existing listener instance, not just at startup");

        System.setProperty("SETTINGS_ALLOWNONADMINUPLOADS", "false");
        assertFalse(listener.isAllowNonAdminUploads(),
                "flipping the setting back must also take effect immediately, without reconstructing the listener");
    }

    @Test
    void parseCappedlistFilename_withYearPrefix_extractsThatYear() {
        // Regression test for A19: the year prefix was accepted by the
        // filename pattern but silently ignored - always processed under
        // whatever the current-year setting happened to be, regardless of
        // what the filename actually said.
        TelegramListener.ParsedCappedlistFilename parsed = TelegramListener.parseCappedlistFilename("2024_cappedlist.csv");
        assertTrue(parsed.matched);
        assertEquals(2024, parsed.year, "The year in the filename must be honored, not silently discarded");
    }

    @Test
    void parseCappedlistFilename_withoutYearPrefix_yearIsNull() {
        TelegramListener.ParsedCappedlistFilename parsed = TelegramListener.parseCappedlistFilename("cappedlist.csv");
        assertTrue(parsed.matched);
        assertNull(parsed.year, "No prefix present - caller falls back to the current-year setting");
    }

    @Test
    void parseCappedlistFilename_unrelatedFilename_doesNotMatch() {
        TelegramListener.ParsedCappedlistFilename parsed = TelegramListener.parseCappedlistFilename("2024_round_1.csv");
        assertFalse(parsed.matched);
    }
}
