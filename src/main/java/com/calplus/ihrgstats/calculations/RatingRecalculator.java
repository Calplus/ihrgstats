package com.calplus.ihrgstats.calculations;

import com.calplus.ihrgstats.databasemanager.*;

import java.sql.SQLException;
import java.util.*;

/**
 * Whole-history (WHR-style) rating recalculation over EVERY stored round
 * across EVERY year, in chronological (year, round_order) order.
 *
 * Replays all standard (non-walkover) matches from the database through
 * {@link EloCalculator#calculateWholeHistory} with {@link #PASSES} passes,
 * so information from later rounds back-propagates into earlier rounds'
 * estimates, then rewrites {@code player_ratings} to match. Year gaps are
 * handled naturally: every round a player misses (including entire missed
 * years) applies one Glicko-2 inactivity RD-growth step to their in-memory
 * trajectory, so uncertainty widens across seasons they sat out.
 *
 * {@code player_ratings_snapshot} (the as-published-at-the-time record) is
 * NEVER touched by this class - see {@link D15_PlayerRatingSnapshots}.
 *
 * Which players get a stored rating row for a round mirrors the live
 * ingestion rule exactly: everyone who played the round, plus every active
 * player of a hall that played the round PROVIDED they had already appeared
 * in some earlier round of that year (matching how player_year_status rows
 * come into existence incrementally during a season).
 */
public class RatingRecalculator {

    /** Number of full sweeps over the history, per the v2.0 design decision. */
    public static final int PASSES = 5;

    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final D11_PlayerRatings playerRatings = new D11_PlayerRatings();

    /** Summary of one recalculation run. */
    public static class RecalcResult {
        public final int roundsRecalculated;
        public final int playersRated;
        public final int ratingRowsWritten;
        public final int passes;

        RecalcResult(int roundsRecalculated, int playersRated, int ratingRowsWritten, int passes) {
            this.roundsRecalculated = roundsRecalculated;
            this.playersRated = playersRated;
            this.ratingRowsWritten = ratingRowsWritten;
            this.passes = passes;
        }
    }

    /** One stored round in global chronological order. */
    private static class RoundRef {
        final A1_Rounds.Round round;
        final int seqIndex; // global chronological index across all years
        final Set<String> playersPlaying = new LinkedHashSet<>();
        final Set<Integer> hallsPlaying = new LinkedHashSet<>();
        final Set<String> ratedSet = new LinkedHashSet<>();

        RoundRef(A1_Rounds.Round round, int seqIndex) {
            this.round = round;
            this.seqIndex = seqIndex;
        }
    }

    /**
     * Recalculates all rounds of all years and rewrites player_ratings.
     * Safe to call with an empty database (returns a zero result).
     */
    public RecalcResult recalculateAll(String nowTimestamp) throws SQLException {
        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            throw new SQLException("Rating type '" + D10_RatingTypes.TRUE_ELO + "' is not seeded - cannot recalculate.");
        }

        // Migration safety net: rounds processed before the snapshot feature
        // existed have no point-in-time record yet - preserve their currently
        // stored (as-published) values BEFORE this recalculation rewrites them.
        new D15_PlayerRatingSnapshots().backfillMissingRounds(nowTimestamp);

        A3_Halls.Hall unknownHall = halls.getHallByCode(A3_Halls.UNKNOWN_HALL_CODE);
        Integer unknownHallId = unknownHall != null ? unknownHall.id : null;

