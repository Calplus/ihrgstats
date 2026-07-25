package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.calculations.EloCalculator;
import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.C9_MatchParticipants;
import com.calplus.ihrgstats.utils.Constants;

import java.sql.SQLException;
import java.util.*;

/**
 * Builds the leak-free per-board training/serving rows for the matchup
 * models: one {@link RawBoard} per rated board, where every quantity on
 * both sides is strictly "as of" the moment the board was played -
 * computed only from rounds strictly BEFORE it.
 *
 * Leakage rules (the credibility of the whole ML layer):
 * - Ratings come from a SINGLE FORWARD Glicko-2 pass
 *   ({@link EloCalculator#calculateWholeHistory} with passes=1), never from
 *   the 5-pass whole-history {@code player_ratings} (later results
 *   back-propagate into earlier rounds there) and never from
 *   {@code player_ratings_snapshot} (its backfill migration copied
 *   hindsight values into pre-feature rounds).
 * - Career/timeout/form/seat aggregates are updated only AFTER a round's
 *   rows have been emitted, so nothing within or after a round leaks into
 *   its own features.
 *
 * Board filtering mirrors {@link com.calplus.ihrgstats.calculations.RatingRecalculator}
 * exactly: walkover boards (WLKOVR sentinel or WALKOVER participation) are
 * excluded, TIMEOUT boards are included, and only boards with exactly two
 * participants count. Walkover boards still contribute the real side's
 * SEAT to the seat-prior aggregates (the captain did assign that seat -
 * informative) but never to games, career counts, or form.
 *
 * Sides are ordered deterministically: A = lexicographically smaller
 * player_id, so repeated extractions are byte-identical.
 */
public class FeatureExtractor {

    public static final double DEFAULT_RATING = Constants.BASE_ELO;
    public static final double DEFAULT_RD = 350.0;
    private static final double GLICKO2_SCALE = 173.7178;

    /** Neutral seat imputation when hall_seat_number is NULL (middle board of 5). */
    public static final double SEAT_IMPUTED = 3.0;

    public static final int SYM_DIM = 4;
    public static final int ANTI_DIM = 7;

    /** One side's as-of state snapshot entering the board's round. */
    public static class Side {
        public final String playerId;
        public final int hallId;
        public final Integer seat;          // nullable - imputed at assembly time
        public final double rating;         // forward-pass entry rating
        public final double rd;             // forward-pass entry RD
        public final int careerBoards;      // rated boards before this round
        public final int careerTimeouts;    // own TIMEOUT participations before this round
        public final double sumOutcomeLast5;// sum of outcomes over the last <=5 rated boards before this round
        public final int countLast5;
        public final double seatPrior;      // running mean entry-rating fielded at (hall, seat) before this round

        public Side(String playerId, int hallId, Integer seat, double rating, double rd,
                    int careerBoards, int careerTimeouts, double sumOutcomeLast5, int countLast5,
                    double seatPrior) {
            this.playerId = playerId;
            this.hallId = hallId;
            this.seat = seat;
            this.rating = rating;
            this.rd = rd;
            this.careerBoards = careerBoards;
            this.careerTimeouts = careerTimeouts;
            this.sumOutcomeLast5 = sumOutcomeLast5;
            this.countLast5 = countLast5;
            this.seatPrior = seatPrior;
        }
    }

    /** One rated board with both sides' as-of snapshots. A = smaller player_id. */
    public static class RawBoard {
        public final int matchId;
        public final int roundSeq;   // global chronological round index (0-based)
        public final int roundId;
        public final int year;
        public final int roundOrder;
        public final Side a;
        public final Side b;
        public final double outcomeA; // 1.0 A wins / 0.5 draw / 0.0 B wins
        public final boolean aTimedOut; // participation_type TIMEOUT on A's row
        public final boolean bTimedOut;

        public RawBoard(int matchId, int roundSeq, int roundId, int year, int roundOrder,
                        Side a, Side b, double outcomeA, boolean aTimedOut, boolean bTimedOut) {
            this.matchId = matchId;
            this.roundSeq = roundSeq;
            this.roundId = roundId;
            this.year = year;
            this.roundOrder = roundOrder;
            this.a = a;
            this.b = b;
            this.outcomeA = outcomeA;
            this.aTimedOut = aTimedOut;
            this.bTimedOut = bTimedOut;
        }

        public boolean isDraw() {
            return outcomeA == 0.5;
        }
    }

    /** Assembled feature vectors for one board (see {@link #assemble}). */
    public static class Vectors {
        public final double[] sym;
        public final double[] anti;

        public Vectors(double[] sym, double[] anti) {
            this.sym = sym;
            this.anti = anti;
        }
    }

