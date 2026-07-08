package com.calplus.ihrgstats.calculations;

import java.util.*;

/**
 * ELO calculation system using the Batch Glicko-2 algorithm.
 * Computes TrueElo (standard Glicko-2 with 1.0/0.5/0.0 win/draw/loss
 * outcomes). PerfElo (sigmoid point-margin transform) has been removed
 * entirely - it was already non-functional in the app (see README), and
 * the "ExpElo" rating_type reserved for future experimentation currently
 * has no calculation code behind it at all.
 */
public class EloCalculator {
    // Glicko-2 Constants
    private static final double DEFAULT_RATING = 1000.0;
    private static final double DEFAULT_RD = 350.0; // High initial uncertainty for fast convergence
    private static final double DEFAULT_VOLATILITY = 0.06;
    private static final double TAU = 1.2; // System constant - higher for faster convergence in short tournaments
    private static final double CONVERGENCE_TOLERANCE = 0.000001;

    // Glicko-2 scale conversion constant
    // Original Glicko-2 works on scale where 1 rating point ≈ 173.7178 in traditional scale
    private static final double GLICKO2_SCALE = 173.7178;
    
    /**
     * Represents a single game result with all necessary data.
     * player1/player2 hold permanent player_id business keys (never
     * "WLKOVR" - walkover games are never passed to the calculator at all,
     * since they don't affect rating).
     */
    public static class Game {
        public String player1;
        public String player2;
        public double score; // 1.0 for player1 win, 0.5 for draw, 0.0 for player1 loss
        public int roundOrder; // sequential round number within the year being processed
        
        public Game(String player1, String player2, double score, int roundOrder) {
            this.player1 = player1;
            this.player2 = player2;
            this.score = score;
            this.roundOrder = roundOrder;
        }
    }
    
    /**
     * Represents a player's Glicko-2 rating state
     */
    public static class Glicko2Rating {
        public double rating; // μ (mu)
        public double rd; // φ (phi) - Rating Deviation
        public double volatility; // σ (sigma)
        
        public Glicko2Rating(double rating, double rd, double volatility) {
            this.rating = rating;
            this.rd = rd;
            this.volatility = volatility;
        }
        
        public Glicko2Rating() {
            this(DEFAULT_RATING, DEFAULT_RD, DEFAULT_VOLATILITY);
        }
    }
    
    /**
     * Result of Glicko-2 calculation for all players across all rounds
     */
    public static class Glicko2Result {
        // Map: roundOrder -> playerId -> Glicko2Rating
        public Map<Integer, Map<String, Glicko2Rating>> ratingsByRound;
        
        public Glicko2Result() {
            this.ratingsByRound = new HashMap<>();
        }
    }
    
    /**
     * Glicko-2 g(φ) function - reduces effect of opponent's rating based on their RD
     * g(φ) = 1 / sqrt(1 + 3φ² / π²)
     * Note: φ must be on Glicko-2 scale (divide by GLICKO2_SCALE first)
     */
    private static double g(double phi) {
        double phiScaled = phi / GLICKO2_SCALE;
        return 1.0 / Math.sqrt(1.0 + 3.0 * phiScaled * phiScaled / (Math.PI * Math.PI));
    }
    
    /**
     * Expected outcome E(μ, μ_j, φ_j) using Bradley-Terry model
     * E = 1 / (1 + e^(-g(φ_j) * (μ - μ_j) / SCALE))
     */
    private static double expectedOutcome(double rating, double oppRating, double oppRd) {
        double gPhi = g(oppRd);
        double ratingDiff = (rating - oppRating) / GLICKO2_SCALE;
        return 1.0 / (1.0 + Math.exp(-gPhi * ratingDiff));
    }
    
    /**
     * Calculates estimated variance v
     * v = [Σ g(φ_j)² * E(μ, μ_j, φ_j) * (1 - E(μ, μ_j, φ_j))]^(-1)
     */
    private static double calculateVariance(double rating, List<OpponentData> opponents) {
        double sum = 0.0;
        for (OpponentData opp : opponents) {
            double gPhi = g(opp.rd);
            double e = expectedOutcome(rating, opp.rating, opp.rd);
            sum += gPhi * gPhi * e * (1.0 - e);
        }
        return 1.0 / sum;
    }
    
