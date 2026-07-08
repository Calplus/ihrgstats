package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.*;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command handler for /exportplayers command.
 * Exports player data (identity, rating, and status history) to a
 * multi-sheet Excel (.xlsx) workbook.
 */
public class CommandExportPlayers {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final B4_Players players = new B4_Players();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final D11_PlayerRatings playerRatings = new D11_PlayerRatings();

    public CommandExportPlayers() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();

        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
    }

    /**
     * Exports player data to a multi-sheet .xlsx workbook.
     * @return Path to the exported .xlsx file, or null if export failed
     */
    public Path exportLatestPlayerData() {
        discordLog.logInfo("Starting player data export...");
        telegramLog.logInfo("Starting player data export...");

        try {
            Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
            if (trueEloTypeId == null) {
                throw new IllegalStateException("TrueElo rating type not found - has the database been seeded?");
            }

            List<String> playerIds = players.getAllPlayerIds();
            if (playerIds.isEmpty()) {
                String errorMsg = "No player data found to export.";
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                return null;
            }

            Map<Integer, A3_Halls.Hall> hallsById = new HashMap<>();
            for (A3_Halls.Hall hall : halls.getAllHalls()) hallsById.put(hall.id, hall);

            Map<Integer, A1_Rounds.Round> roundsById = new HashMap<>();
            for (int year : rounds.getAllYears()) {
                for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
                    roundsById.put(round.id, round);
                }
            }

            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                buildPlayersSheet(workbook, playerIds, hallsById, trueEloTypeId);
                buildNameHistorySheet(workbook);
                buildRatingHistorySheet(workbook, playerIds, roundsById, trueEloTypeId);
                buildYearStatusHistorySheet(workbook, playerIds, hallsById);

                String timestamp = TimezoneHelper.formatNow("yyyyMMdd_HHmmss");
                String filename = String.format("playerExport_%s.xlsx", timestamp);
                Path tempDir = Paths.get(System.getProperty("user.dir"), "temp");
                Files.createDirectories(tempDir);
                Path xlsxPath = tempDir.resolve(filename);

                try (FileOutputStream fos = new FileOutputStream(xlsxPath.toFile())) {
                    workbook.write(fos);
                }

                String successMsg = String.format("Player data export completed: %d players exported to %s", playerIds.size(), filename);
                discordLog.logSuccess(successMsg);
                telegramLog.logSuccess(successMsg);
                return xlsxPath;
            }
        } catch (Exception e) {
            String errorMsg = "Player data export failed: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            e.printStackTrace();
            return null;
        }
    }

    private void buildPlayersSheet(XSSFWorkbook workbook, List<String> playerIds, Map<Integer, A3_Halls.Hall> hallsById, int trueEloTypeId) throws Exception {
        Sheet sheet = workbook.createSheet("Players");
        writeHeader(sheet, "Player ID", "Name", "Hall", "Capped", "Latest TrueElo");

        int rowNum = 1;
        for (String playerId : playerIds) {
            List<B6_PlayerYearStatus.Status> statuses = playerYearStatus.getStatusesForPlayer(playerId);
            B6_PlayerYearStatus.Status latestStatus = statuses.isEmpty() ? null : statuses.get(statuses.size() - 1);

            String name = latestStatus != null ? playerNames.getNameForYear(playerId, latestStatus.year) : null;
            if (name == null) {
                List<B5_PlayerNames.NameRecord> names = playerNames.getNamesForPlayer(playerId);
                name = names.isEmpty() ? playerId : names.get(names.size() - 1).name;
            }

            String hallName = null;
            boolean capped = false;
            if (latestStatus != null) {
                A3_Halls.Hall hall = hallsById.get(latestStatus.hallId);
                hallName = hall != null ? hall.hallName : null;
                capped = latestStatus.capped;
            }

            List<D11_PlayerRatings.Rating> ratingHistory = playerRatings.getRatingHistoryForPlayer(playerId, trueEloTypeId);
            Integer latestElo = ratingHistory.isEmpty() ? null : (int) Math.round(ratingHistory.get(ratingHistory.size() - 1).ratingValue);

            Row row = sheet.createRow(rowNum++);
            setCell(row, 0, playerId);
            setCell(row, 1, name);
            setCell(row, 2, hallName);
            setCell(row, 3, capped ? "true" : "false");
            if (latestElo != null) setCell(row, 4, latestElo); else setCell(row, 4, "");
        }
        autoSizeColumns(sheet, 5);
    }

    private void buildNameHistorySheet(XSSFWorkbook workbook) throws Exception {
        Sheet sheet = workbook.createSheet("Name History");
        writeHeader(sheet, "Player ID", "Name", "First Seen Year", "Last Seen Year");

        int rowNum = 1;
        for (B5_PlayerNames.NameRecord record : playerNames.getAllNames()) {
            Row row = sheet.createRow(rowNum++);
            setCell(row, 0, record.playerId);
            setCell(row, 1, record.name);
            setCell(row, 2, record.firstSeenYear);
            setCell(row, 3, record.lastSeenYear);
        }
        autoSizeColumns(sheet, 4);
    }

    private void buildRatingHistorySheet(XSSFWorkbook workbook, List<String> playerIds, Map<Integer, A1_Rounds.Round> roundsById, int trueEloTypeId) throws Exception {
        Sheet sheet = workbook.createSheet("Rating History");
        writeHeader(sheet, "Player ID", "Year", "Round", "TrueElo", "RD", "Volatility");

        int rowNum = 1;
        for (String playerId : playerIds) {
            for (D11_PlayerRatings.Rating rating : playerRatings.getRatingHistoryForPlayer(playerId, trueEloTypeId)) {
                A1_Rounds.Round round = roundsById.get(rating.roundId);
                Row row = sheet.createRow(rowNum++);
                setCell(row, 0, playerId);
                setCell(row, 1, round != null ? String.valueOf(round.year) : "");
                setCell(row, 2, round != null ? round.roundLabel : "");
                setCell(row, 3, (int) Math.round(rating.ratingValue));
                setCell(row, 4, String.format("%.4f", rating.ratingDeviation));
                setCell(row, 5, String.format("%.6f", rating.volatility));
            }
        }
        autoSizeColumns(sheet, 6);
    }

    private void buildYearStatusHistorySheet(XSSFWorkbook workbook, List<String> playerIds, Map<Integer, A3_Halls.Hall> hallsById) throws Exception {
        Sheet sheet = workbook.createSheet("Year Status History");
        writeHeader(sheet, "Player ID", "Year", "Hall", "Capped", "Active");

        int rowNum = 1;
        for (String playerId : playerIds) {
            for (B6_PlayerYearStatus.Status status : playerYearStatus.getStatusesForPlayer(playerId)) {
                A3_Halls.Hall hall = hallsById.get(status.hallId);
                Row row = sheet.createRow(rowNum++);
                setCell(row, 0, playerId);
                setCell(row, 1, status.year);
                setCell(row, 2, hall != null ? hall.hallName : "");
                setCell(row, 3, status.capped ? "true" : "false");
                setCell(row, 4, status.active ? "true" : "false");
            }
        }
        autoSizeColumns(sheet, 5);
    }

    private void writeHeader(Sheet sheet, String... columns) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < columns.length; i++) {
            setCell(header, i, columns[i]);
        }
    }

    private void setCell(Row row, int col, String value) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
    }

    private void setCell(Row row, int col, int value) {
        row.createCell(col).setCellValue(value);
    }

    private void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
