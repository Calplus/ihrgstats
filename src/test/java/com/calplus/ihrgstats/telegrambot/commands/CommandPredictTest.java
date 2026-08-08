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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless, DB-backed tests for {@link CommandPredict}: the wizard state
 * machine (hall -> player -> hall -> player), admin gating on every step
 * (not just entry), session-expiry, cancellation, and the actual
 * prediction output - both without any trained model (baseline only) and
 * with one (model shown side by side).
 */
public class CommandPredictTest {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN_ID = "999";
    private static final String NON_ADMIN_ID = "111";

    private String originalUserDir;
    private String originalYearProperty;
    private int hallAId;
    private int hallBId;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        originalYearProperty = System.getProperty("SETTINGS_CURRENTYEAR");
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));

        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN_ID, "Test Admin", NOW);

        List<A3_Halls.Hall> realHalls = new A3_Halls().getAllHalls().stream()
                .filter(h -> !A3_Halls.UNKNOWN_HALL_CODE.equals(h.hallCode)).toList();
        hallAId = realHalls.get(0).id;
        hallBId = realHalls.get(1).id;

        registerPlayer("AA-01", "Aurelia Nightshade", hallAId);
        registerPlayer("BB-01", "Bartholomew Krieger", hallBId);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        if (originalYearProperty != null) {
            System.setProperty("SETTINGS_CURRENTYEAR", originalYearProperty);
        } else {
            System.clearProperty("SETTINGS_CURRENTYEAR");
        }
    }

    private static void registerPlayer(String playerId, String name, int hallId) throws Exception {
        new B4_Players().createPlayer(playerId, NOW);
        new B5_PlayerNames().addOrUpdateName(playerId, name, YEAR, NOW);
        new B6_PlayerYearStatus().upsertStatus(playerId, YEAR, hallId, false, true, NOW);
    }

    @Test
    void handleCommand_deniesNonAdmin() {
        CommandPredict predict = new CommandPredict();
        CommandResponse response = predict.handleCommand(NON_ADMIN_ID);
        assertTrue(response.message.contains("Access Denied"));
        assertNull(response.buttonConfig);
    }

    @Test
    void everyWizardStep_deniesNonAdmin_evenMidFlow() {
        CommandPredict predict = new CommandPredict();
        // An admin legitimately starts the flow...
        predict.handleCommand(ADMIN_ID);
        // ...but every step independently re-checks admin status, not just entry.
        assertTrue(predict.handleFirstHallSelection(NON_ADMIN_ID, hallAId).message.contains("Access Denied"));
        assertTrue(predict.handleFirstPlayerSelection(NON_ADMIN_ID, "AA-01").message.contains("Access Denied"));
        assertTrue(predict.handleSecondHallSelection(NON_ADMIN_ID, hallBId).message.contains("Access Denied"));
        assertTrue(predict.handleSecondPlayerSelection(NON_ADMIN_ID, "BB-01").message.contains("Access Denied"));
    }

    @Test
    void handleCommand_admin_returnsHallButtons() {
        CommandPredict predict = new CommandPredict();
        CommandResponse response = predict.handleCommand(ADMIN_ID);
        assertNotNull(response.buttonConfig);
        assertTrue(List.of(response.buttonConfig.labels).stream().anyMatch(l -> l.contains("❌ Cancel")));
    }

    @Test
    void fullWizardFlow_endsWithSideBySidePrediction_baselineOnlyWhenNoModelTrained() {
        CommandPredict predict = new CommandPredict();

        predict.handleCommand(ADMIN_ID);
        CommandResponse hall1Response = predict.handleFirstHallSelection(ADMIN_ID, hallAId);
        assertNotNull(hall1Response.buttonConfig);
        assertTrue(List.of(hall1Response.buttonConfig.labels).stream().anyMatch(l -> l.contains("Aurelia Nightshade")));

        CommandResponse player1Response = predict.handleFirstPlayerSelection(ADMIN_ID, "AA-01");
        assertNotNull(player1Response.buttonConfig);

        CommandResponse hall2Response = predict.handleSecondHallSelection(ADMIN_ID, hallBId);
        assertNotNull(hall2Response.buttonConfig);
        assertTrue(List.of(hall2Response.buttonConfig.labels).stream().anyMatch(l -> l.contains("Bartholomew Krieger")));

        CommandResponse finalResponse = predict.handleSecondPlayerSelection(ADMIN_ID, "BB-01");
        assertNull(finalResponse.buttonConfig);
        assertTrue(finalResponse.message.contains("Aurelia Nightshade"));
        assertTrue(finalResponse.message.contains("Bartholomew Krieger"));
        assertTrue(finalResponse.message.contains("Glicko baseline"));
        assertTrue(finalResponse.message.contains("no trained model yet"),
                "No model has been trained in this fixture - the output must say so, not silently omit it: " + finalResponse.message);
    }

    @Test
    void secondPlayerList_excludesFirstPlayer_evenFromSameHall() throws Exception {
        registerPlayer("AA-02", "Second Hall-A Player", hallAId);

        CommandPredict predict = new CommandPredict();
        predict.handleCommand(ADMIN_ID);
        predict.handleFirstHallSelection(ADMIN_ID, hallAId);
        predict.handleFirstPlayerSelection(ADMIN_ID, "AA-01");
        CommandResponse hall2Response = predict.handleSecondHallSelection(ADMIN_ID, hallAId);

        assertNotNull(hall2Response.buttonConfig);
        assertTrue(List.of(hall2Response.buttonConfig.labels).stream().anyMatch(l -> l.contains("Second Hall-A Player")));
        assertFalse(List.of(hall2Response.buttonConfig.labels).stream().anyMatch(l -> l.contains("Aurelia Nightshade")),
                "The first player must not be selectable again as the second player");
    }

    @Test
    void handleCancel_clearsState_andSubsequentStepsReportExpiredSession() {
        CommandPredict predict = new CommandPredict();
        predict.handleCommand(ADMIN_ID);
        predict.handleFirstHallSelection(ADMIN_ID, hallAId);

        CommandResponse cancelResponse = predict.handleCancel(ADMIN_ID);
        assertTrue(cancelResponse.message.contains("cancelled"));

        CommandResponse afterCancel = predict.handleFirstPlayerSelection(ADMIN_ID, "AA-01");
        assertTrue(afterCancel.message.contains("Session expired"));
    }

    @Test
    void callingStepsOutOfOrder_reportsSessionExpired_insteadOfCrashing() {
        CommandPredict predict = new CommandPredict();
        CommandResponse response = predict.handleSecondPlayerSelection(ADMIN_ID, "BB-01");
        assertTrue(response.message.contains("Session expired"));
    }

    /**
     * Regression: with no current year set, generatePrediction used to fall
     * back to "year 0" and dress up a meaningless empty-features board as a
     * real prediction. It must refuse like /lineup does.
     */
    @Test
    void prediction_refusesWhenNoCurrentYearIsSet_insteadOfPredictingAgainstYearZero() {
        CommandPredict predict = new CommandPredict();
        predict.handleCommand(ADMIN_ID);
        predict.handleFirstHallSelection(ADMIN_ID, hallAId);
        predict.handleFirstPlayerSelection(ADMIN_ID, "AA-01");
        predict.handleSecondHallSelection(ADMIN_ID, hallBId);

        System.clearProperty("SETTINGS_CURRENTYEAR"); // year vanishes mid-wizard (tearDown restores it)
        CommandResponse response = predict.handleSecondPlayerSelection(ADMIN_ID, "BB-01");

        assertTrue(response.message.contains("No current year set"),
                "the final step must refuse without a current year, not fabricate a year-0 prediction: " + response.message);
    }

    /** With enough uploaded history to cross the walk-forward burn-in floor, the model section must actually appear. */
    @Test
    void prediction_showsModelSideBySideWithBaseline_onceAModelHasBeenTrained() throws Exception {
        buildTrainableHistory();

        CommandPredict predict = new CommandPredict();
        predict.handleCommand(ADMIN_ID);
        predict.handleFirstHallSelection(ADMIN_ID, hallAId);
        predict.handleFirstPlayerSelection(ADMIN_ID, "P1");
        predict.handleSecondHallSelection(ADMIN_ID, hallBId);
        CommandResponse finalResponse = predict.handleSecondPlayerSelection(ADMIN_ID, "Q1");

        assertTrue(new E17_MlModels().getRecent(50).size() > 0, "Fixture setup should have trained at least one model");
        assertTrue(finalResponse.message.contains("<b>Model</b>"), "A champion exists - the model section must appear: " + finalResponse.message);
        assertTrue(finalResponse.message.contains("Top factors"), "Feature contributions should be shown once a model exists");
        assertTrue(finalResponse.message.contains("Reliability"));
    }

    /** 12 rounds x 5 boards via the real upload pipeline - enough to clear the burn-in floor and train a model. */
    private void buildTrainableHistory() throws Exception {
        Path csvDir = Files.createTempDirectory("predict-fixture");
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> 0);

        for (int order = 1; order <= 12; order++) {
            StringBuilder body = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
            for (int i = 1; i <= 5; i++) {
                boolean p1Wins = (order + i) % 2 == 0;
                body.append(String.format("P%d,1,%d,Q%d,2,%d%n", i, p1Wins ? 8 : 2, i, p1Wins ? 2 : 8));
            }
            Path csv = csvDir.resolve("round_" + order + ".csv");
            Files.writeString(csv, body.toString());
            assertTrue(processor.processRound(csv.toString(), YEAR, order, NOW), "Round " + order + " should process successfully");
        }
    }
}