    /**
     * Calculates improvement delta Δ
     * Δ = v * Σ g(φ_j) * (s_j - E(μ, μ_j, φ_j))
     */
    private static double calculateDelta(double rating, double variance, List<OpponentData> opponents) {
        double sum = 0.0;
        for (OpponentData opp : opponents) {
            double gPhi = g(opp.rd);
            double e = expectedOutcome(rating, opp.rating, opp.rd);
            sum += gPhi * (opp.outcome - e);
        }
        return variance * sum;
    }
    
    /**
     * Updates volatility using Illinois algorithm
     * This is the complex part of Glicko-2 that prevents rating from changing too quickly
     */
    private static double calculateNewVolatility(double phi, double sigma, double variance, double delta) {
        // Convert to Glicko-2 scale
        double phiScaled = phi / GLICKO2_SCALE;
        double varianceScaled = variance / (GLICKO2_SCALE * GLICKO2_SCALE);
        double deltaScaled = delta / GLICKO2_SCALE;
        
        double alpha = Math.log(sigma * sigma);
        double phiSquared = phiScaled * phiScaled;
        double deltaSquared = deltaScaled * deltaScaled;
        double tauSquared = TAU * TAU;
        
        // Define function f(x)
        java.util.function.DoubleFunction<Double> f = (x) -> {
            double eX = Math.exp(x);
            double phiSq_plus_v_plus_eX = phiSquared + varianceScaled + eX;
            double term1 = eX * (deltaSquared - phiSquared - varianceScaled - eX) / (2.0 * phiSq_plus_v_plus_eX * phiSq_plus_v_plus_eX);
            double term2 = (x - alpha) / tauSquared;
            return term1 - term2;
        };
        
        // Illinois algorithm to find root
        double a = alpha;
        double b = alpha;
        
        // Find initial bracket with safety limit
        int bracketIterations = 0;
        final int MAX_BRACKET_ITERATIONS = 100;
        
        if (deltaSquared > phiSquared + variance) {
            b = alpha + TAU;
            while (f.apply(b) < 0 && bracketIterations < MAX_BRACKET_ITERATIONS) {
                b += TAU;
                bracketIterations++;
            }
        } else {
            b = alpha - TAU;
            while (f.apply(b) > 0 && bracketIterations < MAX_BRACKET_ITERATIONS) {
                b -= TAU;
                bracketIterations++;
            }
        }
        
        double fa = f.apply(a);
        double fb = f.apply(b);
        
        // Iterate to find root with safety limits
        int iterations = 0;
        final int MAX_ITERATIONS = 1000;
        
        while (Math.abs(b - a) > CONVERGENCE_TOLERANCE && iterations < MAX_ITERATIONS) {
            // Check for division by zero
            if (Math.abs(fb - fa) < 1e-10) {
                // Values too close, return current best estimate
                return Math.exp(a / 2.0);
            }
            
            double c = a + (a - b) * fa / (fb - fa);
            double fc = f.apply(c);
            
            if (fc * fb < 0) {
                a = b;
                fa = fb;
            } else {
                fa = fa / 2.0;
            }
            
            b = c;
            fb = fc;
            iterations++;
        }
        
        // If max iterations reached, log warning and return best estimate
        if (iterations >= MAX_ITERATIONS) {
            System.err.println("Warning: calculateNewVolatility reached max iterations. Returning best estimate.");
        }
        
        return Math.exp(a / 2.0);
    }
    
    /**
     * Helper class to store opponent data for calculations
     */
    private static class OpponentData {
        double rating;
        double rd;
        double outcome;
        
        OpponentData(double rating, double rd, double outcome) {
            this.rating = rating;
            this.rd = rd;
            this.outcome = outcome;
        }
    }
    
