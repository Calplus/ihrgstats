# Changelog

All notable changes to IHRGStats are documented in this file. Format loosely follows [Keep a Changelog](https://keepachangelog.com/).

## [Beta 3 Update 17] - 2026-07-25

`/lineup` - the app's actual reason for existing: a seating-order recommendation against a specific opponent, admin-only.

### Added

- Opponent-captain model (`OpponentModel`): recency-weighted per-player seat distributions, an expected 5-player roster, a probability-ranked top-24 list of the most likely full seat orderings, and a captain profile (ordering consistency and reactivity to rematches).
- An exact lineup optimizer (`LineupOptimizer`): enumerates every legal 5-player lineup and seat order (auto-pruned to the top 12 by rating when more than 16 are available, stated in the output), scores each via a two-team half-point DP against the opponent's full predicted ordering distribution, and returns the best-response lineup, the maximin ("safe") lineup, and a named strategy-archetype table (strength order, mirror, single sacrifice, double sacrifice, free optimum) - so a Tian-Ji-style sacrifice gets an actual number, not a guess.
- A per-player `ReliabilityScore` (0-100 + plain-language flags: rating uncertainty, thin history, insular schedule, strength-of-schedule bias) shown for the recommended lineup.
- A fully deterministic "why this lineup" explanation (`LineupExplainer`) built entirely from the optimizer's own numbers - no LLM.
- `/lineup` command: pick the opponent hall (your own home hall is excluded from the list), and get the full report - captain profile, best response, maximin, archetype table, per-board pairing table (model and Glicko side by side), reliability flags, and the explanation.
- 14 new tests, including an exact brute-force cross-check of the DP, a literal Tian Ji scenario proving sacrifice beats strength-order with hand-computable probabilities, the maximin/best-response invariants, and a performance-budget test (12-player roster, 24 opponent orderings, under 2 seconds).

### Scope note

v1 uses your home hall's full active roster automatically - manual availability ticking, seat-locking, and adjusting the opponent's expected roster are real captain-in-the-loop refinements from the original plan, deliberately deferred rather than rushed. The underlying optimizer and opponent model are the full, real engine; only the wizard is simplified.

## [Beta 3 Update 16] - 2026-07-25

Two new admin-only bot commands: the AI/ML layer is now user-facing for the first time.

### Added

- `/predict` - forecasts a hypothetical matchup between any two players. Walks through the same hall/player/hall/player picker as `/compareplayers`, then shows win/draw/loss probabilities from the AI model **and** the plain Glicko baseline side by side, the top factors driving the model's number, and a reliability note (rating deviation, career boards) for each player. Falls back to baseline-only when no model has been trained yet.
- `/modelstats` - the AI model trust dashboard: current champion vs the Glicko baseline in walk-forward backtesting, a leaderboard of every trained model family, and a live scorecard measuring the champion's actual logged pre-round predictions against what really happened (hit rate, mean probability assigned to the realized outcome).
- Both commands are admin-only, matching `/recalculate`/`/matchtypes` - re-checked at every wizard step, not just on entry.
- 11 new command-logic tests (state machine, cancel, session-expiry, admin denial at every step, and full output content once a real model has been trained through the live pipeline).

## [Beta 3 Update 15] - 2026-07-25

Hand-built gradient-boosted matchup model and full upload-pipeline wiring. **Internal only - no bot-facing commands yet** (still admin-only /predict, /modelstats, /lineup to come in a later checkpoint).

### Added

- Expanded the feature set from ~11 to 27 leak-free, as-of covariates per side: rating trajectory, rating stability, hall-vs-own-rating bias, season boards, opponent-quality bias (strength of schedule), graph insularity, rounds missed this season, seat trend, margin form, blowout rate, walkover-received count, forced-timeout rate, and record vs the specific opponent's hall - plus match-type max-score and same-hall context.
- A hand-built, dependency-free gradient-boosted-tree model (`GbmTree`/`GbmModel`): exact-greedy splits on the standard second-order gain, learned missing-value split direction (so unrecorded seats are handled natively instead of always imputed), and an algebraic symmetrization wrapper that guarantees exactly-antisymmetric win probabilities regardless of what the trees learn - proven by a genuine three-way sign-parity interaction the linear model provably cannot represent, verified in a dedicated test.
- Live pipeline wiring: every round upload (and `/recalculate`) now automatically retrains all candidate models, logs each board's pre-round prediction (made with the champion as it stood BEFORE that round, never with hindsight), and refreshes a current-state rolling cache (streaks, recent form, margins) - proven end-to-end with zero direct calls to any ML class.
- 24 new tests, including an end-to-end pipeline test that uploads real rounds through the CSV processor and asserts training, prediction logging, and the rolling cache all fire automatically.

### Fixed

- The walk-forward burn-in heuristic degenerated for any single-year history (the common case for a club's first-ever season): "first year's round count" always equalled the running total, so burn-in chased it upward forever and training could never produce walk-forward evidence no matter how much data accumulated. Single-year histories now use a fixed 10-round floor instead.
- The shaded jar's `Implementation-Version` manifest entry - meant to keep `/about`'s displayed version in sync with the pom - was silently never being written: the `maven-shade-plugin`'s `shade` goal has no `archive` parameter at all (confirmed against the plugin's own descriptor), so that configuration block was dead on arrival. Replaced with the shade transformer's actual `manifestEntries` mechanism, which now genuinely works.

## [Beta 3 Update 14] - 2026-07-25

First checkpoint of the AI/ML implementation plan (Segment A of 7 - see the plan for the full roadmap: covariate-corrected win probabilities, a hand-built XGBoost-style model, player/hall embeddings, and an exact lineup optimizer for `/lineup`). **Internal only - not yet exposed via any bot command.**

### Added

- `ml_models` table + `E17_MlModels` DAO - registry of trained matchup-model runs (parameters, walk-forward backtest metrics, champion flag).
- `com.calplus.ihrgstats.ml` package: leak-free per-board feature extraction (`FeatureExtractor`, single-forward-pass Glicko ratings, seat-as-prior shrinkage for cold-start players), a free Glicko-2 baseline predictor, a hand-rolled two-stage symmetric logistic regression model (draw stage + win stage, exact Newton-Raphson solver), a walk-forward backtest harness (Brier score, log-loss, calibration, paired comparison vs baseline), and a trainer that persists every run and crowns a champion only when it measurably beats the Glicko baseline.
- 22 new tests, including permanent leakage-guard, signal-recovery, and noise-guard regression tests that keep the model honest going forward.

## [2.0.0] - 2026-07-19

A ground-up rewrite of the data layer and rating engine, developed under the internal codename "Beta 2" (commits `b2u7`-`b2u14`). The internal `Beta 2 Update N` version label is retired in favor of semantic versioning, starting here at `2.0.0`.

### Breaking Changes

- **Database schema rewrite.** The legacy wide-table schema (one row per player, with per-round suffix columns like `trueEloR1..T2`) has been replaced by a normalized relational schema across 14+ tables (`A1_Rounds`, `A2_MatchTypes`, `A3_Halls`, `B4_Players`, `B5_PlayerNames`, `B6_PlayerYearStatus`, `B7_CappedImports`, `C8_Matches`, `C9_MatchParticipants`, `D10_RatingTypes`, `D11_PlayerRatings`, `D15_PlayerRatingSnapshots`, `F16_Admins`, plus reserved-but-inert `E12`-`E14` AI/ML tables for future use). **v1.x databases cannot be opened by v2.0.0** - there is no automated migration path.
- **Round CSV format changed.** Bracket-named files (`round_t2.csv`, `round_t4.csv`, `round_t8.csv`, `round_t16.csv`) and `winby1`/`winby2` columns are retired. Rounds are now sequential (`round_{n}.csv` or `{year}_round_{n}.csv`) with `score1`/`score2` columns, and are no longer tied to a fixed swiss/bracket split. Legacy-format samples are preserved under `SAMPLE FILES/backup_old_format/` for reference.
- **`/exportplayers` removed**, merged into `/exportdatabase` (choose a full `.xlsx` export, one sheet per table, or a raw `.db` backup/restore file).
- **Match types are now required for walkovers.** A match type must be created and assigned to a round via `/matchtypes` before that round can process a `WALKOVER` result, since the walkover's default score is derived from the match type's max score.

### Added

- `/matchtypes` - create, list, and edit match types (name, max score, time limit, description), and assign one to a round.
- `/recalculate` - on-demand whole-history rating recalculation (5-pass, every round across every year, year-aware). Also now runs automatically after every round upload.
- Point-in-time rating snapshots, so "rankings as of round N" views stay accurate even after a later recalculation.
- `/admins` - list, add, or remove Telegram bot administrators; refuses to remove the last remaining admin to prevent lockout.
- **All Years mode** across `/rankplayers`, `/rankhalls` (all-time roster using cross-year cumulative rating), `/comparehalls`, `/compareplayers`, `/infohall`, `/infoplayer` (one row per year); `/infomatch` and `/infomatchhall` round pickers now span every year on record.
- `TIMEOUT` result handling - a real, rated win/loss for games decided by a clock timeout, distinct from a `WALKOVER`.
- Generic full-database `.xlsx` export in `/exportdatabase` (one sheet per populated table, read directly off `sqlite_master` so it stays correct as the schema evolves) - replaces the old curated, player-only `/exportplayers` workbook. The raw `.db` export option is unchanged from before.

### Changed

- Rating engine moved from per-round-only calculation to a whole-history WHR-style recalculation that replays the full tournament history whenever ratings change.
- `README.md` substantially rewritten to match current behavior (sample files, settings, commands).

### Fixed

A from-scratch bug audit (independent of the original build) found and fixed 7 medium-severity and 14 low-severity issues, each with a regression test, including:
- Settings toggles (allow non-admin uploads, allow all-channels processing) not taking effect until restart.
- A `round_0.csv` upload could wipe an entire year's data.
- Unbounded log buffer growth over long uptimes.
- Fuzzy player-name matching could bypass hall verification.
- Capped-flag state could cross-contaminate between players.
- A race condition between `/exportdatabase` and a concurrent database writer.
- Heavy commands (e.g. large uploads) blocking the Telegram polling thread.
- `/comparehalls` win-probability, last-round, and multi-opponent display bugs; walkover boards not merging into hall-vs-hall victory tallies; wide `/comparehalls` images clipping at high round counts; stale multi-choice button state; `.env` file value escaping; `/about` version drifting from the actual build.

### Migration Notes

- There is no automated upgrade path from a v1.x database - start fresh for a new season, or reach out if you need historical v1 data ported in by hand.
- Rename existing round files to the new `round_{n}.csv` / `{year}_round_{n}.csv` convention before uploading. Old bracket-named samples remain in `SAMPLE FILES/backup_old_format/` for reference only.
- Create at least one match type via `/matchtypes` before uploading any round containing a `WALKOVER`.

---

Prior releases (`v1.0.0` - `v1.1.5`) predate this changelog; see [GitHub tags](https://github.com/Calplus/ihrgstats/tags) for that history.
