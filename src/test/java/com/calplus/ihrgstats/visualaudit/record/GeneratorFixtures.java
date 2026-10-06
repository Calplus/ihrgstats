package com.calplus.ihrgstats.visualaudit.record;

import com.calplus.ihrgstats.utils.ComparisonImageGenerator;
import com.calplus.ihrgstats.utils.InfoImageGenerator;
import com.calplus.ihrgstats.utils.TableFormatter;
import com.calplus.ihrgstats.utils.TableImageGenerator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Direct generator inputs (no database) shaped like what the commands pass,
 * all names fictional. Used by the pixel-identity proof, the detector
 * calibration and the band-boundary renders.
 */
public final class GeneratorFixtures {

    private GeneratorFixtures() {}

    static final String[] HALLS = {"tanjong", "binjai", "crescent", "1", "Mysteryville", "banyan", "saraca", "pioneer",
            "tamarind", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16"};

    /** A /rankhalls-shaped hall table with {@code n} rows (icons from the real resources, Tanjong first). */
    public static Path hallTable(int n, String entity) throws Exception {
        String[] headers = {"#", "Hall", "Elo", "W-L", "ExpElo"};
        List<String[]> rows = new ArrayList<>();
        List<String> hallNames = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String hall = HALLS[i % HALLS.length];
            hallNames.add(hall);
            rows.add(new String[]{String.valueOf(i + 1), TableFormatter.shortenHallName(hall), String.valueOf(1600 - i * 7),
                    (10 - i % 10) + "-" + (i % 10), i % 3 == 0 ? "-" : String.valueOf(1500 + i)});
        }
        TableFormatter.Alignment[] al = {TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.LEFT,
                TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.CENTER, TableFormatter.Alignment.RIGHT};
        return TableImageGenerator.generateHallTable(headers, rows, hallNames, al, new int[]{3, 4, 5, 5, 6},
                new TableImageGenerator.ImageMetadata("Hall Rankings", "Fixture season\nAll rounds", "Round 10"),
                Set.of(1), "RankHalls", entity);
    }

