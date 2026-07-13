package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.calculations.EloCalculator;
import com.calplus.ihrgstats.calculations.RatingRecalculator;
import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.utils.Constants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full ingestion pipeline for {year}_round_{N}.csv uploads - replaces the
 * legacy A1_PlayerStats.processRound(). Rounds are now plain sequential
 * integers scoped to a year (no more T16/T8/T4/T2 special casing, no more
 * bracket-transition detection - that legacy logic existed solely to
 * handle ambiguity from special round-name tokens, which cannot occur
 * anymore).
 */
public class RoundCsvProcessor {

    /** Filename convention: {year}_round_{N}.csv, or round_{N}.csv (year falls back to caller-supplied default). */
    private static final Pattern FILENAME_WITH_YEAR = Pattern.compile("^(\\d{4})_round_(\\d+)\\.csv$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_WITHOUT_YEAR = Pattern.compile("^round_(\\d+)\\.csv$", Pattern.CASE_INSENSITIVE);

    public interface MultiChoiceCallback {
        int requestChoice(String message, String[] options);
    }

    public interface UploadChatMessageCallback {
        void sendMessage(String message);
    }

    private final A1_Rounds rounds = new A1_Rounds();
    private final A2_MatchTypes matchTypes = new A2_MatchTypes();
    private final A3_Halls halls = new A3_Halls();
    private final B4_Players players = new B4_Players();
    private final C8_Matches matches = new C8_Matches();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final D11_PlayerRatings playerRatings = new D11_PlayerRatings();
    private final D15_PlayerRatingSnapshots ratingSnapshots = new D15_PlayerRatingSnapshots();
    private final PlayerIdentityResolver identityResolver = new PlayerIdentityResolver();

    private MultiChoiceCallback multiChoiceCallback;
    private UploadChatMessageCallback uploadChatCallback;

    public void setMultiChoiceCallback(MultiChoiceCallback callback) {
        this.multiChoiceCallback = callback;
        identityResolver.setMultiChoiceCallback(callback::requestChoice);
    }

    public void setUploadChatCallback(UploadChatMessageCallback callback) {
        this.uploadChatCallback = callback;
    }

    /** Result of a filename parse - year is null if not present in the filename. */
    public static class ParsedFilename {
        public final Integer year;
        public final int roundOrder;
        public final boolean matched;

        ParsedFilename(Integer year, int roundOrder, boolean matched) {
            this.year = year;
            this.roundOrder = roundOrder;
            this.matched = matched;
        }
    }

    /** Parses a round CSV filename. Returns matched=false if it isn't a round file at all. */
    public static ParsedFilename parseFilename(String fileName) {
        Matcher withYear = FILENAME_WITH_YEAR.matcher(fileName);
        if (withYear.matches()) {
            return new ParsedFilename(Integer.parseInt(withYear.group(1)), Integer.parseInt(withYear.group(2)), true);
        }
        Matcher withoutYear = FILENAME_WITHOUT_YEAR.matcher(fileName);
        if (withoutYear.matches()) {
            return new ParsedFilename(null, Integer.parseInt(withoutYear.group(1)), true);
        }
        return new ParsedFilename(null, -1, false);
    }

    static class GameRow {
        String name1;
        String hall1; // may be blank for a WALKOVER side
        String score1; // may be blank for a WALKOVER row
        String name2;
        String hall2;
        String score2;

        boolean isWalkover() {
            return name1.equalsIgnoreCase("WALKOVER") || name2.equalsIgnoreCase("WALKOVER");
        }

        boolean isTimeout() {
            return score1.equalsIgnoreCase("TIMEOUT") || score2.equalsIgnoreCase("TIMEOUT");
        }
    }

    /**
     * Processes a round_N.csv file for the given (year, roundOrder).
     * @return true on success, false on validation failure or user cancellation
     */
    public boolean processRound(String csvFilePath, int year, int roundOrder, String nowTimestamp) {
        File csvFile = new File(csvFilePath);
        if (!csvFile.exists()) {
            notify("🔴", String.format("round_%d.csv file not found at: %s", roundOrder, csvFilePath));
            return false;
        }

        List<GameRow> games;
        try {
            games = parseAndValidateCSV(csvFilePath);
        } catch (Exception e) {
            notify("🔴", "CSV validation failed: " + e.getMessage());
            return false;
        }

        try {
            int latestRoundOrder = rounds.getLatestRoundOrder(year);

            if (roundOrder > latestRoundOrder + 1) {
                notify("🔴", String.format(
                    "Cannot process round %d for %d - rounds must be processed in order (last processed: %d).",
                    roundOrder, year, latestRoundOrder));
                return false;
            }

            boolean isReprocess = roundOrder <= latestRoundOrder;
            if (isReprocess) {
                int choice = requestMultiChoice(String.format(
                    "⚠️ Round Already Processed\n\nRound %d of %d has already been processed!\n\n" +
                    "If you continue:\n- Round %d will be reprocessed with the new data\n" +
                    "- ALL rounds after round %d (for %d) will be DELETED\n" +
                    "- You will need to re-upload those rounds again\n\nDo you want to continue?",
                    roundOrder, year, roundOrder, roundOrder, year),
                    new String[]{"Continue and reprocess", "Cancel"});
                if (choice != 0) {
                    notify("🟡", String.format("Round %d reprocessing cancelled by user.", roundOrder));
                    return false;
                }
                // NOTE: deleteFutureRounds() deliberately does NOT run here -
                // see the single deletion block below, after every remaining
                // interactive step has a chance to cancel.
            }

            // For a reprocess, the round row is GUARANTEED to already exist
            // (that's what "reprocess" means) - fetching (never creating) it
            // now is safe even before the remaining cancellable dialogs
            // below, and is needed to look up any already-assigned
            // match_type_id before it's cleared by the delete block further
            // down (a previously-fixed bug: that lookup used to run after
            // the delete and always returned null on reprocess). For a
            // FIRST-TIME upload, the round row must NOT be created yet -
            // creating it here and then cancelling a later dialog would
            // leave a permanent, empty round row with nothing ever written
            // to it (the actual creation happens further down, once every
            // cancellable step has succeeded).
            A1_Rounds.Round existingRound = isReprocess ? rounds.getRoundByYearAndOrder(year, roundOrder) : null;
            Integer matchTypeId = existingRound != null ? matches.getMatchTypeIdForRound(existingRound.id) : null;

            // Only urgently needed if this round has a walkover (walkover
            // default scoring needs max_score). Otherwise fully deferrable.
            boolean hasWalkover = games.stream().anyMatch(GameRow::isWalkover);
            if (matchTypeId == null && hasWalkover) {
                matchTypeId = resolveMatchTypeInteractively();
                if (matchTypeId == null) {
                    notify("🔴", "Round processing cancelled - a match type with a max_score must be assigned before a walkover can be scored.");
                    return false;
                }
            }
            Double maxScore = matchTypeId != null ? matchTypes.getMatchTypeById(matchTypeId).maxScore : null;

            // Resolve every player in this CSV to a permanent player_id.
            Map<GameRow, String> player1Ids = new LinkedHashMap<>();
            Map<GameRow, Integer> player1HallIds = new LinkedHashMap<>();
            Map<GameRow, String> player2Ids = new LinkedHashMap<>();
            Map<GameRow, Integer> player2HallIds = new LinkedHashMap<>();

            for (GameRow game : games) {
                boolean p1Walkover = game.name1.equalsIgnoreCase("WALKOVER");
                boolean p2Walkover = game.name2.equalsIgnoreCase("WALKOVER");

                if (p1Walkover) {
                    player1Ids.put(game, B4_Players.WALKOVER_PLAYER_ID);
                    player1HallIds.put(game, resolveHallOrUnknown(game.hall1).id);
                } else {
                    A3_Halls.Hall hall = requireHall(game.hall1);
                    PlayerIdentityResolver.ResolutionResult res = identityResolver.resolvePlayer(game.name1, hall, year, nowTimestamp);
                    if (res.cancelled) {
                        notify("🟡", "Round processing cancelled during player identity resolution.");
                        return false;
                    }
                    player1Ids.put(game, res.playerId);
                    player1HallIds.put(game, res.hallId);
                }

                if (p2Walkover) {
                    player2Ids.put(game, B4_Players.WALKOVER_PLAYER_ID);
                    player2HallIds.put(game, resolveHallOrUnknown(game.hall2).id);
                } else {
                    A3_Halls.Hall hall = requireHall(game.hall2);
                    PlayerIdentityResolver.ResolutionResult res = identityResolver.resolvePlayer(game.name2, hall, year, nowTimestamp);
                    if (res.cancelled) {
                        notify("🟡", "Round processing cancelled during player identity resolution.");
                        return false;
                    }
                    player2Ids.put(game, res.playerId);
                    player2HallIds.put(game, res.hallId);
                }
            }

            // Reject a round where the same REAL player appears more than
            // once this round - whether that's one row's two names both
            // resolving to the same player (a same-cell typo), or the same
            // player appearing in two separate rows (a duplicate CSV row).
            // Without this, a duplicate write hits the (match_id, player_id)
            // primary key on the SECOND insertParticipant call and throws
            // mid-write - on a reprocess, that happens AFTER the destructive
            // deletes below have already run, so the round would be left
            // emptied with a half-written replacement. Checked here (after
            // resolution, before any delete) so a rejection is always safe -
            // nothing has been destroyed yet. The WALKOVER sentinel is exempt
            // since it legitimately repeats once per walkover row.
            Map<String, Integer> firstRowOfPlayer = new LinkedHashMap<>();
            int rowNumber = 0;
            for (GameRow game : games) {
                rowNumber++;
                for (String playerId : new String[]{player1Ids.get(game), player2Ids.get(game)}) {
                    if (B4_Players.WALKOVER_PLAYER_ID.equals(playerId)) continue;
                    Integer priorRow = firstRowOfPlayer.putIfAbsent(playerId, rowNumber);
                    if (priorRow != null) {
                        notify("🔴", String.format(
                            "Round processing cancelled - the same player appears more than once this round (row %d and row %d). This is likely a data entry error and must be fixed manually.",
                            priorRow, rowNumber));
                        return false;
                    }
                }
            }

            // Every interactive step above (the reprocess confirmation, the
            // match-type dialog, and every player-identity-resolution dialog)
            // has now succeeded without cancellation - only now is it safe to
            // create the round row (first-time upload) or run the destructive
            // deletes (reprocess). Previously the round row was created (and
            // deletes ran) BEFORE the match-type/identity dialogs, so
            // cancelling one of those left either a permanent empty round
            // row (first-time upload) or the round emptied and all later
            // rounds already gone with nothing re-written (reprocess), even
            // though "Cancel" was presented as safe both times.
            A1_Rounds.Round round = existingRound != null ? existingRound : rounds.getOrCreateRound(year, roundOrder, nowTimestamp);
            if (isReprocess) {
                rounds.deleteFutureRounds(year, roundOrder);
                // Clear this round's OWN data (keep the round row so admin-set
                // metadata like round_datetime survives). Snapshots are cleared
                // too - the underlying match data is changing, so this round's
                // point-in-time record legitimately gets re-taken.
                matches.deleteMatchesForRound(round.id);
                playerRatings.deleteRatingsForRound(round.id);
                ratingSnapshots.deleteSnapshotsForRound(round.id);
            }

            // Seating: a running per-hall counter across the whole round (skips WALKOVER side, matching legacy).
            Map<String, Integer> hallSeatCounter = new HashMap<>();

            // Create matches + participants, and collect real (non-walkover) games for ELO.
            List<EloCalculator.Game> eloGames = new ArrayList<>();
            Set<String> allPlayerIdsThisRound = new HashSet<>();

            for (GameRow game : games) {
                boolean p1Walkover = game.name1.equalsIgnoreCase("WALKOVER");
                boolean p2Walkover = game.name2.equalsIgnoreCase("WALKOVER");
                boolean p1Timeout = !p1Walkover && !p2Walkover && game.score1.equalsIgnoreCase("TIMEOUT");
                boolean p2Timeout = !p1Walkover && !p2Walkover && game.score2.equalsIgnoreCase("TIMEOUT");
                String p1Id = player1Ids.get(game);
                String p2Id = player2Ids.get(game);
                int p1HallId = player1HallIds.get(game);
                int p2HallId = player2HallIds.get(game);

                int matchId = matches.createMatch(round.id, matchTypeId, null, round.roundDatetime, nowTimestamp);

                double score1;
                double score2;
                double outcome1;
                double outcome2;

                if (p1Walkover || p2Walkover) {
                    double defaultScore = MatchScoreUtils.computeWalkoverDefaultScore(maxScore != null ? maxScore : 0.0);
                    if (p1Walkover) {
                        score1 = 0.0;
                        score2 = defaultScore;
                        outcome1 = 0.0;
                        outcome2 = 1.0;
                    } else {
                        score1 = defaultScore;
                        score2 = 0.0;
                        outcome1 = 1.0;
                        outcome2 = 0.0;
                    }
                } else if (p1Timeout || p2Timeout) {
                    // TIMEOUT: unlike WALKOVER, both players actually played -
                    // the timed-out side simply has no final score to report.
                    // The winner's cell may still carry a real recorded score
                    // (kept as-is); the timed-out side's score is always 0.
                    if (p1Timeout) {
                        score1 = 0.0;
                        score2 = game.score2.trim().isEmpty() ? 0.0 : Double.parseDouble(game.score2.trim());
                        outcome1 = 0.0;
                        outcome2 = 1.0;
                    } else {
                        score2 = 0.0;
                        score1 = game.score1.trim().isEmpty() ? 0.0 : Double.parseDouble(game.score1.trim());
                        outcome1 = 1.0;
                        outcome2 = 0.0;
                    }
                } else {
                    score1 = Double.parseDouble(game.score1.trim());
                    score2 = Double.parseDouble(game.score2.trim());
                    if (score1 > score2) {
                        outcome1 = 1.0;
                        outcome2 = 0.0;
                    } else if (score1 < score2) {
                        outcome1 = 0.0;
                        outcome2 = 1.0;
                    } else {
                        outcome1 = 0.5;
                        outcome2 = 0.5;
                    }
                }

                Integer seat1 = null;
                if (!p1Walkover) {
                    String hallKey = String.valueOf(p1HallId);
                    seat1 = hallSeatCounter.merge(hallKey, 1, Integer::sum);
                }
                Integer seat2 = null;
                if (!p2Walkover) {
                    String hallKey = String.valueOf(p2HallId);
                    seat2 = hallSeatCounter.merge(hallKey, 1, Integer::sum);
                }

                participants.insertParticipant(matchId, p1Id, p1HallId, seat1,
                        p1Walkover ? C9_MatchParticipants.PARTICIPATION_WALKOVER
                                : p1Timeout ? C9_MatchParticipants.PARTICIPATION_TIMEOUT
                                : C9_MatchParticipants.PARTICIPATION_STANDARD,
                        score1, outcome1, nowTimestamp);
                participants.insertParticipant(matchId, p2Id, p2HallId, seat2,
                        p2Walkover ? C9_MatchParticipants.PARTICIPATION_WALKOVER
                                : p2Timeout ? C9_MatchParticipants.PARTICIPATION_TIMEOUT
                                : C9_MatchParticipants.PARTICIPATION_STANDARD,
                        score2, outcome2, nowTimestamp);

                if (!p1Walkover) allPlayerIdsThisRound.add(p1Id);
                if (!p2Walkover) allPlayerIdsThisRound.add(p2Id);

                // Walkover games do not affect ELO, matching legacy. TIMEOUT
                // games are real, rated results and are NOT excluded.
                if (!p1Walkover && !p2Walkover) {
                    eloGames.add(new EloCalculator.Game(p1Id, p2Id, outcome1, roundOrder));
                }
            }

            // Non-blocking warning (not a validation failure) if a hall fielded more
            // players than expected this round - the historical "max 5 players per
            // hall" requirement, previously defined (Constants.Validation.MAX_PLAYERS_PER_HALL)
            // but never enforced anywhere.
            for (Map.Entry<String, Integer> entry : hallSeatCounter.entrySet()) {
                if (entry.getValue() > Constants.Validation.MAX_PLAYERS_PER_HALL) {
                    int hallId = Integer.parseInt(entry.getKey());
                    A3_Halls.Hall hall = halls.getHallById(hallId);
                    String hallName = hall != null ? hall.hallName : ("hall_id=" + hallId);
                    notify("🟡", String.format(
                            "Round %d: hall %s fielded %d players this round, exceeding the expected max of %d.",
                            roundOrder, hallName, entry.getValue(), Constants.Validation.MAX_PLAYERS_PER_HALL));
                }
            }

            // Carry forward players who are already active this year but sat out this
            // specific round, PROVIDED their hall has at least one participant this
            // round (mirrors legacy's handleMissingPlayers hall-played gating). This
            // is a real Glicko-2 requirement (their RD must still grow), not carried
            // forward as a copied value.
            Set<Integer> hallsPlayingThisRound = new HashSet<>();
            hallsPlayingThisRound.addAll(player1HallIds.values());
            hallsPlayingThisRound.addAll(player2HallIds.values());

            Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
            Map<String, EloCalculator.Glicko2Rating> initialRatings = new HashMap<>();

            for (Integer hallId : hallsPlayingThisRound) {
                if (hallId == null) continue;
                A3_Halls.Hall hall = halls.getHallById(hallId);
                if (hall == null || hall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) continue;
                for (var status : getActiveHallPlayersForYear(hallId, year)) {
                    if (!allPlayerIdsThisRound.contains(status.playerId)) {
                        allPlayerIdsThisRound.add(status.playerId);
                    }
                }
            }

            // Seed every involved player's starting rating for this round.
            for (String playerId : allPlayerIdsThisRound) {
                initialRatings.put(playerId, resolveSeedRating(playerId, year, roundOrder, trueEloTypeId));
            }

            EloCalculator.Glicko2Result eloResult = EloCalculator.calculateGlicko2TrueElo(
                    eloGames, allPlayerIdsThisRound, initialRatings, List.of(roundOrder));

            // Persist this round's single-round result twice:
            // - player_ratings: the current best estimate (superseded by the
            //   whole-history recalculation below);
            // - player_ratings_snapshot: the immutable point-in-time record of
            //   what was published when this round was processed - recalc
            //   never touches it.
            Map<String, EloCalculator.Glicko2Rating> finalRatings = eloResult.ratingsByRound.getOrDefault(roundOrder, Map.of());
            for (Map.Entry<String, EloCalculator.Glicko2Rating> entry : finalRatings.entrySet()) {
                EloCalculator.Glicko2Rating rating = entry.getValue();
                playerRatings.insertRating(entry.getKey(), round.id, trueEloTypeId, rating.rating, rating.rd, rating.volatility, nowTimestamp);
                ratingSnapshots.insertSnapshot(entry.getKey(), round.id, trueEloTypeId, rating.rating, rating.rd, rating.volatility, nowTimestamp);
            }

            notify("🟢", String.format("round_%d.csv processed successfully for %d. %d matches, %d players rated.",
                    roundOrder, year, games.size(), finalRatings.size()));

            // Whole-history recalculation: replay ALL stored rounds across all
            // years so later results refine earlier rounds' estimates. The
            // round itself is already fully committed - a recalc failure must
            // not roll it back or fail the upload.
            try {
                RatingRecalculator.RecalcResult recalc = new RatingRecalculator().recalculateAll(nowTimestamp);
                notify("🟢", String.format(
                        "Whole-history recalculation complete: %d rounds recalculated across all years (%d passes, %d rating rows updated).",
                        recalc.roundsRecalculated, recalc.passes, recalc.ratingRowsWritten));
            } catch (SQLException e) {
                notify("🟠", "Round data was saved, but the whole-history recalculation failed: " + e.getMessage()
                        + " - run /recalculate to retry.");
            }

            promptForRoundDatetimeIfMissing(round, nowTimestamp);

            return true;

        } catch (PlayerIdentityResolver.HallMismatchException e) {
            notify("🔴", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            // requireHall() throws this for a hall name that doesn't exist -
            // previously uncaught here, it propagated all the way out as a
            // generic crash instead of the same clean "processing failed"
            // notification every other expected validation failure gets.
            notify("🔴", "Round processing failed: " + e.getMessage());
            return false;
        } catch (SQLException e) {
            notify("🔴", "Database update failed: " + e.getMessage());
            return false;
        }
    }

    private List<B6_PlayerYearStatus.Status> getActiveHallPlayersForYear(int hallId, int year) throws SQLException {
        return new B6_PlayerYearStatus().getStatusesForHallAndYear(hallId, year);
    }

    private EloCalculator.Glicko2Rating resolveSeedRating(String playerId, int year, int roundOrder, int ratingTypeId) throws SQLException {
        if (roundOrder > 1) {
            D11_PlayerRatings.Rating withinYear = playerRatings.getLatestRatingUpToRound(playerId, year, roundOrder - 1, ratingTypeId);
            if (withinYear != null) {
                return new EloCalculator.Glicko2Rating(withinYear.ratingValue, withinYear.ratingDeviation, withinYear.volatility);
            }
        }
        D11_PlayerRatings.Rating crossYear = playerRatings.getLatestRatingBeforeYear(playerId, year, ratingTypeId);
        if (crossYear != null) {
            return new EloCalculator.Glicko2Rating(crossYear.ratingValue, crossYear.ratingDeviation, crossYear.volatility);
        }
        return new EloCalculator.Glicko2Rating();
    }

    private A3_Halls.Hall requireHall(String hallName) throws SQLException, IllegalArgumentException {
        A3_Halls.Hall hall = halls.getHallByName(hallName);
        if (hall == null) {
            throw new IllegalArgumentException("Unknown hall: '" + hallName + "'");
        }
        return hall;
    }

    /** Resolves a possibly-blank hall string to the real hall, or the reserved "unknown" hall if blank. */
    private A3_Halls.Hall resolveHallOrUnknown(String hallName) throws SQLException {
        if (hallName == null || hallName.trim().isEmpty()) {
            return halls.getHallByCode(A3_Halls.UNKNOWN_HALL_CODE);
        }
        A3_Halls.Hall hall = halls.getHallByName(hallName.trim());
        return hall != null ? hall : halls.getHallByCode(A3_Halls.UNKNOWN_HALL_CODE);
    }

    private Integer resolveMatchTypeInteractively() throws SQLException {
        List<A2_MatchTypes.MatchType> allTypes = matchTypes.getAllMatchTypes();
        if (allTypes.isEmpty()) {
            return null; // caller reports the "must create a match type first" error
        }
        String[] options = new String[allTypes.size() + 1];
        for (int i = 0; i < allTypes.size(); i++) {
            options[i] = allTypes.get(i).typeName + " (max_score=" + allTypes.get(i).maxScore + ")";
        }
        options[allTypes.size()] = "Cancel";
        int choice = requestMultiChoice(
            "⚠️ This round contains a WALKOVER, which needs a match type's max_score to compute a default score.\n\n" +
            "Select a match type for this round:", options);
        if (choice < 0 || choice >= allTypes.size()) {
            return null;
        }
        return allTypes.get(choice).id;
    }

    private void promptForRoundDatetimeIfMissing(A1_Rounds.Round round, String nowTimestamp) {
        if (round.roundDatetime != null) {
            return;
        }
        // Best-effort - the round is already fully committed by this point, so a
        // timeout/skip here must not roll anything back.
        notify("🔵", String.format(
            "Round %d has no datetime set yet. Use the round management command to set it whenever convenient.",
            round.roundOrder));
    }

    /**
     * Expected header: name1,hall1,score1,name2,hall2,score2.
     * score1/score2 hold each side's RAW board score (not a margin) for
     * standard games. For a WALKOVER row (name1 or name2 == "WALKOVER",
     * exactly one side), hall/score for that side are optional and, when
     * left blank, the app computes a default walkover score automatically -
     * matching legacy's convention exactly (see SAMPLE FILES). For a TIMEOUT
     * row (both players real, but one side's score cell is literally
     * "TIMEOUT"), the other side's cell holds their real score/margin as
     * normal (or is left blank for a 0-0 fallback) - unlike WALKOVER, a
     * TIMEOUT match is a real, rated result; only the timed-out side is
     * flagged, never the winner.
     */
    List<GameRow> parseAndValidateCSV(String csvFilePath) throws Exception {
        List<GameRow> games = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
            String line;
            int lineNumber = 0;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue;

                String[] parts = CsvLineParser.parseLine(line);

                if (isHeader) {
                    if (parts.length != 6) {
                        throw new Exception("Invalid CSV format: Header must have exactly 6 columns (name1,hall1,score1,name2,hall2,score2)");
                    }
                    String[] expected = {"name1", "hall1", "score1", "name2", "hall2", "score2"};
                    for (int i = 0; i < 6; i++) {
                        if (!parts[i].trim().equalsIgnoreCase(expected[i])) {
                            throw new Exception(String.format("Invalid CSV header: Expected '%s' at column %d, found '%s'", expected[i], i + 1, parts[i]));
                        }
                    }
                    isHeader = false;
                    continue;
                }

                if (parts.length != 6) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Expected 6 columns, found %d", lineNumber, parts.length));
                }

                GameRow game = new GameRow();
                game.name1 = parts[0].trim();
                game.hall1 = parts[1].trim();
                game.score1 = parts[2].trim();
                game.name2 = parts[3].trim();
                game.hall2 = parts[4].trim();
                game.score2 = parts[5].trim();

                if (game.name1.isEmpty() || game.name2.isEmpty()) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Player names cannot be empty", lineNumber));
                }

                boolean p1Walkover = game.name1.equalsIgnoreCase("WALKOVER");
                boolean p2Walkover = game.name2.equalsIgnoreCase("WALKOVER");

                if (p1Walkover && p2Walkover) {
                    throw new Exception(String.format("Invalid CSV format at line %d: Both players cannot be WALKOVER", lineNumber));
                }

                if (p1Walkover) {
                    if (game.hall2.isEmpty()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: Non-WALKOVER player must have a hall", lineNumber));
                    }
                    if (game.isTimeout()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: A row cannot be both WALKOVER and TIMEOUT", lineNumber));
                    }
                } else if (p2Walkover) {
                    if (game.hall1.isEmpty()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: Non-WALKOVER player must have a hall", lineNumber));
                    }
                    if (game.isTimeout()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: A row cannot be both WALKOVER and TIMEOUT", lineNumber));
                    }
                } else if (game.isTimeout()) {
                    if (game.hall1.isEmpty() || game.hall2.isEmpty()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: Hall names cannot be empty for regular games", lineNumber));
                    }
                    boolean s1Timeout = game.score1.equalsIgnoreCase("TIMEOUT");
                    boolean s2Timeout = game.score2.equalsIgnoreCase("TIMEOUT");
                    if (s1Timeout && s2Timeout) {
                        throw new Exception(String.format("Invalid CSV format at line %d: Both players cannot be TIMEOUT", lineNumber));
                    }
                    String winnerScore = s1Timeout ? game.score2 : game.score1;
                    if (!winnerScore.isEmpty()) {
                        double parsedWinnerScore;
                        try {
                            parsedWinnerScore = Double.parseDouble(winnerScore);
                        } catch (NumberFormatException e) {
                            throw new Exception(String.format("Invalid CSV format at line %d: score1/score2 must be numeric or TIMEOUT", lineNumber));
                        }
                        if (!Double.isFinite(parsedWinnerScore)) {
                            throw new Exception(String.format("Invalid CSV format at line %d: score1/score2 must be a finite number", lineNumber));
                        }
                    }
                } else {
                    if (game.hall1.isEmpty() || game.hall2.isEmpty()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: Hall names cannot be empty for regular games", lineNumber));
                    }
                    if (game.score1.isEmpty() || game.score2.isEmpty()) {
                        throw new Exception(String.format("Invalid CSV format at line %d: Both score1 and score2 are required for standard games", lineNumber));
                    }
                    double parsedScore1;
                    double parsedScore2;
                    try {
                        parsedScore1 = Double.parseDouble(game.score1);
                        parsedScore2 = Double.parseDouble(game.score2);
                    } catch (NumberFormatException e) {
                        throw new Exception(String.format("Invalid CSV format at line %d: score1/score2 must be numeric", lineNumber));
                    }
                    if (!Double.isFinite(parsedScore1) || !Double.isFinite(parsedScore2)) {
                        throw new Exception(String.format("Invalid CSV format at line %d: score1/score2 must be finite numbers", lineNumber));
                    }
                }

                games.add(game);
            }

            if (games.isEmpty()) {
                throw new Exception("CSV file contains no data rows");
            }

        } catch (IOException e) {
            throw new Exception("Error reading CSV file: " + e.getMessage());
        }

        return games;
    }

    private int requestMultiChoice(String message, String[] options) {
        if (multiChoiceCallback != null) {
            return multiChoiceCallback.requestChoice(message, options);
        }
        System.out.println(message);
        for (int i = 0; i < options.length; i++) {
            System.out.println("  " + (i + 1) + ". " + options[i]);
        }
        return options.length - 1;
    }

    private void notify(String emote, String message) {
        System.out.println(emote + " [RoundCsvProcessor] " + message);
        if (uploadChatCallback != null) {
            uploadChatCallback.sendMessage(emote + " " + message);
        }
    }
}