    private final A1_Rounds rounds = new A1_Rounds();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();

    /**
     * Extracts every rated board across all years in chronological order.
     * Deterministic: same database state, byte-identical output.
     *
     * @throws IllegalStateException if any rated participant outcome is not
     *         exactly 0, 0.5 or 1 (guards against the legacy 1/0/-1 encoding
     *         documented in VictoryRecordCalculator ever appearing here).
     */
    public List<RawBoard> extractAll() throws SQLException {
        // 1. All rounds chronologically, with a global sequence index.
        List<A1_Rounds.Round> allRounds = new ArrayList<>();
        for (int year : rounds.getAllYears()) {
            allRounds.addAll(rounds.getRoundsForYear(year));
        }
        if (allRounds.isEmpty()) {
            return List.of();
        }

        // 2. Load each round's rated boards (and walkover seats) once.
        //    ratedBoardsBySeq: seq -> list of 2-participant rated boards (sorted sides).
        List<List<C9_MatchParticipants.Participant[]>> ratedBoardsBySeq = new ArrayList<>();
        List<List<C9_MatchParticipants.Participant>> walkoverSidesBySeq = new ArrayList<>();
        List<EloCalculator.Game> games = new ArrayList<>();
        Set<String> allPlayerIds = new HashSet<>();

        for (int seq = 0; seq < allRounds.size(); seq++) {
            A1_Rounds.Round round = allRounds.get(seq);
            Map<Integer, List<C9_MatchParticipants.Participant>> byMatch = new LinkedHashMap<>();
            for (C9_MatchParticipants.Participant p : participants.getParticipantsForRound(round.id)) {
                byMatch.computeIfAbsent(p.matchId, k -> new ArrayList<>()).add(p);
            }

            List<C9_MatchParticipants.Participant[]> rated = new ArrayList<>();
            List<C9_MatchParticipants.Participant> walkoverSides = new ArrayList<>();
            for (List<C9_MatchParticipants.Participant> matchParticipants : byMatch.values()) {
                boolean hasWalkover = false;
                for (C9_MatchParticipants.Participant p : matchParticipants) {
                    if (B4_Players.WALKOVER_PLAYER_ID.equals(p.playerId)
                            || C9_MatchParticipants.PARTICIPATION_WALKOVER.equals(p.participationType)) {
                        hasWalkover = true;
                    }
                }
                if (hasWalkover) {
                    // Real sides of walkover boards still carry captain seat info.
                    for (C9_MatchParticipants.Participant p : matchParticipants) {
                        if (!B4_Players.WALKOVER_PLAYER_ID.equals(p.playerId)
                                && !C9_MatchParticipants.PARTICIPATION_WALKOVER.equals(p.participationType)) {
                            walkoverSides.add(p);
                        }
                    }
                    continue;
                }
                if (matchParticipants.size() != 2) {
                    continue;
                }
                C9_MatchParticipants.Participant p1 = matchParticipants.get(0);
                C9_MatchParticipants.Participant p2 = matchParticipants.get(1);
                assertValidOutcome(p1);
                assertValidOutcome(p2);
                // Deterministic side order: A = smaller player_id.
                C9_MatchParticipants.Participant[] pair =
                        p1.playerId.compareTo(p2.playerId) <= 0
                                ? new C9_MatchParticipants.Participant[]{p1, p2}
                                : new C9_MatchParticipants.Participant[]{p2, p1};
                rated.add(pair);
                games.add(new EloCalculator.Game(pair[0].playerId, pair[1].playerId, pair[0].outcome, seq));
                allPlayerIds.add(pair[0].playerId);
                allPlayerIds.add(pair[1].playerId);
            }
            ratedBoardsBySeq.add(rated);
            walkoverSidesBySeq.add(walkoverSides);
        }

        // 3. Single FORWARD Glicko pass: round seq s entry state = post-state of seq s-1.
        List<Integer> sequence = new ArrayList<>();
        for (int seq = 0; seq < allRounds.size(); seq++) {
            sequence.add(seq);
        }
        EloCalculator.Glicko2Result forward = EloCalculator.calculateWholeHistory(
                games, allPlayerIds, Map.of(), sequence, 1);

        // 4. Chronological sweep maintaining strictly-as-of aggregates.
        Map<String, Integer> careerBoards = new HashMap<>();
        Map<String, Integer> careerTimeouts = new HashMap<>();
        Map<String, Deque<Double>> last5 = new HashMap<>();
        Map<Long, double[]> hallSeatAgg = new HashMap<>();   // (hallId, seat) -> {sumRating, count}
        Map<Integer, double[]> hallAgg = new HashMap<>();    // hallId -> {sumRating, count}
        double[] globalAgg = new double[2];

        List<RawBoard> out = new ArrayList<>();
        for (int seq = 0; seq < allRounds.size(); seq++) {
            A1_Rounds.Round round = allRounds.get(seq);
            Map<String, EloCalculator.Glicko2Rating> entry =
                    seq == 0 ? Map.of() : forward.ratingsByRound.getOrDefault(seq - 1, Map.of());

            // Emit rows for this round from pre-round state.
            for (C9_MatchParticipants.Participant[] pair : ratedBoardsBySeq.get(seq)) {
                Side a = buildSide(pair[0], entry, careerBoards, careerTimeouts, last5, hallSeatAgg, hallAgg, globalAgg);
                Side b = buildSide(pair[1], entry, careerBoards, careerTimeouts, last5, hallSeatAgg, hallAgg, globalAgg);
                out.add(new RawBoard(pair[0].matchId, seq, round.id, round.year, round.roundOrder,
                        a, b, pair[0].outcome,
                        C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(pair[0].participationType),
                        C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(pair[1].participationType)));
            }

            // Only AFTER emission: fold this round's results into the aggregates.
            for (C9_MatchParticipants.Participant[] pair : ratedBoardsBySeq.get(seq)) {
                for (C9_MatchParticipants.Participant p : pair) {
                    double entryRating = entryRating(entry, p.playerId);
                    careerBoards.merge(p.playerId, 1, Integer::sum);
                    if (C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(p.participationType)) {
                        careerTimeouts.merge(p.playerId, 1, Integer::sum);
                    }
                    Deque<Double> deque = last5.computeIfAbsent(p.playerId, k -> new ArrayDeque<>());
                    deque.addLast(p.outcome);
                    if (deque.size() > 5) {
                        deque.removeFirst();
                    }
                    updateSeatAggregates(p, entryRating, hallSeatAgg, hallAgg, globalAgg);
                }
            }
            for (C9_MatchParticipants.Participant p : walkoverSidesBySeq.get(seq)) {
                updateSeatAggregates(p, entryRating(entry, p.playerId), hallSeatAgg, hallAgg, globalAgg);
            }
        }
        return out;
    }

