const { describe, it, before } = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');
const fs = require('fs');
const os = require('os');

// Isolate store
process.env.CHAMET_DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'chamet-test-'));
process.env.JWT_SECRET = 'test-secret';

const users = require('../src/services/users');
const lease = require('../src/services/lease');
const quota = require('../src/services/quota');
const codes = require('../src/services/codes');
const rounds = require('../src/services/rounds');
const { sign } = require('../src/middleware/auth');

describe('Phase 2 services', () => {
  let user;

  before(() => {
    user = users.register('u@test.com', 'password1', 'user');
  });

  it('registers and logs in', () => {
    const u = users.login('u@test.com', 'password1');
    assert.equal(u.email, 'u@test.com');
    assert.equal(u.plan, 'free');
    const token = sign(u);
    assert.ok(token.length > 20);
  });

  it('issues one-hour lease', () => {
    const l = lease.requestLease(user.id);
    assert.ok(l.id);
    const st = lease.status(user.id);
    assert.equal(st.active, true);
    assert.ok(st.remainingMs > 0);
  });

  it('enforces free quota', () => {
    const data = require('../src/db/store').read();
    const u = data.users.find((x) => x.id === user.id);
    // reset daily
    u.daily = { date: new Date().toISOString().slice(0, 10), count: 0 };
    require('../src/db/store').write(data);
    for (let i = 0; i < 20; i++) quota.consume(user.id);
    assert.throws(() => quota.consume(user.id), /quota_exceeded/);
  });

  it('redeems pro code', () => {
    const c = codes.createCode({ plan: 'pro', days: 7, maxUses: 1 });
    const r = codes.redeem(user.id, c.code);
    assert.equal(r.plan, 'pro');
  });

  it('syncs rounds idempotently', () => {
    const a = rounds.sync(user.id, [{ roundUuid: 'r1', winner: 'ATV' }]);
    assert.equal(a.inserted, 1);
    const b = rounds.sync(user.id, [{ roundUuid: 'r1', winner: 'ATV' }]);
    assert.equal(b.inserted, 0);
    assert.equal(b.updated, 1);
  });
});
