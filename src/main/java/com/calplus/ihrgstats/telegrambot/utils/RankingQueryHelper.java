package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.B6_PlayerYearStatus;
import com.calplus.ihrgstats.databasemanager.D11_PlayerRatings;
import com.calplus.ihrgstats.databasemanager.D15_PlayerRatingSnapshots;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared cross-table read helpers for the ranking/comparison commands.
 * Lives here (not in a databasemanager/ class) because it combines data
 * from multiple tables - the per-table classes stay thin CRUD only.
 *
 * Rating-source convention (v2.0 whole-history recalculation era):
 * - "Latest overall" / current views read {@code player_ratings} - the
 *   best current estimates, rewritten by the whole-history recalculation.
 * - Any value displayed "as of a specific past round" reads
 *   {@code player_ratings_snapshot} - the immutable as-published-at-the-time
 *   record - falling back to {@code player_ratings} only when no snapshot
 *   row exists (data from before the snapshot feature, pre-backfill).
 */
public class RankingQueryHelper {

    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final D11_PlayerRatings playerRatings = new D11_PlayerRatings();
    private final D15_PlayerRatingSnapshots ratingSnapshots = new D15_PlayerRatingSnapshots();

    /**
     * Returns every active-this-year player's most recent rating at or
     * before the given round (use Integer.MAX_VALUE for "latest overall").
     * Players with no rating yet at that point are omitted.
     *
     * With a specific round limit this is a point-in-time query and reads
     * snapshots; with Integer.MAX_VALUE it reads the current (recalculated)
     * ratings.
     */
    public Map<String, D11_PlayerRatings.Rating> getLatestRatingsUpToRound(int year, int roundOrderLimit, int ratingTypeId) throws SQLException {
        boolean pointInTime = roundOrderLimit != Integer.MAX_VALUE;
        Map<String, D11_PlayerRatings.Rating> ratings = new HashMap<>();
        List<B6_PlayerYearStatus.Status> statuses = playerYearStatus.getActiveStatusesForYear(year);
        for (B6_PlayerYearStatus.Status status : statuses) {
            D11_PlayerRatings.Rating rating = null;
            if (pointInTime) {
                rating = toRating(ratingSnapshots.getLatestSnapshotUpToRound(status.playerId, year, roundOrderLimit, ratingTypeId));
            }
            if (rating == null) {
                rating = playerRatings.getLatestRatingUpToRound(status.playerId, year, roundOrderLimit, ratingTypeId);
            }
            if (rating != null) {
                ratings.put(status.playerId, rating);
            }
        }
        return ratings;
    }

    /**
     * Returns every player who has EVER played (not just this year's active
     * roster), each with their single most recent rating - backs the
     * "All Years" ranking views. Ratings are already whole-history
     * cumulative, so the VALUE shown is identical to the current-year view
     * for any still-active player; what changes is which players are
     * included at all (anyone from any past year, not just this year).
     */
    public Map<String, D11_PlayerRatings.Rating> getLatestRatingsAllYears(int ratingTypeId) throws SQLException {
        Map<String, D11_PlayerRatings.Rating> ratings = new HashMap<>();
        for (B6_PlayerYearStatus.Status status : playerYearStatus.getMostRecentStatusForEachPlayer()) {
            D11_PlayerRatings.Rating rating = playerRatings.getLatestRatingOverall(status.playerId, ratingTypeId);
            if (rating != null) {
                ratings.put(status.playerId, rating);
            }
        }
        return ratings;
    }

    /**
     * A player's rating AS OF a specific round - the point-in-time
     * (snapshot) value, falling back to the current player_ratings row when
     * no snapshot exists. Used by every command that displays a rating next
     * to a specific round (info/compare per-round tables).
     */
    public D11_PlayerRatings.Rating getPointInTimeRating(String playerId, int roundId, int ratingTypeId) throws SQLException {
        D11_PlayerRatings.Rating rating = toRating(ratingSnapshots.getSnapshot(playerId, roundId, ratingTypeId));
        if (rating == null) {
            rating = playerRatings.getRating(playerId, roundId, ratingTypeId);
        }
        return rating;
    }

    /**
     * Every player's point-in-time rating for ONE round in two queries: the
     * round's snapshot rows, overlaying the round's current player_ratings
     * rows (the fallback for players with no snapshot). Player-for-player
     * identical to calling {@link #getPointInTimeRating} in a loop, without
     * that loop's two queries per player - the info/compare report bodies
     * need this for every player of every displayed round.
     */
    public Map<String, D11_PlayerRatings.Rating> getPointInTimeRatingsForRound(int roundId, int ratingTypeId) throws SQLException {
        Map<String, D11_PlayerRatings.Rating> result = new HashMap<>();
        for (D11_PlayerRatings.Rating rating : playerRatings.getRatingsForRound(roundId, ratingTypeId)) {
            result.put(rating.playerId, rating);
        }
        for (D15_PlayerRatingSnapshots.Snapshot snapshot : ratingSnapshots.getSnapshotsForRound(roundId, ratingTypeId)) {
            result.put(snapshot.playerId, toRating(snapshot));
        }
        return result;
    }

    /** Adapts a snapshot row to the Rating carrier every command already consumes. */
    private static D11_PlayerRatings.Rating toRating(D15_PlayerRatingSnapshots.Snapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new D11_PlayerRatings.Rating(snapshot.playerId, snapshot.roundId, snapshot.ratingTypeId,
                snapshot.ratingValue, snapshot.ratingDeviation, snapshot.volatility);
    }

    /** Counts how many ratings are strictly higher than `myRating`, +1 (1-based rank). */
    public int calculateRank(Map<String, D11_PlayerRatings.Rating> allRatings, double myRating) {
        int higherCount = 0;
        for (D11_PlayerRatings.Rating rating : allRatings.values()) {
            if (rating.ratingValue > myRating) {
                higherCount++;
            }
        }
        return higherCount + 1;
    }
}