    /**
     * Updates a player's Glicko-2 rating after a rating period
     * 
     * @param currentRating Current rating state
     * @param opponents List of opponents faced with their ratings and outcomes
     * @return Updated rating state
     */
    private static Glicko2Rating updateGlicko2Rating(Glicko2Rating currentRating, List<OpponentData> opponents) {
        // If no games played, increase RD due to inactivity
        if (opponents.isEmpty()) {
            double newRd = Math.sqrt(currentRating.rd * currentRating.rd + currentRating.volatility * currentRating.volatility);
            return new Glicko2Rating(currentRating.rating, Math.min(newRd, DEFAULT_RD), currentRating.volatility);
        }
        
        // Step 1: Calculate variance v
        double v = calculateVariance(currentRating.rating, opponents);
        
        // Step 2: Calculate delta Δ
        double delta = calculateDelta(currentRating.rating, v, opponents);
        
        // Step 3: Calculate new volatility σ'
        double newVolatility = calculateNewVolatility(currentRating.rd, currentRating.volatility, v, delta);
        
        // Step 4: Calculate new RD φ* (convert to Glicko-2 scale)
        double rdScaled = currentRating.rd / GLICKO2_SCALE;
        double phiStar = Math.sqrt(rdScaled * rdScaled + newVolatility * newVolatility);
        
        // Step 5: Calculate new RD φ' 
        // Note: v is already in Glicko-2 scale from calculateVariance
        double newRdScaled = 1.0 / Math.sqrt(1.0 / (phiStar * phiStar) + 1.0 / v);
        double newRd = newRdScaled * GLICKO2_SCALE;
        
        // Step 6: Calculate new rating μ' (rating change on regular scale)
        double ratingChange = 0.0;
        for (OpponentData opp : opponents) {
            double gPhi = g(opp.rd);
            double e = expectedOutcome(currentRating.rating, opp.rating, opp.rd);
            ratingChange += gPhi * (opp.outcome - e);
        }
        double newRating = currentRating.rating + (newRdScaled * newRdScaled) * ratingChange * GLICKO2_SCALE;
        
        // DEBUG: First call only
        if (DEBUG_FIRST_UPDATE) {
            DEBUG_FIRST_UPDATE = false;
            System.out.println("DEBUG updateGlicko2Rating (FIXED v2):");
            System.out.println("  Current: rating=" + currentRating.rating + ", rd=" + currentRating.rd);
            System.out.println("  Opponents: " + opponents.size());
            System.out.println("  Variance v (Glicko-2 scale): " + v);
            System.out.println("  Delta: " + delta);
            System.out.println("  New volatility: " + newVolatility);
            System.out.println("  RD scaled: " + rdScaled);
            System.out.println("  PhiStar: " + phiStar);
            System.out.println("  New RD scaled: " + newRdScaled);
            System.out.println("  New RD (unscaled): " + newRd);
            System.out.println("  RatingChange sum: " + ratingChange);
            System.out.println("  Rating change: " + ((newRdScaled * newRdScaled) * ratingChange * GLICKO2_SCALE));
            System.out.println("  New rating: " + newRating);
        }
        
        return new Glicko2Rating(newRating, newRd, newVolatility);
    }
    
    private static boolean DEBUG_FIRST_UPDATE = true;
    
