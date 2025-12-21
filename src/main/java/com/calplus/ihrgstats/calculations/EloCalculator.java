package com.calplus.ihrgstats.calculations;

import java.util.*;

/**
 * ELO calculation system using Whole-History Rating (WHR) algorithm.
 * Supports both TrueElo (standard WHR) and PerfElo (WHR + Logistic Mapping for point spreads).
 */
public class EloCalculator {
    private static final double DEFAULT_ELO = 1000.0;
    private static final double KOMI = 7.5;
    
    // WHR parameters
    private static final double W2 = 300.0; // Uncertainty growth per time step
    private static final int MAX_ITERATIONS = 10;
    private static final double CONVERGENCE_THRESHOLD = 0.001;

    /**
     * Represents a single game result
     */
    public static class Game {
        public String player1;
        public String player2;
        public double score; // 1.0 for player1 win, 0.0 for player1 loss, or performance score for perfElo
        public int timeStep; // Round number (1-6, then 16, 8, 4, 2 for tournaments)
        
        public Game(String player1, String player2, double score, int timeStep) {
            this.player1 = player1;
            this.player2 = player2;
            this.score = score;
            this.timeStep = timeStep;
        }
    }

    /**
     * Represents a player's rating at a specific time step
     */
    private static class Rating {
        double r; // Natural rating
        double uncertainty; // Uncertainty (sigma^2)
        
        Rating(double r, double uncertainty) {
            this.r = r;
            this.uncertainty = uncertainty;
        }
        
        double getElo() {
            return r + DEFAULT_ELO;
        }
    }

    /**
     * Calculates TrueElo using standard WHR algorithm (binary win/loss)
     * @param games List of all games to process
     * @param playerNames Set of all player names
     * @param previousElos Map of player names to their previous ELO ratings
     * @return Map of player names to their new ELO ratings
     */
    public static Map<String, Double> calculateTrueElo(List<Game> games, Set<String> playerNames, Map<String, Double> previousElos) {
        // Initialize ratings
        Map<String, Rating> ratings = new HashMap<>();
        for (String player : playerNames) {
            double prevElo = previousElos.getOrDefault(player, DEFAULT_ELO);
            double r = prevElo - DEFAULT_ELO;
            ratings.put(player, new Rating(r, W2));
        }

        // Run WHR algorithm
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            boolean converged = updateRatingsIteration(ratings, games);
            if (converged) {
                break;
            }
        }

