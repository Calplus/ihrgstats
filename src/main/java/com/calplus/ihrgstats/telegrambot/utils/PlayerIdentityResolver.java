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
            A3_Halls.Hall priorHall = halls.getHallById(priorStatus.hallId);
            String priorHallName = priorHall != null ? priorHall.hallName : "?";
            int choice = requestMultiChoice(String.format(
                "⚠️ Hall Mismatch Resolution\n\nPlayer: %s\nThis round's hall: %s\nLast known hall (%d): %s\n\nChoose resolution:",
                name, hall.hallName, priorStatus.year, priorHallName),
                new String[]{
                    "Keep old hall (" + priorHallName + ") - same player",
                    "Use new hall (" + hall.hallName + ") - same player who changed halls",
                    "Use new hall (" + hall.hallName + ") - different player",
                    "Cancel processing"
                });

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
            FuzzyMatch fuzzy = tryFuzzyMatch(name, year);
            if (fuzzy != null) {
                if (fuzzy.cancelled) {
                    return new ResolutionResult(null, hall.id, true);
                }
                if (fuzzy.playerId != null) {
                    resolvedPlayerId = fuzzy.playerId;
                    resolvedHallId = hall.id;
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
            boolean capped = !cappedImports.findByYearAndName(year, name).isEmpty();
            playerYearStatus.upsertStatus(resolvedPlayerId, year, resolvedHallId, capped, true, nowTimestamp);
            for (B7_CappedImports.ImportRow row : cappedImports.findByYearAndName(year, name)) {
                if (!row.mapped) {
                    cappedImports.markMapped(row.id, resolvedPlayerId, nowTimestamp);
                }
            }
        }

        return new ResolutionResult(resolvedPlayerId, resolvedHallId, false);
    }

    private static class FuzzyMatch {
        String playerId; // null if no good match found
        boolean cancelled;
    }

    /**
     * Fuzzy-matches a name against all known name records (typo/partial-name detection),
     * prompting the same-person-vs-different-person dialog on a plausible hit.
     */
    private FuzzyMatch tryFuzzyMatch(String name, int year) throws SQLException {
        List<B5_PlayerNames.NameRecord> allNames = playerNames.getAllNamesForYear(year);
        if (allNames.isEmpty()) {
            allNames = playerNames.getAllNames();
        }

        for (B5_PlayerNames.NameRecord candidate : allNames) {
            if (candidate.name.equalsIgnoreCase(name)) {
                continue; // already handled by the exact-match path
            }
            boolean partial = isPartialNameMatch(name, candidate.name);
            boolean similar = !partial && areSimilarNames(name, candidate.name);
            if (!partial && !similar) {
                continue;
            }
            String type = partial ? "partial name" : "possible spelling";
            int choice = requestMultiChoice(String.format(
                "⚠️ Name Mismatch Detected\n\n%s: '%s' may match existing player '%s'.\n\n" +
                "Please choose how to handle this:",
                type, name, candidate.name),
                new String[]{
                    "Treat as same person (use existing record)",
                    "Treat as different people",
                    "Cancel processing"
                });
            FuzzyMatch result = new FuzzyMatch();
            if (choice == 0) {
                result.playerId = candidate.playerId;
            } else if (choice == 2) {
                result.cancelled = true;
            }
            // choice == 1 (different people): leave playerId null, fall through to new-player creation
            return result;
        }
        return null;
    }

    /** Ported from legacy: checks for substring/partial name overlap (e.g. "John Smith" vs "John"). */
    private boolean isPartialNameMatch(String name1, String name2) {
        String n1 = name1.toLowerCase().replace(",", "").replaceAll("\\s+", " ").trim();
        String n2 = name2.toLowerCase().replace(",", "").replaceAll("\\s+", " ").trim();
        if (n1.equals(n2)) return false; // handled elsewhere
        if (n1.contains(n2) || n2.contains(n1)) return true;

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
