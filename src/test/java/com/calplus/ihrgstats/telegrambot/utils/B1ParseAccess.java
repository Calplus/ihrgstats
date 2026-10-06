package com.calplus.ihrgstats.telegrambot.utils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lane b1: test-only access to the two upload parsers exactly as production
 * runs them (no copy of their logic). Round files go through the
 * package-private {@code RoundCsvProcessor.parseAndValidateCSV}; capped lists
 * through the private {@code CappedListProcessor.parseAndValidateCSV} by
 * reflection. Both return the parsed cells, or throw the exception whose
 * message production shows after "CSV validation failed: ".
 */
public final class B1ParseAccess {

    private B1ParseAccess() {}

    public static List<String[]> parseRound(Path csv) throws Exception {
        List<String[]> out = new ArrayList<>();
        for (RoundCsvProcessor.GameRow g : new RoundCsvProcessor().parseAndValidateCSV(csv.toString())) {
            out.add(new String[]{g.name1, g.hall1, g.score1, g.name2, g.hall2, g.score2});
        }
        return out;
    }

    public static List<String[]> parseCapped(Path csv) throws Exception {
        Method m = CappedListProcessor.class.getDeclaredMethod("parseAndValidateCSV", String.class);
        m.setAccessible(true);
        List<?> entries;
        try {
            entries = (List<?>) m.invoke(new CappedListProcessor(), csv.toString());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
        List<String[]> out = new ArrayList<>();
        for (Object entry : entries) {
            Field name = entry.getClass().getDeclaredField("name");
            Field hall = entry.getClass().getDeclaredField("prevHall");
            name.setAccessible(true);
            hall.setAccessible(true);
            out.add(new String[]{(String) name.get(entry), (String) hall.get(entry)});
        }
        return out;
    }

    /** Parses either kind of upload by its corpus file name. */
    public static List<String[]> parse(Path csv, boolean isRound) throws Exception {
        return isRound ? parseRound(csv) : parseCapped(csv);
    }
}
