package com.calplus.ihrgstats.utils;

import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.TimeZone;

/**
 * Utility class for timezone-aware date and time operations.
 * Reads the timezone setting from application.properties and provides consistent
 * timezone conversion methods throughout the application.
 */
public class TimezoneHelper {
    private static final String DEFAULT_TIMEZONE = "Asia/Singapore"; // UTC+8
    private static final double DEFAULT_OFFSET = 8.0;
    
    /**
     * Gets the configured timezone from settings.timezone property.
     * Returns a ZoneId based on the UTC offset stored in the property.
     * Falls back to Asia/Singapore (UTC+8) if not configured.
     * 
     * @return ZoneId for the configured timezone
     */
    public static ZoneId getConfiguredZoneId() {
        try {
            String timezoneProperty = PropertyResolver.getProperty("settings.timezone", "");
            
            if (timezoneProperty == null || timezoneProperty.trim().isEmpty()) {
                return ZoneId.of(DEFAULT_TIMEZONE);
            }
            
            double offset = Double.parseDouble(timezoneProperty.trim());
            return getZoneIdFromOffset(offset);
            
        } catch (NumberFormatException e) {
            System.err.println("Invalid timezone offset in settings.timezone, using default: " + DEFAULT_TIMEZONE);
            return ZoneId.of(DEFAULT_TIMEZONE);
        } catch (Exception e) {
            System.err.println("Error reading timezone setting, using default: " + e.getMessage());
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }
    
    /**
     * Converts a UTC offset (e.g., +8, -5, +9.5) to a ZoneId.
     * 
     * @param offset The UTC offset as a double
     * @return ZoneId for the offset
     */
    public static ZoneId getZoneIdFromOffset(double offset) {
        int hours = (int) offset;
        int minutes = (int) Math.abs((offset - hours) * 60);
        
        if (offset >= 0) {
            return ZoneId.ofOffset("UTC", ZoneOffset.ofHoursMinutes(hours, minutes));
        } else {
            return ZoneId.ofOffset("UTC", ZoneOffset.ofHoursMinutes(hours, -minutes));
        }
    }
    
    /**
     * Gets the current ZonedDateTime in the configured timezone.
     * 
     * @return ZonedDateTime in the configured timezone
     */
    public static ZonedDateTime now() {
        return ZonedDateTime.now(getConfiguredZoneId());
    }
    
    /**
     * Converts a ZonedDateTime to the configured timezone.
     * 
     * @param dateTime The ZonedDateTime to convert
     * @return ZonedDateTime in the configured timezone
     */
    public static ZonedDateTime toConfiguredZone(ZonedDateTime dateTime) {
        return dateTime.withZoneSameInstant(getConfiguredZoneId());
    }
    
    /**
     * Creates a SimpleDateFormat with the configured timezone.
     * 
     * @param pattern The date/time pattern
     * @return SimpleDateFormat configured with the timezone
     */
    public static SimpleDateFormat createSimpleDateFormat(String pattern) {
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        sdf.setTimeZone(TimeZone.getTimeZone(getConfiguredZoneId()));
        return sdf;
    }
    
    /**
     * Creates a DateTimeFormatter with the configured timezone.
     * 
     * @param pattern The date/time pattern
     * @return DateTimeFormatter configured with the timezone
     */
    public static DateTimeFormatter createDateTimeFormatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withZone(getConfiguredZoneId());
    }
    
    /**
     * Formats a Date object using the configured timezone.
     * 
     * @param date The Date to format
     * @param pattern The date/time pattern
     * @return Formatted date string in the configured timezone
     */
    public static String formatDate(Date date, String pattern) {
        SimpleDateFormat sdf = createSimpleDateFormat(pattern);
        return sdf.format(date);
    }
    
    /**
     * Formats the current time using the configured timezone.
     * 
     * @param pattern The date/time pattern
     * @return Formatted date string for current time in the configured timezone
     */
    public static String formatNow(String pattern) {
        return formatDate(new Date(), pattern);
    }
    
    /**
     * Gets a formatted timezone display string (e.g., "UTC+8", "UTC-5").
     * 
     * @return Formatted timezone string
     */
    public static String getFormattedTimezone() {
        try {
            String timezoneProperty = PropertyResolver.getProperty("settings.timezone", "");
            
            if (timezoneProperty == null || timezoneProperty.trim().isEmpty()) {
                return "UTC+8"; // Default
            }
            
            double offset = Double.parseDouble(timezoneProperty.trim());
            if (offset == 0) {
                return "UTC";
            } else if (offset > 0) {
                return String.format("UTC+%.1f", offset).replace(".0", "");
            } else {
                return String.format("UTC%.1f", offset).replace(".0", "");
            }
        } catch (Exception e) {
            return "UTC+8"; // Default
        }
    }
    
    /**
     * Gets the timezone offset in hours from the configuration.
     * 
     * @return Timezone offset as a double
     */
    public static double getTimezoneOffset() {
        try {
            String timezoneProperty = PropertyResolver.getProperty("settings.timezone", "");
            
            if (timezoneProperty == null || timezoneProperty.trim().isEmpty()) {
                return DEFAULT_OFFSET;
            }
            
            return Double.parseDouble(timezoneProperty.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_OFFSET;
        }
    }
}
