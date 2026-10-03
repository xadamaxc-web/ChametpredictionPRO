/**
 * Session 17 — nightly self-learning from synced rounds.
 * Emits a params candidate (length prior + version) under data/params_suggested.json
 * and data/params_candidate.json for optional admin publish.
 */
const store = require('../db/store');
const fs = require('fs');
const path = require('path');

function allRoundPayloads() {
  const data = store.read();
  return data.rounds.map((r) => r.payload || {}).filter((p) => p && typeof p === 'object');
}

function modelAccuracy(payloads) {
  let hits = 0, known = 0;
  for (const p of payloads) {
    const mw = p.modelWinner;
    const w = p.winner;
    if (!mw || !w) continue;
    known++;
    if (String(mw).toLowerCase() === String(w).toLowerCase()) hits++;
  }
  return { hits, known, pct: known ? (hits * 100) / known : 0 };
}

function lengthPriorFromPayloads(payloads) {
  const out = [];
  for (const p of payloads) {
    for (let i = 1; i <= 3; i++) {
      const t = p['roadType' + i];
      const px = p['roadPx' + i];
      if (t && px != null && Number(px) > 0) out.push({ type: String(t), px: Number(px) });
    }
  }
  return out;
}

function runNightly() {
  const payloads = allRoundPayloads();
  const accuracy = modelAccuracy(payloads);
  const lengths = lengthPriorFromPayloads(payloads);
  const version = '8.3.learn.' + payloads.length + '.' + Date.now().toString(36);
  const candidate = {
    schema_version: 1,
    version,
    simulations: 2000,
    defaultSeed: 42,
    trackWidthPx: 990,
    segmentCountMin: 2,
    segmentCountMax: 3,
    lengthPriorPx: lengths.slice(0, 200),
    position_bias: { p1: 1.0, p2: 1.0, p3: 1.0 },
    generatedAt: new Date().toISOString(),
    sampleRounds: payloads.length,
    modelAccuracy: accuracy,
    notes: [
      'Candidate params from server nightly job.',
      'Admin must review before publishing to clients.',
      'Speeds not auto-tuned.'
    ]
  };
  // If no measured lengths, still write report-only suggestion
  const suggestion = {
    generatedAt: candidate.generatedAt,
    sampleRounds: payloads.length,
    modelAccuracy: accuracy,
    lengthPriorCount: lengths.length,
    candidateVersion: version,
    hasParams: lengths.length >= 4
  };

  const data = store.read();
  if (!data.learningRuns) data.learningRuns = [];
  data.learningRuns.push({
    at: candidate.generatedAt,
    sampleRounds: payloads.length,
    accuracyPct: accuracy.pct,
    lengthPriorCount: lengths.length
  });
  if (data.learningRuns.length > 30) data.learningRuns = data.learningRuns.slice(-30);
  store.write(data);

  const dir = store.DATA_DIR;
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'params_suggested.json'), JSON.stringify(suggestion, null, 2));
  if (lengths.length >= 4) {
    fs.writeFileSync(path.join(dir, 'params_candidate.json'), JSON.stringify(candidate, null, 2));
  }
  return { suggestion, candidate: lengths.length >= 4 ? candidate : null, outPath: path.join(dir, 'params_suggested.json') };
}

function latestSuggestion() {
  const dir = store.DATA_DIR;
  const outPath = path.join(dir, 'params_suggested.json');
  if (!fs.existsSync(outPath)) return null;
  return JSON.parse(fs.readFileSync(outPath, 'utf8'));
}

function latestCandidate() {
  const dir = store.DATA_DIR;
  const p = path.join(dir, 'params_candidate.json');
  if (!fs.existsSync(p)) return null;
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

function history() {
  return store.read().learningRuns || [];
}

module.exports = { runNightly, latestSuggestion, latestCandidate, history, modelAccuracy };
