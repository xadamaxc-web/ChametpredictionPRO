/**
 * Session 20 — referral codes.
 * Each user can have a referral code; redeems grant both parties a small quota boost flag.
 */
const { uuid } = require('../util');
const store = require('../db/store');

function ensureCode(userId) {
  return store.update((data) => {
    const u = data.users.find((x) => x.id === userId);
    if (!u) {
      const err = new Error('not_found');
      err.status = 404;
      throw err;
    }
    if (u.referralCode) return u.referralCode;
    let code;
    do {
      code = 'CG' + Math.random().toString(36).slice(2, 8).toUpperCase();
    } while (data.users.some((x) => x.referralCode === code));
    u.referralCode = code;
    u.referralCount = u.referralCount || 0;
    return code;
  });
}

function applyReferral(newUserId, code) {
  code = String(code || '').trim().toUpperCase();
  if (!code) {
    const err = new Error('missing_code');
    err.status = 400;
    throw err;
  }
  return store.update((data) => {
    const referrer = data.users.find((x) => x.referralCode === code);
    if (!referrer) {
      const err = new Error('invalid_referral');
      err.status = 404;
      throw err;
    }
    if (referrer.id === newUserId) {
      const err = new Error('self_referral');
      err.status = 400;
      throw err;
    }
    const newbie = data.users.find((x) => x.id === newUserId);
    if (!newbie) {
      const err = new Error('not_found');
      err.status = 404;
      throw err;
    }
    if (newbie.referredBy) {
      const err = new Error('already_referred');
      err.status = 409;
      throw err;
    }
    newbie.referredBy = referrer.id;
    referrer.referralCount = (referrer.referralCount || 0) + 1;
    // soft reward: mark bonusQuota days
    newbie.bonusQuota = (newbie.bonusQuota || 0) + 10;
    referrer.bonusQuota = (referrer.bonusQuota || 0) + 10;
    data.audit.push({
      at: new Date().toISOString(),
      type: 'referral',
      referrerId: referrer.id,
      newUserId
    });
    return { referrerEmail: referrer.email, bonusQuota: 10 };
  });
}

function stats(userId) {
  const data = store.read();
  const u = data.users.find((x) => x.id === userId);
  if (!u) return null;
  return {
    referralCode: u.referralCode || null,
    referralCount: u.referralCount || 0,
    bonusQuota: u.bonusQuota || 0,
    referredBy: u.referredBy || null
  };
}

module.exports = { ensureCode, applyReferral, stats };
