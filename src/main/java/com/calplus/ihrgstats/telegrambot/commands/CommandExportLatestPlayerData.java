package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Command handler for /exportplayers command.
 * Exports latest player data (ELO ratings, hall, capped status) to a CSV file.
 */
public class CommandExportLatestPlayerData {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final String dbPath;

    // Round sequence
    private static final List<String> ROUND_SEQUENCE = Arrays.asList("1", "2", "3", "4", "5", "6", "t16", "t8", "t4", "t2");

    public CommandExportLatestPlayerData() {
        // Load environment variables
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.dbPath = Paths.get(System.getProperty("user.dir"), "database", "core", "default.db").toString();
    }

    /**
     * Represents a player's export data
     */
    private static class PlayerExportData {
        String name;
        Integer trueElo;
        Integer perfElo;
        Double rdTrueElo;
        Double volTrueElo;
        Double rdPerfElo;
        Double volPerfElo;
        String lastRound;
        String lastHall;
        boolean capped;

        PlayerExportData(String name, Integer trueElo, Integer perfElo, 
                        Double rdTrueElo, Double volTrueElo, 
                        Double rdPerfElo, Double volPerfElo,
                        String lastRound, String lastHall, boolean capped) {
            this.name = name;
            this.trueElo = trueElo;
            this.perfElo = perfElo;
            this.rdTrueElo = rdTrueElo;
            this.volTrueElo = volTrueElo;
            this.rdPerfElo = rdPerfElo;
            this.volPerfElo = volPerfElo;
            this.lastRound = lastRound;
            this.lastHall = lastHall;
            this.capped = capped;
        }
    }

    /**
     * Exports latest player data to CSV file
     * @return Path to the exported CSV file, or null if export failed
     */
    public Path exportLatestPlayerData() {
        discordLog.logInfo("Starting player data export...");
        telegramLog.logInfo("Starting player data export...");

        List<PlayerExportData> exportData = new ArrayList<>();

        try {
            String jdbcUrl = "jdbc:sqlite:" + dbPath;
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                String sql = "SELECT * FROM A1_PlayerStats";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {

                    while (rs.next()) {
                        String name = rs.getString("name");
                        String hall = rs.getString("hall");
                        boolean capped = rs.getInt("capped") == 1;
                        
                        // Find latest round where player actually played (has opponent data)
                        Integer latestTrueElo = null;
                        Integer latestPerfElo = null;
                        Double latestRdTrueElo = null;
                        Double latestVolTrueElo = null;
                        Double latestRdPerfElo = null;
                        Double latestVolPerfElo = null;
                        String lastRound = null;

                        // Check rounds in reverse order to find the latest round where player actually played
                        for (int i = ROUND_SEQUENCE.size() - 1; i >= 0; i--) {
                            String round = ROUND_SEQUENCE.get(i);
                            String trueEloCol = getRoundColumnName("trueElo", round);
                            String perfEloCol = getRoundColumnName("perfElo", round);
                            String rdTrueEloCol = getRoundColumnName("rdTrueElo", round);
                            String volTrueEloCol = getRoundColumnName("volTrueElo", round);
                            String rdPerfEloCol = getRoundColumnName("rdPerfElo", round);
                            String volPerfEloCol = getRoundColumnName("volPerfElo", round);
                            String oppNameCol = getRoundColumnName("oppName", round);

                            // Check if player actually played this round (has opponent name)
                            String oppName = rs.getString(oppNameCol);
                            Integer trueElo = (Integer) rs.getObject(trueEloCol);
                            
                            // Player actually played if they have an opponent name (even if it's "WALKOVER")
                            // or if they have trueElo but no opponent columns exist yet (backwards compatibility)
                            if (oppName != null && !oppName.trim().isEmpty() && trueElo != null) {
                                latestTrueElo = trueElo;
                                latestPerfElo = (Integer) rs.getObject(perfEloCol);
                                latestRdTrueElo = (Double) rs.getObject(rdTrueEloCol);
                                latestVolTrueElo = (Double) rs.getObject(volTrueEloCol);
                                latestRdPerfElo = (Double) rs.getObject(rdPerfEloCol);
                                latestVolPerfElo = (Double) rs.getObject(volPerfEloCol);
                                lastRound = round;
                                break;
                            }
                        }

                        // If no round ELO found, use base ELO
                        if (latestTrueElo == null) {
                            latestTrueElo = (Integer) rs.getObject("baseTrueElo");
                            latestPerfElo = (Integer) rs.getObject("basePerfElo");
                            latestRdTrueElo = (Double) rs.getObject("baseRdTrueElo");
                            latestVolTrueElo = (Double) rs.getObject("baseVolTrueElo");
                            latestRdPerfElo = (Double) rs.getObject("baseRdPerfElo");
                            latestVolPerfElo = (Double) rs.getObject("baseVolPerfElo");
                            lastRound = "base";
                        }

                        // Only export if we have at least trueElo
                        if (latestTrueElo != null) {
                            exportData.add(new PlayerExportData(name, latestTrueElo, latestPerfElo,
                                latestRdTrueElo, latestVolTrueElo, latestRdPerfElo, latestVolPerfElo,
                                lastRound, hall, capped));
                        }
                    }
                }
            }

            if (exportData.isEmpty()) {
                String errorMsg = "No player data found to export.";
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                return null;
            }

            // Create CSV file
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String filename = String.format("playerExport_%s.csv", timestamp);
            Path tempDir = Paths.get(System.getProperty("user.dir"), "temp");
            Files.createDirectories(tempDir);
            Path csvPath = tempDir.resolve(filename);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvPath.toFile()))) {
                // Write header
                writer.write("name,trueElo,perfElo,rdTrueElo,volTrueElo,rdPerfElo,volPerfElo,lastRound,lastHall,capped\n");

                // Write data
                for (PlayerExportData data : exportData) {
                    writer.write(String.format("%s,%d,%s,%s,%s,%s,%s,%s,%s,%s\n",
                        escapeCsvField(data.name),
                        data.trueElo,
                        data.perfElo != null ? data.perfElo.toString() : "",
                        data.rdTrueElo != null ? String.format("%.4f", data.rdTrueElo) : "",
                        data.volTrueElo != null ? String.format("%.6f", data.volTrueElo) : "",
                        data.rdPerfElo != null ? String.format("%.4f", data.rdPerfElo) : "",
                        data.volPerfElo != null ? String.format("%.6f", data.volPerfElo) : "",
                        data.lastRound,
                        escapeCsvField(data.lastHall),
                        data.capped ? "true" : "false"));
                }
            }

            String successMsg = String.format("Player data export completed: %d players exported to %s", 
                exportData.size(), filename);
            discordLog.logSuccess(successMsg);
            telegramLog.logSuccess(successMsg);

            return csvPath;

        } catch (Exception e) {
            String errorMsg = "Player data export failed: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Escapes a CSV field (wraps in quotes if contains comma, quotes, or newline)
     */
    private String escapeCsvField(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    /**
     * Gets the database column name for a round
     */
    private String getRoundColumnName(String prefix, String round) {
        if (round.startsWith("t")) {
            return prefix + "T" + round.substring(1);
        }
        return prefix + "R" + round;
    }

    /**
     * Main method for testing
     */
    public static void main(String[] args) {
        CommandExportLatestPlayerData exporter = new CommandExportLatestPlayerData();
        Path csvPath = exporter.exportLatestPlayerData();
        
        if (csvPath != null) {
            System.out.println("Export successful: " + csvPath);
        } else {
            System.err.println("Export failed");
        }
    }
}
