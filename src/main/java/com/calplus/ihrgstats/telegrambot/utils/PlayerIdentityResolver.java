package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a CSV row's (name, hall) to a permanent player_id for a given
 * year, creating a new player and/or player_year_status row as needed.
 *
 * Preserves the legacy interactive resolution UX (keep old hall / update
 * hall / create new player / same-person-vs-different-person dialogs,
 * Levenshtein + partial-name matching) - only the persistence target
 * changes (writes to player_names/player_year_status/players instead of
 * denormalized A1_PlayerStats columns).
 *
 * Key behavioral note: the legacy "active vs inactive player" branch in
 * hall-mismatch resolution maps onto "does a player_year_status row
 * already exist for the CURRENT year being processed" here, not a stored
 * flag - a player with no row yet this year always gets the interactive
 * resolution path when their hall differs from their last known hall; a
 * player who already has a row THIS year hitting a differing hall is a
 * hard error (likely a data-entry mistake within the same season).
 */
public class PlayerIdentityResolver {

    public interface MultiChoiceCallback {
        /** Returns the 0-based index of the chosen option, or -1/timeout to cancel. */
        int requestChoice(String message, String[] options);
    }

    private final B4_Players players = new B4_Players();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final B7_CappedImports cappedImports = new B7_CappedImports();
    private final A3_Halls halls = new A3_Halls();

    private MultiChoiceCallback multiChoiceCallback;

    public void setMultiChoiceCallback(MultiChoiceCallback callback) {
        this.multiChoiceCallback = callback;
    }

    /** Result of resolving one CSV row's player. */
    public static class ResolutionResult {
        public final String playerId; // null if cancelled
        public final int hallId; // the hall actually used (may differ from the CSV's if "keep old hall" was chosen)
        public final boolean cancelled;

        ResolutionResult(String playerId, int hallId, boolean cancelled) {
            this.playerId = playerId;
            this.hallId = hallId;
            this.cancelled = cancelled;
        }
    }

    /** Thrown when a hall mismatch is detected for a player already active THIS year - not user-resolvable. */
    public static class HallMismatchException extends RuntimeException {
        public HallMismatchException(String message) {
            super(message);
        }
    }

