package com.calplus.ihrgstats.perf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Lane b2 - synthetic league generator for the scalability probe. Extends the
 * {@link PerfBenchmarkHarness} scheme (deterministic CSVs written to a temp folder,
 * ingested through the real {@code RoundCsvProcessor}) from one 12 x 3 x 24 season
 * to a whole multi-season league shaped like the fictional sample corpus
 * (SAMPLE FILES/corpus_notes.md): Swiss rounds where every hall meets another hall
 * on 5 boards (odd hall count -> one bye hall written as 5 WALKOVER rows), then a
 * knock-out bracket halving the field (odd -> top seed walks over), a capped list per
 * season, a few draws and TIMEOUT losses.
 *
 * <p>Size k scales the four dimensions together: seasons, rounds per season and
 * halls by sqrt(k) (so total rounds grow k-fold), players by k (roster per hall by
 * sqrt(k), the rest is season-to-season churn). Every name is generated from
 * syllables - no name comes from a sample file ({@link #assertNoSampleNames}).
 */
public final class ScaleCorpus {

    public static final double MAX_SCORE = 370.0;
    public static final int BOARDS = 5;
    public static final int FIRST_YEAR = 2001;

    /** One generated league. */
    public record Spec(String label, int seasons, int[] roundsPerSeason, int halls, int rosterPerHall,
                       int totalPlayers, int cappedPerSeason, long seed) {

        public int totalRounds() {
            int t = 0;
            for (int r : roundsPerSeason) {
                t += r;
            }
            return t;
        }

        public int lastYear() {
            return FIRST_YEAR + seasons - 1;
        }

        public String describe() {
            StringBuilder rps = new StringBuilder();
            for (int i = 0; i < roundsPerSeason.length; i++) {
                rps.append(i == 0 ? "" : ",").append(roundsPerSeason[i]);
            }
            return String.format(Locale.ROOT, "%s: %d seasons (%d-%d), rounds/season [%s] = %d rounds, %d halls, roster %d/hall/season, %d players total, %d capped/season",
                    label, seasons, FIRST_YEAR, lastYear(), rps, totalRounds(), halls, rosterPerHall, totalPlayers, cappedPerSeason);
        }
    }

    /**
     * Today's scale (1x) is the corpus: 4 seasons of 10/10/9/10 rounds, 11 halls, rosters of 7,
     * 121 players. k x: seasons, rounds per season and halls times sqrt(k), players times k.
     */
    public static Spec ofScale(double k) {
        if (k == 1.0) {
            return new Spec("1x", 4, new int[]{10, 10, 9, 10}, 11, 7, 121, 10, 20261006L);
        }
        double s = Math.sqrt(k);
        int seasons = (int) Math.round(4 * s);
        int rps = (int) Math.round(9.75 * s);
        int halls = (int) Math.round(11 * s);
        int roster = (int) Math.round(7 * s);
        int players = (int) Math.round(121 * k);
        int[] rounds = new int[seasons];
        java.util.Arrays.fill(rounds, rps);
        String label = (k == Math.rint(k) ? String.valueOf((int) k) : String.valueOf(k)) + "x";
        return new Spec(label, seasons, rounds, halls, roster, players, (int) Math.round(10 * s), 20261006L + (long) (k * 1000));
    }

    /** A generated upload in ingest order. */
    public record Upload(int year, int round, boolean cappedList, Path file, int dataRows, boolean hasWalkover) {
    }

    /** What was generated, for the report and the answer sheet. */
    public static final class Generated {
        public final Spec spec;
        public final List<Upload> uploads = new ArrayList<>();
        public final List<String> hallNames = new ArrayList<>();
        public final Set<String> allPlayers = new HashSet<>();
        public final Map<Integer, Integer> playersPerYear = new LinkedHashMap<>();
        public int matchRows;
        public int walkoverRows;
        public int draws;
        public int timeouts;

        Generated(Spec spec) {
            this.spec = spec;
        }

        public String summary() {
            return String.format(Locale.ROOT, "%s | %d uploads (%d round files + %d capped lists) | %d distinct players | %d board rows (%d walkover rows, %d draws, %d timeouts)",
                    spec.describe(), uploads.size(), uploads.stream().filter(u -> !u.cappedList).count(),
                    uploads.stream().filter(Upload::cappedList).count(), allPlayers.size(), matchRows, walkoverRows, draws, timeouts);
        }
    }

    private ScaleCorpus() {
    }

    /** Hall names as stored: numeric 1..16 (seeded), then the 7 fictional named halls, then 17, 18, ... */
    public static List<String> hallNames(int halls) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= Math.min(16, halls); i++) {
            names.add(String.valueOf(i));
        }
        int extra = 17;
        int named = 0;
        while (names.size() < halls) {
            if (named < FICTIONAL_NAMED_HALLS.length) {
                names.add(FICTIONAL_NAMED_HALLS[named++]);
            } else {
                names.add(String.valueOf(extra++));
            }
        }
        return names;
    }

    /** Replacements for the 7 named seed halls (same codes, generated names). */
    public static final String[] FICTIONAL_NAMED_HALLS = {"Quillmere", "Ostravel", "Brindlecove", "Vantorre", "Elmshade", "Corvanta", "Sablewick"};

    // ------------------------------------------------------------------ names

    private static final String[] SYL = {
        "ka", "lo", "mi", "ren", "tas", "vo", "qui", "zel", "dar", "fen", "gor", "hal", "ix", "jun", "kor", "lum",
        "nav", "or", "pel", "ras", "sor", "tev", "ul", "vex", "wyn", "yar", "zor", "bel", "cen", "dov", "eth", "fal"
    };

    public static String generatedName(Random rnd, Set<String> used) {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            String first = cap(SYL[rnd.nextInt(SYL.length)] + SYL[rnd.nextInt(SYL.length)]);
            String last = cap(SYL[rnd.nextInt(SYL.length)] + SYL[rnd.nextInt(SYL.length)] + SYL[rnd.nextInt(SYL.length)]);
            String name = first + " " + last;
            String key = name.toLowerCase(Locale.ROOT);
            // keep names far apart: no shared first or last name with an existing player
            boolean clash = false;
            for (String u : used) {
                String[] p = u.split(" ");
                if (p[0].equals(first.toLowerCase(Locale.ROOT)) || p[1].equals(last.toLowerCase(Locale.ROOT))) {
                    clash = true;
                    break;
                }
            }
            if (!clash && used.add(key)) {
                return name;
            }
        }
        throw new IllegalStateException("name space exhausted");
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ generation

    private static final class Player {
        final String name;
        final int hall;
        final double skill;

        Player(String name, int hall, double skill) {
            this.name = name;
            this.hall = hall;
            this.skill = skill;
        }
    }

    /** Writes every upload CSV of the league into {@code dir} (created) and returns the ingest order. */
    public static Generated generate(Spec spec, Path dir) throws IOException {
        Files.createDirectories(dir);
        Generated g = new Generated(spec);
        Random rnd = new Random(spec.seed());
        g.hallNames.addAll(hallNames(spec.halls()));
        double[] hallStrength = new double[spec.halls()];
        for (int h = 0; h < spec.halls(); h++) {
            hallStrength[h] = rnd.nextGaussian() * 0.6;
        }
        Set<String> usedNames = new HashSet<>();
        List<List<Player>> rosters = new ArrayList<>();
        for (int h = 0; h < spec.halls(); h++) {
            List<Player> roster = new ArrayList<>();
            for (int i = 0; i < spec.rosterPerHall(); i++) {
                roster.add(new Player(generatedName(rnd, usedNames), h, hallStrength[h] + rnd.nextGaussian() * 0.5));
            }
            rosters.add(roster);
        }
        int initial = spec.halls() * spec.rosterPerHall();
        int churnTotal = Math.max(0, spec.totalPlayers() - initial);
        int churnSeasons = Math.max(1, spec.seasons() - 1);

        for (int si = 0; si < spec.seasons(); si++) {
            int year = FIRST_YEAR + si;
            if (si > 0) {
                // season-to-season churn: replace players, spread over halls round-robin
                int replace = churnTotal / churnSeasons + (si <= churnTotal % churnSeasons ? 1 : 0);
                for (int i = 0; i < replace; i++) {
                    int h = (si * 7 + i) % spec.halls();
                    List<Player> roster = rosters.get(h);
                    int slot = (si * 3 + i / spec.halls()) % roster.size();
                    roster.set(slot, new Player(generatedName(rnd, usedNames), h, hallStrength[h] + rnd.nextGaussian() * 0.5));
                }
            }
            Set<String> yearPlayers = new HashSet<>();
            for (List<Player> roster : rosters) {
                for (Player p : roster) {
                    yearPlayers.add(p.name);
                }
            }
            g.playersPerYear.put(year, yearPlayers.size());
            g.allPlayers.addAll(yearPlayers);

            // capped list: the strongest player of the first cappedPerSeason halls (rotating)
            StringBuilder capped = new StringBuilder("name,hall\n");
            for (int i = 0; i < spec.cappedPerSeason(); i++) {
                int h = (si + i) % spec.halls();
                Player best = Collections.max(rosters.get(h), (a, b) -> Double.compare(a.skill, b.skill));
                capped.append(csv(best.name)).append(',').append(g.hallNames.get(h)).append('\n');
            }
            Path cappedFile = dir.resolve(year + "_cappedlist.csv");
            Files.writeString(cappedFile, capped.toString(), StandardCharsets.UTF_8);
            g.uploads.add(new Upload(year, 0, true, cappedFile, spec.cappedPerSeason(), false));

            int rounds = spec.roundsPerSeason()[si];
            int bracketRounds = bracketRounds(spec.halls());
            int swiss = Math.max(1, rounds - bracketRounds);
            double[] boardWins = new double[spec.halls()];
            List<Integer> alive = new ArrayList<>();
            for (int h = 0; h < spec.halls(); h++) {
                alive.add(h);
            }
            for (int r = 1; r <= rounds; r++) {
                StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
                int[] counts = new int[4];
                List<int[]> pairs = new ArrayList<>();
                Integer bye = null;
                if (r <= swiss) {
                    // circle-method round robin over halls (+ a phantom for odd counts)
                    List<Integer> ids = new ArrayList<>();
                    for (int h = 0; h < spec.halls(); h++) {
                        ids.add(h);
                    }
                    if (ids.size() % 2 == 1) {
                        ids.add(-1);
                    }
                    int n = ids.size();
                    List<Integer> rot = new ArrayList<>(ids.subList(1, n));
                    Collections.rotate(rot, (r - 1) + si);
                    List<Integer> order = new ArrayList<>();
                    order.add(ids.get(0));
                    order.addAll(rot);
                    for (int i = 0; i < n / 2; i++) {
                        int a = order.get(i);
                        int b = order.get(n - 1 - i);
                        if (a < 0 || b < 0) {
                            bye = a < 0 ? b : a;
                        } else {
                            pairs.add(new int[]{a, b});
                        }
                    }
                } else {
                    // bracket: seeds by cumulative board wins, top vs weakest, odd -> top seed walks over
                    List<Integer> seeded = new ArrayList<>(alive);
                    seeded.sort((a, b) -> Double.compare(boardWins[b], boardWins[a]));
                    if (seeded.size() <= 1) {
                        break;
                    }
                    if (seeded.size() % 2 == 1) {
                        bye = seeded.remove(0);
                    }
                    int n = seeded.size();
                    for (int i = 0; i < n / 2; i++) {
                        pairs.add(new int[]{seeded.get(i), seeded.get(n - 1 - i)});
                    }
                }
                List<Integer> nextAlive = new ArrayList<>();
                if (bye != null) {
                    nextAlive.add(bye);
                }
                for (int[] pr : pairs) {
                    double hallScore = 0;
                    List<Player> ra = lineup(rosters.get(pr[0]), r);
                    List<Player> rb = lineup(rosters.get(pr[1]), r);
                    for (int bIdx = 0; bIdx < BOARDS; bIdx++) {
                        Player pa = ra.get(bIdx);
                        Player pb = rb.get(bIdx);
                        double p = 1.0 / (1.0 + Math.exp(-(pa.skill - pb.skill)));
                        double u = rnd.nextDouble();
                        String sa;
                        String sbs;
                        double outcome;
                        if (u < 0.03) {
                            double d = quarter(100 + rnd.nextDouble() * 150);
                            sa = fmt(d);
                            sbs = fmt(d);
                            outcome = 0.5;
                            counts[1]++;
                        } else if (u < 0.04) {
                            boolean aLoses = rnd.nextDouble() < 0.5;
                            sa = aLoses ? "TIMEOUT" : "";
                            sbs = aLoses ? "" : "TIMEOUT";
                            outcome = aLoses ? 0 : 1;
                            counts[2]++;
                        } else {
                            boolean aWins = rnd.nextDouble() < p;
                            double win = quarter(180 + rnd.nextDouble() * (MAX_SCORE - 180));
                            double lose = quarter(rnd.nextDouble() * (win - 0.25));
                            sa = fmt(aWins ? win : lose);
                            sbs = fmt(aWins ? lose : win);
                            outcome = aWins ? 1 : 0;
                        }
                        boardWins[pr[0]] += outcome;
                        boardWins[pr[1]] += 1 - outcome;
                        hallScore += outcome;
                        sb.append(csv(pa.name)).append(',').append(g.hallNames.get(pr[0])).append(',').append(sa).append(',')
                                .append(csv(pb.name)).append(',').append(g.hallNames.get(pr[1])).append(',').append(sbs).append('\n');
                        counts[0]++;
                    }
                    nextAlive.add(hallScore >= BOARDS / 2.0 ? pr[0] : pr[1]);
                }
                boolean hasWalkover = false;
                if (bye != null) {
                    for (Player p : lineup(rosters.get(bye), r)) {
                        sb.append(csv(p.name)).append(',').append(g.hallNames.get(bye)).append(",,WALKOVER,,\n");
                        counts[3]++;
                    }
                    hasWalkover = true;
                }
                if (r > swiss) {
                    alive = nextAlive;
                }
                Path f = dir.resolve(year + "_round_" + r + ".csv");
                Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
                g.uploads.add(new Upload(year, r, false, f, counts[0] + counts[3], hasWalkover));
                g.matchRows += counts[0];
                g.draws += counts[1];
                g.timeouts += counts[2];
                g.walkoverRows += counts[3];
            }
        }
        Files.writeString(dir.resolve("SPEC.txt"), g.summary() + "\n", StandardCharsets.UTF_8);
        return g;
    }

    /** Number of knock-out rounds that take {@code halls} teams down to one (odd -> bye). */
    public static int bracketRounds(int halls) {
        int n = halls;
        int r = 0;
        while (n > 1) {
            n = (n + 1) / 2;
            r++;
        }
        return r;
    }

    /** BOARDS players of the roster, rotating who sits out. */
    private static List<Player> lineup(List<Player> roster, int round) {
        List<Player> out = new ArrayList<>();
        for (int i = 0; i < BOARDS; i++) {
            out.add(roster.get((round + i) % roster.size()));
        }
        return out;
    }

    private static double quarter(double v) {
        return Math.round(v * 4) / 4.0;
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private static String csv(String s) {
        return s.contains(",") ? '"' + s + '"' : s;
    }

    /**
     * Fails if any generated player name occurs in a fictional sample CSV (top level of
     * SAMPLE FILES only - the generator reads no other folder).
     */
    public static void assertNoSampleNames(Generated g, Path sampleDir) throws IOException {
        Set<String> sample = new HashSet<>();
        try (var files = Files.list(sampleDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".csv")).toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    for (String cell : line.split(",")) {
                        sample.add(cell.replace("\"", "").trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        for (String name : g.allPlayers) {
            if (sample.contains(name.toLowerCase(Locale.ROOT))) {
                throw new AssertionError("generated name collides with a sample-file name: " + name);
            }
        }
        for (String hall : FICTIONAL_NAMED_HALLS) {
            if (sample.contains(hall.toLowerCase(Locale.ROOT))) {
                throw new AssertionError("generated hall name collides with a sample-file value: " + hall);
            }
        }
    }
}
