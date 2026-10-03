# Chamet Race Guesser (Android) — v8.3.0

Real-time overlay for the Chamet Race mini-game.
Reads cars via OCR, uses manual road selection, ranks 1-2-3 with confidence,
logs every round, and tracks win/loss over time.

## Features
- Floating overlay on top of Chamet
- One-tap screen capture
- ML Kit OCR reads car names
- Manual road picker (6 roads)
- Ranking with confidence %
- Drag-to-reorder if OCR fails
- Won/Lost logger with SQLite
- 100% on-device, no internet needed

## Requirements
- Android 10+ (API 29)
- Tested on Samsung SM-G960U

## Build via GitHub Actions
1. Push this repo to GitHub
2. Actions → "Build APK" → Run workflow
3. Download APK from Artifacts
4. Install (Settings → Install unknown apps)

## Permissions
- SYSTEM_ALERT_WINDOW (floating overlay)
- FOREGROUND_SERVICE (background capture)
- MediaProjection (screen capture)

## Usage
1. Open Chamet Race
2. Tap floating button during a round
3. App reads cars via OCR
4. Tap revealed road (6 buttons)
5. See ranking 1-2-3 + confidence
6. Drag to reorder if OCR is wrong
7. Place bet in Chamet
8. Tap 🔒 when the race starts, then WON (and tap the winning car), LOST or NO BET. The result is logged and the next game is captured automatically.

## Tech Stack
- Kotlin 1.9.20
- Android Gradle Plugin 8.2.0
- ML Kit Text Recognition v2 (on-device)
- MediaProjection API
- SQLite (Room)
- Min SDK 29, Target SDK 34

## v8.0 Session 1 changes
- Pools are matched to cars by screen column (left=1, mid=2, right=3), never by pool size (`PositionMapper`).
- One bet adviser: `OddsEngine` (Guesser now only ranks + confidence).
- Rank labels (1st/2nd/3rd) come from the car's real rank; before ranking, cards show L/M/R.
- Odds refresh re-maps pools by car name.
- Unit tests: `app/src/test/.../Session1Tests.kt`.

## v8.0 Session 2 changes
- Win chance per car = its share of the Guesser expected score (was fixed 50/30/20 by rank). Confidence now only scales the stake.
- Confidence "odds agreement" is real: does our top car have the biggest pool? With no pool data the term is dropped and the other weights rescaled (was a hardcoded 0.6).
- Payout follows the car that really won (`OddsEngine.net`). With two bets, tap WON then tap the winning car. Winner and winner position are now logged.
- Settlement is computed from the round-start balance, so WON then LOST (or double taps) can no longer apply the bet twice.
- Removed the N / R / 2 / 3 mode buttons (the `mode` column is still in the DB, now always empty).
- Tests: `Session2Tests.kt`.

## v8.0 Session 3 changes
- Overlay is 45% of screen height with 5% top padding.
- 15 .webp images in `res/drawable-nodpi` (`veh_01..09_*`, `road_*`), mapped by name in `Assets.kt` (01 Monster Truck, 02 ORV, 03 SUV, 04 Car, 05 Motorcycle, 06 Stock Car, 07 ATV, 08 Sports Car, 09 Supercar).
- Rank cards show the car image; the road picker shows the road images.
- Car chooser: long-press a car card to swap in any of the 9 vehicles when OCR got it wrong.

## v8.0 Session 4 changes
- Gold border = the 1st suggested car, blue = the 2nd; both come from the same suggestion as the text (matched by car name, rank taken from the shown ranking).
- 🔒 locks the round (race started). Locked: only WON, LOST and NO BET are active (no reorder, refresh, road/car change, close, or 🏁 until a result is tapped).
- WON arms "tap the winning car": tapping that car's image records result and winner rank in one tap (rank = its position in v1/v2/v3). Tapping a car we did not bet on logs the real winner as a loss.
- NO BET records the round with the balance unchanged.
- Red "Last round unrecorded" strip shows while locked; a new capture is blocked until a result is tapped (flag survives in Prefs).
- 💾 export moved to the top bar to make room.
- Tests: `Session4Tests.kt`.

