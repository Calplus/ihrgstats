package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for /infohall: the selection wizard (hall -> round),
 * the per-round and All-Years report bodies, and the session-expiry/cancel
 * paths. Written against the current output so later structural refactors
 * of CommandInfoHall can prove they preserved behavior.
 */
public class CommandInfoHallTest {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));

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
    }

    private static Path writeRoundCsv(Path dir, String fileName, String dataRows) throws Exception {
        Path csv = dir.resolve(fileName);
        Files.writeString(csv, "name1,hall1,score1,name2,hall2,score2\n" + dataRows);
        return csv;
    }

    private static RoundCsvProcessor newProcessor() {
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> 0);
        return processor;
    }

    private static int hall1Id() throws Exception {
        return new A3_Halls().getHallByName("1").id;
    }

    @Test
    void selectionFlow_presentsHallsThenRounds(@TempDir Path csvDir) throws Exception {
        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Aurelia Nightshade,1,10,Bartholomew Krieger,2,5\n");
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process");

        CommandInfoHall infoHall = new CommandInfoHall();
        CommandInfoHall.InfoResponse hallStep = infoHall.handleCommand("user_flow");

        assertNotNull(hallStep.buttonConfig, "Hall step must offer buttons");
        List<String> callbacks = Arrays.asList(hallStep.buttonConfig.callbacks);
        assertTrue(callbacks.contains("infohall_hall_" + hall1Id()), "Hall 1 must be selectable");
        assertTrue(callbacks.contains("infohall_cancel"), "Cancel must be offered");
        int unknownHallId = new A3_Halls().getAllHalls().stream()
                .filter(h -> h.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE))
                .findFirst().orElseThrow().id;
        assertFalse(callbacks.contains("infohall_hall_" + unknownHallId),
                "The internal unknown-hall placeholder must never be selectable");

        CommandInfoHall.InfoResponse roundStep = infoHall.handleHallSelection("user_flow", hall1Id());
        assertNotNull(roundStep.buttonConfig, "Round step must offer buttons");
        List<String> labels = Arrays.asList(roundStep.buttonConfig.labels);
        assertTrue(labels.contains("All Rounds"), "All Rounds option expected, got: " + labels);
        assertTrue(labels.contains("🌐 All Years"), "All Years option expected, got: " + labels);
        assertTrue(labels.contains("Round 1"), "Ingested round expected, got: " + labels);
        assertTrue(labels.contains("❌ Cancel"), "Cancel option expected, got: " + labels);
    }

    @Test
    void allRounds_rendersAllSectionsAndImage(@TempDir Path csvDir) throws Exception {
        Path r1 = writeRoundCsv(csvDir, "r1.csv",
                "Aurelia Nightshade,1,10,Bartholomew Krieger,2,5\n"
                + "Maximiliana Theodora Vandergriff,1,7,Dmitri Volkov,2,8\n");
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process");
        Path r2 = writeRoundCsv(csvDir, "r2.csv",
                "Aurelia Nightshade,1,6,Dmitri Volkov,2,9\n"
                + "Maximiliana Theodora Vandergriff,1,10,Bartholomew Krieger,2,3\n");
        assertTrue(newProcessor().processRound(r2.toString(), YEAR, 2, NOW), "Round 2 should process");

        CommandInfoHall infoHall = new CommandInfoHall();
        infoHall.handleHallSelection("user_all", hall1Id());
        CommandInfoHall.InfoResponse response = infoHall.handleRoundSelection("user_all", "all");

        String msg = response.message;
        assertTrue(msg.contains("Hall 1 Information"), "Title with formatted hall name expected: " + msg);
        assertTrue(msg.contains("Hall Elo"), "Hall Elo section expected: " + msg);
        assertTrue(msg.contains("Player Stats"), "Player Stats section expected: " + msg);
        assertTrue(msg.contains("Seating Arrangements"), "Seating section expected: " + msg);
        assertTrue(msg.contains("Victory Record"), "Victory Record section expected: " + msg);
        assertTrue(msg.contains("Aurelia Nightshade"), "Roster must list the hall's players: " + msg);
        assertTrue(msg.contains("Maximiliana Theod..."),
                "Names over 20 chars must be truncated to 17 + ellipsis: " + msg);
        assertTrue(msg.contains("Round 1") && msg.contains("Round 2"),
                "All-rounds view must cover every ingested round: " + msg);

        assertNotNull(response.imagePath, "All-rounds view must render an image");
        assertTrue(Files.exists(response.imagePath), "Rendered image file must exist on disk");
    }

    @Test
    void singleRound_excludesLaterRounds(@TempDir Path csvDir) throws Exception {
        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Aurelia Nightshade,1,10,Bartholomew Krieger,2,5\n");
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process");
        Path r2 = writeRoundCsv(csvDir, "r2.csv", "Aurelia Nightshade,1,6,Bartholomew Krieger,2,9\n");
        assertTrue(newProcessor().processRound(r2.toString(), YEAR, 2, NOW), "Round 2 should process");

        CommandInfoHall infoHall = new CommandInfoHall();
        infoHall.handleHallSelection("user_single", hall1Id());
        CommandInfoHall.InfoResponse response = infoHall.handleRoundSelection("user_single", "1");

        assertTrue(response.message.contains("Round 1"), "Selected round must appear: " + response.message);
        assertFalse(response.message.contains("Round 2"),
                "A round after the selected cutoff must not appear: " + response.message);
    }

    @Test
    void walkoverRound_attributesRecordToWalkover(@TempDir Path csvDir) throws Exception {
        // Scoring a walkover board needs a match type (board count) to exist.
        new A2_MatchTypes().createMatchType("Test", 10.0, null, "Test match type", NOW);
        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Aurelia Nightshade,1,10,Bartholomew Krieger,2,5\n");
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process");
        Path r2 = writeRoundCsv(csvDir, "r2.csv", "Aurelia Nightshade,1,,WALKOVER,,\n");
        assertTrue(newProcessor().processRound(r2.toString(), YEAR, 2, NOW), "Walkover round should process");

        CommandInfoHall infoHall = new CommandInfoHall();
        infoHall.handleHallSelection("user_wo", hall1Id());
        CommandInfoHall.InfoResponse response = infoHall.handleRoundSelection("user_wo", "all");

        assertTrue(response.message.contains("WALKOVER"),
                "A round won purely by walkover must show WALKOVER as the opponent: " + response.message);
        assertNotNull(response.imagePath, "Walkover round must still render an image");
    }

    @Test
    void allYears_collapsesToOneRowPerYear(@TempDir Path csvDir) throws Exception {
        System.setProperty("SETTINGS_CURRENTYEAR", "2025");
        Path r2025 = writeRoundCsv(csvDir, "r2025.csv", "Aurelia Nightshade,1,10,Bartholomew Krieger,2,5\n");
        assertTrue(newProcessor().processRound(r2025.toString(), 2025, 1, NOW), "2025 round should process");

        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        Path r2026 = writeRoundCsv(csvDir, "r2026.csv", "Aurelia Nightshade,1,8,Bartholomew Krieger,2,7\n");
        assertTrue(newProcessor().processRound(r2026.toString(), YEAR, 1, NOW), "2026 round should process");

        CommandInfoHall infoHall = new CommandInfoHall();
        infoHall.handleHallSelection("user_years", hall1Id());
        CommandInfoHall.InfoResponse response = infoHall.handleRoundSelection("user_years", "allyears");

        assertTrue(response.message.contains("(All Years)"), "All-Years title expected: " + response.message);
        assertTrue(response.message.contains("2025"), "2025 summary row expected: " + response.message);
        assertTrue(response.message.contains("2026"), "2026 summary row expected: " + response.message);
        assertNotNull(response.imagePath, "All-Years mode must still render an image");
    }

    @Test
    void roundSelectionWithoutPriorHallSelection_reportsExpiredSession() {
        CommandInfoHall.InfoResponse response =
                new CommandInfoHall().handleRoundSelection("user_expired", "all");
        assertTrue(response.message.contains("Session expired"),
                "Missing wizard state must be reported as an expired session: " + response.message);
    }

    @Test
    void cancel_discardsStateSoRoundSelectionExpires(@TempDir Path csvDir) throws Exception {
        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Aurelia Nightshade,1,10,Bartholomew Krieger,2,5\n");
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process");

        CommandInfoHall infoHall = new CommandInfoHall();
        infoHall.handleHallSelection("user_cancel", hall1Id());
        CommandInfoHall.InfoResponse cancelResponse = infoHall.handleCancel("user_cancel");
        assertTrue(cancelResponse.message.contains("cancelled"), "Cancel must confirm: " + cancelResponse.message);

        CommandInfoHall.InfoResponse afterCancel = infoHall.handleRoundSelection("user_cancel", "all");
        assertTrue(afterCancel.message.contains("Session expired"),
                "Cancelled state must not be reusable: " + afterCancel.message);
    }
}
