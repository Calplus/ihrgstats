package com.calplus.ihrgstats.visualaudit;

import com.calplus.ihrgstats.utils.ComparisonImageGenerator;
import com.calplus.ihrgstats.utils.InfoImageGenerator;
import com.calplus.ihrgstats.utils.TableFormatter;
import com.calplus.ihrgstats.utils.TableImageGenerator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Generator-level cases the commands cannot reach (or only reach with
 * datasets far larger than the harness's): band boundaries at 9/10/11/20/21
 * rows, every hall icon, null metadata / last round, empty cells, unknown
 * outcomes, dual header icons, a table past Telegram's 10000 px limit.
 * Fictional names only.
 */
final class GeneratorBoundaryCases {

    private GeneratorBoundaryCases() {}

    /** The hall-icon resources plus one name with no icon (falls back to unknown.png). */
    static final String[] ALL_HALL_ICONS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
            "Banyan", "Binjai", "Crescent", "Pioneer", "Saraca", "Tamarind", "Tanjong", "Nowhere Fictional"};

    record Case(String name, Callable<Path> render, String outliers) {}

    static List<Case> cases() {
        List<Case> out = new ArrayList<>();
        for (int n : new int[]{9, 10, 11, 20, 21}) {
            out.add(new Case(String.format("g%02d_hall_table_%drows", n, n), () -> hallTable(n, true), "T4/T5/T8 hall table, " + n + " rows (band switch at 10/20)"));
            out.add(new Case(String.format("g%02d_player_table_%drows", 30 + n, n), () -> playerTable(n, meta()), "T8 player table, " + n + " rows"));
            out.add(new Case(String.format("g%02d_info_rows_%d", 60 + n, n), () -> infoRows(n), "I5 alternation carry over " + n + " rows + victory rows"));
            out.add(new Case(String.format("g%02d_comparison_rows_%d", 90 + n, n), () -> comparisonRows(n), "C6 row colours over " + n + " rows"));
        }
        out.add(new Case("g24_hall_table_all_icons", () -> hallTable(ALL_HALL_ICONS.length, true), "H2 every hall icon resource incl. Tanjong 256x288 + unknown.png fallback"));
        out.add(new Case("g25_player_table_300rows_telegram_limit", () -> playerTable(300, meta()), "W+H past Telegram's 10000 px photo limit"));
        out.add(new Case("g26_player_table_null_metadata", () -> playerTable(5, null), "T1 metadata == null (no header block)"));
        out.add(new Case("g27_player_table_no_desc_no_lastround",
                () -> playerTable(5, new TableImageGenerator.ImageMetadata("Player Rankings", null, null)), "T2/T3 description and last round null"));
        out.add(new Case("g28_player_table_empty_cells", GeneratorBoundaryCases::emptyCells, "T10 empty cells and an empty header cell"));
        out.add(new Case("g29_info_no_sections_no_hall", () -> InfoImageGenerator.generateInfoImage(infoMeta(), new ArrayList<>(), null, "InfoMatch", "g29"),
                "I1 hall null + I9 zero sections"));
        out.add(new Case("g30_info_dual_icons_unknown_outcomes", GeneratorBoundaryCases::dualIconsUnknownOutcomes,
                "I2 dual icons; O1 null outcome '?'; O2 outcome 2 (no icon -> emoji fallback); hall rows with a 15-char score (name space <= 20 px)"));
        out.add(new Case("g31_comparison_players_oversize_score", GeneratorBoundaryCases::comparisonPlayersOversize,
                "C5 player victory entries + NA rows + oversize score; C4 centered lines; long entity name"));
        out.add(new Case("g32_info_player_name_space_squeeze", () -> infoSqueeze(false),
                "I7/S-c name space squeezed to <= 20 px and to 21-46 px (ellipsis wider than target) by asymmetric row content; player entries"));
        out.add(new Case("g33_info_hall_name_space_squeeze", () -> infoSqueeze(true), "same, hall entries"));
        out.add(new Case("g34_comparison_player_name_space_squeeze", () -> comparisonSqueeze(false), "C5 player entries, squeezed name space"));
        out.add(new Case("g35_comparison_hall_name_space_squeeze", () -> comparisonSqueeze(true), "C5 hall entries, squeezed name space"));
        return out;
    }

    /** Elo strings of growing length on one side only: the score stays centred, so that side's name space shrinks row by row. */
    private static String longElo(int n) {
        return "9".repeat(n);
    }

    static Path infoSqueeze(boolean hallEntries) throws Exception {
        InfoImageGenerator.Section v = new InfoImageGenerator.Section("Squeezed Names");
        for (int n = 30; n <= 70; n += 4) {
            for (boolean left : new boolean[]{true, false}) {
                InfoImageGenerator.VictoryEntry e = new InfoImageGenerator.VictoryEntry();
                e.round = "R" + n;
                e.hallOutcome = 1;
                e.oppOutcome = -1;
                e.playerElo = left ? longElo(n) : "1500";
                e.opponentElo = left ? "1490" : longElo(n);
                e.score = "10-5";
                if (hallEntries) {
                    e.playerHall = "Squeezed Left Hall";
                    e.opponentHall = "Squeezed Right Hall";
                } else {
                    e.playerHall = "BJ";
                    e.opponentHall = "CS";
                    e.playerName = "Squeezed Left Player";
                    e.opponentName = "Squeezed Right Player";
                }
                v.addVictoryEntry(e);
            }
        }
        List<InfoImageGenerator.Section> sections = new ArrayList<>();
        sections.add(v);
        return InfoImageGenerator.generateInfoImage(infoMeta(), sections, "Binjai", "InfoPlayer", hallEntries ? "g33" : "g32");
    }

    static Path comparisonSqueeze(boolean hallEntries) throws Exception {
        List<ComparisonImageGenerator.Section> left = new ArrayList<>(), right = new ArrayList<>();
        for (List<ComparisonImageGenerator.Section> side : List.of(left, right)) {
            if (hallEntries) {
                List<ComparisonImageGenerator.HallVictoryEntry> v = new ArrayList<>();
                for (int n = 30; n <= 70; n += 4) {
                    v.add(new ComparisonImageGenerator.HallVictoryEntry("R" + n, "", longElo(n), "Squeezed Left Hall", "10-5",
                            "Squeezed Right Hall", "1490", "", 1, -1));
                    v.add(new ComparisonImageGenerator.HallVictoryEntry("R" + n, "", "1500", "Squeezed Left Hall", "10-5",
                            "Squeezed Right Hall", longElo(n), "", 1, -1));
                }
                side.add(ComparisonImageGenerator.Section.forHallVictory("Squeezed Names", v));
            } else {
                List<ComparisonImageGenerator.PlayerVictoryEntry> v = new ArrayList<>();
                for (int n = 30; n <= 70; n += 4) {
                    v.add(new ComparisonImageGenerator.PlayerVictoryEntry("R" + n, "", "BJ", longElo(n), "Squeezed Left Player", "10-5",
                            "Squeezed Right Player", "1490", "CS", "", 1, -1));
                    v.add(new ComparisonImageGenerator.PlayerVictoryEntry("R" + n, "", "BJ", "1500", "Squeezed Left Player", "10-5",
                            "Squeezed Right Player", longElo(n), "CS", "", 1, -1));
                }
                side.add(ComparisonImageGenerator.Section.forPlayerVictory("Squeezed Names", v));
            }
        }
        return ComparisonImageGenerator.generateComparisonImage("Squeeze Comparison",
                new ComparisonImageGenerator.ComparisonData("Left Squeeze", "Binjai", left),
                new ComparisonImageGenerator.ComparisonData("Right Squeeze", "Crescent", right),
                new ComparisonImageGenerator.ImageMetadata("Squeeze Comparison", "Boundary fixture", "Round 21"),
                hallEntries ? "CompareHalls" : "ComparePlayers", hallEntries ? "g35" : "g34", "r");
    }

    static TableImageGenerator.ImageMetadata meta() {
        return new TableImageGenerator.ImageMetadata("Player Rankings", "Boundary fixture\nSecond description line", "Round 21");
    }

    static Path hallTable(int n, boolean icons) throws Exception {
        String[] headers = {"#", "Hall", "Elo", "W-L"};
        List<String[]> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String hall = ALL_HALL_ICONS[i % ALL_HALL_ICONS.length];
            names.add(hall);
            rows.add(new String[]{String.valueOf(i + 1), TableFormatter.shortenHallName(hall), String.format("%.1f", 1600.0 - i * 7.3), (i % 9) + "-" + (9 - i % 9)});
        }
        TableFormatter.Alignment[] al = {TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.LEFT, TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.CENTER};
        return TableImageGenerator.generateHallTable(headers, rows, icons ? names : null, al, new int[]{3, 4, 7, 5},
                new TableImageGenerator.ImageMetadata("Hall Rankings", "Boundary fixture", "Round 21"), Set.of(n / 2), "RankHalls", "g" + n);
    }

    static Path playerTable(int n, TableImageGenerator.ImageMetadata md) throws Exception {
        String[] headers = {"#", "Player", "Hall", "Elo", "W-L", "LR"};
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new String[]{String.valueOf(i + 1), "Boundary Player " + (i + 1), "BJ", String.valueOf(1800 - i), (i % 7) + "-" + (7 - i % 7), "R" + (1 + i % 21)});
        }
        TableFormatter.Alignment[] al = {TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.LEFT, TableFormatter.Alignment.CENTER,
                TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.CENTER, TableFormatter.Alignment.LEFT};
        return TableImageGenerator.generatePlayerTable(headers, rows, al, new int[]{3, 20, 4, 5, 5, 3}, md, Set.of(0), "RankPlayers", "g" + n + (md == null ? "n" : ""));
    }

    static Path emptyCells() throws Exception {
        String[] headers = {"#", "", "Elo"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"1", "Empty Right", ""});
        rows.add(new String[]{"2", "", "1500"});
        rows.add(new String[]{"", "No Rank", "1490"});
        TableFormatter.Alignment[] al = {TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.LEFT, TableFormatter.Alignment.RIGHT};
        return TableImageGenerator.generatePlayerTable(headers, rows, al, new int[]{3, 12, 5}, meta(), Set.of(), "RankPlayers", "g28");
    }

    static InfoImageGenerator.ImageMetadata infoMeta() {
        InfoImageGenerator.ImageMetadata md = new InfoImageGenerator.ImageMetadata();
        md.title = "Boundary Information";
        md.description = "Boundary fixture";
        md.lastRound = "Round 21";
        md.subtitle = "Boundary Subtitle";
        return md;
    }

    static Path infoRows(int n) throws Exception {
        List<InfoImageGenerator.Section> sections = new ArrayList<>();
        InfoImageGenerator.Section kv = new InfoImageGenerator.Section("Key Values");
        for (int i = 0; i < n; i++) kv.addRow("Label " + (i + 1), "Value " + (i + 1));
        sections.add(kv);
        InfoImageGenerator.Section v = new InfoImageGenerator.Section("Victory Record");
        for (int i = 0; i < n; i++) {
            InfoImageGenerator.VictoryEntry e = new InfoImageGenerator.VictoryEntry();
            e.round = "R" + (i + 1);
            e.hallOutcome = i % 2 == 0 ? 1 : -1;
            e.oppOutcome = -e.hallOutcome;
            e.playerHall = "BJ";
            e.playerElo = "1500";
            e.playerName = "Boundary Player";
            e.score = "10-" + (i % 10);
            e.opponentName = "Boundary Opponent";
            e.opponentElo = "1490";
            e.opponentHall = "CS";
            v.addVictoryEntry(e);
        }
        sections.add(v);
        return InfoImageGenerator.generateInfoImage(infoMeta(), sections, "Binjai", "InfoPlayer", "g" + n);
    }

    static Path dualIconsUnknownOutcomes() throws Exception {
        InfoImageGenerator.ImageMetadata md = infoMeta();
        md.secondHallIdentifier = "Tanjong";
        List<InfoImageGenerator.Section> sections = new ArrayList<>();
        InfoImageGenerator.Section v = new InfoImageGenerator.Section("Board Results");
        Integer[] outcomes = {null, 2, 1, 0, -1};
        for (int i = 0; i < outcomes.length; i++) {
            InfoImageGenerator.VictoryEntry e = new InfoImageGenerator.VictoryEntry();
            e.round = String.valueOf(i + 1);
            e.hallOutcome = outcomes[i];
            e.oppOutcome = outcomes[i] == null ? null : -outcomes[i];
            e.playerElo = "1500";
            e.playerHall = "Crescent Hall Boundary";
            e.score = i == 4 ? "123456789.5-0.5" : "10-" + i;   // hall-entry rows (no playerName)
            e.opponentHall = "Tanjong Hall Boundary";
            e.opponentElo = "1490";
            v.addVictoryEntry(e);
        }
        sections.add(v);
        return InfoImageGenerator.generateInfoImage(md, sections, "Crescent", "InfoMatchHall", "g30");
    }

    static Path comparisonRows(int n) throws Exception {
        List<ComparisonImageGenerator.Section> left = new ArrayList<>(), right = new ArrayList<>();
        for (List<ComparisonImageGenerator.Section> side : List.of(left, right)) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) lines.add(String.format("%-6s %-6d %-8s", "Y" + (2000 + i), i + 1, String.format("%.1f", 1500.0 + i)));
            side.add(new ComparisonImageGenerator.Section("Rows", lines));
        }
        return ComparisonImageGenerator.generateComparisonImage("Boundary Comparison",
                new ComparisonImageGenerator.ComparisonData("Left Boundary", "Binjai", left),
                new ComparisonImageGenerator.ComparisonData("Right Boundary", "Tanjong", right),
                new ComparisonImageGenerator.ImageMetadata("Boundary Comparison", "Boundary fixture", "Round 21"), "CompareHalls", "g" + n, "r");
    }

    static Path comparisonPlayersOversize() throws Exception {
        List<ComparisonImageGenerator.Section> left = new ArrayList<>(), right = new ArrayList<>();
        for (List<ComparisonImageGenerator.Section> side : List.of(left, right)) {
            List<ComparisonImageGenerator.PlayerVictoryEntry> v = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                if (i == 3) {
                    v.add(new ComparisonImageGenerator.PlayerVictoryEntry("R" + i, true));
                    continue;
                }
                String score = i == 5 ? "123456789.5-0.5" : (10 + i) + "-" + i;
                v.add(new ComparisonImageGenerator.PlayerVictoryEntry("R" + i, "", "BJ", "1500", "Boundary Player Name",
                        score, "Boundary Opponent Name", "1490", "CS", "", 1, -1));
            }
            side.add(ComparisonImageGenerator.Section.forPlayerVictory("Victory Record", v));
            side.add(new ComparisonImageGenerator.Section("Centered", List.of("Win probability 55.0%", "-NA-", ""), true, false));
        }
        return ComparisonImageGenerator.generateComparisonImage("Player Comparison",
                new ComparisonImageGenerator.ComparisonData("A Very Long Fictional Entity Name For The Left Side", "Binjai", left),
                new ComparisonImageGenerator.ComparisonData("Right Boundary", "7", right),
                new ComparisonImageGenerator.ImageMetadata("Player Comparison", "Boundary fixture", "Round 21"), "ComparePlayers", "g31", "r");
    }
}