## v8.0.1 Session 5 changes
- Removed the 🏁 Next button. Recording a result (WON + winner tap, LOST, or NO BET) now logs the round, resets the overlay and captures the next game by itself. A round is never logged without a result.
- Analytics (`AnalyticsStats.kt`, unit-tested):
  - No-bet rounds are counted separately and no longer lower the win rate or break streaks.
  - New "Winner rank" section: how often our 1st / 2nd / 3rd pick won.
  - "Winner screen position" (left / mid / right) shows the bias flag only after 30 rounds with a known winner, plus the active multipliers.
  - "By revealed road" uses whichever of R1/R2/R3 was picked (it used R1 only, so R2/R3 rounds were grouped as "???").
- Position bias now counts every round with a known winner position, including a winner tapped on a car we did not bet on (it only counted won rounds).
- Version 8.0.1 (versionCode 81). CI now runs the unit tests before building the debug APK (`ChametGuesser-8.0.1-debug`).
- Tests: `Session5Tests.kt`.

## v8.0.2 Phase 1 / Session 1 — Data safety
- Removed `fallbackToDestructiveMigration`. Real Room migration **v4 → v5** keeps every existing row.
- New round columns: `roundUuid`, `revealedSlot`, `visibleRoad`, `roadType1/2/3`, `roadPx1/2/3`, `trackWidthPx`, `poolTotalShown`, `laneCars`, `finishOrder`, `modelWinner`, `modelTimes`, `pickJson`, `paramsVersion`, `winnerSource`, `roadSource`, `captureStage`, `synced`.
- New child table `race_track` (roundUuid, timeMs, x1, x2, x3) for top-view samples.
- Automatic timestamped backup of `chamet_rounds.db` (and -wal/-shm) into app Documents before every open/migration.
- CSV export includes all new columns (`CsvColumns`).
- New rounds get a random `roundUuid` at insert.
- Version 8.0.2 (versionCode 82). Tests: `DataSafetyTests.kt`.

## v8.0.3 Phase 1 / Session 2 — Pre-race reader and manual road fallback
- Visible road is read from **banner text only** (`RoadMatcher`), never texture or color. Local OCR first, AI fallback second, manual picker last.
- `OCRHelper` returns `revealedRoadName`, `revealedRoadPosition` (R1/R2/R3), and `roadSource` (`screen` | `ai` | null).
- Unknown road after OCR+AI opens the 6-image manual picker; pick stores `roadSource=manual`. Ignored → road stays unknown, confidence = 0 / "ROAD UNKNOWN", next round still starts.
- Manual rate is logged in Prefs (`recordRoadSource` / `manualRoadRatePct`); target under ~5%.
- `WonLostLogger.logRound` persists `revealedSlot`, `visibleRoad`, `roadSource`.
- Pool sum helper for post-close check only (`OCRHelper.poolSum`).
- Tests: `Session2RoadTests.kt` (all 6 banners, OCR aliases, slot estimate, assets).
- Version 8.0.3 (versionCode 83).

## v8.0.4 Phase 1 / Session 4 — Race-strip reader
- `RaceStripReader`: finds track between checkered start/finish (screen fractions), median colour per column, split/merge segments, classify vs 6 road signatures.
- Reference colours: Desert, Highway, Expressway, Bumpy, Dirt, Potholes.
- Output: road types + px lengths + fractions + confidence; cross-check that the Session-2 visible road appears in the strip.
- Wired into capture (`OverlayService.tryApplyStrip`) and overlay (`ResultOverlay.applyStripLayout`); persisted on log (`roadType1..3`, `roadPx1..3`, `trackWidthPx`, `captureStage`).
- Low-confidence strip crops saved under Documents for training.
- Golden tests: 4:11, 4:14, 4:16 layouts within ~10 px (`Session4StripTests.kt`).
- Version 8.0.4 (versionCode 84). Note: Session 3 (state machine) is still pending.

