package com.calplus.ihrgstats.utils;

import java.util.*;

/**
 * Utility class for formatting data as monospace tables for Telegram messages
 */
public class TableFormatter {
    
    /**
     * Alignment options for table columns
     */
    public enum Alignment {
        LEFT, CENTER, RIGHT
    }
    
    /**
     * Formats data as a monospace table
     * @param headers Column headers
     * @param rows Data rows (each row is a list of cell values)
     * @param alignments Alignment for each column
     * @param columnWidths Width for each column (in characters)
     * @return Formatted table as string (wrapped in ```monospace``` tags)
     */
    public static String formatTable(String[] headers, List<String[]> rows, 
                                    Alignment[] alignments, int[] columnWidths) {
        StringBuilder sb = new StringBuilder();
        sb.append("```\n");
        
        // Format header row with | separators
        sb.append(formatRow(headers, alignments, columnWidths)).append("\n");

        // Add === separator after header
        sb.append(createHeaderSeparator(columnWidths)).append("\n");

        // Format data rows with | separators
        int rowCount = 0;
        for (String[] row : rows) {
            sb.append(formatRow(row, alignments, columnWidths)).append("\n");
            rowCount++;
            
            // Add --- separator every 10 rows (but not after the last row)
            if (rowCount % 10 == 0 && rowCount < rows.size()) {
                sb.append(createRowSeparator(columnWidths)).append("\n");
            }
        }
        
        sb.append("```");
        return sb.toString();
    }
    
    /**
     * Formats a single row with | separators
     */
    private static String formatRow(String[] cells, Alignment[] alignments, int[] columnWidths) {
        StringBuilder row = new StringBuilder();
        row.append("| ");

        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i];
            Alignment align = alignments[i];
            int width = columnWidths[i];

            // Truncate if too long
            if (cell.length() > width) {
                cell = cell.substring(0, width);
            }

            // Pad according to alignment
            row.append(padString(cell, width, align));

