/**
 * Session 19 — anonymized live results (sanitized).
 */
const store = require('../db/store');

function scrub(s, max = 40) {
  if (s == null) return null;
  return String(s)
    .replace(/[<>&"'`]/g, '')
    .replace(/[^\w\s.\-:%+/]/g, '')
    .slice(0, max);
}

function publicFeed(limit = 30) {
  const data = store.read();
  // Prefer verified rows: winnerSource track|screen|balance only
  const rows = data.rounds
    .filter((r) => {
      const p = r.payload || {};
      const src = p.winnerSource;
      return src === 'track' || src === 'screen' || src === 'balance' || src === 'manual';
    })
    .sort((a, b) => (b.syncedAt || '').localeCompare(a.syncedAt || ''))
    .slice(0, limit)
    .map((r) => {
      const p = r.payload || {};
      const winner = scrub(p.winner);
      const modelWinner = scrub(p.modelWinner);
      return {
        at: r.syncedAt,
        road: scrub(p.r1 || p.visibleRoad),
        winner,
        modelWinner,
        modelHit:
          winner && modelWinner
            ? winner.toLowerCase() === modelWinner.toLowerCase()
            : null
      };
    });
  const known = rows.filter((x) => x.modelHit !== null);
  const hits = known.filter((x) => x.modelHit).length;
  return {
    updatedAt: new Date().toISOString(),
    count: rows.length,
    modelAccuracyPct: known.length ? (hits * 100) / known.length : null,
    results: rows
  };
}

module.exports = { publicFeed };
