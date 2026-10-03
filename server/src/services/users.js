const crypto = require('crypto');
const { uuid } = require('../util');
const store = require('../db/store');

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(password, salt, 32).toString('hex');
  return salt + ':' + hash;
}

function verifyPassword(password, stored) {
  const [salt, hash] = String(stored).split(':');
  if (!salt || !hash) return false;
  const check = crypto.scryptSync(password, salt, 32).toString('hex');
  try {
    return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(check, 'hex'));
  } catch (e) {
    return false;
  }
}

function publicUser(u) {
  return {
    id: u.id,
    email: u.email,
    role: u.role,
    plan: u.plan,
    createdAt: u.createdAt,
    disabled: !!u.disabled
  };
}

function register(email, password, role) {
  role = role || 'user';
  email = String(email || '').trim().toLowerCase();
  if (!email || !email.includes('@') || !email.includes('.') || !password || password.length < 6) {
    const err = new Error('invalid_credentials');
    err.status = 400;
    throw err;
  }
  // Strip HTML-ish characters from email
  email = email.replace(/[<>"'`]/g, '');
  return store.update((data) => {
    if (data.users.some((u) => u.email === email)) {
      const err = new Error('email_taken');
      err.status = 409;
      throw err;
    }
    const user = {
      id: uuid(),
      email,
      passwordHash: hashPassword(password),
      role,
      plan: role === 'admin' ? 'pro' : 'free',
      createdAt: new Date().toISOString(),
      disabled: false,
      daily: { date: '', count: 0 }
    };
    data.users.push(user);
    data.audit.push({ at: user.createdAt, type: 'register', userId: user.id, email });
    return publicUser(user);
  });
}

function login(email, password) {
  email = String(email || '').trim().toLowerCase();
  const data = store.read();
  const user = data.users.find((u) => u.email === email);
  if (!user || !verifyPassword(password, user.passwordHash)) {
    const err = new Error('bad_login');
    err.status = 401;
    throw err;
  }
  if (user.disabled) {
    const err = new Error('disabled');
    err.status = 403;
    throw err;
  }
  return publicUser(user);
}

function listUsers() {
  return store.read().users.map(publicUser);
}

function setPlan(userId, plan) {
  return store.update((data) => {
    const u = data.users.find((x) => x.id === userId);
    if (!u) { const err = new Error('not_found'); err.status = 404; throw err; }
    if (plan !== 'free' && plan !== 'pro') { const err = new Error('bad_plan'); err.status = 400; throw err; }
    u.plan = plan;
    data.audit.push({ at: new Date().toISOString(), type: 'set_plan', userId, plan });
    return publicUser(u);
  });
}

function setDisabled(userId, disabled) {
  return store.update((data) => {
    const u = data.users.find((x) => x.id === userId);
    if (!u) { const err = new Error('not_found'); err.status = 404; throw err; }
    u.disabled = !!disabled;
    return publicUser(u);
  });
}

module.exports = { register, login, listUsers, setPlan, setDisabled, publicUser };
