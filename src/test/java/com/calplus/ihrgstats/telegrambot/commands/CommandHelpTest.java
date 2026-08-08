package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the /help "🔙 Back" button: both submenus carry a
 * help_back callback, and handleBack must return the user to the MAIN help
 * menu - before the fix the listener routed help_back to no branch at all,
 * so clicking Back stripped the keyboard and stranded the user.
 */
public class CommandHelpTest {

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
    void handleBack_returnsTheMainHelpMenu() {
        CommandHelp help = new CommandHelp();
        CommandResponse main = help.handleCommand("help_user");
        CommandResponse back = help.handleBack("help_user");

        assertNotNull(back.buttonConfig, "Back must return a menu with buttons, not a bare message");
        assertArrayEquals(main.buttonConfig.labels, back.buttonConfig.labels,
                "Back must land on exactly the main help menu's buttons");
        assertArrayEquals(main.buttonConfig.callbacks, back.buttonConfig.callbacks);
        assertEquals(main.message, back.message, "Back must show the main help menu's own text");
    }

    @Test
    void commandsSubmenu_carriesTheBackButton_theListenerRoutes() {
        CommandHelp help = new CommandHelp();
        help.handleCommand("help_user"); // creates the wizard session the category step requires
        CommandResponse commandsMenu = help.handleCategorySelection("help_user", "commands");

        assertNotNull(commandsMenu.buttonConfig, "the commands submenu must offer buttons");
        assertTrue(Arrays.asList(commandsMenu.buttonConfig.callbacks).contains("help_back"),
                "the commands submenu must carry the help_back callback the listener routes to handleBack");
    }
}
