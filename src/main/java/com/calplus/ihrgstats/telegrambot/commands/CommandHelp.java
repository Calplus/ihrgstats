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
        
        String message = "ℹ️ <i>IHRG Stats Bot Help</i>\n\n" +
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
        message.append("📋 <b>Available Commands</b>\n\n");
        
        // High-level overview of commands
        message.append("<b>Player Commands:</b>\n");
        message.append("• /rankplayers - Ranks all players in the database (highest to lowest elo)\n");
        message.append("• /compareplayers - Compare 2 players performance\n");
        message.append("• /infoplayer - Get details of a player\n\n");
        
        message.append("<b>Hall Commands:</b>\n");
        message.append("• /rankhalls - Ranks all halls in the database (highest to lowest avg. elo of top 5 players)\n");
        message.append("• /comparehalls - Compares 2 halls performance\n");
        message.append("• /infohall - Get details of a hall\n\n");
        
        message.append("<b>Match & Data Commands:</b>\n");
        message.append("• /infomatch - Get details of a match for a specific round\n");
        message.append("• /infomatchhall - Get detailed match information for a hall in a specific round\n");
        message.append("• /exportplayers - Exports latest player data as a .csv. File can be uploaded to set default values in a blank database table.\n\n");
        
        message.append("<b>Utility Commands:</b>\n");
        message.append("• /about - About this bot\n");
        message.append("• /help - i think you need help\n\n");
        
        message.append("<b>Administrator Commands:</b>\n");
        message.append("• /settings - (ADMIN) Change bot's settings\n\n");
        message.append("• /exportdatabase - (ADMIN) Exports database for debugging/storing data.\n\n");
        
        message.append("Click on any command below for detailed information:\n");
        
        String[] labels = {
            "/rankplayers", "/rankhalls",
            "/compareplayers", "/comparehalls",
            "/infoplayer", "/infohall",
            "/infomatch", "/infomatchhall",
            "/exportplayers", "/settings",
            "/exportdatabase", "/about",
            "/help", "🔙 Back",
            "❌ Cancel"
        };
        String[] callbacks = {
            "help_cmd_rankplayers", "help_cmd_rankhalls",
            "help_cmd_compareplayers", "help_cmd_comparehalls",
            "help_cmd_infoplayer", "help_cmd_infohall",
            "help_cmd_infomatch", "help_cmd_infomatchhall",
            "help_cmd_exportplayers", "help_cmd_settings",
            "help_cmd_exportdatabase", "help_cmd_about",
            "help_cmd_help", "help_back",
            "help_cancel"
        };
        
        logHelper.logSuccess("Generated commands help menu with buttons");
        return new CommandResponse(message.toString(), new ButtonConfig(labels, callbacks, 2));
    }
    
    /**
     * Generates file upload menu
     */
    private CommandResponse generateFileUploadMenu() {
        String message = "📁 <b>File Upload Help</b>\n\n" +
                        "Select a file type to learn more about upload requirements:";
        
        String[] labels = {
            "round_{n}.csv",
            "playerExport.csv", 
            "cappedlist.csv",
            "🔙 Back", "❌ Cancel"
        };
        String[] callbacks = {
            "help_filetype_roundcsv",
            "help_filetype_playerexport",
            "help_filetype_cappedplayers",
            "help_back", "help_cancel"
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
                message.append("<b><u>round_{n}.csv</u></b>\n\n");
                message.append("Upload a CSV file with match results for round {n}.\n");
                message.append("<i>n = {1, 2, 3, 4, 5, 6, t16, t8, t4, t2, t1}</i>\n\n");
                message.append("<b>Required Columns:</b>\n");
                message.append("<pre>\n");
                message.append("name1, hall1, winby1, name2, hall2, winby2\n");
                message.append("</pre>\n\n");
                message.append("<b>Example:</b>\n");
                message.append("<pre>\n");
                message.append("name1    | hall1 | winby1 | name2   | hall2 | winby2\n");
                message.append("Player1  | 4     | 204.5  | Player3 | 5     |\n");
                message.append("Player2  | 4     | draw   | Player4 | 5     | draw\n");
                message.append("WALKOVER |       |        | Player5 | 5     |\n");
                message.append("</pre>\n\n");
                message.append("<b>Rules:</b>\n");
                message.append("• <code>name1</code> / <code>name2</code> \n    - Player Names\n    - Walkover opponents: fill with \"WALKOVER\"\n    - Can only have 1 \"WALKOVER\" opponent per row.\n\n");
                message.append("• <code>hall1</code> / <code>hall2</code> \n    - Hall Names\n    - Remove \"Hall\" for hall names (e.g., \"Hall 4\" becomes \"4\")\n    - Hall Walkovers: hall can be empty\n    - Player Walkovers: hall can be filled, if needed.\n\n");
                message.append("• <code>winby1</code> / <code>winby2</code> \n    - How much a player won by (else 1/0 for win/loss)\n    - Only 1 column (winner) needs to be filled\n    - Draws: both columns must be <code>draw</code>\n");
                break;
                
            case "playerexport":
                message.append("<b><u>playerExport_{timestamp}.csv</u></b>\n\n");
                message.append("Export/Import player data with latest ELO ratings and stats.\n");
                message.append("<i>Generated by /exportplayers command</i>\n\n");
                message.append("<b>Required Columns:</b>\n");
                message.append("<pre>\n");
                message.append("name, trueElo, perfElo, rdTrueElo, volTrueElo,\n");
                message.append("rdPerfElo, volPerfElo, lastRound, lastHall, capped\n");
                message.append("</pre>\n\n");
                message.append("<b>Example:</b>\n");
                message.append("<pre>\n");
                message.append("name     | trueElo | perfElo | rdTrueElo | volTrueElo\n");
                message.append("Player1  | 1500    | 1450    | 350.0000  | 0.060000\n");
                message.append("Player2  | 1600    | 1580    | 320.5000  | 0.055000\n");
                message.append("\n");
                message.append("rdPerfElo | volPerfElo | lastRound | lastHall | capped\n");
                message.append("320.0000  | 0.055000   | 5         | 4        | false\n");
                message.append("310.0000  | 0.050000   | t8        | HallA    | true\n");
                message.append("</pre>\n\n");
                message.append("<b>Rules:</b>\n");
                message.append("• <code>name</code>\n    - Player's full name (required)\n\n");
                message.append("• <code>trueElo</code> / <code>perfElo</code>\n    - ELO ratings (integers, required)\n    - perfElo may be empty if not available\n\n");
                message.append("• <code>rdTrueElo</code> / <code>volTrueElo</code>\n    - Rating deviation/volatility for trueElo (decimals)\n    - May be empty if not available\n\n");
                message.append("• <code>rdPerfElo</code> / <code>volPerfElo</code>\n    - Rating deviation/volatility for perfElo (decimals)\n    - May be empty if not available\n\n");
                message.append("• <code>lastRound</code>\n    - Last round where player actually played\n    - Values: {1, 2, 3, 4, 5, 6, t16, t8, t4, t2} or 'base' (identified but not played)\n\n");
                message.append("• <code>lastHall</code>\n    - Hall name from database\n\n");
                message.append("• <code>capped</code>\n    - 'true' or 'false' for capped status\n");
                break;
                
            case "cappedplayers":
                message.append("<b><u>cappedlist.csv</u></b>\n\n");
                message.append("Upload a CSV or Excel file listing capped players.\n\n");
                message.append("<b>Required Columns:</b>\n");
                message.append("<pre>\n");
                message.append("name, hall\n");
                message.append("</pre>\n\n");
                message.append("<b>Example:</b>\n");
                message.append("<pre>\n");
                message.append("name    | hall\n");
                message.append("Player1 | 4\n");
                message.append("Player2 | 5\n");
                message.append("</pre>\n\n");
                message.append("<b>Rules:</b>\n");
                message.append("• <code>name</code> \n    - Player's full name \n    - If uploaded after round_{n}.csv is uploaded, it must match database name. Check using /infoplayer command.\n");
                message.append("• <code>hall</code> \n    - Hall name\n");
                message.append("• All players listed will be marked as capped in the database for reference/ hall scoring.\n");
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
                message.append("<b>/about</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Displays metadata and system information about the IHRG Stats bot.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/about</code>\n\n");
                message.append("<b>Output:</b>\n");
                message.append("• Bot author and version number\n");
                message.append("• Configured server timezone\n");
                message.append("• GitHub repository link\n");
                message.append("• Bot launch timestamp (when bot started)\n");
                message.append("• Last updated date (from build)\n");
                message.append("• Current server time\n");
                message.append("• Administrator contact information\n");
                break;
                
            case "help":
                message.append("<b>/help</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("seriously, if you don't know what this command does, how did you even get here?\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/help</code>\n\n");
                message.append("<b>Options:</b>\n");
                message.append("• Commands - List of all available commands with detailed descriptions\n");
                message.append("• File Uploads - Instructions for uploading round CSVs, player exports, and capped players\n");
                break;
                
            case "rankplayers":
                message.append("<b>/rankplayers</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Displays a ranked table of all players sorted by TrueElo rating (highest first).\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/rankplayers</code>\n\n");
                message.append("<b>Steps:</b>\n");
                message.append("1. Select a round (filters data to include only rounds up to selected point)\n");
                message.append("2. View ranked player table generated as an image\n\n");
                message.append("<b>Output Table Columns:</b>\n");
                message.append("• <b>Rank</b> - Player's position in rankings (1 = highest Elo)\n");
                message.append("• <b>Elo</b> - Player's TrueElo rating at the selected round\n");
                message.append("• <b>Hall</b> - Player's affiliated hall (shortened, e.g., \"H4\" for \"Hall 4\")\n");
                message.append("• <b>LR</b> - Last Round where the player actually competed\n");
                message.append("• <b>Cap</b> - Asterisk (*) indicates player is capped\n");
                message.append("• <b>Name</b> - Player's name (truncated to 20 characters if longer)\n\n");
                message.append("<b>Interpretation:</b>\n");
                message.append("• Players are sorted by TrueElo rating in descending order\n");
                message.append("• Home hall members (if configured) are highlighted with green (image) or asterisks at the end of rows (text)\n");
                message.append("• Round selection filters to show only data up to that point in the tournament\n");
                break;
                
            case "compareplayers":
                message.append("<b>/compareplayers</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Compare two players side-by-side with detailed statistics, ELO progression, and match history.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/compareplayers</code>\n\n");
                message.append("<b>Steps:</b>\n");
                message.append("1. Select first player's hall\n");
                message.append("2. Select first player from that hall\n");
                message.append("3. Select second player's hall (Can bee same hall as first player)\n");
                message.append("4. Select second player from that hall (Cannot be same player from hall)\n");
                message.append("5. Select a round or \"All Rounds\"\n");
                message.append("6. View split-screen comparison image with both players\n\n");
                message.append("<b>Output Sections:</b>\n");
                message.append("<b>1. Stats Per Round:</b>\n");
                message.append("• <b>Rnd</b> - Round name (shortened)\n");
                message.append("• <b>Rank</b> - Player's ranking position at that round\n");
                message.append("• <b>ΔRank</b> - Change in rank from previous round (+/- shows improvement/decline)\n");
                message.append("• <b>ELO</b> - TrueElo rating at that round\n");
                message.append("• <b>ΔELO</b> - Change in Elo from previous round (+/- shows gain/loss)\n\n");
                message.append("<b>2. Seating Arrangement:</b>\n");
                message.append("• Shows seat number for each round (\" - \" if player didn't play)\n\n");
                message.append("<b>3. Victory Record:</b>\n");
                message.append("• <b>Format:</b> Emoji | Hall | Player | Elo | Score | Opponent | Opp Elo | Opp Hall | Emoji\n");
                message.append("• <b>Emoji:</b> Win (✅), Draw (🟰), Loss (❌)\n");
                message.append("• <b>Score:</b> Actual game score (e.g., \"204.5-164.0\" or \"1-0\" for wins)\n");
                message.append("• <b>WALKOVER:</b> Shows as opponent with \" - \" for Elo\n");
                message.append("• <b>-NA-:</b> Player existed but didn't play that round\n");
                break;
                
            case "infoplayer":
                message.append("<b>/infoplayer</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("View comprehensive information for a single player with complete statistics and match history.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/infoplayer</code>\n\n");
                message.append("<b>Steps:</b>\n");
                message.append("1. Select a player's hall\n");
                message.append("2. Select the player from that hall\n");
                message.append("3. Select a round or \"All Rounds\"\n");
                message.append("4. View player information image\n\n");
                message.append("<b>Output Sections:</b>\n");
                message.append("<b>1. Stats Per Round:</b>\n");
                message.append("• Shows Rank, ΔRank, ELO, ΔELO for each round\n");
                message.append("• Δ (delta) indicates change from previous round\n");
                message.append("• Positive ΔRank = rank improvement (lower number is better)\n");
                message.append("• Positive ΔELO = rating increase\n\n");
                message.append("<b>2. Seating Arrangement:</b>\n");
                message.append("• Displays seat number for each round played\n\n");
                message.append("<b>3. Victory Record:</b>\n");
                message.append("• Complete match history with opponent details\n");
                message.append("• Shows: Round, Outcome emoji, Hall, Player Elo, Score, Opponent, Opponent Elo, Opponent Hall\n");
                message.append("• Emoji indicators: ✅ Win, 🟰 Draw, ❌ Loss\n");
                message.append("• Includes capped status and hall affiliation\n");
                break;
                
            case "rankhalls":
                message.append("<b>/rankhalls</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Displays a ranked table of all halls sorted by average TrueElo rating of their players.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/rankhalls</code>\n\n");
                message.append("<b>Steps:</b>\n");
                message.append("1. Select a round or \"All Rounds\" (filters data to rounds up to that point)\n");
                message.append("2. View ranked hall table generated as an image\n\n");
                message.append("<b>Output Table Columns:</b>\n");
                message.append("• <b>Rank</b> - Hall's position in rankings (1 = highest average Elo)\n");
                message.append("• <b>Hall</b> - Hall name\n");
                message.append("• <b>Cap</b> - Number of capped players in the hall\n");
                message.append("• <b>Avg Elo</b> - Average TrueElo rating of top 5 players (Max. 2 Capped players, if possible) in the hall (1 decimal place)\n\n");
                message.append("<b>Interpretation:</b>\n");
                message.append("• Halls ranked by average Elo (highest first)\n");
                message.append("• Only includes players who have actually played (not just registered)\n");
                message.append("• Average calculated from all active players up to selected round\n");
                message.append("• Home hall (if configured) highlighted with asterisks\n");
                break;
                
            case "comparehalls":
                message.append("<b>/comparehalls</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Compare two halls side-by-side with detailed statistics, Elo progression, and head-to-head performance.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/comparehalls</code>\n\n");
                message.append("<b>Steps:</b>\n");
                message.append("1. Select first hall from the list\n");
                message.append("2. Select second hall from the list\n");
                message.append("3. Select a round or \"All Rounds\"\n");
                message.append("4. View split-screen comparison image\n\n");
                message.append("<b>Output Sections:</b>\n");
                message.append("<b>1. Hall Elo Per Round:</b>\n");
                message.append("• Shows Rank, ΔRank, Elo, ΔElo for each round\n");
                message.append("• Average Elo calculated from all active players\n");
                message.append("• Delta values show progression over rounds\n\n");
                message.append("<b>2. Player Stats:</b>\n");
                message.append("• Lists all players in the hall in order of trueElo rating\n");
                message.append("• Shows Hall Rank, Global Rank, trueElo, Capped status and name\n");
                message.append("<b>3. Seating Arrangement:</b>\n");
                message.append("• Shows seating arrangement of all players, in order of their average seating arrangement\n");
                message.append("• Shows avereage seating arrangement, name, and seat per round\n");
                message.append("<b>4. Victory Record:</b>\n");
                message.append("• Complete match history against other halls\n");
                message.append("• Shows: Round, Hall Elo, Score, Opponent Hall, Opponent Elo\n");
                message.append("• Outcome emoji: ✅ Win, 🟰 Draw, ❌ Loss\n");
                message.append("• Score format shows board wins (e.g., \"3-2\" = 3 boards won to 2 lost)\n");
                message.append("<b>5. Win Probability</b>\n");
                message.append("• Shows probability of win against opponent hall\n");
                message.append("• Shows: Win %\n");
                message.append("• Calculated based on all permutations of valid player matchups in that round (max. 2 capped players, if possible).\n");
                break;
                
            case "infohall":
                message.append("<b>/infohall</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("View comprehensive information for a single hall including player roster, statistics, and match history.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/infohall</code>\n\n");
                message.append("<b>Steps:</b>\n");
                message.append("1. Select a hall from the list\n");
                message.append("2. Select a round or \"All Rounds\"\n");
                message.append("3. View hall information image\n\n");
                message.append("<b>Output Sections:</b>\n");
                message.append("<b>1. Hall Elo Per Round:</b>\n");
                message.append("• Shows Rank, ΔRank, Elo, ΔElo for each round\n");
                message.append("• Average Elo calculated from all active players\n");
                message.append("• Delta values show progression over rounds\n\n");
                message.append("<b>2. Player Stats:</b>\n");
                message.append("• Lists all players in the hall in order of trueElo rating\n");
                message.append("• Shows Hall Rank, Global Rank, trueElo, Capped status and name\n");
                message.append("<b>3. Seating Arrangement:</b>\n");
                message.append("• Shows seating arrangement of all players, in order of their average seating arrangement\n");
                message.append("• Shows avereage seating arrangement, name, and seat per round\n");
                message.append("<b>4. Victory Record:</b>\n");
                message.append("• Complete match history against other halls\n");
                message.append("• Shows: Round, Hall Elo, Score, Opponent Hall, Opponent Elo\n");
                message.append("• Outcome emoji: ✅ Win, 🟰 Draw, ❌ Loss\n");
                message.append("• Score format shows board wins (e.g., \"3-2\" = 3 boards won to 2 lost)\n");
                break;
                
            case "infomatch":
                message.append("<b>/infomatch</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("View detailed match information between two halls for a specific round, including individual board results.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/infomatch</code>\n\n");
                message.append("<b>Steps:</b>\n");
                message.append("1. Select a round from the list\n");
                message.append("2. View match details image with two tables\n\n");
                message.append("<b>Output Tables:</b>\n");
                message.append("<b>Table 1 - Match Summary:</b>\n");
                message.append("• Hall names and their Elo ratings\n");
                message.append("• Overall match outcome (win/draw/loss)\n");
                message.append("• Total board score (e.g., \"5-3\" = 5 boards won to 3)\n\n");
                message.append("<b>Table 2 - Cumulative Scores:</b>\n");
                message.append("• Running total of match wins (W column)\n");
                message.append("• Running total of board wins (B column)\n");
                message.append("• Accumulated up to the selected round\n");
                message.append("• Shows tournament progression\n\n");
                message.append("<b>Interpretation:</b>\n");
                message.append("• Match wins = number of rounds won\n");
                message.append("• Board wins = total individual game victories across all rounds\n");
                break;
                
            case "infomatchhall":
                message.append("<b>/infomatchhall</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("View detailed match information for a specific hall in a specific round, including player stats, seating, and individual match results.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/infomatchhall</code>\n\n");
                message.append("<b>Steps:</b>\n");
                message.append("1. Select a hall from the list\n");
                message.append("2. Select a round from available rounds\n");
                message.append("3. View detailed statistics with three tables\n\n");
                message.append("<b>Output Tables:</b>\n");
                message.append("<b>Table 1 - Player ELO Stats:</b>\n");
                message.append("• Player names from the selected hall\n");
                message.append("• Current rank and ELO rating for the round\n");
                message.append("• ΔRank: Change in rank from previous round (+ = improvement, - = decline, = = no change, - = first round)\n");
                message.append("• ΔELO: Change in ELO from previous round\n\n");
                message.append("<b>Table 2 - Seating:</b>\n");
                message.append("• Seat number assignment\n");
                message.append("• Player name for each seat\n");
                message.append("• Shows the seating order for the round\n\n");
                message.append("<b>Table 3 - Match Details:</b>\n");
                message.append("• Individual match results per seat\n");
                message.append("• Format: Seat | Emoji | Hall | ELO | Player | Score | Opponent | ELO | Hall | Emoji\n");
                message.append("• Outcome emoji: ✅ Win, 🟰 Draw, ❌ Loss\n");
                message.append("• Score format shows board wins\n");
                message.append("• WALKOVER opponents shown with \"-\" ELO\n\n");
                message.append("<b>Note:</b>\n");
                message.append("• For round 1, ΔRank will show \"-\" (no previous rank)\n");
                message.append("• prevElo for round 1 uses baseElo from database (typically 1000)\n");
                break;
                
            case "settings":
                message.append("<b>(ADMIN) /settings</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("View and modify bot configuration settings. Admin-only command.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/settings</code>\n\n");
                message.append("<b>Access:</b>\n");
                message.append("• Only users with admin privileges can use this command\n");
                message.append("• Non-admin users will receive an access denied message\n\n");
                message.append("<b>Configurable Settings:</b>\n");
                message.append("• <b>Home Hall</b> - Set which hall is highlighted in rankings\n");
                message.append("• <b>Timezone</b> - Configure server timezone for timestamps\n");
                message.append("• <b>Max Seeds</b> - Set maximum seeds value for score calculations\n");
                message.append("• Other application.properties parameters\n\n");
                message.append("<b>Note:</b>\n");
                message.append("• Settings are saved to application.properties\n");
                message.append("• Changes take effect immediately\n");
                break;
                
            case "exportplayers":
                message.append("<b>/exportplayers</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Export latest player data from the database to a CSV file for backup or importing into a new database.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/exportplayers</code>\n\n");
                message.append("<b>Process:</b>\n");
                message.append("• Command generates a CSV file automatically\n");
                message.append("• File sent as attachment for download\n");
                message.append("• Filename format: playerExport_YYYYMMDD_HHMMSS.csv\n\n");
                message.append("<b>Exported Data Columns:</b>\n");
                message.append("• <b>name</b> - Player's full name\n");
                message.append("• <b>trueElo</b> / <b>perfElo</b> - ELO ratings (integers)\n");
                message.append("• <b>rdTrueElo</b> / <b>volTrueElo</b> - TrueElo rating deviation and volatility\n");
                message.append("• <b>rdPerfElo</b> / <b>volPerfElo</b> - PerfElo rating deviation and volatility\n");
                message.append("• <b>lastRound</b> - Last round where player competed\n");
                message.append("• <b>lastHall</b> - Player's current hall\n");
                message.append("• <b>capped</b> - Capped status (true/false)\n\n");
                message.append("<b>Use Cases:</b>\n");
                message.append("• Backup player data before major changes\n");
                message.append("• Import into new database to set initial values\n");
                message.append("• External data analysis\n");
                break;
                
            case "exportdatabase":
                message.append("<b>(ADMIN) /exportdatabase</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Export the complete SQLite database file for backup, debugging, or migration. Admin-only command.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/exportdatabase</code>\n\n");
                message.append("<b>Access:</b>\n");
                message.append("• Only users with admin privileges can use this command\n");
                message.append("• Non-admin users will receive an access denied message\n\n");
                message.append("<b>Process:</b>\n");
                message.append("1. Command prompts for confirmation (prevents accidental exports)\n");
                message.append("2. User confirms with \"✅ Confirm\" button or cancels with \"❌ Cancel\"\n");
                message.append("3. Upon confirmation, database file is sent to admin's DM\n");
                message.append("4. File sent as attachment (default.db)\n\n");
                message.append("<b>Exported Content:</b>\n");
                message.append("• Complete SQLite database file\n");
                message.append("• All player statistics and Elo ratings\n");
                message.append("• All round data and match results\n");
                message.append("• All hall information\n");
                message.append("• Database schema and indices\n\n");
                message.append("<b>Use Cases:</b>\n");
                message.append("• Full system backup before major changes\n");
                message.append("• Database debugging and inspection\n");
                message.append("• Migration to new server\n");
                message.append("• Disaster recovery\n");
                break;
                
            default:
                return new CommandResponse("❌ Unknown command.", (java.nio.file.Path) null);
        }
        
        logHelper.logSuccess(String.format("Generated detailed help for command: %s", command));
        return new CommandResponse(message.toString(), (java.nio.file.Path) null);
    }
}
