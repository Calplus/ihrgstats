package com.calplus.ihrgstats.calculations;

import java.util.*;

/**
 * ELO calculation system using Glicko-2 algorithm.
 * Supports both TrueElo (standard Glicko-2 with binary outcomes) and 
 * PerfElo (Glicko-2 + Sigmoid Point-Margin Transform for quality of win).
 */
public class EloCalculator {
    // Glicko-2 Constants
    private static final double DEFAULT_RATING = 1000.0;
    private static final double DEFAULT_RD = 350.0; // High initial uncertainty for fast convergence
    private static final double DEFAULT_VOLATILITY = 0.06;
    private static final double TAU = 1.2; // System constant - higher for faster convergence in short tournaments
    private static final double CONVERGENCE_TOLERANCE = 0.000001;
    
    // PerfElo margin transform constant (sigmoid parameter)
    // K value controls sensitivity to point margins in Weiqi/Go
    // Recommended range: 0.1 to 0.15
    private static final double MARGIN_K = 0.12; // Middle value for balanced sensitivity
    
    // Glicko-2 scale conversion constant
    // Original Glicko-2 works on scale where 1 rating point ≈ 173.7178 in traditional scale
    private static final double GLICKO2_SCALE = 173.7178;
    
    /**
     * Represents a single game result with all necessary data
     */
    public static class Game {
        public String player1;
        public String player2;
        public double score; // 1.0 for player1 win, 0.0 for player1 loss, or continuous value for perfElo
        public double pointMargin; // For perfElo calculation (player1_score - player2_score)
        public String roundName; // e.g., "1", "2", "t16"
        
        public Game(String player1, String player2, double score, double pointMargin, String roundName) {
            this.player1 = player1;
            this.player2 = player2;
            this.score = score;
            this.pointMargin = pointMargin;
            this.roundName = roundName;
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
        // Map: roundName -> playerName -> Glicko2Rating
        public Map<String, Map<String, Glicko2Rating>> ratingsByRound;
        
        public Glicko2Result() {
            this.ratingsByRound = new HashMap<>();
        }
    }
    
    /**
     * Converts point margin to continuous outcome score using sigmoid transform.
     * 
     * Formula: s = 1 / (1 + exp(-K * m))
     * 
     * Where:
     * - m = point margin (your points - opponent points)
     * - K = sensitivity parameter (0.12 for balanced response)
     * 
     * Score mapping (approximate with K=0.12):
     * - margin = 0:     s = 0.50 (even game)
     * - margin = +10:   s ≈ 0.77 (modest advantage)
     * - margin = +20:   s ≈ 0.92 (strong advantage)
     * - margin = +30:   s ≈ 0.98 (dominant win)
     * - margin = -10:   s ≈ 0.23 (modest loss)
     * - margin = -20:   s ≈ 0.08 (strong loss)
     * - margin = -30:   s ≈ 0.02 (dominant loss)
     * 
     * @param pointMargin Margin of victory (positive for win, negative for loss)
     * @return Continuous outcome value between 0 and 1
     */
    public static double pointMarginToOutcome(double pointMargin) {
        return 1.0 / (1.0 + Math.exp(-MARGIN_K * pointMargin));
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
     * @param allGames List of all games across all rounds
     * @param playerNames Set of all player names
     * @param initialRatings Map of player -> initial Glicko2Rating
     * @param roundSequence Ordered list of round names
     * @return Glicko2Result containing ratings for all players at each round
     */
    public static Glicko2Result calculateGlicko2TrueElo(
            List<Game> allGames,
            Set<String> playerNames,
            Map<String, Glicko2Rating> initialRatings,
            List<String> roundSequence) {
        
        Glicko2Result result = new Glicko2Result();
        
        // Initialize current ratings (ensure lowercase keys for consistency)
        Map<String, Glicko2Rating> currentRatings = new HashMap<>();
        for (String player : playerNames) {
            String playerKey = player.toLowerCase();
            currentRatings.put(playerKey, initialRatings.getOrDefault(playerKey, new Glicko2Rating()));
        }
        
        // Group games by round
        Map<String, List<Game>> gamesByRound = new HashMap<>();
        for (Game game : allGames) {
            gamesByRound.computeIfAbsent(game.roundName, k -> new ArrayList<>()).add(game);
        }
        
        // Process each round sequentially
        for (String round : roundSequence) {
            List<Game> roundGames = gamesByRound.getOrDefault(round, new ArrayList<>());
            
            System.out.println("DEBUG EloCalculator: Processing round " + round + " with Batch Glicko-2");
            System.out.println("  - Games in this round: " + roundGames.size());
            
            // Build opponent data for each player in this round
            Map<String, List<OpponentData>> playerOpponents = new HashMap<>();
            
            for (Game game : roundGames) {
                String player1Key = game.player1.toLowerCase();
                String player2Key = game.player2.toLowerCase();
                
                Glicko2Rating opp2Rating = currentRatings.get(player2Key);
                if (opp2Rating != null) {
                    playerOpponents.computeIfAbsent(player1Key, k -> new ArrayList<>())
                        .add(new OpponentData(opp2Rating.rating, opp2Rating.rd, game.score));
                }
                
                Glicko2Rating opp1Rating = currentRatings.get(player1Key);
                if (opp1Rating != null) {
                    playerOpponents.computeIfAbsent(player2Key, k -> new ArrayList<>())
                        .add(new OpponentData(opp1Rating.rating, opp1Rating.rd, 1.0 - game.score));
                }
            }
            
            System.out.println("  - Players with opponents: " + playerOpponents.size());
            
            // Perform 3 iterations of Batch Glicko-2 for this round
            Map<String, Glicko2Rating> iterationRatings = new HashMap<>(currentRatings);
            
            for (int iteration = 1; iteration <= 3; iteration++) {
                System.out.println("  - Iteration " + iteration + "/3");
                
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
                    String player1Key = game.player1.toLowerCase();
                    String player2Key = game.player2.toLowerCase();
                    
                    Glicko2Rating opp2Rating = iterationRatings.get(player2Key);
                    if (opp2Rating != null) {
                        iterationOpponents.computeIfAbsent(player1Key, k -> new ArrayList<>())
                            .add(new OpponentData(opp2Rating.rating, opp2Rating.rd, game.score));
                    }
                    
                    Glicko2Rating opp1Rating = iterationRatings.get(player1Key);
                    if (opp1Rating != null) {
                        iterationOpponents.computeIfAbsent(player2Key, k -> new ArrayList<>())
                            .add(new OpponentData(opp1Rating.rating, opp1Rating.rd, 1.0 - game.score));
                    }
                }
                
                // Update all player ratings for this iteration
                Map<String, Glicko2Rating> newRatings = new HashMap<>();
                for (String player : playerNames) {
                    String playerKey = player.toLowerCase();
                    Glicko2Rating currentRating = iterationRatings.get(playerKey);
                    List<OpponentData> opponents = iterationOpponents.getOrDefault(playerKey, new ArrayList<>());
                    
                    Glicko2Rating newRating;
                    if (opponents.isEmpty()) {
                        // No opponents - preserve rating exactly
                        newRating = new Glicko2Rating(currentRating.rating, currentRating.rd, currentRating.volatility);
                    } else {
                        // Normal rating update with Batch Glicko-2
                        newRating = updateGlicko2Rating(currentRating, opponents);
                    }
                    newRatings.put(playerKey, newRating);
                }
                
                iterationRatings = newRatings;
            }
            
            System.out.println("  - Batch Glicko-2 complete for round " + round);
            
            // Store final ratings for this round
            result.ratingsByRound.put(round, new HashMap<>(iterationRatings));
            
            // Update current ratings for next round
            currentRatings = iterationRatings;
        }
        
        return result;
    }
    
