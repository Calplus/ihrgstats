package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.calculations.EloCalculator;
import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.A2_MatchTypes;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.C8_Matches;
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
 * - Every running aggregate (career counts, form, margins, seat/rating
 *   history, opponent-quality, hall records...) is updated only AFTER a
 *   round's rows have been emitted, so nothing within or after a round
 *   leaks into its own features.
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
 *
 * Scope note (deliberate cuts from the full feature catalog, so the
 * per-board model stays a clean function of two independent Side
 * snapshots): exact head-to-head history against one specific opponent
 * and hall-vs-hall aggregate records are NOT modeled here - both are
 * inherently pairwise-of-pairwise statistics that don't fit the
 * per-player Side abstraction cleanly, and the plan itself notes
 * repeat pairings are rare at this data scale (their value is deferred
 * to the player-embedding interaction terms). Calendar-based "rest
 * days" is also skipped: round_datetime is essentially never populated
 * by any live flow today, so a sequence-based "rounds missed" proxy is
 * used instead, which is always available.
 */
public class FeatureExtractor {

    public static final double DEFAULT_RATING = Constants.BASE_ELO;
    public static final double DEFAULT_RD = 350.0;
    private static final double GLICKO2_SCALE = 173.7178;

    /** Neutral seat imputation when hall_seat_number is NULL (middle board of 5), for models that can't handle NaN. */
    public static final double SEAT_IMPUTED = 3.0;
    /** Fallback normalization divisor for a board margin when the round has no assigned match type. */
    private static final double DEFAULT_MARGIN_SCALE = 10.0;
    /** Blowout threshold on normalized margin (own score - opp score, over match max_score). */
    private static final double BLOWOUT_THRESHOLD = 0.75;

    public static final int SYM_DIM = 6;
    public static final int ANTI_DIM = 20;

    /** One side's as-of state snapshot entering the board's round. */
    public static class Side {
        public final String playerId;
        public final int hallId;
        public final Integer seat;          // nullable - imputed (or NaN-sentinel'd) at assembly time
        public final double rating;         // forward-pass entry rating
        public final double rd;             // forward-pass entry RD
        public final int careerBoards;      // rated boards before this round
        public final int careerTimeouts;    // own TIMEOUT participations before this round
        public final double sumOutcomeLast5;// sum of outcomes over the last <=5 rated boards before this round
        public final int countLast5;
        public final double seatPrior;      // running mean entry-rating fielded at (hall, seat) before this round

        // -- Extended ("[B]") features - all strictly as-of, folded in only after emission --
        public final double ratingTrajectory;  // slope of last <=3 entry ratings (0 if <2 samples)
        public final double ratingStability;   // population stdev of last <=5 entry ratings (0 if <2 samples)
        public final double hallRatingBias;    // own entry rating - own hall's current mean fielded rating
        public final int seasonBoards;         // rated boards played THIS YEAR before this round
        public final double oppQualityBias;    // mean opponent rating faced so far - population mean rating so far
        public final double graphInsularity;   // share of career boards vs the single most-faced opponent hall
        public final int roundsMissedThisSeason; // rounds since last played this year (0 if debut or consecutive)
        public final double seatTrend;         // slope of last <=3 known (non-null) seats (0 if <2 known)
        public final double marginForm;        // mean of last <=5 normalized board margins (0 if none)
        public final double blowoutRate;       // share of last <=5 boards with normalized margin >= 0.75
        public final int walkoverReceivedCount;// times this player was the real side of a walkover
        public final double oppTimeoutForcedRate; // shrunk rate of forcing THIS opponent's hall... see note below
        public final double vsOpponentHallRate;   // shrunk rate of outcomes vs the actual opponent's hall

