package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.utils.PropertyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for A31 (toggle-key allowlist) and A32 (homeHall
 * validated against the real halls list) using the same headless,
 * DB-backed bootstrap as {@link com.calplus.ihrgstats.telegrambot.utils.CommandLogicSmokeTest}.
 */
public class CommandSettingsTest {

    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN_USER_ID = "test_admin";

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        System.setProperty("SETTINGS_CURRENTYEAR", "2026");
        System.setProperty("TELEGRAM_ADMIN_USERID", ADMIN_USER_ID);

        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
        System.clearProperty("TELEGRAM_ADMIN_USERID");
        System.clearProperty("SETTINGS_HOMEHALL");
        System.clearProperty("SETTINGS_ALLOWNONADMINUPLOADS");
    }

    @Test
    void handleToggle_rejectsNonAllowlistedKey_insteadOfWritingArbitraryValue() {
        CommandSettings settings = new CommandSettings();
        String before = PropertyResolver.getProperty("settings.currentYear", "");

        String response = settings.handleToggle("setting_toggle_settings.currentYear", ADMIN_USER_ID);

        assertTrue(response.contains("not a valid toggleable setting"), "Response: " + response);
        assertEquals(before, PropertyResolver.getProperty("settings.currentYear", ""),
                "A forged/stale toggle callback must never be able to overwrite a non-boolean setting like currentYear");
    }

    @Test
    void handleToggle_allowsAnAllowlistedBooleanSetting() {
        CommandSettings settings = new CommandSettings();
        String before = PropertyResolver.getProperty("settings.allowNonAdminUploads", "");

        String response = settings.handleToggle("setting_toggle_settings.allowNonAdminUploads", ADMIN_USER_ID);

        assertTrue(response.contains("Successfully"), "Response: " + response);
        String after = PropertyResolver.getProperty("settings.allowNonAdminUploads", "");
        assertNotEquals(before, after, "The allowlisted boolean setting should have actually toggled");
    }

    @Test
    void handleHomeHallCallback_rejectsAnUnrecognizedHall() {
        CommandSettings settings = new CommandSettings();

        String response = settings.handleHomeHallCallback("setting_homeHall_NotARealHall", ADMIN_USER_ID);

        assertTrue(response.contains("not a recognized hall"), "Response: " + response);
        assertEquals("", PropertyResolver.getProperty("settings.homeHall", ""),
                "An unrecognized hall value must never be written into settings.homeHall");
    }

    @Test
    void handleHomeHallCallback_acceptsARealHall_andCanonicalizesCase() {
        CommandSettings settings = new CommandSettings();

        String response = settings.handleHomeHallCallback("setting_homeHall_banyan", ADMIN_USER_ID);

        assertTrue(response.contains("Successfully"), "Response: " + response);
        assertEquals("Banyan", PropertyResolver.getProperty("settings.homeHall", ""),
                "The stored value should be the canonical hall_name, not whatever case the input used");
    }

    @Test
    void handleTextInput_manualHomeHall_rejectsAnUnrecognizedHall() {
        CommandSettings settings = new CommandSettings();
        settings.handleHomeHallCallback("setting_homeHall_manual", ADMIN_USER_ID);

        String response = settings.handleTextInput(ADMIN_USER_ID, "Definitely Not A Hall");

        assertTrue(response.contains("not a recognized hall"), "Response: " + response);
        assertEquals("", PropertyResolver.getProperty("settings.homeHall", ""));
    }

    @Test
    void handleTextInput_manualHomeHall_acceptsARealHall() {
        CommandSettings settings = new CommandSettings();
        settings.handleHomeHallCallback("setting_homeHall_manual", ADMIN_USER_ID);

        String response = settings.handleTextInput(ADMIN_USER_ID, "4");

        assertTrue(response.contains("Successfully"), "Response: " + response);
        assertEquals("4", PropertyResolver.getProperty("settings.homeHall", ""));
    }
}
