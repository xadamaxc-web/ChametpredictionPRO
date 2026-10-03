const { uuid } = require('../util');
const store = require('../db/store');

/**
 * Payment-by-code: admin creates codes that grant pro for N days (or forever).
 */
function createCode({ plan = 'pro', days = 30, maxUses = 1, note = '' } = {}) {
  return store.update((data) => {
    const code = {
      id: uuid(),
      code: randomCode(),
      plan,
      days,
      maxUses,
      uses: 0,
      note,
      createdAt: new Date().toISOString(),
      disabled: false
    };
    data.codes.push(code);
    return code;
  });
}

function listCodes() {
  return store.read().codes;
}

function redeem(userId, rawCode) {
  const codeStr = String(rawCode || '').trim().toUpperCase();
  if (!codeStr) {
    const err = new Error('missing_code');
    err.status = 400;
    throw err;
  }
  return store.update((data) => {
    const c = data.codes.find((x) => x.code === codeStr && !x.disabled);
    if (!c) {
      const err = new Error('invalid_code');
      err.status = 404;
      throw err;
    }
    if (c.uses >= c.maxUses) {
      const err = new Error('code_exhausted');
      err.status = 410;
      throw err;
    }
    const u = data.users.find((x) => x.id === userId);
    if (!u) {
      const err = new Error('not_found');
      err.status = 404;
      throw err;
    }
    c.uses += 1;
    u.plan = c.plan;
    if (c.days > 0) {
      u.planExpiresAt = new Date(Date.now() + c.days * 86400000).toISOString();
    } else {
      u.planExpiresAt = null;
    }
    data.audit.push({
      at: new Date().toISOString(),
      type: 'redeem',
      userId,
      code: c.code,
      plan: c.plan
    });
    return { plan: u.plan, planExpiresAt: u.planExpiresAt || null, code: c.code };
  });
}

function randomCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let s = '';
  for (let i = 0; i < 8; i++) s += alphabet[Math.floor(Math.random() * alphabet.length)];
  return s;
}

module.exports = { createCode, listCodes, redeem };