        public Side(String playerId, int hallId, Integer seat, double rating, double rd,
                    int careerBoards, int careerTimeouts, double sumOutcomeLast5, int countLast5,
                    double seatPrior, double ratingTrajectory, double ratingStability, double hallRatingBias,
                    int seasonBoards, double oppQualityBias, double graphInsularity, int roundsMissedThisSeason,
                    double seatTrend, double marginForm, double blowoutRate, int walkoverReceivedCount,
                    double oppTimeoutForcedRate, double vsOpponentHallRate) {
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
            this.ratingTrajectory = ratingTrajectory;
            this.ratingStability = ratingStability;
            this.hallRatingBias = hallRatingBias;
            this.seasonBoards = seasonBoards;
            this.oppQualityBias = oppQualityBias;
            this.graphInsularity = graphInsularity;
            this.roundsMissedThisSeason = roundsMissedThisSeason;
            this.seatTrend = seatTrend;
            this.marginForm = marginForm;
            this.blowoutRate = blowoutRate;
            this.walkoverReceivedCount = walkoverReceivedCount;
            this.oppTimeoutForcedRate = oppTimeoutForcedRate;
            this.vsOpponentHallRate = vsOpponentHallRate;
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
        public final double matchMaxScoreNorm; // this round's match_type max_score / 10, 0 if unassigned

        public RawBoard(int matchId, int roundSeq, int roundId, int year, int roundOrder,
                        Side a, Side b, double outcomeA, boolean aTimedOut, boolean bTimedOut,
                        double matchMaxScoreNorm) {
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
            this.matchMaxScoreNorm = matchMaxScoreNorm;
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
    private final C8_Matches matches = new C8_Matches();
    private final A2_MatchTypes matchTypes = new A2_MatchTypes();

    /** All chronologically-updated running state, bundled to keep buildSide's signature manageable. */
    private static class RunningState {
        final Map<String, Integer> careerBoards = new HashMap<>();
        final Map<String, Integer> careerTimeouts = new HashMap<>();
        final Map<String, Deque<Double>> last5 = new HashMap<>();
        final Map<Long, double[]> hallSeatAgg = new HashMap<>();     // (hallId,seat) -> {sumRating, count}
        final Map<Integer, double[]> hallAgg = new HashMap<>();      // hallId -> {sumRating, count}
        final double[] globalAgg = new double[2];                   // {sumRating, count}
        final Map<String, Deque<Double>> ratingHistory = new HashMap<>(); // up to 5 entry ratings
        final Map<String, Deque<Integer>> seatHistory = new HashMap<>();  // up to 3 known seats
        final Map<String, Deque<Double>> marginHistory = new HashMap<>(); // up to 5 normalized margins
        final Map<String, Integer> seasonBoards = new HashMap<>();        // key playerId|year
        final Map<String, Integer> lastPlayedRoundOrder = new HashMap<>();// key playerId|year
        final Map<String, double[]> oppRatingAgg = new HashMap<>();       // playerId -> {sumOppRating, count}
        final Map<String, Map<Integer, double[]>> vsHallAgg = new HashMap<>(); // playerId -> hallId -> {sumOutcome, count}
        final Map<String, Integer> walkoverReceivedCount = new HashMap<>();
        final Map<String, Integer> oppTimeoutForcedWins = new HashMap<>();
    }

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

        // 2. Load each round's rated boards (and walkover seats) once, plus its match-type max score.
        List<List<C9_MatchParticipants.Participant[]>> ratedBoardsBySeq = new ArrayList<>();
        List<List<C9_MatchParticipants.Participant>> walkoverSidesBySeq = new ArrayList<>();
        double[] matchMaxScoreNormBySeq = new double[allRounds.size()];
        Map<Integer, Double> maxScoreByTypeId = new HashMap<>();
        List<EloCalculator.Game> games = new ArrayList<>();
        Set<String> allPlayerIds = new HashSet<>();

        for (int seq = 0; seq < allRounds.size(); seq++) {
            A1_Rounds.Round round = allRounds.get(seq);

            Integer matchTypeId = matches.getMatchTypeIdForRound(round.id);
            double maxScoreNorm = 0.0;
            if (matchTypeId != null) {
                Double cached = maxScoreByTypeId.get(matchTypeId);
                if (cached == null) {
                    A2_MatchTypes.MatchType mt = matchTypes.getMatchTypeById(matchTypeId);
                    cached = mt != null ? mt.maxScore : 0.0;
                    maxScoreByTypeId.put(matchTypeId, cached);
                }
                maxScoreNorm = cached / DEFAULT_MARGIN_SCALE;
            }
            matchMaxScoreNormBySeq[seq] = maxScoreNorm;

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
        RunningState state = new RunningState();
        List<RawBoard> out = new ArrayList<>();
        for (int seq = 0; seq < allRounds.size(); seq++) {
            A1_Rounds.Round round = allRounds.get(seq);
            Map<String, EloCalculator.Glicko2Rating> entry =
                    seq == 0 ? Map.of() : forward.ratingsByRound.getOrDefault(seq - 1, Map.of());

            Integer matchTypeIdForRound = matches.getMatchTypeIdForRound(round.id);
            double marginScale = matchTypeIdForRound != null && maxScoreByTypeId.getOrDefault(matchTypeIdForRound, 0.0) > 0
                    ? maxScoreByTypeId.get(matchTypeIdForRound) : DEFAULT_MARGIN_SCALE;

            // Emit rows for this round from pre-round state.
            for (C9_MatchParticipants.Participant[] pair : ratedBoardsBySeq.get(seq)) {
                Side a = buildSide(pair[0], pair[1].hallId, entry, round, state);
                Side b = buildSide(pair[1], pair[0].hallId, entry, round, state);
                out.add(new RawBoard(pair[0].matchId, seq, round.id, round.year, round.roundOrder,
                        a, b, pair[0].outcome,
                        C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(pair[0].participationType),
                        C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(pair[1].participationType),
                        matchMaxScoreNormBySeq[seq]));
            }

            // Only AFTER emission: fold this round's results into the aggregates.
            for (C9_MatchParticipants.Participant[] pair : ratedBoardsBySeq.get(seq)) {
                C9_MatchParticipants.Participant p1 = pair[0];
                C9_MatchParticipants.Participant p2 = pair[1];
                double margin1 = (p1.score - p2.score) / marginScale;
                double margin2 = -margin1;
                foldInResult(p1, p2, margin1, entry, round, state);
                foldInResult(p2, p1, margin2, entry, round, state);
            }
            for (C9_MatchParticipants.Participant p : walkoverSidesBySeq.get(seq)) {
                double entryRating = entryRating(entry, p.playerId);
                updateSeatAggregates(p, entryRating, state);
                state.walkoverReceivedCount.merge(p.playerId, 1, Integer::sum);
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

    private static String seasonKey(String playerId, int year) {
        return playerId + "|" + year;
    }

    private Side buildSide(C9_MatchParticipants.Participant p, int opponentHallId,
                           Map<String, EloCalculator.Glicko2Rating> entry,
                           A1_Rounds.Round round, RunningState state) {
        EloCalculator.Glicko2Rating r = entry.get(p.playerId);
        double rating = r != null ? r.rating : DEFAULT_RATING;
        double rd = r != null ? r.rd : DEFAULT_RD;

        Deque<Double> outcomeDeque = state.last5.get(p.playerId);
        double sum = 0.0;
        int count = 0;
        if (outcomeDeque != null) {
            for (double o : outcomeDeque) {
                sum += o;
            }
            count = outcomeDeque.size();
        }

        double seatPrior = seatPrior(p.hallId, p.hallSeatNumber, state.hallSeatAgg, state.hallAgg, state.globalAgg);

        Deque<Double> ratingDeque = state.ratingHistory.get(p.playerId);
        double trajectory = slope(ratingDeque, 3);
        double stability = stdev(ratingDeque, 5);

        double[] hallStats = state.hallAgg.get(p.hallId);
        double hallMean = hallStats != null && hallStats[1] > 0 ? hallStats[0] / hallStats[1] : rating;
        double hallRatingBias = rating - hallMean;

        int seasonBoards = state.seasonBoards.getOrDefault(seasonKey(p.playerId, round.year), 0);

        double[] oppStats = state.oppRatingAgg.get(p.playerId);
        double oppMean = oppStats != null && oppStats[1] > 0 ? oppStats[0] / oppStats[1] : Double.NaN;
        double populationMean = state.globalAgg[1] > 0 ? state.globalAgg[0] / state.globalAgg[1] : DEFAULT_RATING;
        double oppQualityBias = Double.isNaN(oppMean) ? 0.0 : oppMean - populationMean;

        int career = state.careerBoards.getOrDefault(p.playerId, 0);
        Map<Integer, double[]> vsHalls = state.vsHallAgg.get(p.playerId);
        double graphInsularity = 0.0;
        if (vsHalls != null && career > 0) {
            double maxCount = 0.0;
            for (double[] stats : vsHalls.values()) {
                maxCount = Math.max(maxCount, stats[1]);
            }
            graphInsularity = maxCount / career;
        }

        Integer lastOrder = state.lastPlayedRoundOrder.get(seasonKey(p.playerId, round.year));
        int roundsMissed = lastOrder != null ? Math.max(0, round.roundOrder - lastOrder - 1) : 0;

        Deque<Integer> seatDeque = state.seatHistory.get(p.playerId);
        double seatTrend = slopeInt(seatDeque, 3);

        Deque<Double> marginDeque = state.marginHistory.get(p.playerId);
        double marginForm = mean(marginDeque);
        double blowoutRate = blowoutShare(marginDeque);

        int walkoverReceived = state.walkoverReceivedCount.getOrDefault(p.playerId, 0);

        int forcedWins = state.oppTimeoutForcedWins.getOrDefault(p.playerId, 0);
        double oppTimeoutForcedRate = (forcedWins + 1.0) / (career + 10.0);

        double[] hallRecord = vsHalls != null ? vsHalls.get(opponentHallId) : null;
        double vsOpponentHallRate = hallRecord != null
                ? (hallRecord[0] + 1.5) / (hallRecord[1] + 3.0)
                : 0.5;

        return new Side(p.playerId, p.hallId, p.hallSeatNumber, rating, rd,
                career, state.careerTimeouts.getOrDefault(p.playerId, 0), sum, count, seatPrior,
                trajectory, stability, hallRatingBias, seasonBoards, oppQualityBias, graphInsularity,
                roundsMissed, seatTrend, marginForm, blowoutRate, walkoverReceived,
                oppTimeoutForcedRate, vsOpponentHallRate);
    }

    /** Folds one participant's result into every running aggregate - called strictly AFTER emission. */
    private static void foldInResult(C9_MatchParticipants.Participant p, C9_MatchParticipants.Participant opponent,
                                     double normalizedMargin, Map<String, EloCalculator.Glicko2Rating> entry,
                                     A1_Rounds.Round round, RunningState state) {
        double entryRating = entryRating(entry, p.playerId);
        double opponentEntryRating = entryRating(entry, opponent.playerId);

        state.careerBoards.merge(p.playerId, 1, Integer::sum);
        if (C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(p.participationType)) {
            state.careerTimeouts.merge(p.playerId, 1, Integer::sum);
        }

        Deque<Double> outcomeDeque = state.last5.computeIfAbsent(p.playerId, k -> new ArrayDeque<>());
        pushCapped(outcomeDeque, p.outcome, 5);

        updateSeatAggregates(p, entryRating, state);

        pushCapped(state.ratingHistory.computeIfAbsent(p.playerId, k -> new ArrayDeque<>()), entryRating, 5);
        if (p.hallSeatNumber != null) {
            pushCapped(state.seatHistory.computeIfAbsent(p.playerId, k -> new ArrayDeque<>()), p.hallSeatNumber, 3);
        }
        pushCapped(state.marginHistory.computeIfAbsent(p.playerId, k -> new ArrayDeque<>()), normalizedMargin, 5);

        state.seasonBoards.merge(seasonKey(p.playerId, round.year), 1, Integer::sum);
        state.lastPlayedRoundOrder.put(seasonKey(p.playerId, round.year), round.roundOrder);

        double[] oppAgg = state.oppRatingAgg.computeIfAbsent(p.playerId, k -> new double[2]);
        oppAgg[0] += opponentEntryRating;
        oppAgg[1] += 1;

        Map<Integer, double[]> vsHalls = state.vsHallAgg.computeIfAbsent(p.playerId, k -> new HashMap<>());
        double[] hallRecord = vsHalls.computeIfAbsent(opponent.hallId, k -> new double[2]);
        hallRecord[0] += p.outcome;
        hallRecord[1] += 1;

        if (p.outcome == 1.0 && C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(opponent.participationType)) {
            state.oppTimeoutForcedWins.merge(p.playerId, 1, Integer::sum);
        }
    }

    private static void pushCapped(Deque<Double> deque, double value, int cap) {
        deque.addLast(value);
        if (deque.size() > cap) {
            deque.removeFirst();
        }
    }

    private static void pushCapped(Deque<Integer> deque, int value, int cap) {
        deque.addLast(value);
        if (deque.size() > cap) {
            deque.removeFirst();
        }
    }

    private static double slope(Deque<Double> deque, int maxSamples) {
        if (deque == null || deque.size() < 2) {
            return 0.0;
        }
        List<Double> values = new ArrayList<>(deque);
        int from = Math.max(0, values.size() - maxSamples);
        List<Double> window = values.subList(from, values.size());
        int n = window.size();
        return (window.get(n - 1) - window.get(0)) / (n - 1);
    }

    private static double slopeInt(Deque<Integer> deque, int maxSamples) {
        if (deque == null || deque.size() < 2) {
            return 0.0;
        }
        List<Integer> values = new ArrayList<>(deque);
        int from = Math.max(0, values.size() - maxSamples);
        List<Integer> window = values.subList(from, values.size());
        int n = window.size();
        return (window.get(n - 1) - window.get(0)) / (double) (n - 1);
    }

    private static double stdev(Deque<Double> deque, int maxSamples) {
        if (deque == null || deque.size() < 2) {
            return 0.0;
        }
        List<Double> values = new ArrayList<>(deque);
        int from = Math.max(0, values.size() - maxSamples);
        List<Double> window = values.subList(from, values.size());
        int n = window.size();
        double mean = 0.0;
        for (double v : window) {
            mean += v;
        }
        mean /= n;
        double sq = 0.0;
        for (double v : window) {
            sq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sq / n);
    }

    private static double mean(Deque<Double> deque) {
        if (deque == null || deque.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : deque) {
            sum += v;
        }
        return sum / deque.size();
    }

    private static double blowoutShare(Deque<Double> deque) {
        if (deque == null || deque.isEmpty()) {
            return 0.0;
        }
        int blowouts = 0;
        for (double v : deque) {
            if (Math.abs(v) >= BLOWOUT_THRESHOLD) {
                blowouts++;
            }
        }
        return (double) blowouts / deque.size();
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

    private static void updateSeatAggregates(C9_MatchParticipants.Participant p, double entryRating, RunningState state) {
        if (p.hallSeatNumber != null) {
            double[] hs = state.hallSeatAgg.computeIfAbsent(hallSeatKey(p.hallId, p.hallSeatNumber), k -> new double[2]);
            hs[0] += entryRating;
            hs[1] += 1;
        }
        double[] h = state.hallAgg.computeIfAbsent(p.hallId, k -> new double[2]);
        h[0] += entryRating;
        h[1] += 1;
        state.globalAgg[0] += entryRating;
        state.globalAgg[1] += 1;
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

    /** Assembles feature vectors with the NULL seat mean-imputed (for models that can't handle NaN, e.g. LogisticModel). */
    public static Vectors assemble(RawBoard rb, double n0) {
        return assemble(rb, n0, false);
    }

    /**
     * Assembles the model input vectors for one board.
     *
     * anti[] entries are exactly antisymmetric (swapping A and B negates
     * every entry), sym[] entries are exactly symmetric - together with the
     * two-stage model structure this guarantees P(A beats B) = 1 - P(B beats A).
     *
     * @param allowMissingSeat when true, the seat-diff dimension (anti[2])
     *        is NaN if either side's seat is NULL, instead of using the
     *        neutral {@link #SEAT_IMPUTED} fallback - lets a NaN-aware
     *        model (the GBM) learn a genuine split direction for missing
     *        seats rather than always treating them as "board 3".
     */
    public static Vectors assemble(RawBoard rb, double n0, boolean allowMissingSeat) {
        Side a = rb.a;
        Side b = rb.b;
        double effA = effectiveRating(a, n0);
        double effB = effectiveRating(b, n0);
        boolean seatMissing = a.seat == null || b.seat == null;
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
        anti[2] = (allowMissingSeat && seatMissing) ? Double.NaN : (seatA - seatB);
        anti[3] = Math.log1p(a.careerBoards) - Math.log1p(b.careerBoards);
        anti[4] = toRateA - toRateB;
        anti[5] = formA - formB;
        anti[6] = anti[1] * damp;
        anti[7] = a.ratingTrajectory - b.ratingTrajectory;
        anti[8] = a.hallRatingBias - b.hallRatingBias;
        anti[9] = Math.log1p(a.seasonBoards) - Math.log1p(b.seasonBoards);
        anti[10] = a.oppQualityBias - b.oppQualityBias;
        anti[11] = a.graphInsularity - b.graphInsularity;
        anti[12] = a.roundsMissedThisSeason - b.roundsMissedThisSeason;
        anti[13] = a.seatTrend - b.seatTrend;
        anti[14] = a.marginForm - b.marginForm;
        anti[15] = a.blowoutRate - b.blowoutRate;
        anti[16] = Math.log1p(a.walkoverReceivedCount) - Math.log1p(b.walkoverReceivedCount);
        anti[17] = a.oppTimeoutForcedRate - b.oppTimeoutForcedRate;
        anti[18] = a.vsOpponentHallRate - b.vsOpponentHallRate;
        anti[19] = a.ratingStability - b.ratingStability;

        double[] sym = new double[SYM_DIM];
        sym[0] = Math.abs(effA - effB) / 400.0;
        sym[1] = ((a.rd + b.rd) / 2.0) / 350.0;
        sym[2] = (Math.log1p(a.careerBoards) + Math.log1p(b.careerBoards)) / 2.0;
        sym[3] = rb.roundOrder / 10.0;
        sym[4] = a.hallId == b.hallId ? 1.0 : 0.0;
        sym[5] = rb.matchMaxScoreNorm;

        return new Vectors(sym, anti);
    }

    /**
     * A copy of {@code s} with only the seat overridden - used by the
     * lineup optimizer to ask "how does THIS candidate seat affect the
     * matchup" for a player whose other features (rating, career,
     * seatPrior, etc) come from their own real history. seatPrior itself
     * is deliberately NOT recomputed for the hypothetical seat (that would
     * require re-deriving hall/seat aggregates for a seat the player may
     * never have actually sat at) - only the direct seat-diff feature and
     * the effective-rating blend respond to the override. Documented
     * simplification, not a full live re-sweep.
     */
    public static Side withSeat(Side s, Integer newSeat) {
        return new Side(s.playerId, s.hallId, newSeat, s.rating, s.rd, s.careerBoards, s.careerTimeouts,
                s.sumOutcomeLast5, s.countLast5, s.seatPrior, s.ratingTrajectory, s.ratingStability,
                s.hallRatingBias, s.seasonBoards, s.oppQualityBias, s.graphInsularity, s.roundsMissedThisSeason,
                s.seatTrend, s.marginForm, s.blowoutRate, s.walkoverReceivedCount, s.oppTimeoutForcedRate,
                s.vsOpponentHallRate);
    }

    /** The board with A and B exchanged - used by symmetry tests and predictors. */
    public static RawBoard swapped(RawBoard rb) {
        return new RawBoard(rb.matchId, rb.roundSeq, rb.roundId, rb.year, rb.roundOrder,
                rb.b, rb.a, 1.0 - rb.outcomeA, rb.bTimedOut, rb.aTimedOut, rb.matchMaxScoreNorm);
    }
}
