package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.LogHelper;
import com.calplus.ihrgstats.utils.TelegramCommandUtils;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Command handler for /help command.
 * Provides interactive help for commands and file uploads with button navigation.
 */
public class CommandHelp {
    private final LogHelper logHelper;
    
    // State management for multi-step selection
    private static final Map<String, HelpSelectionState> userSelectionStates = new HashMap<>();
    
    private static class HelpSelectionState extends SelectionState {
        String category;  // "commands" or "fileupload"
    }
    
    public CommandHelp() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.logHelper = new LogHelper();
    }
    
    /**
     * Handles the /help command (initial call)
     */
    public CommandResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("User %s requested /help", userId));
        
        // Clean up old states
        TelegramCommandUtils.cleanupOldStates(userSelectionStates);
        
        // Create initial state
        HelpSelectionState state = new HelpSelectionState();
        userSelectionStates.put(userId, state);
        
        String message = "ℹ️ *IHRG Stats Bot Help*\n\n" +
                        "What would you like help with?";
        
        String[] labels = {"📋 Commands", "📁 File Uploads"};
        String[] callbacks = {"help_category_commands", "help_category_fileupload"};
        
        return new CommandResponse(message, new ButtonConfig(labels, callbacks));
    }
    
    /**
     * Handles category selection (commands or file uploads)
     */
    public CommandResponse handleCategorySelection(String userId, String category) {
        logHelper.logInfo(String.format("User %s selected help category: %s", userId, category));
        
        HelpSelectionState state = userSelectionStates.get(userId);
        if (state == null) {
            return new CommandResponse("❌ Session expired. Please use /help to start again.", (java.nio.file.Path) null);
        }
        
        state.category = category;
        
        if ("commands".equals(category)) {
            userSelectionStates.remove(userId);
            return generateCommandsHelp();
        } else if ("fileupload".equals(category)) {
            return generateFileUploadMenu();
        }
        
        return new CommandResponse("❌ Invalid category selection.", (java.nio.file.Path) null);
    }
    
    /**
     * Handles file type selection
     */
    public CommandResponse handleFileTypeSelection(String userId, String fileType) {
        logHelper.logInfo(String.format("User %s requested file upload help for: %s", userId, fileType));
        
        userSelectionStates.remove(userId);
        
        return generateFileTypeHelp(fileType);
    }
    
    /**
     * Handles cancellation
     */
    public CommandResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new CommandResponse("ℹ️ Help session cancelled.", (java.nio.file.Path) null);
    }
    
    /**
     * Generates commands help text
     */
    private CommandResponse generateCommandsHelp() {
        StringBuilder message = new StringBuilder();
        message.append("📋 **Available Commands**\n\n");
        
        message.append("**Information Commands:**\n");
        message.append("• /about \\- Bot information and admin contact\n");
        message.append("• /help \\- This help menu\n\n");
        
        message.append("**Player Commands:**\n");
        message.append("• /rankplayers \\- View ranked player lists by TrueElo\n");
        message.append("• /compareplayers \\- Compare two players side\\-by\\-side\n");
        message.append("• /infoplayer \\- View detailed information for a single player\n\n");
        
        message.append("**Hall Commands:**\n");
        message.append("• /rankhalls \\- View ranked hall lists by average ELO\n");
        message.append("• /comparehalls \\- Compare two halls side\\-by\\-side\n");
        message.append("• /infohall \\- View detailed information for a single hall\n\n");
        
        message.append("**Match Commands:**\n");
        message.append("• /infomatch \\- View match information and scores for a specific round\n\n");
        
        message.append("**Database Commands:**\n");
        message.append("• /settings \\- View/modify database settings\n");
        message.append("• /exportplayers \\- Export player data\n");
        message.append("• /exportdatabase \\- Export full database\n\n");
        
        message.append("_For file upload help, use /help and select 'File Uploads'_");
        
        logHelper.logSuccess("Generated commands help");
        return new CommandResponse(message.toString(), (java.nio.file.Path) null);
    }
    
    /**
     * Generates file upload menu
     */
    private CommandResponse generateFileUploadMenu() {
        String message = "📁 **File Upload Help**\n\n" +
                        "Select a file type to learn more about upload requirements:";
        
        String[] labels = {
            "Round CSV", "Player Export", 
            "Capped Players", "Cancel"
        };
        String[] callbacks = {
            "help_filetype_roundcsv", "help_filetype_playerexport",
            "help_filetype_cappedplayers", "help_cancel"
        };
        
        return new CommandResponse(message, new ButtonConfig(labels, callbacks));
    }
    
    /**
     * Generates file type specific help
     */
    private CommandResponse generateFileTypeHelp(String fileType) {
        StringBuilder message = new StringBuilder();
        
        switch (fileType) {
            case "roundcsv":
                message.append("🎮 **Round CSV File Format**\n\n");
                message.append("Upload a CSV file with match results for a round\\.\n\n");
                message.append("**Required Columns:**\n");
                message.append("```\n");
                message.append("name1, hall1, winby1, name2, hall2, winby2\n");
                message.append("```\n\n");
                message.append("**Example:**\n");
                message.append("```\n");
                message.append("name1      | hall1 | winby1 | name2      | hall2 | winby2\n");
                message.append("John Doe   | 4     | 1      | Jane Smith | 5     | 0\n");
                message.append("Bob Lee    | 4     | draw   | Alice Wong | 5     | draw\n");
                message.append("WALKOVER   |       |        | Chris Tan  | 6     |\n");
                message.append("```\n\n");
                message.append("**Rules:**\n");
                message.append("• `name1` / `name2` \\- Player names \\(required, use WALKOVER for byes\\)\n");
                message.append("• `hall1` / `hall2` \\- Hall identifiers \\(required for regular games\\)\n");
                message.append("• `winby1` / `winby2` \\- Result: `1` \\(win\\), `0` \\(loss\\), `draw`, or empty\n");
                message.append("• Both winby fields must be: both 'draw', or '0'/'1' \\(exactly one '1'\\)\n");
                message.append("• For WALKOVER: opponent wins by default, hall/winby optional\n");
                break;
                
            case "playerexport":
                message.append("📊 **Player Export File Format**\n\n");
                message.append("Upload a CSV file to import player data with ELO ratings\\.\n\n");
                message.append("**Required Columns:**\n");
                message.append("```\n");
                message.append("name, trueElo, perfElo, rdTrueElo, volTrueElo,\n");
                message.append("rdPerfElo, volPerfElo, lastRound, lastHall, capped\n");
                message.append("```\n\n");
                message.append("**Example:**\n");
                message.append("```\n");
                message.append("name       | trueElo | perfElo | rdTrueElo | volTrueElo\n");
                message.append("John Doe   | 1500    | 1450    | 350.0     | 0.06\n");
                message.append("\n");
                message.append("rdPerfElo | volPerfElo | lastRound | lastHall | capped\n");
                message.append("350.0     | 0.06       | 6         | 4        | FALSE\n");
                message.append("```\n\n");
                message.append("**Rules:**\n");
                message.append("• `name` \\- Player's full name \\(required\\)\n");
                message.append("• `trueElo` / `perfElo` \\- ELO ratings \\(integers, required\\)\n");
                message.append("• `rdTrueElo` / `volTrueElo` \\- Rating deviation/volatility for trueElo \\(decimals\\)\n");
                message.append("• `rdPerfElo` / `volPerfElo` \\- Rating deviation/volatility for perfElo \\(decimals\\)\n");
                message.append("• `lastRound` \\- Last round played \\(e\\.g\\., '6', 't8'\\)\n");
                message.append("• `lastHall` \\- Hall identifier \\(required\\)\n");
                message.append("• `capped` \\- TRUE/FALSE for capped status\n");
                break;
                
            case "cappedplayers":
                message.append("🔒 **Capped Players File Format**\n\n");
                message.append("Upload a CSV or Excel file listing capped players\\.\n\n");
                message.append("**Required Columns:**\n");
                message.append("```\n");
                message.append("name, hall\n");
                message.append("```\n\n");
                message.append("**Example:**\n");
                message.append("```\n");
                message.append("name       | hall\n");
                message.append("John Doe   | 4\n");
                message.append("Jane Smith | 5\n");
                message.append("```\n\n");
                message.append("**Rules:**\n");
                message.append("• `name` \\- Player's full name \\(must match database\\)\n");
                message.append("• `hall` \\- Hall identifier \\(number or name\\)\n");
                message.append("• All players listed will be marked as capped\n");
                break;
                
            default:
                return new CommandResponse("❌ Unknown file type.", (java.nio.file.Path) null);
        }
        
        logHelper.logSuccess(String.format("Generated file upload help for: %s", fileType));
        return new CommandResponse(message.toString(), (java.nio.file.Path) null);
    }
}
