const crypto = require('crypto');
const config = require('../config');
const store = require('../db/store');

function b64url(buf) {
  return Buffer.from(buf).toString('base64url');
}

function sign(user) {
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({
    sub: user.id, email: user.email, role: user.role, plan: user.plan,
    exp: Math.floor(Date.now() / 1000) + 30 * 86400
  }));
  const data = header + '.' + payload;
  const sig = crypto.createHmac('sha256', config.jwtSecret).update(data).digest('base64url');
  return data + '.' + sig;
}

function verify(token) {
  const parts = String(token || '').split('.');
  if (parts.length !== 3) return null;
  const data = parts[0] + '.' + parts[1];
  const expect = crypto.createHmac('sha256', config.jwtSecret).update(data).digest('base64url');
  if (parts[2] !== expect) return null;
  try {
    const body = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
    if (body.exp && body.exp < Math.floor(Date.now() / 1000)) return null;
    return body;
  } catch (e) { return null; }
}

function requireAuth(req, res, next) {
  const hdr = req.headers.authorization || '';
  const token = hdr.startsWith('Bearer ') ? hdr.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'missing_token' });
  const payload = verify(token);
  if (!payload) return res.status(401).json({ error: 'invalid_token' });
  const data = store.read();
  const user = data.users.find((u) => u.id === payload.sub);
  if (!user || user.disabled) return res.status(401).json({ error: 'invalid_user' });
  req.user = user;
  next();
}

function requireAdmin(req, res, next) {
  if (!req.user || req.user.role !== 'admin') return res.status(403).json({ error: 'admin_only' });
  next();
}

module.exports = { sign, verify, requireAuth, requireAdmin };
