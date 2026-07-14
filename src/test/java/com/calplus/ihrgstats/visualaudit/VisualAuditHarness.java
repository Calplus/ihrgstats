package com.calplus.ihrgstats.visualaudit;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.commands.*;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off, manually-run visual audit generator - NOT part of the regular
 * suite. Seeds a synthetic-but-realistic dataset (4 halls incl. one with no
 * icon file, 12 players chosen to exercise every name-truncation code path,
 * 10 rounds covering byes/draws/walkovers/timeouts/multi-opponent rounds),
 * then calls all 8 image-producing commands directly and prints the
 * generated PNG paths so they can be opened and visually inspected.
 *
 * Run with: mvn -Dtest=VisualAuditHarness -Dsurefire.failIfNoSpecifiedTests=false test
 * (after temporarily removing @Disabled, or via an IDE "run single test").
 */
@Disabled("one-off visual audit generator - run manually, not part of CI")
public class VisualAuditHarness {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN_USER_ID = "visual_audit_admin";

    @Test
    void generateAllImages() throws Exception {
        Path baseDir = Paths.get(System.getProperty("user.dir")).resolve("temp/visual-audit");
        Files.createDirectories(baseDir);
        System.setProperty("user.dir", baseDir.toAbsolutePath().toString());
        System.setProperty("TELEGRAM_ADMIN_USERID", ADMIN_USER_ID);
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        System.setProperty("SETTINGS_HOMEHALL", "Binjai");

        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        insertMysteryvilleHall();
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
        new A2_MatchTypes().createMatchType("VisualAuditMatch", 21.0, null, "Visual audit match type", NOW);

        Path csvDir = baseDir.resolve("csv");
        Files.createDirectories(csvDir);
        for (int round = 1; round <= 10; round++) {
            Path csv = csvDir.resolve("round_" + round + ".csv");
            Files.writeString(csv, roundCsv(round));
            RoundCsvProcessor processor = new RoundCsvProcessor();
            processor.setMultiChoiceCallback((message, options) -> 0);
            boolean ok = processor.processRound(csv.toString(), YEAR, round, NOW);
            if (!ok) {
                throw new IllegalStateException("Round " + round + " failed to process - fix the CSV before continuing");
            }
        }

        markCapped("Kai");
        markCapped("Priyanka Chandrasekaran");

        List<String> outputPaths = new ArrayList<>();

        int hall1Id = new A3_Halls().getHallByName("1").id;
        int hallBinjaiId = new A3_Halls().getHallByName("Binjai").id;
        int hallCrescentId = new A3_Halls().getHallByName("Crescent").id;
        int hallMysteryvilleId = new A3_Halls().getHallByName("Mysteryville").id;

        // 1. /rankplayers - All Rounds
        CommandRankPlayers rankPlayers = new CommandRankPlayers();
        rankPlayers.handleCommand(ADMIN_USER_ID);
        outputPaths.add("1_rankplayers: " + rankPlayers.handleRoundSelection(ADMIN_USER_ID, "all").imagePath);

        // 2. /rankhalls - All Rounds
        CommandRankHalls rankHalls = new CommandRankHalls();
        rankHalls.handleCommand(ADMIN_USER_ID);
        outputPaths.add("2_rankhalls: " + rankHalls.handleRoundSelection(ADMIN_USER_ID, "all").imagePath);

        // 3. /infohall - Hall 1, All Rounds
        CommandInfoHall infoHall1 = new CommandInfoHall();
        infoHall1.handleCommand(ADMIN_USER_ID);
        infoHall1.handleHallSelection(ADMIN_USER_ID, hall1Id);
        outputPaths.add("3_infohall_hall1: " + infoHall1.handleRoundSelection(ADMIN_USER_ID, "all").imagePath);

        // 4. /infohall - Mysteryville, All Rounds (no-icon fallback)
        CommandInfoHall infoHallMV = new CommandInfoHall();
        infoHallMV.handleCommand(ADMIN_USER_ID);
        infoHallMV.handleHallSelection(ADMIN_USER_ID, hallMysteryvilleId);
        outputPaths.add("4_infohall_mysteryville: " + infoHallMV.handleRoundSelection(ADMIN_USER_ID, "all").imagePath);

        // 5. /infoplayer - Zara, All Rounds
        String zaraId = findPlayerId("Zara Zephyrine Quintessa Blackwood-Ashford");
        CommandInfoPlayer infoPlayer = new CommandInfoPlayer();
        infoPlayer.handleCommand(ADMIN_USER_ID);
        infoPlayer.handleHallSelection(ADMIN_USER_ID, hallCrescentId);
        infoPlayer.handlePlayerSelection(ADMIN_USER_ID, zaraId);
        outputPaths.add("5_infoplayer_zara: " + infoPlayer.handleRoundSelection(ADMIN_USER_ID, "all").imagePath);

        // 6. /comparehalls - Hall 1 vs Crescent, All Rounds
        CommandCompareHalls compareHalls1 = new CommandCompareHalls();
        compareHalls1.handleCommand(ADMIN_USER_ID);
        compareHalls1.handleFirstHallSelection(ADMIN_USER_ID, hall1Id);
        compareHalls1.handleSecondHallSelection(ADMIN_USER_ID, hallCrescentId);
        outputPaths.add("6_comparehalls_1v_crescent: " + compareHalls1.handleRoundSelection(ADMIN_USER_ID, "all").imagePath);

        // 7. /comparehalls - Hall 1 vs Mysteryville, All Rounds
        CommandCompareHalls compareHalls2 = new CommandCompareHalls();
        compareHalls2.handleCommand(ADMIN_USER_ID);
        compareHalls2.handleFirstHallSelection(ADMIN_USER_ID, hall1Id);
        compareHalls2.handleSecondHallSelection(ADMIN_USER_ID, hallMysteryvilleId);
        outputPaths.add("7_comparehalls_1v_mysteryville: " + compareHalls2.handleRoundSelection(ADMIN_USER_ID, "all").imagePath);

        // 8. /compareplayers - Bartholomew vs Kai, All Rounds
        String bartholomewId = findPlayerId("Bartholomew Alexander Krieger");
        String kaiId = findPlayerId("Kai");
        CommandComparePlayers comparePlayers = new CommandComparePlayers();
        comparePlayers.handleCommand(ADMIN_USER_ID);
        comparePlayers.handleFirstHallSelection(ADMIN_USER_ID, hall1Id);
        comparePlayers.handleFirstPlayerSelection(ADMIN_USER_ID, bartholomewId);
        comparePlayers.handleSecondHallSelection(ADMIN_USER_ID, hallBinjaiId);
        comparePlayers.handleSecondPlayerSelection(ADMIN_USER_ID, kaiId);
        outputPaths.add("8_compareplayers_bartholomew_v_kai: " + comparePlayers.handleRoundSelection(ADMIN_USER_ID, "all").imagePath);

        // 9. /infomatch - Round 4 (the draw round)
        CommandInfoMatch infoMatch = new CommandInfoMatch();
        infoMatch.handleCommand(ADMIN_USER_ID);
        outputPaths.add("9_infomatch_round4: " + infoMatch.handleRoundSelection(ADMIN_USER_ID, YEAR + "_4").imagePath);

        // 10. /infomatchhall - Hall 1, Round 7 (the multi-opponent round)
        CommandInfoMatchHall infoMatchHall = new CommandInfoMatchHall();
        infoMatchHall.handleCommand(ADMIN_USER_ID);
        infoMatchHall.handleHallSelection(ADMIN_USER_ID, hall1Id);
        outputPaths.add("10_infomatchhall_hall1_round7: " + infoMatchHall.handleRoundSelection(ADMIN_USER_ID, YEAR + "_7").imagePath);

        System.out.println("=== VISUAL AUDIT OUTPUT PATHS ===");
        for (String line : outputPaths) {
            System.out.println(line);
        }
        System.out.println("=== END VISUAL AUDIT OUTPUT PATHS ===");
    }

