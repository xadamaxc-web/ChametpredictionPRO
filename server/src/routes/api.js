const express = require('express');
const users = require('../services/users');
const lease = require('../services/lease');
const quota = require('../services/quota');
const codes = require('../services/codes');
const rounds = require('../services/rounds');
const { sign, requireAuth, requireAdmin } = require('../middleware/auth');

const router = express.Router();

function handle(err, res) {
  const status = err.status || 500;
  res.status(status).json({ error: err.message || 'error' });
}

// ---- auth ----
router.post('/auth/register', (req, res) => {
  try {
    const user = users.register(req.body.email, req.body.password);
    const token = sign(user);
    res.json({ user, token });
  } catch (e) {
    handle(e, res);
  }
});

router.post('/auth/login', (req, res) => {
  try {
    const user = users.login(req.body.email, req.body.password);
    const token = sign({ ...user, id: user.id });
    // sign needs id on object - publicUser has id
    res.json({ user, token: sign(user) });
  } catch (e) {
    handle(e, res);
  }
});

router.get('/me', requireAuth, (req, res) => {
  res.json({
    user: users.publicUser(req.user),
    quota: quota.getQuota(req.user),
    lease: lease.status(req.user.id)
  });
});

// ---- lease (1-hour engine unlock) ----
router.post('/lease/request', requireAuth, (req, res) => {
  try {
    const l = lease.requestLease(req.user.id);
    res.json({ lease: l, quota: quota.getQuota(req.user) });
  } catch (e) {
    handle(e, res);
  }
});

router.get('/lease/status', requireAuth, (req, res) => {
  res.json(lease.status(req.user.id));
});

// ---- rounds sync ----
router.post('/rounds/sync', requireAuth, (req, res) => {
  try {
    // consume one quota unit per sync batch (closed-group rate limit)
    quota.consume(req.user.id);
    const result = rounds.sync(req.user.id, req.body.rounds || [], req.body.track || []);
    res.json(result);
  } catch (e) {
    handle(e, res);
  }
});

router.get('/rounds', requireAuth, (req, res) => {
  res.json({ rounds: rounds.listForUser(req.user.id) });
});

// ---- payment codes ----
router.post('/redeem', requireAuth, (req, res) => {
  try {
    const result = codes.redeem(req.user.id, req.body.code);
    res.json(result);
  } catch (e) {
    handle(e, res);
  }
});

// ---- admin ----
router.get('/admin/stats', requireAuth, requireAdmin, (req, res) => {
  res.json(rounds.adminStats());
});

router.get('/admin/users', requireAuth, requireAdmin, (req, res) => {
  res.json({ users: users.listUsers() });
});

router.post('/admin/users/:id/plan', requireAuth, requireAdmin, (req, res) => {
  try {
    res.json({ user: users.setPlan(req.params.id, req.body.plan) });
  } catch (e) {
    handle(e, res);
  }
});

router.post('/admin/users/:id/disabled', requireAuth, requireAdmin, (req, res) => {
  try {
    res.json({ user: users.setDisabled(req.params.id, !!req.body.disabled) });
  } catch (e) {
    handle(e, res);
  }
});

router.get('/admin/codes', requireAuth, requireAdmin, (req, res) => {
  res.json({ codes: codes.listCodes() });
});

router.post('/admin/codes', requireAuth, requireAdmin, (req, res) => {
  try {
    const c = codes.createCode(req.body || {});
    res.json({ code: c });
  } catch (e) {
    handle(e, res);
  }
});

router.get('/health', (_req, res) => {
  res.json({ ok: true, version: '8.2.0', phase: 2 });
});

module.exports = router;