    /** A /rankplayers-shaped player table with {@code n} rows. */
    public static Path playerTable(int n, String entity) throws Exception {
        String[] headers = {"#", "Player", "Hall", "Elo", "W-L", "LR", "ExpElo"};
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new String[]{String.valueOf(i + 1), "Fixture Player " + (char) ('A' + i % 26) + (i >= 26 ? String.valueOf(i) : ""),
                    "BJ", String.valueOf(1700 - i * 9), (9 - i % 9) + "-" + (i % 9), "R" + (1 + i % 10), "-"});
        }
        TableFormatter.Alignment[] al = {TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.LEFT, TableFormatter.Alignment.CENTER,
                TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.CENTER, TableFormatter.Alignment.LEFT, TableFormatter.Alignment.RIGHT};
        return TableImageGenerator.generatePlayerTable(headers, rows, al, new int[]{3, 20, 4, 5, 5, 3, 6},
                new TableImageGenerator.ImageMetadata("Player Rankings", "Fixture season", "Round 10"),
                Set.of(), "RankPlayers", entity);
    }

    /**
     * An /infoplayer-shaped image carrying the S2 shape on purpose: a
     * "Stats Per Round" block and a seating grid whose labels are "Round N"
     * (the default), plus a victory record with a "Round 10" label.
     */
    public static Path infoImage(String hall, String entity, String generatedDate) throws Exception {
        InfoImageGenerator.ImageMetadata md = new InfoImageGenerator.ImageMetadata();
        md.title = "Player Information";
        md.subtitle = "Fixture Player Alpha";
        md.description = "Fixture season";
        md.lastRound = "Round 10";
        if (generatedDate != null) md.generatedDate = generatedDate;
        List<InfoImageGenerator.Section> sections = new ArrayList<>();
        InfoImageGenerator.Section stats = new InfoImageGenerator.Section("Stats Per Round");
        stats.addMonospacedRow(String.format("%-4s %-6s %-10s %-6s %-10s", "Rnd", "Rank", "ΔRank", "ELO", "ΔELO"));
        for (int r = 1; r <= 10; r++) {
            stats.addMonospacedRow(String.format("%-4s %-6d %-10s %-6d %-10s", "Round " + r, r, r == 1 ? "-" : "=", 1000 + r, "+1"));
        }
        sections.add(stats);
        InfoImageGenerator.Section seating = new InfoImageGenerator.Section("Seating");
        StringBuilder h = new StringBuilder("Rnd: ");
        StringBuilder d = new StringBuilder("Seat:");
        for (int r = 1; r <= 10; r++) {
            h.append(String.format("%-3s|", "Round " + r));
            d.append(String.format("%-3s|", r % 3));
        }
        seating.addMonospacedRow(h.toString());
        seating.addMonospacedRow(d.toString());
        sections.add(seating);
        InfoImageGenerator.Section victory = new InfoImageGenerator.Section("Victory Record");
        for (int r = 1; r <= 10; r++) {
            InfoImageGenerator.VictoryEntry e = new InfoImageGenerator.VictoryEntry();
            e.round = "Round " + r;
            if (r == 7) {
                e.isNA = true;
            } else {
                e.hallOutcome = r % 3 == 0 ? -1 : (r % 4 == 0 ? 0 : 1);
                e.oppOutcome = e.hallOutcome == 0 ? 0 : -e.hallOutcome;
                e.playerHall = "BJ";
                e.playerElo = String.valueOf(1000 + r);
                e.playerName = "Fixture Player Alpha";
                e.score = (10 + r) + "-" + r;
                e.opponentName = "Fixture Opponent " + r;
                e.opponentElo = String.valueOf(990 + r);
                e.opponentHall = "CS";
                e.highlightPlayer = r == 2;
            }
            victory.addVictoryEntry(e);
        }
        sections.add(victory);
        return InfoImageGenerator.generateInfoImage(md, sections, hall, "InfoPlayer", entity);
    }

    /** A /comparehalls-shaped image: line sections, hall victory entries, two icons. */
    public static Path comparisonImage(String leftHall, String rightHall, String entity) throws Exception {
        ComparisonImageGenerator.ComparisonData left = side("Left Fixture Hall", leftHall, true);
        ComparisonImageGenerator.ComparisonData right = side("Right Fixture Hall", rightHall, false);
        return ComparisonImageGenerator.generateComparisonImage("Hall Comparison", left, right,
                new ComparisonImageGenerator.ImageMetadata("Hall Comparison", "Fixture season", "Round 10"),
                "CompareHalls", entity, entity + "b");
    }

    private static ComparisonImageGenerator.ComparisonData side(String entity, String hall, boolean left) {
        List<ComparisonImageGenerator.Section> sections = new ArrayList<>();
        List<String> elo = new ArrayList<>();
        elo.add(String.format("%-4s %-6s %-8s %-8s %-8s", "Rnd", "Rank", "ΔRank", "Elo", "ΔElo"));
        for (int r = 1; r <= 10; r++) {
            elo.add(String.format("%-4s %-6d %-8s %-8s %-8s", "R" + r, r, "=", String.format("%.1f", 1000.0 + r), "+1.0"));
        }
        sections.add(new ComparisonImageGenerator.Section("Hall Elo", elo));
        List<ComparisonImageGenerator.HallVictoryEntry> v = new ArrayList<>();
        for (int r = 1; r <= 10; r++) {
            if (r == 7) {
                v.add(new ComparisonImageGenerator.HallVictoryEntry("R" + r, true));
            } else {
                int o = r % 3 == 0 ? -1 : 1;
                v.add(new ComparisonImageGenerator.HallVictoryEntry("R" + r, "", "1500", left ? "Left Fixture" : "Right Fixture",
                        (5 + r) + "-" + (3 + r % 2), "Opponent Hall " + r, "1490", "", o, -o));
            }
        }
        sections.add(ComparisonImageGenerator.Section.forHallVictory("Victory Record", v));
        return new ComparisonImageGenerator.ComparisonData(entity, hall, sections);
    }
}