    /**
     * Resolves a single CSV row's player. `hall` is the hall as stated in
     * THIS row of the CSV (already resolved from the raw CSV string via
     * A3_Halls.getHallByName by the caller).
     */
    public ResolutionResult resolvePlayer(String rawName, A3_Halls.Hall hall, int year, String nowTimestamp) throws SQLException {
        String name = rawName.trim();
        List<B5_PlayerNames.NameRecord> candidates = playerNames.findCandidatesByExactName(name);

        String resolvedPlayerId = null;
        int resolvedHallId = hall.id;

        for (B5_PlayerNames.NameRecord candidate : candidates) {
            String candidatePlayerId = candidate.playerId;
            B6_PlayerYearStatus.Status thisYearStatus = playerYearStatus.getStatus(candidatePlayerId, year);

            if (thisYearStatus != null) {
                if (thisYearStatus.hallId == hall.id) {
                    resolvedPlayerId = candidatePlayerId;
                    resolvedHallId = hall.id;
                    break;
                }
                // Already has a row THIS year with a DIFFERENT hall - hard error, no override.
                A3_Halls.Hall existingHall = halls.getHallById(thisYearStatus.hallId);
                throw new HallMismatchException(String.format(
                    "Active player hall mismatch: '%s' is already registered in hall '%s' for %d, " +
                    "but this row lists hall '%s'. Active players should not have hall changes within the " +
                    "same year - this is likely a data entry error and must be fixed manually.",
                    name, existingHall != null ? existingHall.hallName : "?", year, hall.hallName));
            }

            B6_PlayerYearStatus.Status priorStatus = playerYearStatus.getMostRecentStatusBeforeYear(candidatePlayerId, year);
            if (priorStatus == null || priorStatus.hallId == hall.id) {
                resolvedPlayerId = candidatePlayerId;
                resolvedHallId = hall.id;
                break;
            }

            // First appearance this year, hall differs from most recent prior year - interactive resolution.
            int choice = requestHallMismatchChoice(name, hall, priorStatus);

            if (choice == 0) {
                resolvedPlayerId = candidatePlayerId;
                resolvedHallId = priorStatus.hallId;
                break;
            } else if (choice == 1) {
                resolvedPlayerId = candidatePlayerId;
                resolvedHallId = hall.id;
                break;
            } else if (choice == 2) {
                continue; // treat as a different person - keep checking other candidates
            } else {
                return new ResolutionResult(null, hall.id, true);
            }
        }

        if (resolvedPlayerId == null) {
            FuzzyMatch fuzzy = tryFuzzyMatch(name);
            if (fuzzy != null) {
                if (fuzzy.cancelled) {
                    return new ResolutionResult(null, hall.id, true);
                }
                if (fuzzy.playerId != null) {
                    // The exact-name loop above hard-errors when a candidate
                    // already has a THIS-YEAR status row under a different
                    // hall (a same-season hall change is almost always a data
                    // entry error, never user-resolvable). A fuzzy "treat as
                    // same person" match must be held to the same guard -
                    // without it, a typo'd name for a player already active
                    // this year in another hall silently resolved under the
                    // CSV row's hall instead, corrupting that player's
                    // this-year hall/stats.
                    B6_PlayerYearStatus.Status thisYearStatus = playerYearStatus.getStatus(fuzzy.playerId, year);
                    if (thisYearStatus != null && thisYearStatus.hallId != hall.id) {
                        A3_Halls.Hall existingHall = halls.getHallById(thisYearStatus.hallId);
                        throw new HallMismatchException(String.format(
                            "Active player hall mismatch: '%s' is already registered in hall '%s' for %d, " +
                            "but this row lists hall '%s'. Active players should not have hall changes within the " +
                            "same year - this is likely a data entry error and must be fixed manually.",
                            name, existingHall != null ? existingHall.hallName : "?", year, hall.hallName));
                    }
                    if (thisYearStatus != null) {
                        resolvedPlayerId = fuzzy.playerId;
                        resolvedHallId = thisYearStatus.hallId;
                    } else {
                        // First appearance this year: a fuzzy-confirmed
                        // returning player gets the same prior-year
                        // hall-mismatch dialog the exact-name path offers -
                        // previously a typo'd returning player who also
                        // moved halls silently adopted the CSV row's hall
                        // with no prompt, while the identical situation via
                        // exact match prompted.
                        B6_PlayerYearStatus.Status priorStatus = playerYearStatus.getMostRecentStatusBeforeYear(fuzzy.playerId, year);
                        if (priorStatus != null && priorStatus.hallId != hall.id) {
                            int choice = requestHallMismatchChoice(name, hall, priorStatus);
                            if (choice == 0) {
                                resolvedPlayerId = fuzzy.playerId;
                                resolvedHallId = priorStatus.hallId;
                            } else if (choice == 1) {
                                resolvedPlayerId = fuzzy.playerId;
                                resolvedHallId = hall.id;
                            } else if (choice == 2) {
                                // Different player after all - leave unresolved so
                                // the new-player path below creates a fresh record.
                            } else {
                                return new ResolutionResult(null, hall.id, true);
                            }
                        } else {
                            resolvedPlayerId = fuzzy.playerId;
                            resolvedHallId = hall.id;
                        }
                    }
                }
            }
        }

        if (resolvedPlayerId == null) {
            // Genuinely new player.
            resolvedPlayerId = players.generateNewPlayerId(hall.hallCode, nowTimestamp);
            resolvedHallId = hall.id;
        }

        // Record this name usage for this year.
        playerNames.addOrUpdateName(resolvedPlayerId, name, year, nowTimestamp);

        // Ensure a player_year_status row exists for this year (only created once, on first
        // observation this year - never bridges across a year boundary automatically).
        if (playerYearStatus.getStatus(resolvedPlayerId, year) == null) {
            List<B7_CappedImports.ImportRow> cappedRows = cappedImports.findByYearAndName(year, name);
            // A row counts toward THIS player only if it's still unclaimed,
            // or already claimed by this exact player_id - a row already
            // mapped to a DIFFERENT player_id (a distinct, same-named person
            // - B5_PlayerNames' own javadoc calls this a legitimate case)
            // must not also flag this player as capped.
            boolean capped = false;
            for (B7_CappedImports.ImportRow row : cappedRows) {
                if (!row.mapped || resolvedPlayerId.equals(row.playerId)) {
                    capped = true;
                    break;
                }
            }
            playerYearStatus.upsertStatus(resolvedPlayerId, year, resolvedHallId, capped, true, nowTimestamp);
            for (B7_CappedImports.ImportRow row : cappedRows) {
                if (!row.mapped) {
                    // Claim exactly ONE unmapped row: a year's capped list
                    // can legitimately contain two distinct same-named
                    // people, and if the first to appear claimed every
                    // unmapped same-name row, the second would find them all
                    // taken and never be flagged capped.
                    cappedImports.markMapped(row.id, resolvedPlayerId, nowTimestamp);
                    break;
                }
            }
        }

        return new ResolutionResult(resolvedPlayerId, resolvedHallId, false);
    }

