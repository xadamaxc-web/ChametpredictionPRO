#!/usr/bin/env node
/** Cron entry: node scripts/nightly.js */
const learning = require('../src/services/learning');
const result = learning.runNightly();
console.log(JSON.stringify({
  at: result.suggestion.generatedAt,
  samples: result.suggestion.sampleRounds,
  accuracy: result.suggestion.modelAccuracy,
  path: result.outPath
}, null, 2));
