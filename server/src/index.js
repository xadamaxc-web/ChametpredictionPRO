const http = require('http');
const fs = require('fs');
const path = require('path');
const config = require('./config');
const users = require('./services/users');
const lease = require('./services/lease');
const quota = require('./services/quota');
const codes = require('./services/codes');
const rounds = require('./services/rounds');
const learning = require('./services/learning');
const referrals = require('./services/referrals');
const live = require('./services/live');
const beta = require('./services/beta');
const { sign, requireAuth, requireAdmin } = require('./middleware/auth');
const store = require('./db/store');

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw) return resolve({});
      try { resolve(JSON.parse(raw)); } catch (e) { reject(e); }
    });
    req.on('error', reject);
  });
}

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'
  });
  res.end(body);
}

function wrapAuth(handler, admin) {
  return async (req, res) => {
    const fake = {
      headers: req.headers,
      user: null,
      body: req.body,
      params: req.params || {}
    };
    const fakeRes = {
      status(code) {
        this._code = code;
        return this;
      },
      json(obj) {
        send(res, this._code || 200, obj);
      },
      _code: 200
    };
    await new Promise((resolve) => {
      requireAuth(fake, fakeRes, () => {
        if (admin) {
          requireAdmin(fake, fakeRes, () => {
            req.user = fake.user;
            resolve(true);
          });
        } else {
          req.user = fake.user;
          resolve(true);
        }
      });
    });
    if (!req.user) return;
    return handler(req, res);
  };
}