    /**
     * Shows the prior-year hall-mismatch dialog (shared by the exact-name
     * and fuzzy-confirmed paths so the two can never drift apart) and
     * returns the raw choice index: 0 = keep old hall, 1 = use new hall,
     * 2 = different player, anything else = cancel.
     */
    private int requestHallMismatchChoice(String name, A3_Halls.Hall csvHall, B6_PlayerYearStatus.Status priorStatus) throws SQLException {
        A3_Halls.Hall priorHall = halls.getHallById(priorStatus.hallId);
        String priorHallName = priorHall != null ? priorHall.hallName : "?";
        return requestMultiChoice(String.format(
            "⚠️ Hall Mismatch Resolution\n\nPlayer: %s\nThis round's hall: %s\nLast known hall (%d): %s\n\nChoose resolution:",
            name, csvHall.hallName, priorStatus.year, priorHallName),
            new String[]{
                "Keep old hall (" + priorHallName + ") - same player",
                "Use new hall (" + csvHall.hallName + ") - same player who changed halls",
                "Use new hall (" + csvHall.hallName + ") - different player",
                "Cancel processing"
            });
    }

    private static class FuzzyMatch {
        String playerId; // null if no good match found
        boolean cancelled;
    }

    /**
     * Fuzzy-matches a name against all known name records (typo/partial-name detection),
     * prompting the same-person-vs-different-person dialog on a plausible hit.
     */
    private FuzzyMatch tryFuzzyMatch(String name) throws SQLException {
        // Match against every name ever recorded, not just this year's -
        // matching only this year's names meant fuzzy detection was only
        // ever reachable for the very first resolved row of a season (the
        // one case where this year's list is still empty); from the second
        // row onward, a typo'd RETURNING player (e.g. "Amara Whitloc" for
        // last year's "Amara Whitlock" - a dropped trailing letter) had no
        // prior-year name to compare against and silently became a new
        // player with no dialog at all - exactly the case this check exists
        // to catch.
        List<B5_PlayerNames.NameRecord> allNames = playerNames.getAllNames();

        // getAllNames() has no ORDER BY and now spans every year (not just
        // this one), so simply prompting on whichever candidate SQLite
        // happens to return first could surface an unrelated, coincidentally
        // typo-distance name ahead of the actual best match. Instead, scan
        // every candidate and keep the single best one: a partial-name match
        // (substring/word-overlap - a stronger signal) beats a spelling-only
        // match, and within the same match strength, prefer whichever
        // candidate was more RECENTLY active - a typo for a recently-active
        // player is a far more likely real-world scenario than confusion
        // with a long-dormant record. Exact strength+recency ties break by
        // name (A-Z), then playerId, so the offered candidate never depends
        // on the scan order.
        B5_PlayerNames.NameRecord bestCandidate = null;
        boolean bestIsPartial = false;

        for (B5_PlayerNames.NameRecord candidate : allNames) {
            if (candidate.name.equalsIgnoreCase(name)) {
                continue; // already handled by the exact-match path
            }
            boolean partial = isPartialNameMatch(name, candidate.name);
            boolean similar = !partial && areSimilarNames(name, candidate.name);
            if (!partial && !similar) {
                continue;
            }
            if (bestCandidate == null
                    || (partial && !bestIsPartial)
                    || (partial == bestIsPartial && candidate.lastSeenYear > bestCandidate.lastSeenYear)
                    || (partial == bestIsPartial && candidate.lastSeenYear == bestCandidate.lastSeenYear
                            && tieBreaksBefore(candidate, bestCandidate))) {
                bestCandidate = candidate;
                bestIsPartial = partial;
            }
        }

        if (bestCandidate == null) {
            return null;
        }

        String type = bestIsPartial ? "partial name" : "possible spelling";
        int choice = requestMultiChoice(String.format(
            "⚠️ Name Mismatch Detected\n\n%s: '%s' may match existing player '%s'.\n\n" +
            "Please choose how to handle this:",
            type, name, bestCandidate.name),
            new String[]{
                "Treat as same person (use existing record)",
                "Treat as different people",
                "Cancel processing"
            });
        FuzzyMatch result = new FuzzyMatch();
        if (choice == 0) {
            result.playerId = bestCandidate.playerId;
        } else if (choice == 1) {
            // different people: leave playerId null, fall through to new-player creation
        } else {
            // choice == 2 (explicit cancel), -1 (dialog timeout or a
            // rejected concurrent dialog), or any other unexpected value -
            // treat all of these as cancel. Previously only choice==2
            // was recognized, so a timeout/rejection fell through to the
            // SAME branch as "different people" and silently created a
            // spurious new player instead of aborting.
            result.cancelled = true;
        }
        return result;
    }

