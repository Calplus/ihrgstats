package com.calplus.ihrgstats.test;

import com.calplus.ihrgstats.calculations.EloCalculator;
import java.util.*;

/**
 * Test program to verify ELO calculation logic
 */
public class EloCalculationTest {
    
    public static void main(String[] args) {
        System.out.println("=== ELO Calculation Test ===\n");
        
        // Test Scenario 1: Two players, two rounds
        testTwoPlayersTwoRounds();
        
        // Test Scenario 2: Four players, three rounds
        testFourPlayersThreeRounds();
        
        System.out.println("\n=== All Tests Complete ===");
    }
    
    private static void testTwoPlayersTwoRounds() {
        System.out.println("--- Test 1: Two Players, Two Rounds ---");
        System.out.println("Setup: Round 1: A beats B, Round 2: A beats B again\n");
        
        // Setup
        Set<String> players = new HashSet<>(Arrays.asList("player_a", "player_b"));
        List<String> rounds = Arrays.asList("1", "2");
        
        // Round 1: A beats B (5 point margin)
        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("player_a", "player_b", 1.0, 5.0, "1"));
        
        // Initial ratings
        Map<String, EloCalculator.Glicko2Rating> initialRatings = new HashMap<>();
        initialRatings.put("player_a", new EloCalculator.Glicko2Rating(1000, 350, 0.06));
        initialRatings.put("player_b", new EloCalculator.Glicko2Rating(1000, 350, 0.06));
        
        // Calculate Round 1 (1 iteration)
        System.out.println("Processing Round 1 (1 iteration):");
        EloCalculator.Glicko2Result result1 = EloCalculator.calculateGlicko2TrueElo(
            games, players, initialRatings, Arrays.asList("1"));
        
        printRoundRatings(result1, "1", players);
        
        // Now add Round 2: A beats B again (7 point margin)
        games.add(new EloCalculator.Game("player_a", "player_b", 1.0, 7.0, "2"));
        
        // For Round 2, use 3 iterations with starting ratings from Round 1 final
        System.out.println("\nProcessing Rounds 1-2 (3 iterations, iterative refinement):");
        
        Map<String, EloCalculator.Glicko2Rating> round1Final = result1.ratingsByRound.get("1");
        EloCalculator.Glicko2Result resultIter1 = null;
        
        for (int iter = 1; iter <= 3; iter++) {
            Map<String, EloCalculator.Glicko2Rating> startRatings = (iter == 1) ? round1Final : 
                resultIter1.ratingsByRound.get("2");
            
            resultIter1 = EloCalculator.calculateGlicko2TrueElo(
                games, players, startRatings, rounds);
            
            System.out.println("\n  Iteration " + iter + ":");
            printRoundRatings(resultIter1, "1", players);
            printRoundRatings(resultIter1, "2", players);
        }
        
        // Verify: Round 1 ratings should have changed from first calculation
        EloCalculator.Glicko2Rating aRound1Initial = result1.ratingsByRound.get("1").get("player_a");
        EloCalculator.Glicko2Rating aRound1Final = resultIter1.ratingsByRound.get("1").get("player_a");
        
        System.out.println("\n  Verification:");
        System.out.println("  - Round 1 rating changed: " + 
            (Math.abs(aRound1Initial.rating - aRound1Final.rating) > 0.1 ? "✓ YES" : "✗ NO"));
        System.out.println("    Initial R1 (A): " + Math.round(aRound1Initial.rating));
        System.out.println("    Final R1 (A):   " + Math.round(aRound1Final.rating));
        