## v8.0.5 Phase 1 / Session 5 — Top-view vehicle tracker
- `TopViewTracker`: lane k = card k; front-edge progress; up to 100 samples in `race_track`.
- Finish detection → crossing order + winner; encode as `finishOrder` / `laneCars`.
- Speed-drift check: px/s ÷ table speed should match across vehicles (flag if >5% off median).
- Sprite colour hints for Motorcycle, Supercar, Car, Sports Car, SUV, Monster Truck (ORV/ATV/Stock Car open).
- Wired into overlay (`applyLaneCars`, `addTrackSample`, `finaliseTrack`) and logger (`trackSamples` → RaceTrackDao).
- Tests: `Session5TrackTests.kt`.
- Version 8.0.5 (versionCode 85). Sessions 3 and 6 still pending for full auto race loop.

## v8.0.6 Phase 1 / Session 6 — Auto result and round record
- `FinishTimeModel`: exact T = Σ (px ÷ speed) when layout is known; golden 4:11 / 4:14 / 4:16 times.
- `AutoResult`: resolve winner from manual > track > balance > unknown; stores `winnerSource`.
- Model times + `modelWinner` computed on strip apply; logged on every round.
- Rounds with unknown winner are still saved — next capture is never blocked (`saveAutoOrUnknown`).
- Manual WON / LOST / NO BET remains the override (`winnerSource=manual`).
- Analytics report adds **Model accuracy (layout known)** and winner-source counts.
- Tests: `Session6Tests.kt`.
- Version 8.0.6 (versionCode 86). Session 3 (state machine) still pending for full 4 fps race loop.

## v8.0.7 Phase 1 / Session 7 — Finish-time model + hidden-layout simulation
- Removed speed×length scoring from `Guesser`. **T = Σ (px ÷ speed)**; lowest T wins.
- `EngineParams` + `assets/params.json` (version 8.1.0): speeds, road families, length prior, sim count.
- Pre-race: ~2000 Monte Carlo layouts (deterministic with seed) → P(win) and P(top 2).
- Post-race (layout known): exact order via `FinishTimeModel` / `Guesser.exactGuess`.
- Same seed → identical P(win); different seeds typically within a few %; target <50 ms / round.
- Golden tests 4:11 / 4:14 / 4:16 still pass. `paramsVersion` logged on every round.
- Tests: `Session7Tests.kt`.
- Version 8.0.7 (versionCode 87).

## v8.0.8 Phase 1 / Session 8 — Betting layer, local learning, backtest
- OddsEngine: EV = odds × P(win); 7% cap; half stake below 40% conf; skip when all EV < 1; never exceed cap after rounding.
- `ConfidenceCalibration`: HIGH/MEDIUM/LOW/VERY LOW bands with measured win rate and sample size from logs.
- `LocalLearner`: rebuild length prior + road families from layout rounds (≥30); apply only if holdout model accuracy is not worse; one-tap `rollback()`.
- `Backtester`: replay logged rounds with current engine vs 8.0.1-style score share; win rate + profit.
- Analytics report includes calibration table, backtest, and active params version.
- Tests: `Session8Tests.kt`.
- Version 8.0.8 (versionCode 88).

## v8.0.9 Phase 1 / Session 9 — Overlay UI/UX and controller (light)
- One-glance card (`tvGlance`): pick · confidence band · stake; model order line once layout is known (static, not per-frame).
- State strip (`tvState`): BETTING / CLOSED / RACE / FINISH / SAVED with distinct colours (`OverlayController`).
- Controller prefs: observe-only, pause capture, min confidence, stake cap %; Settings UI + learn/rollback buttons.
- Observe-only and below-threshold confidence zero all suggested stakes.
- Capture respects pause flag. Overlay stays 45% height / 5% top padding.
- Tests: `Session9Tests.kt`.
- Version 8.0.9 (versionCode 89).

