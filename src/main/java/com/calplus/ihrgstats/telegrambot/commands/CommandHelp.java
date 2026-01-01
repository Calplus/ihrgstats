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
     * Handles back button - returns to main help menu
     */
    public CommandResponse handleBack(String userId) {
        logHelper.logInfo(String.format("User %s requested back to help menu", userId));
        return handleCommand(userId);
    }
    
    /**
     * Handles cancellation
     */
    public CommandResponse handleCancel(String userId) {
        userSelectionStates.remove(userId);
        return new CommandResponse("ℹ️ Help session cancelled.", (java.nio.file.Path) null);
    }
    
    /**
     * Generates commands help text with buttons for detailed help
     */
    private CommandResponse generateCommandsHelp() {
        StringBuilder message = new StringBuilder();
        message.append("📋 **Available Commands**\n\n");
        
        // High-level overview of commands
        message.append("**Player Commands:**\n");
        message.append("• `/rankplayers` - View ranked list of all players\n");
        message.append("• `/compareplayers` - Compare two players side-by-side\n");
        message.append("• `/infoplayer` - View detailed info for a single player\n\n");
        
        message.append("**Hall Commands:**\n");
        message.append("• `/rankhalls` - View ranked list of all halls\n");
        message.append("• `/comparehalls` - Compare two halls side-by-side\n");
        message.append("• `/infohall` - View detailed info for a single hall\n\n");
        
        message.append("**Match & Data Commands:**\n");
        message.append("• `/infomatch` - View match details for a specific round\n");
        message.append("• `/exportplayers` - Export player data to CSV/Excel\n");
        message.append("• `/exportdatabase` - Export complete database\n\n");
        
        message.append("**Utility Commands:**\n");
        message.append("• `/about` - Information about this bot\n");
        message.append("• `/help` - This interactive help menu\n");
        message.append("• `/settings` - View/modify database settings\n\n");
        
        message.append("Click on any command below for detailed information:\n");
        
        String[] labels = {
            "/about", "/help",
            "/rankplayers", "/compareplayers",
            "/infoplayer", "/rankhalls",
            "/comparehalls", "/infohall",
            "/infomatch", "/settings",
            "/exportplayers", "/exportdatabase",
            "🔙 Back", "❌ Cancel"
        };
        String[] callbacks = {
            "help_cmd_about", "help_cmd_help",
            "help_cmd_rankplayers", "help_cmd_compareplayers",
            "help_cmd_infoplayer", "help_cmd_rankhalls",
            "help_cmd_comparehalls", "help_cmd_infohall",
            "help_cmd_infomatch", "help_cmd_settings",
            "help_cmd_exportplayers", "help_cmd_exportdatabase",
            "help_back", "help_cancel"
        };
        
        logHelper.logSuccess("Generated commands help menu with buttons");
        return new CommandResponse(message.toString(), new ButtonConfig(labels, callbacks, 2));
    }
    
    /**
     * Generates file upload menu
     */
    private CommandResponse generateFileUploadMenu() {
        String message = "📁 **File Upload Help**\n\n" +
                        "Select a file type to learn more about upload requirements:";
        
        String[] labels = {
            "Round CSV",
            "Player Export", 
            "Capped Players",
            "🔙 Back",
            "❌ Cancel"
        };
        String[] callbacks = {
            "help_filetype_roundcsv",
            "help_filetype_playerexport",
            "help_filetype_cappedplayers",
            "help_back",
            "help_cancel"
        };
        
        return new CommandResponse(message, new ButtonConfig(labels, callbacks, 1));
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
    
    /**
     * Handles command detail selection
     */
    public CommandResponse handleCommandDetail(String userId, String command) {
        logHelper.logInfo(String.format("User %s requested detailed help for: %s", userId, command));
        
        userSelectionStates.remove(userId);
        
        return generateCommandDetail(command);
    }
    
    /**
     * Generates detailed help for a specific command
     */
    private CommandResponse generateCommandDetail(String command) {
        StringBuilder message = new StringBuilder();
        
        switch (command) {
            case "about":
                message.append("ℹ️ **/about Command**\n\n");
                message.append("**Description:**\n");
                message.append("Displays information about the bot, including version, features, and admin contact\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/about`\n\n");
                message.append("**Output:**\n");
                message.append("• Bot version and description\n");
                message.append("• List of available features\n");
                message.append("• Admin contact information\n");
                break;
                
            case "help":
                message.append("❓ **/help Command**\n\n");
                message.append("**Description:**\n");
                message.append("Interactive help menu with options for command help or file upload instructions\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/help`\n\n");
                message.append("**Options:**\n");
                message.append("• Commands \\- List of all available commands with detailed descriptions\n");
                message.append("• File Uploads \\- Instructions for uploading round CSVs, player exports, and capped players\n");
                break;
                
            case "rankplayers":
                message.append("🏆 **/rankplayers Command**\n\n");
                message.append("**Description:**\n");
                message.append("Displays ranked list of players sorted by TrueElo rating\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/rankplayers`\n\n");
                message.append("**Steps:**\n");
                message.append("1\\. Select a round from the list\n");
                message.append("2\\. View ranked player table with ELO ratings\n\n");
                message.append("**Output:**\n");
                message.append("• Player rankings with TrueElo scores\n");
                message.append("• Hall affiliations\n");
                message.append("• Win/Loss/Draw statistics\n");
                break;
                
            case "compareplayers":
                message.append("⚖️ **/compareplayers Command**\n\n");
                message.append("**Description:**\n");
                message.append("Compare two players side\\-by\\-side with stats, ELO progression, and head\\-to\\-head record\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/compareplayers`\n\n");
                message.append("**Steps:**\n");
                message.append("1\\. Select first player from the list\n");
                message.append("2\\. Select second player from the list\n");
                message.append("3\\. View side\\-by\\-side comparison image\n\n");
                message.append("**Output:**\n");
                message.append("• ELO per round comparison\n");
                message.append("• Victory records for both players\n");
                message.append("• Head\\-to\\-head matchup statistics\n");
                message.append("• Win probability calculation\n");
                break;
                
            case "infoplayer":
                message.append("👤 **/infoplayer Command**\n\n");
                message.append("**Description:**\n");
                message.append("View detailed information for a single player including stats and match history\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/infoplayer`\n\n");
                message.append("**Steps:**\n");
                message.append("1\\. Select a player from the list\n");
                message.append("2\\. View detailed player information image\n\n");
                message.append("**Output:**\n");
                message.append("• Player statistics \\(ELO, rank, W/L/D\\)\n");
                message.append("• Stats per round with deltas\n");
                message.append("• Complete victory record with opponents\n");
                message.append("• Hall affiliation and capped status\n");
                break;
                
            case "rankhalls":
                message.append("🏛️ **/rankhalls Command**\n\n");
                message.append("**Description:**\n");
                message.append("Displays ranked list of halls sorted by average ELO rating\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/rankhalls`\n\n");
                message.append("**Steps:**\n");
                message.append("1\\. Select a round from the list\n");
                message.append("2\\. View ranked hall table with average ELO\n\n");
                message.append("**Output:**\n");
                message.append("• Hall rankings with average ELO\n");
                message.append("• Number of players per hall\n");
                message.append("• Win/Loss/Draw statistics\n");
                break;
                
            case "comparehalls":
                message.append("⚖️ **/comparehalls Command**\n\n");
                message.append("**Description:**\n");
                message.append("Compare two halls side\\-by\\-side with stats, ELO progression, and head\\-to\\-head record\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/comparehalls`\n\n");
                message.append("**Steps:**\n");
                message.append("1\\. Select first hall from the list\n");
                message.append("2\\. Select second hall from the list\n");
                message.append("3\\. View side\\-by\\-side comparison image\n\n");
                message.append("**Output:**\n");
                message.append("• Hall ELO per round comparison\n");
                message.append("• Victory records for both halls\n");
                message.append("• Head\\-to\\-head matchup statistics\n");
                message.append("• Win probability calculation\n");
                break;
                
            case "infohall":
                message.append("🏛️ **/infohall Command**\n\n");
                message.append("**Description:**\n");
                message.append("View detailed information for a single hall including stats and match history\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/infohall`\n\n");
                message.append("**Steps:**\n");
                message.append("1\\. Select a hall from the list\n");
                message.append("2\\. View detailed hall information image\n\n");
                message.append("**Output:**\n");
                message.append("• Hall statistics \\(Average ELO, rank, W/L/D\\)\n");
                message.append("• Player roster with individual stats\n");
                message.append("• Hall ELO per round with deltas\n");
                message.append("• Complete victory record against other halls\n");
                break;
                
            case "infomatch":
                message.append("🎮 **/infomatch Command**\n\n");
                message.append("**Description:**\n");
                message.append("View match information and detailed scores for a specific round\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/infomatch`\n\n");
                message.append("**Steps:**\n");
                message.append("1\\. Select a round from the list\n");
                message.append("2\\. View match details image\n\n");
                message.append("**Output:**\n");
                message.append("• Match info \\(halls, ELO, score\\)\n");
                message.append("• Individual game results table\n");
                message.append("• Player names and outcomes for each board\n");
                message.append("• Game\\-by\\-game breakdown\n");
                break;
                
            case "settings":
                message.append("⚙️ **/settings Command**\n\n");
                message.append("**Description:**\n");
                message.append("View and modify database configuration settings\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/settings`\n\n");
                message.append("**Options:**\n");
                message.append("• View current settings\n");
                message.append("• Modify last round\n");
                message.append("• Update configuration parameters\n\n");
                message.append("**Note:** Admin privileges may be required for some operations\\.\n");
                break;
                
            case "exportplayers":
                message.append("📤 **/exportplayers Command**\n\n");
                message.append("**Description:**\n");
                message.append("Export player data to CSV or Excel format\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/exportplayers`\n\n");
                message.append("**Steps:**\n");
                message.append("1\\. Select export format \\(CSV or Excel\\)\n");
                message.append("2\\. Download exported file\n\n");
                message.append("**Output:**\n");
                message.append("• Complete player roster with all statistics\n");
                message.append("• ELO ratings, rounds played, capped status\n");
                message.append("• Suitable for backup or external analysis\n");
                break;
                
            case "exportdatabase":
                message.append("💾 **/exportdatabase Command**\n\n");
                message.append("**Description:**\n");
                message.append("Export complete database including all rounds, players, and halls\\.\n\n");
                message.append("**Usage:**\n");
                message.append("`/exportdatabase`\n\n");
                message.append("**Steps:**\n");
                message.append("1\\. Confirm export operation\n");
                message.append("2\\. Download exported database file\n\n");
                message.append("**Output:**\n");
                message.append("• Full database backup\n");
                message.append("• All historical data included\n");
                message.append("• Can be used for migration or backup purposes\n\n");
                message.append("**Note:** Admin privileges may be required\\.\n");
                break;
                
            default:
                return new CommandResponse("❌ Unknown command.", (java.nio.file.Path) null);
        }
        
        logHelper.logSuccess(String.format("Generated detailed help for command: %s", command));
        return new CommandResponse(message.toString(), (java.nio.file.Path) null);
    }
}