    /**
     * Deterministic final tie-break for two fuzzy candidates of equal match
     * strength and recency: name A-Z, then playerId (unique, so the order
     * is total even for two distinct players sharing one exact name).
     */
    private static boolean tieBreaksBefore(B5_PlayerNames.NameRecord a, B5_PlayerNames.NameRecord b) {
        int byName = a.name.compareToIgnoreCase(b.name);
        if (byName != 0) {
            return byName < 0;
        }
        return a.playerId.compareTo(b.playerId) < 0;
    }

    /** Ported from legacy: checks for substring/partial name overlap (e.g. "John Smith" vs "John"). */
    private boolean isPartialNameMatch(String name1, String name2) {
        String n1 = name1.toLowerCase().replace(",", "").replaceAll("\\s+", " ").trim();
        String n2 = name2.toLowerCase().replace(",", "").replaceAll("\\s+", " ").trim();
        if (n1.equals(n2)) return false; // handled elsewhere
        // Containment only counts when the shorter side is a substantial
        // string (>=3 chars): a 2-letter name like "Ng" is contained in every
        // future debut sharing those letters ("Nightingale, ...") and
        // permanently added a spurious dialog to ingestion. The word-overlap
        // branch below is unaffected (it requires whole-word equality), and
        // Levenshtein typo detection runs separately.
        if (Math.min(n1.length(), n2.length()) >= 3 && (n1.contains(n2) || n2.contains(n1))) return true;

        String[] words1 = n1.split(" ");
        String[] words2 = n2.split(" ");
        String[] shorter = words1.length <= words2.length ? words1 : words2;
        String[] longer = words1.length <= words2.length ? words2 : words1;
        if (shorter.length == 0) return false;

        int matchedWords = 0;
        for (String word : shorter) {
            for (String otherWord : longer) {
                if (word.equals(otherWord)) {
                    matchedWords++;
                    break;
                }
            }
        }
        return matchedWords / (double) shorter.length >= 0.7;
    }

    /** Ported from legacy: Levenshtein distance <= 2 (typo detection). */
    private boolean areSimilarNames(String name1, String name2) {
        int distance = levenshteinDistance(name1.toLowerCase(), name2.toLowerCase());
        int maxLen = Math.max(name1.length(), name2.length());
        return distance > 0 && distance <= 2 && maxLen > 3;
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[s1.length()][s2.length()];
    }

    private int requestMultiChoice(String message, String[] options) {
        if (multiChoiceCallback != null) {
            return multiChoiceCallback.requestChoice(message, options);
        }
        // CLI fallback for local/manual testing without a Telegram callback wired up.
        System.out.println(message);
        for (int i = 0; i < options.length; i++) {
            System.out.println("  " + (i + 1) + ". " + options[i]);
        }
        return options.length - 1; // default to the "cancel" option when unattended
    }
}
