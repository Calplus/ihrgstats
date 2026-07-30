package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.C8_Matches;
import com.calplus.ihrgstats.databasemanager.C9_MatchParticipants;
import com.calplus.ihrgstats.databasemanager.E13_PlayerRollingCache;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Populates {@code player_rolling_cache} (E13) as a current-state serving
 * cache: unlike {@link FeatureExtractor}, which deliberately computes
 * strictly historical, leak-free "as-of" snapshots for training, this
 * reflects reality AS OF RIGHT NOW (including the just-processed round) -
 * the two are intentionally different views over the same history.
 *
 * Simplification: {@code avg_margin_last_5_matches} uses the RAW score
 * margin (own score - opponent score), not match-type-normalized like
 * {@link FeatureExtractor}'s training features - reasonable for a cache
 * nothing reads yet (still fully inert, same status as before this
 * segment); revisit if/when a live feature actually consumes it.
 */
public class RollingCacheUpdater {

    private final B4_Players players = new B4_Players();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final E13_PlayerRollingCache cache = new E13_PlayerRollingCache();
    private final C8_Matches matches = new C8_Matches();

    /** Recomputes and upserts every player's rolling cache row. Returns the number of players updated. */
    public int updateAll(String nowTimestamp) throws SQLException {
        List<E13_PlayerRollingCache.CacheRow> rows = new ArrayList<>();
        for (String playerId : players.getAllPlayerIds()) {
            if (B4_Players.WALKOVER_PLAYER_ID.equals(playerId)) {
                continue;
            }
            List<C9_MatchParticipants.Participant> rated = ratedHistory(playerId);
            if (rated.isEmpty()) {
                continue;
            }

            int streak = computeStreak(rated);
            double avgMargin = computeAvgMarginLast5(playerId, rated);
            int matchesToday = computeMatchesInMostRecentRound(rated);

            rows.add(new E13_PlayerRollingCache.CacheRow(playerId, streak, avgMargin, matchesToday));
        }
        cache.upsertCacheBatch(rows, nowTimestamp);
        return rows.size();
    }

    /** Walkover boards excluded, most-recent-first (matches getParticipantsForPlayer's own ordering). */
    private List<C9_MatchParticipants.Participant> ratedHistory(String playerId) throws SQLException {
        List<C9_MatchParticipants.Participant> rated = new ArrayList<>();
        for (C9_MatchParticipants.Participant p : participants.getParticipantsForPlayer(playerId)) {
            if (!C9_MatchParticipants.PARTICIPATION_WALKOVER.equals(p.participationType)) {
                rated.add(p);
            }
        }
        return rated;
    }

    /** Signed run length of consecutive identical results ending at the most recent board; 0 if that board was a draw. */
    private static int computeStreak(List<C9_MatchParticipants.Participant> rated) {
        double mostRecent = rated.get(0).outcome;
        if (mostRecent == 0.5) {
            return 0;
        }
        int direction = mostRecent == 1.0 ? 1 : -1;
        int streak = direction;
        for (int i = 1; i < rated.size(); i++) {
            if (rated.get(i).outcome != mostRecent) {
                break;
            }
            streak += direction;
        }
        return streak;
    }

    private double computeAvgMarginLast5(String playerId, List<C9_MatchParticipants.Participant> rated) throws SQLException {
        int n = Math.min(5, rated.size());
        double sum = 0.0;
        int counted = 0;
        for (int i = 0; i < n; i++) {
            C9_MatchParticipants.Participant p = rated.get(i);
            C9_MatchParticipants.Participant opponent = participants.getOpponentParticipant(p.matchId, playerId);
            if (opponent == null) {
                continue;
            }
            sum += p.score - opponent.score;
            counted++;
        }
        return counted > 0 ? sum / counted : 0.0;
    }

    /** Rated boards sharing the same round as the player's most recent board - rated is most-recent-first, so matches form a contiguous prefix. */
    private int computeMatchesInMostRecentRound(List<C9_MatchParticipants.Participant> rated) throws SQLException {
        Integer mostRecentRoundId = matches.getRoundIdForMatch(rated.get(0).matchId);
        if (mostRecentRoundId == null) {
            return 1;
        }
        int count = 0;
        for (C9_MatchParticipants.Participant p : rated) {
            if (mostRecentRoundId.equals(matches.getRoundIdForMatch(p.matchId))) {
                count++;
            } else {
                break;
            }
        }
        return Math.max(1, count);
    }
}
