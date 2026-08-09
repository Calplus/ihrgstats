package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;

import java.sql.SQLException;

/**
 * Shared walkover-score-normalization and latest-round helpers, extracted so
 * that fixing this logic once fixes it everywhere - previously each command
 * hand-rolled its own copy, and the copies drifted (one command's "3-2 not
 * 5-0" fix was never applied to its sibling, one command's "actual latest
 * round" lookup was never applied to its sibling).
 */
public final class MatchScoreUtils {

    private MatchScoreUtils() {}

    /**
     * The default score awarded to the winning side of an individual
     * WALKOVER game (or, at the team level, the normalized "3-2" convention
     * for a hall whose entire team faced a walkover this round): winner
     * gets ceil(n/2), or n/2+1 if that's already an integer. Applying this
     * to the actual observed board count (not a hardcoded assumption of 5
     * boards per hall) is what lets it generalize to any team size or
     * per-board score cap.
     */
    public static double computeWalkoverDefaultScore(double n) {
        double half = n / 2.0;
        if (half == Math.floor(half)) {
            return half + 1;
        }
        return Math.ceil(half);
    }

    /**
     * The tournament's actual latest processed round for a year - for "All
     * Rounds" views, this is what should be shown as "Last Round", not
     * whichever round some other sorted list's first entry happened to last
     * play (that entity may have been eliminated early or have fewer rounds
     * recorded than the tournament's true latest round).
     *
     * @return the latest round's label, or null if no rounds exist yet for the year
     */
    public static String latestRoundLabel(A1_Rounds rounds, int year) throws SQLException {
        int latestOrder = rounds.getLatestRoundOrder(year);
        if (latestOrder <= 0) {
            return null;
        }
        A1_Rounds.Round latest = rounds.getRoundByYearAndOrder(year, latestOrder);
        return latest != null ? latest.roundLabel : null;
    }

    /**
     * Display label for a round-scoped report header: "All Years"/"All
     * Rounds" for the aggregate selections, otherwise the round's REAL
     * stored label when it has one - the raw selection value is a
     * round_order number, and the two can disagree on custom labels -
     * falling back to "Round {n}". Never throws: the header is not worth
     * failing a command over. Previously a byte-identical block in each
     * rank command.
     */
    public static String roundDisplayLabel(boolean allYears, String selectedRound, Integer year, A1_Rounds rounds) {
        if (allYears) {
            return "All Years";
        }
        if (selectedRound.equalsIgnoreCase("all")) {
            return "All Rounds";
        }
        String roundDisplay = "Round " + selectedRound;
        try {
            A1_Rounds.Round round = rounds.getRoundByYearAndOrder(year, Integer.parseInt(selectedRound));
            if (round != null && round.roundLabel != null && !round.roundLabel.isEmpty()) {
                roundDisplay = round.roundLabel;
            }
        } catch (SQLException | NumberFormatException e) {
            // Keep the raw fallback.
        }
        return roundDisplay;
    }
}