        System.out.println();
    }
    
    private static void testFourPlayersThreeRounds() {
        System.out.println("\n--- Test 2: Four Players, Three Rounds ---");
        System.out.println("Setup:");
        System.out.println("  Round 1: A beats B (5 pts), C beats D (3 pts)");
        System.out.println("  Round 2: A beats C (6 pts), B beats D (4 pts)");
        System.out.println("  Round 3: A beats D (8 pts), B beats C (2 pts)\n");
        
        Set<String> players = new HashSet<>(Arrays.asList("a", "b", "c", "d"));
        
        // Initial ratings
        Map<String, EloCalculator.Glicko2Rating> initialRatings = new HashMap<>();
        for (String p : players) {
            initialRatings.put(p, new EloCalculator.Glicko2Rating(1000, 350, 0.06));
        }
        
        // Round 1 games
        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("a", "b", 1.0, 5.0, "1"));
        games.add(new EloCalculator.Game("c", "d", 1.0, 3.0, "1"));
        
        System.out.println("Processing Round 1 (1 iteration):");
        EloCalculator.Glicko2Result result = EloCalculator.calculateGlicko2TrueElo(
            games, players, initialRatings, Arrays.asList("1"));
        printRoundRatings(result, "1", players);
        
        // Round 2 games
        games.add(new EloCalculator.Game("a", "c", 1.0, 6.0, "2"));
        games.add(new EloCalculator.Game("b", "d", 1.0, 4.0, "2"));
        
        System.out.println("\nProcessing Rounds 1-2 (3 iterations):");
        Map<String, EloCalculator.Glicko2Rating> startRatings = result.ratingsByRound.get("1");
        EloCalculator.Glicko2Result result2 = null;
        
        for (int iter = 1; iter <= 3; iter++) {
            if (iter > 1) {
                startRatings = result2.ratingsByRound.get("2");
            }
            result2 = EloCalculator.calculateGlicko2TrueElo(
                games, players, startRatings, Arrays.asList("1", "2"));
            
            if (iter == 3) {
                System.out.println("  Final iteration results:");
                printRoundRatings(result2, "1", players);
                printRoundRatings(result2, "2", players);
            }
        }
        
        // Round 3 games
        games.add(new EloCalculator.Game("a", "d", 1.0, 8.0, "3"));
        games.add(new EloCalculator.Game("b", "c", 1.0, 2.0, "3"));
        
        System.out.println("\nProcessing Rounds 1-3 (3 iterations):");
        startRatings = result2.ratingsByRound.get("2");
        EloCalculator.Glicko2Result result3 = null;
        
        for (int iter = 1; iter <= 3; iter++) {
            if (iter > 1) {
                startRatings = result3.ratingsByRound.get("3");
            }
            result3 = EloCalculator.calculateGlicko2TrueElo(
                games, players, startRatings, Arrays.asList("1", "2", "3"));
            
            if (iter == 3) {
                System.out.println("  Final iteration results:");
                printRoundRatings(result3, "1", players);
                printRoundRatings(result3, "2", players);
                printRoundRatings(result3, "3", players);
            }
        }
        
        // Verify player A has highest rating (won all games)
        EloCalculator.Glicko2Rating aFinal = result3.ratingsByRound.get("3").get("a");
        EloCalculator.Glicko2Rating bFinal = result3.ratingsByRound.get("3").get("b");
        EloCalculator.Glicko2Rating cFinal = result3.ratingsByRound.get("3").get("c");
        EloCalculator.Glicko2Rating dFinal = result3.ratingsByRound.get("3").get("d");
        
        System.out.println("\n  Verification:");
        System.out.println("  - Player A (3-0) has highest rating: " + 
            (aFinal.rating > bFinal.rating && aFinal.rating > cFinal.rating && aFinal.rating > dFinal.rating ? 
                "✓ YES" : "✗ NO"));
        System.out.println("  - Player D (0-3) has lowest rating: " + 
            (dFinal.rating < bFinal.rating && dFinal.rating < cFinal.rating && dFinal.rating < aFinal.rating ? 
                "✓ YES" : "✗ NO"));
        
        // Check that Round 1 ratings evolved
        EloCalculator.Glicko2Rating aR1Initial = result.ratingsByRound.get("1").get("a");
        EloCalculator.Glicko2Rating aR1Final = result3.ratingsByRound.get("1").get("a");
        
        System.out.println("  - Round 1 ratings changed after Round 3: " + 
            (Math.abs(aR1Initial.rating - aR1Final.rating) > 1.0 ? "✓ YES" : "✗ NO"));
        System.out.println("    R1 after R1: " + Math.round(aR1Initial.rating));
        System.out.println("    R1 after R3: " + Math.round(aR1Final.rating));
    }
    
    private static void printRoundRatings(EloCalculator.Glicko2Result result, String round, Set<String> players) {
        Map<String, EloCalculator.Glicko2Rating> ratings = result.ratingsByRound.get(round);
        if (ratings == null) {
            System.out.println("  Round " + round + ": No ratings");
            return;
        }
        
        System.out.print("  Round " + round + ": ");
        List<String> sortedPlayers = new ArrayList<>(players);
        Collections.sort(sortedPlayers);
        
        for (String player : sortedPlayers) {
            EloCalculator.Glicko2Rating rating = ratings.get(player);
            if (rating != null) {
                System.out.print(player.toUpperCase() + "=" + Math.round(rating.rating) + " ");
            }
        }
        System.out.println();
    }
}
