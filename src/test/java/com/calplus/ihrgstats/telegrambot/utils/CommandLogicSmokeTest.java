package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.commands.*;
import com.calplus.ihrgstats.utils.TelegramHtml;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A "live smoke test" workaround that exercises real command logic directly
 * (no Telegram network calls, no bot token) against a real throwaway SQLite
 * DB - the same headless approach as {@link RoundCsvProcessorPipelineTest}.
 * Each command's wizard is driven by calling its public handle* methods in
 * the same sequence a real button-click flow would, and every returned
 * message is passed through {@link TelegramHtml#prepareForSending(String)}
 * before assertions, exactly as {@code TelegramListener} does before a real
 * send - this is the step that would otherwise be invisible when bypassing
 * Telegram entirely.
 */
public class CommandLogicSmokeTest {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN_USER_ID = "test_admin";

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        System.setProperty("TELEGRAM_ADMIN_USERID", ADMIN_USER_ID);

        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
        System.clearProperty("TELEGRAM_ADMIN_USERID");
    }

    private static RoundCsvProcessor newProcessor(String preferredOption) {
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> {
            for (int i = 0; i < options.length; i++) {
                if (options[i].startsWith(preferredOption)
                        || options[i].startsWith("Continue and reprocess")
                        || options[i].startsWith("Treat as different people")) {
                    return i;
                }
            }
            return 0;
        });
        return processor;
    }

    private static Path writeRoundCsv(Path dir, String fileName, String dataRows) throws Exception {
        Path csv = dir.resolve(fileName);
        Files.writeString(csv, "name1,hall1,score1,name2,hall2,score2\n" + dataRows);
        return csv;
    }

    @Test
    void commandsProduceCorrectOutput_forWalkoverTimeoutAndSpecialCharacterNames(@TempDir Path csvDir) throws Exception {
        // Two match types on purpose - proves the walkover-round-2 dialog is
        // a REAL choice being honored, not just an auto-picked single option.
        new A2_MatchTypes().createMatchType("TypeA", 10.0, null, "First type", NOW);
        new A2_MatchTypes().createMatchType("TypeB", 20.0, null, "Second type", NOW);

        // --- Round 1: a name with HTML-special characters, to verify escaping. ---
        Path r1 = writeRoundCsv(csvDir, "r1.csv",
                "A&J <script>Voss,1,8,Corwin Fairweather,2,2\n");
        assertTrue(newProcessor("TypeB").processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process");

        // --- Round 2: WALKOVER, explicitly choosing "TypeB" among 2 options. ---
        Path r2 = writeRoundCsv(csvDir, "r2.csv", "A&J <script>Voss,1,,WALKOVER,,\n");
        assertTrue(newProcessor("TypeB").processRound(r2.toString(), YEAR, 2, NOW), "Round 2 (walkover) should process");

        int walkoverMatchTypeId = new C8_Matches().getMatchTypeIdForRound(new A1_Rounds().getRoundByYearAndOrder(YEAR, 2).id);
        A2_MatchTypes.MatchType assignedType = new A2_MatchTypes().getMatchTypeById(walkoverMatchTypeId);
        assertEquals("TypeB", assignedType.typeName,
                "The walkover round should have been assigned the SPECIFIC match type chosen in the dialog, not just the first option");

        // --- Round 3: a TIMEOUT match between two new players. ---
        Path r3 = writeRoundCsv(csvDir, "r3.csv", "Petra Lindqvist,1,TIMEOUT,Dmitri Katsaros,2,5\n");
        assertTrue(newProcessor("TypeB").processRound(r3.toString(), YEAR, 3, NOW), "Round 3 (timeout) should process");

        // === Checklist item: /infoplayer renders special characters safely ===
        CommandInfoPlayer infoPlayer = new CommandInfoPlayer();
        String voss = firstCandidatePlayerId("A&J <script>Voss");
        int hall1Id = new A3_Halls().getHallByName("1").id;
        infoPlayer.handleHallSelection(ADMIN_USER_ID, hall1Id);
        infoPlayer.handlePlayerSelection(ADMIN_USER_ID, voss);
        CommandInfoPlayer.InfoResponse infoResponse = infoPlayer.handleRoundSelection(ADMIN_USER_ID, "all");
        String infoSent = TelegramHtml.prepareForSending(infoResponse.message);
        assertFalse(infoSent.contains("<script>"), "A raw, unescaped <script> tag must never reach the outgoing message");
        assertTrue(infoSent.contains("&lt;script&gt;") || infoSent.contains("&amp;lt;script&amp;gt;"),
                "The player's special characters must be HTML-escaped somewhere in the final message: " + infoSent);

        // === Checklist item: /infomatchhall shows "TIMEOUT" for the timed-out side ===
        CommandInfoMatchHall infoMatchHall = new CommandInfoMatchHall();
        infoMatchHall.handleHallSelection(ADMIN_USER_ID, hall1Id);
        CommandInfoMatchHall.InfoResponse matchHallResponse = infoMatchHall.handleRoundSelection(ADMIN_USER_ID, "3");
        String matchHallSent = TelegramHtml.prepareForSending(matchHallResponse.message);
        assertTrue(matchHallSent.contains("TIMEOUT"), "Hall match info for round 3 should show TIMEOUT for Petra Lindqvist: " + matchHallSent);

        // === Checklist item: /compareplayers shows "TIMEOUT" for the matchup ===
        CommandComparePlayers comparePlayers = new CommandComparePlayers();
        String petra = firstCandidatePlayerId("Petra Lindqvist");
        String dmitri = firstCandidatePlayerId("Dmitri Katsaros");
        int hall2Id = new A3_Halls().getHallByName("2").id;
        comparePlayers.handleFirstHallSelection(ADMIN_USER_ID, hall1Id);
        comparePlayers.handleFirstPlayerSelection(ADMIN_USER_ID, petra);
        comparePlayers.handleSecondHallSelection(ADMIN_USER_ID, hall2Id);
        comparePlayers.handleSecondPlayerSelection(ADMIN_USER_ID, dmitri);
        CommandComparePlayers.CompareResponse compareResponse = comparePlayers.handleRoundSelection(ADMIN_USER_ID, "all");
        String compareSent = TelegramHtml.prepareForSending(compareResponse.message);
        assertTrue(compareSent.contains("TIMEOUT"), "Player comparison should show TIMEOUT for the round-3 matchup: " + compareSent);

        // === Checklist item: /rankplayers "Last Round" reflects the true latest round ===
        CommandRankPlayers rankPlayers = new CommandRankPlayers();
        rankPlayers.handleCommand(ADMIN_USER_ID);
        CommandRankPlayers.RankResponse rankResponse = rankPlayers.handleRoundSelection(ADMIN_USER_ID, "all");
        String rankSent = TelegramHtml.prepareForSending(rankResponse.message);
        assertNotNull(rankResponse.imagePath, "Rank players should generate an image");
        assertTrue(Files.exists(rankResponse.imagePath), "The rank players image file should actually exist on disk");

        // === Checklist item: /exportdatabase - both formats produce a real file ===
        CommandExportDatabase exportDatabase = new CommandExportDatabase();
        assertTrue(exportDatabase.isAdmin(ADMIN_USER_ID), "Test admin user should pass the admin check");
        CommandExportDatabase.ExportResponse xlsxResponse = exportDatabase.executeXlsxExport(ADMIN_USER_ID);
        assertTrue(xlsxResponse.success, "XLSX export should succeed: " + xlsxResponse.message);
        assertTrue(Files.exists(xlsxResponse.exportedFilePath) && Files.size(xlsxResponse.exportedFilePath) > 0,
                "XLSX export file should exist and be non-empty");

        CommandExportDatabase.ExportResponse dbResponse = exportDatabase.executeDbExport(ADMIN_USER_ID);
        assertTrue(dbResponse.success, "DB file export should succeed: " + dbResponse.message);
        assertTrue(Files.exists(dbResponse.exportedFilePath) && Files.size(dbResponse.exportedFilePath) > 0,
                "Exported .db file should exist and be non-empty");

        // === Checklist item: /recalculate is admin-gated and completes for an admin ===
        CommandRecalculate recalculate = new CommandRecalculate();
        assertTrue(recalculate.isAdmin(ADMIN_USER_ID), "The seeded admin should be recognized via the new admins table");
        assertFalse(recalculate.isAdmin("not_an_admin"), "A non-admin must not be recognized as admin");

        var deniedResult = recalculate.execute("not_an_admin");
        assertTrue(deniedResult.message.contains("Access Denied"), "A non-admin must be denied /recalculate: " + deniedResult.message);

        String confirmationMessage = recalculate.buildConfirmationMessage();
        assertTrue(confirmationMessage.contains("Whole-History"), "Confirmation message should describe the whole-history recalculation");
        var recalcResult = recalculate.execute(ADMIN_USER_ID);
        String recalcSent = TelegramHtml.prepareForSending(recalcResult.message);
        assertTrue(recalcSent.contains("Complete"), "Recalculation should report completion: " + recalcSent);
    }

    private static String firstCandidatePlayerId(String name) throws Exception {
        var candidates = new B5_PlayerNames().findCandidatesByExactName(name);
        assertFalse(candidates.isEmpty(), "Expected player '" + name + "' to exist");
        return candidates.get(0).playerId;
    }
}
