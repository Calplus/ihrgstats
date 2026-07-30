package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.SelectionKeyboards;
import com.calplus.ihrgstats.telegrambot.utils.MatchScoreUtils;
import com.calplus.ihrgstats.telegrambot.utils.RankingQueryHelper;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;
import com.calplus.ihrgstats.utils.TableFormatter.Alignment;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

/**
 * Command handler for /rankplayers command.
 * Displays a ranked list of all players by their TrueElo rating for the
 * current year (settings.currentYear).
 */
public class CommandRankPlayers {
    private final LogHelper logHelper;
    private final A1_Rounds rounds = new A1_Rounds();
    private final A3_Halls halls = new A3_Halls();
    private final B5_PlayerNames playerNames = new B5_PlayerNames();
    private final B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
    private final C9_MatchParticipants participants = new C9_MatchParticipants();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final RankingQueryHelper rankingQueryHelper = new RankingQueryHelper();

    public CommandRankPlayers() {
        EnvironmentManager.ensureSystemPropertiesLoaded();
        this.logHelper = new LogHelper();
    }

    /** Represents a player's ranking data. expElo is null until a champion model has ever been trained. */
    private static class PlayerRankData {
        String name;
        String hall;
        String lastRound;
        double trueElo;
        Double expElo;
        boolean isCapped;

        PlayerRankData(String name, String hall, String lastRound, double trueElo, Double expElo, boolean isCapped) {
            this.name = name;
            this.hall = hall;
            this.lastRound = lastRound;
            this.trueElo = trueElo;
            this.expElo = expElo;
            this.isCapped = isCapped;
        }
    }

    public RankResponse handleCommand(String userId) {
        logHelper.logInfo("Processing /rankplayers command for user " + userId);

        Integer year = YearContext.getCurrentYear();
        if (year == null) {
            return new RankResponse("⚠️ No current year set. An admin must set `settings.currentYear` first.", (Path) null);
        }

        List<A1_Rounds.Round> availableRounds;
        try {
            availableRounds = rounds.getRoundsForYear(year);
        } catch (SQLException e) {
            logHelper.logError("Error fetching rounds: " + e.getMessage());
            return new RankResponse("❌ Database error fetching rounds.", (Path) null);
        }

        if (availableRounds.isEmpty()) {
            return new RankResponse("ℹ️ No rounds with data found for " + year + ".", (Path) null);
        }

        String message = "🏆 **Player Rankings** (" + year + ")\n\nSelect which round to rank players up to:";

        return new RankResponse(message, SelectionKeyboards.roundButtons(availableRounds, "rankplayers_round_", "rankplayers_cancel"));
    }

    public RankResponse handleRoundSelection(String userId, String selectedRound) {
        logHelper.logInfo(com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId) + " selected round: " + selectedRound);

        boolean allYears = selectedRound.equalsIgnoreCase("allyears");
        Integer year = null;
        if (!allYears) {
            year = YearContext.getCurrentYear();
            if (year == null) {
                return new RankResponse("⚠️ No current year set.", (Path) null);
            }
        }

        List<PlayerRankData> players;
        try {
            players = allYears ? fetchPlayerDataAllYears() : fetchPlayerData(year, selectedRound);
        } catch (SQLException e) {
            logHelper.logError("Error fetching player data: " + e.getMessage());
            return new RankResponse("❌ Database error fetching player data.", (Path) null);
        }

        if (players.isEmpty()) {
            String errorMsg = "ℹ️ No player data found for " + (allYears ? "any year" : "round " + selectedRound) + ".";
            return new RankResponse(errorMsg, (Path) null);
        }

        players.sort((p1, p2) -> Double.compare(p2.trueElo, p1.trueElo));

        String homeHall = PropertyResolver.getProperty("settings.homeHall", "");
        String table = formatPlayersTable(players, homeHall);

        String roundDisplay = allYears ? "All Years" : (selectedRound.equalsIgnoreCase("all") ? "All Rounds" : "Round " + selectedRound);
        String yearDisplay = allYears ? "" : (", " + year);
        String message = "🏆 **Player Rankings** (" + roundDisplay + yearDisplay + ")\n\n" +
                "Players ranked by TrueElo rating - ExpElo (the AI model's distilled rating) shown alongside where a champion has been trained\n\n" + table;

        Path imagePath = null;
        try {
            Set<Integer> highlightRows = new HashSet<>();
            if (!homeHall.isEmpty()) {
                for (int i = 0; i < players.size(); i++) {
                    if (players.get(i).hall.equalsIgnoreCase(homeHall)) {
                        highlightRows.add(i);
                    }
                }
            }
            imagePath = allYears
                ? generatePlayersImageAllYears(players, highlightRows)
                : generatePlayersImage(players, highlightRows, selectedRound, year);
        } catch (Exception e) {
            logHelper.logWarning("Failed to generate table image: " + e.getMessage());
        }