    private static void assertValidOutcome(C9_MatchParticipants.Participant p) {
        if (p.outcome != 0.0 && p.outcome != 0.5 && p.outcome != 1.0) {
            throw new IllegalStateException(String.format(
                    "match_participants outcome for player %s in match %d is %s - expected exactly 0, 0.5 or 1 "
                    + "(the legacy 1/0/-1 encoding must never reach the ML layer)",
                    p.playerId, p.matchId, p.outcome));
        }
    }

    private static double entryRating(Map<String, EloCalculator.Glicko2Rating> entry, String playerId) {
        EloCalculator.Glicko2Rating r = entry.get(playerId);
        return r != null ? r.rating : DEFAULT_RATING;
    }

    private Side buildSide(C9_MatchParticipants.Participant p,
                           Map<String, EloCalculator.Glicko2Rating> entry,
                           Map<String, Integer> careerBoards,
                           Map<String, Integer> careerTimeouts,
                           Map<String, Deque<Double>> last5,
                           Map<Long, double[]> hallSeatAgg,
                           Map<Integer, double[]> hallAgg,
                           double[] globalAgg) {
        EloCalculator.Glicko2Rating r = entry.get(p.playerId);
        double rating = r != null ? r.rating : DEFAULT_RATING;
        double rd = r != null ? r.rd : DEFAULT_RD;
        Deque<Double> deque = last5.get(p.playerId);
        double sum = 0.0;
        int count = 0;
        if (deque != null) {
            for (double o : deque) {
                sum += o;
            }
            count = deque.size();
        }
        return new Side(p.playerId, p.hallId, p.hallSeatNumber, rating, rd,
                careerBoards.getOrDefault(p.playerId, 0),
                careerTimeouts.getOrDefault(p.playerId, 0),
                sum, count,
                seatPrior(p.hallId, p.hallSeatNumber, hallSeatAgg, hallAgg, globalAgg));
    }

    /**
     * Running mean entry-rating this hall has fielded at this seat before
     * now; falls back hall -> global -> default when no history yet. This
     * is the leak-free "hall strength-at-seat" prior a debutant inherits.
     */
    private static double seatPrior(int hallId, Integer seat,
                                    Map<Long, double[]> hallSeatAgg,
                                    Map<Integer, double[]> hallAgg,
                                    double[] globalAgg) {
        if (seat != null) {
            double[] hs = hallSeatAgg.get(hallSeatKey(hallId, seat));
            if (hs != null && hs[1] > 0) {
                return hs[0] / hs[1];
            }
        }
        double[] h = hallAgg.get(hallId);
        if (h != null && h[1] > 0) {
            return h[0] / h[1];
        }
        if (globalAgg[1] > 0) {
            return globalAgg[0] / globalAgg[1];
        }
        return DEFAULT_RATING;
    }