        // Convert to ELO ratings
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, Rating> entry : ratings.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getElo());
        }
        return result;
    }

    /**
     * Calculates PerfElo using WHR with performance scores based on point margin
     * @param games List of all games with performance scores
     * @param playerNames Set of all player names
     * @param previousElos Map of player names to their previous PerfElo ratings
     * @return Map of player names to their new PerfElo ratings
     */
    public static Map<String, Double> calculatePerfElo(List<Game> games, Set<String> playerNames, Map<String, Double> previousElos) {
        // Same algorithm as TrueElo, but games already contain performance scores instead of binary scores
        return calculateTrueElo(games, playerNames, previousElos);
    }

    /**
     * Converts a point margin to a performance score using logistic mapping
     * @param pointMargin The margin of victory (positive for win, negative for loss)
     * @param k Steepness parameter (default 0.04 gives good spread)
     * @return Performance score between 0 and 1
     */
    public static double pointMarginToPerformanceScore(double pointMargin, double k) {
        // S' = 1 / (1 + e^(-k * (M)))
        // Note: Komi is already accounted for in pointMargin from CSV
        return 1.0 / (1.0 + Math.exp(-k * pointMargin));
    }

    /**
     * Converts a point margin to a performance score using default steepness
     * @param pointMargin The margin of victory
     * @return Performance score between 0 and 1
     */
    public static double pointMarginToPerformanceScore(double pointMargin) {
        return pointMarginToPerformanceScore(pointMargin, 0.05);
    }

    /**
     * Single iteration of WHR algorithm using Newton-Raphson method
     * @param ratings Current ratings map
     * @param games List of all games
     * @return true if converged, false otherwise
     */
    private static boolean updateRatingsIteration(Map<String, Rating> ratings, List<Game> games) {
        boolean converged = true;

        for (Map.Entry<String, Rating> entry : ratings.entrySet()) {
            String player = entry.getKey();
            Rating rating = entry.getValue();
            
            double oldR = rating.r;

            // Compute gradient and hessian
            double gradient = -rating.r / rating.uncertainty;
            double hessian = -1.0 / rating.uncertainty;

            for (Game game : games) {
                String opponent;
                double score;
                
                if (game.player1.equals(player)) {
                    opponent = game.player2;
                    score = game.score;
                } else if (game.player2.equals(player)) {
                    opponent = game.player1;
                    score = 1.0 - game.score;
                } else {
                    continue;
                }

                Rating opponentRating = ratings.get(opponent);
                if (opponentRating == null) continue;

                double prob = winProbability(rating.r, opponentRating.r);
                
                gradient += score - prob;
                hessian -= prob * (1.0 - prob);
            }

            // Newton-Raphson update
            if (Math.abs(hessian) > 1e-10) {
                double delta = -gradient / hessian;
                rating.r = oldR + delta;

                if (Math.abs(delta) > CONVERGENCE_THRESHOLD) {
                    converged = false;
                }
            }
        }

        return converged;
    }

    /**
     * Calculates win probability using Bradley-Terry model
     * @param r1 Natural rating of player 1
     * @param r2 Natural rating of player 2
     * @return Probability of player 1 winning
     */
    private static double winProbability(double r1, double r2) {
        return 1.0 / (1.0 + Math.exp(-(r1 - r2) / 100.0));
    }

    /**
     * Converts round name to time step number for WHR
     * @param roundName Round name (e.g., "1", "2", "t16", "t8")
     * @return Time step number
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
     * Converts time step number back to round name
     * @param timeStep Time step number
     * @return Round name
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
     * Calculates Whole-History Rating across multiple rounds
     * This method processes ALL games together and returns ratings for each round/player,
     * implementing true WHR where adding new games updates ALL historical ratings
     * 
     * @param games List of all games across all rounds
     * @param playerNames Set of all player names
     * @param initialElos Initial ELO ratings (baseTrueElo/basePerfElo)
     * @param roundSequence List of round names in order
     * @param maxRoundIndex Maximum round index to calculate (0-based)
     * @return Map of round name -> (player name -> ELO rating)
     */
    public static Map<String, Map<String, Double>> calculateWholeHistoryRating(
            List<Game> games, 
            Set<String> playerNames, 
            Map<String, Double> initialElos,
            List<String> roundSequence,
            int maxRoundIndex) {
        
        // Group games by time step
        Map<Integer, List<Game>> gamesByTimeStep = new HashMap<>();
        for (Game game : games) {
            gamesByTimeStep.computeIfAbsent(game.timeStep, k -> new ArrayList<>()).add(game);
        }
        
        // Initialize ratings for each player at each time step
        Map<String, Map<Integer, Rating>> ratingHistory = new HashMap<>();
        for (String player : playerNames) {
            Map<Integer, Rating> history = new HashMap<>();
            double initialR = initialElos.getOrDefault(player, DEFAULT_ELO) - DEFAULT_ELO;
            
            // Create rating entry for each time step where player has games
            for (int timeStep : gamesByTimeStep.keySet()) {
                for (Game game : gamesByTimeStep.get(timeStep)) {
                    if (game.player1.equals(player) || game.player2.equals(player)) {
                        history.put(timeStep, new Rating(initialR, W2));
                        break;
                    }
                }
            }
            
            if (!history.isEmpty()) {
                ratingHistory.put(player, history);
            }
        }
        
        // Run WHR algorithm with temporal links
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            boolean converged = updateWholeHistoryIteration(ratingHistory, gamesByTimeStep);
            if (converged) {
                break;
            }
        }
        
        // Convert to output format: round name -> player -> ELO
        Map<String, Map<String, Double>> result = new HashMap<>();
        for (int i = 0; i <= maxRoundIndex; i++) {
            String roundName = roundSequence.get(i);
            int timeStep = roundNameToTimeStep(roundName);
            Map<String, Double> roundElos = new HashMap<>();
            
            for (Map.Entry<String, Map<Integer, Rating>> entry : ratingHistory.entrySet()) {
                String player = entry.getKey();
                Map<Integer, Rating> history = entry.getValue();
                Rating rating = history.get(timeStep);
                
                if (rating != null) {
                    roundElos.put(player, rating.getElo());
                } else {
                    // Player didn't play this round, use most recent rating
                    Double mostRecent = null;
                    for (int t = timeStep - 1; t >= 1; t--) {
                        if (history.containsKey(t)) {
                            mostRecent = history.get(t).getElo();
                            break;
                        }
                    }
                    if (mostRecent == null) {
                        mostRecent = initialElos.getOrDefault(player, DEFAULT_ELO);
                    }
                    roundElos.put(player, mostRecent);
                }
            }
            
            result.put(roundName, roundElos);
        }
        
        return result;
    }

    /**
     * Single iteration of WHR with temporal links between time steps
     */
    private static boolean updateWholeHistoryIteration(
            Map<String, Map<Integer, Rating>> ratingHistory,
            Map<Integer, List<Game>> gamesByTimeStep) {
        
        boolean converged = true;
        
        for (Map.Entry<String, Map<Integer, Rating>> playerEntry : ratingHistory.entrySet()) {
            String player = playerEntry.getKey();
            Map<Integer, Rating> history = playerEntry.getValue();
            
            // Get sorted time steps for this player
            List<Integer> timeSteps = new ArrayList<>(history.keySet());
            Collections.sort(timeSteps);
            
            for (int i = 0; i < timeSteps.size(); i++) {
                int timeStep = timeSteps.get(i);
                Rating rating = history.get(timeStep);
                double oldR = rating.r;
                
                // Compute gradient and hessian
                double gradient = 0.0;
                double hessian = 0.0;
                
                // Prior term (pull towards initial rating or previous time step)
                if (i == 0) {
                    // First time step: prior pulls towards 0 (DEFAULT_ELO)
                    gradient = -rating.r / rating.uncertainty;
                    hessian = -1.0 / rating.uncertainty;
                } else {
                    // Temporal link to previous time step
                    int prevTimeStep = timeSteps.get(i - 1);
                    Rating prevRating = history.get(prevTimeStep);
                    double diff = rating.r - prevRating.r;
                    gradient = -diff / W2;
                    hessian = -1.0 / W2;
                }
                
                // Link to next time step
                if (i < timeSteps.size() - 1) {
                    int nextTimeStep = timeSteps.get(i + 1);
                    Rating nextRating = history.get(nextTimeStep);
                    double diff = nextRating.r - rating.r;
                    gradient += -diff / W2;
                    hessian += -1.0 / W2;
                }
                
                // Game results at this time step
                List<Game> games = gamesByTimeStep.get(timeStep);
                if (games != null) {
                    for (Game game : games) {
                        String opponent;
                        double score;
                        
                        if (game.player1.equals(player)) {
                            opponent = game.player2;
                            score = game.score;
                        } else if (game.player2.equals(player)) {
                            opponent = game.player1;
                            score = 1.0 - game.score;
                        } else {
                            continue;
                        }
                        
                        Map<Integer, Rating> oppHistory = ratingHistory.get(opponent);
                        if (oppHistory == null) continue;
                        
                        Rating oppRating = oppHistory.get(timeStep);
                        if (oppRating == null) continue;
                        
                        double prob = winProbability(rating.r, oppRating.r);
                        gradient += score - prob;
                        hessian -= prob * (1.0 - prob);
                    }
                }
                
                // Newton-Raphson update
                if (Math.abs(hessian) > 1e-10) {
                    double delta = -gradient / hessian;
                    rating.r = oldR + delta;
                    
                    if (Math.abs(delta) > CONVERGENCE_THRESHOLD) {
                        converged = false;
                    }
                }
            }
        }
        
        return converged;
    }

    /**
     * Gets the previous round column name for database queries
     * @param currentRound Current round name
     * @return Previous round column name, or null if this is round 1
     */
    public static String getPreviousRound(String currentRound) {
        int timeStep = roundNameToTimeStep(currentRound);
        if (timeStep <= 1) return null;
        
        String prevRound = timeStepToRoundName(timeStep - 1);
        return prevRound;
    }
}