## v8.1.0 Phase 1 / Session 10 — Acceptance and freeze
- **Phase 1 DONE** (local engine, offline, no server lock).
- `Phase1Gates`: min rounds, no lost rows, manual road ≤5%, auto-winner coverage, model accuracy reported, params version, winnerSource values.
- Full snapshot export (long-press 💾): `*_rounds.csv`, `*_race_track.csv`, `*_gates.txt` under Documents — seeds Phase 2.
- Analytics includes acceptance block.
- Observe-only 24h run on a spare phone is the operational proof (tester feed for Phase 3).
- Sign APK with the release keystore and back it up before distributing.
- Tests: `Session10Tests.kt`.
- Version **8.1.0** (versionCode 90).

### Phase 1 module map
| Session | Deliverable |
|---------|-------------|
| 1 | Data safety, Room v5, race_track |
| 2 | Banner road OCR + manual picker |
| 4 | Race-strip types + lengths |
| 5 | Top-view tracker |
| 6 | Auto result + model times |
| 7 | Finish-time model + Monte Carlo |
| 8 | Betting, learn, backtest |
| 9 | Overlay controller (light) |
| 10 | Acceptance gates + freeze |

Session 3 (state machine / timed capture) remains a follow-up improvement inside 8.1.x if needed; core capture is still tap-driven with auto-advance.

## v8.1.1 Session 3 — Round state machine and capture timing
- `RoundStateMachine`: IDLE → BETTING → CLOSED → RACE (~15 s, strip once + track ~4 fps) → FINISH (~6 s) → SAVED.
- Pause / resume freezes the clock (calls, screen off, observe pause pref).
- `OverlayService` runs a 200 ms tick loop: auto phase transitions, strip capture once, track samples, finish check + auto-save.
- Floating button: 1st tap opens betting + full OCR; 2nd tap closes betting; further taps sample track while racing.
- Strip visibility advances BETTING/CLOSED → RACE. Tracker + `winnerSource=track` can now receive data.
- Tests: `Session3Tests.kt` (controllable clock).
- Version 8.1.1 (versionCode 91).

## v8.1.2 Patch top 4 (review fixes)
1. **Tests compile** — `DataSafetyTests` `HEADERS.indexOf` fixed.
2. **`params.json` loaded** — `ParamsLoader` parses assets (or `params_learned.json`); applied in MainActivity + OverlayService. `schema_version` field added.
3. **Honest gates** — fail-closed: insufficient samples fail; manual-only winners fail auto-winner gate; model accuracy needs ≥10 pairs.
4. **Stake-cap %** — `Prefs.stakeCapPercent` flows into `OddsEngine.compute` (1–20%, default 7).
- Learned params saved to disk on Settings → Learn; rollback clears learned file.
- Tests: `ParamsLoaderTests.kt`, updated `Session10Tests.kt`.
- Version 8.1.2 (versionCode 92).


## v8.1.3 Patch all remaining (review follow-ups)
- **OCR Session 2:** AI road fallback uses **banner crop** (top 35%), not full screenshot; road-only prompt; fallback scan **breaks** on first match; `estimateSlot` returns **null** when center unknown (no forced R2).
- **Tracker Session 5:** ORV / ATV / Stock Car sprite colour hints; `sampleLaneProgress` from strip pixels; race samples no longer pure time proxies.
- **Math Session 7:** golden tolerance ±0.05; seed P(win) drift <2%; sim timing budget 250 ms; length prior = **measured goldens only**.
- **Data Session 1:** pre-migration backups **pruned** to last 5.
- **Params:** `params.json` length prior synced to measured layouts.
- Version 8.1.3 (versionCode 93).