            // Add separator between columns
            if (i < cells.length - 1) {
                row.append(" | ");
            } else {
                row.append(" |");
            }
        }

        return row.toString();
    }
    
    /**
     * Pads a string to the specified width according to alignment
     */
    private static String padString(String str, int width, Alignment alignment) {
        if (str.length() >= width) {
            return str.substring(0, width);
        }
        
        int padding = width - str.length();

        switch (alignment) {
            case LEFT:
                return str + " ".repeat(padding);
            case RIGHT:
                return " ".repeat(padding) + str;
            case CENTER:
                int leftPad = padding / 2;
                return " ".repeat(leftPad) + str + " ".repeat(padding - leftPad);
            default:
                return str;
        }
    }
    
    /**
     * Creates a header separator line (===)
     */
    private static String createHeaderSeparator(int[] columnWidths) {
        return createSeparatorLine(columnWidths, '=');
    }

    /**
     * Creates a row separator line (---)
     */
    private static String createRowSeparator(int[] columnWidths) {
        return createSeparatorLine(columnWidths, '-');
    }

    private static String createSeparatorLine(int[] columnWidths, char fill) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ");
        for (int i = 0; i < columnWidths.length; i++) {
            sb.append(String.valueOf(fill).repeat(columnWidths[i]));
            if (i < columnWidths.length - 1) {
                sb.append(" | ");
            } else {
                sb.append(" |");
            }
        }
        return sb.toString();
    }
    
    /**
     * Appends a trailing "*" marker to each data row whose 0-based index
     * satisfies {@code shouldMark} in a table produced by
     * {@link #formatTable} - used by the ranking commands to flag home-hall
     * rows. Header lines (first 3), "---" separators and the closing fence
     * pass through unchanged; the result is trimmed exactly like the
     * previously-duplicated per-command implementations were.
     */
    public static String markRows(String table, java.util.function.IntPredicate shouldMark) {
        String[] lines = table.split("\n");
        StringBuilder result = new StringBuilder();
        int rowIndex = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i < 3) {
                result.append(line).append("\n");
            } else if (line.contains("----")) {
                result.append(line).append("\n");
            } else if (line.trim().equals("```")) {
                result.append(line);
            } else {
                result.append(line).append(shouldMark.test(rowIndex) ? "*\n" : "\n");
                rowIndex++;
            }
        }
        return result.toString().trim();
    }

    /**
     * Shortens a player name to fit within maxLength characters
     * Shortens the longest part of the name to just its first letter until it fits
     * @param name The full name
     * @param maxLength Maximum length
     * @return Shortened name
     */
    public static String shortenPlayerName(String name, int maxLength) {
        if (name.length() <= maxLength) {
            return name;
        }
        
        String[] parts = name.split("\\s+");
        
        // Keep shortening the longest part until the name fits
        while (getTotalLength(parts) > maxLength && hasLongPart(parts)) {
            int longestIndex = findLongestPartIndex(parts);
            if (parts[longestIndex].length() > 1) {
                parts[longestIndex] = parts[longestIndex].substring(0, 1) + ".";
            }
        }
        
        String result = String.join(" ", parts);
        
        // If still too long, just truncate
        if (result.length() > maxLength) {
            result = result.substring(0, maxLength);
        }
        
        return result;
    }
    
    /**
     * Gets total length of name parts including spaces
     */
    private static int getTotalLength(String[] parts) {
        int total = 0;
        for (int i = 0; i < parts.length; i++) {
            total += parts[i].length();
            if (i < parts.length - 1) {
                total++; // Space between parts
            }
        }
        return total;
    }
    
    /**
     * Checks if there's a part longer than 2 characters (not already shortened)
     */
    private static boolean hasLongPart(String[] parts) {
        for (String part : parts) {
            if (part.length() > 2 && !part.endsWith(".")) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Finds the index of the longest part (that's not already shortened)
     */
    private static int findLongestPartIndex(String[] parts) {
        int maxLength = 0;
        int maxIndex = 0;
        
        for (int i = 0; i < parts.length; i++) {
            // Skip already shortened parts
            if (parts[i].endsWith(".")) {
                continue;
            }
            
            if (parts[i].length() > maxLength) {
                maxLength = parts[i].length();
                maxIndex = i;
            }
        }
        
        return maxIndex;
    }
    
    /**
     * Shortens a hall name to 2 letters
     * @param hall The hall name
     * @return 2-letter abbreviation
     */
    public static String shortenHallName(String hall) {
        if (hall == null || hall.isEmpty()) {
            return "??";
        }
        
        String lowerHall = hall.toLowerCase();
        
        switch (lowerHall) {
            case "banyan":
                return "BY";
            case "binjai":
                return "BJ";
            case "crescent":
                return "CS";
            case "pioneer":
                return "PR";
            case "saraca":
                return "SC";
            case "tamarind":
                return "TM";
            case "tanjong":
                return "TJ";
            default:
                // Take first 2 letters and uppercase
                if (hall.length() >= 2) {
                    return hall.substring(0, 2).toUpperCase();
                } else {
                    return hall.toUpperCase();
                }
        }
    }
    
    /**
     * Shortens a round label for display in narrow table columns. Rounds'
     * default label is "Round {N}" (see A1_Rounds.getOrCreateRound) - this
     * strips that prefix down to "R{N}" so it fits a 3-character column
     * instead of being hard-truncated to garbage by formatRow. Any other
     * (admin-renamed) label is just uppercased and left to formatRow's
     * normal truncation, same as before.
     * @param round The round label (e.g., "Round 12", or a custom name)
     * @return Shortened version (e.g., "R12")
     */
    public static String shortenRoundName(String round) {
        if (round == null || round.isEmpty()) {
            return "";
        }
        if (round.regionMatches(true, 0, "Round ", 0, 6)) {
            String suffix = round.substring(6).trim();
            if (!suffix.isEmpty()) {
                return "R" + suffix.toUpperCase();
            }
        }
        return round.toUpperCase();
    }
}
