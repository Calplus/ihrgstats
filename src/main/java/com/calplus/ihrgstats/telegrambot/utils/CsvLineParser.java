package com.calplus.ihrgstats.telegrambot.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared, quote-aware CSV line parser - extracted from RoundCsvProcessor so
 * fixing this logic once fixes it everywhere. Previously CappedListProcessor
 * had its own naive {@code line.split(",", -1)}, which breaks on a quoted,
 * comma-containing name (e.g. "Nightingale, Florence", present in real AY24
 * data) - the exact class of bug this class exists to prevent recurring.
 */
public final class CsvLineParser {

    private CsvLineParser() {}

    /** RFC 4180-ish CSV line parser (handles quoted fields with embedded commas). */
    public static String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
