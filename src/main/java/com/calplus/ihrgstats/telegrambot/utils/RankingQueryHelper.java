package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.B6_PlayerYearStatus;
import com.calplus.ihrgstats.databasemanager.D11_PlayerRatings;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared cross-table read helpers for the ranking/comparison commands.
 * Lives here (not in a databasemanager/ class) because it combines data
 * from multiple tables - the per-table classes stay thin CRUD only.
 */
public class RankingQueryHelper {

    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final D11_PlayerRatings playerRatings = new D11_PlayerRatings();

    /**
     * Returns every active-this-year player's most recent rating at or
     * before the given round (use Integer.MAX_VALUE for "latest overall").
     * Players with no rating yet at that point are omitted.
     */
    public Map<String, D11_PlayerRatings.Rating> getLatestRatingsUpToRound(int year, int roundOrderLimit, int ratingTypeId) throws SQLException {
        Map<String, D11_PlayerRatings.Rating> ratings = new HashMap<>();
        List<B6_PlayerYearStatus.Status> statuses = playerYearStatus.getActiveStatusesForYear(year);
        for (B6_PlayerYearStatus.Status status : statuses) {
            D11_PlayerRatings.Rating rating = playerRatings.getLatestRatingUpToRound(status.playerId, year, roundOrderLimit, ratingTypeId);
            if (rating != null) {
                ratings.put(status.playerId, rating);
            }
        }
        return ratings;
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
