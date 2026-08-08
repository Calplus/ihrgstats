package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests for /recalculate (previously covered only by the smoke
 * test's gate + happy path): the empty-database path, and the isolation
 * contract - the recalculation's own success must be reported even when the
 * downstream ML steps have nothing to do (below burn-in, no champion), with
 * each step's outcome stated honestly in the same message.
 */
public class CommandRecalculateTest {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN_USER_ID = "recalc_admin";

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

    @Test
    void emptyDatabase_reportsNothingToRecalculate_insteadOfAFalseCompletion() {
        CommandResponse response = new CommandRecalculate().execute(ADMIN_USER_ID);

        assertTrue(response.message.contains("Nothing to recalculate"),
                "an empty database must be reported as such: " + response.message);
        assertFalse(response.message.contains("Recalculation Complete"),
                "no completion banner may appear when nothing was recalculated: " + response.message);
    }

    @Test
    void populatedDatabase_reportsExactCounts_andHonestSkipNotesForTheMlSteps() throws Exception {
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> 0);
        Path csvDir = Files.createTempDirectory("recalc-fixture");
        Path r1 = csvDir.resolve("r1.csv");
        Files.writeString(r1, "name1,hall1,score1,name2,hall2,score2\n"
                + "Aurelia Nightshade,1,8,Bartholomew Krieger,2,2\n");
        assertTrue(processor.processRound(r1.toString(), YEAR, 1, NOW), "round 1 must ingest");
        Path r2 = csvDir.resolve("r2.csv");
        Files.writeString(r2, "name1,hall1,score1,name2,hall2,score2\n"
                + "Aurelia Nightshade,1,3,Bartholomew Krieger,2,7\n");
        assertTrue(processor.processRound(r2.toString(), YEAR, 2, NOW), "round 2 must ingest");

        CommandResponse response = new CommandRecalculate().execute(ADMIN_USER_ID);

        assertTrue(response.message.contains("Recalculation Complete"), response.message);
        assertTrue(response.message.contains("Rounds recalculated:</b> 2"),
                "the exact round count must be reported: " + response.message);

        // The reported row count must match what is actually stored.
        int trueEloTypeId = new D10_RatingTypes().getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        A1_Rounds rounds = new A1_Rounds();
        D11_PlayerRatings ratings = new D11_PlayerRatings();
        int storedRows = 0;
        for (A1_Rounds.Round round : rounds.getRoundsForYear(YEAR)) {
            storedRows += ratings.getRatingsForRound(round.id, trueEloTypeId).size();
        }
        assertTrue(storedRows > 0, "fixture sanity: rating rows must exist");
        assertTrue(response.message.contains("Rating rows written:</b> " + storedRows),
                "the reported row count must equal the " + storedRows + " actually stored: " + response.message);

        // Two rounds are far below the walk-forward burn-in: training SKIPS
        // and no champion exists - and neither may hide the recalculation's
        // own success, nor be misreported as having happened.
        assertTrue(response.message.contains("AI models not retrained"),
                "the training skip must be stated honestly alongside the successful recalc: " + response.message);
        assertTrue(response.message.contains("ExpElo not distilled"),
                "the distillation no-op must be stated honestly: " + response.message);
        assertTrue(response.message.contains("snapshots were left untouched"),
                "the snapshot guarantee must be stated: " + response.message);
    }
}
