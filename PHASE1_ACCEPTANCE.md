# Phase 1 acceptance report (template)

**App version:** 8.1.5  
**Date:** _fill in_  
**Device:** _fill in_  
**Mode:** observe-only / auto-capture  

## Gates (from Analytics or snapshot `*_gates.txt`)

| Gate | Result | Detail |
|------|--------|--------|
| min_rounds | PASS / FAIL | |
| no_lost_rounds | PASS / FAIL | |
| manual_road_rate | PASS / FAIL | |
| auto_winner_rate | PASS / FAIL | |
| model_accuracy_reported | PASS / FAIL | |
| params_version | PASS / FAIL | |
| winner_source_values | PASS / FAIL | |

## Live run metrics (must be measured)

| Metric | Value | Notes |
|--------|-------|-------|
| Rounds logged | | |
| Stuck states | | |
| Manual road rate | | |
| Auto winner vs manual | | need ≥50 with both |
| Model accuracy | | layout-known rounds |
| Max PSS (kb) | | ResourceProbe |
| Battery drop (1h) | | **unverified in CI** |
| Strip width error (px) | | need real screenshots |
| Track order match | | need 20 races |

## Signed APK

- [ ] `release` signingConfig configured  
- [ ] Keystore backed up offline  
- [ ] APK signed and sideloaded once  

## Snapshot

- [ ] `chamet_snapshot_*_rounds.csv`  
- [ ] `chamet_snapshot_*_race_track.csv`  
- [ ] `chamet_snapshot_*_gates.txt`  

## Honest summary

_Any FAIL stays FAIL. Do not claim Phase 1 complete until every gate passes on real data or failures are listed here._
