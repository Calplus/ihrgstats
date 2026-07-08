package com.calplus.ihrgstats.utils;

/**
 * Small shared helpers for the "current year" context used pervasively by
 * commands and CSV ingestion. Replaces the legacy Constants.ROUND_SEQUENCE-
 * based round enumeration - rounds are now real rows queried directly via
 * A1_Rounds for whichever year is active.
 */
public final class YearContext {

    private YearContext() {
        throw new UnsupportedOperationException("YearContext cannot be instantiated");
    }

    /**
     * Reads the admin-configured "current year" setting. Returns null if
     * unset or invalid - callers must NOT silently default to the calendar
     * year, since a wrong year would silently corrupt historical stats.
     */
    public static Integer getCurrentYear() {
        String yearStr = PropertyResolver.getProperty("settings.currentYear", "").trim();
        if (yearStr.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(yearStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
