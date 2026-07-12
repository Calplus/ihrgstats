package com.calplus.ihrgstats.telegrambot.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MatchScoreUtils#computeWalkoverDefaultScore(double)}
 * (moved here from RoundCsvProcessor so the same formula can be reused for
 * team-level walkover-sweep normalization - see MatchScoreUtilsTest).
 * Replaces the old margin-based ScoreCalculationTest.java - scores are now
 * stored raw (no win-margin formula) except for this one remaining formula:
 * the default score awarded to a walkover winner when the CSV row leaves
 * both score fields blank.
 *
 * Formula: winner gets ceil(maxScore/2), or maxScore/2+1 if that's already
 * an integer (matches the legacy WALKOVER scoring convention exactly).
 */
public class RoundCsvProcessorTest {

    @Test
    void evenMaxScore_addsOneToHalf() {
        // maxScore=10 -> half=5.0 is already an integer -> 5.0 + 1 = 6.0
        assertEquals(6.0, MatchScoreUtils.computeWalkoverDefaultScore(10.0), 0.0001);
    }

    @Test
    void oddMaxScore_roundsUpFromHalf() {
        // maxScore=361 -> half=180.5 is not an integer -> ceil(180.5) = 181.0
        assertEquals(181.0, MatchScoreUtils.computeWalkoverDefaultScore(361.0), 0.0001);
    }

    @Test
    void oddDecimalMaxScore_roundsUpFromHalf() {
        // maxScore=368.5 -> half=184.25 is not an integer -> ceil(184.25) = 185.0
        assertEquals(185.0, MatchScoreUtils.computeWalkoverDefaultScore(368.5), 0.0001);
    }

    @Test
    void smallEvenMaxScore_addsOneToHalf() {
        // maxScore=64 (Othello) -> half=32.0 is already an integer -> 32.0 + 1 = 33.0
        assertEquals(33.0, MatchScoreUtils.computeWalkoverDefaultScore(64.0), 0.0001);
    }

    // --- TIMEOUT row parsing/validation (RoundCsvProcessor.parseAndValidateCSV) ---

    @Test
    void timeoutRow_withRealWinnerScore_parsesSuccessfully(@TempDir Path tempDir) throws Exception {
        Path csv = writeCsv(tempDir, "TimeoutPlayer,1,TIMEOUT,WinnerPlayer,2,190.0\n");
        List<RoundCsvProcessor.GameRow> games = new RoundCsvProcessor().parseAndValidateCSV(csv.toString());
        assertEquals(1, games.size());
        assertTrue(games.get(0).isTimeout());
        assertEquals("TIMEOUT", games.get(0).score1);
        assertEquals("190.0", games.get(0).score2);
    }

    @Test
    void timeoutRow_withNoWinnerScore_parsesSuccessfully(@TempDir Path tempDir) throws Exception {
        Path csv = writeCsv(tempDir, "WinnerPlayer,1,0,TimeoutPlayer,2,TIMEOUT\n");
        List<RoundCsvProcessor.GameRow> games = new RoundCsvProcessor().parseAndValidateCSV(csv.toString());
        assertEquals(1, games.size());
        assertTrue(games.get(0).isTimeout());
    }

    @Test
    void timeoutRow_bothSidesTimeout_isRejected(@TempDir Path tempDir) {
        Path csv = writeCsv(tempDir, "Player1,1,TIMEOUT,Player2,2,TIMEOUT\n");
        Exception ex = assertThrows(Exception.class, () -> new RoundCsvProcessor().parseAndValidateCSV(csv.toString()));
        assertTrue(ex.getMessage().contains("Both players cannot be TIMEOUT"));
    }

    @Test
    void timeoutRow_winnerScoreNonNumeric_isRejected(@TempDir Path tempDir) {
        Path csv = writeCsv(tempDir, "Player1,1,TIMEOUT,Player2,2,notANumber\n");
        Exception ex = assertThrows(Exception.class, () -> new RoundCsvProcessor().parseAndValidateCSV(csv.toString()));
        assertTrue(ex.getMessage().contains("numeric or TIMEOUT"));
    }

    @Test
    void timeoutRow_isNotConfusedWithWalkover(@TempDir Path tempDir) throws Exception {
        // A TIMEOUT row involves two real players - isWalkover() must stay false.
        Path csv = writeCsv(tempDir, "Player1,1,TIMEOUT,Player2,2,50\n");
        List<RoundCsvProcessor.GameRow> games = new RoundCsvProcessor().parseAndValidateCSV(csv.toString());
        assertTrue(games.get(0).isTimeout());
        assertEquals(false, games.get(0).isWalkover());
    }

    private static Path writeCsv(Path tempDir, String dataRow) {
        try {
            Path csv = tempDir.resolve("round.csv");
            Files.writeString(csv, "name1,hall1,score1,name2,hall2,score2\n" + dataRow);
            return csv;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
