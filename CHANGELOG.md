# Changelog

All notable changes to IHRGStats are documented in this file. Format loosely follows [Keep a Changelog](https://keepachangelog.com/).

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
