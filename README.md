# IHRG Statistics Bot

![IHRG Stats Icon](Github%20Images/Icon_IHRGStats.png)
**Version:** 1.0.0 (January 2, 2026)

A powerful and intelligent statistics tracking bot designed specifically for NTU's Inter-Hall Recreational Games (IHRG). Transform your hanafuda tournament data into stunning visualizations, detailed player analytics, and comprehensive rankings with just a few commands. Whether you're managing a competitive league or tracking casual play, IHRG Statistics Bot delivers professional-grade statistics with minimal effort.

## Table of Contents

- [Why Choose IHRG Statistics Bot?](#why-choose-ihrg-statistics-bot)
- [Key Features](#key-features)
- [Installation & Server Deployment](#installation--server-deployment)
- [Configuration](#configuration)
- [Commands Reference](#commands-reference)
- [File Upload System](#file-upload-system)
- [Error Handling & Reliability](#error-handling--reliability)
- [Visual Examples](#visual-examples)
- [Project Structure](#project-structure)
- [Support](#support)

## Why Choose IHRG Statistics Bot?

IHRG Statistics Bot is the complete solution for tournament organizers and competitive players who demand accuracy, automation, and beautiful presentation. Built from the ground up for hanafuda tournaments, this bot eliminates manual spreadsheet work and delivers instant insights through an intuitive command interface.

### What Makes It Special

**Dual Platform Support**: Seamlessly operates on both Telegram and Discord simultaneously, ensuring your community can access statistics on their platform of choice.

**Advanced ELO System**: Implements dual ELO rating calculations (True Elo and Performance Elo) with Glicko-2 volatility for precise skill assessment. Track player progression with industry-standard algorithms used by professional gaming leagues.

**Zero Data Loss**: Built on SQLite with comprehensive validation, your tournament data is stored reliably with automatic backup capabilities. Every upload is verified, every mismatch is caught, and every change is logged.

**Professional Visualizations**: Generate publication-quality images for rankings, player profiles, and head-to-head comparisons. Each image is timestamped, customizable, and ready to share with your community.

**Smart Automation**: Intelligent file processing detects errors before they corrupt your database. Interactive confirmation dialogs guide you through data conflicts, and comprehensive error messages explain exactly what went wrong and how to fix it.

**Admin-Friendly**: Granular permission controls, interactive settings management, and detailed activity logging ensure secure administration without technical complexity.

## Key Features

### Player Analytics

**Individual Player Profiles**
Get comprehensive statistics for any player with the `/infoplayer` command. View their current ELO ratings, match history, victory records, hall affiliation, and performance trends over time.

![Info Player Example](Github%20Images/SAMPLE_InfoPlayer.png)

**Player Rankings**
Display dynamic leaderboards sorted by True ELO or Performance ELO. Filter by hall affiliation or view top performers across all halls. Rankings update automatically with each processed round.

![Rank Players Example](Github%20Images/SAMPLE_RankPlayers.png)

**Player Comparisons**
Compare two players side-by-side with visual split-screen layouts. See win rates, ELO differences, head-to-head records, and statistical advantages at a glance.

![Compare Players Example](Github%20Images/SAMPLE_ComparePlayers.png)

### Hall Management

**Hall Rankings**
Track team performance with comprehensive hall-level statistics. See which halls dominate the competition based on aggregate player performance and match results.

![Rank Halls Example](Github%20Images/SAMPLE_RankHalls.png)

**Hall Information**
View detailed statistics for individual halls including member rosters, win/loss records, average ELO ratings, and recent performance trends.

![Info Hall Example](Github%20Images/SAMPLE_InfoHall.png)

**Hall Comparisons**
Compare two halls side-by-side to analyze team strength differences, member statistics, and competitive advantages.

![Compare Halls Example](Github%20Images/SAMPLE_CompareHalls.png)

### Match & Tournament Data

**Match Information**
Retrieve detailed breakdowns of any match by round number. See all participating players, scores, ELO changes, and match outcomes including proper WALKOVER handling.

![Info Match Example](Github%20Images/SAMPLE_InfoMatch.png)

**Round Processing**
Upload round result files and watch as the bot automatically calculates ELO changes, updates player statistics, tracks victories, and generates comprehensive match summaries.

**Tournament Export**
Export complete player databases or full tournament data to CSV format for external analysis, archival purposes, or migration to other systems.

### Administrative Controls

**Interactive Settings**
Access a full-featured settings panel with button-based controls:
- Toggle Performance ELO calculations on/off
- Enable/disable non-admin file uploads
- Configure home hall for personalized queries
- Set maximum seed values for calculations
- Adjust timezone (UTC-12 to UTC+14) for accurate timestamps
- Control channel processing permissions

**Database Management**
Export complete databases with administrative approval. All exports are timestamped and logged for audit trails.

**Permission System**
Granular access controls ensure only authorized administrators can modify settings, process files, or export sensitive data.

### Intelligent Data Processing

**CSV File Validation**
Every uploaded file is thoroughly validated before processing:
- Format verification (header structure, column count, data types)
- Duplicate detection across players and matches
- Hall name consistency checks
- Active/inactive player status validation
- Missing data identification

**Interactive Conflict Resolution**
When data mismatches occur (e.g., player hall changes), the bot provides interactive resolution options:
- Keep old hall (link to existing player record)
- Update hall (same player, new affiliation)
- Create new player (treat as different individual)
- Bulk resolution for multiple conflicts
- Cancel and review data manually

**Smart WALKOVER Handling**
Automatic detection and proper scoring for walkover matches:
- Displays "WALKOVER" for absent opponents (not blank or malformed text)
- Correct score display (3-2 instead of 5-0)
- Proper ELO display ("-" for non-existent opponents)
- Accurate board wins calculation (+3 instead of +5)
- Fair match wins distribution (+1 to participating hall only)

## Installation & Server Deployment

### Prerequisites

- Java Development Kit (JDK) 24 or higher
- Apache Maven 3.6.0 or higher
- Minimum 2GB RAM (4GB recommended)
- Telegram Bot Token (from @BotFather)
- Discord Bot Token (from Discord Developer Portal)
- Server with persistent storage and internet connectivity

### Step 1: Download and Extract

```bash
# Clone the repository or download the source code
git clone https://github.com/Calplus/ihrgstats.git
cd ihrgstats
```

### Step 2: Build the Application

**Option A: Using Maven (Standard)**
```bash
# Clean previous builds and compile
mvn clean compile

# Package as executable JAR with all dependencies
mvn package

# The compiled JAR will be in: target/ihrgstats-1.0-SNAPSHOT.jar
```

**Option B: Using Maven Daemon (Faster)**
```bash
# Install Maven Daemon from: https://github.com/apache/maven-mvnd
# Then use mvnd instead of mvn for faster builds

mvnd clean compile
mvnd package
```

### Step 3: Configure Environment Variables

Create a file named `.env.properties` in the project root directory:

```properties
# Telegram Bot Configuration
TELEGRAM_BOT_TOKEN=your_telegram_bot_token_here
TELEGRAM_BOT_USERNAME=your_bot_username
TELEGRAM_CHAT_ID=your_telegram_channel_id
TELEGRAM_CHAT_ID_COMMANDS=your_commands_thread_id
TELEGRAM_CHAT_ID_STATUS=your_status_thread_id
TELEGRAM_CHAT_ID_FILEUPLOAD=your_file_upload_thread_id
TELEGRAM_ADMIN_ID=your_telegram_user_id

# Discord Bot Configuration
DISCORD_BOT_TOKEN=your_discord_bot_token_here
DISCORD_CHANNEL_ID_COMMANDS=your_discord_commands_channel_id
DISCORD_CHANNEL_ID_STATUS=your_discord_status_channel_id
DISCORD_ADMIN_ID=your_discord_user_id
```

**How to Get These Values:**

**Telegram Bot Token:**
1. Message @BotFather on Telegram
2. Send `/newbot` and follow the prompts
3. Save the bot token provided

**Telegram Chat IDs:**
1. Add your bot to your Telegram channel/group
2. Send a message in the channel
3. Visit: `https://api.telegram.org/bot<YOUR_BOT_TOKEN>/getUpdates`
4. Find the `chat` object and note the `id` field

**Telegram User ID:**
1. Message @userinfobot on Telegram
2. Your user ID will be displayed

**Discord Bot Token:**
1. Visit [Discord Developer Portal](https://discord.com/developers/applications)
2. Create a new application
3. Go to "Bot" section and create a bot
4. Copy the bot token

**Discord Channel IDs:**
1. Enable Developer Mode in Discord (User Settings → Advanced)
2. Right-click your channel and select "Copy ID"

### Step 4: Deploy to Server

**For Linux/Unix Servers:**
```bash
# Transfer the JAR file and .env.properties to your server
scp target/ihrgstats-1.0-SNAPSHOT.jar user@yourserver:/opt/ihrgstats/
scp .env.properties user@yourserver:/opt/ihrgstats/

# SSH into your server
ssh user@yourserver

# Navigate to the application directory
cd /opt/ihrgstats

# Run the bot
java -jar ihrgstats-1.0-SNAPSHOT.jar
```

**For Windows Servers:**
```powershell
# Copy files to server directory
# Open PowerShell or Command Prompt in the application directory

# Run the bot
java -jar ihrgstats-1.0-SNAPSHOT.jar
```

### Step 5: Run as Background Service

**Linux (systemd service):**

Create `/etc/systemd/system/ihrgstats.service`:
```ini
[Unit]
Description=IHRG Statistics Bot
After=network.target

[Service]
Type=simple
User=yourusername
WorkingDirectory=/opt/ihrgstats
ExecStart=/usr/bin/java -jar /opt/ihrgstats/ihrgstats-1.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable ihrgstats
sudo systemctl start ihrgstats

# Check status
sudo systemctl status ihrgstats

# View logs
sudo journalctl -u ihrgstats -f
```

**Windows (Task Scheduler):**
1. Open Task Scheduler
2. Create Basic Task
3. Trigger: "When the computer starts"
4. Action: "Start a program"
5. Program: `java`
6. Arguments: `-jar C:\path\to\ihrgstats-1.0-SNAPSHOT.jar`
7. Start in: `C:\path\to\`

### Verification

After deployment, verify the bot is working:
1. Check bot appears online in Telegram/Discord
2. Send `/help` command
3. Check logs for any error messages
4. Upload a test file to verify file processing

## Configuration

### Application Settings

Edit `src/main/resources/application.properties` before building:

```properties
# Database Configuration
database.path=database/core/default.db

# Default ELO Settings
settings.performance.elo=true
settings.max.seeds=10
settings.home.hall=
settings.timezone=+00:00

# File Upload Settings
settings.non.admin.uploads=false
settings.allow.all.channels.processing=false

# Logging Configuration
logging.level.root=INFO
logging.level.com.calplus.ihrgstats=DEBUG
```

**Key Settings Explained:**

- `settings.performance.elo`: Enable/disable Performance ELO calculation
- `settings.max.seeds`: Maximum seed value for calculations (default: 10)
- `settings.home.hall`: Default hall for personalized queries (empty = none)
- `settings.timezone`: UTC offset for timestamps (+08:00, -05:00, etc.)
- `settings.non.admin.uploads`: Allow non-admins to upload files
- `settings.allow.all.channels.processing`: Process commands from any channel

These settings can also be changed at runtime using the `/settings` command (admin only).

## Commands Reference

All commands work on both Telegram and Discord. Commands are case-insensitive.

### Player Commands

**`/rankplayers [hall] [top N]`**
Display player rankings sorted by ELO rating.

```
/rankplayers                    # Show all players
/rankplayers 10                # Show top 10 players
/rankplayers Hall4             # Show players from Hall 4
/rankplayers Hall4 5           # Show top 5 players from Hall 4
```

**Error Handling:**
- No rounds found: "No rounds with data found in database"
- No player data: "No player data found for round X"
- Invalid hall name: Shows available halls
- Invalid number format: "Invalid number format. Please use: /rankplayers [hall] [top N]"

---

**`/infoplayer <name>`**
Display detailed information for a specific player.

```
/infoplayer John Doe
/infoplayer JohnDoe
```

**Error Handling:**
- Player not found: "Player 'Name' not found. Please check the spelling."
- Missing name: "Please provide a player name. Usage: /infoplayer <name>"
- No data available: "No statistics available for this player yet"
- Database error: "Error retrieving player information. Please try again later."

---

**`/compareplayers <name1> vs <name2>`**
Compare two players side-by-side.

```
/compareplayers John Doe vs Jane Smith
/compareplayers Alice vs Bob
```

**Error Handling:**
- Invalid format: "Invalid format. Use: /compareplayers <name1> vs <name2>"
- Player not found: "Player 'Name' not found. Please check the spelling."
- Same player: "Cannot compare a player with themselves"
- Missing vs keyword: "Please use 'vs' between player names"

---

**`/exportplayers`**
Export complete player database to CSV. Admin only.

**Error Handling:**
- Access denied: "Access Denied: Only administrators can export player data"
- Database empty: "No player data available to export"
- Export failed: "Failed to create export file. Please contact administrator."

---

### Hall Commands

**`/rankhalls [top N]`**
Display hall rankings based on player performance.

```
/rankhalls          # Show all halls
/rankhalls 5        # Show top 5 halls
```

**Error Handling:**
- No rounds found: "No rounds with data found in database"
- No player data: "No player data found for round X"
- No hall rankings: "No hall rankings could be calculated for round X"
- Invalid number: "Invalid number format. Please use: /rankhalls [top N]"

---

**`/infohall <hall>`**
Display detailed information for a specific hall.

```
/infohall Hall4
/infohall Binjai
```

**Error Handling:**
- Hall not found: "Hall 'Name' not found. Please check the spelling."
- Missing hall name: "Please provide a hall name. Usage: /infohall <hall>"
- No data available: "No statistics available for this hall yet"
- Database error: "Error retrieving hall information. Please try again later."

---

**`/comparehalls <hall1> vs <hall2>`**
Compare two halls side-by-side.

```
/comparehalls Hall4 vs Binjai
/comparehalls Sinar vs Cahaya
```

**Error Handling:**
- Invalid format: "Invalid format. Use: /comparehalls <hall1> vs <hall2>"
- Hall not found: "Hall 'Name' not found. Please check the spelling."
- Same hall: "Cannot compare a hall with itself"
- Missing vs keyword: "Please use 'vs' between hall names"

---

### Match Commands

**`/infomatch <round>`**
Display detailed match information for a specific round.

```
/infomatch round_1
/infomatch round_t4
/infomatch 3
```

**Error Handling:**
- Invalid round format: "Invalid round format. Use: /infomatch <round_number> or /infomatch round_X"
- Round not found: "No data found for round X"
- No match data: "No matches recorded for round X"
- Database error: "Error retrieving match information. Please try again later."

---

### Utility Commands

**`/settings`**
Open interactive settings menu. Admin only.

**Error Handling:**
- Access denied: "Access Denied: Only administrators can use the /settings command"
- No settings found: "No configurable settings found in application.properties"
- Setting not found: "Error: Setting not found: <setting_key>"
- Update failed: "Error: Failed to update setting <setting_key>"

---

**`/exportdatabase`**
Export complete database (all tables). Admin only with confirmation.

**Error Handling:**
- Access denied: "Access Denied: Only administrators can export the database"
- Database empty: "Database is empty. Nothing to export."
- Export failed: "Failed to create database export. Please contact administrator."
- Confirmation timeout: "Export cancelled due to timeout"

---

**`/help`**
Display command help and usage instructions.

**Error Handling:**
- Always returns help text (no errors)

---

**`/about`**
Display bot information, version, and statistics.

**Error Handling:**
- Error generating info: "Error generating about information. Please try again later."

---

## File Upload System

The bot accepts three types of CSV files for data import. Upload files directly to the configured Telegram upload channel.

### File Types

#### 1. playerExport_YYYYMMDD_HHMMSS.csv
Initial player data import file containing baseline player information.

**Format:**
```csv
name,trueElo,perfElo,baseRdTrueElo,baseVolTrueElo,baseRdPerfElo,baseVolPerfElo,lastRound,hall,capped,active,dateLogged,victories
John Doe,1500.0,1500.0,350.0,0.06,350.0,0.06,round_1,Hall4,0,1,2026-01-02 12:00:00,[]
```

**Columns:**
- `name`: Player full name
- `trueElo`: True ELO rating (decimal)
- `perfElo`: Performance ELO rating (decimal)
- `baseRdTrueElo`: Rating deviation for True ELO
- `baseVolTrueElo`: Volatility for True ELO
- `baseRdPerfElo`: Rating deviation for Performance ELO
- `baseVolPerfElo`: Volatility for Performance ELO
- `lastRound`: Last played round (e.g., "round_1")
- `hall`: Player's hall affiliation
- `capped`: 0 or 1 (capped status)
- `active`: 0 or 1 (active status)
- `dateLogged`: Timestamp (YYYY-MM-DD HH:MM:SS)
- `victories`: JSON array of victory records

**Error Handling:**
- File not found: "Failed to download file from Telegram"
- Invalid format: "Invalid CSV format: Header must match expected columns"
- Duplicate players: "Duplicate player found: <name>"
- Missing columns: "Missing required columns: <column_list>"
- Invalid data types: "Invalid data type in row X, column Y: expected <type>, got <value>"
- Hall mismatch (active players): Stops processing, requires data correction
- Hall mismatch (inactive players): Interactive resolution with options

---

#### 2. cappedlist.csv
List of capped players (players who have reached maximum rating cap).

**Format:**
```csv
name,hall
John Doe,Hall4
Jane Smith,Binjai
```

**Columns:**
- `name`: Player full name
- `hall`: Player's hall affiliation

**Error Handling:**
- File not found: "cappedlist.csv file not found at: <path>"
- Invalid format: "Invalid CSV format: Header must have exactly 2 columns (name,hall)"
- Incorrect headers: "Invalid CSV format: Header must be 'name,hall' (case insensitive)"
- CSV validation failed: "CSV validation failed: <error_message>"
- Database update failed: "Database update failed: <error_message>"
- Empty file: "No capped players found in file"

**Success Confirmation:**
```
CSV validated successfully. X capped players found.
- X capped players added to database
- Y players already in database
- Z players updated in A1_PlayerStats
```

---

#### 3. round_N.csv
Round results file containing match outcomes for a specific round.

**Format:**
```csv
name,score,oppscore,oppHall,hall
John Doe,5,0,Hall1,Hall4
Jane Smith,3,2,Binjai,Hall4
```

**Columns:**
- `name`: Player full name
- `score`: Player's score (0-5)
- `oppscore`: Opponent's score (0-5)
- `oppHall`: Opponent's hall (or empty/whitespace for WALKOVER)
- `hall`: Player's hall

**Valid Round Names:**
- `round_1`, `round_2`, `round_3`, `round_4`, `round_5`, `round_6`
- `round_t16`, `round_t8`, `round_t4`, `round_t2` (tournament rounds)

**Error Handling:**
- Invalid filename: "Invalid round filename format: <filename>"
- Invalid round name: "Invalid round name. Must be: round_1 through round_6, or round_t16, round_t8, round_t4, round_t2"
- File not found: "Failed to download file from Telegram"
- Invalid format: "Invalid CSV format: Header must have exactly 5 columns (name,score,oppscore,oppHall,hall)"
- Incorrect headers: "Invalid CSV format: Header must be 'name,score,oppscore,oppHall,hall'"
- Duplicate matches: "Duplicate match found for player: <name>"
- Invalid scores: "Invalid score value: must be 0-5"
- Score mismatch: "Total scores don't add up to 5: <player_score> + <opp_score> = <total>"
- Missing player: "Player '<name>' not found in database. Upload playerExport file first."
- Hall mismatch (active players): Stops processing with error message
- Hall mismatch (inactive players): Interactive resolution dialog
- WALKOVER detection: Automatically normalizes empty/whitespace oppHall to "WALKOVER"

**Interactive Resolution (Hall Mismatches):**
When a player's hall in the CSV doesn't match the database and the player is inactive:

1. **Individual Resolution**: Choose action for each mismatch one-by-one
2. **Bulk Resolution**: Apply same action to all mismatches
3. **Options:**
   - Keep old hall (link to existing player record)
   - Update hall (same player, new affiliation)
   - Create new player (treat as different individual)
4. **Cancel**: Stop processing and review data manually

**Confirmation Dialogs:**
- User confirmation requested via Telegram with "yes/no" response
- Button selection for multi-choice options (timeout: 120 seconds)
- Timeout message: "Button selection timeout - processing cancelled"

**Success Confirmation:**
```
Round X processed successfully!
- Y matches processed
- Z ELO ratings updated
- W victory records added
```

---

### Upload Workflow Best Practices

1. **Start with playerExport.csv** - Establishes baseline player data
2. **Upload cappedlist.csv** - Updates capping status for players
3. **Upload round files in order** - Process round_1, round_2, etc. sequentially
4. **Monitor confirmation messages** - Check for warnings or validation errors
5. **Resolve conflicts promptly** - Handle hall mismatch dialogs immediately
6. **Backup regularly** - Use `/exportdatabase` before major uploads
7. **Test with samples** - Verify CSV format with small test files first (see SAMPLE FILES folder)

### File Upload Permissions

By default, only administrators can upload files. This can be changed:
- Use `/settings` command (admin only)
- Toggle "Allow Non-Admin Uploads"
- When enabled, any user in the upload channel can upload files

### Channel Processing

By default, commands only work in designated channels/threads. This can be changed:
- Use `/settings` command (admin only)
- Toggle "Allow All Channels Processing"
- When enabled, commands work in any channel where the bot is present
- File uploads always use the channel where the file was uploaded for responses

## Error Handling & Reliability

### Comprehensive Validation

**Every Command Includes Error Handling:**
- Missing parameters: Clear usage instructions provided
- Invalid input: Specific error messages explain what went wrong
- Database errors: User-friendly messages instead of technical jargon
- Permission errors: Access denied messages for admin-only commands
- Not found errors: Suggests checking spelling or available options

**Every File Upload is Validated:**
- CSV format verification before processing
- Column name and count validation
- Data type checking for each field
- Duplicate detection (players, matches)
- Referential integrity (players must exist before round upload)
- Hall consistency checks
- Active/inactive player status validation

### Intelligent Error Recovery

**Interactive Conflict Resolution:**
- Hall mismatch dialog for inactive players
- Multiple resolution options with clear explanations
- Bulk or individual resolution modes
- Cancel option to review data manually

**Automatic Corrections:**
- WALKOVER normalization (empty/whitespace → "WALKOVER")
- Whitespace trimming from player names and hall names
- Case-insensitive command parsing
- Flexible round name formats (accepts "3", "round_3", "round_t4")

**Confirmation Dialogs:**
- Admin actions require explicit confirmation
- Timeout protection (60-120 seconds depending on action)
- Clear cancel options for all interactive prompts

### Logging & Monitoring

**Dual Logging System:**
- Discord logging channel for administrator monitoring
- Telegram logging channel for status updates
- Batch logging prevents spam during file processing
- Error, warning, and info level messages
- Timestamps on all log entries

**What Gets Logged:**
- All file uploads and processing results
- Command usage (user ID, command, parameters)
- Database operations (inserts, updates, deletes)
- Settings changes (who, what, when)
- Error conditions with stack traces
- Confirmation dialog results

### Database Safety

**Transaction-Based Processing:**
- All database operations use transactions
- Rollback on error ensures no partial updates
- ACID compliance guarantees data integrity

**Backup Capabilities:**
- `/exportdatabase` creates timestamped full backup
- `/exportplayers` exports player data only
- All exports include timestamps in filename
- CSV format ensures portability

**Data Validation:**
- Foreign key constraints enforce referential integrity
- NOT NULL constraints prevent missing data
- PRIMARY KEY ensures unique records
- Type checking on all fields

## Visual Examples

All commands that generate images create publication-quality PNG files with consistent formatting, hall icons, and timezone-aware timestamps.

### Player Rankings

![Player Rankings](Github%20Images/SAMPLE_RankPlayers.png)

Dynamic leaderboards showing:
- Rank position
- Player name
- Hall affiliation with icon
- True ELO and/or Performance ELO
- Customizable filtering and top N display

---

### Player Information

![Player Information](Github%20Images/SAMPLE_InfoPlayer.png)

Comprehensive player profiles showing:
- Current ELO ratings (True and Performance)
- Hall affiliation
- Match statistics
- Victory records
- Rating history
- Capped status

---

### Player Comparison

![Player Comparison](Github%20Images/SAMPLE_ComparePlayers.png)

Side-by-side player comparison with:
- Split-screen blue/red layout
- ELO ratings for both players
- Win/loss statistics
- Head-to-head record
- Statistical advantages highlighted

---

### Hall Rankings

![Hall Rankings](Github%20Images/SAMPLE_RankHalls.png)

Team performance leaderboards showing:
- Hall rank position
- Hall name with icon
- Aggregate statistics
- Average player ELO
- Match results

---

### Hall Information

![Hall Information](Github%20Images/SAMPLE_InfoHall.png)

Detailed hall statistics showing:
- Member roster
- Average ELO ratings
- Win/loss records
- Recent performance
- Active player count

---

### Hall Comparison

![Hall Comparison](Github%20Images/SAMPLE_CompareHalls.png)

Side-by-side hall comparison with:
- Split-screen layout
- Member statistics
- Team strength indicators
- Average ELO differences
- Win rate comparisons

---

### Match Information

![Match Information](Github%20Images/SAMPLE_InfoMatch.png)

Detailed match breakdowns showing:
- All participating players
- Individual scores
- ELO changes
- Hall matchups
- WALKOVER indicators
- Round summaries

---

## Project Structure

```
ihrgstats/
├── src/
│   ├── main/
│   │   ├── java/com/calplus/ihrgstats/
│   │   │   ├── Main.java                    # Application entry point
│   │   │   ├── calculations/
│   │   │   │   └── EloCalculator.java       # ELO rating calculations
│   │   │   ├── databasemanager/
│   │   │   │   ├── DatabaseSchema.java      # Database initialization
│   │   │   │   ├── A1_PlayerStats.java      # Player statistics manager
│   │   │   │   └── A2_CappedPlayers.java    # Capped players manager
│   │   │   ├── discordbot/
│   │   │   │   └── logs/DiscordLog.java     # Discord logging
│   │   │   ├── telegrambot/
│   │   │   │   ├── listener/
│   │   │   │   │   └── TelegramListener.java    # Main Telegram handler
│   │   │   │   ├── commands/                    # All command handlers
│   │   │   │   │   ├── CommandRankPlayers.java
│   │   │   │   │   ├── CommandRankHalls.java
│   │   │   │   │   ├── CommandInfoPlayer.java
│   │   │   │   │   ├── CommandInfoHall.java
│   │   │   │   │   ├── CommandInfoMatch.java
│   │   │   │   │   ├── CommandComparePlayers.java
│   │   │   │   │   ├── CommandCompareHalls.java
│   │   │   │   │   ├── CommandExportPlayers.java
│   │   │   │   │   ├── CommandExportDatabase.java
│   │   │   │   │   ├── CommandSettings.java
│   │   │   │   │   ├── CommandHelp.java
│   │   │   │   │   └── CommandAbout.java
│   │   │   │   └── logs/TelegramLog.java    # Telegram logging
│   │   │   └── utils/                       # Utility classes
│   │   │       ├── ComparisonImageGenerator.java
│   │   │       ├── InfoImageGenerator.java
│   │   │       ├── TableImageGenerator.java
│   │   │       ├── DatabaseHelper.java
│   │   │       ├── LogHelper.java
│   │   │       ├── PropertyManager.java
│   │   │       ├── TimezoneHelper.java
│   │   │       └── ...
│   │   └── resources/
│   │       ├── application.properties        # Configuration
│   │       └── halls/                        # Hall icons (PNG files)
│   └── test/
│       └── java/com/calplus/ihrgstats/      # Test files
├── database/
│   └── core/
│       └── default.db                        # SQLite database (auto-created)
├── temp/                                     # Temporary files (auto-created)
├── Github Images/                            # Sample images for README
├── SAMPLE FILES/                             # Sample CSV files for testing
├── .env.properties                           # Environment variables (create this)
├── pom.xml                                   # Maven configuration
└── README.md                                 # This file
```

## Support

### Getting Help

For support, questions, or feature requests:
- Open an issue on [GitHub](https://github.com/Calplus/ihrgstats)
- Check existing documentation in this README
- Review closed issues for similar problems
- Contact: [@Calplus](https://github.com/Calplus)

### Troubleshooting Common Issues

**Bot not responding:**
- Verify bot tokens in `.env.properties`
- Check bot is added to channels/groups
- Confirm bot has proper permissions
- Review logs for error messages

**File upload fails:**
- Verify file format matches templates in SAMPLE FILES folder
- Check file name matches expected patterns
- Ensure playerExport uploaded before round files
- Review validation error messages carefully

**Commands return errors:**
- Verify data exists in database (upload files first)
- Check player/hall names match database exactly
- Ensure admin commands used by administrators only
- Verify command format matches examples in Commands Reference

**Images not generating:**
- Verify hall icons exist in `src/main/resources/halls/`
- Check sufficient disk space for temp files
- Review logs for image generation errors
- Confirm Java graphics libraries are installed

**Database issues:**
- Ensure only one bot instance is running
- Verify database file permissions
- Check disk space availability
- Use `/exportdatabase` to backup before troubleshooting

### Sample Files

The `SAMPLE FILES` folder contains example CSV files demonstrating correct formats:
- `playerExport_YYYYMMDD_HHMMSS.csv` - Initial player data
- `cappedlist.csv` - Capped players list
- `round_1.csv` through `round_6.csv` - Round results
- `round_t16.csv`, `round_t8.csv`, `round_t4.csv`, `round_t2.csv` - Tournament rounds

Use these files as templates when creating your own data files.