    private void insertMysteryvilleHall() throws Exception {
        try (Connection conn = com.calplus.ihrgstats.utils.DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) VALUES (?, ?, 1, ?, ?)")) {
            ps.setString(1, "MV");
            ps.setString(2, "Mysteryville");
            ps.setString(3, NOW);
            ps.setString(4, NOW);
            ps.executeUpdate();
        }
    }

    private void markCapped(String playerName) throws Exception {
        String playerId = findPlayerId(playerName);
        new B6_PlayerYearStatus().setCapped(playerId, YEAR, true, NOW);
    }

    private String findPlayerId(String name) throws Exception {
        List<B5_PlayerNames.NameRecord> candidates = new B5_PlayerNames().findCandidatesByExactName(name);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No player found with name: " + name);
        }
        return candidates.get(0).playerId;
    }

    private static String row(String name1, String hall1, String score1, String name2, String hall2, String score2) {
        return name1 + "," + hall1 + "," + score1 + "," + name2 + "," + hall2 + "," + score2 + "\n";
    }

    private static String roundCsv(int round) {
        StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
        switch (round) {
            case 1:
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Kai", "Binjai", "4"));
                sb.append(row("Marcus Villanueva", "1", "10", "Anne-Marie O'Brien-Smith", "Binjai", "6"));
                sb.append(row("Sam Lee", "1", "10", "Ravi Kumar", "Binjai", "7"));
                sb.append(row("Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "10", "Priyanka Chandrasekaran", "Mysteryville", "3"));
                sb.append(row("Ng", "Crescent", "10", "Jean-Luc", "Mysteryville", "8"));
                sb.append(row("Fatimah Zahra", "Crescent", "10", "Ah Huat", "Mysteryville", "2"));
                break;
            case 2:
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "8"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ng", "Crescent", "5"));
                sb.append(row("Sam Lee", "1", "10", "Fatimah Zahra", "Crescent", "6"));
                sb.append(row("Kai", "Binjai", "10", "Priyanka Chandrasekaran", "Mysteryville", "7"));
                sb.append(row("Anne-Marie O'Brien-Smith", "Binjai", "10", "Jean-Luc", "Mysteryville", "9"));
                sb.append(row("Ravi Kumar", "Binjai", "10", "Ah Huat", "Mysteryville", "4"));
                break;
            case 3:
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Priyanka Chandrasekaran", "Mysteryville", "6"));
                sb.append(row("Marcus Villanueva", "1", "10", "Jean-Luc", "Mysteryville", "7"));
                sb.append(row("Sam Lee", "1", "10", "Ah Huat", "Mysteryville", "3"));
                sb.append(row("Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "10", "Anne-Marie O'Brien-Smith", "Binjai", "5"));
                sb.append(row("Ravi Kumar", "Binjai", "10", "Ng", "Crescent", "8"));
                // Kai (Binjai) and Fatimah (Crescent) both sit out this round.
                break;
            case 4:
                sb.append(row("Marcus Villanueva", "1", "5", "Kai", "Binjai", "5")); // DRAW - Kai's last rated round
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Anne-Marie O'Brien-Smith", "Binjai", "6"));
                sb.append(row("Sam Lee", "1", "10", "Ravi Kumar", "Binjai", "7"));
                sb.append(row("Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "10", "Jean-Luc", "Mysteryville", "4"));
                sb.append(row("Ng", "Crescent", "10", "Ah Huat", "Mysteryville", "6"));
                sb.append(row("Priyanka Chandrasekaran", "Mysteryville", "10", "Fatimah Zahra", "Crescent", "9"));
                break;
            case 5:
                // Kai sits out from here on (last played round 4).
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Jean-Luc", "Mysteryville", "5"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ah Huat", "Mysteryville", "6"));
                sb.append(row("Priyanka Chandrasekaran", "Mysteryville", "", "WALKOVER", "1", "")); // WALKOVER (forfeiting side's hall supplied)
                sb.append(row("Anne-Marie O'Brien-Smith", "Binjai", "10", "Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "8"));
                sb.append(row("Fatimah Zahra", "Crescent", "10", "Ravi Kumar", "Binjai", "7"));
                // Ng (Crescent) sits out this round.
                break;
            case 6:
                sb.append(row("Ravi Kumar", "Binjai", "TIMEOUT", "Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "190")); // TIMEOUT
                sb.append(row("Anne-Marie O'Brien-Smith", "Binjai", "10", "Ng", "Crescent", "6"));
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Priyanka Chandrasekaran", "Mysteryville", "7"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ah Huat", "Mysteryville", "5"));
                sb.append(row("Sam Lee", "1", "10", "Jean-Luc", "Mysteryville", "8"));
                // Fatimah (Crescent) sits out this round.
                break;
            case 7:
                // Multi-opponent round: Hall 1 plays 2 boards vs Binjai AND 1 board vs Crescent.
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Anne-Marie O'Brien-Smith", "Binjai", "6"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ravi Kumar", "Binjai", "8"));
                sb.append(row("Sam Lee", "1", "10", "Ng", "Crescent", "9"));
                // Mysteryville has a bye; Zara/Fatimah (Crescent) also sit out.
                break;
            case 8:
                sb.append(row("Priyanka Chandrasekaran", "Mysteryville", "10", "Fatimah Zahra", "Crescent", "7"));
                sb.append(row("Jean-Luc", "Mysteryville", "10", "Ng", "Crescent", "6"));
                sb.append(row("Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "10", "Ah Huat", "Mysteryville", "4"));
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Anne-Marie O'Brien-Smith", "Binjai", "5"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ravi Kumar", "Binjai", "7"));
                // Sam Lee (Hall 1) sits out this round.
                break;
            case 9:
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Priyanka Chandrasekaran", "Mysteryville", "6"));
                sb.append(row("Marcus Villanueva", "1", "10", "Jean-Luc", "Mysteryville", "8"));
                sb.append(row("Sam Lee", "1", "10", "Ah Huat", "Mysteryville", "5"));
                sb.append(row("Anne-Marie O'Brien-Smith", "Binjai", "10", "Fatimah Zahra", "Crescent", "7"));
                sb.append(row("Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "10", "Ravi Kumar", "Binjai", "6"));
                // Ng (Crescent) sits out this round.
                break;
            case 10:
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "9"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ng", "Crescent", "7"));
                sb.append(row("Sam Lee", "1", "10", "Fatimah Zahra", "Crescent", "8"));
                sb.append(row("Anne-Marie O'Brien-Smith", "Binjai", "10", "Priyanka Chandrasekaran", "Mysteryville", "6"));
                sb.append(row("Ravi Kumar", "Binjai", "10", "Jean-Luc", "Mysteryville", "8"));
                // Ah Huat (Mysteryville) sits out this round; Kai still absent.
                break;
            default:
                throw new IllegalArgumentException("No CSV designed for round " + round);
        }
        return sb.toString();
    }
}