    private static void updateSeatAggregates(C9_MatchParticipants.Participant p, double entryRating,
                                             Map<Long, double[]> hallSeatAgg,
                                             Map<Integer, double[]> hallAgg,
                                             double[] globalAgg) {
        if (p.hallSeatNumber != null) {
            double[] hs = hallSeatAgg.computeIfAbsent(hallSeatKey(p.hallId, p.hallSeatNumber), k -> new double[2]);
            hs[0] += entryRating;
            hs[1] += 1;
        }
        double[] h = hallAgg.computeIfAbsent(p.hallId, k -> new double[2]);
        h[0] += entryRating;
        h[1] += 1;
        globalAgg[0] += entryRating;
        globalAgg[1] += 1;
    }

    private static long hallSeatKey(int hallId, int seat) {
        return ((long) hallId << 32) | (seat & 0xffffffffL);
    }

    // ========================================================================
    // Feature assembly (RawBoard -> model input vectors)
    // ========================================================================

    /**
     * Experience-shrunk effective rating: blends the player's own rating
     * with their hall-seat prior, weighted by how much is known about them.
     * w = n / (n + n0): a debutant (n=0) is entirely their seat prior, a
     * veteran is entirely their own rating. n0 is a backtest-tuned
     * hyperparameter (the "seat-as-prior" lever).
     */
    public static double effectiveRating(Side s, double n0) {
        double w = s.careerBoards / (s.careerBoards + n0);
        return w * s.rating + (1.0 - w) * s.seatPrior;
    }

    /** Glicko-2 g() with both RDs combined - symmetric in A/B by construction. */
    public static double combinedG(double rdA, double rdB) {
        double phi = Math.sqrt(rdA * rdA + rdB * rdB) / GLICKO2_SCALE;
        return 1.0 / Math.sqrt(1.0 + 3.0 * phi * phi / (Math.PI * Math.PI));
    }

    /**
     * Assembles the model input vectors for one board.
     *
     * anti[] entries are exactly antisymmetric (swapping A and B negates
     * every entry), sym[] entries are exactly symmetric - together with the
     * two-stage model structure this guarantees P(A beats B) = 1 - P(B beats A).
     */
    public static Vectors assemble(RawBoard rb, double n0) {
        Side a = rb.a;
        Side b = rb.b;
        double effA = effectiveRating(a, n0);
        double effB = effectiveRating(b, n0);
        double seatA = a.seat != null ? a.seat : SEAT_IMPUTED;
        double seatB = b.seat != null ? b.seat : SEAT_IMPUTED;
        double toRateA = (a.careerTimeouts + 1.0) / (a.careerBoards + 10.0);
        double toRateB = (b.careerTimeouts + 1.0) / (b.careerBoards + 10.0);
        double formA = (a.sumOutcomeLast5 + 1.5) / (a.countLast5 + 3.0); // shrunk toward 0.5 (k=3)
        double formB = (b.sumOutcomeLast5 + 1.5) / (b.countLast5 + 3.0);
        double damp = 1.0 / (1.0 + (a.rd + b.rd) / 350.0);

        double[] anti = new double[ANTI_DIM];
        anti[0] = combinedG(a.rd, b.rd) * (effA - effB) / GLICKO2_SCALE; // logit of Glicko-style expected score
        anti[1] = (effA - effB) / 400.0;
        anti[2] = seatA - seatB;
        anti[3] = Math.log1p(a.careerBoards) - Math.log1p(b.careerBoards);
        anti[4] = toRateA - toRateB;
        anti[5] = formA - formB;
        anti[6] = anti[1] * damp;

        double[] sym = new double[SYM_DIM];
        sym[0] = Math.abs(effA - effB) / 400.0;
        sym[1] = ((a.rd + b.rd) / 2.0) / 350.0;
        sym[2] = (Math.log1p(a.careerBoards) + Math.log1p(b.careerBoards)) / 2.0;
        sym[3] = rb.roundOrder / 10.0;

        return new Vectors(sym, anti);
    }

    /** The board with A and B exchanged - used by symmetry tests and predictors. */
    public static RawBoard swapped(RawBoard rb) {
        return new RawBoard(rb.matchId, rb.roundSeq, rb.roundId, rb.year, rb.roundOrder,
                rb.b, rb.a, 1.0 - rb.outcomeA, rb.bTimedOut, rb.aTimedOut);
    }
}