async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'
    });
    return res.end();
  }

  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;

  // static admin
  if (req.method === 'GET' && (p === '/admin' || p === '/admin.html')) {
    const file = path.join(__dirname, '../public/index.html');
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end(fs.readFileSync(file));
  }
  if (req.method === 'GET' && (p === '/' || p === '/index.html' || p === '/landing' || p === '/landing.html')) {
    const file = path.join(__dirname, '../public/landing.html');
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end(fs.readFileSync(file));
  }

  try {
    if (req.method === 'POST' || req.method === 'PUT') {
      req.body = await readBody(req);
    } else {
      req.body = {};
    }

    if (p === '/api/health' && req.method === 'GET') {
      return send(res, 200, { ok: true, version: '8.3.0', phase: 3 });
    }
    if (p === '/api/public/live' && req.method === 'GET') {
      return send(res, 200, live.publicFeed(40));
    }
    if (p === '/api/public/launch' && req.method === 'GET') {
      return send(res, 200, beta.launchConfig());
    }
    if (p === '/api/public/waitlist' && req.method === 'POST') {
      return send(res, 200, beta.joinWaitlist(req.body.email, req.body.note));
    }

    if (p === '/api/auth/register' && req.method === 'POST') {
      const launch = beta.launchConfig();
      if (launch.closedBeta && !launch.publicLaunch) {
        // allow only if invite code matches a redeemable pro code OR waitlist invited
        const invite = req.body.invite;
        if (!invite) {
          return send(res, 403, { error: 'closed_beta', message: launch.message });
        }
      }
      const user = users.register(req.body.email, req.body.password);
      if (req.body.referral) {
        try { referrals.applyReferral(user.id, req.body.referral); } catch (_) {}
      }
      return send(res, 200, { user, token: sign(user) });
    }
    if (p === '/api/auth/login' && req.method === 'POST') {
      const user = users.login(req.body.email, req.body.password);
      return send(res, 200, { user, token: sign(user) });
    }

    // auth required routes
    const needAuth = p.startsWith('/api/') && p !== '/api/health' &&
      !p.startsWith('/api/auth/') && !p.startsWith('/api/public/');
    if (needAuth) {
      const fake = { headers: req.headers, user: null };
      const fakeRes = {
        _code: 200,
        status(c) { this._code = c; return this; },
        json(o) { send(res, this._code, o); }
      };
      let ok = false;
      requireAuth(fake, fakeRes, () => { ok = true; });
      if (!ok) return;
      req.user = fake.user;
      if (p.startsWith('/api/admin/')) {
        let aok = false;
        requireAdmin(fake, fakeRes, () => { aok = true; });
        if (!aok) return;
      }
    }

    if (p === '/api/me' && req.method === 'GET') {
      return send(res, 200, {
        user: users.publicUser(req.user),
        quota: quota.getQuota(req.user),
        lease: lease.status(req.user.id)
      });
    }
    if (p === '/api/lease/request' && req.method === 'POST') {
      const l = lease.requestLease(req.user.id);
      return send(res, 200, { lease: l, quota: quota.getQuota(req.user) });
    }
    if (p === '/api/lease/status' && req.method === 'GET') {
      return send(res, 200, lease.status(req.user.id));
    }
    if (p === '/api/rounds/sync' && req.method === 'POST') {
      const config = require('./config');
      if (config.requireLeaseForSync) {
        const st = lease.status(req.user.id);
        if (!st.active) return send(res, 403, { error: 'lease_required' });
      }
      quota.consume(req.user.id);
      const result = rounds.sync(req.user.id, req.body.rounds || [], req.body.track || []);
      return send(res, 200, result);
    }
    if (p === '/api/rounds' && req.method === 'GET') {
      return send(res, 200, { rounds: rounds.listForUser(req.user.id) });
    }
    if (p === '/api/redeem' && req.method === 'POST') {
      return send(res, 200, codes.redeem(req.user.id, req.body.code));
    }
    if (p === '/api/admin/stats' && req.method === 'GET') {
      return send(res, 200, rounds.adminStats());
    }
    if (p === '/api/admin/users' && req.method === 'GET') {
      return send(res, 200, { users: users.listUsers() });
    }
    if (p.startsWith('/api/admin/users/') && p.endsWith('/plan') && req.method === 'POST') {
      const id = p.split('/')[4];
      return send(res, 200, { user: users.setPlan(id, req.body.plan) });
    }
    if (p.startsWith('/api/admin/users/') && p.endsWith('/disabled') && req.method === 'POST') {
      const id = p.split('/')[4];
      return send(res, 200, { user: users.setDisabled(id, !!req.body.disabled) });
    }
    if (p === '/api/admin/codes' && req.method === 'GET') {
      return send(res, 200, { codes: codes.listCodes() });
    }
    if (p === '/api/admin/codes' && req.method === 'POST') {
      return send(res, 200, { code: codes.createCode(req.body || {}) });
    }

    // Phase 3
    if (p === '/api/referrals/code' && req.method === 'GET') {
      return send(res, 200, { code: referrals.ensureCode(req.user.id), stats: referrals.stats(req.user.id) });
    }
    if (p === '/api/referrals/apply' && req.method === 'POST') {
      return send(res, 200, referrals.applyReferral(req.user.id, req.body.code));
    }
    if (p === '/api/learning/latest' && req.method === 'GET') {
      return send(res, 200, { suggestion: learning.latestSuggestion(), candidate: learning.latestCandidate(), history: learning.history() });
    }
    if (p === '/api/learning/run' && req.method === 'POST') {
      // admin only already if path under admin - force check
      if (req.user.role !== 'admin') return send(res, 403, { error: 'admin_only' });
      return send(res, 200, learning.runNightly());
    }
    if (p === '/api/admin/waitlist' && req.method === 'GET') {
      return send(res, 200, { waitlist: beta.listWaitlist() });
    }
    if (p === '/api/admin/launch' && req.method === 'POST') {
      return send(res, 200, { launch: beta.setLaunchConfig(req.body || {}) });
    }
    if (p === '/api/admin/launch' && req.method === 'GET') {
      return send(res, 200, beta.launchConfig());
    }

    send(res, 404, { error: 'not_found' });
  } catch (e) {
    send(res, e.status || 500, { error: e.message || 'error' });
  }
}

// bootstrap admin
(function bootstrap() {
  const data = store.read();
  if (data.users.length === 0) {
    try {
      users.register(config.adminBootstrapEmail, config.adminBootstrapPassword, 'admin');
      console.log('Bootstrapped admin', config.adminBootstrapEmail);
    } catch (e) {
      console.warn('bootstrap', e.message);
    }
  }
})();

http.createServer(handler).listen(config.port, () => {
  console.log('Chamet Guesser server 8.3.0 on :' + config.port);
});
