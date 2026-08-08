# Changelog

All notable changes to IHRGStats are documented in this file. Format loosely follows [Keep a Changelog](https://keepachangelog.com/).

## [Beta 3 Update 27] - 2026-08-08

Test-hardening and visual-audit close-out of the full-codebase review. Every test class was read end to end for authenticity (tautologies, circular oracles, no-exception-only tests, over-mocking, swallowed assertions - none found anywhere), the suite was stress-tested with deliberate bugs, and the rendered output of every image command was re-audited on a denser variant matrix.

### Added

- **28 new tests (273 → 301)** closing every practically-testable gap the audit surfaced:
  - Regression tests for the review's bug-fix batch: the 3-character containment rule (a 2-char name no longer dialogs every containing debut, a 3-char one still does), the fuzzy returning-player hall-mismatch dialog (both keep-old-hall and use-new-hall resolutions), the one-capped-row-per-appearance claim, negative-score rejection in both validation branches, `formatHallName(null)`, the win-probability strongest-boards ordering (a capped-first team order can no longer field the wrong board), the identity-keyed All-Years seating collector (a renamed player is one row under the latest name; two same-named people stay two rows), `/predict`'s no-current-year refusal, deterministic name order on exact rating ties, renamed-round labels in text headers, `message_thread_id` emitted as a JSON number (with empty/malformed omitted), wrong-channel wording fallback, the HTTP client factory actually applying its connect timeout (and the long-poll timeout exceeding the 30s hold), schema creation failing fast (provoked against a real readonly database file), and `/admins` reporting "already an admin" with the table unchanged.
  - Direct `/recalculate` tests: the empty-database path, exact reported counts cross-checked against the stored rows, and honest skip notes for the ML steps alongside a successful recalculation.
  - Direct DAO tests for the bulk latest-row-per-player queries behind the ranking rewrite: last-write-wins per player, the inclusive round boundary, and year/rating-type isolation (for both current ratings and snapshots).
  - `/help` Back-button tests (Back returns the main menu; the submenu carries the routed callback) and a message-chunker test for the one window nothing exercised: a message whose raw length fits the limit but whose converted length exceeds it.
- **Deliberate-bug (mutation) verification of the suite**: 8 deliberate bugs introduced one at a time - rating-update sign flip, idle-round RD reset, ingestion outcome-comparison flip, identity-containment threshold, bulk-query boundary, walkover boards leaking into ML extraction, a dropped export row, and the chunker's length gate measuring raw text. 7 were caught immediately by existing tests (the dropped export row by the corpus export round-trip, off by exactly one row). The chunker gate survived the entire suite - that genuine gap became the new chunker test above, which now catches it. The tree was verified byte-identical after every check.
- **Visual audit on a denser matrix**: 33 variants (every image command; thin single-outlier variants merged into denser ones, each image's full outlier inventory written to the coverage map), including **5 All-Years views audited for the first time**. Every image inspected: **no rendering defects**. The All-Years hall comparison now shows a year-long renamed player as ONE seating row under the most recent name with every year column filled - the intended rendering of the identity-keyed fix, previously two disjoint rows.

### Fixed

- `/help` typos: "Can bee same hall" → "Can be same hall"; "avereage" → "average" (twice).

### Changed

- Version and `/about` date bumped. Two private helpers (the listener's send-target/thread-description helpers and the hall comparison's All-Years row collector) widened to package-private so they can be unit-tested directly - no behavior change.

### Notes

- Recorded observation, deliberately unchanged pending a decision: All-Years rankings show each player's last-played round label without a year qualifier ("R10" could be any season's round 10).
- 301 tests, 0 failures.

## [Beta 3 Update 26] - 2026-08-07

Performance + deduplication batch from the full-codebase review. Strictly behavior-preserving: the corpus exact-value battery and the full suite pass unchanged (273/0), and the perf harness was re-run against the recorded pre-review baseline.

### Changed

- **The per-player rank-map query collapsed to three flat queries**: `RankingQueryHelper.getLatestRatingsUpToRound` ran up to two queries per active player (snapshot probe + ratings fallback); it now runs one bulk latest-ratings query, one bulk latest-snapshots query overlaid on top, and the active-roster filter - same signature, player-for-player identical output, and every ranking/info/compare view benefits.
- **`/comparehalls` ported onto the bulk-loading path** it never received: a new shared `HallStatsBuilder` (extracted verbatim from `/infohall`'s already-optimized report body) builds the per-round context (point-in-time ratings, rank maps, participants, halls) ONCE per comparison and shares it across BOTH halls - previously each hall re-fetched the point-in-time rating, the FULL rank map, the participant row, the opponent row and the opponent's hall once per player per round (thousands of queries per rendered view on a full season, multiplied by year count in the All-Years view, which now also builds each year's context once for both halls).
- **`/compareplayers`/`/infoplayer` share one `PlayerStatsBuilder`**: their byte-identical ~50-line `fetchPlayerData` copies (plus `YearSummary` building and `formatWinLoss`, which was itself a byte-identical copy of `VictoryRecordCalculator.formatScorePair`) now live in one class, with per-view caches so a two-player comparison computes each round's rank map once, not once per player.
- **Listener scaffolding deduplicated (~350 lines)**: the fifteen slash-command handlers' copy-pasted frame (extract userId → run command → dispatch → catch/log/report) collapsed onto one `runCommandHandler` scaffold (the command-side twin of the existing callback scaffold; which handlers run on a worker thread is unchanged per handler); the "determine where to send" chat/thread resolution block (7 copies) onto `resolveSendTarget`/`applySendTarget`; and the three byte-identical multipart body builders (photo, document, DM file) onto one `postMultipart` core with the photo/document senders merged into a single parameterized frame.
- **Image generators**: the byte-identical name shortening/truncation twins (~90 lines) moved to `ImageRenderSupport`; `TableImageGenerator`'s player/hall table methods (~100 near-identical lines) merged into one core whose only branch is the hall-icon column. Hygiene: three undisposed font-metrics `Graphics2D` now disposed, dead imports removed from all three generators, and two long-deprecated `VictoryEntry` fields with zero readers deleted.
- **Smaller**: whole-history recalculation caches the per-(hall, year) status list instead of re-querying it for every round; `.xlsx` export sizes columns from the header plus a bounded 200-row sample (character-count based) instead of font-measuring every cell of every table - widths are display-only; single-key property reads no longer regex-resolve every `${}` in the file per lookup; `/settings` menu keys are sorted (previously JVM-dependent Hashtable order); `/lineup`'s "pruned to top N" message references the optimizer's actual constant; a double filename parse in the upload router hoisted.

### Performance (benchmark: 12 rounds, 3 halls, 24 players; baseline recorded pre-review at the same commit the review audited)

- `/infohall` all-rounds view: **609 ms → 421-480 ms** (two after-runs)
- `/rankplayers` all-rounds view: **124 ms → 94-109 ms**
- `recalculateAll`: **68 ms → 53-60 ms**
- retrain+distill+cache: 1494 ms → 1514-1540 ms (untouched path, within noise)
- ingest 12 rounds: 9850 ms → 10378-10685 ms - within this metric's historical run-to-run swing (it has varied 2x across sessions on identical code); nothing on the ingest path changed except cheaper recalculation
- The benchmark has no `/comparehalls` probe; its improvement is structural (the per-player-per-round query pattern is gone) and scales with roster and season size.

### Notes

- Deliberately NOT deduplicated after review: `/infomatchhall`'s per-opponent tally (a third copy of the fix, but a genuinely different shape - flat single-round fields plus the walkover "3-2" normalization); the round-scoped rewrite of upload prediction logging and the rolling-cache read loop (its most-recent-first ordering assumptions are load-bearing, per the previous perf pass).
- The send-target dedup tightened one corner: senders that previously attempted a doomed send to an empty configured chat id now skip it (reachable only in misconfigured setups).
- Visual-audit variants re-rendered and inspected after the image-generator changes.
- 273 tests, 0 failures.

## [Beta 3 Update 25] - 2026-08-07

Bug-fix batch from the full-codebase review (all 89 main files read end to end, findings reported first, fixes applied only after approval). Headline: every outbound HTTP call in the app could hang forever on a silently dropped connection - for the polling thread that meant a permanently deaf bot whose status heartbeat kept reporting "online".

### Fixed

- **HTTP timeouts everywhere**: `java.net.http` has no default connect or request timeout, and no call site set one - a silently dropped TCP connection (NAT/firewall drop, network flap) left `HttpClient.send` blocked forever. The Telegram long poll, every message/photo/document sender, both log senders, file downloads and `/about`'s getChat lookup now build their clients through a new `HttpClientFactory` (10s connect timeout) and set per-request timeouts: 45s on the long poll (comfortably above the 30s server-side hold), 30s on ordinary calls, 120s on file transfers.
- **Heartbeat can no longer mask a dead polling loop**: the 5-minute status heartbeat runs on its own executor, so it kept reporting "Bot is online and monitoring" even with the polling thread hung or dead. It now checks the polling loop's liveness stamp and sends an explicit stale-poll warning when no poll has completed for over 2 minutes.
- **`/help`'s "🔙 Back" button did nothing**: both help menus carry a Back button whose `help_back` callback matched no listener branch - clicking it stripped the keyboard (the shared scaffold does that before routing) and left the user stranded with no menu. It now re-sends the main help menu.
- **`/modelstats` no longer stalls the polling thread**: it was the one report command still executed synchronously on the polling thread, yet its live scorecard re-extracts every board in the database. It now runs on a worker thread like its siblings, and its per-model decode cache actually caches misses (a missing model version was previously re-queried once per logged prediction).
- **Shutdown visibility**: `isRunning` is now volatile - the polling loop reads it while `stop()` writes it from another thread; without the barrier the JMM permitted the loop to never observe the stop.
- **Schema creation fails fast**: a failed `CREATE TABLE`/`CREATE INDEX` was logged and skipped, leaving a half-built schema that surfaced as confusing failures at first use. It now throws at startup, like connection-level failures always did.
- **Negative CSV scores rejected**: score validation accepted any finite number, so a "-5" was silently ingested and decided outcomes by comparison.
- **`/comparehalls` All-Years seating matrix keyed by player identity**: rows were keyed by display name, so a player renamed across years split into two rows (and two different players sharing a name would merge). Now keyed by player id with the most recent name shown - the same convention the player/hall info views already use.
- **Deterministic ranking order on exact ties**: rating sorts had no tiebreak over HashMap-ordered input, so equal-rated players/halls could swap display order between runs/JVMs. Name (then hall/id) tiebreaks added to `/rankplayers`, `/rankhalls` and the hall roster tables in `/infohall`/`/comparehalls`.
- **Round-scoped text headers show the round's real label** in `/rankplayers` and `/rankhalls`, matching the image-metadata fix from the visual audit (the raw selection value is an internal round number; the two can disagree on custom labels).
- **`message_thread_id` sent as a number by the remaining senders**: half the send paths were fixed previously with an explicit "Telegram expects a number" note; the commands-channel and button senders still sent the string form. All JSON senders now parse to int.
- **Over-length message recovery**: a Telegram 400 for "message is too long" (reachable on unchunked paths, e.g. an exception message interpolated into an error report) now re-sends the payload through the message chunker instead of being dropped after a futile parse_mode-strip retry.
- **Wrong-channel error wording** no longer renders "Thread ID  (...)" with a blank in half-configured setups - it falls back to a generic channel label when the thread ID is empty.
- **`/admins` replies "already an admin"** instead of a false "✅ Added" when the target already existed (the insert is if-absent).
- **Discord log 429 retries capped** at 5 per message - a persistently rate-limited channel could spin the log worker forever and block the whole queue behind one message.
- **One capped-list row claimed per player**: on first appearance a player claimed every unmapped same-name capped row, so if a year's list legitimately contained two distinct same-named people, the first to appear took both rows and the second was never flagged capped.
- **`/predict` refuses without a current year** (parity with `/lineup`) instead of dressing up a meaningless "year 0" empty-features board as a real prediction.
- **`formatHallName(null)`** hardened to "?" instead of an NPE.

### Changed

- **Name-containment matching requires 3+ characters on the shorter side**: any name containing a very short existing name (a 2-letter "Ng" inside every future "Nightingale...") triggered the same-person dialog on every such debut, forever. The word-overlap and Levenshtein typo checks are unaffected. The four sample-corpus dialogs where the 1-char name "X" partial-matched unrelated debuts no longer fire; all four were answered "different people", so ingestion outcomes are byte-identical - only the dialog noise is gone.
- **Fuzzy-confirmed returning players get the hall-mismatch dialog**: a typo'd returning player who had also moved halls silently adopted the CSV row's hall, while the identical situation via exact name match prompted "Keep old hall / Use new hall". Both paths now share one dialog implementation.
- **Pending yes/no confirmations outrank wizard text input** for exact yes/y/no/n answers: a user simultaneously mid-`/settings` text input and awaiting an upload confirmation had their "yes" swallowed by the wizard as invalid input while the confirmation timed out at 60s. Any other text keeps the wizard-first priority.
- **`/comparehalls` win probability sorts both selected teams strongest-first**: the capped-filter branch returned its team capped-first, and with unequal roster sizes only the first N entries play - that played subset is now the strongest available boards. The displayed % changes only in that narrow (>2 capped in the top 5 AND unequal rosters) case.

### Notes

- 273 tests, 0 failures.

## [Beta 3 Update 24] - 2026-08-01

Visual audit pass: every image-producing command rendered and manually inspected across a parameterized variant matrix (30 variants over two fictional datasets), two long-standing bugs fixed - one of them breaking the .xlsx export outright on any database with a trained model - and the database export round-trip is now verified end to end.

### Fixed

- **`/exportdatabase` .xlsx export failed on any database with a trained AI model**: Excel hard-caps cell text at 32,767 characters and POI throws past it, so the champion model's serialized parameters (`ml_models.params_json`) killed the whole workbook write. Oversized values are now truncated with an explicit `...[truncated]` marker - the .xlsx dump is the human-readable reference, the .db file remains the lossless recovery path. Found by the new export round-trip verification below.
- **`/rankplayers` round-scoped image label**: a single-round ranking now labels itself with the round's real label ("Last Round: Round 6") instead of the raw selection value, which could surface an internal round number on multi-year databases. Uses the same round resolution the halls ranking already had; present since at least v1.1.5.
- **commons-io version conflict pinned**: the Telegram library drags in commons-io 2.15.1, which (by Maven nearest-wins) shadowed POI 5.3.0's required 2.16.1 and broke POI's entire workbook READ path with a `NoSuchMethodError` - invisible until now because the app only ever wrote workbooks. An explicit commons-io 2.16.1 dependency restores the version POI declares. Also found by the export round-trip verification.

### Added

- **`VisualAuditHarness` rebuilt as a property-gated variant matrix** (run manually: `mvn test -Dtest=VisualAuditHarness -Dvisual.audit=true -Dsurefire.failIfNoSpecifiedTests=false`; no longer an `@Disabled` annotation to edit). All 8 image commands render 3+ parameter variants each into flat `temp/visual-audit/exports/<nn>_<command>_<variant>.png` files with a written per-image outlier-coverage map, from two datasets:
  - the synthetic season, extended to cover both TIMEOUT side conventions (including the blank-winner-score 0-0 form), stated-hall AND blank-hall walkovers (including a WALKOVER-as-name1 row exercising the unknown-hall fallback), a full-win-vs-0 board, a quoted comma-name with a mid-season debut, a near-duplicate name pair, capped and dormant players, a bye round and a multi-opponent round;
  - the committed 4-year sample corpus, ingested through the real pipeline - its trained champion supplies the ExpElo-populated ranking/player/comparison variants.
- **`/exportdatabase` round-trip verification** appended to the corpus ingestion test: the exported `.db` snapshot is reopened as its own database home and held against the full integrity battery, and the `.xlsx` dump is opened through POI and checked sheet-by-sheet against live row counts, real column headers and expected content.

### Notes

- Audit observations recorded as established, unchanged behavior: the round-wide match view uses the neutral "??" watermark; unknown-hall walkover sides render as "??"/"WALKOVER"; the rankings hall column shows short codes for long hall names; ranking tables switch their row-color scheme every 10 rows; the hall info view shows a multi-opponent round against its primary opponent (per-board detail lives in the hall match view); the player info view carries no capped marker (the rankings and hall info views do); a hall's bye round in the hall match view is a text-only response by design.
- 273 tests, 0 failures.

## [Beta 3 Update 23] - 2026-07-31

Corpus validation pass: the sample data was regenerated from scratch as a four-season fictional dataset (2001-2004, 11 halls, 39 round files plus a capped list per year) with a realistic tournament structure, and is proven ingestible end to end - a fresh v2-schema database is built through the real ingestion pipeline and held against an extended integrity battery, an independent recount of every board, and determinism checks. All validation runs exclusively on the fictional sample corpus.

### Changed

- **Sample corpus regenerated: four fictional seasons, eleven halls** (`{year}_round_{n}.csv` + `{year}_cappedlist.csv`, matching the app's own naming conventions; the previous single-season files are replaced):
  - **Season structure**: day 1 is Swiss rounds (six per year, five in the reduced 2003 season) - eleven teams pair into five matchups of exactly five boards each, with a rotating bye emitted as a team-walkover block; day 2 is a knockout bracket (last-16, quarter, semi, final) seeded by cumulative board wins, top seed vs weakest, the middle seed drawing the walkover on odd counts. Eliminated halls have no rows in later rounds.
  - **Three more production halls join** (6, 7, 8) plus a third fictional hall (HallC) - a deliberate stress-test roster: a 51-character hyphenated name, a quoted comma+apostrophe+hyphen name, escaped quotes inside a quoted name, lowercase particles, a single-letter name, a three-letter name, and a name that sorts last everywhere.
  - **A capped list per year**, entries claimed as players first appear that season; two entries deliberately never play their listed year and must stay unmapped staging rows. At most 3 capped players per hall per year and at most 2 fielded per hall per match hold throughout.
  - **Outlier coverage across the four seasons**: 13 TIMEOUT boards (both side conventions, every winner score left blank - the legal convention - stored as rated 0-0 wins); 162 walkover sides (whole-hall team blocks including a WALKOVER-as-name1 row, plus eight individual short-handed walkovers with the opposing hall stated); four 370-0 board sweepouts; 19 draws including one 0-0 standard draw; nine scripted misspellings (single-round typos, a short-form partial name, and two year-long variants - one of them a returning player misspelled all season); two hall moves; a cross-hall near-duplicate name pair; a same-name-different-person pair in different halls and years; a mid-season debut; sit-out-and-return seasons; rostered-but-never-fielded players; and players who appear in exactly one round of a season alongside one who plays every round of all four.
  - `SAMPLE FILES/corpus_notes.md` records the structural facts: per-year rosters (never-fielded players annotated), per-round outlier locations, champions, the capped lists, and the exact identity-dialog script the corpus fires on ingestion.

### Added

- `CorpusIntegrityChecks` gains two structural checks, run as part of the battery: no hall fields more than the expected maximum of 5 players in any round, and no hall uses more than 7 distinct players across a year.
- `CorpusIngestionTest` rewritten for the four-season corpus: ingests all four years in order through the real `CappedListProcessor` + `RoundCsvProcessor` (capped list first each season, every interactive dialog answered exactly as an admin would; anything unexpected cancels and fails loudly), asserting the complete dialog script in order - 31 match-type dialogs (independently derived from the CSVs) and 18 identity dialogs. Then: identity outcomes (typos and both year-long variants merged onto their players, near-duplicates split, hall movers kept as one identity with the right hall per season, the same-name pair kept as two people with disjoint years); per-year capped mapping including the two permanently unmapped rows and the negative pins (variant-season and sat-out players not flagged); the ML burn-in boundary (a single ingested season trains no champion; the finished corpus must hold a champion with distilled ExpElo, whose per-round parity with TrueElo the battery asserts); an INDEPENDENT recount of every board of all 39 files (per-round multiset comparison of player/type/score/outcome signatures) plus per-season W/D/L per player; and whole-history recalculation determinism, which must also leave the distilled ExpElo rows untouched. The same-year cross-hall duplicate-name rejection test is unchanged.

### Notes

- The corpus keeps its deliberately FICTIONAL hall names (HallA/HallB/HallC) so nothing in the repo mirrors real hall data; they are not part of the production hall seed, so `CorpusIngestionTest` creates them up front (ingestion hard-fails on unknown halls, which the shipped samples would otherwise hit against a stock database).
- The corpus clears the ML burn-in during its second season, so the fully ingested corpus database ends with a trained champion and populated ExpElo ratings - groundwork for the upcoming image-export audit.
- The corpus test deliberately runs the full production after-steps on all 39 uploads (whole-history recalculation, model retraining, ExpElo distillation), so the suite now takes ~7.5 minutes - an accepted trade-off for running the full-depth corpus on every build.
- Test-data hygiene: name literals quoted in a few older comments and tests were replaced with fictional sample-corpus names; all testing runs exclusively on the fictional sample data.
- Still open: direct `CommandRecalculate`/DAO-edge tests.
- 273 tests, 0 failures.

## [Beta 3 Update 22] - 2026-07-30

Performance pass over the write and read hot paths, measured with a new reproducible benchmark (synthetic 12-round, 3-hall, 24-player season through the real ingest pipeline). Results are bit-identical before and after - pinned by the existing determinism tests plus a new database-level regression test.

### Changed

- **Whole-history rating rewrite batched**: the recalculation previously opened a fresh SQLite connection (with per-connection PRAGMA setup) for every single delete and insert - 600+ connections per recalc. It now writes through one connection with one transaction per round (`D11_PlayerRatings.replaceRatingsForRounds`), preserving the old round-granularity failure isolation exactly. **Recalc: 1190 ms → 88 ms (−93%)** on the benchmark; the ExpElo distillation write path uses the same batched API.
- **One feature extraction per retrain cycle**: the trainer's `TrainOutcome` now carries its extracted boards and the ExpElo distillation reuses them (nothing writes boards in between) instead of re-running the whole-database extraction; only a training failure falls back to extracting afresh. `/predict`'s hypothetical-board builder also no longer extracts twice per call. **Retrain+distill+cache cycle: 2642 ms → 1664 ms (−37%).**
- **Champion model decoded once, not per call**: `PredictionService.loadChampion()` now caches the decoded predictor, keyed by database path + the champion row's content-hashed `model_version` - so a retrain invalidates it naturally on the next load. `/predict`, `/lineup` and the upload's prediction hook all benefit.
- **Rolling cache upserts batched**: one connection/transaction for the whole roster instead of one connection per player.
- **`/infohall` report body bulk-loads its per-round context** (point-in-time ratings, rank maps, participants, opponents, halls) once per round instead of once per player per round - previously hundreds of queries per rendered view, including a full rank-map rebuild per player. **View render: 2842 ms → 739 ms (−74%).** Backed by a new `RankingQueryHelper.getPointInTimeRatingsForRound` (two queries per round, player-for-player identical to the per-player probe).
- **`/rankplayers` last-played-round column** now builds one player→label map in a single newest-first pass instead of probing one query per round per player (quadratic for players who sat out recent rounds).
- Full ingest of the benchmark season (12 rounds incl. per-round recalc + ML hooks): **28.9 s → 19.1 s (−34%)**; the test suite drops 1:17 → 1:02 as a side effect.

### Added

- `PerfBenchmarkHarness` - the reproducible benchmark behind the numbers above; excluded from the normal suite, run manually with `mvn test -Dtest=PerfBenchmarkHarness -Dperf.benchmark=true`.
- `RatingRecalculatorBatchWriteTest` - pins the batched rewrite at the database level: rerunning the recalculation is row-for-row deterministic, and stale rows for players outside the rated set are still swept per round.

### Notes

- Deliberately untouched: the rolling-cache read loop (its most-recent-first ordering assumptions are load-bearing) and the per-player status/hall lookups in `/rankplayers` (linear, small constant). Still open from the previous update: direct `CommandRecalculate`/DAO-edge tests.
- 271 tests, 0 failures.

## [Beta 3 Update 21] - 2026-07-30

Structural deduplication pass - the large extractions deliberately deferred from the previous update's sweep, each locked in behind new or existing tests first. Behavior-preserving throughout; ~1,200 lines of main code removed net while test count grows 241 → 270.

### Added

- `CommandInfoHall` characterization tests - the largest previously untested class (853 lines) now has 7 tests covering the hall→round wizard, the all-rounds/single-round/all-years report bodies, walkover attribution, name truncation, session expiry and cancel.
- `/matchtypes` create-wizard tests (step walkthrough, per-step validation, fail-closed admin gate, cancel), `CsvLineParser` tests (quoted comma-names, escaped quotes, empty fields), `VictoryRecordCalculator` contract tests (all outcome/score/delta/hall-name formatting), and tests for the new shared keyboard builders. 29 new tests total.

### Changed

- **Shared wizard keyboards**: the hall-picker, player-picker and round-picker inline keyboards - previously ~15 hand-rolled copies across the eleven selection wizards (`/infohall`, `/infoplayer`, `/infomatch`, `/infomatchhall`, `/compareplayers`, `/comparehalls`, `/rankplayers`, `/rankhalls`, `/predict`, `/lineup`) - are now built by one `SelectionKeyboards` class. Button labels, ordering, callbacks and the unknown-hall exclusion are unchanged and pinned by tests.
- **One `ButtonConfig`**: `/settings` and `/exportdatabase` had their own private near-copies of the shared button class (with a different default column count), each requiring its own typed sender in the listener - both now use the shared class, with `/settings`' historical one-column default preserved explicitly. The ~70-line settings sender collapsed to a delegate of the shared column sender.
- **`TelegramListener` callback scaffolding deduplicated**: all 13 per-command callback handlers repeated the same ~25-line frame (extract message → strip keyboard → spawn worker thread → error logging) plus the same reply patterns - now one `runCallbackRouting` scaffold plus shared `sendStepOrPlain` / `sendReportWithImage` helpers. Which handlers run on a worker thread vs inline is unchanged per handler. The seven inline `/settings` callback branches likewise collapsed onto one delivery helper.
- **`TelegramLog`/`DiscordLog` shared core**: the two structurally twin log classes (~1,350 lines maintained in parallel) now extend one `ChannelLog` base holding the ordered send queue, batch buffer, INFO-accumulation buffer, char-limit splitting and shutdown flush. Everything wire-specific stays per-platform: config keys, HTML escaping (Telegram only), send format, rate-limit parsing, the deliberately different retry strategies, and admin mention formats.
- **Image generators**: the byte-identical rotated-watermark tiling and filename sanitization (three copies) extracted to `ImageRenderSupport`. Header layouts were compared and deliberately kept per-generator - they are genuinely different designs, not duplicates.
- `/compareplayers`, `/comparehalls` and `/predict` now expire stale wizard states after 10 minutes like every other wizard (they were the last three commands whose abandoned selections lingered forever).

### Removed

- The private `formatHallNameForImage` copies in `/infohall` and `/comparehalls` (now the shared `VictoryRecordCalculator.formatHallName`, making zero-padded numeric hall names render consistently with `/infomatch`/`/infoplayer`) and the duplicated `deltaDoubleString` copies; a write-only `playerId` field in `/compareplayers` (verified absent in v1.1.5).

### Notes

- Still open for the next passes: direct `CommandRecalculate`/`RankingQueryHelper`/DAO-edge tests (currently exercised indirectly through the pipeline and command tests) and the performance batch (transaction batching, N+1 elimination, shared feature extraction).
- 270 tests, 0 failures.

## [Beta 3 Update 20] - 2026-07-30

Whole-app refactor sweep: every package reviewed end to end against the last manually-verified release (v1.1.5) - duplicated code combined, dead code removed, hot paths sped up. Behavior-preserving throughout (full suite green before and after); ~750 lines of main code removed net.

### Changed

- `.env.properties` is now read from disk once per working directory per process (`EnvironmentManager.ensureSystemPropertiesLoaded()`) instead of on every command construction - previously every incoming Telegram message re-read and re-parsed the file across 17 constructor call sites. Runtime `/settings` changes still apply instantly (they already mirror into system properties directly). Also cuts the test suite's wall time by ~25%.
- Dual Discord+Telegram logging pairs collapsed into the existing `LogHelper` across `DatabaseSchema`, `Main`, `TelegramListener`, `/settings`, `/admins`, `/exportdatabase` and `/matchtypes` (~120 duplicated call pairs removed). `LogHelper` gained a wrapping constructor and `flush()` for the listener's deliberately asymmetric single-platform logging.
- Four byte-identical ~70-line button-keyboard senders in `TelegramListener` (compare halls/players, rank players/halls) merged into one shared column-layout sender; `sendMessageToChat` now goes through the same shared sender as every sibling, gaining the plain-text fallback retry it alone lacked.
- `deltaString` and `formatScorePair` (five and five private copies across the info/compare commands) centralized into `VictoryRecordCalculator`; the home-hall `*` row-marker post-processing duplicated between `/rankplayers` and `/rankhalls` extracted to `TableFormatter.markRows`; `/rankhalls` now shares its table constants/row builder between text and image like `/rankplayers` already did; `/infomatch`'s per-matchup display logic deduplicated between its text and image builders.
- `TimezoneHelper`'s triple property-read consolidated; timezone display formatting shared between `TimezoneHelper` and `/settings`.
- `OutcomeIconRenderer` now caches failed icon loads with a sentinel (mirroring `HallIconLoader`) instead of re-reading the classpath on every row render.
- `/infomatchhall` no longer refetches the full per-round rating map once per player (was O(players²) queries per view).

### Removed

- **JDA (Java Discord API) dependency removed from the build** - its only consumer was `DiscordOnlineStatus`, a presence-setting class with zero callers in this codebase AND in v1.1.5 (verified). Discord *logging* is unaffected (it uses Discord's plain HTTP API). Shaded jar shrinks accordingly.
- Dead code, verified unused in v1.1.5 too before deletion: `CommandAbout.formatTimezone` (never called) plus its dead locals/imports, `VictoryRecordCalculator.calculateWinPercentage` (zero callers ever), `TableFormatter`'s unreachable no-separator row format, two unused `generatePlayerTable`/`generateHallTable` overloads each, `TelegramListener`'s unused `webhookPort` field, `getLogChatIdAndThread`, `sendFatalErrorAndStop`, two unused send overloads, and unused fields on its confirmation-request holders; `RoundCsvProcessor`'s unused `B4_Players` field and a single-use wrapper method.

### Added

- Round upload now warns (non-blocking, same convention as the max-players warning) when a recorded score exceeds the round's match type `max_score` - previously an impossible score like 190 in a max-21 format was silently accepted.
- `/settings` now expires stale manual-input states after 10 minutes via the same `cleanupOldStates` every other wizard already used (it was the only state-holding command that never cleaned up).

### Notes

- v1.1.5 cross-check: every deletion in code that existed at v1.1.5 was first verified against that tag (`git show v1.1.5:...`); the v1.1.5-only classes (`HallUtils`, `RoundDetector`, `RoundUtils`, `CommandExportPlayers`) were each traced to their v2 successor or confirmed deliberately dropped with the schema redesign.
- Deliberately NOT merged: the ML post-processing hook trios in `RoundCsvProcessor` vs `/recalculate` (reporting semantics genuinely differ) and `TelegramLog` vs `DiscordLog` (structure matches but APIs, char limits, and escaping differ materially - a shared core is a follow-up, not a sweep-level change).
- 241 tests, 0 failures (unchanged count; suite time 1:52 → 1:22).

## [Beta 3 Update 19] - 2026-07-26

ExpElo - the AI/ML layer's first directly visible rating, shown alongside TrueElo in `/rankplayers`.

### Added

- `ExpEloDistiller`: distills the champion model's win probabilities into a scalar rating via a hand-rolled least-squares Bradley-Terry projection - for every round, the minimal equal-and-opposite adjustment to both players' running ExpElo that makes the standard rating-diff formula reproduce the champion's predicted expected score exactly. A player who didn't play a given round simply carries their ExpElo forward unchanged.
- ExpElo now gets written to the long-reserved `ExpElo` rating slot after every retrain (round upload and `/recalculate`), covering exactly the same players as TrueElo for every round - a no-op until a champion model exists.
- `/rankplayers` gains an **ExpElo** column (text table and image) shown alongside Elo (TrueElo); TrueElo remains authoritative for sorting and everywhere else.
- 5 new tests plus 2 new end-to-end pipeline assertions, 241 total passing.

### Scope note

`/infoplayer` and `/compareplayers` still show TrueElo only - extending their (more involved) per-round image/text layouts to include ExpElo is a real follow-up, deliberately deferred rather than rushed.

## [Beta 3 Update 18] - 2026-07-26

Hand-built neural-network player embeddings. **Internal only - no bot-facing changes** (`/predict`, `/lineup`, `/modelstats` surface the result automatically since both already display whichever family is champion).

### Added

- `EmbeddingNet`: a from-scratch neural net (per-player and per-hall embeddings feeding a single hidden tanh layer) with hand-derived backpropagation, checked against a numeric finite-difference gradient - no autodiff, no ML library.
- A new `GBM_EMB` model family: the embedding net's antisymmetric interaction score is appended to the existing GBM feature set, added to the walk-forward candidate grid alongside plain GBM.
- A stricter champion-selection rule: `GBM_EMB` is only crowned champion if it measurably beats plain GBM's Brier score, not merely the Glicko baseline - embeddings have to earn their added complexity.
- Trained player embeddings are now written to the long-reserved `player_profiles.playstyle_vector` column after every retrain - the first thing that table has ever stored.
- A synthetic cyclic-dominance ("rock-paper-scissors") test fixture where every player shares identical scalar stats, proving embeddings recover a pure identity-based signal that plain GBM structurally cannot see at all.
- 14 new tests, 236 total passing.

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
