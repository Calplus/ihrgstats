<div align = "center">

# IHRG Statistics Bot

<img src="Github%20Images/Icon_IHRGStats.png" width="75%" alt="IHRG Stats Icon">

![Version](https://img.shields.io/badge/version-Beta%202%20Update%2011-blue) &nbsp; ![Last Updated](https://img.shields.io/badge/Last%20Updated-Jul%2012,%202026-red)

<img src="src/main/resources/halls/4.png" width="18" height="18" alt="Hall 4"> *Developed with love, 4 Hall 4* <img src="src/main/resources/halls/4.png" width="18" height="18" alt="Hall 4">
</div>

A powerful and intelligent statistics tracking bot designed specifically for NTU's Inter-Hall Recreational Games (IHRG). View stunning visualizations, detailed player/team analytics, and comprehensive rankings with just a few commands. Whether you're a manager/captain wondering about your opponent's skills, or a player wishing to know more about their upcoming opponent, the IHRG Statistics Bot delivers professional-grade statistics with minimal effort for everyone!



<div align = "center">

*DISCLAIMER: All data used in the examples here are compeltely made-up and randomly generated, and not representitive of any of the aforementioned person's skills or talent. All names used are either fictional or historical figures, and are merely used for fun. All fictional names belong to their respective copyright holder(s). Any coincidences or similarities to real-life events involving historical figures are completely coincidental. No harm or message is intended by the examples used here. Hall Logos used are designed by the respective JCRCs of NTU Halls.*

</div>



## Table of Contents

- [About IHRGStats](#About-IHRGStats)
- [Commands](#commands)
- [File Uploads & Data Handling](#File-Uploads--Data-Handling)
- [Installation & Server Deployment](#installation--server-deployment)
- [File Upload System](#file-upload-system)
- [Error Handling & Reliability](#error-handling--reliability)
- [Project Structure](#project-structure)
- [Support](#support)

## About IHRGStats

IHRG Statistics Bot is the complete solution for tournament organizers and competitive players who demand accuracy, automation, and beautiful presentation. Built from the ground up for IHRG tournaments, this bot eliminates manual spreadsheet work and delivers instant insights through an intuitive command interface.

### Who is it for?

**- Captains/Managers**: Check how your team is doing against other teams in a tournament, and view statistics about your or your opponent's teams!

**- Players**: View your previous tournament matches, and check how good your opponent(s) are!

### Key Features

**1. Simple Telegram Integration**: Seamlessly integrate the bot on Telegram, ensuring your community can access statistics anytime!

**2. Ease of Management**: Administrators can optionally set up detailed logs to both Telegram and Discord, without the need to open up server console!

**3. Advanced ELO System**: Uses Batch Glicko-2 with increased volatility for precise and distinct skill assessment with as few games as possible. Ratings are kept consistent across the whole tournament history via a whole-history recalculation that replays every round across every year whenever new results come in (or on-demand via `/recalculate`), while point-in-time snapshots ensure "rankings as of round N" always stay accurate even after a recalculation.

**4. Quick Data Retrieval**: Built on SQLite with comprehensive validation, your tournament data is stored reliably. Every file upload is thoroughly verified, every mismatch is caught, and every change is logged.

**5. Professional Visualizations**: Generate publication-quality images for rankings, player profiles, and head-to-head comparisons. Each image is timestamped, customizable, and ready to share with your community.

**6. Smart Automation**: Intelligent file processing detects errors before they corrupt your database. Interactive confirmation dialogs guide you through data conflicts, and comprehensive error messages explain exactly what went wrong and how to fix it.

**7. Admin-Friendly**: Granular permission controls, interactive settings management, and detailed activity logging ensure secure administration without technical complexity.

**8. Rounds Adaptability across games**: Rounds are numbered sequentially per year and are no longer tied to a fixed swiss/bracket split - upload as many rounds as your tournament format needs, in order.

## Commands

### Player Analytics

**1. Individual Player Profiles (/infoplayer)**

Get comprehensive statistics for any player. View their Elo, Past seating, Victory records, Hall affiliation, and performance trends over time.

<div align = "center">
<img src="Github%20Images/SAMPLE_InfoPlayer.png">
</div>

**2. Player Rankings (/rankplayers)**

Display player rankings sorted by trueElo. Easily view player's Capped status, Hall affiliation, Elo and Last Round played. Players part of the hall you selected will be highlighted!

<div align = "center">
<img src="Github%20Images/SAMPLE_RankPlayers.png">
</div>

**3. Player Comparisons (/compareplayers)**

Compare two players side-by-side with visual split-screen layouts. See Round Stats, Past Seating and Victory Records at a glance.

<div align = "center">
<img src="Github%20Images/SAMPLE_ComparePlayers.png">
</div>



### Hall Analytics

**4. Hall Information (infohall)**

View detailed statistics for individual halls including member rosters, win/loss records, average ELO ratings, and recent performance trends.

<div align = "center">
<img src="Github%20Images/SAMPLE_InfoHall.png">
</div>

**5. Hall Rankings (/rankhalls)**

See which halls dominate the competition based on aggregate player performance and match results! Easily view each hall's total Cap Points and Average Elo, taken from the top 5 players of the team. The hall you selected in the settings will be highlighted!

<div align = "center">
<img src="Github%20Images/SAMPLE_RankHalls.png">
</div>

**6. Hall Comparisons (/comparehalls)**

Compare two halls side-by-side to analyze team strength differences, member statistics, and competitive advantages!

<div align = "center">
<img src="Github%20Images/SAMPLE_CompareHalls.png">
</div>



### Match & Tournament Data

**7. Match Information (/infomatch)**

Retrieve a high-level breakdown of any match by round number. See all participating teams, scores and match outcomes including proper WALKOVER handling.

<div align = "center">
<img src="Github%20Images/SAMPLE_InfoMatch.png">
</div>

**8. Hall Match Details (/infomatchhall)**

View comprehensive match information for a specific hall in a specific round. 

<div align = "center">
<img src="Github%20Images/SAMPLE_InfoMatchHall.png">
</div>



### Miscellanious

**9. Help (/help)**

You ask it for help. Or it asks you for help. Either way, you get to choose whether you need help for commands, or file upload format. You can also ask it for specifics of a file, if you so choose.

<p align="center">
  <img src="Github%20Images/TELEGRAM_help.png" width="45%" />
  <img src="Github%20Images/TELEGRAM_helpfiles.png" width="45%" />
</p>

<p align="center">
  <img src="Github%20Images/TELEGRAM_helpcommands.png" width="45%" />
  <img src="Github%20Images/TELEGRAM_helpfilesround.png" width="45%" />
</p>

**10. About (/about)**

about.

<div align = "center">
<img src="Github%20Images/TELEGRAM_about.png">
</div>



### Administrator Commands

**11. (ADMIN) Settings (/settings)**

Fine-tune specific settings for your bot, from your home hall to allowing file processing in all channels!

<div align = "center">
<img src="Github%20Images/TELEGRAM_settings.png">
</div>

**12. (ADMIN) Match Types (/matchtypes)**

Create, list, and edit match types (name, max score, time limit, description), and assign a match type to a round that was uploaded without one. A match type must be assigned to a round before it can process a WALKOVER, since the walkover's default score is derived from the match type's max score.

**13. (ADMIN) Recalculate Ratings (/recalculate)**

Manually triggers the whole-history rating recalculation: every stored round across every year is replayed through the rating engine and `player_ratings` is rewritten from scratch. This runs automatically after every round upload already - use this command to refresh ratings on demand (e.g. after a rating-engine update) without re-uploading any files. Point-in-time snapshots ("rankings as of round N") are never touched by a recalculation.

**14. (ADMIN) Export Database (/exportdatabase)**

Need to bug-test your database? Run your own data analytics? Migrating servers? Backing up your database? Choose between a full **.xlsx export** (one sheet per populated table - great for spreadsheet analysis) or the **raw .db file** (a drop-in backup/restore of the entire SQLite database). Sent to your DM for additional privacy!

<div align = "center">
<img src="Github%20Images/TELEGRAM_exportdatabase.png">
</div>



## File Uploads & Data Handling

<div align = "center">
<img src="Github%20Images/TELEGRAM_roundnamemismatch.png">
</div>

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
- (OPTIONAL) Discord Bot Token (from Discord Developer Portal)
- (OPTIONAL) Server with persistent storage and internet connectivity

### Step 1: Download and Extract

```bash
git clone https://github.com/Calplus/ihrgstats.git
cd ihrgstats
```

### Step 2: Build the Application (Maven)
```bash
mvn clean compile
mvn package
```

### Step 3: Configure Environment Variables

Create a file named `.env.properties` in the project root directory:

```properties
# Settings
SETTINGS_ALLOWNONADMINUPLOADS=false
SETTINGS_ALLOWALLCHANNELSPROCESSING=true
SETTINGS_HOMEHALL=
SETTINGS_CURRENTYEAR=
SETTINGS_TIMEZONE=0

# Internet Connectivity
INTERNET_WEBHOOK_URL=
INTERNET_WEBHOOK_PORT=
INTERNET_WEBHOOK_TIMEOUTMS=5000

# Discord Bot Configuration
DISCORD_BOT_TOKEN=
DISCORD_LOG_CHANNELID=
DISCORD_ADMIN_USERID=

# Telegram Bot Configuration
TELEGRAM_BOT_TOKEN=
TELEGRAM_ADMIN_USERID=
TELEGRAM_DEV_CHATID=
TELEGRAM_DEV_CHATID_LOG=
TELEGRAM_DEV_CHATID_STATUS=
TELEGRAM_PUBLIC_CHATID=
TELEGRAM_PUBLIC_CHATID_FILEUPLOAD=
TELEGRAM_PUBLIC_CHATID_COMMANDS=
```

**Settings Explained:**
- `SETTINGS_ALLOWNONADMINUPLOADS`: Allow non-admin users to upload files for processing
- `SETTINGS_ALLOWALLCHANNELSPROCESSING`: Allow file processing from any channel (not just configured ones)
- `SETTINGS_HOMEHALL`: Your home hall number for highlighting in image exports
- `SETTINGS_CURRENTYEAR`: The tournament year currently being played. Used to resolve which year's rounds/players a command or upload operates on - required before uploading `round_{n}.csv` (without a year prefix) or `cappedlist.csv`
- `SETTINGS_TIMEZONE`: Timezone offset for displaying timestamps (e.g., 8 for UTC+8)

**Internet Connectivity Settings:**
- `INTERNET_WEBHOOK_URL`: Webhook URL if using webhook mode (leave empty for polling)
- `INTERNET_WEBHOOK_PORT`: Port number for webhook server
- `INTERNET_WEBHOOK_TIMEOUTMS`: Webhook timeout in milliseconds

*Note: ChatID subsettings only apply if you are using a telegram groups with multiple channels/threads. Populate it with the channel/thread ID. Else, leave it blank.*

*Discord settings are optional and not required for core functionality.*

*You can also modify these settings at runtime using the `/settings` command (admin only).*

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
1. Visit the [Discord Developer Portal](https://discord.com/developers/applications)
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
scp <JAR_NAME>.jar user@yourserver:/opt/ihrgstats/
scp .env.properties user@yourserver:/opt/ihrgstats/

# SSH into your server
ssh user@yourserver

# Navigate to the application directory
cd /opt/ihrgstats

# Run the bot (quote the jar name - the version string contains spaces)
java -jar "<JAR_NAME>.jar"
```

**For Oracle Cloud Servers:**
```bash
# Upload JAR file to server
scp -i "<PUBLIC_KEY_FILEPATH>" "<.JAR FILEPATH>" opc@<SERVER_IP>:~/

# SSH into your server
ssh -i "<PUBLIC_KEY_FILEPATH>" opc@<SERVER_IP>
cd <DIRECTORY>

# Create .env.properties file
nano .env.properties

# Run the bot
nohup java -Denv.file.path=./.env.properties -jar <.JAR NAME>.jar
```

**For Windows Servers:**
```powershell
# Copy files to server directory
# Open PowerShell or Command Prompt in the application directory

# Run the bot (quote the jar name - the version string contains spaces)
java -jar "<JAR_NAME>.jar"
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
ExecStart=/usr/bin/java -jar "/opt/ihrgstats/<JAR_NAME>.jar"
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
6. Arguments: `-jar "C:\path\to\<JAR_NAME>.jar"`
7. Start in: `C:\path\to\`

### Verification

After deployment, the bot should send a ping message every 5 minutes to the dev/log channel you choose on Telegram.

## File Upload System


The bot accepts two types of CSV files for data import. Upload files directly to the configured Telegram upload channel. Players don't need a separate import file - they're created automatically the first time their name appears in an uploaded round.

You may refer to the "SAMPLE FILES" folder in the project repo.

### File Types

#### 1. cappedlist.csv
List of capped players (released from previous year's IHRG).

**Format:**
```csv
name,hall
Hank Schrader,1
Kim Wexler,1
Jon Snow,3
```

**Columns:**
- `name`: Player's stored name
- `hall`: Player's hall affiliation

**Error Handling:**
- No current year: "Cannot process cappedlist.csv: no current year set. An admin must set settings.currentYear first." (only applies to the plain `cappedlist.csv` name - use `{year}_cappedlist.csv` to specify the year directly)
- File not found: "cappedlist.csv file not found at: (path)"
- Invalid format: "Invalid CSV format: Header must have exactly 2 columns (name,hall)"
- Incorrect headers: "Invalid CSV format: Header must be 'name,hall' (case insensitive)"
- CSV validation failed: "CSV validation failed: (error_message)"
- Database update failed: "Database update failed: (error_message)"
- Empty file: "No capped players found in file"

---

#### 2. {year}_round_{n}.csv (or round_{n}.csv)
Round results file containing match outcomes for a specific round. Rounds are just sequential numbers now - there's no separate "swiss" vs "bracket" filename convention. Number them however your tournament format needs (e.g. rounds 1-6 as swiss, then 7-10 continuing as bracket rounds).

- `{year}_round_{n}.csv` (e.g. `2025_round_1.csv`) is self-contained and always processes into that year.
- `round_{n}.csv` (no year prefix) falls back to the admin-configured `settings.currentYear` - an admin must set it first via `/settings`.

**Format:**
```csv
name1,hall1,score1,name2,hall2,score2
Hank Schrader,1,59.5,Princess Bubblegum,2,0
Jesse Pinkman,1,0,Jake the Dog,2,191
Kim Wexler,1,0,Flame Princess,2,0
```

**Columns:**
- `name1`, `name2`: Player names. Use "WALKOVER" for walkover opponents (only one per row).
- `hall1`, `hall2`: Hall names (numeric or short name, e.g., "4", "Binjai"). Remove the word "Hall" (e.g., "Hall 4" → "4"). For a WALKOVER row, only the non-walkover side's hall is required.
- `score1`, `score2`: Each side's raw board score. Both are required and numeric for standard games (equal values = a draw). Leave both blank for a WALKOVER row - the bot computes the default walkover score automatically from the round's assigned match type. For a game decided by a clock timeout rather than a finished board, put `TIMEOUT` in the losing side's score cell instead of a number - the winning side's cell can still hold a real recorded score, or `0` if none was recorded.

**Rules & Validation:**
- The header must be exactly: `name1,hall1,score1,name2,hall2,score2` (case-insensitive, no extra spaces).
- Each row represents a match between two players. If a player is absent (walkover), use "WALKOVER" for their name and leave their hall/score blank.
- Only one "WALKOVER" per row is allowed.
- Hall names must match those in the database (case-insensitive, whitespace trimmed).
- A round containing a WALKOVER must have a match type assigned (see `/matchtypes`) so the default walkover score can be computed.
- A TIMEOUT result is still a real, rated win/loss (unlike a WALKOVER) - both players must be real, present names with valid halls. Only one side's score cell may be `TIMEOUT`.
- Player names must not be empty. Both players cannot be "WALKOVER" in the same row.
- All matches must be valid and not duplicated.

**Error Handling:**
- Invalid filename: "Unknown file type: (filename). Accepted files: cappedlist.csv, {year}_cappedlist.csv, {year}_round_[n].csv, round_[n].csv"
- No year available: "Cannot process (filename): filename has no year prefix and no current year is set..."
- File not found: "round_{n}.csv file not found at: (path)"
- Invalid format: "Invalid CSV format: Header must have exactly 6 columns (name1,hall1,score1,name2,hall2,score2)"
- Incorrect headers: "Invalid CSV header: Expected '(col)' at column (n), found '(col)'"
- Duplicate matches: "Duplicate match found for player: (name)"
- Invalid values: "Invalid CSV format at line (n): ..."
- Hall mismatch (active players): Stops processing with error message
- Hall mismatch (inactive players): Interactive resolution dialog (see below)
- WALKOVER detection: Only one walkover per row; both cannot be walkover.

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

---

### Recommended Upload Workflow

1. **Set the current year** - Use `/settings` to set `settings.currentYear` (or use year-prefixed filenames throughout)
2. **Set up match types** - Use `/matchtypes` to create at least one match type before any round with a WALKOVER
3. **Upload cappedlist.csv** - Updates capping status for players
4. **Upload round files in order** - Process round_1, round_2, etc. sequentially (players are created automatically on first appearance)
5. **Monitor confirmation messages** - Check for warnings or validation errors
6. **Resolve conflicts promptly** - Handle hall mismatch dialogs immediately
7. **Backup regularly** - Use `/exportdatabase` before major uploads

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

**Confirmation Dialogs:**
- Admin actions require explicit confirmation
- Timeout protection (60-120 seconds depending on action)
- Clear cancel options for all interactive prompts

### Logging & Monitoring

**Dual Logging System:**
- Discord & Telegram logging channel for administrator monitoring
- Batch logging prevents spam during file processing
- Error, warning, and info level messages
- Timestamps + Source file + User (If Applicable) on all log entries

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
- Does not write/modify any data until all checks passed

**Backup Capabilities:**
- `/exportdatabase` creates a timestamped full backup - choose a full .xlsx (one sheet per table) or the raw .db file
- Restoring from a .db export is a straight file swap - no re-import step needed
- All exports include timestamps in filename

## Project Structure

```
ihrgstats/
├── src/
│   ├── main/
│   │   ├── java/com/calplus/ihrgstats/
│   │   │   ├── Main.java                          # Application entry point
│   │   │   ├── calculations/
│   │   │   │   ├── EloCalculator.java             # Batch Glicko-2 rating math
│   │   │   │   └── RatingRecalculator.java        # Whole-history multi-pass recalculation
│   │   │   ├── databasemanager/                   # DAOs for every table (DatabaseSchema.java + A/B/C/D/E-prefixed managers)
│   │   │   ├── discordbot/
│   │   │   │   └── logs/DiscordLog.java           # Discord logging
│   │   │   ├── telegrambot/
│   │   │   │   ├── listener/
│   │   │   │   │   └── TelegramListener.java      # Main Telegram handler
│   │   │   │   ├── commands/                      # All command handlers
│   │   │   │   │   ├── CommandRankPlayers.java
│   │   │   │   │   ├── CommandRankHalls.java
│   │   │   │   │   ├── CommandInfoPlayer.java
│   │   │   │   │   ├── CommandInfoHall.java
│   │   │   │   │   ├── CommandInfoMatch.java
│   │   │   │   │   ├── CommandInfoMatchHall.java
│   │   │   │   │   ├── CommandComparePlayers.java
│   │   │   │   │   ├── CommandCompareHalls.java
│   │   │   │   │   ├── CommandMatchTypes.java
│   │   │   │   │   ├── CommandRecalculate.java
│   │   │   │   │   ├── CommandExportDatabase.java
│   │   │   │   │   ├── CommandSettings.java
│   │   │   │   │   ├── CommandHelp.java
│   │   │   │   │   └── CommandAbout.java
│   │   │   │   ├── utils/                         # RoundCsvProcessor, MatchScoreUtils, etc.
│   │   │   │   └── logs/TelegramLog.java          # Telegram logging
│   │   │   └── utils/                             # Shared utility classes (TelegramHtml, TableFormatter, VictoryRecordCalculator, ...)
│   │   └── resources/
│   │       ├── application.properties             # Configuration
│   │       └── halls/                             # Hall icons (PNG files)
│   └── test/
│       └── java/com/calplus/ihrgstats/            # Test files
├── database/
│   └── core/
│       └── default.db                             # SQLite database (auto-created)
├── temp/                                          # Temporary files (auto-created, gitignored)
├── docs/legacy/                                   # Retired reference material (e.g. pre-v2 schema)
├── Github Images/                                 # Sample images for README
├── SAMPLE FILES/                                  # Sample CSV files for testing/reference
│   └── backup_old_format/                         # Pre-v2 sample files, kept for reference
├── .env.properties                                # Environment variables (SENSITIVE)
├── pom.xml                                        # Maven configuration
└── README.md                                      # This file (lol)
```

## Support

### Getting Help

Try googling before contacting me :)

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
- Check file name matches expected patterns ({year}_round_[n].csv, round_[n].csv, cappedlist.csv, {year}_cappedlist.csv)
- If using a year-less filename, ensure `settings.currentYear` is set via `/settings`
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
- `cappedlist.csv` - Capped players list
- `2025_round_1.csv` through `2025_round_10.csv` - Round results (rounds 1-6 swiss, 7-10 continuing as bracket rounds)

Pre-v2 originals (old `winby1/winby2` format, `round_t16.csv`-style bracket names) are preserved under `SAMPLE FILES/backup_old_format/` for reference.

Use these files as templates when creating your own data files.