        // 1. All rounds across all years, chronological.
        List<RoundRef> roundRefs = new ArrayList<>();
        for (int year : rounds.getAllYears()) {
            for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
                roundRefs.add(new RoundRef(round, roundRefs.size()));
            }
        }
        if (roundRefs.isEmpty()) {
            return new RecalcResult(0, 0, 0, PASSES);
        }

        // 2. Load participants, build games, and track per-year first appearances.
        List<EloCalculator.Game> games = new ArrayList<>();
        // year -> playerId -> first round_order they appeared in that year
        Map<Integer, Map<String, Integer>> firstAppearance = new HashMap<>();

        for (RoundRef ref : roundRefs) {
            Map<Integer, List<C9_MatchParticipants.Participant>> byMatch = new LinkedHashMap<>();
            for (C9_MatchParticipants.Participant p : participants.getParticipantsForRound(ref.round.id)) {
                byMatch.computeIfAbsent(p.matchId, k -> new ArrayList<>()).add(p);
            }

            for (List<C9_MatchParticipants.Participant> matchParticipants : byMatch.values()) {
                boolean hasWalkover = false;
                for (C9_MatchParticipants.Participant p : matchParticipants) {
                    // A hall counts as "playing this round" even when its side
                    // is the WLKOVR sentinel (matching live ingestion, where a
                    // walkover row's real hall still carries its sitting
                    // players forward). The unknown/ZZ hall never counts.
                    if (unknownHallId == null || p.hallId != unknownHallId) {
                        ref.hallsPlaying.add(p.hallId);
                    }
                    if (B4_Players.WALKOVER_PLAYER_ID.equals(p.playerId)
                            || C9_MatchParticipants.PARTICIPATION_WALKOVER.equals(p.participationType)) {
                        hasWalkover = true;
                        continue;
                    }
                    ref.playersPlaying.add(p.playerId);
                    firstAppearance
                        .computeIfAbsent(ref.round.year, k -> new HashMap<>())
                        .merge(p.playerId, ref.round.roundOrder, Math::min);
                }
                // Walkover matches never affect ratings, matching live ingestion.
                if (!hasWalkover && matchParticipants.size() == 2) {
                    C9_MatchParticipants.Participant p1 = matchParticipants.get(0);
                    C9_MatchParticipants.Participant p2 = matchParticipants.get(1);
                    games.add(new EloCalculator.Game(p1.playerId, p2.playerId, p1.outcome, ref.seqIndex));
                }
            }
        }

        // 3. Rated set per round: players who played it, plus active same-hall
        //    players who had already appeared earlier that year.
        Set<String> allPlayerIds = new HashSet<>();
        for (RoundRef ref : roundRefs) {
            ref.ratedSet.addAll(ref.playersPlaying);
            Map<String, Integer> yearFirstAppearance =
                    firstAppearance.getOrDefault(ref.round.year, Map.of());
            for (int hallId : ref.hallsPlaying) {
                for (B6_PlayerYearStatus.Status status : playerYearStatus.getStatusesForHallAndYear(hallId, ref.round.year)) {
                    Integer first = yearFirstAppearance.get(status.playerId);
                    if (first != null && first <= ref.round.roundOrder) {
                        ref.ratedSet.add(status.playerId);
                    }
                }
            }
            allPlayerIds.addAll(ref.ratedSet);
        }

        // 4. Full refit from scratch: everyone starts at the default rating.
        List<Integer> sequence = new ArrayList<>();
        for (RoundRef ref : roundRefs) {
            sequence.add(ref.seqIndex);
        }
        EloCalculator.Glicko2Result result = EloCalculator.calculateWholeHistory(
                games, allPlayerIds, Map.of(), sequence, PASSES);

        // 5. Rewrite player_ratings so each round's rows exactly mirror its
        //    rated set - one connection, one transaction per round (was one
        //    fresh connection per delete/insert statement, 600+ per recalc).
        List<D11_PlayerRatings.RoundRatings> writes = new ArrayList<>();
        for (RoundRef ref : roundRefs) {
            Map<String, EloCalculator.Glicko2Rating> roundRatings =
                    result.ratingsByRound.getOrDefault(ref.seqIndex, Map.of());
            List<D11_PlayerRatings.Rating> rows = new ArrayList<>();
            for (String playerId : ref.ratedSet) {
                EloCalculator.Glicko2Rating rating = roundRatings.get(playerId);
                if (rating == null) {
                    continue;
                }
                rows.add(new D11_PlayerRatings.Rating(playerId, ref.round.id, trueEloTypeId,
                        rating.rating, rating.rd, rating.volatility));
            }
            writes.add(new D11_PlayerRatings.RoundRatings(ref.round.id, rows));
        }
        int rowsWritten = playerRatings.replaceRatingsForRounds(writes, trueEloTypeId, nowTimestamp);

        return new RecalcResult(roundRefs.size(), allPlayerIds.size(), rowsWritten, PASSES);
    }
}
