package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B5_PlayerNames;
import com.calplus.ihrgstats.databasemanager.B6_PlayerYearStatus;
import com.calplus.ihrgstats.databasemanager.B7_CappedImports;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Processes {year}_cappedlist.csv uploads - replaces the legacy
 * A2_CappedPlayers.processCappedList. Writes to the year-scoped
 * capped_imports staging table (full replace per year), and immediately
 * flips capped=true on any player who already has a player_year_status
 * row for this year. Players who haven't appeared in a round yet this
 * year are picked up later by PlayerIdentityResolver when their first
 * player_year_status row for the year is created (mirrors legacy's
 * checkCappedStatus() running on every round upload, not just once at
 * cappedlist-upload time).
 */
public class CappedListProcessor {

    public interface UploadChatMessageCallback {
        void sendMessage(String message);
    }

    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final B7_CappedImports cappedImports = new B7_CappedImports();
    private final A3_Halls halls = new A3_Halls();

    private UploadChatMessageCallback uploadChatCallback;

    public void setUploadChatCallback(UploadChatMessageCallback callback) {
        this.uploadChatCallback = callback;
    }

    private static class CappedEntry {
        String name;
        String prevHall;

        CappedEntry(String name, String prevHall) {
            this.name = name;
            this.prevHall = prevHall;
        }
    }

    /**
     * @param csvFilePath path to the cappedlist.csv file
     * @param year        the year this capped list applies to
     * @return true on success, false on validation/processing failure
     */
    public boolean processCappedList(String csvFilePath, int year, String nowTimestamp) {
        File csvFile = new File(csvFilePath);
        if (!csvFile.exists()) {
            notify("🔴", "cappedlist.csv file not found at: " + csvFilePath);
            return false;
        }

        List<CappedEntry> entries;
        try {
            entries = parseAndValidateCSV(csvFilePath);
        } catch (Exception e) {
            notify("🔴", "CSV validation failed: " + e.getMessage());
            return false;
        }

        try {
            // Full replace semantics for this year, matching legacy's DELETE + re-INSERT.
            cappedImports.clearImportsForYear(year);
            // Also clear any existing capped flags for the year first - otherwise a
            // player removed from a corrected list stays capped forever, since the
            // loop below only ever sets capped=true, never false.
            playerYearStatus.clearCappedForYear(year, nowTimestamp);

            int mappedCount = 0;
            for (CappedEntry entry : entries) {
                int importId = cappedImports.insertImportRow(year, entry.name, entry.prevHall, nowTimestamp);

                List<B5_PlayerNames.NameRecord> candidates = playerNames.findCandidatesByExactName(entry.name);
                A3_Halls.Hall entryHall = halls.getHallByName(entry.prevHall);

                String matchedPlayerId = null;
                B6_PlayerYearStatus.Status matchedStatus = null;
                for (B5_PlayerNames.NameRecord candidate : candidates) {
                    B6_PlayerYearStatus.Status status = playerYearStatus.getStatus(candidate.playerId, year);
                    if (status == null) continue;
                    // When two distinct players share this exact name (a real
                    // case per B5_PlayerNames' own javadoc) and are BOTH
                    // already active this year, prefer whichever one's
                    // current hall matches this entry's stated hall instead
                    // of always capping whichever was most recently active -
                    // that previously capped the wrong player whenever their
                    // halls actually disambiguate them.
                    if (entryHall != null && status.hallId == entryHall.id) {
                        matchedPlayerId = candidate.playerId;
                        matchedStatus = status;
                        break;
                    }
                    if (matchedStatus == null) {
                        matchedPlayerId = candidate.playerId;
                        matchedStatus = status;
                    }
                }
                if (matchedStatus != null) {
                    playerYearStatus.setCapped(matchedPlayerId, year, true, nowTimestamp);
                    cappedImports.markMapped(importId, matchedPlayerId, nowTimestamp);
                    mappedCount++;
                }
            }

            notify("🟢", String.format(
                "cappedlist.csv processed successfully for %d. %d entries loaded, %d immediately matched to already-active players.",
                year, entries.size(), mappedCount));
            return true;

        } catch (SQLException e) {
            notify("🔴", "Database update failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Expected format: name,hall (with header row) - unchanged from legacy.
     */
    private List<CappedEntry> parseAndValidateCSV(String csvFilePath) throws Exception {
        List<CappedEntry> entries = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
            String line;
            int lineNumber = 0;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = CsvLineParser.parseLine(line);

                if (isHeader) {
                    if (parts.length != 2) {
                        throw new Exception("Invalid CSV format: Header must have exactly 2 columns (name,hall)");
                    }
                    String col1 = parts[0].trim().toLowerCase();
                    String col2 = parts[1].trim().toLowerCase();
                    if (!col1.equals("name") || !col2.equals("hall")) {
                        throw new Exception("Invalid CSV format: Header must be 'name,hall' (case insensitive)");
                    }
                    isHeader = false;
                    continue;
                }

                if (parts.length != 2) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Expected 2 columns, found %d", lineNumber, parts.length));
                }

                String name = parts[0].trim();
                String hall = parts[1].trim();

                if (name.isEmpty()) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Player name cannot be empty", lineNumber));
                }
                if (hall.isEmpty()) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Hall cannot be empty", lineNumber));
                }

                entries.add(new CappedEntry(name, hall));
            }

            if (entries.isEmpty()) {
                throw new Exception("CSV file contains no data rows");
            }

        } catch (IOException e) {
            throw new Exception("Error reading CSV file: " + e.getMessage());
        }

        return entries;
    }

    private void notify(String emote, String message) {
        System.out.println(emote + " [CappedListProcessor] " + message);
        if (uploadChatCallback != null) {
            uploadChatCallback.sendMessage(emote + " " + message);
        }
    }
}
