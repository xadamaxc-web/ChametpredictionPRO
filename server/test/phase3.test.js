const { describe, it, before } = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');
const fs = require('fs');
const os = require('os');

process.env.CHAMET_DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'chamet-p3-'));
process.env.JWT_SECRET = 'test-secret-p3';

const users = require('../src/services/users');
const rounds = require('../src/services/rounds');
const learning = require('../src/services/learning');
const referrals = require('../src/services/referrals');
const live = require('../src/services/live');
const beta = require('../src/services/beta');

describe('Phase 3 services', () => {
  let a, b;

  before(() => {
    a = users.register('ref@test.com', 'password1', 'user');
    b = users.register('new@test.com', 'password1', 'user');
  });

  it('referral code and apply', () => {
    const code = referrals.ensureCode(a.id);
    assert.ok(code.startsWith('CG'));
    const r = referrals.applyReferral(b.id, code);
    assert.equal(r.bonusQuota, 10);
    assert.throws(() => referrals.applyReferral(b.id, code)); // already
  });

  it('nightly learning from synced rounds', () => {
    rounds.sync(a.id, [
      { roundUuid: 'x1', winner: 'ATV', modelWinner: 'ATV', r1: 'Desert', roadType1: 'Desert', roadPx1: 870, winnerSource: 'track' },
      { roundUuid: 'x2', winner: 'Car', modelWinner: 'ATV', r1: 'Highway', roadType1: 'Highway', roadPx1: 400, winnerSource: 'manual' }
    ]);
    const out = learning.runNightly();
    assert.ok(out.suggestion.sampleRounds >= 2);
    assert.ok(out.candidate == null || out.candidate.version);
    assert.ok(out.suggestion.modelAccuracy.known >= 2);
    assert.ok(fs.existsSync(out.outPath));
  });

  it('live public feed anonymized', () => {
    const feed = live.publicFeed(10);
    assert.ok(Array.isArray(feed.results));
    assert.ok(feed.count >= 1);
  });

  it('waitlist and launch config', () => {
    const j = beta.joinWaitlist('beta@test.com', 'please');
    assert.equal(j.status, 'joined');
    assert.equal(beta.joinWaitlist('beta@test.com').status, 'already');
    const cfg = beta.setLaunchConfig({ publicLaunch: false, closedBeta: true, message: 'soon' });
    assert.equal(cfg.message, 'soon');
    assert.equal(beta.listWaitlist().length >= 1, true);
  });
});
