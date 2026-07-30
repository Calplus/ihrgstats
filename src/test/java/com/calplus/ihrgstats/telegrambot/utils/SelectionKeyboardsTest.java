package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B6_PlayerYearStatus;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins down the shared wizard-keyboard builders extracted from the eleven
 * selection wizards: button order, the unknown-hall exclusion, the trailing
 * Cancel entry, and the exact callback formats the listener's routing
 * prefixes depend on.
 */
public class SelectionKeyboardsTest {

    private static A3_Halls.Hall hall(int id, String name) {
        return new A3_Halls.Hall(id, "H" + id, name, 1);
    }

    private static A1_Rounds.Round round(int order, String label, int year) {
        return new A1_Rounds.Round(order, year, order, label, null, "now", "now");
    }

    @Test
    void hallButtons_excludeUnknownHall_andEndWithCancel() {
        A3_Halls.Hall unknown = new A3_Halls.Hall(99, A3_Halls.UNKNOWN_HALL_CODE, "Unknown", 1);
        ButtonConfig config = SelectionKeyboards.hallButtons(
                List.of(hall(1, "1"), unknown, hall(2, "Binjai")), "infohall_hall_", "infohall_cancel");

        assertArrayEquals(new String[]{"1", "Binjai", "❌ Cancel"}, config.labels);
        assertArrayEquals(new String[]{"infohall_hall_1", "infohall_hall_2", "infohall_cancel"}, config.callbacks);
    }

    @Test
    void playerButtons_resolveNames_layOutOnePerRow_andEndWithCancel() throws Exception {
        List<B6_PlayerYearStatus.Status> roster = List.of(
                new B6_PlayerYearStatus.Status("p1", 2026, 1, false, true),
                new B6_PlayerYearStatus.Status("p2", 2026, 1, true, true));

        ButtonConfig config = SelectionKeyboards.playerButtons(roster,
                pid -> pid.equals("p1") ? "Aurelia Nightshade" : pid,
                "predict_selectplayer1_", "predict_cancel");

        assertArrayEquals(new String[]{"Aurelia Nightshade", "p2", "❌ Cancel"}, config.labels);
        assertArrayEquals(new String[]{"predict_selectplayer1_p1", "predict_selectplayer1_p2", "predict_cancel"}, config.callbacks);
        assertEquals(1, config.columnsPerRow, "player names are wide - one button per row");
    }

    @Test
    void roundButtons_offerAllRoundsThenAllYearsThenEachRoundThenCancel() {
        ButtonConfig config = SelectionKeyboards.roundButtons(
                List.of(round(1, "Round 1", 2026), round(2, "Round 2", 2026)),
                "rankplayers_round_", "rankplayers_cancel");

        assertArrayEquals(new String[]{"All Rounds", "🌐 All Years", "Round 1", "Round 2", "❌ Cancel"}, config.labels);
        assertArrayEquals(new String[]{"rankplayers_round_all", "rankplayers_round_allyears",
                "rankplayers_round_1", "rankplayers_round_2", "rankplayers_cancel"}, config.callbacks);
    }

    @Test
    void yearRoundButtons_disambiguateByYear_withOptionalHeadEntry() {
        List<A1_Rounds.Round> rounds = List.of(round(1, "Round 1", 2025), round(1, "Round 1", 2026));

        ButtonConfig plain = SelectionKeyboards.yearRoundButtons(rounds, "infomatchhall_round_", "infomatchhall_cancel", null, null);
        assertArrayEquals(new String[]{"2025 · Round 1", "2026 · Round 1", "❌ Cancel"}, plain.labels);
        assertArrayEquals(new String[]{"infomatchhall_round_2025_1", "infomatchhall_round_2026_1", "infomatchhall_cancel"}, plain.callbacks);

        ButtonConfig withHead = SelectionKeyboards.yearRoundButtons(rounds, "infomatch_round_", "infomatch_cancel",
                "⏱️ Latest Round (2026 · Round 1)", "infomatch_round_latest");
        assertEquals("⏱️ Latest Round (2026 · Round 1)", withHead.labels[0], "head entry must come first");
        assertEquals("infomatch_round_latest", withHead.callbacks[0]);
        assertEquals(4, withHead.labels.length);
    }
}
