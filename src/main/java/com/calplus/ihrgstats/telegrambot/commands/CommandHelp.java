package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.LogHelper;
import com.calplus.ihrgstats.utils.TelegramCommandUtils;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command handler for /help command.
 * Provides interactive help for commands and file uploads with button navigation.
 */
public class CommandHelp {
    private final LogHelper logHelper;
    
    // State management for multi-step selection
    private static final Map<String, HelpSelectionState> userSelectionStates = new ConcurrentHashMap<>();
    
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
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested /help", userInfo));
        
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
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s selected help category: %s", userInfo, category));
        
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
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested file upload help for: %s", userInfo, fileType));
        
        userSelectionStates.remove(userId);
        
        return generateFileTypeHelp(fileType);
    }
    
    /**
     * Handles back button - returns to main help menu
     */
    public CommandResponse handleBack(String userId) {
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested back to help menu", userInfo));
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
        message.append("• /infomatchhall - Get detailed match information for a hall in a specific round\n\n");

        message.append("<b>Utility Commands:</b>\n");
        message.append("• /about - About this bot\n");
        message.append("• /help - i think you need help\n\n");

        message.append("<b>Administrator Commands:</b>\n");
        message.append("• /settings - (ADMIN) Change bot's settings\n");
        message.append("• /exportdatabase - (ADMIN) Exports the full database as .xlsx or the raw .db file\n");
        message.append("• /matchtypes - (ADMIN) Manage match types (name, max score, time limit, description)\n");
        message.append("• /recalculate - (ADMIN) Recalculates all ratings across all years (whole-history WHR-style refit)\n");
        message.append("• /predict - (ADMIN) Forecasts a hypothetical matchup: AI model vs Glicko baseline, side by side\n");
        message.append("• /modelstats - (ADMIN) AI model leaderboard and live prediction-accuracy scorecard\n");
        message.append("• /lineup - (ADMIN) Recommends your seating order against a specific opponent hall, with a strategy-archetype comparison\n\n");

        message.append("Click on any command below for detailed information:\n");

        String[] labels = {
            "/rankplayers", "/rankhalls",
            "/compareplayers", "/comparehalls",
            "/infoplayer", "/infohall",
            "/infomatch", "/infomatchhall",
            "/settings", "/exportdatabase",
            "/matchtypes", "/recalculate",
            "/predict", "/modelstats",
            "/lineup",
            "/about", "/help",
            "🔙 Back", "❌ Cancel"
        };
        String[] callbacks = {
            "help_cmd_rankplayers", "help_cmd_rankhalls",
            "help_cmd_compareplayers", "help_cmd_comparehalls",
            "help_cmd_infoplayer", "help_cmd_infohall",
            "help_cmd_infomatch", "help_cmd_infomatchhall",
            "help_cmd_settings", "help_cmd_exportdatabase",
            "help_cmd_matchtypes", "help_cmd_recalculate",
            "help_cmd_predict", "help_cmd_modelstats",
            "help_cmd_lineup",
            "help_cmd_about", "help_cmd_help",
            "help_back", "help_cancel"
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
            "cappedlist.csv",
            "🔙 Back", "❌ Cancel"
        };
        String[] callbacks = {
            "help_filetype_roundcsv",
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
                message.append("<b><u>{year}_round_{n}.csv</u></b>\n\n");
                message.append("Upload a CSV file with match results for round n of the current tournament year.\n");
                message.append("<i>n = a plain sequential round number (1, 2, 3, ...) - no more T16/T8/T4/T2 tokens</i>\n");
                message.append("<i>Filename can also be round_{n}.csv (without a year prefix), which falls back to the admin-configured settings.currentYear</i>\n\n");
                message.append("<b>Required Columns:</b>\n");
                message.append("<pre>\n");
                message.append("name1, hall1, score1, name2, hall2, score2\n");
                message.append("</pre>\n\n");
                message.append("<b>Example:</b>\n");
                message.append("<pre>\n");
                message.append("name1    | hall1 | score1 | name2   | hall2 | score2\n");
                message.append("Player1  | 4     | 204.5  | Player3 | 5     | 164.0\n");
                message.append("Player2  | 4     | 100    | Player4 | 5     | 100\n");
                message.append("WALKOVER |       |        | Player5 | 5     |\n");
                message.append("</pre>\n\n");
                message.append("<b>Rules:</b>\n");
                message.append("• <code>name1</code> / <code>name2</code> \n    - Player Names\n    - Walkover opponents: fill with \"WALKOVER\"\n    - Can only have 1 \"WALKOVER\" opponent per row.\n\n");
                message.append("• <code>hall1</code> / <code>hall2</code> \n    - Hall Names\n    - Remove \"Hall\" for hall names (e.g., \"Hall 4\" becomes \"4\")\n    - Hall Walkovers: hall can be empty (falls back to an \"unknown\" hall)\n    - Player Walkovers: hall can be filled, if needed.\n\n");
                message.append("• <code>score1</code> / <code>score2</code> \n    - Each side's own raw board score (no win-margin formula)\n    - Standard games: BOTH scores are required\n    - WALKOVER rows: leave BOTH score fields blank - the app auto-computes a default score based on the round's configured match type\n    - TIMEOUT rows (clock ran out, not a WALKOVER - both players are real): put \"TIMEOUT\" in the losing side's score cell; the winner's cell keeps a real score if known, or \"0\" if not. Still a real, rated result.\n");
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
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested detailed help for: %s", userInfo, command));
        
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
                message.append("• For round 1 (or if no rating exists for the previous round), ΔRank/ΔELO show \"-\"\n");
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
                message.append("• <b>Current Year</b> - Set the tournament year currently being played (used to resolve which year's rounds/players to operate on)\n");
                message.append("• Other application.properties parameters\n\n");
                message.append("<b>Note:</b>\n");
                message.append("• Settings are saved to application.properties\n");
                message.append("• Changes take effect immediately\n");
                break;
                
            case "exportdatabase":
                message.append("<b>(ADMIN) /exportdatabase</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Export the entire database, in your choice of format. Admin-only command.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/exportdatabase</code>\n\n");
                message.append("<b>Access:</b>\n");
                message.append("• Only users with admin privileges can use this command\n");
                message.append("• Non-admin users will receive an access denied message\n\n");
                message.append("<b>Process:</b>\n");
                message.append("1. Command prompts for a format choice\n");
                message.append("2. Choose <b>Full export (.xlsx)</b> or <b>Database file (.db)</b>, or cancel\n");
                message.append("3. The exported file is sent to your Direct Message\n\n");
                message.append("<b>Full export (.xlsx):</b>\n");
                message.append("• One sheet per populated table - a complete, mechanical dump of the current database\n");
                message.append("• Best for browsing/analysis in a spreadsheet\n\n");
                message.append("<b>Database file (.db):</b>\n");
                message.append("• The raw SQLite file, unchanged\n");
                message.append("• <b>Recommended disaster-recovery path:</b> restore this file into <code>database/core/</code> to fully restore the database\n\n");
                message.append("<b>Use Cases:</b>\n");
                message.append("• Full system backup before major changes\n");
                message.append("• Database debugging and inspection\n");
                message.append("• Migration to new server\n");
                message.append("• Disaster recovery\n");
                break;

            case "matchtypes":
                message.append("<b>(ADMIN) /matchtypes</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Manage match types (name, max score, time limit, description). Admin-only command.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/matchtypes</code>\n\n");
                message.append("<b>Access:</b>\n");
                message.append("• Only users with admin privileges can use this command\n\n");
                message.append("<b>Why it matters:</b>\n");
                message.append("• Round processing blocks and prompts for a match type whenever a walkover is present and none is assigned yet - the match type's max score is what determines the default walkover score\n\n");
                message.append("<b>Actions:</b>\n");
                message.append("• <b>Create New</b> - Add a match type (name, max score, time limit, description)\n");
                message.append("• <b>List All</b> - View all existing match types\n");
                message.append("• <b>Edit Existing</b> - Update a match type's details\n");
                message.append("• <b>Assign to Round</b> - Retroactively assign a match type to a round whose matches ended up with none set (e.g. after a reprocess with no walkover in the new data)\n");
                break;

            case "recalculate":
                message.append("<b>(ADMIN) /recalculate</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Runs a whole-history (WHR-style) recalculation of every player's TrueElo rating. Admin-only command.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/recalculate</code>\n\n");
                message.append("<b>Access:</b>\n");
                message.append("• Only users with admin privileges can use this command\n\n");
                message.append("<b>What it does:</b>\n");
                message.append("• Replays every stored round across every year, in chronological order, through 5 refinement passes\n");
                message.append("• Later results can revise earlier rounds' rating estimates (unlike a normal round upload, which only ever computes forward)\n");
                message.append("• Rewrites the current ratings used by rankings/comparisons/info commands\n\n");
                message.append("<b>What it does NOT touch:</b>\n");
                message.append("• Point-in-time snapshots (\"rankings as of round N\") stay exactly as originally published\n");
                message.append("• Match data is only read, never modified\n\n");
                message.append("<b>When to run it:</b>\n");
                message.append("• This runs automatically after every round upload - manual use is for refreshing stored ratings without re-uploading data (e.g. after a rating-engine update)\n");
                break;

            case "predict":
                message.append("<b>(ADMIN) /predict</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Forecasts a hypothetical matchup between any two players using the current AI model, shown side by side with the plain Glicko baseline. Admin-only command.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/predict</code>\n\n");
                message.append("<b>Access:</b>\n");
                message.append("• Only users with admin privileges can use this command\n\n");
                message.append("<b>Flow:</b>\n");
                message.append("• Pick the first player's hall, then the first player, then the second player's hall, then the second player\n");
                message.append("• Returns win/draw/loss probabilities from the AI model AND the Glicko baseline, the top factors driving the model's number, and a reliability note (rating deviation, career boards) for each player\n\n");
                message.append("<b>Honest limits:</b>\n");
                message.append("• Each player's features come from their own most recently played round, not a perfectly live snapshot\n");
                message.append("• If no AI model has been trained yet, only the Glicko baseline is shown\n");
                break;

            case "modelstats":
                message.append("<b>(ADMIN) /modelstats</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("The AI model trust dashboard: current champion, a leaderboard comparing every trained model family against the Glicko baseline, and a live scorecard of how the champion's real pre-round predictions have actually done. Admin-only command.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/modelstats</code>\n\n");
                message.append("<b>Access:</b>\n");
                message.append("• Only users with admin privileges can use this command\n\n");
                message.append("<b>What it shows:</b>\n");
                message.append("• The current champion model and how it compares to the Glicko baseline in walk-forward backtesting\n");
                message.append("• A leaderboard of every model family that has ever been trained\n");
                message.append("• A live scorecard: predicted-outcome hit rate and mean probability assigned to what actually happened, across every logged pre-round prediction\n\n");
                message.append("<b>Why it matters:</b>\n");
                message.append("• Models and the baseline are always shown side by side, and a model only becomes champion if it measurably beat the baseline - this command is how you check whether that's actually still true\n");
                break;

            case "lineup":
                message.append("<b>(ADMIN) /lineup</b>\n\n");
                message.append("<b>Description:</b>\n");
                message.append("Recommends your seating order against a specific opponent hall: an opponent-captain model plus an exact optimizer over every legal lineup and seat order. Admin-only command.\n\n");
                message.append("<b>Usage:</b>\n");
                message.append("<code>/lineup</code>\n\n");
                message.append("<b>Access:</b>\n");
                message.append("• Only users with admin privileges can use this command\n");
                message.append("• Requires <code>settings.homeHall</code> and <code>settings.currentYear</code> to be set (see /settings), and a trained AI model\n\n");
                message.append("<b>What it shows:</b>\n");
                message.append("• The opponent captain's expected roster and seating consistency (fixed vs random, reactivity to rematches)\n");
                message.append("• The best-response lineup (highest expected win probability) and the maximin \"safe\" lineup, with each one's worst-case win probability\n");
                message.append("• A strategy-archetype table - strength order, mirror, single sacrifice, double sacrifice, and the optimizer's free optimum - so you can see whether a Tian-Ji-style sacrifice actually helps here, with real numbers\n");
                message.append("• A per-board pairing table with model and Glicko win% side by side, and a reliability flag for each of your players\n");
                message.append("• A plain-language, fully deterministic explanation of the recommendation (no LLM involved)\n\n");
                message.append("<b>v1 scope:</b>\n");
                message.append("• Uses your home hall's full active roster automatically - manual availability/exclude and opponent-roster adjustment are planned refinements, not yet built\n");
                break;

            default:
                return new CommandResponse("❌ Unknown command.", (java.nio.file.Path) null);
        }
        
        logHelper.logSuccess(String.format("Generated detailed help for command: %s", command));
        return new CommandResponse(message.toString(), (java.nio.file.Path) null);
    }
}
