package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B6_PlayerYearStatus;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builders for the inline-keyboard shapes shared by the selection wizards
 * (hall picker, player picker, round picker) - previously each wizard
 * command carried its own copy of these loops. Button labels, ordering and
 * the trailing Cancel entry are part of the commands' user-visible contract;
 * callback prefixes stay per-command.
 */
public final class SelectionKeyboards {

    private SelectionKeyboards() {}

    /** Resolves a display name for a player id; may hit the database. */
    public interface PlayerNameResolver {
        String nameFor(String playerId) throws SQLException;
    }

    /**
     * One button per hall in list order - the internal unknown-hall
     * placeholder is never selectable - plus a trailing Cancel.
     */
    public static ButtonConfig hallButtons(List<A3_Halls.Hall> halls, String selectPrefix, String cancelCallback) {
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        for (A3_Halls.Hall hall : halls) {
            if (hall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) continue;
            labels.add(hall.hallName);
            callbacks.add(selectPrefix + hall.id);
        }
        labels.add("❌ Cancel");
        callbacks.add(cancelCallback);
        return new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]));
    }

    /**
     * One button per roster entry in list order plus a trailing Cancel,
     * laid out one per row (player names are wide).
     */
    public static ButtonConfig playerButtons(List<B6_PlayerYearStatus.Status> statuses, PlayerNameResolver nameResolver,
                                             String selectPrefix, String cancelCallback) throws SQLException {
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        for (B6_PlayerYearStatus.Status status : statuses) {
            labels.add(nameResolver.nameFor(status.playerId));
            callbacks.add(selectPrefix + status.playerId);
        }
        labels.add("❌ Cancel");
        callbacks.add(cancelCallback);
        return new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]), 1);
    }

    /**
     * Current-year round picker: "All Rounds", "🌐 All Years", one button
     * per round ({@code selectPrefix} + round_order), trailing Cancel.
     */
    public static ButtonConfig roundButtons(List<A1_Rounds.Round> rounds, String selectPrefix, String cancelCallback) {
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        labels.add("All Rounds");
        callbacks.add(selectPrefix + "all");
        labels.add("🌐 All Years");
        callbacks.add(selectPrefix + "allyears");
        for (A1_Rounds.Round round : rounds) {
            labels.add(round.roundLabel);
            callbacks.add(selectPrefix + round.roundOrder);
        }
        labels.add("❌ Cancel");
        callbacks.add(cancelCallback);
        return new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]));
    }

    /**
     * Whole-history round picker: round numbers repeat across years, so
     * each button is "{year} · {label}" with callback
     * {@code selectPrefix + year + "_" + round_order}. An optional head
     * entry (e.g. "Latest Round") goes first; pass nulls for none.
     */
    public static ButtonConfig yearRoundButtons(List<A1_Rounds.Round> rounds, String selectPrefix, String cancelCallback,
                                                String headLabel, String headCallback) {
        List<String> labels = new ArrayList<>();
        List<String> callbacks = new ArrayList<>();
        if (headLabel != null) {
            labels.add(headLabel);
            callbacks.add(headCallback);
        }
        for (A1_Rounds.Round round : rounds) {
            labels.add(round.year + " · " + round.roundLabel);
            callbacks.add(selectPrefix + round.year + "_" + round.roundOrder);
        }
        labels.add("❌ Cancel");
        callbacks.add(cancelCallback);
        return new ButtonConfig(labels.toArray(new String[0]), callbacks.toArray(new String[0]));
    }
}