    /**
     * Calculates TrueElo using Batch Glicko-2 with binary win/loss/draw outcomes
     * Implements proper Batch Glicko-2 algorithm with 3 iterations per round
     * 
     * @param allGames List of all games across all rounds being (re)processed this year
     * @param playerIds Set of all permanent player_ids involved
     * @param initialRatings Map of player_id -> initial Glicko2Rating (seeded from
     *                       the player's most recent prior-year rating, or default if new)
     * @param roundOrderSequence Ordered list of round_order values for the year being processed
     * @return Glicko2Result containing ratings for all players at each round
     */
    public static Glicko2Result calculateGlicko2TrueElo(
            List<Game> allGames,
            Set<String> playerIds,
            Map<String, Glicko2Rating> initialRatings,
            List<Integer> roundOrderSequence) {
        
        Glicko2Result result = new Glicko2Result();
        
        // Initialize current ratings
        Map<String, Glicko2Rating> currentRatings = new HashMap<>();
        for (String playerId : playerIds) {
            currentRatings.put(playerId, initialRatings.getOrDefault(playerId, new Glicko2Rating()));
        }
        
        // Group games by round
        Map<Integer, List<Game>> gamesByRound = new HashMap<>();
        for (Game game : allGames) {
            gamesByRound.computeIfAbsent(game.roundOrder, k -> new ArrayList<>()).add(game);
        }
        
        // Process each round sequentially
        for (int round : roundOrderSequence) {
            List<Game> roundGames = gamesByRound.getOrDefault(round, new ArrayList<>());
            
            // Build opponent data for each player in this round
            Map<String, List<OpponentData>> playerOpponents = new HashMap<>();
            
            for (Game game : roundGames) {
                String player1Id = game.player1;
                String player2Id = game.player2;
                
                Glicko2Rating opp2Rating = currentRatings.get(player2Id);
                if (opp2Rating != null) {
                    playerOpponents.computeIfAbsent(player1Id, k -> new ArrayList<>())
                        .add(new OpponentData(opp2Rating.rating, opp2Rating.rd, game.score));
                }
                
                Glicko2Rating opp1Rating = currentRatings.get(player1Id);
                if (opp1Rating != null) {
                    playerOpponents.computeIfAbsent(player2Id, k -> new ArrayList<>())
                        .add(new OpponentData(opp1Rating.rating, opp1Rating.rd, 1.0 - game.score));
                }
            }
            
            // Perform 3 iterations of Batch Glicko-2 for this round
            Map<String, Glicko2Rating> iterationRatings = new HashMap<>(currentRatings);
            
            for (int iteration = 1; iteration <= 3; iteration++) {
                // Reset RD to 350 before first iteration
                if (iteration == 1) {
                    Map<String, Glicko2Rating> resetRatings = new HashMap<>();
                    for (Map.Entry<String, Glicko2Rating> entry : iterationRatings.entrySet()) {
                        resetRatings.put(entry.getKey(), 
                            new Glicko2Rating(entry.getValue().rating, DEFAULT_RD, entry.getValue().volatility));
                    }
                    iterationRatings = resetRatings;
                }
                
                // Update opponent data with current iteration ratings
                Map<String, List<OpponentData>> iterationOpponents = new HashMap<>();
                for (Game game : roundGames) {
                    String player1Id = game.player1;
                    String player2Id = game.player2;
                    
                    Glicko2Rating opp2Rating = iterationRatings.get(player2Id);
                    if (opp2Rating != null) {
                        iterationOpponents.computeIfAbsent(player1Id, k -> new ArrayList<>())
                            .add(new OpponentData(opp2Rating.rating, opp2Rating.rd, game.score));
                    }
                    
                    Glicko2Rating opp1Rating = iterationRatings.get(player1Id);
                    if (opp1Rating != null) {
                        iterationOpponents.computeIfAbsent(player2Id, k -> new ArrayList<>())
                            .add(new OpponentData(opp1Rating.rating, opp1Rating.rd, 1.0 - game.score));
                    }
                }
                
                // Update all player ratings for this iteration
                Map<String, Glicko2Rating> newRatings = new HashMap<>();
                for (String playerId : playerIds) {
                    Glicko2Rating currentRating = iterationRatings.get(playerId);
                    List<OpponentData> opponents = iterationOpponents.getOrDefault(playerId, new ArrayList<>());
                    
                    Glicko2Rating newRating;
                    if (opponents.isEmpty()) {
                        // No opponents - preserve rating exactly
                        newRating = new Glicko2Rating(currentRating.rating, currentRating.rd, currentRating.volatility);
                    } else {
                        // Normal rating update with Batch Glicko-2
                        newRating = updateGlicko2Rating(currentRating, opponents);
                    }
                    newRatings.put(playerId, newRating);
                }
                
                iterationRatings = newRatings;
            }
            
            // Store final ratings for this round
            result.ratingsByRound.put(round, new HashMap<>(iterationRatings));
            
            // Update current ratings for next round
            currentRatings = iterationRatings;
        }
        
        return result;
    }
}