    /**
     * Calculates PerfElo using Batch Glicko-2 with sigmoid-transformed point margins
     * Uses continuous outcome values based on quality of win
     * Implements same Batch Glicko-2 algorithm as TrueElo but with sigmoid outcomes
     * 
     * @param allGames List of all games with point margins
     * @param playerNames Set of all player names
     * @param initialRatings Map of player -> initial Glicko2Rating
     * @param roundSequence Ordered list of round names
     * @return Glicko2Result containing ratings for all players at each round
     */
    public static Glicko2Result calculateGlicko2PerfElo(
            List<Game> allGames,
            Set<String> playerNames,
            Map<String, Glicko2Rating> initialRatings,
            List<String> roundSequence) {
        
        // Transform games to use sigmoid outcomes
        List<Game> transformedGames = new ArrayList<>();
        for (Game game : allGames) {
            double sigmoidOutcome = pointMarginToOutcome(game.pointMargin);
            transformedGames.add(new Game(game.player1, game.player2, sigmoidOutcome, game.pointMargin, game.roundName));
        }
        
        // Use same Batch Glicko-2 calculation as TrueElo but with transformed outcomes
        return calculateGlicko2TrueElo(transformedGames, playerNames, initialRatings, roundSequence);
    }
    
    /**
     * Converts round name to time step number (for compatibility)
     */
    public static int roundNameToTimeStep(String roundName) {
        roundName = roundName.toLowerCase().replace("round_", "").replace(".csv", "");
        
        switch (roundName) {
            case "1": return 1;
            case "2": return 2;
            case "3": return 3;
            case "4": return 4;
            case "5": return 5;
            case "6": return 6;
            case "t16": return 7;
            case "t8": return 8;
            case "t4": return 9;
            case "t2": return 10;
            default: return 0;
        }
    }
    
    /**
     * Converts time step number back to round name (for compatibility)
     */
    public static String timeStepToRoundName(int timeStep) {
        switch (timeStep) {
            case 1: return "1";
            case 2: return "2";
            case 3: return "3";
            case 4: return "4";
            case 5: return "5";
            case 6: return "6";
            case 7: return "t16";
            case 8: return "t8";
            case 9: return "t4";
            case 10: return "t2";
            default: return "unknown";
        }
    }
    
    /**
     * Gets the previous round column name for database queries
     */
    public static String getPreviousRound(String currentRound) {
        int timeStep = roundNameToTimeStep(currentRound);
        if (timeStep <= 1) return null;
        
        String prevRound = timeStepToRoundName(timeStep - 1);
        return prevRound;
    }
}