        logHelper.logSuccess(String.format("Ranked %d players", players.size()));

        return new RankResponse(message, imagePath);
    }

    public RankResponse handleCancel(String userId) {
        return new RankResponse("❌ Player ranking cancelled.", (Path) null);
    }

    private List<PlayerRankData> fetchPlayerData(int year, String selectedRound) throws SQLException {
        int roundOrderLimit = selectedRound.equalsIgnoreCase("all") ? Integer.MAX_VALUE : Integer.parseInt(selectedRound);
        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            return new ArrayList<>();
        }

        // Rounds at or before the selected limit, most recent first - used
        // below to find each player's actual last-PLAYED round (see
        // findLastRoundLabelActuallyPlayed), rather than relying on the
        // round their latest RATING row happens to belong to.
        List<A1_Rounds.Round> roundsDescending = new ArrayList<>(rounds.getRoundsForYear(year));
        roundsDescending.removeIf(r -> r.roundOrder > roundOrderLimit);
        roundsDescending.sort((a, b) -> Integer.compare(b.roundOrder, a.roundOrder));

        Map<String, D11_PlayerRatings.Rating> ratingsByPlayer = rankingQueryHelper.getLatestRatingsUpToRound(year, roundOrderLimit, trueEloTypeId);
        Map<String, D11_PlayerRatings.Rating> expEloByPlayer = fetchExpEloRatings(h -> rankingQueryHelper.getLatestRatingsUpToRound(year, roundOrderLimit, h));

        List<PlayerRankData> players = new ArrayList<>();
        for (Map.Entry<String, D11_PlayerRatings.Rating> entry : ratingsByPlayer.entrySet()) {
            String playerId = entry.getKey();
            D11_PlayerRatings.Rating rating = entry.getValue();

            B6_PlayerYearStatus.Status status = playerYearStatus.getStatus(playerId, year);
            if (status == null) continue;

            A3_Halls.Hall hall = halls.getHallById(status.hallId);
            String lastPlayedLabel = findLastRoundLabelActuallyPlayed(playerId, roundsDescending);
            String name = playerNames.getNameForYear(playerId, year);
            D11_PlayerRatings.Rating expElo = expEloByPlayer.get(playerId);

            players.add(new PlayerRankData(
                name != null ? name : playerId,
                hall != null ? hall.hallName : "?",
                lastPlayedLabel != null ? lastPlayedLabel : "-",
                rating.ratingValue,
                expElo != null ? expElo.ratingValue : null,
                status.capped));
        }

        return players;
    }

    /**
     * ExpElo alongside TrueElo, best-effort: the rating type may not be
     * seeded yet on an old un-migrated database, and no player will have a
     * row until a champion model has ever been trained - both are normal,
     * not errors, so the whole rankings view degrades to an ExpElo-less
     * "-" column rather than failing.
     */
    private interface RatingFetcher {
        Map<String, D11_PlayerRatings.Rating> fetch(int expEloTypeId) throws SQLException;
    }

    private Map<String, D11_PlayerRatings.Rating> fetchExpEloRatings(RatingFetcher fetcher) throws SQLException {
        Integer expEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO);
        return expEloTypeId != null ? fetcher.fetch(expEloTypeId) : Map.of();
    }

    /**
     * All-time roster: every player who has EVER played, not just this
     * year's active ones. Ratings are already whole-history cumulative, so
     * the VALUE shown for a still-active player is identical to the
     * current-year view - only the roster differs. Each player's hall/capped
     * status is shown as of their own single most recent active year (that
     * concept isn't well-defined jointly across multiple years).
     */
    private List<PlayerRankData> fetchPlayerDataAllYears() throws SQLException {
        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        if (trueEloTypeId == null) {
            return new ArrayList<>();
        }

        List<A1_Rounds.Round> roundsDescending = new ArrayList<>(rounds.getAllRounds());
        Collections.reverse(roundsDescending);

        Map<String, D11_PlayerRatings.Rating> ratingsByPlayer = rankingQueryHelper.getLatestRatingsAllYears(trueEloTypeId);
        Map<String, D11_PlayerRatings.Rating> expEloByPlayer = fetchExpEloRatings(rankingQueryHelper::getLatestRatingsAllYears);

        List<PlayerRankData> players = new ArrayList<>();
        for (B6_PlayerYearStatus.Status status : playerYearStatus.getMostRecentStatusForEachPlayer()) {
            D11_PlayerRatings.Rating rating = ratingsByPlayer.get(status.playerId);
            if (rating == null) continue;

            A3_Halls.Hall hall = halls.getHallById(status.hallId);
            String lastPlayedLabel = findLastRoundLabelActuallyPlayed(status.playerId, roundsDescending);
            String name = playerNames.getNameForYear(status.playerId, status.year);
            D11_PlayerRatings.Rating expElo = expEloByPlayer.get(status.playerId);

            players.add(new PlayerRankData(
                name != null ? name : status.playerId,
                hall != null ? hall.hallName : "?",
                lastPlayedLabel != null ? lastPlayedLabel : "-",
                rating.ratingValue,
                expElo != null ? expElo.ratingValue : null,
                status.capped));
        }

        return players;
    }

    /**
     * The label of the most recent round (from roundsDescending, already
     * newest-first) this player actually PLAYED - has a match_participants
     * row for - rather than merely the round their latest rating row
     * belongs to. A rating row is written for every round a player's hall
     * played even when the player personally sat out that round (a real
     * Glicko-2 RD-growth requirement, not evidence of having played), so
     * using it directly as "last round" mislabelled a carried-forward,
     * non-playing player as having played their hall's most recent round -
     * contradicting the /help text's own "last round the player actually
     * competed" description of this column. Returns null if the player
     * never played any round within roundsDescending.
     */
    private String findLastRoundLabelActuallyPlayed(String playerId, List<A1_Rounds.Round> roundsDescending) throws SQLException {
        for (A1_Rounds.Round round : roundsDescending) {
            if (participants.getParticipantForPlayerAndRound(playerId, round.id) != null) {
                return round.roundLabel;
            }
        }
        return null;
    }

    private String formatPlayersTable(List<PlayerRankData> players, String homeHall) {
        String table = TableFormatter.formatTable(RANK_TABLE_HEADERS, buildRankRows(players), RANK_TABLE_ALIGNMENTS, RANK_TABLE_COLUMN_WIDTHS);

        if (!homeHall.isEmpty()) {
            return TableFormatter.markRows(table,
                    i -> i < players.size() && players.get(i).hall.equalsIgnoreCase(homeHall));
        }

        return table;
    }

    private static final String[] RANK_TABLE_HEADERS = {"Rank", "Elo", "ExpElo", "Hall", "LR", "Cap", "Name"};
    private static final Alignment[] RANK_TABLE_ALIGNMENTS =
        {Alignment.RIGHT, Alignment.RIGHT, Alignment.RIGHT, Alignment.CENTER, Alignment.CENTER, Alignment.CENTER, Alignment.LEFT};
    private static final int[] RANK_TABLE_COLUMN_WIDTHS = {4, 4, 6, 4, 3, 3, 20};

    private static List<String[]> buildRankRows(List<PlayerRankData> players) {
        List<String[]> rows = new ArrayList<>();
        int rank = 1;
        for (PlayerRankData player : players) {
            rows.add(new String[]{
                String.valueOf(rank),
                String.format("%.0f", player.trueElo),
                player.expElo != null ? String.format("%.0f", player.expElo) : "-",
                TableFormatter.shortenHallName(player.hall),
                TableFormatter.shortenRoundName(player.lastRound),
                player.isCapped ? "*" : "",
                TableFormatter.shortenPlayerName(player.name, 20)
            });
            rank++;
        }
        return rows;
    }

    private Path generatePlayersImage(List<PlayerRankData> players, Set<Integer> highlightRows, String selectedRound, int year) throws Exception {
        String lastRoundForMetadata = selectedRound.equalsIgnoreCase("all")
            ? MatchScoreUtils.latestRoundLabel(rounds, year)
            : selectedRound;

        TableImageGenerator.ImageMetadata metadata = new TableImageGenerator.ImageMetadata(
            "Player Rankings", "Players ranked by TrueElo rating", lastRoundForMetadata);

        String entityName = lastRoundForMetadata != null ? lastRoundForMetadata : "unknown";
        return TableImageGenerator.generatePlayerTable(RANK_TABLE_HEADERS, buildRankRows(players), RANK_TABLE_ALIGNMENTS,
            RANK_TABLE_COLUMN_WIDTHS, metadata, highlightRows, "RankPlayers", entityName);
    }

    private Path generatePlayersImageAllYears(List<PlayerRankData> players, Set<Integer> highlightRows) throws Exception {
        List<A1_Rounds.Round> allRounds = rounds.getAllRounds();
        String lastRoundForMetadata = allRounds.isEmpty() ? null : allRounds.get(allRounds.size() - 1).roundLabel;

        TableImageGenerator.ImageMetadata metadata = new TableImageGenerator.ImageMetadata(
            "Player Rankings", "Players ranked by TrueElo rating (All Years)", lastRoundForMetadata);

        return TableImageGenerator.generatePlayerTable(RANK_TABLE_HEADERS, buildRankRows(players), RANK_TABLE_ALIGNMENTS,
            RANK_TABLE_COLUMN_WIDTHS, metadata, highlightRows, "RankPlayers", "AllYears");
    }

    public static class RankResponse extends CommandResponse {
        public RankResponse(String message, Path imagePath) {
            super(message, imagePath);
        }

        public RankResponse(String message, ButtonConfig buttonConfig) {
            super(message, buttonConfig);
        }
    }
}