## v8.1.4 New plan Sessions 1 leftovers + Session 2
### Session 1 leftovers
- `position_bias` in `params.json` + `EngineParams` + ParamsLoader parse/toJson.
- Rollback snapshot on disk (`params_rollback.json`); learn saves rollback before apply; Settings rollback restores from disk.
- `ParamsLoader.loadIntoEngineLogged` logs fallback to DEFAULT.
- Session 7: 4000 sims for seed drift &lt;1% P(win); timing printed.
### Session 2
- `CountdownReader`: parse "27s" / crop fractions; map seconds → phase.
- `RoundStateMachine.applyCountdown` for hands-free transitions.
- Prefs `autoCapture` + Settings checkbox; OverlayService polls countdown ~1/s when auto.
- Blind `onRecapture` after settle removed — next round from state machine.
- Manual road prompt only when ≤6s and road unknown.
- Low-confidence strip crops pruned (keep 20).
- Tests: `CountdownReaderTests.kt`.
- **Unverified without device:** 20 live no-tap rounds, real countdown crop position, CI green on GitHub.
- Version 8.1.4 (versionCode 94).

## v8.1.5 New plan Sessions 3 + 4 + 5 (code freeze scaffold)
### Session 3
- `RaceLoopController`: strip once + recheck ~1s + ~4 fps track work.
- Finish path + strip low-conf **crop → AI** (best-effort).
- Tracker pixel sampling + sprite hints (still not true templates).
### Session 4
- `FinishScreenMatcher` (OCR text near WIN; pixel templates **unverified**).
- `DataIntegrity`: UUID backfill, measured length prior, migration SQL checks.
- Launch-time `roundUuid` backfill for old rows.
- LocalLearner prefers measured strip lengths from logs.
### Session 5
- `ResourceProbe` memory samples during service tick.
- `PHASE1_ACCEPTANCE.md` report template.
- Release signingConfig **commented** in Gradle (fill env locally; never commit secrets).
- Tests: `Session345Tests.kt`.
### Unverified (needs phone + assets)
- 20 races 95% track order, real Dirt/Potholes goldens, instrumented Room migration, 24h observe, battery, signed APK.
- Version **8.1.5** (versionCode 95).

## v8.2.0 Phase 2 — Server & Control (Sessions 11–16)
- **server/** Node.js Express app: auth, rounds sync, 1h lease, Free/Pro quota, payment codes, admin UI.
- Android `ServerClient` + Prefs + Settings (login, lease, redeem).
- Engine lock only when **server mode is enabled**; offline Phase 1 unchanged.
- Sync after each logged round (best-effort).
- Server tests: `server/test/api.test.js` (`npm test`).
- Version **8.2.0** (versionCode 100).


## v8.3.0 Phase 3 — Growth & Launch (Sessions 17–21)
- **Nightly learning** (`scripts/nightly.js`, `/api/learning/*`): accuracy + length prior suggestion from synced rounds.
- **Landing page** `/` with live results + waitlist.
- **Live feed** `GET /api/public/live` (anonymized).
- **Referrals** code per user, apply on signup path, +10 bonus quota each.
- **Closed beta** waitlist + launch flags (`/api/public/waitlist`, admin launch config).
- Admin UI moved to `/admin`.
- Server tests: 9/9 (Phase 2 + 3).
- App version **8.3.0** (versionCode 110).

## v8.3.1 Audit hard-fix patch
- XSS escaped on landing + admin; admin creds not pre-filled.
- Live feed sanitizes fields; only track/screen/balance/manual sources.
- Lease required for sync (server); engineUnlocked gates recomputeOdds.
- Countdown uses ML Kit text-only (no per-second AI).
- Sync payload includes layout/track; markSynced; pickJson param.
- LocalLearner rejects no-op / weak holdout; Backtester no look-ahead.
- Pro unlimited (-1); planExpiresAt enforced; closed-beta blocks open register.
- Nightly learning writes params_candidate.json when lengths exist.
- Cleartext traffic allowed for local HTTP dev.
- Server tests 9/9.
