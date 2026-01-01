# IHRG Stats Bot

A comprehensive statistics tracking and analysis bot for the International Hanafuda Rating Group (IHRG). This bot processes player statistics, calculates Elo ratings, generates visualizations, and provides detailed analytics through both Telegram and Discord interfaces.

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Configuration](#configuration)
- [Usage](#usage)
- [Commands](#commands)
- [Data Management](#data-management)
- [Image Generation](#image-generation)
- [Development](#development)
- [Project Structure](#project-structure)
- [Dependencies](#dependencies)
- [Contributing](#contributing)
- [License](#license)

## Features

### Core Functionality

- **Dual Bot Integration**: Operates on both Telegram and Discord platforms simultaneously
- **ELO Rating System**: Implements both True Elo and Performance Elo calculations with Glicko-2 volatility
- **Database Management**: SQLite-based persistent storage for player statistics, match history, and hall rankings
- **CSV File Processing**: Automated import and validation of player data, capped players list, and round results
- **Real-time Logging**: Comprehensive logging system with batch operations for both Discord and Telegram

### Player Statistics

- **Individual Player Info**: Detailed statistics including Elo ratings, match history, and victory records
- **Player Rankings**: Dynamic leaderboards for both True Elo and Performance Elo
- **Player Comparisons**: Side-by-side comparison of two players with visual representations
- **Export Functionality**: Export complete player database to CSV format with timestamps

### Hall Management

- **Hall Rankings**: Track and display hall-level statistics and rankings
- **Hall Information**: Detailed hall statistics with win/loss records
- **Hall Comparisons**: Visual comparison between two halls
- **Home Hall Setting**: Configure default hall for personalized queries

### Match & Data Analysis

- **Match Information**: Retrieve detailed information about specific matches by round number
- **Victory Records**: Track and display player victory records with opponent information
- **Statistical Validation**: Automatic validation of uploaded data with mismatch detection and resolution
- **Active/Inactive Player Management**: Handle player status changes with interactive resolution for discrepancies

### Visual Generation

- **Table Images**: Generate ranked tables for players and halls with customizable layouts
- **Information Cards**: Create detailed info cards for individual players, halls, or matches
- **Comparison Images**: Side-by-side visual comparisons with split-screen layouts
- **Customizable Metadata**: Include titles, descriptions, timestamps, and custom data in generated images
- **Timezone-Aware Timestamps**: All generated images use configured timezone for consistency

### Administrative Features

- **Settings Management**: Interactive settings menu for administrators
  - Toggle boolean settings (Performance Elo, Non-admin uploads, Channel processing)
  - Configure home hall from database
  - Set max seeds value
  - Configure timezone (UTC-12 to UTC+14)
- **Database Export**: Export complete database with administrative approval
- **File Upload Controls**: Admin-only file processing with confirmation dialogs
- **Logging System**: Comprehensive error tracking and status reporting

## Architecture

### System Design

```
┌─────────────────────────────────────────────────────────────┐
│                       Main Application                       │
│                      (Main.java)                            │
└──────────────────┬──────────────────────────────────────────┘
                   │
          ┌────────┴────────┐
          │                 │
┌─────────▼─────────┐  ┌───▼───────────┐
│  Telegram Bot     │  │  Discord Bot  │
│  (TelegramListener)│  │  (DiscordLog) │
└─────────┬─────────┘  └───┬───────────┘
          │                 │
          └────────┬────────┘
                   │
       ┌───────────▼──────────────┐
       │    Command Handlers      │
       │ • RankPlayers            │
       │ • RankHalls              │
       │ • InfoPlayer             │
       │ • InfoHall               │
       │ • InfoMatch              │
       │ • ComparePlayers         │
       │ • CompareHalls           │
       │ • ExportPlayers          │
       │ • ExportDatabase         │
       │ • Settings               │
       │ • Help                   │
       │ • About                  │
       └───────────┬──────────────┘
                   │
       ┌───────────▼──────────────┐
       │   Database Manager       │
       │ • A1_PlayerStats         │
       │ • A2_CappedPlayers       │
       │ • DatabaseSchema         │
       └───────────┬──────────────┘
                   │
       ┌───────────▼──────────────┐
       │       Utilities          │
       │ • EloCalculator          │
       │ • VictoryRecordCalculator│
       │ • ImageGenerators        │
       │ • TimezoneHelper         │
       │ • PropertyManager        │
       └──────────────────────────┘
```

### Data Flow

1. **File Upload**: Users upload CSV files (playerExport, cappedlist, round_n) through Telegram
2. **Validation**: System validates data format and checks for discrepancies
3. **Database Update**: Validated data is processed and stored in SQLite database
4. **Command Processing**: Users issue commands to query statistics
5. **Calculation**: System calculates Elo ratings, victory records, and rankings
6. **Image Generation**: Visual representations are created with timezone-aware timestamps
7. **Response Delivery**: Results are sent back to users through Telegram/Discord

## Prerequisites

### System Requirements

- **Java Development Kit (JDK)**: Version 24 or higher
- **Apache Maven**: Version 3.6.0 or higher (or Maven Daemon for faster builds)
- **Operating System**: Windows 11, macOS, or Linux
- **Memory**: Minimum 2GB RAM (4GB recommended)
- **Disk Space**: At least 500MB for application and database

### API Requirements

- **Telegram Bot Token**: Obtain from [@BotFather](https://t.me/botfather) on Telegram
- **Discord Bot Token**: Create an application on [Discord Developer Portal](https://discord.com/developers/applications)
- **Administrator IDs**: Telegram and Discord user IDs for administrative access

## Installation

### 1. Clone or Download the Repository

```bash
git clone https://github.com/Calplus/ihrgstats.git
cd ihrgstats
```

### 2. Configure Environment Variables

Create a `.env.properties` file in the project root directory:

```properties
# Discord Bot Configuration
DISCORD_BOT_TOKEN=your_discord_bot_token_here
DISCORD_LOG_CHANNELID=your_discord_log_channel_id
DISCORD_ADMIN_USERID=your_discord_admin_user_id

# Telegram Bot Configuration
TELEGRAM_BOT_TOKEN=your_telegram_bot_token_here
TELEGRAM_ADMIN_USERID=your_telegram_admin_user_id
TELEGRAM_DEV_CHATID=your_dev_chat_id
TELEGRAM_DEV_CHATID_LOG=your_dev_log_chat_id
TELEGRAM_DEV_CHATID_STATUS=your_dev_status_chat_id
TELEGRAM_PUBLIC_CHATID=your_public_chat_id
TELEGRAM_PUBLIC_CHATID_FILEUPLOAD=your_public_fileupload_chat_id
TELEGRAM_PUBLIC_CHATID_COMMANDS=your_public_commands_chat_id
```

**How to get these values:**

- **Discord Bot Token**: 
  1. Visit https://discord.com/developers/applications
  2. Create a new application
  3. Go to "Bot" section and click "Reset Token"
  4. Copy the token

- **Discord Channel/User IDs**: 
  1. Enable Developer Mode in Discord (User Settings → Advanced → Developer Mode)
  2. Right-click on channels/users and select "Copy ID"

- **Telegram Bot Token**:
  1. Message [@BotFather](https://t.me/botfather) on Telegram
  2. Send `/newbot` and follow the instructions
  3. Copy the provided token

- **Telegram User/Chat IDs**:
  1. Message [@userinfobot](https://t.me/userinfobot) to get your user ID
  2. Add [@RawDataBot](https://t.me/rawdatabot) to a chat to get chat IDs

### 3. Build the Project

Using Maven:
```bash
mvn clean package
```

Using Maven Daemon (faster):
```bash
mvnd clean package
```

The compiled JAR file will be located in the `target/` directory.

### 4. Initialize the Database

The database will be automatically created on first run. The application will:
- Create `database/core/` directory structure
- Initialize `default.db` with required tables
- Set up schema for player stats, capped players, and match data

## Configuration

### Application Settings

The `src/main/resources/application.properties` file contains all configurable settings:

```properties
# Feature Toggles
settings.perfElo.enabled=true
settings.allowNonAdminUploads=true
settings.allowAllChannelsProcessing=false

# Home Hall Configuration
settings.homeHall=4

# Maximum Seeds
settings.maxSeeds=368.5

# Timezone Configuration (UTC offset)
settings.timezone=8
```

**Settings Explanation:**

- **perfElo.enabled**: Enable/disable Performance Elo calculations
- **allowNonAdminUploads**: Allow non-administrators to upload CSV files
- **allowAllChannelsProcessing**: Process files from any channel (not just configured channels)
- **homeHall**: Default hall number for queries
- **maxSeeds**: Maximum seed value for Elo calculations
- **timezone**: UTC offset for all timestamps (e.g., `8` for UTC+8, `-5` for UTC-5, `9.5` for UTC+9.5)

### Timezone Configuration

The timezone setting affects:
- All generated images (filenames and embedded timestamps)
- Log file timestamps
- About command display
- Export file timestamps
- Database record timestamps

The timezone can be configured:
1. **Via Settings Command**: Use `/settings` in Telegram and select "Change Timezone"
2. **Via Properties File**: Edit `settings.timezone` in `application.properties`
3. **Values**: Any UTC offset from -12 to +14 (supports half-hours like 9.5)

### Runtime Configuration

Many settings can be changed at runtime using the `/settings` command (admin only):
- Performance Elo toggle
- Non-admin upload permissions
- Channel processing mode
- Home hall selection
- Max seeds value
- Timezone selection

## Usage

### Starting the Bot

Run the compiled JAR file:

```bash
java -jar target/ihrgstats-1.0.0.jar
```

Or use the Maven exec plugin:

```bash
mvn exec:java -Dexec.mainClass="com.calplus.ihrgstats.Main"
```

The application will:
1. Load environment variables from `.env.properties`
2. Initialize the database if it doesn't exist
3. Start the Telegram listener
4. Begin logging to configured Discord and Telegram channels

### Stopping the Bot

- **Graceful Shutdown**: Press `Ctrl+C` in the terminal
- The shutdown hook will:
  - Stop the Telegram listener
  - Flush all pending log messages
  - Close database connections
  - Save any unsaved data

### CSV File Upload Workflow

#### 1. Player Export (playerExport.csv)

Upload a CSV file with the following columns:
```
name,capped,active,hall,baseTrueElo,perfElo,baseRdTrueElo,baseVolTrueElo,baseRdPerfElo,baseVolPerfElo,dateLogged
```

**Example:**
```csv
John Doe,0,1,4,1500.0,1520.0,350.0,0.06,350.0,0.06,2026-01-01 12:00:00
Jane Smith,1,1,4,1650.0,1680.0,300.0,0.05,300.0,0.05,2026-01-01 12:00:00
```

**Validation:**
- System checks for name and hall matches with existing records
- Active players (active=1) with hall mismatches generate errors
- Inactive players (active=0) with hall mismatches trigger interactive resolution

#### 2. Capped Players List (cappedlist.csv)

Upload a CSV file with player names who are capped:
```
PlayerName
```

**Example:**
```csv
John Doe
Jane Smith
```

**Processing:**
- Must be uploaded AFTER playerExport
- Updates the `capped` field for matching players
- Validates against existing player database

#### 3. Round Results (round_1.csv, round_2.csv, etc.)

Upload CSV files with match results:
```
name,hall,oppName,oppHall,hallElo,oppElo,perfElo,oppPerfElo,score
```

**Example:**
```csv
John Doe,4,Jane Smith,4,1500,1650,1520,1680,185-160
```

**Processing:**
- Must be uploaded AFTER playerExport and cappedlist
- Validates player names against database
- Calculates victory records
- Updates player statistics

## Commands

### Player Commands

#### `/rankplayers [hall] [top N]`
Display player rankings with optional filters.

**Examples:**
- `/rankplayers` - Show top 20 players across all halls
- `/rankplayers 4` - Show top 20 players from Hall 4
- `/rankplayers 4 50` - Show top 50 players from Hall 4
- `/rankplayers all 100` - Show top 100 players across all halls

**Output**: Table image with rank, name, hall, ELO ratings, and victory records

#### `/infoplayer <name>`
Get detailed information about a specific player.

**Example:**
- `/infoplayer John Doe` - Show John Doe's complete statistics

**Output**: Information card with:
- ELO ratings (True Elo and Performance Elo if enabled)
- Glicko-2 ratings (RD and Volatility)
- Match statistics
- Victory records with opponent details
- Last round information

#### `/compareplayers <name1> vs <name2>`
Compare two players side-by-side.

**Example:**
- `/compareplayers John Doe vs Jane Smith`

**Output**: Split-screen comparison image with both players' statistics

### Hall Commands

#### `/rankhalls [top N]`
Display hall rankings.

**Examples:**
- `/rankhalls` - Show top 20 halls
- `/rankhalls 50` - Show top 50 halls

**Output**: Table image with hall icons, rankings, and statistics

#### `/infohall <hall>`
Get detailed information about a specific hall.

**Example:**
- `/infohall 4` - Show Hall 4's complete statistics

**Output**: Information card with hall statistics and victory records

#### `/comparehalls <hall1> vs <hall2>`
Compare two halls side-by-side.

**Example:**
- `/comparehalls 4 vs Binjai`

**Output**: Split-screen comparison image with both halls' statistics

### Match & Data Commands

#### `/infomatch <round_number>`
Get information about a specific round.

**Example:**
- `/infomatch 5` - Show Round 5 match details

**Output**: Information card with match statistics and results

#### `/exportplayers`
Export the complete player database to CSV format.

**Admin Only**: Requires administrator privileges

**Output**: CSV file with all player statistics (timestamped filename)

#### `/exportdatabase`
Export the complete SQLite database file.

**Admin Only**: Requires administrator privileges and confirmation

**Output**: `.db` file with complete database backup (timestamped filename)

### Utility Commands

#### `/settings`
Open the interactive settings menu (admin only).

**Features:**
- Toggle boolean settings with buttons
- Select home hall from database
- Set max seeds value
- Configure timezone
- All changes take effect immediately

**Settings Available:**
- Performance Elo: Enable/disable performance ELO calculations
- Allow Non-Admin Uploads: Let non-admins upload CSV files
- Allow All Channels Processing: Process files from any channel
- Home Hall: Set default hall for queries
- Max Seeds: Configure maximum seed value
- Timezone: Set UTC offset for timestamps

#### `/help`
Display comprehensive help information.

**Features:**
- Overview of all available commands
- File upload instructions
- Interactive buttons for command categories
- Cancel option

#### `/about`
Display bot information.

**Output:**
- Bot version
- Author information
- Configured timezone
- Launch time (in configured timezone)
- Current time (in configured timezone)
- Last updated date
- Bot administrator contact

## Data Management

### Database Schema

#### A1_PlayerStats Table
Stores comprehensive player statistics:
- `name`: Player name (TEXT, PRIMARY KEY)
- `baseTrueElo`: Base True Elo rating (REAL)
- `perfElo`: Performance Elo rating (REAL)
- `baseRdTrueElo`: Rating deviation for True Elo (REAL)
- `baseVolTrueElo`: Volatility for True Elo (REAL)
- `baseRdPerfElo`: Rating deviation for Performance Elo (REAL)
- `baseVolPerfElo`: Volatility for Performance Elo (REAL)
- `lastRound`: Last played round (TEXT)
- `hall`: Player's hall (TEXT)
- `capped`: Whether player is capped (INTEGER, 0 or 1)
- `active`: Whether player is active (INTEGER, 0 or 1)
- `dateLogged`: Last update timestamp (TEXT)
- `victories`: JSON array of victory records (TEXT)

#### A2_CappedPlayers Table
Tracks capped players list:
- `name`: Player name (TEXT, PRIMARY KEY)
- `dateAdded`: Timestamp when added (TEXT)

### Validation System

#### Active Player Validation
For players with `active=1`:
- Hall must match between CSV and database
- Mismatch generates error and stops processing
- User must fix data and re-upload

#### Inactive Player Validation
For players with `active=0` and hall mismatch:

**Interactive Resolution Options:**
1. **Keep Old Hall**: Use database hall, link to existing player record
2. **Update Same Player**: Update database hall to CSV hall, same player
3. **Create New Player**: Treat as new player, insert new record

**Resolution Modes:**
- **Individual**: Resolve each mismatch one at a time
- **Bulk**: Apply same resolution to all mismatches
- **Cancel**: Stop processing and review data

### Data Import Best Practices

1. **Always start with playerExport.csv** - This establishes the baseline
2. **Upload cappedlist.csv second** - Updates capping status
3. **Upload round files in sequence** - Ensures chronological order
4. **Review validation messages** - Check for any warnings or errors
5. **Resolve mismatches promptly** - Handle active/inactive player issues
6. **Backup regularly** - Use `/exportdatabase` to create backups
7. **Test with small datasets** - Verify format before bulk uploads

## Image Generation

### Naming Convention

All generated images follow the pattern:
```
{command}_{name}_{date}_{time}.png
```

**Examples:**
- `RankPlayers_260102_004520.png` - Player rankings generated on 2026-01-02 at 00:45:20
- `InfoHall_4_260102_004530.png` - Hall 4 info generated on 2026-01-02 at 00:45:30
- `CompareHalls_4_Binjai_260102_004540.png` - Comparison between Hall 4 and Binjai

### Timezone in Images

All timestamps in generated images use the configured timezone:
- **Filename timestamps**: Based on configured timezone
- **"Generated:" metadata**: Displays full datetime with timezone
- **Consistency**: All timestamps across the application use same timezone

### Image Types

#### 1. Table Images
- **Purpose**: Display rankings and lists
- **Layout**: Rows with alternating colors, headers, metadata
- **Features**: Hall icons, customizable columns, automatic sizing
- **Classes**: `TableImageGenerator`

#### 2. Information Cards
- **Purpose**: Show detailed statistics for single entity
- **Layout**: Sections with headers, key-value pairs, victory records
- **Features**: Hall icons, score displays, formatted data
- **Classes**: `InfoImageGenerator`

#### 3. Comparison Images
- **Purpose**: Side-by-side comparisons
- **Layout**: Split screen with blue (left) and red (right) backgrounds
- **Features**: Tiled backgrounds, divider line, mirrored layouts
- **Classes**: `ComparisonImageGenerator`

## Development

### Building from Source

```bash
# Clone the repository
git clone https://github.com/Calplus/ihrgstats.git
cd ihrgstats

# Build with Maven
mvn clean compile

# Run tests
mvn test

# Package as JAR
mvn package

# Clean build artifacts
mvn clean
```

### Using Maven Daemon (Recommended)

Maven Daemon significantly speeds up build times:

```bash
# Install Maven Daemon
# Download from: https://github.com/apache/maven-mvnd

# Use mvnd instead of mvn
mvnd clean compile
mvnd package
mvnd test
```

### IDE Setup

#### IntelliJ IDEA
1. Open project directory
2. IDEA will automatically detect Maven project
3. Configure JDK 24 in Project Settings
4. Enable auto-import for Maven dependencies

#### Visual Studio Code
1. Install Java Extension Pack
2. Open project folder
3. Configure Java path if needed
4. Use integrated terminal for Maven commands

#### Eclipse
1. Import as "Existing Maven Project"
2. Configure JDK 24 in project properties
3. Enable Maven nature
4. Refresh project dependencies

### Code Structure Guidelines

#### Package Organization
- `com.calplus.ihrgstats` - Main application entry point
- `com.calplus.ihrgstats.calculations` - ELO and statistical calculations
- `com.calplus.ihrgstats.databasemanager` - Database operations and schema
- `com.calplus.ihrgstats.databaseupdater` - CSV processing and data updates
- `com.calplus.ihrgstats.discordbot` - Discord bot integration
- `com.calplus.ihrgstats.scheduler` - Scheduled tasks and automation
- `com.calplus.ihrgstats.telegrambot` - Telegram bot integration
- `com.calplus.ihrgstats.utils` - Utility classes and helpers

#### Naming Conventions
- **Classes**: PascalCase (e.g., `EloCalculator`)
- **Methods**: camelCase (e.g., `calculateTrueElo`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `MAX_SEEDS`)
- **Variables**: camelCase (e.g., `playerName`)

### Adding New Commands

1. Create command class in `telegrambot/commands/` package
2. Implement command handler method
3. Add command to `TelegramListener` switch/case
4. Update `CommandHelp` with command description
5. Test with various inputs
6. Document in README

Example command template:
```java
package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.utils.*;

public class CommandNewFeature {
    private final LogHelper logHelper;
    
    public CommandNewFeature() {
        this.logHelper = new LogHelper();
    }
    
    public CommandResponse handleCommand(String userId, String arguments) {
        logHelper.logInfo(String.format("User %s requested /newfeature", userId));
        
        try {
            // Command logic here
            String response = "Feature result";
            return new CommandResponse(response, (java.nio.file.Path) null);
        } catch (Exception e) {
            logHelper.logError("Error in newfeature: " + e.getMessage());
            return new CommandResponse("❌ Error occurred", (java.nio.file.Path) null);
        }
    }
}
```

### Testing

#### Unit Tests
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=EloCalculationTest

# Run with coverage
mvn test jacoco:report
```

#### Integration Testing
1. Set up test environment with test bot tokens
2. Create test database
3. Run through complete workflow
4. Verify image generation
5. Check log output

### Debugging

#### Enable Debug Logging
Add to `application.properties`:
```properties
logging.level.com.calplus.ihrgstats=DEBUG
```

#### Common Issues

**Issue**: Database locked
**Solution**: Ensure only one instance is running

**Issue**: Bot not responding
**Solution**: Check token configuration in `.env.properties`

**Issue**: Image generation fails
**Solution**: Verify hall icons exist in `src/main/resources/halls/`

**Issue**: Timezone incorrect
**Solution**: Set `settings.timezone` to correct UTC offset

## Project Structure

```
ihrgstats/
├── src/
│   ├── main/
│   │   ├── java/com/calplus/ihrgstats/
│   │   │   ├── Main.java                    # Application entry point
│   │   │   ├── calculations/
│   │   │   │   ├── EloCalculator.java       # Elo rating calculations
│   │   │   │   └── SeedCalculator.java      # Seed value calculations
│   │   │   ├── databasemanager/
│   │   │   │   ├── DatabaseSchema.java      # Database initialization
│   │   │   │   ├── A1_PlayerStats.java      # Player stats management
│   │   │   │   └── A2_CappedPlayers.java    # Capped players management
│   │   │   ├── databaseupdater/
│   │   │   │   └── CSVProcessor.java        # CSV file processing
│   │   │   ├── discordbot/
│   │   │   │   └── logs/
│   │   │   │       └── DiscordLog.java      # Discord logging
│   │   │   ├── scheduler/
│   │   │   │   └── ScheduledTasks.java      # Automated tasks
│   │   │   ├── telegrambot/
│   │   │   │   ├── listener/
│   │   │   │   │   └── TelegramListener.java # Main Telegram handler
│   │   │   │   ├── logs/
│   │   │   │   │   └── TelegramLog.java     # Telegram logging
│   │   │   │   └── commands/                # All command handlers
│   │   │   │       ├── CommandRankPlayers.java
│   │   │   │       ├── CommandRankHalls.java
│   │   │   │       ├── CommandInfoPlayer.java
│   │   │   │       ├── CommandInfoHall.java
│   │   │   │       ├── CommandInfoMatch.java
│   │   │   │       ├── CommandComparePlayers.java
│   │   │   │       ├── CommandCompareHalls.java
│   │   │   │       ├── CommandExportPlayers.java
│   │   │   │       ├── CommandExportDatabase.java
│   │   │   │       ├── CommandSettings.java
│   │   │   │       ├── CommandHelp.java
│   │   │   │       └── CommandAbout.java
│   │   │   └── utils/
│   │   │       ├── ComparisonImageGenerator.java # Comparison images
│   │   │       ├── DatabaseHelper.java           # Database utilities
│   │   │       ├── EnvironmentManager.java       # Environment config
│   │   │       ├── InfoImageGenerator.java       # Info card images
│   │   │       ├── LogHelper.java                # Logging utilities
│   │   │       ├── PropertyManager.java          # Property management
│   │   │       ├── PropertyResolver.java         # Property resolution
│   │   │       ├── TableImageGenerator.java      # Table images
│   │   │       ├── TelegramCommandUtils.java     # Telegram utilities
│   │   │       ├── TelegramFileDownloader.java   # File download
│   │   │       ├── TimezoneHelper.java           # Timezone management
│   │   │       └── VictoryRecordCalculator.java  # Victory calculations
│   │   └── resources/
│   │       ├── application.properties        # Application configuration
│   │       ├── halls/                        # Hall icon images
│   │       │   ├── 1.png
│   │       │   ├── 2.png
│   │       │   ├── ...
│   │       │   └── unknown.png
│   │       └── logback.xml                   # Logging configuration
│   └── test/
│       └── java/com/calplus/ihrgstats/
│           ├── ScoreCalculationTest.java     # Score calculation tests
│           └── test/
│               └── EloCalculationTest.java   # ELO calculation tests
├── database/
│   └── core/
│       └── default.db                        # SQLite database (auto-created)
├── temp/                                     # Temporary files (auto-created)
├── .env.properties                           # Environment variables (create this)
├── pom.xml                                   # Maven configuration
└── README.md                                 # This file
```

## Dependencies

### Core Dependencies

- **SQLite JDBC** (3.47.1.0) - Database operations
- **JDA** (5.2.1) - Discord bot API
- **Telegram Bots API** (6.9.7.1) - Telegram bot API
- **SLF4J** (2.0.16) - Logging facade
- **Logback** (1.5.12) - Logging implementation
- **Gson** (2.11.0) - JSON processing

### Build Tools

- **Maven Compiler Plugin** (3.13.0) - Java compilation
- **Maven Shade Plugin** (3.6.0) - JAR packaging with dependencies
- **Java** (version 24) - Programming language

### Runtime Requirements

- JRE 24 or higher
- Minimum 2GB RAM
- Internet connection for bot API access

## Contributing

### How to Contribute

1. **Fork the Repository**
   ```bash
   git clone https://github.com/Calplus/ihrgstats.git
   cd ihrgstats
   git checkout -b feature/your-feature-name
   ```

2. **Make Changes**
   - Follow existing code style
   - Add tests for new features
   - Update documentation
   - Test thoroughly

3. **Submit Pull Request**
   - Provide clear description
   - Reference any related issues
   - Ensure all tests pass
   - Update README if needed

### Code Review Process

1. Automated checks run on PR
2. Maintainer reviews code
3. Address any feedback
4. Changes merged when approved

### Reporting Issues

When reporting bugs, include:
- Java version
- Operating system
- Steps to reproduce
- Expected vs actual behavior
- Relevant log output
- Configuration (without sensitive data)

### Feature Requests

For new features, describe:
- Use case and motivation
- Proposed implementation
- Potential impact
- Alternative approaches considered

## License

This project is licensed under the MIT License. See LICENSE file for details.

## Author

**Calplus**
- GitHub: [@Calplus](https://github.com/Calplus)
- Repository: https://github.com/Calplus/ihrgstats

## Acknowledgments

- International Hanafuda Rating Group (IHRG) community
- JDA Library maintainers
- Telegram Bot API developers
- Contributors and testers

## Version History

### 1.0.0 (January 2, 2026)
- Initial release
- Full Telegram and Discord bot integration
- ELO rating system (True Elo and Performance Elo)
- Player and hall statistics tracking
- Image generation for rankings and comparisons
- CSV file processing with validation
- Administrative settings interface
- Timezone configuration support
- Database export functionality
- Comprehensive logging system

## Support

For support and questions:
- Open an issue on GitHub
- Check existing documentation
- Review closed issues for similar problems
- Contact project maintainer

---

**Happy Rating! 🃏**
